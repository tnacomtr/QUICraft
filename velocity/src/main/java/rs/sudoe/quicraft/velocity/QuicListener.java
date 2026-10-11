// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import java.net.InetSocketAddress;
import java.util.function.Supplier;
import org.slf4j.Logger;
import rs.sudoe.quicraft.bridge.GameHost;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * The proxy's QUIC listener. Each accepted stream becomes a {@link QuicBridgeChannel} with
 * Velocity's current server channel initializer, so Velocity handles the player exactly like a
 * TCP connection. The channel's remote address is the player's UDP address, so IP bans and
 * forwarding keep working.
 *
 * <p>Hosted (the default): the UDP socket is a Velocity-native datagram channel (epoll on Linux)
 * on one Velocity worker loop, QUIC runs on that loop, and so do the players' pipelines and,
 * since Velocity connects backends on the player's loop, their backend connections. A packet
 * then crosses no thread between the wire and the backend, as with TCP. If the datagram channel
 * type can't be found, QUIC runs on core's own thread instead, one hop away.
 */
final class QuicListener implements AutoCloseable {
    private final QuicServer server;

    private QuicListener(QuicServer server) {
        this.server = server;
    }

    static QuicListener bind(InetSocketAddress address, ServerIdentity identity, TransportConfig config,
            Supplier<ChannelInitializer<Channel>> initializer, EventLoopGroup workers,
            ChannelFactory<? extends DatagramChannel> datagrams, Logger logger) throws Exception {
        // For the stats line: the server's early-release count, once it exists.
        java.util.concurrent.atomic.AtomicReference<QuicServer> bound = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.LongSupplier early = () -> bound.get() == null ? -1 : bound.get().earlyReleases();
        if (datagrams == null) {
            QuicServer server = QuicServer.bind(address, identity, config,
                    stream -> accept(stream, initializer, workers, logger, early));
            bound.set(server);
            return new QuicListener(server);
        }
        EventLoop loop = workers.next();
        DatagramChannel socket = (DatagramChannel) new Bootstrap().group(loop).channelFactory(datagrams)
                .handler(new ChannelInboundHandlerAdapter())
                .bind(address).sync().channel();
        try {
            QuicServer server = QuicServer.bind(GameHost.loop(loop), GameHost.socket(socket), identity, config,
                    stream -> accept(stream, initializer, loop, logger, early));
            bound.set(server);
            return new QuicListener(server);
        } catch (Exception | Error e) {
            socket.close();
            throw e;
        }
    }

    /** See {@link QuicServer#closeUnconfirmedEarly}. */
    java.util.concurrent.CompletableFuture<Integer> closeUnconfirmedEarly(InetSocketAddress login, String name) {
        return server.closeUnconfirmedEarly(login, name);
    }

    private static void accept(QuicByteStream stream, Supplier<ChannelInitializer<Channel>> initializer,
            EventLoopGroup loop, Logger logger, java.util.function.LongSupplier earlyReleases) {
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        channel.pipeline().addLast(initializer.get());
        channel.pipeline().addLast(new DropProxyProtocol());
        if (LOG_STATS) {
            channel.pipeline().addLast(new StatsOnClose(stream, logger, earlyReleases));
        }
        loop.register(channel).addListener(f -> {
            if (!f.isSuccess()) {
                logger.debug("QUICraft: registering a QUIC connection failed", f.cause());
                stream.close();
            }
        });
    }

    /**
     * Diagnostics: log each QUIC connection's stats when the player's channel closes, with the
     * listener's count of logins passed on 0-RTT data so far.
     */
    static final boolean LOG_STATS = Boolean.getBoolean("quicraft.logStats");

    private static final class StatsOnClose extends ChannelInboundHandlerAdapter {
        private final QuicByteStream stream;
        private final Logger logger;
        private final java.util.function.LongSupplier earlyReleases;

        StatsOnClose(QuicByteStream stream, Logger logger, java.util.function.LongSupplier earlyReleases) {
            this.stream = stream;
            this.logger = logger;
            this.earlyReleases = earlyReleases;
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            stream.connectionStats().whenComplete((stats, error) -> logger.info("QUICraft stats {}: {} earlyReleases={}",
                    ctx.channel().remoteAddress(), error == null ? stats : error.toString(),
                    earlyReleases.getAsLong()));
            ctx.fireChannelInactive();
        }
    }

    /**
     * QUIC carries no PROXY protocol header: the UDP source is the player. Velocity adds the
     * decoder when {@code haproxy-protocol} is on, which is meant for its TCP frontend. Runs once,
     * right after the initializer, then removes itself.
     */
    static final class DropProxyProtocol extends ChannelInboundHandlerAdapter {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            while (ctx.pipeline().get(HAProxyMessageDecoder.class) != null) {
                ctx.pipeline().remove(HAProxyMessageDecoder.class);
            }
            ctx.pipeline().remove(this);
        }
    }

    InetSocketAddress localAddress() {
        return server.localAddress();
    }

    @Override
    public void close() {
        server.close();
    }
}
