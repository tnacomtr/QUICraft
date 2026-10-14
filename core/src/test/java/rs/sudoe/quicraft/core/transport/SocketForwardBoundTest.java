// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;

/**
 * The forward bound of {@link QuicServer.ForwardLimiter} (docs/protocol.md §11): how many datagrams
 * one socket's loop may hand to another socket's loop, and what happens to the rest.
 *
 * <p>Two registered datagram channels on two loops stand in for two SO_REUSEPORT sockets, each with
 * a limiter in front of the handler that plays the codec, one shared set of counters, exactly as
 * {@code SocketDispatcher} shares them. No socket is bound and no QUIC native is needed: the
 * datagrams are fired into the source pipeline, and the hand-over is the same one the dispatcher
 * does, through the target's executor.
 */
class SocketForwardBoundTest {
    /** The socket the test datagrams name: the other one, so every hand-over crosses loops. */
    private static final int TARGET = 1;
    /** The bound is large; this is what has to be released over it. */
    private static final int OVER = 8;

    @Test
    void theBoundReleasesWhatOneSocketQueuedTooMuchForAnother() throws Exception {
        EventLoopGroup loops = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        List<ByteBuf> datagrams = new ArrayList<>();
        Recorder codec = new Recorder();
        // While it is held, everything handed to the target loop stays queued, so the bound fills.
        CountDownLatch release = new CountDownLatch(1);
        try {
            Counters counters = new Counters(2);
            Channel source = channel(loops, counters.limiter(0));
            Channel sink = channel(loops, counters.limiter(1), codec);
            assertNotSame(source.eventLoop(), sink.eventLoop(), "the two sockets must be on different loops");

            CountDownLatch held = new CountDownLatch(1);
            sink.eventLoop().execute(() -> {
                held.countDown();
                awaitQuietly(release, 60); // bounded, so a failing assertion cannot hold the loop for ever
            });
            assertTrue(held.await(5, TimeUnit.SECONDS), "the target loop is held");

            int limit = QuicServer.ForwardLimiter.MAX_PENDING_FORWARDS;
            for (int i = 0; i < limit + OVER; i++) {
                datagrams.add(shortHeaderFor(TARGET));
                source.pipeline().fireChannelRead(packet(datagrams.get(i)));
            }
            drain(source.eventLoop());

            assertEquals(limit, counters.pending.get(TARGET),
                    "the bound must let exactly " + limit + " datagrams through to the target socket");
            assertEquals(OVER, counters.dropped.get(TARGET), "the datagrams over the bound must be counted");
            for (int i = 0; i < limit; i++) {
                assertEquals(1, datagrams.get(i).refCnt(), "datagram " + i + " is under the bound and queued");
            }
            for (int i = limit; i < datagrams.size(); i++) {
                assertEquals(0, datagrams.get(i).refCnt(), "datagram " + i + " is over the bound and released");
            }

            release.countDown();
            drain(sink.eventLoop());
            assertEquals(limit, codec.received.get(), "the datagrams over the bound must never be queued");
            assertEquals(0, counters.pending.get(TARGET), "the in-flight count is released as the queue drains");
            assertEquals(OVER, counters.dropped.get(TARGET), "draining the queue drops nothing more");

            // A later datagram still gets through: the slot the bound freed is usable again.
            ByteBuf after = shortHeaderFor(TARGET);
            datagrams.add(after);
            source.pipeline().fireChannelRead(packet(after));
            assertTrue(codec.await(limit + 1, 5), "a datagram under the bound still reaches the target codec");
            assertEquals(OVER, counters.dropped.get(TARGET), "nothing else was dropped");
        } finally {
            // A failing assertion must not leave the target loop held, and the buffers are only
            // released once that loop has stopped touching them.
            release.countDown();
            shutdown(loops);
            release(datagrams);
        }
    }

    @Test
    void aDatagramForTheSocketItArrivedOnIsNotQueuedNorCounted() throws Exception {
        EventLoopGroup loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Recorder codec = new Recorder();
        ByteBuf content = shortHeaderFor(0);
        try {
            Counters counters = new Counters(1);
            Channel socket = channel(loops, counters.limiter(0), codec);

            socket.pipeline().fireChannelRead(packet(content));
            assertTrue(codec.await(1, 5), "a datagram for the socket it arrived on reaches that codec");
            drain(socket.eventLoop());

            assertEquals(0, counters.pending.get(0), "same-loop delivery is not a queued forward");
            assertEquals(0, counters.dropped.get(0), "same-loop delivery is never dropped");
            assertEquals(1, content.refCnt(), "the bound neither queues nor releases it");
        } finally {
            release(java.util.Collections.singletonList(content));
            shutdown(loops);
        }
    }

