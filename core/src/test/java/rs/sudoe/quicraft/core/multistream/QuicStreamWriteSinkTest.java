// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;
import rs.sudoe.quicraft.core.transport.LaneLoopback;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/** Real QUIC loopback: the client side writes through a {@link QuicStreamWriteSink} driven by a real send driver. */
final class QuicStreamWriteSinkTest {
    private static final long WAIT_MILLIS = 10_000;
    /** Stream window small enough that a 48 KiB slice cannot be accepted until the peer reads. */
    private static final long SMALL_WINDOW = 16 * 1024;
    /** Bidirectional stream limit the server grants: the sink's three client streams need room. */
    private static final int STREAMS = 16;

    /** A send driver with its pool, on a manual clock. */
    private static final class Rig {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final PressureRanking ranking = new PressureRanking();
        final OutboundPool pool;
        final Object owner = new Object();
        final SessionSendDriver driver;

        Rig() throws Exception {
            NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
            pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, ranking.view(now::get));
            ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
            driver = new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, owner,
                    InetAddress.getLoopbackAddress(), null, now::get);
        }
    }

    /** An outbound packet of a VarInt length prefix and {@code content} bytes. */
    private static OutboundPacket frame(int content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.write(out, content);
        byte[] body = new byte[content];
        Arrays.fill(body, (byte) 7);
        out.write(body, 0, body.length);
        return new OutboundPacket(out.toByteArray());
    }

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

    /** Pool bytes charged to the owner, read on the loop that drives the driver. */
    private static long reserved(Rig rig, EventLoop loop) throws Exception {
        return onLoop(loop, () -> rig.pool.reserved(rig.owner));
    }

    /** Waits until the owner's charge falls to {@code target}; fails with the last value seen otherwise. */
    private static void awaitReserved(Rig rig, EventLoop loop, long target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        long last = reserved(rig, loop);
        while (last != target) {
            if (System.nanoTime() > deadline) {
                fail("charge " + last + ", expected " + target);
            }
            Thread.sleep(5);
            last = reserved(rig, loop);
        }
    }

    /** Client streams for each logical stream, attached to a sink over {@code driver}. */
    private static QuicStreamWriteSink sinkFor(Rig rig, LaneLoopback lanes, QuicStreamChannel[] channels)
            throws Exception {
        QuicStreamWriteSink sink = new QuicStreamWriteSink(rig.driver, channels.length);
        for (int i = 0; i < channels.length; i++) {
            channels[i] = lanes.clientStream();
            sink.attach(i, channels[i]);
        }
        return sink;
    }

    private static LaneLoopback open(TransportConfig config) throws Exception {
        LaneLoopback.Options options = new LaneLoopback.Options();
        options.server = config;
        options.client = config;
        options.serverMaxStreamsBidi = STREAMS;
        return LaneLoopback.open(options);
    }

    @Test
    void drainWritesTheGrantedRecordAndReleasesTheCharge() throws Exception {
        Rig rig = new Rig();
        try (LaneLoopback lanes = open(TransportConfig.multistream())) {
            QuicStreamChannel[] channels = new QuicStreamChannel[3];
            QuicStreamWriteSink sink = sinkFor(rig, lanes, channels);
            EventLoop loop = channels[0].eventLoop();
            long base = reserved(rig, loop);

            // Fits in one stream window, so its write completes without the peer reading.
            onLoop(loop, () -> rig.driver.submit(frame(1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0));
            assertTrue(reserved(rig, loop) > base, "the record is charged at submission");

            onLoop(loop, () -> {
                sink.drain();
                return null;
            });
            awaitReserved(rig, loop, base);
        }
    }

    @Test
    void outboundChargeHeldUntilWriteCompletes() throws Exception {
        Rig rig = new Rig();
        TransportConfig small = TransportConfig.builder().multistream(true)
                .initialMaxStreamData(SMALL_WINDOW).build();
        LaneLoopback.Options options = new LaneLoopback.Options();
        options.server = small;
        options.client = small;
        options.serverMaxStreamsBidi = STREAMS;
        try (LaneLoopback lanes = LaneLoopback.open(options)) {
            QuicStreamChannel[] channels = new QuicStreamChannel[3];
            QuicStreamWriteSink sink = sinkFor(rig, lanes, channels);
            EventLoop loop = channels[0].eventLoop();
            long base = reserved(rig, loop);

            // One slice, larger than the window. The server never reads, so the peer grants no credit and the write
            // stays pending after the last slice is submitted: the charge must still be held.
            onLoop(loop, () -> rig.driver.submit(frame(48 * 1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0));
            onLoop(loop, () -> {
                sink.drain();
                return null;
            });
            long held = reserved(rig, loop);
            assertTrue(held > base, "the charge must stay held while the write is pending");
            Thread.sleep(200);
            assertEquals(held, reserved(rig, loop), "a pending write must keep its charge");
        }
    }

    @Test
    void failedWriteReleasesTheCharge() throws Exception {
        Rig rig = new Rig();
        try (LaneLoopback lanes = open(TransportConfig.multistream())) {
            QuicStreamChannel[] channels = new QuicStreamChannel[3];
            QuicStreamWriteSink sink = sinkFor(rig, lanes, channels);
            EventLoop loop = channels[0].eventLoop();
            long base = reserved(rig, loop);

            onLoop(loop, () -> rig.driver.submit(frame(1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0));
            channels[0].close().sync();
            SessionSendDriver.Write write = onLoop(loop, () -> rig.driver.poll(stream -> true));
            assertNotNull(write);
            onLoop(loop, () -> {
                sink.write(write);
                return null;
            });
            awaitReserved(rig, loop, base);
        }
    }

    @Test
    void unwritableStreamIsNotPolled() throws Exception {
        Rig rig = new Rig();
        try (LaneLoopback lanes = open(TransportConfig.multistream())) {
            QuicStreamChannel[] channels = new QuicStreamChannel[3];
            QuicStreamWriteSink sink = sinkFor(rig, lanes, channels);
            EventLoop loop = channels[0].eventLoop();
            long base = reserved(rig, loop);

            onLoop(loop, () -> rig.driver.submit(frame(1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0));
            long charged = reserved(rig, loop);
            channels[0].close().sync();
            channels[1].close().sync();
            channels[2].close().sync();
            onLoop(loop, () -> {
                sink.drain();
                return null;
            });
            assertEquals(charged, reserved(rig, loop), "no slice is granted to an inactive stream");
            assertTrue(charged > base);
        }
    }
}
