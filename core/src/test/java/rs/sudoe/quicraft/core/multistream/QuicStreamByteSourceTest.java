// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.transport.LaneLoopback;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/** Real QUIC loopback: the server side reads its peer's streams through a {@link QuicStreamByteSource}. */
final class QuicStreamByteSourceTest {
    private static final long WAIT_MILLIS = 10_000;

    /** A loopback pair whose server side attaches each peer-created stream to a source, in arrival order. */
    private static final class Fixture implements AutoCloseable {
        final QuicStreamByteSource source = new QuicStreamByteSource(3);
        final BlockingQueue<QuicStreamChannel> serverStreams = new LinkedBlockingQueue<QuicStreamChannel>();
        final AtomicInteger attached = new AtomicInteger();
        final LaneLoopback lanes;

        Fixture(TransportConfig config) throws Exception {
            LaneLoopback.Options options = new LaneLoopback.Options();
            options.server = config;
            options.client = config;
            options.serverSink = stream -> {
                source.attach(attached.getAndIncrement(), stream);
                serverStreams.add(stream);
            };
            lanes = LaneLoopback.open(options);
        }

        /** Waits for the server side of the next stream the client wrote on. */
        QuicStreamChannel acceptPeerStream() throws InterruptedException {
            QuicStreamChannel stream = serverStreams.poll(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            assertNotNull(stream, "server saw no stream");
            return stream;
        }

        @Override
        public void close() {
            lanes.close();
        }
    }

