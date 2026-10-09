// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.Version;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * Runs in each bridge module against that module's game Netty (4.1.82 or 4.2.1), with the
 * shaded core on the classpath: the production layout.
 */
class QuicBridgeChannelTest {
    private static ServerIdentity identity;
    private static EventLoopGroup gameGroup;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
        gameGroup = new NioEventLoopGroup(2);
        System.out.println("game Netty: " + Version.identify().get("netty-transport"));
    }

    @AfterAll
    static void tearDown() {
        gameGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /** Echo server whose streams run through a bridge channel on the game's event loop. */
    private static QuicServer echoServer(CompletableFuture<Channel> serverChannel) throws Exception {
        return QuicServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), identity,
                TransportConfig.DEFAULT, stream -> {
                    QuicBridgeChannel ch = new QuicBridgeChannel(stream);
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ctx.write(msg);
                        }

                        @Override
                        public void channelReadComplete(ChannelHandlerContext ctx) {
                            ctx.flush();
                        }
                    });
                    gameGroup.register(ch).addListener(f -> serverChannel.complete(ch));
                });
    }

    private static Collector clientChannel(QuicServer server, Collector collector) throws Exception {
        QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
        QuicBridgeChannel ch = new QuicBridgeChannel(stream);
        ch.pipeline().addLast(collector);
        gameGroup.register(ch).sync();
        collector.channel = ch;
        return collector;
    }

    @Test
    void vanillaStylePipelineRoundTripsBytesOnTheGameEventLoop() throws Exception {
        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        try (QuicServer server = echoServer(serverChannel)) {
            Collector c = clientChannel(server, new Collector());
            assertTrue(c.channel.isActive());
            byte[] payload = new byte[300_000];
            new Random(7).nextBytes(payload);
            c.channel.writeAndFlush(Unpooled.wrappedBuffer(payload)).sync();
            assertArrayEquals(payload, c.await(payload.length));
            assertFalse(c.offLoop.get(), "pipeline events must run on the channel's own event loop");
            c.channel.close().sync();
        }
    }

    @Test
    void remoteAddressIsThePeersUdpAddress() throws Exception {
        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        try (QuicServer server = echoServer(serverChannel)) {
            Collector c = clientChannel(server, new Collector());
            c.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {1})).sync();
            Channel serverSide = serverChannel.get(5, TimeUnit.SECONDS);
            InetSocketAddress remote = (InetSocketAddress) serverSide.remoteAddress();
            assertEquals(InetAddress.getLoopbackAddress(), remote.getAddress());
            assertEquals(((InetSocketAddress) c.channel.localAddress()).getPort(), remote.getPort());
            c.channel.close().sync();
        }
    }

    @Test
    void tcpOptionsAreAcceptedWithoutThrowing() throws Exception {
        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        try (QuicServer server = echoServer(serverChannel)) {
            Collector c = clientChannel(server, new Collector());
            c.channel.config().setOption(ChannelOption.TCP_NODELAY, true);
            c.channel.config().setOption(ChannelOption.SO_KEEPALIVE, true);
            c.channel.config().setOption(ChannelOption.IP_TOS, 0x18);
            assertTrue(c.channel.config().setOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000));
            c.channel.close().sync();
        }
    }

    @Test
    void autoReadOffStopsDeliveryAndReadResumesIt() throws Exception {
        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        try (QuicServer server = echoServer(serverChannel)) {
            Collector c = clientChannel(server, new Collector());
            // On the event loop, after channelActive's first read: in Netty 4.2 the register future
            // completes before channelActive runs, and that first read may legitimately deliver.
            c.channel.eventLoop().submit(() -> c.channel.config().setAutoRead(false)).sync();
            c.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {1, 2, 3})).sync();
            Thread.sleep(500);
            assertEquals(0, c.size(), "nothing may arrive while auto-read is off");
            c.channel.read();
            assertArrayEquals(new byte[] {1, 2, 3}, c.await(3));
            c.channel.config().setAutoRead(true);
            c.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {4})).sync();
            assertEquals(4, c.await(4).length);
            c.channel.close().sync();
        }
    }

    @Test
    void aStalledReceiverMakesTheSenderUnwritableAndNothingIsLost() throws Exception {
        // The server reads nothing until told to, so QUIC flow control stalls the sender.
        CompletableFuture<QuicBridgeChannel> serverChannel = new CompletableFuture<>();
        try (QuicServer server = QuicServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), identity,
                TransportConfig.DEFAULT, stream -> {
                    QuicBridgeChannel ch = new QuicBridgeChannel(stream);
                    ch.config().setAutoRead(false);
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ctx.write(msg);
                        }

                        @Override
                        public void channelReadComplete(ChannelHandlerContext ctx) {
                            ctx.flush();
                        }
                    });
                    gameGroup.register(ch).addListener(f -> serverChannel.complete(ch));
                })) {
            Collector c = clientChannel(server, new Collector());
            byte[] chunk = new byte[16 * 1024];
            new Random(3).nextBytes(chunk);
            long limit = 64L << 20;
            CompletableFuture<Long> queuedWhenUnwritable = new CompletableFuture<>();
            c.channel.eventLoop().execute(new Runnable() {
                long queued;

                @Override
                public void run() {
                    while (c.channel.isWritable() && queued < limit) {
                        c.channel.writeAndFlush(Unpooled.wrappedBuffer(chunk));
                        queued += chunk.length;
                    }
                    if (!c.channel.isWritable()) {
                        queuedWhenUnwritable.complete(queued);
                    } else if (queued >= limit) {
                        queuedWhenUnwritable.completeExceptionally(new AssertionError("never became unwritable"));
                    } else {
                        c.channel.eventLoop().schedule(this, 10, TimeUnit.MILLISECONDS);
                    }
                }
            });
            long queued = queuedWhenUnwritable.get(20, TimeUnit.SECONDS);
            // Bounded by the stream and connection windows plus buffers, far below the 64 MiB cap.
            assertTrue(queued < (32L << 20), "queued " + queued + " bytes before backpressure");

            // Let the receiver read: everything arrives, echoed back, and the sender recovers.
            QuicBridgeChannel receiver = serverChannel.get(5, TimeUnit.SECONDS);
            receiver.eventLoop().execute(() -> receiver.config().setAutoRead(true));
            byte[] got = c.await((int) queued, 60);
            assertEquals(queued, got.length);
            for (int off = 0; off < got.length; off += chunk.length) {
                assertEquals(chunk[0], got[off]);
                assertEquals(chunk[chunk.length - 1], got[off + chunk.length - 1]);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!c.channel.isWritable() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(c.channel.isWritable(), "writable again once drained");
            c.channel.close().sync();
        }
    }

    @Test
    void closingEitherSideClosesTheOther() throws Exception {
        CompletableFuture<Channel> serverChannel = new CompletableFuture<>();
        try (QuicServer server = echoServer(serverChannel)) {
            Collector c = clientChannel(server, new Collector());
            c.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {1})).sync();
            Channel serverSide = serverChannel.get(5, TimeUnit.SECONDS);
            c.channel.close().sync();
            assertTrue(serverSide.closeFuture().await(5, TimeUnit.SECONDS), "server side must close");

            CompletableFuture<Channel> second = new CompletableFuture<>();
            try (QuicServer server2 = echoServer(second)) {
                Collector c2 = clientChannel(server2, new Collector());
                c2.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {1})).sync();
                second.get(5, TimeUnit.SECONDS).close().sync();
                assertTrue(c2.channel.closeFuture().await(5, TimeUnit.SECONDS), "client side must close");
            }
        }
    }

    static final class Collector extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final AtomicBoolean offLoop = new AtomicBoolean();
        volatile Channel channel;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!ctx.channel().eventLoop().inEventLoop()) {
                offLoop.set(true);
            }
            ByteBuf buf = (ByteBuf) msg;
            try {
                synchronized (this) {
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    bytes.write(b, 0, b.length);
                    notifyAll();
                }
            } finally {
                buf.release();
            }
        }

        synchronized int size() {
            return bytes.size();
        }

        byte[] await(int count) throws InterruptedException {
            return await(count, 10);
        }

        synchronized byte[] await(int count, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (bytes.size() < count) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return bytes.toByteArray();
        }
    }
}
