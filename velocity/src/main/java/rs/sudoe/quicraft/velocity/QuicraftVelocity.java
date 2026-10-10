// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.proxy.network.ConnectionManager;
import com.velocitypowered.proxy.network.ServerChannelInitializerHolder;
import com.velocitypowered.proxy.network.TransportType;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.FallbackReport;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * QUICraft for Velocity (docs/platforms/velocity.md). Every step fails safe: whatever goes
 * wrong, the plugin logs once and leaves Velocity's TCP listener and ping exactly as they were.
 */
@Plugin(id = "quicraft", name = "QUICraft", version = BuildInfo.VERSION, description = "QUIC transport for Minecraft, with TCP fallback")
public final class QuicraftVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private EventLoopGroup workers;
    private QuicListener listener;
    private StatusAdvertiser advertiser;
    /** Set while QUIC runs: clients that fell back to TCP report it here (docs/protocol.md §6). */
    volatile FallbackReport.Log fallbackLog;
    static final MinecraftChannelIdentifier FALLBACK_CHANNEL = MinecraftChannelIdentifier.from(Protocol.FALLBACK_CHANNEL);

    @Inject
    public QuicraftVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /** Runs before Velocity binds its listeners, so the wrapped initializer covers every channel. */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            start();
        } catch (Throwable t) {
            logger.warn("QUICraft: QUIC disabled, players connect over TCP as usual", t);
            stop();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        stop();
    }

    /**
     * A player's {@code quicraft:fallback} report: untrusted, so it only feeds the rate-limited
     * hint in the log, and it never reaches the backend.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        try {
            if (!FALLBACK_CHANNEL.equals(event.getIdentifier()) || !(event.getSource() instanceof Player)) {
                return;
            }
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            FallbackReport.Log log = fallbackLog;
            if (log != null) {
                log.report(event.getData());
            }
        } catch (Throwable t) {
            logger.debug("QUICraft: handling a fallback report failed", t);
        }
    }

    @SuppressWarnings("deprecation") // ServerChannelInitializerHolder.set: internal, but the only hook
    private void start() throws Exception {
        Settings settings = Settings.load(dataDirectory);
        if (!settings.enabled()) {
            logger.info("QUICraft: disabled in {}", Settings.FILE);
            return;
        }
        if (!QuicSupport.isAvailable()) {
            logger.info("QUICraft: no QUIC native for this platform ({}); TCP only",
                    String.valueOf(QuicSupport.unavailabilityCause()));
            return;
        }
        ServerChannelInitializerHolder holder = connectionManager().serverChannelInitializer;

        ProxyConfig config = proxy.getConfiguration();
        InetSocketAddress tcp = proxy.getBoundAddress();
        int port = settings.quicPort(tcp.getPort(), config.isQueryEnabled(), config.getQueryPort());
        InetSocketAddress udp = tcp.getAddress() != null
                ? new InetSocketAddress(tcp.getAddress(), port)
                : new InetSocketAddress(tcp.getHostString(), port);

        ServerIdentity identity = ServerIdentity.loadOrCreate(dataDirectory);
        TransportType transport = TransportType.bestType();
        workers = transport.createEventLoopGroup(TransportType.Type.WORKER);
        try {
            listener = QuicListener.bind(udp, identity, TransportConfig.DEFAULT, holder, workers,
                    datagramChannels(transport), logger);
        } catch (Exception e) {
            // docs/protocol.md §2: one WARN, no advertisement, TCP keeps running.
            logger.warn("QUICraft: could not bind UDP {} for QUIC ({}); not advertising QUIC, TCP only",
                    udp, e.toString());
            stop();
            return;
        }

        advertiser = new StatusAdvertiser(
                Advertisement.v1(listener.localAddress().getPort(), identity.fingerprint()), logger);
        holder.set(new AdvertisingInitializer(holder.get(), advertiser, logger));
        fallbackLog = new FallbackReport.Log(listener.localAddress().getPort());
        proxy.getChannelRegistrar().register(FALLBACK_CHANNEL);
        logger.info("QUICraft: QUIC listening on UDP {} (fingerprint {}); open this UDP port in your firewall",
                listener.localAddress(), identity.fingerprint());
    }

    /**
     * Velocity's datagram channel type for its transport (as used by its query listener), or
     * null if this Velocity hides it: QUIC then runs on core's own thread.
     */
    @SuppressWarnings("unchecked")
    private ChannelFactory<? extends DatagramChannel> datagramChannels(TransportType transport) {
        try {
            Field f = TransportType.class.getDeclaredField("datagramChannelFactory");
            f.setAccessible(true);
            return (ChannelFactory<? extends DatagramChannel>) f.get(transport);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.info("QUICraft: no Velocity datagram channel for {} ({}); QUIC runs on its own thread",
                    transport, e.toString());
            return null;
        }
    }

    private ConnectionManager connectionManager() throws ReflectiveOperationException {
        // VelocityServer keeps it in a private field with no getter (4.2.0).
        Field cm = proxy.getClass().getDeclaredField("cm");
        cm.setAccessible(true);
        return (ConnectionManager) cm.get(proxy);
    }

    private void stop() {
        fallbackLog = null;
        if (advertiser != null) {
            advertiser.withdraw();
            advertiser = null;
        }
        if (listener != null) {
            try {
                listener.close();
            } catch (Throwable t) {
                logger.debug("QUICraft: closing the QUIC listener failed", t);
            }
            listener = null;
        }
        if (workers != null) {
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            workers = null;
        }
    }
}
