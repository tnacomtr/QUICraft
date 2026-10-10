// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/** A QUIC player reaches the proxy's own initializer on a proxy event loop, as a TCP one would. */
class QuicListenerTest {
    private static EventLoopGroup workers;
    private static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
        workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    }

    @AfterAll
    static void tearDown() {
        workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /** Velocity-like: proxy-protocol on, so a PROXY decoder comes first; then an echo handler. */
    @ChannelHandler.Sharable
    private static final class ProxyInitializer extends ChannelInitializer<Channel> {
        final CompletableFuture<Channel> channel = new CompletableFuture<>();
        final CompletableFuture<SocketAddress> remote = new CompletableFuture<>();

        @Override
        protected void initChannel(Channel ch) {
            ch.pipeline().addLast("handler", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    remote.complete(ctx.channel().remoteAddress());
                    ctx.fireChannelActive();
                }

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ctx.writeAndFlush(msg);
                }
            });
            ch.pipeline().addFirst(new HAProxyMessageDecoder());
            channel.complete(ch);
        }
    }

    @Test
    void hostedAQuicStreamRunsThroughTheProxyInitializerOnTheSocketsLoop() throws Exception {
        run(true);
    }

    @Test
    void onCoresThreadAQuicStreamRunsThroughTheProxyInitializer() throws Exception {
        run(false);
    }

    private static void run(boolean hosted) throws Exception {
        ProxyInitializer velocity = new ProxyInitializer();
        try (QuicListener listener = QuicListener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                identity, TransportConfig.DEFAULT, () -> velocity, workers, hosted ? NioDatagramChannel::new : null,
                LoggerFactory.getLogger(QuicListenerTest.class))) {
            QuicByteStream client = QuicClient.connect(listener.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            CompletableFuture<String> echoed = new CompletableFuture<>();
            StringBuilder received = new StringBuilder();
            client.setListener(new QuicByteStream.Listener() {
                @Override
                public void onData(ByteBuffer data) {
                    received.append(StandardCharsets.UTF_8.decode(data));
                    if (received.length() >= 5) {
                        echoed.complete(received.toString());
                    }
                }

                @Override
                public void onWritabilityChanged(boolean writable) {
                }

                @Override
                public void onClosed(Throwable cause) {
                    echoed.completeExceptionally(new IllegalStateException("closed", cause));
                }
            });
            client.setAutoRead(true);
            // Not a PROXY header: the decoder would reject this if it were still there.
            client.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
            client.flush();
            assertEquals("hello", echoed.get(5, TimeUnit.SECONDS));

            Channel ch = velocity.channel.get(5, TimeUnit.SECONDS);
            assertTrue(ch.eventLoop().parent() == workers, "registered on the proxy's worker group");
            assertNull(ch.pipeline().get(HAProxyMessageDecoder.class));
            assertFalse(ch.pipeline().names().contains("QuicListener$DropProxyProtocol#0"));
            assertEquals(new InetSocketAddress(InetAddress.getLoopbackAddress(), client.localAddress().getPort()),
                    velocity.remote.get(5, TimeUnit.SECONDS));
            client.close();
        }
    }
}