    /** Runs a task on the connection's event loop and returns its result. */
    private static <T> T onLoop(EventLoop loop, Callable<T> task) throws Exception {
        try {
            return loop.submit(task).get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    /** Writes {@code length} pattern bytes on the client, accepts the server side and waits until {@code atLeast} are buffered. */
    private static QuicStreamChannel fill(Fixture fx, QuicStreamChannel[] client, int length, int atLeast)
            throws Exception {
        client[0] = fx.lanes.clientStream();
        client[0].writeAndFlush(Unpooled.wrappedBuffer(pattern(length)));
        QuicStreamChannel server = fx.acceptPeerStream();
        EventLoop loop = server.eventLoop();
        // One read with room asks QUIC for one message of up to the room; it is not copied out here.
        onLoop(loop, () -> fx.source.read(0, new byte[1024], 0, 1024));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (onLoop(loop, () -> fx.source.buffered(0)) < atLeast) {
            if (System.nanoTime() > deadline) {
                fail("fewer than " + atLeast + " bytes buffered");
            }
            Thread.sleep(5);
        }
        return server;
    }

    @Test
    void readsNeverExceedTheGrant() throws Exception {
        try (Fixture fx = new Fixture(TransportConfig.multistream())) {
            QuicStreamChannel[] client = new QuicStreamChannel[1];
            byte[] payload = pattern(64 * 1024);
            client[0] = fx.lanes.clientStream();
            client[0].writeAndFlush(Unpooled.wrappedBuffer(payload));
            QuicStreamChannel server = fx.acceptPeerStream();
            EventLoop loop = server.eventLoop();

            int total = 0;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            while (total < 8192) {
                byte[] chunk = new byte[1000];
                int got = onLoop(loop, () -> fx.source.read(0, chunk, 0, 1000));
                assertTrue(got <= 1000, "read returned " + got + " for a grant of 1000");
                int buffered = onLoop(loop, () -> fx.source.buffered(0));
                assertTrue(buffered <= 1000, "buffered " + buffered + " beyond the grant");
                for (int i = 0; i < got; i++) {
                    assertEquals(payload[total + i], chunk[i], "byte " + (total + i));
                }
                total += got;
                if (got == 0) {
                    assertTrue(System.nanoTime() < deadline, "no bytes arrived");
                    Thread.sleep(5);
                }
            }
        }
    }

    @Test
    void optInTraceObservesConsumedGrantWithoutIssuingARead() throws Exception {
        String previous = System.getProperty("quicraft.v3.trace");
        System.setProperty("quicraft.v3.trace", "true");
        try (Fixture fx = new Fixture(TransportConfig.multistream())) {
            QuicStreamChannel[] client = new QuicStreamChannel[1];
            QuicStreamChannel server = fill(fx, client, 64 * 1024, 1024);
            onLoop(server.eventLoop(), () -> {
                int buffered = fx.source.traceBufferedBytes();
                assertEquals(1024, buffered);
                assertEquals(buffered, fx.source.read(0, new byte[buffered], 0, buffered));
                int before = fx.source.readCalls(0);
                assertTrue(fx.source.traceGrantConsumed(1));
                assertTrue(fx.source.traceSummary().contains("receivedBytes=1024"));
                assertEquals(before, fx.source.readCalls(0), "diagnostics must not request peer credit");
                fx.source.read(0, new byte[1], 0, 1);
                assertTrue(fx.source.traceSummary().contains("oneByteGrants=1"));
                return null;
            });
        } finally {
            if (previous == null) System.clearProperty("quicraft.v3.trace");
            else System.setProperty("quicraft.v3.trace", previous);
        }
    }

    @Test
    void noReadCallWhileTheGrantIsExhausted() throws Exception {
        try (Fixture fx = new Fixture(TransportConfig.multistream())) {
            QuicStreamChannel[] client = new QuicStreamChannel[1];
            QuicStreamChannel server = fill(fx, client, 64 * 1024, 64);
            EventLoop loop = server.eventLoop();

            int before = onLoop(loop, () -> fx.source.readCalls(0));
            // 16 bytes are asked for, far below what is buffered, so the stream keeps its grant: no read call.
            int got = onLoop(loop, () -> fx.source.read(0, new byte[16], 0, 16));
            assertEquals(16, got);
            int after = onLoop(loop, () -> fx.source.readCalls(0));
            assertEquals(before, after, "a read was issued while the grant was exhausted");

            // Positive control: draining the buffer leaves room, so the read call is made.
            onLoop(loop, () -> fx.source.read(0, new byte[16 * 1024], 0, 16 * 1024));
            int drained = onLoop(loop, () -> fx.source.readCalls(0));
            assertTrue(drained > after, "a read with room must reach the channel");
        }
    }

    /**
     * The read that finishes a record body asks for exactly the bytes still wanted. Once they are copied out, the
     * grant is used up: no channel read (and so no new peer credit) may fetch the next record before its admission.
     * A grant only partly filled from the buffer asks the channel for the rest, not for the whole grant again.
     */
    @Test
    void finishingABodyDoesNotPrefetchTheNextRecord() throws Exception {
        try (Fixture fx = new Fixture(TransportConfig.multistream())) {
            QuicStreamChannel[] client = new QuicStreamChannel[1];
            QuicStreamChannel server = fill(fx, client, 64 * 1024, 64);
            EventLoop loop = server.eventLoop();

            int buffered = onLoop(loop, () -> fx.source.buffered(0));
            int before = onLoop(loop, () -> fx.source.readCalls(0));
            int got = onLoop(loop, () -> fx.source.read(0, new byte[buffered], 0, buffered));
            assertEquals(buffered, got, "the whole body was buffered");
            assertEquals(0, (int) onLoop(loop, () -> fx.source.buffered(0)));
            assertEquals(before, (int) onLoop(loop, () -> fx.source.readCalls(0)),
                    "a read call after the grant was filled prefetches the next record");

            // A grant of 7 more than is buffered: the buffered bytes are copied and the channel is asked for 7.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            onLoop(loop, () -> fx.source.read(0, new byte[64], 0, 64));
            int partial;
            while ((partial = onLoop(loop, () -> fx.source.buffered(0))) == 0) {
                assertTrue(System.nanoTime() < deadline, "no bytes arrived");
                Thread.sleep(5);
            }
            int have = partial;
            int calls = onLoop(loop, () -> fx.source.readCalls(0));
            int copied = onLoop(loop, () -> fx.source.read(0, new byte[have + 7], 0, have + 7));
            assertEquals(have, copied);
            assertEquals(calls + 1, (int) onLoop(loop, () -> fx.source.readCalls(0)), "the unfilled rest is asked for");
            assertEquals(7, (int) onLoop(loop, () -> ReadGrantAllocator.limitOf(server)),
                    "the channel is asked only for the unfilled rest of the grant");
        }
    }

    @Test
    void buffersAreReleasedOnClose() throws Exception {
        try (Fixture fx = new Fixture(TransportConfig.multistream())) {
            QuicStreamChannel[] client = new QuicStreamChannel[1];
            QuicStreamChannel server = fill(fx, client, 64 * 1024, 64);
            EventLoop loop = server.eventLoop();

            ByteBuf head = onLoop(loop, () -> fx.source.headForTest(0));
            assertNotNull(head);
            assertTrue(head.refCnt() > 0);
            onLoop(loop, () -> {
                fx.source.close();
                return null;
            });
            assertEquals(0, head.refCnt(), "close must release the buffered ByteBuf");
            assertEquals(0, (int) onLoop(loop, () -> fx.source.buffered(0)));
            assertEquals(0, (int) onLoop(loop, () -> fx.source.read(0, new byte[16], 0, 16)));
        }
    }

    @Test
    void buffersAreReleasedOnResetAndReadsThrow() throws Exception {
        try (Fixture fx = new Fixture(TransportConfig.multistream())) {
            QuicStreamChannel[] client = new QuicStreamChannel[1];
            QuicStreamChannel server = fill(fx, client, 64 * 1024, 64);
            EventLoop loop = server.eventLoop();

            ByteBuf head = onLoop(loop, () -> fx.source.headForTest(0));
            assertNotNull(head);
            client[0].shutdownOutput(0).sync();

            // The reset is seen once the stream is read; each read with room gives QUIC that chance.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
            boolean thrown = false;
            while (!thrown) {
                try {
                    onLoop(loop, () -> fx.source.read(0, new byte[16 * 1024], 0, 16 * 1024));
                } catch (IOException expected) {
                    thrown = true;
                }
                if (!thrown) {
                    assertTrue(System.nanoTime() < deadline, "reset never surfaced");
                    Thread.sleep(5);
                }
            }
            assertEquals(0, head.refCnt(), "reset must release the buffered ByteBuf");
            assertEquals(0, (int) onLoop(loop, () -> fx.source.buffered(0)));
            // The reset stays sticky.
            assertThrows(IOException.class,
                    () -> onLoop(loop, () -> fx.source.read(0, new byte[16], 0, 16)));
        }
    }
}
