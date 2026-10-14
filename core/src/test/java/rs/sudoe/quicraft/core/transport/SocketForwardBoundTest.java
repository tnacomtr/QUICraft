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
import io.netty.handler.codec.quic.QuicCodecDispatcher;
import io.netty.handler.codec.quic.QuicConnectionIdGenerator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
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
 * does, through the target's executor. One test uses Netty's real dispatcher above the limiter, so
 * the delivery and the read-completion it owes a forwarded datagram are the ones a live listener
 * runs.
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

    /**
     * A forwarded burst must also close the target socket's read cycle. The real dispatcher
     * delivers a forwarded datagram by firing the read on the target's codec context and records
     * that it owes that context a read-complete ({@code ChannelHandlerContextDispatcher}); the
     * completion is only paid by {@code channelReadComplete} on the target's own pipeline, which
     * nothing fires for a datagram the target never read itself. The QUIC codec flushes and
     * completes the receive in exactly that method, so without the completion a connection whose
     * datagrams all arrive on a foreign socket, as they do after a NAT rebind, would never flush.
     *
     * <p>The dispatcher here is Netty's own, one instance shared by both sockets exactly as
     * {@code SocketDispatcher} is, with the recorder standing in for the codec where
     * {@code SocketDispatcher} installs it: after the dispatcher. The target loop is held while the
     * burst is handed over, then released with no further traffic, so nothing but the hand-over
     * itself can complete the read.
     */
    @Test
    void theLastForwardedDatagramOfABurstCompletesTheReadOnTheTargetSocket() throws Exception {
        EventLoopGroup loops = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        List<ByteBuf> datagrams = new ArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        try {
            Counters counters = new Counters(2);
            // Added to the source pipeline first: the dispatcher indexes the sockets in that order,
            // and the datagrams name TARGET, the second one.
            QuicCodecDispatcher dispatcher = new RecorderDispatcher();
            Channel source = channel(loops, counters.limiter(0), dispatcher);
            Channel sink = channel(loops, counters.limiter(1), dispatcher);
            Recorder codec = sink.pipeline().get(Recorder.class);
            assertNotSame(source.eventLoop(), sink.eventLoop(), "the two sockets must be on different loops");

            CountDownLatch held = new CountDownLatch(1);
            sink.eventLoop().execute(() -> {
                held.countDown();
                awaitQuietly(release, 60); // bounded, so a failing assertion cannot hold the loop for ever
            });
            assertTrue(held.await(5, TimeUnit.SECONDS), "the target loop is held");

            int burst = 4;
            for (int i = 0; i < burst; i++) {
                ByteBuf content = shortHeaderFor(TARGET);
                datagrams.add(content);
                source.pipeline().fireChannelRead(packet(content));
            }
            drain(source.eventLoop());
            assertEquals(burst, counters.pending.get(TARGET), "the whole burst is waiting on the target loop");

            // No more traffic, and nothing else on the target socket: only the hand-over can close
            // the read the dispatcher opened for these datagrams.
            release.countDown();
            drain(sink.eventLoop());
            assertTrue(codec.await(burst, 5), "every forwarded datagram reached the target codec");
            assertEquals(0, counters.pending.get(TARGET), "the in-flight count is released as the burst drains");
            assertEquals(Arrays.asList("read", "read", "read", "read", "complete"), codec.events,
                    "the burst's one read completion comes after its last read");

            // A later burst closes its own read: the count is per hand-over, not one completion ever.
            ByteBuf after = shortHeaderFor(TARGET);
            datagrams.add(after);
            source.pipeline().fireChannelRead(packet(after));
            drain(source.eventLoop());
            drain(sink.eventLoop());
            assertTrue(codec.await(burst + 1, 5), "a later datagram still reaches the target codec");
            assertEquals(2, codec.completed.get(), "the later burst fires its own read completion");
            assertEquals(Arrays.asList("read", "read", "read", "read", "complete", "read", "complete"), codec.events,
                    "each burst's completion follows its own last read: " + codec.events);
        } finally {
            // A failing assertion must not leave the target loop held.
            release.countDown();
            shutdown(loops);
            release(datagrams);
        }
    }

    @Test
    void aDatagramTheDispatcherMustSeeIsPassedOnUnchanged() throws Exception {
        EventLoopGroup loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Recorder codec = new Recorder();
        // A long header carries no index, so the dispatcher decodes none for it (and creates no
        // connection from it here): the bound must leave it to the dispatcher, on the socket that
        // received it.
        ByteBuf longHeader = longHeader();
        List<ByteBuf> datagrams = new ArrayList<>();
        datagrams.add(longHeader);
        try {
            Counters counters = new Counters(1);
            Channel socket = channel(loops, counters.limiter(0), codec);

            socket.pipeline().fireChannelRead(packet(longHeader));
            assertTrue(codec.await(1, 5), "a long header goes on to the dispatcher unchanged");
            drain(socket.eventLoop());

            assertEquals(1, longHeader.refCnt(), "a long header is neither queued nor released");
            assertEquals(0, counters.dropped.get(0), "the bound counted no forward drop for it");
            assertEquals(0, counters.malformed.get(0), "the bound counted no malformed drop for it");
        } finally {
            release(datagrams);
            shutdown(loops);
        }
    }

    /**
     * A short header that stops before the whole connection id, including the single byte {@code 40}
     * and {@code 40 00}, is a datagram the listener can never decode, and one Netty's dispatcher
     * throws for: {@code QuicCodecDispatcher.getDestinationConnectionId} slices the index out of the
     * id with {@code QuicHeaderParser.sliceCid}, which throws when there are too few bytes. Nothing
     * on a datagram pipeline catches that (the tail logs a stack trace and the buffer is never
     * released), so the bound releases it here instead and counts it against the socket that read
     * it.
     */
    @Test
    void aShortHeaderTruncatedBeforeTheConnectionIdIsReleasedAndCounted() throws Exception {
        EventLoopGroup loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Recorder codec = new Recorder();
        List<ByteBuf> datagrams = new ArrayList<>();
        ByteBuf twoBytes = shortHeader(0, 2); // `40 00`
        ByteBuf oneByte = shortHeader(0, 1); // `40`
        // One byte short of the whole connection id: readable == 1 + localConnectionIdLength would
        // be the first datagram the dispatcher can slice.
        ByteBuf oneByteShortOfTheId = shortHeader(0, Codecs.LOCAL_CONNECTION_ID_LENGTH);
        datagrams.add(twoBytes);
        datagrams.add(oneByte);
        datagrams.add(oneByteShortOfTheId);
        try {
            Counters counters = new Counters(1);
            Channel socket = channel(loops, counters.limiter(0), codec);

            socket.pipeline().fireChannelRead(packet(twoBytes));
            socket.pipeline().fireChannelRead(packet(oneByte));
            socket.pipeline().fireChannelRead(packet(oneByteShortOfTheId));
            drain(socket.eventLoop());

            assertEquals(0, codec.received.get(), "none of them may reach the dispatcher or the codec");
            assertEquals(0, twoBytes.refCnt(), "`40 00` is released");
            assertEquals(0, oneByte.refCnt(), "`40` is released");
            assertEquals(0, oneByteShortOfTheId.refCnt(),
                    "a short header one byte short of the whole connection id is released");
            assertEquals(3, counters.malformed.get(0), "every one is counted on the socket that read it");
            assertEquals(0, counters.dropped.get(0), "none of them is a forward drop");
            assertEquals(0, counters.pending.get(0), "none of them was queued");
        } finally {
            release(datagrams);
            shutdown(loops);
        }
    }

    /**
     * The pipeline one socket of a multi-socket listener really has
     * ({@code SocketDispatcher.initChannel}): the bound, Netty's real dispatcher, the socket's codec
     * and the {@link QuicServer.DatagramExceptionBackstop} last. A truncated short header must be
     * gone by the end of the bound: its parser would throw, and an exception that reaches the tail
     * is what floods the log (and leaks the datagram) in production. A capture handler after the
     * backstop stands in for the tail, and records what would have reached it.
     */
    @Test
    void aTruncatedShortHeaderNeverReachesTheParserOnTheProductionPipeline() throws Exception {
        EventLoopGroup loops = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        List<ByteBuf> datagrams = new ArrayList<>();
        ByteBuf twoBytes = shortHeader(0, 2);
        ByteBuf oneByte = shortHeader(0, 1);
        ByteBuf oneByteShortOfTheId = shortHeader(0, Codecs.LOCAL_CONNECTION_ID_LENGTH);
        datagrams.add(twoBytes);
        datagrams.add(oneByte);
        datagrams.add(oneByteShortOfTheId);
        try {
            Counters counters = new Counters(2);
            QuicCodecDispatcher dispatcher = new RecorderDispatcher();
            java.util.concurrent.atomic.AtomicLong lastLog = new java.util.concurrent.atomic.AtomicLong();
            Tail tail = new Tail();
            Channel source = channel(loops, counters.limiter(0), dispatcher,
                    new QuicServer.DatagramExceptionBackstop(lastLog, 0), tail);
            Channel sink = channel(loops, counters.limiter(1), dispatcher,
                    new QuicServer.DatagramExceptionBackstop(lastLog, 1));
            Recorder codec = sink.pipeline().get(Recorder.class);
            assertNotSame(source.eventLoop(), sink.eventLoop(), "the two sockets must be on different loops");

            source.pipeline().fireChannelRead(packet(twoBytes));
            source.pipeline().fireChannelRead(packet(oneByte));
            source.pipeline().fireChannelRead(packet(oneByteShortOfTheId));
            drain(source.eventLoop());
            drain(sink.eventLoop());

            assertEquals(0, codec.received.get(), "a truncated short header reaches no codec");
            assertEquals(0, twoBytes.refCnt(), "`40 00` is released before the dispatcher");
            assertEquals(0, oneByte.refCnt(), "`40` is released before the dispatcher");
            assertEquals(0, oneByteShortOfTheId.refCnt(), "the one-byte-short header is released");
            assertEquals(3, counters.malformed.get(0), "every one is counted on the socket that read it");
            assertEquals(0, tail.causes.size(), "nothing reached the pipeline tail: " + tail.causes);

            // The bound still does its other job on the same pipeline: a datagram for the other
            // socket is handed to that socket's loop and read there.
            ByteBuf forwarded = shortHeaderFor(TARGET);
            datagrams.add(forwarded);
            source.pipeline().fireChannelRead(packet(forwarded));
            assertTrue(codec.await(1, 5), "a datagram for the other socket still reaches its codec");
            drain(sink.eventLoop());
            assertEquals(0, tail.causes.size(), "a forwarded datagram reaches the tail with no exception either");
            assertEquals(0, counters.dropped.get(TARGET), "it was under the bound");
        } finally {
            release(datagrams);
            shutdown(loops);
        }
    }

    /** Stands in for Netty's pipeline tail: records every exception that would have reached it. */
    private static final class Tail extends ChannelInboundHandlerAdapter {
        final List<Throwable> causes = new CopyOnWriteArrayList<>();

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            causes.add(cause);
        }
    }

    /** The counters and the limiter slots one listener shares between its sockets. */
    private static final class Counters {
        final AtomicIntegerArray pending;
        final AtomicIntegerArray dropped;
        final AtomicIntegerArray malformed;
        final AtomicReferenceArray<ChannelHandlerContext> limiters;

        Counters(int sockets) {
            pending = new AtomicIntegerArray(sockets);
            dropped = new AtomicIntegerArray(sockets);
            malformed = new AtomicIntegerArray(sockets);
            limiters = new AtomicReferenceArray<>(sockets);
        }

        QuicServer.ForwardLimiter limiter(int socket) {
            return new QuicServer.ForwardLimiter(socket, Codecs.LOCAL_CONNECTION_ID_LENGTH,
                    pending, dropped, malformed, limiters);
        }
    }

    /**
     * Netty's real dispatcher, without a QUIC codec under it: the delivery of a forwarded datagram
     * and the read-complete it owes are the dispatcher's own code, and what stands in for the codec
     * is the {@link Recorder}, installed after the dispatcher exactly where
     * {@code SocketDispatcher} installs the socket's codec.
     */
    private static final class RecorderDispatcher extends QuicCodecDispatcher {
        RecorderDispatcher() {
            super(Codecs.LOCAL_CONNECTION_ID_LENGTH);
        }

        @Override
        protected void initChannel(Channel channel, int localConnectionIdLength, QuicConnectionIdGenerator idGenerator) {
            channel.pipeline().addLast(new Recorder());
        }
    }

    /**
     * Stands in for one socket's codec: counts the reads and read-completions that reach it, keeps
     * their order, and keeps the buffers to release.
     */
    private static final class Recorder extends ChannelInboundHandlerAdapter {
        final AtomicInteger received = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        /** Every read and read-completion in the order they arrived, as {@code read}/{@code complete}. */
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<ByteBuf> contents = new CopyOnWriteArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            contents.add(((DatagramPacket) msg).content());
            received.incrementAndGet();
            events.add("read");
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            completed.incrementAndGet();
            events.add("complete");
            ctx.fireChannelReadComplete();
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

    /**
     * A short header of exactly {@code bytes} bytes: the header byte {@code 40}, then the socket's
     * index when there is room for it, then zeroes. Shorter than
     * {@code 1 + Codecs.LOCAL_CONNECTION_ID_LENGTH} this is a datagram Netty's dispatcher throws for.
     */
    private static ByteBuf shortHeader(int socket, int bytes) {
        ByteBuf content = Unpooled.buffer(bytes);
        content.writeByte(0x40);
        if (bytes >= Byte.BYTES + Short.BYTES) {
            content.writeShort(socket);
        }
        content.writeZero(bytes - content.writerIndex());
        return content;
    }

    /** A long header (RFC 9000 §17.2): no index to decode, and the dispatcher leaves it alone. */
    private static ByteBuf longHeader() {
        ByteBuf content = Unpooled.buffer(24);
        content.writeByte(0xC0);
        content.writeZero(23);
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
