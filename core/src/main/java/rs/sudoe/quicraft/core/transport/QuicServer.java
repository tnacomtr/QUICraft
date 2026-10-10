// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * QUIC listener. Each client's v1 stream is handed to the {@link StreamAcceptor} only after the
 * QUIC handshake has completed. 0-RTT data that arrived earlier waits in the stream until then,
 * so a replayed first flight never reaches the game (docs/protocol.md §8).
 */
public final class QuicServer implements Closeable {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    /** RFC 9001 §4.8: CRYPTO_ERROR 0x100 + TLS alert handshake_failure (40). */
    private static final int CRYPTO_ERROR_HANDSHAKE_FAILURE = 0x100 + 40;

    /** Receives each accepted stream on the stream's thread. Must set a listener, then enable auto-read. */
    public interface StreamAcceptor {
        void accept(QuicByteStream stream);
    }

    private final EventLoopGroup group;
    private final Channel channel;

    private QuicServer(EventLoopGroup group, Channel channel) {
        this.group = group;
        this.channel = channel;
    }

    /** Binds {@code address} and runs QUIC on core's own thread. */
    public static QuicServer bind(InetSocketAddress address, ServerIdentity identity, TransportConfig config,
            StreamAcceptor acceptor) throws Exception {
        io.netty.channel.ChannelHandler codec = codec(identity, config, acceptor);
        EventLoopGroup group = Codecs.newGroup("quicraft-server", 1);
        try {
            Channel channel = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec)
                    .bind(address).sync().channel();
            return new QuicServer(group, channel);
        } catch (Exception | Error e) {
            group.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
            throw e;
        }
    }

    /**
     * Runs QUIC on the platform's {@code loop} over its bound {@code socket} (hosted transport,
     * docs/protocol.md §11): accepted streams can go straight into a pipeline on the same loop,
     * with no thread hop. {@link #close()} closes the socket.
     */
    public static QuicServer bind(HostLoop loop, HostDatagramSocket socket, ServerIdentity identity,
            TransportConfig config, StreamAcceptor acceptor) throws Exception {
        HostedDatagramChannel channel = new HostedDatagramChannel(socket);
        try {
            channel.pipeline().addLast(codec(identity, config, acceptor));
        } catch (Exception | Error e) {
            socket.close();
            throw e;
        }
        io.netty.channel.ChannelFuture registered = new HostedEventLoop(loop).register(channel);
        if (!loop.inEventLoop(Thread.currentThread())) {
            registered.sync();
        } else if (registered.isDone() && !registered.isSuccess()) {
            throw new IllegalStateException("registering the QUIC listener failed", registered.cause());
        }
        return new QuicServer(null, channel);
    }

    private static io.netty.channel.ChannelHandler codec(ServerIdentity identity, TransportConfig config,
            StreamAcceptor acceptor) throws Exception {
        QuicSslContext ssl = QuicSslContextBuilder
                .forServer(identity.privateKey(), null, identity.certificate())
                .applicationProtocols(Protocol.ALPN)
                .earlyData(config.earlyData)
                .build();
        return Codecs.apply(new QuicServerCodecBuilder(), config, true)
                .sslContext(ssl)
                // Address validation (Retry) under load comes with Phase 4 (docs/protocol.md §9).
                .tokenHandler(NoRetryTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel connection) {
                        connection.pipeline().addLast(new Connection(acceptor));
                    }
                })
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel stream) {
                        Connection connection = stream.parent().pipeline().get(Connection.class);
                        if (connection == null || stream.type() != QuicStreamType.BIDIRECTIONAL
                                || stream.isLocalCreated()) {
                            stream.parent().close(true, Protocol.CLOSE_PROTOCOL_VIOLATION, Unpooled.EMPTY_BUFFER);
                            return;
                        }
                        connection.offer(new NettyQuicByteStream(stream));
                    }
                })
                .build();
    }

    public InetSocketAddress localAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    @Override
    public void close() {
        if (group != null) {
            channel.close().syncUninterruptibly();
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        } else {
            // Hosted: the loop is the platform's. Bounded wait, in case it is already gone.
            channel.close().awaitUninterruptibly(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** Per-connection state: holds streams back until the handshake has completed. */
    private static final class Connection extends ChannelInboundHandlerAdapter {
        private final StreamAcceptor acceptor;
        private final List<NettyQuicByteStream> pending = new ArrayList<>(1);
        private boolean handshakeDone;

        Connection(StreamAcceptor acceptor) {
            this.acceptor = acceptor;
        }

        /** Called on the connection's event loop. */
        void offer(NettyQuicByteStream stream) {
            if (handshakeDone) {
                deliver(stream);
            } else {
                pending.add(stream);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof SslHandshakeCompletionEvent) {
                SslHandshakeCompletionEvent done = (SslHandshakeCompletionEvent) evt;
                if (done.isSuccess()) {
                    handshakeDone = true;
                    for (NettyQuicByteStream stream : pending) {
                        deliver(stream);
                    }
                    pending.clear();
                } else {
                    ctx.close();
                }
            }
            ctx.fireUserEventTriggered(evt);
        }

        /**
         * Closes the connection with an explicit error where quiche can still send one. Known
         * limit (Netty 4.2.19): on a TLS failure such as no common ALPN, the error escapes
         * quiche's send path and no CONNECTION_CLOSE goes out, so the client only learns of it at
         * its connect timeout. Racing covers that case: TCP starts after the head start
         * (docs/protocol.md §5).
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof javax.net.ssl.SSLHandshakeException) {
                ((QuicChannel) ctx.channel()).close(false, CRYPTO_ERROR_HANDSHAKE_FAILURE, Unpooled.EMPTY_BUFFER);
            } else {
                LOG.log(Level.FINE, "QUIC connection error", cause);
                ((QuicChannel) ctx.channel()).close(true, Protocol.CLOSE_INTERNAL_ERROR, Unpooled.EMPTY_BUFFER);
            }
        }

        private void deliver(NettyQuicByteStream stream) {
            try {
                acceptor.accept(stream);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "QUIC stream acceptor failed; closing the connection", t);
                stream.close();
            }
        }
    }
}
