// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.EventLoopGroupHolder;
import rs.sudoe.quicraft.bridge.GameHost;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.bridge.status.StatusQuery;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.connect.ConnectionRace;
import rs.sudoe.quicraft.core.connect.TransportSelection;
import rs.sudoe.quicraft.core.connect.V3Plan;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.ClientStream;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.core.transport.V3ConnectionImpl;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.QuicraftFabric;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;

/**
 * Hook for the connect screen's {@code Connection.connect} call (docs/protocol.md §4–§6).
 *
 * <p>When QUIC can't play a part, vanilla's own connect runs, untouched. Otherwise core's
 * {@link ClientConnector} decides, races and records, on the game's event loop, and the winner
 * (a {@link QuicBridgeChannel} or a plain TCP channel) gets exactly the pipeline vanilla would
 * have built. The screen gets a future that completes once the {@link Connection} is active on
 * the winner; cancelling it (the Cancel button) abandons the attempt.
 */
public final class ClientConnect {
    /** Client gametest only: aligns the test routing table with the protocol under test. */
    private static volatile int testProtocolOverride;

    private ClientConnect() {}

    static void setProtocolOverrideForTests(int protocol) {
        testProtocolOverride = protocol;
    }

    private static int currentProtocolNumber() {
        int override = testProtocolOverride;
        return override != 0 ? override : SharedConstants.getCurrentVersion().protocolVersion();
    }

    public static ChannelFuture connect(InetSocketAddress address, EventLoopGroupHolder holder, Connection connection,
            Supplier<ChannelFuture> vanilla) {
        ChannelFuture ours = null;
        try {
            Hooks.enter("connect");
            QuicraftClient client = QuicraftClient.get();
            if (client != null && !EarlyJoins.takeTcpOnce(address)
                    && client.connector().plan(address, client.mode()) == ClientConnector.Plan.CONNECT) {
                ours = start(client, address, holder, connection, vanilla);
            }
        } catch (Throwable t) {
            Hooks.failed("connect", t);
        }
        return ours != null ? ours : vanilla.get();
    }

