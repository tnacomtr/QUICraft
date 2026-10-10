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
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.EventLoopGroupHolder;
import rs.sudoe.quicraft.bridge.GameHost;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.bridge.status.StatusQuery;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.connect.ConnectionRace;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.ClientStream;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.TransportConfig;
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
    private ClientConnect() {}

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
        CompletableFuture<ClientConnector.Outcome<Channel>> attempt = client.connector()
                .connect(address, mode, joinInputs, new Platform(address, holder, loop));
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
                if (outcome.quic() != null) {
                    QuicraftFabric.LOG.info("QUICraft: connected to {} over QUIC{}", outcome.quic().remoteAddress(),
                            outcome.quic() instanceof ClientStream cs && cs.sentEarlyData() ? " (0-RTT)" : "");
                    useQuic(outcome.quic(), loop, connection, promise, attached);
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
            boolean[] attached) {
        if (stream instanceof ClientStream early && early.sentEarlyData()) {
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
    private static void installVanillaPipeline(Channel channel, Connection connection) {
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

        Platform(InetSocketAddress address, EventLoopGroupHolder holder, EventLoop loop) {
            this.address = address;
            this.holder = holder;
            this.loop = loop;
        }

        @Override
        public CompletableFuture<Optional<Advertisement>> queryStatus(InetSocketAddress server) {
            Hooks.enter("status query"); // a throw here counts as "no advertisement": TCP
            return StatusQuery.query(loop, new ReflectiveChannelFactory<>(holder.channelCls()), server,
                    address.getHostString(), SharedConstants.getCurrentVersion().protocolVersion())
                    .thenApply(Advertisement::extract);
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