    @Test
    void aDatagramTheDispatcherMustSeeIsPassedOnUnchanged() throws Exception {
        EventLoopGroup loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Recorder codec = new Recorder();
        // A long header, and a short header with too few bytes for the whole connection id: the
        // dispatcher decodes no index for either (and throws for the truncated one), so the bound
        // must leave both to it, on the socket that received them.
        ByteBuf longHeader = Unpooled.buffer(24);
        longHeader.writeByte(0xC0);
        longHeader.writeZero(23);
        ByteBuf truncated = Unpooled.buffer(8);
        truncated.writeByte(0x40);
        truncated.writeZero(7);
        List<ByteBuf> datagrams = new ArrayList<>();
        datagrams.add(longHeader);
        datagrams.add(truncated);
        try {
            Counters counters = new Counters(1);
            Channel socket = channel(loops, counters.limiter(0), codec);

            socket.pipeline().fireChannelRead(packet(longHeader));
            socket.pipeline().fireChannelRead(packet(truncated));
            assertTrue(codec.await(2, 5), "an undecodable datagram goes on to the dispatcher unchanged");
            drain(socket.eventLoop());

            assertEquals(1, longHeader.refCnt(), "a long header is neither queued nor released");
            assertEquals(1, truncated.refCnt(), "a truncated short header is neither queued nor released");
            assertEquals(0, counters.dropped.get(0), "the bound counted no drop for either");
        } finally {
            release(datagrams);
            shutdown(loops);
        }
    }

    /** The counters and the limiter slots one listener shares between its sockets. */
    private static final class Counters {
        final AtomicIntegerArray pending;
        final AtomicIntegerArray dropped;
        final AtomicReferenceArray<ChannelHandlerContext> limiters;

        Counters(int sockets) {
            pending = new AtomicIntegerArray(sockets);
            dropped = new AtomicIntegerArray(sockets);
            limiters = new AtomicReferenceArray<>(sockets);
        }

        QuicServer.ForwardLimiter limiter(int socket) {
            return new QuicServer.ForwardLimiter(socket, Codecs.LOCAL_CONNECTION_ID_LENGTH,
                    pending, dropped, limiters);
        }
    }

    /** Stands in for one socket's codec: counts what reaches it and keeps the buffers to release. */
    private static final class Recorder extends ChannelInboundHandlerAdapter {
        final AtomicInteger received = new AtomicInteger();
        final List<ByteBuf> contents = new CopyOnWriteArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            contents.add(((DatagramPacket) msg).content());
            received.incrementAndGet();
        }

        /** Waits until {@code count} datagrams reached this codec. */
        boolean await(int count, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (received.get() < count && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            return received.get() >= count;
        }
    }

    /** A registered datagram channel on one of {@code loops}, with no socket bound to it. */
    private static Channel channel(EventLoopGroup loops, ChannelHandler... handlers) throws Exception {
        Channel channel = new NioDatagramChannel();
        channel.pipeline().addLast(handlers);
        loops.register(channel).sync();
        return channel;
    }

    /** A short-header datagram (RFC 9000 §17.3) whose destination connection id names {@code socket}. */
    private static ByteBuf shortHeaderFor(int socket) {
        ByteBuf content = Unpooled.buffer(24);
        content.writeByte(0x40);
        content.writeShort(socket);
        content.writeZero(18); // the rest of the 20-byte connection id
        content.writeZero(3); // a little payload
        return content;
    }

    private static DatagramPacket packet(ByteBuf content) {
        return new DatagramPacket(content, loopback(1), loopback(2));
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    /** Returns once the loop has run everything that was queued for it before this call. */
    private static void drain(EventLoop loop) throws InterruptedException {
        CountDownLatch drained = new CountDownLatch(1);
        loop.execute(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS), "the loop did not get through its queue");
    }

    /** Waits for {@code latch}, at most {@code seconds}, restoring the interrupt flag. */
    private static void awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void release(List<ByteBuf> datagrams) {
        for (ByteBuf content : datagrams) {
            if (content.refCnt() > 0) {
                content.release();
            }
        }
    }

    private static void shutdown(EventLoopGroup loops) {
        loops.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
