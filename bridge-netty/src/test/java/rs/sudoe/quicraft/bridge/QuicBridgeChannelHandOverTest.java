// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/**
 * The {@code /3} hand-over of stream 0 (docs/protocol.md 12.10 to 12.13, 13.2; plan gaps G14 to
 * G16), over a fake {@link QuicByteStream} so the state of the stream is the test's to set. The
 * real transport is exercised by {@link QuicBridgeChannelTest}.
 */
class QuicBridgeChannelHandOverTest {
    private static EventLoopGroup loop;

    @BeforeAll
    static void setUp() {
        loop = new NioEventLoopGroup(1);
    }

    @AfterAll
    static void tearDown() {
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void handOverStopsReadingAndReturnsOneOwnedLeftoverInWireOrder() throws Exception {
        FakeStream stream = new FakeStream();
        Channel channel = open(stream);
        Collector collector = new Collector();
        channel.pipeline().addLast(collector);
        // Auto-read off, so what the stream delivers is held here instead of reaching the pipeline.
        onLoop(channel, () -> channel.config().setAutoRead(false));
        onLoop(channel, () -> stream.deliver(new byte[] {0x0a, 0x0b, 0x0c}));
        assertEquals(3, ((QuicBridgeChannel) channel).heldBytesForTest(),
                "the bytes read but not delivered are held, not dropped");

        ByteBuf cumulation = Unpooled.wrappedBuffer(new byte[] {0x01, 0x02});
        AtomicReference<CompletableFuture<ByteBuf>> handed = new AtomicReference<>();
        onLoop(channel, () -> handed.set(((QuicBridgeChannel) channel).handOver(cumulation)));
        CompletableFuture<ByteBuf> future = handed.get();

        assertTrue(future.isDone(), "nothing was queued, so the hand-over completes at once");
        ByteBuf leftover = future.get();
        try {
            assertEquals(1, leftover.refCnt(), "the caller owns exactly one reference");
            // Wire order (12.13): what the caller still holds undecoded was read before what the
            // channel read but has not delivered.
            assertArrayEquals(new byte[] {0x01, 0x02, 0x0a, 0x0b, 0x0c}, bytes(leftover));
        } finally {
            leftover.release();
        }
        assertEquals(1, cumulation.refCnt(), "the caller's own cumulation is copied, not consumed");
        assertArrayEquals(new byte[] {0x01, 0x02}, bytes(cumulation));
        cumulation.release();

        assertEquals(0, ((QuicBridgeChannel) channel).heldBytesForTest(), "nothing is held any more");
        assertEquals(Boolean.FALSE, stream.autoRead.get(stream.autoRead.size() - 1),
                "12.10: the channel stops reading stream 0");
        assertEquals(0, collector.bytes.size(), "none of it reaches the game pipeline (12.13)");

        channel.close().sync();
    }

    @Test
    void handOverWaitsForTheWritesQueuedBeforeItOnANonWritableStream() throws Exception {
        FakeStream stream = new FakeStream();
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        loop.register(channel).sync();
        TrackingAllocator allocator = new TrackingAllocator(false);
        channel.config().setAllocator(allocator);
        stream.writable = false; // the switch packet cannot go out yet
        byte[] switchPacket = {0x10, 0x11, 0x12, 0x13};
        onLoop(channel, () -> channel.writeAndFlush(Unpooled.wrappedBuffer(switchPacket)));

        ByteBuf cumulation = Unpooled.wrappedBuffer(new byte[] {0x21});
        AtomicReference<CompletableFuture<ByteBuf>> handed = new AtomicReference<>();
        onLoop(channel, () -> handed.set(channel.handOver(cumulation)));
        CompletableFuture<ByteBuf> future = handed.get();

        assertFalse(future.isDone(),
                "(a): the hand-over completes only after the writes queued before it are out");
        assertTrue(stream.writtenBytes().length == 0, "nothing has reached the stream yet");

        // The stream drains: the queued switch packet goes out first, and only then is the
        // hand-over honoured, so no record can overtake it.
        stream.writability(true);
        ByteBuf leftover = future.get(5, TimeUnit.SECONDS);
        try {
            assertArrayEquals(new byte[] {0x21}, bytes(leftover));
        } finally {
            leftover.release();
        }
        assertArrayEquals(switchPacket, stream.writtenBytes(),
                "the switch packet reached the stream, and the marker added no bytes");
        cumulation.release();
        allocator.assertAllReleased("the drained hand-over must release every buffer it took, ");

        channel.close().sync();
    }

    @Test
    void handOverFailsAndReleasesTheLeftoverWhenTheChannelClosesWithWritesPending() throws Exception {
        FakeStream stream = new FakeStream();
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        loop.register(channel).sync();
        TrackingAllocator allocator = new TrackingAllocator(false);
        channel.config().setAllocator(allocator);
        stream.writable = false;
        onLoop(channel, () -> channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {0x31})));

