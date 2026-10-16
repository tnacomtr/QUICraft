// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.MessageDigest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.bridge.GameHost;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.bridge.status.StatusAdvertising;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.FallbackReport;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.discovery.QuicPort;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.QuicraftFabric;

/**
 * QUIC on a dedicated server (docs/platforms/fabric.md): a UDP listener next to vanilla's TCP
 * listener, on one of the game's own event loops, feeding each QUIC stream into the game's own
 * channel initializer through the bridge. The TCP listener is never changed; the only thing
 * added to TCP channels is the advertisement in status responses.
 */
public final class ServerQuic {
    private static volatile @Nullable ServerQuic current;
    /** Vanilla's initializer wrapped with the advertiser; also what QUIC channels get. */
    private static volatile @Nullable ChannelInitializer<Channel> initializer;
    /** What a {@code /3} connection's join needs from the running server (docs/protocol.md 12.7). */
    private static volatile @Nullable MinecraftServer minecraft;
    private static volatile byte @Nullable [] fingerprintDigest;

    private final QuicServer server;
    private final Advertisement advertisement;
    private final FallbackReport.Log fallbackLog;
    /** UDP sockets the listener bound, for the startup line. */
    private final int sockets;

    private ServerQuic(QuicServer server, Advertisement advertisement, int port, int sockets) {
        this.server = server;
        this.advertisement = advertisement;
        this.fallbackLog = new FallbackReport.Log(port);
        this.sockets = sockets;
    }

    private static final java.util.concurrent.atomic.AtomicInteger FALLBACK_REPORTS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The advertisement to add to status responses, or null when QUIC isn't running. */
    public static @Nullable Advertisement advertisement() {
        ServerQuic s = current;
        return s != null ? s.advertisement : null;
    }

    /** Streams passed to the game on 0-RTT data so far (docs/protocol.md §8); 0 when QUIC isn't running. */
    public static long earlyReleases() {
        ServerQuic s = current;
        return s != null ? s.server.earlyReleases() : 0;
    }

    /**
     * Hook: vanilla's TCP child handler. Returns it wrapped so status responses carry the
     * advertisement once QUIC runs. On any failure, returns vanilla's handler unchanged.
     */
    @SuppressWarnings("unchecked")
    public static ChannelHandler wrapInitializer(MinecraftServer minecraft, ChannelHandler vanilla) {
        try {
            Hooks.enter("server initializer");
            if (!minecraft.isDedicatedServer() || !(vanilla instanceof ChannelInitializer)) {
                return vanilla;
            }
            ChannelInitializer<Channel> wrapped = new Advertising((ChannelInitializer<Channel>) vanilla);
            initializer = wrapped;
            return wrapped;
        } catch (Throwable t) {
            Hooks.failed("server initializer", t);
            return vanilla;
        }
    }

    /** Hook: the TCP listener is bound. Starts QUIC; never throws. */
    public static void start(MinecraftServer minecraft, @Nullable InetAddress address, int port) {
        try {
            Hooks.enter("server start");
            if (!(minecraft instanceof DedicatedServer dedicated) || current != null) {
                return;
            }
            ChannelInitializer<Channel> init = initializer;
            if (init == null) {
                QuicraftFabric.LOG.warn("QUICraft: the server's channel initializer wasn't found; TCP only");
                return;
            }
            Path dir = QuicraftFabric.configDir();
            ServerSettings settings = ServerSettings.load(dir);
            if (!settings.enabled()) {
                QuicraftFabric.LOG.info("QUICraft: disabled in config/quicraft/{}", ServerSettings.FILE);
                return;
            }
            if (!QuicSupport.isAvailable()) {
                QuicraftFabric.LOG.info("QUICraft: no QUIC native for this platform ({}); TCP only",
                        String.valueOf(QuicSupport.unavailabilityCause()));
                return;
            }
            DedicatedServerProperties properties = dedicated.getProperties();
            int quicPort = QuicPort.choose(settings.port(), settings.alternativePort(), port, properties.enableQuery,
                    properties.queryPort);
            InetSocketAddress udp = address != null ? new InetSocketAddress(address, quicPort)
                    : new InetSocketAddress(quicPort);
            ServerIdentity identity = ServerIdentity.loadOrCreate(dir);
            ServerQuic.minecraft = minecraft;
            fingerprintDigest = MessageDigest.getInstance("SHA-256").digest(identity.certificate().getEncoded());
            current = bind(minecraft, udp, identity, init, settings);
            QuicraftFabric.LOG.info("QUICraft: QUIC listening on UDP {} with {} socket(s) (fingerprint {}); open this "
                    + "UDP port in your firewall", current.server.localAddress(), current.sockets,
                    identity.fingerprint());
        } catch (Throwable t) {
            // docs/protocol.md §2: one WARN, no advertisement, TCP keeps running.
            QuicraftFabric.LOG.warn("QUICraft: QUIC disabled, players connect over TCP as usual: {}", t.toString());
            QuicraftFabric.LOG.debug("QUICraft: QUIC start failure", t);
        }
    }