    private static ChannelFuture start(QuicraftClient client, InetSocketAddress address, EventLoopGroupHolder holder,
            Connection connection, Supplier<ChannelFuture> vanilla) {
        Mode mode = client.mode();
        EventLoop loop = holder.eventLoopGroup().next();
        // The screen only syncs on and cancels this future, never asks for its channel.
        ChannelPromise promise = new DefaultChannelPromise(new EmbeddedChannel(), loop);
        String joinInputs = null;
        try {
            Hooks.enter("early inputs");
            joinInputs = EarlyJoins.joinInputs(address);
        } catch (Throwable t) {
            Hooks.failed("early inputs", t); // no 0-RTT this time
        }
        // The multistream path only when the player turned it on; otherwise the call that existed
        // before it, untouched (docs/protocol.md 12.1).
        ServerAddress logical = EarlyJoins.logicalAddress();
        ClientConnector multistream = client.multistream() && logical != null
                && ClientV3State.useMultistream(true, true, logical.getHost(), logical.getPort())
                        ? client.multistreamConnector() : null;
        ClientV3State files = multistream != null ? client.v3State() : null;
        CompletableFuture<ClientConnector.Outcome<Channel>> attempt = multistream != null && files != null
                ? multistream.connect(address, logical.getHost(), logical.getPort(), mode, joinInputs,
                        new Platform(address, holder, loop, true))
                : client.connector().connect(address, mode, joinInputs, new Platform(address, holder, loop, false));
        promise.addListener(f -> {
            if (f.isCancelled()) {
                attempt.cancel(false);
            }
        });
        attempt.whenComplete((outcome, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                if (cause instanceof ClientConnector.TcpConnectException) {
                    promise.tryFailure(withMessage(cause.getCause())); // as vanilla would have failed
                } else {
                    failed(mode, cause, promise, vanilla);
                }
                return;
            }
            // Until the Connection is attached to a channel, any failure can still go vanilla.
            boolean[] attached = {false};
            try {
                Hooks.enter("connect outcome");
                if (outcome.quic() instanceof V3Connection v3 && outcome.v3Plan() != null && files != null) {
                    QuicraftFabric.LOG.info("QUICraft: connected to {} over QUIC /3{}", v3.remoteAddress(),
                            v3 instanceof V3ConnectionImpl impl && impl.sentEarlyData() ? " (0-RTT)" : "");
                    useV3(v3, outcome.v3Plan(), loop, connection, promise, attached, mode, files, logical, vanilla);
                } else if (outcome.quic() != null) {
                    QuicraftFabric.LOG.info("QUICraft: connected to {} over QUIC{}", outcome.quic().remoteAddress(),
                            outcome.quic() instanceof ClientStream cs && cs.sentEarlyData() ? " (0-RTT)" : "");
                    useQuic(outcome.quic(), loop, connection, promise, attached, mode);
                } else {
                    if (outcome.fallback() != null) {
                        QuicraftFabric.LOG.info("QUICraft: connected to {} over TCP; QUIC failed ({})", address,
                                outcome.fallback().name().toLowerCase(java.util.Locale.ROOT));
                    } else {
                        QuicraftFabric.LOG.info("QUICraft: connected to {} over TCP; no QUIC advertised", address);
                    }
                    useTcp(outcome, connection, promise, mode, vanilla);
                }
            } catch (Throwable t) {
                close(outcome);
                if (attached[0]) {
                    promise.tryFailure(withMessage(t));
                } else {
                    if (outcome.v3Plan() != null && mode != Mode.QUIC_ONLY) {
                        // The attempt held the server's re-bind state and is recorded as failed (12.14).
                        outcome.v3Plan().failed(V3Plan.Phase.JOINING, V3Plan.INTERNAL_ERROR);
                        QuicraftFabric.LOG.warn("QUICraft: {}", V3Notices.fellBack(V3Plan.INTERNAL_ERROR));
                    }
                    failed(mode, t, promise, vanilla);
                }
            }
        });
        return promise;
    }

    /**
     * QUICraft failed after the screen got its future. In {@code quic-only} mode that's the
     * player's error; otherwise connect as vanilla would have, and complete the screen's future
     * with that (CLAUDE.md: a QUICraft bug never stops a TCP join).
     */
    private static void failed(Mode mode, Throwable cause, ChannelPromise promise, Supplier<ChannelFuture> vanilla) {
        if (mode == Mode.QUIC_ONLY) {
            promise.tryFailure(withMessage(cause));
            return;
        }
        Hooks.failed("async connect", cause);
        if (promise.isDone()) {
            return; // cancelled
        }
        try {
            ChannelFuture tcp = vanilla.get();
            promise.addListener(f -> {
                if (f.isCancelled()) {
                    tcp.cancel(true);
                    tcp.channel().close();
                }
            });
            tcp.addListener(f -> {
                if (f.isSuccess()) {
                    if (!promise.trySuccess()) {
                        tcp.channel().close();
                    }
                } else {
                    promise.tryFailure(withMessage(f.cause()));
                }
            });
        } catch (Throwable t) {
            promise.tryFailure(withMessage(t));
        }
    }

    private static void useQuic(QuicByteStream stream, EventLoop loop, Connection connection, ChannelPromise promise,
            boolean[] attached, Mode mode) {
        // quic-only fails with QUIC's error instead (docs/protocol.md §10): no TCP retry.
        if (mode != Mode.QUIC_ONLY && stream instanceof ClientStream early && early.sentEarlyData()) {
            // Before the game writes: a first flight that differs from the 0-RTT one closes
            // QUIC, and the disconnect then starts the same join over TCP (EarlyJoins).
            early.onFirstFlightMismatch(() -> Transports.retryOverTcp(connection));
        }
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        attached[0] = true;
        installVanillaPipeline(channel, connection);
        Transports.quic(connection, stream);
        // Registration makes the bridge active, so the Connection sees channelActive as on TCP.
        loop.register(channel).addListener(f -> {
            if (!f.isSuccess()) {
                stream.close();
                promise.tryFailure(withMessage(f.cause()));
            } else if (!promise.trySuccess()) {
                channel.close(); // cancelled meanwhile
            }
        });
    }

    /**
     * A {@code /3} connection won: the vanilla pipeline plus the multi-stream handler in front of
     * the game's packet handler. From here {@link V3Client} runs the exchange; a failure before the
     * lanes are active ends in a TCP retry (docs/protocol.md 12.14).
     */
    static void useV3(V3Connection v3, V3Plan plan, EventLoop loop, Connection connection,
            ChannelPromise promise, boolean[] attached, Mode mode, ClientV3State files, ServerAddress logical,
            Supplier<ChannelFuture> vanilla) {
        // Transactional: until the pipeline is complete and the channel registered, nothing is attached
        // to the screen's future, and any failure releases what was built and takes the mode-aware
        // fallback (docs/protocol.md 12.14).
        QuicBridgeChannel channel = null;
        V3Client client = null;
        try {
            int protocol = currentProtocolNumber();
            RoutingTable table = RoutingRegistry.forProtocol(protocol)
                    .orElseThrow(() -> new IllegalStateException("no routing table for protocol " + protocol));
            channel = new QuicBridgeChannel(v3);
            Hooks.enter("v3 setup");
            client = new V3Client(connection, channel, loop, v3, plan, table, protocol,
                    mode == Mode.QUIC_ONLY, files, logical.getHost(), logical.getPort(),
                    plan.pinState() == TransportSelection.PinState.CHANGED
                            ? V3Client.oldPin(files, logical.getHost(), logical.getPort())
                            : java.util.concurrent.CompletableFuture.completedFuture(null));
            installVanillaPipeline(channel, connection);
            channel.pipeline().addBefore("packet_handler", V3Handler.NAME, new V3Handler(client));
            Transports.quic(connection, v3);
            Transports.v3(connection, client);
            Hooks.enter("v3 published");
            V3Client controller = client;
            if (v3 instanceof V3ConnectionImpl impl) {
                // The game's first flight differed from the 0-RTT one: the connection closes, and the join
                // starts again over TCP like any other /3 failure before activation.
                impl.onFirstFlightMismatch(() -> controller.fail("the early data did not match"));
            }
            final QuicBridgeChannel registering = channel;
            attached[0] = true;
            loop.register(channel).addListener(f -> {
                if (!f.isSuccess()) {
                    setupFailed(v3, controller, connection, registering, plan, mode, f.cause(), promise, vanilla);
                } else if (!promise.trySuccess()) {
                    registering.close(); // cancelled meanwhile
                }
            });
        } catch (Throwable t) {
            attached[0] = false;
            setupFailed(v3, client, connection, channel, plan, mode, t, promise, vanilla);
        }
    }

    /** Releases a half-built /3 join and falls back as the mode requires: TCP and a notice, or the error. */
    private static void setupFailed(V3Connection v3, V3Client client, Connection connection, QuicBridgeChannel channel,
            V3Plan plan, Mode mode, Throwable cause, ChannelPromise promise, Supplier<ChannelFuture> vanilla) {
        Transports.rollbackV3(connection, v3, client);
        try {
            if (channel != null && channel.isRegistered()) {
                channel.close();
            }
            v3.close();
        } catch (Throwable ignored) {
            // Closing is best effort; the connection dies with its owner.
        }
        boolean retry = plan.failed(V3Plan.Phase.JOINING, V3Plan.INTERNAL_ERROR);
        if (retry && mode != Mode.QUIC_ONLY) {
            QuicraftFabric.LOG.warn("QUICraft: {}", V3Notices.fellBack(V3Plan.INTERNAL_ERROR), cause);
            failed(mode, cause, promise, vanilla);
        } else {
            promise.tryFailure(withMessage(new java.io.IOException(
                    "QUIC multi-stream failed (" + V3Plan.INTERNAL_ERROR + ")", cause)));
        }
    }

    private static void useTcp(ClientConnector.Outcome<Channel> outcome, Connection connection,
            ChannelPromise promise, Mode mode, Supplier<ChannelFuture> vanilla) {
        Channel channel = outcome.tcp();
        // A task, so it runs after Netty has fired channelActive for the bare channel.
        channel.eventLoop().execute(() -> {
            boolean attached = false;
            try {
                if (promise.isDone()) {
                    channel.close(); // cancelled meanwhile
                    return;
                }
                Hooks.enter("connect tcp winner");
                attached = true;
                installVanillaPipeline(channel, connection);
                if (channel.isActive()) {
                    ChannelHandlerContext ctx = channel.pipeline().context(connection);
                    connection.channelActive(ctx);
                }
                if (outcome.fallback() != null) {
                    Transports.fellBack(connection, outcome.fallback());
                }
                if (outcome.fallbackMessage() != null) {
                    // A /3 attempt that failed before the adapter could drive it (12.14): said in the log
                    // now and in the chat once the join reaches play.
                    QuicraftFabric.LOG.warn("QUICraft: {}", outcome.fallbackMessage());
                    V3Notices.fellBackWith(outcome.fallbackMessage());
                }
                if (!promise.trySuccess()) {
                    channel.close();
                }
            } catch (Throwable t) {
                channel.close();
                if (attached) {
                    promise.tryFailure(withMessage(t));
                } else {
                    failed(mode, t, promise, vanilla);
                }
            }
        });
    }

    /** What vanilla's {@code Connection.connect} initializer does (checked per version). */
    static void installVanillaPipeline(Channel channel, Connection connection) {
        try {
            channel.config().setOption(ChannelOption.TCP_NODELAY, true);
        } catch (ChannelException ignored) {
            // as vanilla
        }
        ChannelPipeline pipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
        Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false,
                ((ConnectionAccessor) connection).quicraft$bandwidthDebugMonitor());
        connection.configurePacketHandler(pipeline);
    }

    /**
     * The connect screen shows {@code cause.getMessage()} and calls {@code replaceAll} on it, so a
     * failure without a message would throw there and leave the screen hanging.
     */
    static Throwable withMessage(Throwable cause) {
        if (cause.getMessage() != null) {
            return cause;
        }
        return new java.io.IOException(cause.getClass().getSimpleName(), cause);
    }

    private static void close(ClientConnector.Outcome<Channel> outcome) {
        if (outcome.quic() != null) {
            outcome.quic().close();
        } else if (outcome.tcp() != null) {
            outcome.tcp().close();
        }
    }

    /** The game's side of core's connect path. */
    private static final class Platform implements ClientConnector.Platform<Channel> {
        private final InetSocketAddress address;
        private final EventLoopGroupHolder holder;
        private final EventLoop loop;
        private final boolean selectionAware;

        Platform(InetSocketAddress address, EventLoopGroupHolder holder, EventLoop loop, boolean selectionAware) {
            this.address = address;
            this.holder = holder;
            this.loop = loop;
            this.selectionAware = selectionAware;
        }

        @Override
        public CompletableFuture<Optional<Advertisement>> queryStatus(InetSocketAddress server) {
            Hooks.enter("status query"); // a throw here counts as "no advertisement": TCP
            return StatusQuery.query(loop, new ReflectiveChannelFactory<>(holder.channelCls()), server,
                    address.getHostString(), currentProtocolNumber())
                    .thenApply(json -> selectionAware ? Advertisement.extractForSelection(json)
                            : Advertisement.extract(json));
        }

        @Override
        public CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target, Fingerprint fingerprint,
                EarlyFlight early) {
            try {
                Hooks.enter("quic connect"); // a throw here is a QUIC failure: TCP wins the race
                return GameHost.connect(loop, GameHost.datagramChannelsLike(holder.channelCls()), target, fingerprint,
                        TransportConfig.DEFAULT, early);
            } catch (Throwable t) {
                CompletableFuture<QuicByteStream> failed = new CompletableFuture<>();
                failed.completeExceptionally(t);
                return failed;
            }
        }

        @Override
        public CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target, Fingerprint fingerprint,
                EarlyFlight early, TransportSelection.Transport alpn, boolean bypassResumption) {
            if (alpn != TransportSelection.Transport.V3) {
                return connectQuic(target, fingerprint, early);
            }
            CompletableFuture<QuicByteStream> result = new CompletableFuture<>();
            try {
                Hooks.enter("quic connect"); // a throw here is a QUIC failure: TCP wins the race
                int protocol = currentProtocolNumber();
                RoutingTable table = RoutingRegistry.forProtocol(protocol).orElseThrow(
                        () -> new IllegalStateException("no routing table for protocol " + protocol));
                if (!MultistreamSwitch.serves(table)) {
                    // 13.7: a schema-1 resource cannot drive /3, so never offer the dial at all.
                    throw new IllegalStateException("no reviewed schema-2 routing table for protocol "
                            + protocol + " (13.7)");
                }
                CompletableFuture<V3Connection> dial = GameHost.connectV3(loop,
                        GameHost.datagramChannelsLike(holder.channelCls()), target, fingerprint, table,
                        TransportConfig.DEFAULT, early, bypassResumption);
                result.whenComplete((stream, error) -> {
                    if (result.isCancelled()) {
                        dial.cancel(false);
                    }
                });
                dial.whenComplete((connection, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else if (!result.complete(connection)) {
                        connection.close();
                    }
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
            return result;
        }

        @Override
        public ConnectionRace.Tcp<Channel> tcp() {
            return new ConnectionRace.Tcp<Channel>() {
                @Override
                public CompletableFuture<Channel> connect() {
                    CompletableFuture<Channel> result = new CompletableFuture<>();
                    ChannelFuture f = new Bootstrap().group(loop).channel(holder.channelCls())
                            .handler(new Bare())
                            .connect(address.getAddress(), address.getPort());
                    f.addListener(done -> {
                        if (done.isSuccess()) {
                            if (!result.complete(f.channel())) {
                                f.channel().close();
                            }
                        } else {
                            f.channel().close();
                            result.completeExceptionally(done.cause());
                        }
                    });
                    result.whenComplete((ch, e) -> {
                        if (result.isCancelled()) {
                            f.channel().close();
                        }
                    });
                    return result;
                }

                @Override
                public void close(Channel connection) {
                    connection.close();
                }
            };
        }

        @Override
        public ScheduledExecutorService scheduler() {
            return loop;
        }
    }

    /** A racing TCP channel starts with an empty pipeline; vanilla's goes in if TCP wins. */
    private static final class Bare extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) {
        }
    }
}
