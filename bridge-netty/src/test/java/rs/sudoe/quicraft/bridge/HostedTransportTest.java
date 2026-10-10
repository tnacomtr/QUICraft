// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * Hosted transport: QUIC on the game's own event loop over a game {@link DatagramChannel}, so
 * the bridge channel, QUIC and the socket share one thread per side.
 */
// NioEventLoopGroup: deprecated in 4.2, the only choice in 4.1. "try": servers closed by try only.
@SuppressWarnings({"deprecation", "try"})
class HostedTransportTest {
    private static ServerIdentity identity;
    private EventLoopGroup serverGroup;
    private EventLoopGroup clientGroup;
    private EventLoop serverLoop;
    private EventLoop clientLoop;

    @BeforeAll
    static void setUpClass() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    @BeforeEach
    void setUp() {
        serverGroup = new NioEventLoopGroup(1);
        clientGroup = new NioEventLoopGroup(1);
        serverLoop = serverGroup.next();
        clientLoop = clientGroup.next();
    }

    @AfterEach
    void tearDown() {
        serverGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private static DatagramChannel udp(EventLoop loop) throws InterruptedException {
        return (DatagramChannel) new Bootstrap().group(loop).channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter())
                .bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
    }

    /** Echo server; records the thread the acceptor and the echo handler ran on. */
    private QuicServer echoServer(DatagramChannel socket, AtomicReference<Thread> acceptThread,
            AtomicReference<Thread> readThread) throws Exception {
        return QuicServer.bind(GameHost.loop(serverLoop), GameHost.socket(socket), identity, TransportConfig.DEFAULT,
                stream -> {
                    acceptThread.set(Thread.currentThread());
                    QuicBridgeChannel ch = new QuicBridgeChannel(stream);
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            readThread.compareAndSet(null, Thread.currentThread());
                            ctx.write(msg);
                        }

                        @Override
                        public void channelReadComplete(ChannelHandlerContext ctx) {
                            ctx.flush();
                        }
                    });
                    serverLoop.register(ch);
                });
    }

    private static Thread threadOf(EventLoop loop) throws Exception {
        return loop.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
    }

    @Test
    void bytesRoundTripWithQuicTheSocketAndThePipelineOnOneThreadPerSide() throws Exception {
        DatagramChannel serverUdp = udp(serverLoop);
        AtomicReference<Thread> acceptThread = new AtomicReference<>();
        AtomicReference<Thread> readThread = new AtomicReference<>();
        try (QuicServer server = echoServer(serverUdp, acceptThread, readThread)) {
            DatagramChannel clientUdp = udp(clientLoop);
            QuicByteStream stream = QuicClient.connect(GameHost.loop(clientLoop), GameHost.socket(clientUdp),
                    serverUdp.localAddress(), identity.fingerprint(), TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            assertTrue(clientLoop.submit(stream::inEventLoop).get(5, TimeUnit.SECONDS),
                    "the client stream runs on the game loop");

            QuicBridgeChannelTest.Collector collector = new QuicBridgeChannelTest.Collector();
            QuicBridgeChannel ch = new QuicBridgeChannel(stream);
            ch.pipeline().addLast(collector);
            clientLoop.register(ch).sync();
            collector.channel = ch;
            // Large enough for flow control and writability to matter.
            byte[] payload = new byte[300_000];
            new Random(11).nextBytes(payload);
            ch.writeAndFlush(Unpooled.wrappedBuffer(payload)).sync();
            assertArrayEquals(payload, collector.await(payload.length));

            Thread serverThread = threadOf(serverLoop);
            assertSame(serverThread, acceptThread.get(), "streams are accepted on the game loop");
            assertSame(serverThread, readThread.get(), "the game pipeline runs on the same loop as QUIC");
            assertFalse(collector.offLoop.get());
            ch.close().sync();
            assertTrue(clientUdp.closeFuture().await(5, TimeUnit.SECONDS), "the socket closes with the connection");
        }
        assertTrue(serverUdp.closeFuture().await(5, TimeUnit.SECONDS), "closing the server closes its socket");
    }

    @Test
    void aFingerprintMismatchFailsTheConnectAndClosesTheSocket() throws Exception {
        DatagramChannel serverUdp = udp(serverLoop);
        try (QuicServer server = echoServer(serverUdp, new AtomicReference<>(), new AtomicReference<>())) {
            DatagramChannel clientUdp = udp(clientLoop);
            CompletableFuture<QuicByteStream> attempt = QuicClient.connect(GameHost.loop(clientLoop),
                    GameHost.socket(clientUdp), serverUdp.localAddress(), ServerIdentity.generate().fingerprint(),
                    TransportConfig.DEFAULT);
            ExecutionException e = assertThrows(ExecutionException.class, () -> attempt.get(5, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof FingerprintTrustManager.FingerprintMismatchException,
                    () -> "expected a fingerprint mismatch, got " + e.getCause());
            assertTrue(clientUdp.closeFuture().await(5, TimeUnit.SECONDS));
        }
    }

    /** QUIC's timers run through the host loop: the idle timeout closes a dead connection. */
    @Test
    void theIdleTimeoutFiresOnTheHostLoop() throws Exception {
        TransportConfig shortIdle = TransportConfig.builder().maxIdleTimeout(500, TimeUnit.MILLISECONDS).build();
        DatagramChannel serverUdp = udp(serverLoop);
        QuicServer server = QuicServer.bind(GameHost.loop(serverLoop), GameHost.socket(serverUdp), identity,
                shortIdle, stream -> stream.setAutoRead(true));
        DatagramChannel clientUdp = udp(clientLoop);
        QuicByteStream stream = QuicClient.connect(GameHost.loop(clientLoop), GameHost.socket(clientUdp),
                serverUdp.localAddress(), identity.fingerprint(), shortIdle).get(5, TimeUnit.SECONDS);
        QuicBridgeChannel ch = new QuicBridgeChannel(stream);
        clientLoop.register(ch).sync();
        // The server vanishes without a CONNECTION_CLOSE.
        serverUdp.close().sync();
        long start = System.nanoTime();
        assertTrue(ch.closeFuture().await(5, TimeUnit.SECONDS), "idle timeout never fired");
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(waited < 3000, "closed after " + waited + " ms");
        server.close();
    }

    @Test
    void cancellingTheConnectClosesTheSocket() throws Exception {
        // Nothing answers on this socket: the attempt hangs until cancelled.
        DatagramChannel silent = udp(serverLoop);
        DatagramChannel clientUdp = udp(clientLoop);
        CompletableFuture<QuicByteStream> attempt = QuicClient.connect(GameHost.loop(clientLoop),
                GameHost.socket(clientUdp), silent.localAddress(), identity.fingerprint(), TransportConfig.DEFAULT);
        Thread.sleep(200);
        assertTrue(attempt.cancel(false));
        assertTrue(clientUdp.closeFuture().await(5, TimeUnit.SECONDS));
        silent.close().sync();
    }
}