    private static ServerQuic bind(MinecraftServer minecraft, InetSocketAddress udp, ServerIdentity identity,
            ChannelInitializer<Channel> init, ServerSettings settings) throws Exception {
        // The group vanilla's TCP listener runs on; QUIC players share its loops, one UDP socket
        // per loop with SO_REUSEPORT so the server's QUIC work spreads over several of them
        // (docs/protocol.md §11). bindHosted fails safe: one socket wherever several are not
        // possible, and only a failure to bind the first one throws.
        EventLoopGroupHolder holder = EventLoopGroupHolder.remote(minecraft.useNativeTransport());
        ChannelFactory<? extends DatagramChannel> datagrams = GameHost.datagramChannelsLike(holder.channelCls());
        GameHost.HostedSockets sockets = GameHost.bindHosted(holder.eventLoopGroup(), settings.udpSockets(), udp,
                datagrams);
        try {
            // The disabled-by-default /3 switch (12.1, 12.3, 13.1, 13.13). Off, or missing any
            // prerequisite, is today's quicraft/2 listener and advertisement, unchanged.
            MultistreamSwitch.Reason decision = MultistreamSwitch.probe(settings.multistream());
            boolean multistream = false;
            QuicServer server = null;
            if (decision.isEnabled()) {
                server = bindV3(sockets, identity, init, settings);
                multistream = server != null;
                if (server == null) {
                    // The failed bind closed the sockets it had registered, so bind them again for
                    // the /2 listener rather than leaving the server without QUIC at all.
                    sockets = GameHost.bindHosted(holder.eventLoopGroup(), settings.udpSockets(), udp, datagrams);
                }
            } else if (settings.multistream()) {
                // Once per start, never a failure: 12.3's false capability probe is an ordinary
                // compatibility condition.
                QuicraftFabric.LOG.info("QUICraft: multi-stream is unavailable here: {}", decision.message());
            }
            if (server == null) {
                final GameHost.HostedSockets bound = sockets;
                server = QuicServer.bind(bound.hostLoops(), bound.hostSockets(), identity,
                        TransportConfig.DEFAULT, stream -> accept(stream, init, bound.acceptingLoop()));
            }
            int port = server.localAddress().getPort();
            Advertisement advertisement = multistream
                    ? Advertisement.of(Protocol.ALPN_V3, port, identity.fingerprint(),
                            MultistreamSwitch.advertisedRouting())
                    : Advertisement.v1(port, identity.fingerprint());
            if (multistream) {
                QuicraftFabric.LOG.info("QUICraft: multi-stream (/3) enabled: advertising quicraft/3 for {}"
                        + " protocol number(s); node pending-bytes budget {} (provisional until the"
                        + " 50,000-connection benchmark, 13.17)",
                        Integer.valueOf(MultistreamSwitch.advertisedRouting().size()),
                        Long.valueOf(settings.multistreamNodeBudgetBytes()));
            }
            return new ServerQuic(server, advertisement, port, sockets.size());
        } catch (Exception | Error e) {
            // Core closes the sockets it registered; this covers a failure before it did.
            sockets.close();
            throw e;
        }
    }

