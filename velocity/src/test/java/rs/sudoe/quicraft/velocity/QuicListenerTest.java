// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import rs.sudoe.quicraft.bridge.GameHost;
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
                1, LoggerFactory.getLogger(QuicListenerTest.class))) {
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

    /**
     * docs/protocol.md §11: with udp-sockets=2 the listener binds two UDP sockets with
     * SO_REUSEPORT, on two distinct worker loops, and the proxy's QUIC work spreads over both
     * (which can only happen with the option: the second bind would otherwise be refused).
     */
    @Test
    void twoUdpSocketsSpreadQuicOverTwoWorkerLoops() throws Exception {
        assumeTrue(System.getProperty("os.name", "").startsWith("Linux"), "SO_REUSEPORT balancing is Linux only");
        assumeTrue(GameHost.reusePortOption(NioDatagramChannel.class) != null, "this JDK has no SO_REUSEPORT");
        LoopRecording velocity = new LoopRecording();
        try (QuicListener listener = QuicListener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                identity, TransportConfig.DEFAULT, () -> velocity, workers, NioDatagramChannel::new, 2,
                LoggerFactory.getLogger(QuicListenerTest.class))) {
            assertEquals(2, listener.socketCount(), "two sockets, which needs SO_REUSEPORT on the second bind");
            List<QuicByteStream> clients = new ArrayList<>();
            try {
                // Open them together: each client socket holds a distinct source port, so the
                // kernel's 4-tuple hash spreads them over the two sockets.
                for (int i = 0; i < 32; i++) {
                    QuicByteStream client = QuicClient.connect(listener.localAddress(), identity.fingerprint(),
                            TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
                    clients.add(client);
                    client.write(ByteBuffer.wrap(new byte[] {1}));
                    client.flush();
                }
                List<EventLoop> served = new ArrayList<>();
                for (int i = 0; i < clients.size(); i++) {
                    EventLoop loop = velocity.accepted.poll(5, TimeUnit.SECONDS);                    assertTrue(loop != null, "connection " + i + " reached no proxy initializer");
                    assertTrue(loop.parent() == workers, "served on the proxy's worker group");
                    served.add(loop);
                }
                assertEquals(2, new HashSet<>(served).size(),
                        () -> "one worker loop served every connection: " + served);
            } finally {
                for (QuicByteStream client : clients) {
                    client.close();
                }
            }
        }
    }

    /** Initializer that records the loop each accepted stream's pipeline is registered on. */
    private static final class LoopRecording extends ChannelInitializer<Channel> {
        final java.util.concurrent.BlockingQueue<EventLoop> accepted = new java.util.concurrent.LinkedBlockingQueue<>();

        @Override
        protected void initChannel(Channel ch) {
            accepted.add(ch.eventLoop());
        }
    }

    /**
     * The transport the proxy really uses here is epoll or io_uring on Linux, not NIO, and its
     * SO_REUSEPORT looks nothing like NIO's: if the name-derived lookup missed it, a production
     * proxy would silently bind one socket. The option must exist for that transport.
     */
    @Test
    void theProxysOwnTransportHasAReusePortOption() throws Exception {
        Class<? extends Channel> datagram = proxyDatagramChannelClass();
        assumeTrue(datagram != null, "Velocity hides its datagram channel factory on this build");
        System.out.println("proxy datagram channel: " + datagram.getName());
        assumeTrue(System.getProperty("os.name", "").startsWith("Linux"), "SO_REUSEPORT balancing is Linux only");
        assertNotNull(GameHost.reusePortOption(datagram),
                () -> datagram.getSimpleName() + " has no SO_REUSEPORT option: the proxy would bind one socket");
    }

    /**
     * The production path end to end: two UDP sockets of the transport the proxy really uses
     * (epoll here), on two of its own worker loops, serving QUIC. socketCount() is 2 only if the
     * kernel took SO_REUSEPORT on both, which is what the scale target needs.
     */
    @Test
    void twoSocketsOnTheProxysOwnTransport() throws Exception {
        assumeTrue(System.getProperty("os.name", "").startsWith("Linux"), "SO_REUSEPORT balancing is Linux only");
        Class<? extends Channel> datagram = proxyDatagramChannelClass();
        assumeTrue(datagram != null, "Velocity hides its datagram channel factory on this build");
        assumeTrue(GameHost.reusePortOption(datagram) != null, "this transport has no SO_REUSEPORT option");
        com.velocitypowered.proxy.network.TransportType transport =
                com.velocitypowered.proxy.network.TransportType.bestType();
        EventLoopGroup group = transport.createEventLoopGroup(
                com.velocitypowered.proxy.network.TransportType.Type.WORKER);
        try {
            LoopRecording velocity = new LoopRecording();
            try (QuicListener listener = QuicListener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    identity, TransportConfig.DEFAULT, () -> velocity, group, proxyDatagramChannels(transport), 2,
                    LoggerFactory.getLogger(QuicListenerTest.class))) {
                System.out.println("proxy transport " + transport + ": listening on " + listener.socketCount() + " sockets");
                assertEquals(2, listener.socketCount(), () -> transport + " did not take SO_REUSEPORT on two sockets");
                QuicByteStream client = QuicClient.connect(listener.localAddress(), identity.fingerprint(),
                        TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
                client.write(ByteBuffer.wrap(new byte[] {1}));
                client.flush();
                assertTrue(velocity.accepted.poll(5, TimeUnit.SECONDS) != null, "the proxy served the connection");
                client.close();
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    /** Velocity's own datagram channel type for its best transport, or null if it hides it. */
    private static Class<? extends Channel> proxyDatagramChannelClass() throws Exception {
        io.netty.channel.ChannelFactory<? extends DatagramChannel> datagrams = proxyDatagramChannels(
                com.velocitypowered.proxy.network.TransportType.bestType());
        if (datagrams == null) {
            return null;
        }
        DatagramChannel channel = datagrams.newChannel();
        try {
            return channel.getClass();
        } finally {
            channel.unsafe().closeForcibly();
        }
    }

    /** As {@code QuicraftVelocity.datagramChannels}: Velocity's datagram factory for a transport. */
    private static io.netty.channel.ChannelFactory<? extends DatagramChannel> proxyDatagramChannels(
            com.velocitypowered.proxy.network.TransportType transport) throws Exception {
        java.lang.reflect.Field field = com.velocitypowered.proxy.network.TransportType.class
                .getDeclaredField("datagramChannelFactory");
        field.setAccessible(true);
        Object factory = field.get(transport);
        @SuppressWarnings("unchecked")
        io.netty.channel.ChannelFactory<? extends DatagramChannel> datagrams =
                factory instanceof io.netty.channel.ChannelFactory
                        ? (io.netty.channel.ChannelFactory<? extends DatagramChannel>) factory
                        : null;
        return datagrams;
    }
}
