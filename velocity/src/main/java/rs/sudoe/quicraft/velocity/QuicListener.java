// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import java.net.InetSocketAddress;
import java.util.function.Supplier;
import org.slf4j.Logger;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * The proxy's QUIC listener. Each accepted stream becomes a {@link QuicBridgeChannel} with
 * Velocity's current server channel initializer, registered on a worker group of its own, so
 * Velocity handles the player exactly like a TCP connection. The channel's remote address is
 * the player's UDP address, so IP bans and forwarding keep working.
 */
final class QuicListener implements AutoCloseable {
    private final QuicServer server;

    private QuicListener(QuicServer server) {
        this.server = server;
    }

    static QuicListener bind(InetSocketAddress address, ServerIdentity identity, TransportConfig config,
            Supplier<ChannelInitializer<Channel>> initializer, EventLoopGroup workers, Logger logger)
            throws Exception {
        return new QuicListener(QuicServer.bind(address, identity, config,
                stream -> accept(stream, initializer, workers, logger)));
    }

    private static void accept(QuicByteStream stream, Supplier<ChannelInitializer<Channel>> initializer,
            EventLoopGroup workers, Logger logger) {
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        channel.pipeline().addLast(initializer.get());
        channel.pipeline().addLast(new DropProxyProtocol());
        workers.register(channel).addListener(f -> {
            if (!f.isSuccess()) {
                logger.debug("QUICraft: registering a QUIC connection failed", f.cause());
                stream.close();
            }
        });
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