    /**
     * The {@code /3}-only listener (13.1), or null when it cannot be bound: a failure here is never
     * the end of QUIC on this server, it is today's {@code /2} listener instead.
     */
    private static QuicServer bindV3(GameHost.HostedSockets sockets, ServerIdentity identity,
            ChannelInitializer<Channel> init, ServerSettings settings) {
        try {
            return QuicServer.bind(sockets.hostLoops(), sockets.hostSockets(), identity,
                    TransportConfig.DEFAULT, stream -> accept(stream, init, sockets.acceptingLoop()),
                    MultistreamSwitch::tableFor);
        } catch (Throwable t) {
            // docs/protocol.md 12.3: log once and stay on quicraft/2; 0-RTT (12.18) is untouched by
            // this path either way, because the transport's own early-data setting is unchanged.
            QuicraftFabric.LOG.warn("QUICraft: the multi-stream listener could not be bound ({});"
                    + " advertising quicraft/2 instead", t.toString());
            QuicraftFabric.LOG.debug("QUICraft: multi-stream bind failure", t);
            return null;
        }
    }

    private static void accept(QuicByteStream stream, ChannelInitializer<Channel> init, EventLoop loop) {
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        if (stream instanceof V3Connection v3) {
            // A quicraft/3 connection: its join runs the binding exchange once the game reaches
            // configuration (12.4). Nothing about /2 or TCP changes.
            MinecraftServer server = minecraft;
            byte[] digest = fingerprintDigest;
            if (server != null && digest != null) {
                V3Join.attach(channel, v3, server, digest);
            } else {
                v3.abort(Protocol.CLOSE_BINDING_FAILURE);
            }
        }
        channel.pipeline().addLast(init);
        loop.register(channel).addListener(f -> {
            if (!f.isSuccess()) {
                QuicraftFabric.LOG.debug("QUICraft: registering a QUIC connection failed", f.cause());
                stream.close();
            }
        });
    }

    /** Hook: the server stops listening. */
    public static void stop() {
        ServerQuic s = current;
        current = null;
        if (s != null) {
            try {
                s.server.close();
            } catch (Throwable t) {
                QuicraftFabric.LOG.debug("QUICraft: closing the QUIC listener failed", t);
            }
        }
    }

    /** Hook: a client reported a fallback to TCP (docs/protocol.md §6). Untrusted; log only. */
    public static void onFallbackReport(byte[] payload) {
        FALLBACK_REPORTS.incrementAndGet();
        ServerQuic s = current;
        if (s != null) {
            s.fallbackLog.report(payload);
        }
    }

    /** Tests: fallback reports received since start, well-formed or not. */
    public static int fallbackReportsReceived() {
        return FALLBACK_REPORTS.get();
    }

    /**
     * Runs vanilla's initializer unchanged, then adds the status advertiser next to its frame
     * decoder ("splitter") and encoder ("prepender"). Shared by all channels, TCP and QUIC.
     */
    @ChannelHandler.Sharable
    private static final class Advertising extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> vanilla;

        Advertising(ChannelInitializer<Channel> vanilla) {
            this.vanilla = vanilla;
        }

        @Override
        protected void initChannel(Channel ch) {
            // Registered and on its loop: vanilla's initChannel runs right here, inside addLast.
            ch.pipeline().addLast(vanilla);
            try {
                Hooks.enter("status advertiser");
                if (advertisement() != null) {
                    StatusAdvertising.install(ch.pipeline(), "splitter", "prepender", ServerQuic::advertisement);
                }
            } catch (Throwable t) {
                Hooks.failed("status advertiser", t);
            }
        }
    }
}