        AtomicReference<CompletableFuture<ByteBuf>> handed = new AtomicReference<>();
        onLoop(channel, () -> handed.set(channel.handOver(null)));
        CompletableFuture<ByteBuf> future = handed.get();
        assertFalse(future.isDone());

        channel.close().sync();
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS),
                "the hand-over fails rather than silently reordering");
        assertTrue(future.isCompletedExceptionally());
        allocator.assertAllReleased("a failed hand-over must release the leftover it took ownership of,");
    }

    @Test
    void closingAfterHandOverNeverClosesTheStream() throws Exception {
        FakeStream detached = new FakeStream();
        Channel handedOver = open(detached);
        onLoop(handedOver, () -> ((QuicBridgeChannel) handedOver).handOver(null));
        handedOver.close().sync();
        assertEquals(0, detached.closeCount.get(),
                "13.14/G15: the lane session alone ends stream 0, never the bridge's close path");

        FakeStream ordinary = new FakeStream();
        Channel plain = open(ordinary);
        plain.close().sync();
        assertEquals(1, ordinary.closeCount.get(),
                "without a hand-over the bridge still closes its stream, as on /2");
    }

    @Test
    void aWriteSubmittedAfterHandOverFailsAndNeverReachesTheStream() throws Exception {
        FakeStream stream = new FakeStream();
        Channel channel = open(stream);
        onLoop(channel, () -> ((QuicBridgeChannel) channel).handOver(null));
        assertEquals(0, stream.writtenBytes().length);

        AtomicReference<ChannelFuture> write = new AtomicReference<>();
        onLoop(channel, () -> write.set(channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {0x41}))));
        assertTrue(write.get().isDone());
        assertFalse(write.get().isSuccess(),
                "the lane session owns stream 0: a late bridge write is a bug, not a byte");
        assertEquals(0, stream.writtenBytes().length);

        channel.close().sync();
    }

    @Test
    void dataDeliveredAfterHandOverNeverReachesThePipelineAndFailsClosed() throws Exception {
        FakeStream stream = new FakeStream();
        Channel channel = open(stream);
        Collector collector = new Collector();
        channel.pipeline().addLast(collector);
        onLoop(channel, () -> ((QuicBridgeChannel) channel).handOver(null));
        onLoop(channel, () -> stream.deliver(new byte[] {0x51, 0x52}));

        assertEquals(0, collector.bytes.size(), "no record byte may reach the game pipeline twice");
        assertNotNull(collector.cause.get(),
                "a byte that arrives anyway is a caller bug, and dropping it silently would desync "
                        + "the record stream");
        assertTrue(collector.cause.get().getMessage().contains("handed to the lane session"));
        channel.close().sync();
    }

    @Test
    void handOverRefusesASecondCallAndAnOffLoopCall() throws Exception {
        FakeStream stream = new FakeStream();
        Channel channel = open(stream);
        assertThrows(IllegalStateException.class, () -> ((QuicBridgeChannel) channel).handOver(null),
                "12.10: the cutover runs on the connection's event loop");
        onLoop(channel, () -> ((QuicBridgeChannel) channel).handOver(null));
        onLoop(channel, () -> assertThrows(IllegalStateException.class,
                () -> ((QuicBridgeChannel) channel).handOver(null)));
        channel.close().sync();
    }

    @Test
    void aFailedLeftoverCopyReleasesEveryHeldBuffer() throws Exception {
        FakeStream stream = new FakeStream();
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        loop.register(channel).sync();
        ThrowingAllocator allocator = new ThrowingAllocator(false);
        channel.config().setAllocator(allocator);
        onLoop(channel, () -> channel.config().setAutoRead(false));
        onLoop(channel, () -> {
            stream.deliver(new byte[] {1, 2, 3});
            stream.deliver(new byte[] {4, 5});
        });
        assertEquals(5, channel.heldBytesForTest(), "both chunks are held, not delivered");

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        onLoop(channel, () -> {
            try {
                channel.handOver(null);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        assertNotNull(thrown.get(), "a failed copy must never hand out a partial leftover");
        assertEquals(0, channel.heldBytesForTest(),
                "the held data is emptied on the failure path, so nothing is owned by nobody");
        allocator.assertAllReleased("a failed copy must release the polled buffer, the rest of the held"
                + " data and the leftover, ");
        channel.close().sync();
    }

    private static Channel open(FakeStream stream) throws Exception {
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        loop.register(channel).sync();
        return channel;
    }

    private static void onLoop(Channel channel, Runnable task) throws Exception {
        channel.eventLoop().submit(task).sync();
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] out = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), out);
        return out;
    }

    /**
     * Remembers every buffer it hands out, so a test can prove the hand-over released its own:
     * the {@code controlLeftover}, and the zero-byte marker it appends to the outbound buffer.
     */
    static class TrackingAllocator extends AbstractByteBufAllocator {
        final List<ByteBuf> all = Collections.synchronizedList(new ArrayList<ByteBuf>());

        TrackingAllocator(boolean direct) {
            super(direct);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
            all.add(buffer);
            return buffer;
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity);
            all.add(buffer);
            return buffer;
        }

        void assertAllReleased(String what) {
            synchronized (all) {
                for (int i = 0; i < all.size(); i++) {
                    assertEquals(0, all.get(i).refCnt(), what + " buffer #" + i + " leaked");
                }
            }
        }
    }

    /**
     * Hands out buffers whose {@code writeBytes(ByteBuf)} copy fails, the way an allocation or a
     * copy inside {@code handOver} can, and tracks every one of them.
     */
    static final class ThrowingAllocator extends TrackingAllocator {
        ThrowingAllocator(boolean direct) {
            super(direct);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = new ThrowingByteBuf(this, initialCapacity, maxCapacity);
            all.add(buffer);
            return buffer;
        }
    }

    /** A buffer that cannot be copied into, but can be written from a ByteBuffer as onData does. */
    static final class ThrowingByteBuf extends UnpooledHeapByteBuf {
        ThrowingByteBuf(ByteBufAllocator allocator, int initialCapacity, int maxCapacity) {
            super(allocator, initialCapacity, maxCapacity);
        }

        @Override
        public ByteBuf writeBytes(ByteBuf src) {
            throw new IllegalStateException("the copy failed");
        }
    }

    /** Collects what reaches the pipeline and remembers the first exception. */
    static final class Collector extends ChannelInboundHandlerAdapter {        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final AtomicReference<Throwable> cause = new AtomicReference<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buffer = (ByteBuf) msg;
            try {
                byte[] b = new byte[buffer.readableBytes()];
                buffer.readBytes(b);
                synchronized (bytes) {
                    bytes.write(b, 0, b.length);
                }
            } finally {
                buffer.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
            cause.set(t);
        }
    }

    /**
     * A {@link QuicByteStream} the test drives directly: what the stream delivers, whether it is
     * writable, and what was written to it are all the test's to set and read.
     */
    static final class FakeStream implements QuicByteStream {
        final List<byte[]> written = Collections.synchronizedList(new ArrayList<byte[]>());
        final List<Boolean> autoRead = Collections.synchronizedList(new ArrayList<Boolean>());
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger readCount = new AtomicInteger();
        volatile Listener listener;
        volatile boolean writable = true;
        volatile boolean open = true;

        @Override
        public InetSocketAddress remoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        }

        @Override
        public InetSocketAddress localAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25566);
        }

        @Override
        public void setListener(Listener newListener) {
            this.listener = newListener;
        }

        @Override
        public void write(ByteBuffer data) {
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            written.add(copy);
        }

        @Override
        public void flush() {}

        @Override
        public boolean isWritable() {
            return writable;
        }

        @Override
        public void setAutoRead(boolean enabled) {
            autoRead.add(Boolean.valueOf(enabled));
        }

        @Override
        public void read() {
            readCount.incrementAndGet();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            open = false;
        }

        @Override
        public CompletableFuture<Map<String, Long>> connectionStats() {
            return CompletableFuture.completedFuture(Collections.<String, Long>emptyMap());
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public boolean inEventLoop() {
            return true;
        }

        /** As the transport would: deliver bytes to the bridge's listener. */
        void deliver(byte[] data) {
            listener.onData(ByteBuffer.wrap(data));
        }

        /** As the transport would: report the stream drained. */
        void writability(boolean writable) {
            this.writable = writable;
            Listener current = listener;
            if (current != null) {
                current.onWritabilityChanged(writable);
            }
        }

        /** Everything written to this stream, in order. */
        byte[] writtenBytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            synchronized (written) {
                for (byte[] chunk : written) {
                    out.write(chunk, 0, chunk.length);
                }
            }
            return out.toByteArray();
        }
    }
}
