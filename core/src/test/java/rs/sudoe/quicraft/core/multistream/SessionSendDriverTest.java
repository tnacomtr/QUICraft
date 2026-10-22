// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Test;

import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.time.StallClock;

class SessionSendDriverTest {
    @Test void optInTraceFollowsRealQueueAndSelectionWithoutChangingDrainOrder() throws Exception {
        String previous = System.getProperty("quicraft.v3.trace");
        System.setProperty("quicraft.v3.trace", "true");
        try {
            Rig rig = new Rig(2, 100);
            try {
                long started = System.nanoTime();
                Long outer = SendTrace.beginGameWrite(started);
                try {
                    rig.driver.submit(frame(5), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
                    rig.driver.submit(frame(5), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
                } finally {
                    SendTrace.endGameWrite(started, outer);
                }
                assertNotNull(rig.driver.trace());
                assertTrue(rig.driver.trace().summary(System.nanoTime()).contains("queuedPeakPerStream=[2, 0"));
                Drain drained = drainAndRelease(rig.driver);
                assertEquals(2, drained.writes.size());
                assertTrue(drained.writes.get(0).traceSelectedNanos > 0);
                String summary = rig.driver.trace().summary(System.nanoTime());
                assertTrue(summary.contains("gameToSelection={n=2"), summary);
                assertTrue(summary.contains("notFullySelectedPerStream=[0, 0, 0, 0, 0, 0, 0, 0]"), summary);
            } finally {
                rig.driver.close();
            }
        } finally {
            if (previous == null) System.clearProperty("quicraft.v3.trace");
            else System.setProperty("quicraft.v3.trace", previous);
        }
    }

    private static final long MB = 1L << 20;
    /** The REALTIME slice used by small-cap rigs; caps in those rigs are raised by this amount (13.12). */
    private static final long TEST_SLICE = 32;

    /** A rig with generous pools, a manual clock and the given profile limits. */
    private static final class Rig {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final PressureRanking ranking = new PressureRanking();
        final OutboundPool pool;
        final Object owner = new Object();
        final SessionSendDriver driver;
        /** The REALTIME slice this driver holds for the connection. */
        final long slice;

        Rig(int shards, int queuePackets) throws Exception {
            this(shards, queuePackets, 1L << 40);
        }

        /**
         * A rig whose record and ACK budget is {@code connectionCap} bytes. Small caps also hold the REALTIME slice,
         * so the pool cap is raised by {@link #TEST_SLICE} and the budget is unchanged.
         */
        Rig(int shards, int queuePackets, long connectionCap) throws Exception {
            this(shards, queuePackets, connectionCap, 1L << 40);
        }

        /** A rig with the given queue footprint byte limit (13.20); the connection cap is as in the other constructors. */
        Rig(int shards, int queuePackets, long connectionCap, long queueBytes) throws Exception {
            boolean large = connectionCap >= ReceiveSlots.BYTES_PER_STREAM + AckJoinTracker.MAX_BYTES;
            slice = large ? SessionSendDriver.DEFAULT_REALTIME_SLICE_BYTES : TEST_SLICE;
            NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
            pool = new OutboundPool(node, 1L << 40, 1L << 40, large ? connectionCap : connectionCap + TEST_SLICE,
                    ranking.view(now::get));
            ServerProfile profile = new ServerProfile(1, 1, 1024, .5, queuePackets, queueBytes, 4, 4, 4);
            if (large) {
                driver = new SessionSendDriver(SessionContext.of(shards), profile, pool, ranking, owner,
                        InetAddress.getLoopbackAddress(), null, now::get);
            } else {
                // A small test cap is split between one maximum record and the ACK headroom (the check in the constructor).
                driver = new SessionSendDriver(SessionContext.of(shards), profile, pool, ranking, owner,
                        InetAddress.getLoopbackAddress(), null, now::get, connectionCap - connectionCap / 4,
                        connectionCap / 4, TEST_SLICE);
            }
        }
    }

    /** A rig with explicit record and ACK headroom, so a small cap can hold one maximum record beside the ACK bound. */
    private static final class HeadroomRig {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final PressureRanking ranking = new PressureRanking();
        final OutboundPool pool;
        final Object owner = new Object();
        final SessionSendDriver driver;
        /** The REALTIME slice this driver holds; the pool cap is raised by it (13.12). */
        final long slice = TEST_SLICE;

        HeadroomRig(long connectionCap, long maxRecordBytes, long ackByteLimit) throws Exception {
            NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
            pool = new OutboundPool(node, 1L << 40, 1L << 40, connectionCap + TEST_SLICE, ranking.view(now::get));
            ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
            driver = new SessionSendDriver(SessionContext.of(2), profile, pool, ranking, owner,
                    InetAddress.getLoopbackAddress(), null, now::get, maxRecordBytes, ackByteLimit, TEST_SLICE);
        }
    }

    /** One written slice, grouped by stream in write order. */
    private static final class Drain {
        final List<SessionSendDriver.Write> writes = new ArrayList<SessionSendDriver.Write>();
        final ByteArrayOutputStream[] perStream = new ByteArrayOutputStream[8];

        Drain() {
            for (int i = 0; i < perStream.length; i++) {
                perStream[i] = new ByteArrayOutputStream();
            }
        }

        byte[] stream(int index) {
            return perStream[index].toByteArray();
        }
    }

    /** Polls until nothing is selectable, reporting every slice as released at once. */
    private static Drain drainAndRelease(SessionSendDriver driver) {
        Drain drain = new Drain();
        SessionSendDriver.Write write;
        while ((write = driver.poll(stream -> true)) != null) {
            drain.writes.add(write);
            byte[] bytes = write.bytes();
            drain.perStream[write.stream()].write(bytes, 0, bytes.length);
            driver.released(write.token());
        }
        return drain;
    }

    /** A GAME frame: VarInt(content) followed by content bytes (13.4). */
    private static OutboundPacket frame(int content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.write(out, content);
        byte[] body = new byte[content];
        Arrays.fill(body, (byte) 7);
        out.write(body, 0, body.length);
        return new OutboundPacket(out.toByteArray());
    }

    private static int chunkStream(int x, int z, long epoch, int shards) {
        return 2 + ShardHash.shardOf(x, z, epoch, shards);
    }

    /** A chunk X whose shard is not 0, so a join on CHUNK[0] carries its frontier explicitly. */
    private static int nonzeroShardX(int shards) {
        int x = 0;
        while (ShardHash.shardOf(x, 0, 0, shards) == 0) {
            x++;
        }
        return x;
    }

    @Test
    void endIsAnEmptyGlobalControlRecordAfterEveryEarlierRecord() throws Exception {
        Rig rig = new Rig(2, 100);
        SessionSendDriver driver = rig.driver;
        SentPacket chunk = driver.submit(frame(5), StreamSequencer.Fence.CHUNK, 3, 4);
        SentPacket realtime = driver.submit(frame(5), StreamSequencer.Fence.REALTIME, 0, 0);
        SentPacket end = driver.submitEnd();
        assertEquals(LogicalStream.CONTROL, end.logicalStream());
        Drain drain = drainAndRelease(driver);
        Record decoded = RecordCodec.decodeRecord(drain.stream(LogicalStream.CONTROL), LogicalStream.CONTROL);
        assertEquals(RecordCodec.KIND_END, decoded.kind());
        assertEquals(0, decoded.payload().length);
        long[] deps = new long[4];
        for (Record.Dependency dep : decoded.dependencies()) deps[dep.streamIndex()] = dep.sequence();
        assertEquals(chunk.sequence(), deps[chunk.logicalStream()]);
        assertEquals(realtime.sequence(), deps[LogicalStream.REALTIME]);
    }

    /** After END every submission path assigns nothing, and END is the last record on the wire (13.14). */
    @Test
    void nothingIsAcceptedAfterEnd() throws Exception {
        Rig rig = new Rig(2, 100);
        SessionSendDriver driver = rig.driver;
        SentPacket before = driver.submit(frame(4), StreamSequencer.Fence.CONTROL_FENCE, 0, 0);
        SentPacket end = driver.submitEnd();
        assertTrue(end.isAssigned());
        assertTrue(driver.isSealed());
        assertFalse(driver.submit(frame(4), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertFalse(driver.submit(frame(4), StreamSequencer.Fence.CHUNK, 1, 1).isAssigned());
        assertFalse(driver.submit(frame(4), StreamSequencer.Fence.REALTIME, 0, 0).isAssigned());
        assertFalse(driver.submitAfter(frame(4), StreamSequencer.Fence.GLOBAL, 0, 0, before).isAssigned());
        assertFalse(driver.submitEpochTransition(frame(4)).isAssigned());
        assertTrue(driver.submitBundle(frame(1), Arrays.asList(frame(2)),
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL}, frame(1)).isEmpty());
        assertFalse(driver.createAck(1, new byte[] {1}, 8));
        assertNull(driver.takeAck(1));
        assertFalse(driver.coverPending(1, 1));
        assertFalse(driver.beginBatch(1, frame(2)).isAssigned());
        assertFalse(driver.batchInstall(1, frame(9), 1, 1).isAssigned());
        assertFalse(driver.finishBatch(1, frame(2)).isAssigned());
        assertFalse(driver.submitEnd().isAssigned(), "a second END");
        assertFalse(driver.isClosed(), "refusing after END does not close");

        Drain drain = drainAndRelease(driver);
        byte[] control = drain.stream(LogicalStream.CONTROL);
        int first = firstRecordLength(control);
        byte[] rest = Arrays.copyOfRange(control, first, control.length);
        assertEquals(rest.length, firstRecordLength(rest), "exactly two records on CONTROL");
        assertEquals(RecordCodec.KIND_END, RecordCodec.decodeRecord(rest, LogicalStream.CONTROL).kind());
        for (int stream = 1; stream < 4; stream++) {
            assertEquals(0, drain.stream(stream).length, "nothing on stream " + stream);
        }
        assertEquals(rig.slice, rig.pool.reserved(rig.owner));
    }

    /**
     * END raced by submissions on other threads: each submission lands either before END, covered by its
     * dependencies, or not at all. Nothing is assigned after END on any stream.
     */
    @Test
    void endIsAtomicAgainstConcurrentSubmissions() throws Exception {
        for (int round = 0; round < 20; round++) {
            Rig rig = new Rig(2, 100_000);
            SessionSendDriver driver = rig.driver;
            List<SentPacket> handles = java.util.Collections.synchronizedList(new ArrayList<SentPacket>());
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<Thread> threads = new ArrayList<Thread>();
            for (int t = 0; t < 4; t++) {
                final int seed = t;
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < 200; i++) {
                        StreamSequencer.Fence fence = (i + seed) % 3 == 0 ? StreamSequencer.Fence.CHUNK
                                : (i + seed) % 3 == 1 ? StreamSequencer.Fence.REALTIME
                                : StreamSequencer.Fence.ORDINARY_CONTROL;
                        handles.add(driver.submit(frame(2), fence, i, seed));
                    }
                });
                thread.start();
                threads.add(thread);
            }
            start.countDown();
            Thread.yield();
            SentPacket end = driver.submitEnd();
            for (Thread thread : threads) {
                thread.join(10_000);
            }
            assertTrue(end.isAssigned());
            Drain drain = drainAndRelease(driver);
            byte[] control = drain.stream(LogicalStream.CONTROL);
            Record last = null;
            for (int at = 0; at < control.length;) {
                int length = firstRecordLength(Arrays.copyOfRange(control, at, control.length));
                last = RecordCodec.decodeRecord(Arrays.copyOfRange(control, at, at + length), LogicalStream.CONTROL);
                at += length;
            }
            assertNotNull(last);
            assertEquals(RecordCodec.KIND_END, last.kind(), "END is the last CONTROL record");
            long[] covered = new long[4];
            covered[LogicalStream.CONTROL] = end.sequence();
            for (Record.Dependency dep : last.dependencies()) covered[dep.streamIndex()] = dep.sequence();
            synchronized (handles) {
                for (SentPacket handle : handles) {
                    if (handle.isAssigned()) {
                        assertTrue(handle.sequence() <= covered[handle.logicalStream()],
                                "round " + round + ": " + handle.logicalStream() + "#" + handle.sequence()
                                        + " assigned after END");
                    }
                }
            }
        }
    }

    /**
     * Deferred release (13.12): a close decided inside a submission on a caller's thread stops the driver but keeps
     * the charge of a slice the owner already polled, so the slice is still charged when written; the owner's close
     * returns everything once, and the write's late completion releases nothing twice.
     */
    @Test
    void closeInsideASubmissionDefersReleaseToTheOwner() throws Exception {
        Rig rig = new Rig(2, 2);
        SessionSendDriver driver = rig.driver;
        driver.releaseOnOwnerClose();
        driver.submit(frame(64), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        SessionSendDriver.Write polled = driver.poll(stream -> true);
        assertNotNull(polled);
        long held = rig.pool.reserved(rig.owner);
        assertTrue(held > rig.slice, "the polled record is charged");
        driver.submit(frame(1), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        driver.submit(frame(1), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        driver.submit(frame(1), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(driver.isClosed(), "the queue limit closed the driver");
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertNull(driver.poll(stream -> true), "a stopped driver offers nothing");
        assertTrue(rig.pool.reserved(rig.owner) >= held, "the polled slice stays charged until the owner closes");
        driver.close();
        assertEquals(0, rig.pool.reserved(rig.owner));
        driver.released(polled.token());
        assertEquals(0, rig.pool.reserved(rig.owner), "the late completion releases nothing twice");
    }

    @Test
    void serverboundDriverCarriesTheInteractionFlagAndRefusesChunks() throws Exception {
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        PressureRanking ranking = new PressureRanking();
        AtomicLong now = new AtomicLong(1);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, ranking.view(now::get));
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(2),
                new ServerProfile(1, 1, 1024, .5, 100, 1L << 40, 4, 4, 4), pool, ranking, new Object(),
                InetAddress.getLoopbackAddress(), null, now::get, true);
        SentPacket interaction = driver.submit(frame(3), StreamSequencer.Fence.ORDINARY_CONTROL, true, 0, 0);
        assertTrue(interaction.isAssigned());
        assertThrows(IllegalArgumentException.class, () -> driver.submit(frame(3), StreamSequencer.Fence.CHUNK, 0, 0));
    }

    @Test
    void interactionFlagReachesTheSequencerAndIsRefusedClientbound() throws Exception {
        Rig rig = new Rig(2, 100);
        // The flag is serverbound-only (13.9): the sequencer's route validation refuses it here.
        assertThrows(IllegalArgumentException.class, () -> rig.driver.submit(frame(3),
                StreamSequencer.Fence.ORDINARY_CONTROL, true, 0, 0));
        // The unflagged overload still assigns normally.
        SentPacket ok = rig.driver.submit(frame(3), StreamSequencer.Fence.ORDINARY_CONTROL, false, 0, 0);
        assertNotNull(ok);
    }

    @Test
    void shardsAndScopesFollowOriginalSubmissionOrder() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        SentPacket first = driver.submit(frame(3), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        SentPacket chunkA = driver.submit(frame(3), StreamSequencer.Fence.CHUNK, 1, 0);
        SentPacket realtime = driver.submit(frame(3), StreamSequencer.Fence.REALTIME, 0, 0);
        SentPacket chunkB = driver.submit(frame(3), StreamSequencer.Fence.CHUNK, 5, -3);
        SentPacket control = driver.submit(frame(3), StreamSequencer.Fence.CONTROL_FENCE, 0, 0);

        assertEquals(0, first.logicalStream());
        assertEquals(1, first.sequence());
        assertEquals(chunkStream(1, 0, 0, 2), chunkA.logicalStream());
        assertEquals(1, chunkA.sequence());
        assertEquals(1, realtime.logicalStream());
        assertEquals(1, realtime.sequence());
        assertEquals(chunkStream(5, -3, 0, 2), chunkB.logicalStream());
        assertEquals(2, control.sequence(), "control fences follow the original order on CONTROL");

        // A later epoch rehashes chunk shards at the submission point, not at write time.
        SentPacket transition = driver.submitEpochTransition(frame(3));
        assertEquals(3, transition.sequence());
        SentPacket afterEpoch = driver.submit(frame(3), StreamSequencer.Fence.CHUNK, 1, 0);
        assertEquals(chunkStream(1, 0, 1, 2), afterEpoch.logicalStream());
        long expected = 1;
        for (SentPacket prior : new SentPacket[] {first, chunkA, realtime, chunkB, control, transition}) {
            if (prior.logicalStream() == afterEpoch.logicalStream()) {
                expected++;
            }
        }
        assertEquals(expected, afterEpoch.sequence(), "sequences are per stream, in submission order");
        assertFalse(driver.isClosed());
    }

    @Test
    void ackWaitDoesNotBlockCoveredUpdateAssignment() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(7, frame(2).frame(), 100));
        assertTrue(driver.coverPending(7, 42));
        driver.sealAckCoverage(7);
        assertNull(driver.takeAck(7), "an ack waits for its covered update");

        // Unrelated submissions are never blocked by the pending ack.
        SentPacket other = driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(other.isAssigned());
        SentPacket covered = driver.submit(frame(2), StreamSequencer.Fence.CHUNK, 0, 0);
        assertTrue(covered.isAssigned());
        driver.assignedPending(42, covered);
        SentPacket join = driver.takeAck(7);
        assertNotNull(join, "the ack resolves once every covered update is assigned");
        assertEquals(2, join.logicalStream(), "joins are always CHUNK[0]");
        assertEquals(2, join.sequence());

        // A sequenced ack keeps its entry until it is written and released, so write it out first.
        drainAndRelease(driver);
        // Backpressure: the 129th pending ack is refused, nothing stored.
        for (int id = 100; id < 100 + 128; id++) {
            assertTrue(driver.createAck(id, frame(1).frame(), 1), "entry " + id);
            assertTrue(driver.coverPending(id, 10_000 + id), "keep ack " + id + " unresolved");
            driver.sealAckCoverage(id);
        }
        assertFalse(driver.createAck(999, frame(1).frame(), 1), "entry limit is 128 pending acks");

        // Expiry: an uncovered ack past its 10 s deadline is dropped and never sequenced.
        int queued = driver.queuedRecords();
        rig.now.addAndGet(10_000_000_000L);
        driver.expireAcks();
        assertNull(driver.takeAck(100));
        assertEquals(queued - 128, driver.queuedRecords(),
                "expired pending acks leave the queue count and emit no join");

        // Transition settlement: a complete ack is sequenced in the old epoch before the transition.
        int beforeAck = driver.queuedRecords();
        assertTrue(driver.createAck(5000, frame(1).frame(), 10));
        driver.sealAckCoverage(5000);
        driver.submitEpochTransition(frame(2));
        assertEquals(beforeAck + 2, driver.queuedRecords(), "the complete ack joins before the transition");
        assertFalse(driver.isClosed());
    }

    @Test
    void nextBatchDependsOnPreviousJoinIncludingEmptyBatch() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        SentPacket start1 = driver.beginBatch(1, frame(2));
        SentPacket install = driver.batchInstall(1, frame(2), 0, 0);
        SentPacket join1 = driver.finishBatch(1, frame(2));
        assertEquals(2, join1.logicalStream());
        SentPacket start2 = driver.beginBatch(2, frame(2));
        SentPacket join2 = driver.finishBatch(2, frame(2));
        assertEquals(2, join2.logicalStream());
        assertTrue(join2.sequence() > join1.sequence());

        Drain drain = drainAndRelease(driver);
        Record startTwo = RecordCodec.decodeRecord(drain.writes.get(startIndex(drain, start2)).bytes(), 0);
        boolean dependsOnJoin = false;
        for (Record.Dependency dependency : startTwo.dependencies()) {
            dependsOnJoin |= dependency.streamIndex() == 2 && dependency.sequence() == join1.sequence();
        }
        assertTrue(dependsOnJoin,
                "the next chunk_batch_start depends on the previous join even when the batch was empty");
        assertEquals(1, start1.sequence());
        assertEquals(1, install.sequence());
    }

    /** Index of the write that carries {@code handle} (single-slice records only). */
    private static int startIndex(Drain drain, SentPacket handle) {
        for (int i = 0; i < drain.writes.size(); i++) {
            SessionSendDriver.Write write = drain.writes.get(i);
            if (write.stream() == handle.logicalStream() && write.last()) {
                Record record = decodeQuietly(write, handle.logicalStream());
                if (record != null && record.sequence() == handle.sequence()) {
                    return i;
                }
            }
        }
        throw new AssertionError("no write for " + handle.logicalStream() + ":" + handle.sequence());
    }

    private static Record decodeQuietly(SessionSendDriver.Write write, int stream) {
        try {
            return RecordCodec.decodeRecord(write.bytes(), stream);
        } catch (Exception e) {
            return null;
        }
    }

    /** The decoded record of a single-slice handle among the drained writes. */
    private static Record recordOf(Drain drain, SentPacket handle) {
        Record record = decodeQuietly(drain.writes.get(startIndex(drain, handle)), handle.logicalStream());
        assertNotNull(record, "record " + handle.logicalStream() + "#" + handle.sequence());
        return record;
    }

    @Test
    void schedulerPromotesPrerequisitesAndAdvancesEveryRunnableShard() throws Exception {
        Rig rig = new Rig(3, 1000);
        SessionSendDriver driver = rig.driver;
        List<SentPacket> submitted = new ArrayList<SentPacket>();
        for (int round = 0; round < 6; round++) {
            submitted.add(driver.submit(frame(40), StreamSequencer.Fence.CHUNK, round, 0));
            submitted.add(driver.submit(frame(40), StreamSequencer.Fence.REALTIME, 0, 0));
        }
        SentPacket fence = driver.submit(frame(40), StreamSequencer.Fence.GLOBAL, 0, 0);
        submitted.add(fence);
        for (int round = 0; round < 3; round++) {
            submitted.add(driver.submit(frame(40), StreamSequencer.Fence.CHUNK, round + 10, 0));
        }
        Drain drain = drainAndRelease(driver);
        assertEquals(submitted.size(), drain.writes.size(), "every submitted record is written once");
        assertEquals(0, driver.queuedRecords());
        int[] lastSeq = new int[8];
        for (SessionSendDriver.Write write : drain.writes) {
            Record record = RecordCodec.decodeRecord(write.bytes(), write.stream());
            assertTrue(record.sequence() > lastSeq[write.stream()], "stream order is kept");
            lastSeq[write.stream()] = (int) record.sequence();
        }
    }

    @Test
    void outboundChargeSurvivesWriteUntilTransportRelease() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        SentPacket packet = driver.submit(frame(150 * 1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(packet.isAssigned());
        long charged = rig.pool.reserved(rig.owner);
        assertTrue(charged > 150 * 1024, "the encoded record is charged at submission");

        List<SessionSendDriver.Write> slices = new ArrayList<SessionSendDriver.Write>();
        SessionSendDriver.Write slice;
        do {
            slice = driver.poll(stream -> true);
            assertNotNull(slice, "the record is selectable until its last slice");
            slices.add(slice);
        } while (!slice.last());
        assertTrue(slices.size() >= 3, "a 150 KiB record takes at least three 64 KiB quanta");
        assertEquals(charged, rig.pool.reserved(rig.owner), "write() does not release the charge");

        for (SessionSendDriver.Write earlier : slices.subList(0, slices.size() - 1)) {
            driver.released(earlier.token());
        }
        assertEquals(charged, rig.pool.reserved(rig.owner), "slices that are not the last do not release");
        SessionSendDriver.Write second = slices.get(slices.size() - 1);
        driver.released(second.token());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "the last slice's release frees the whole record");
        driver.released(second.token());
        driver.failed(second.token());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "a repeated completion releases nothing again");
    }

    @Test
    void outboundChargeIsReleasedOnceOnFailureAndClose() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        driver.submit(frame(100), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        driver.submit(frame(100), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        SessionSendDriver.Write write = driver.poll(stream -> true);
        assertNotNull(write);
        assertTrue(rig.pool.reserved(rig.owner) > 0);
        driver.failed(write.token());
        long afterFailure = rig.pool.reserved(rig.owner);
        assertTrue(afterFailure > 0, "the second record is still charged");
        driver.close();
        assertEquals(0, rig.pool.reserved(rig.owner), "close releases every outstanding charge");
        driver.released(write.token());
        driver.close();
        assertEquals(0, rig.pool.reserved(rig.owner));
        assertTrue(driver.isClosed());
    }

    @Test
    void recordIsNotWrittenBeforeItsChargeIsGranted() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        // A per-connection cap of 100 bytes: the second record's reservation waits for the first release.
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                1L << 40, 1L << 40, 100 + TEST_SLICE, ranking.view(now::get));
        Object owner = new Object();
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(1),
                new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4), pool, ranking, owner,
                InetAddress.getLoopbackAddress(), null, now::get, 75, 25, TEST_SLICE);
        SentPacket first = driver.submit(frame(40), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        SentPacket second = driver.submit(frame(80), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(first.isAssigned() && second.isAssigned());
        assertTrue(pool.reserved(owner) <= 100 + TEST_SLICE, "only the first reservation is granted");

        SessionSendDriver.Write written = driver.poll(stream -> true);
        assertNotNull(written);
        assertNull(driver.poll(stream -> true), "the second record is not written before its grant");
        driver.released(written.token());
        SessionSendDriver.Write next = driver.poll(stream -> true);
        assertNotNull(next, "the release grants the second record, which is then written");
        assertTrue(next.last());
        assertEquals(second.sequence(), RecordCodec.decodeRecord(next.bytes(), 0).sequence());
        driver.close();
        assertEquals(0, pool.reserved(owner));
    }

    @Test
    void overflowPacketsReasonNamesTheQueuePacketLimitAndKeepsTheFirstReason() throws Exception {
        Rig rig = new Rig(1, 2);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertNull(driver.lastCloseReason(), "no reason before a refusal");
        assertFalse(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        String reason = driver.lastCloseReason();
        assertTrue(reason.startsWith("check=queue-packet-limit stream=-1 queuedRecords=2 "), reason);
        assertTrue(reason.contains(" queuePacketLimit=2 "), reason);
        assertTrue(reason.endsWith(" recordBytes=" + frame(2).frame().length), reason);
        // A later refusal on the closed driver is passive and must not replace the first reason.
        assertFalse(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertEquals(reason, driver.lastCloseReason());
    }

    @Test
    void overflowBytesReasonNamesTheQueueByteLimit() throws Exception {
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        PressureRanking ranking = new PressureRanking();
        AtomicLong now = new AtomicLong(1);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, ranking.view(now::get));
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(2),
                new ServerProfile(1, 1, 1024, .5, 100, 10, 4, 4, 4), pool, ranking, new Object(),
                InetAddress.getLoopbackAddress(), null, now::get, true);
        int size = frame(40).frame().length;
        assertTrue(size > 10, "the record must exceed the 10-byte queue limit");
        assertFalse(driver.submit(frame(40), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertTrue(driver.isClosed());
        String reason = driver.lastCloseReason();
        assertTrue(reason.startsWith("check=queue-byte-limit stream=-1 queuedRecords=0 queuedFootprint=0 "), reason);
        assertTrue(reason.contains(" queueByteLimit=10 "), reason);
        assertTrue(reason.endsWith(" recordBytes=" + size), reason);
    }

    @Test
    void inadmissibleRecordReasonNamesThePoolCheck() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                1L << 40, 1L << 40, 100 + TEST_SLICE, ranking.view(now::get));
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(1),
                new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4), pool, ranking, new Object(),
                InetAddress.getLoopbackAddress(), null, now::get, 75, 25, TEST_SLICE);
        assertNull(driver.lastCloseReason());
        // A record larger than the connection cap can never be admitted, so the driver closes 0x12 (13.12).
        assertFalse(driver.submit(frame(300), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        String reason = driver.lastCloseReason();
        assertTrue(reason.startsWith("check=pool-inadmissible stream="), reason);
        assertTrue(reason.contains(" queuePacketLimit=1000 queueByteLimit=" + (1L << 40) + " "), reason);
        long recordBytes = Long.parseLong(reason.substring(reason.lastIndexOf(" recordBytes=") + " recordBytes=".length()));
        assertTrue(recordBytes > 100 + TEST_SLICE, "the refused record is larger than the cap: " + reason);
    }

    @Test
    void sealedRefusalReasonIsPassiveAndNeverClosesTheDriver() throws Exception {
        Rig rig = new Rig(1, 100);
        SessionSendDriver driver = rig.driver;
        driver.seal();
        assertFalse(driver.submit(frame(3), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertFalse(driver.isClosed(), "a sealed refusal is not a close");
        assertEquals(0, driver.closeCode());
        String reason = driver.lastCloseReason();
        assertTrue(reason.startsWith("check=sealed stream=-1 queuedRecords=0 queuedFootprint=0 "), reason);
        assertTrue(reason.contains(" queuePacketLimit=100 "), reason);
    }

    @Test
    void aThrowingLogHandlerDoesNotStopAResourceCloseOrItsRelease() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Handler broken = new Handler() {
            @Override public void publish(LogRecord record) {
                attempts.incrementAndGet();
                throw new IllegalStateException("broken log handler");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        assertOverflowCloseCompletesUnderLogging(broken, null, attempts);
    }

    @Test
    void aThrowingLogFilterDoesNotStopAResourceCloseOrItsRelease() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Filter broken = record -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("broken log filter");
        };
        assertOverflowCloseCompletesUnderLogging(null, broken, attempts);
    }

    /**
     * One queue-overflow 0x12 close with a failing handler or filter on the QUICraft logger. The close must complete
     * exactly as without logging: the driver is closed, the code is 0x12 and every charge is released. The failing hook
     * must have run, or the test would prove nothing.
     */
    private static void assertOverflowCloseCompletesUnderLogging(Handler handler, Filter filter, AtomicInteger attempts)
            throws Exception {
        Logger quicraft = Logger.getLogger("QUICraft");
        Level priorLevel = quicraft.getLevel();
        Filter priorFilter = quicraft.getFilter();
        quicraft.setLevel(Level.INFO);
        quicraft.setFilter(filter);
        if (handler != null) {
            quicraft.addHandler(handler);
        }
        try {
            Rig rig = new Rig(1, 2);
            SessionSendDriver driver = rig.driver;
            assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
            assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
            assertFalse(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
            assertTrue(driver.isClosed(), "the overflow closed the driver");
            assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
            assertEquals(0, rig.pool.reserved(rig.owner), "close releases every outstanding charge");
            assertTrue(driver.lastCloseReason().startsWith("check=queue-packet-limit "), driver.lastCloseReason());
            assertTrue(attempts.get() > 0, "the failing log hook ran during the close");
        } finally {
            if (handler != null) {
                quicraft.removeHandler(handler);
            }
            quicraft.setFilter(priorFilter);
            quicraft.setLevel(priorLevel);
        }
    }

    @Test
    void producerOverflowIsResourceNotPeerViolation() throws Exception {
        Rig rig = new Rig(1, 2);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        SentPacket overflow = driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertFalse(overflow.isAssigned(), "an overflowing submission is not assigned");
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode(), "overflow closes 0x12, never 0x11");
        assertEquals(0x12, SessionSendDriver.CLOSE_RESOURCE);
    }

    @Test
    void bundleOverItsMemberCapClosesOrderingLocally() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        List<OutboundPacket> members = new ArrayList<OutboundPacket>();
        StreamSequencer.Fence[] scopes = new StreamSequencer.Fence[StreamSequencer.MAX_BUNDLE_MEMBERS + 1];
        for (int i = 0; i < scopes.length; i++) {
            members.add(frame(1));
            scopes[i] = StreamSequencer.Fence.ORDINARY_CONTROL;
        }
        List<SentPacket> result = driver.submitBundle(frame(1), members, scopes, frame(1));
        assertTrue(result.isEmpty());
        assertEquals(SessionSendDriver.CLOSE_ORDERING, driver.closeCode());
    }

    @Test
    void bundleEncodesKind4OpenerThatClientReadPumpCompletes() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        List<OutboundPacket> members = new ArrayList<OutboundPacket>();
        members.add(frame(20));
        members.add(frame(100 * 1024));
        members.add(frame(30));
        StreamSequencer.Fence[] scopes = {StreamSequencer.Fence.ORDINARY_CONTROL,
            StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.ORDINARY_CONTROL};
        List<SentPacket> unit = driver.submitBundle(frame(5), members, scopes, frame(5));
        assertEquals(5, unit.size());
        assertFalse(driver.isClosed());

        Drain drain = drainAndRelease(driver);
        byte[] controlBytes = drain.stream(0);
        Record opener = RecordCodec.decodeRecord(Arrays.copyOf(controlBytes, firstRecordLength(controlBytes)), 0);
        assertTrue(opener.isBundleOpen(), "the opener is kind 4");
        assertEquals(5, opener.unitRecords());
        assertEquals(controlBytes.length, opener.unitWireBytes(), "unit wire bytes are the exact unit total");

        final byte[] stream0 = controlBytes;
        final int[] at = {0};
        ClientReadPump pump = new ClientReadPump(1, new ReceiveSlots(1), false, (stream, into, offset, max) -> {
            if (stream != 0) {
                return 0;
            }
            int n = Math.min(max, stream0.length - at[0]);
            System.arraycopy(stream0, at[0], into, offset, n);
            at[0] += n;
            return n;
        });
        List<ClientReadPump.Unit> units = new ArrayList<ClientReadPump.Unit>();
        for (int step = 0; step < 4096 && units.isEmpty(); step++) {
            units.addAll(pump.step());
        }
        assertEquals(1, units.size(), "the client read pump completes exactly one unit");
        assertEquals(5, units.get(0).recordCount());
        assertEquals(opener.unitWireBytes(), units.get(0).wireBytes());
        pump.commit(units.get(0));
    }

    /**
     * A record waiting for its grant holds back every later record, on every stream, until it is granted: the
     * grant order is the assignment order (class javadoc). Round 2 let later records on other streams overtake.
     */
    @Test
    void ownerCapWaitHoldsEveryLaterRecordInAssignmentOrder() throws Exception {
        Rig rig = new Rig(1, 1000, 100);
        SessionSendDriver driver = rig.driver;
        SentPacket realtime = driver.submit(frame(40), StreamSequencer.Fence.REALTIME, 0, 0);
        SentPacket waiting = driver.submit(frame(80), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket behind = driver.submit(frame(10), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket control = driver.submit(frame(10), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(realtime.isAssigned() && waiting.isAssigned() && behind.isAssigned() && control.isAssigned());

        SessionSendDriver.Write first = driver.poll(stream -> true);
        assertNotNull(first);
        assertEquals(LogicalStream.REALTIME, first.stream());
        assertNull(driver.poll(stream -> true), "the 86-byte chunk waits for its grant; nothing after it is granted");
        assertEquals(rig.slice + 46, rig.pool.reserved(rig.owner), "only the granted realtime record is charged");
        driver.released(first.token());

        Drain rest = drainAndRelease(driver);
        assertEquals(3, rest.writes.size(), "the waiting chunk, then the records behind it, are written");
        assertEquals(waiting.sequence(), RecordCodec.decodeRecord(rest.writes.get(0).bytes(), 2).sequence());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "only the slice is held once every record is released");
        assertFalse(driver.isClosed());
    }

    /**
     * Trace A (round 2 deadlock). Cap 100: REALTIME R=46 is granted, CHUNK U=86 waits for its grant, and a CONTROL
     * fence C=16 depends on U. Round 2 granted C at once, so after R released, C's 16 bytes left 84 free for U's 86
     * and nothing could be written. C must wait behind U's grant.
     */
    @Test
    void grantWaitOnChunkDoesNotLetDependentFenceHoldBytes() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                1L << 40, 1L << 40, 100 + TEST_SLICE, ranking.view(now::get));
        Object owner = new Object();
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(1),
                new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4), pool, ranking, owner,
                InetAddress.getLoopbackAddress(), null, now::get, 86, 14, TEST_SLICE);
        SentPacket realtime = driver.submit(frame(40), StreamSequencer.Fence.REALTIME, 0, 0);
        SentPacket chunk = driver.submit(frame(80), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket fence = driver.submitAfter(frame(10), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0, chunk);
        assertTrue(realtime.isAssigned() && chunk.isAssigned() && fence.isAssigned());

        SessionSendDriver.Write first = driver.poll(stream -> true);
        assertNotNull(first);
        assertEquals(LogicalStream.REALTIME, first.stream());
        assertNull(driver.poll(stream -> true), "the chunk waits for its grant, and the fence waits behind it");
        driver.released(first.token());

        Drain rest = drainAndRelease(driver);
        assertEquals(2, rest.writes.size(), "the chunk is granted once realtime is released, then the fence");
        assertEquals(chunk.sequence(), RecordCodec.decodeRecord(rest.writes.get(0).bytes(), 2).sequence());
        assertEquals(fence.sequence(), RecordCodec.decodeRecord(rest.writes.get(1).bytes(), 0).sequence());
        assertEquals(TEST_SLICE, pool.reserved(owner), "only the slice is held");
        assertFalse(driver.isClosed());
    }

    /**
     * Trace B (round 2 deadlock). Cap 102 holds one maximum record of 86 beside one 16-byte ACK. A second 16-byte
     * ACK would leave 70 bytes for the covered update's 86, which could never be granted while both ACKs wait for it.
     * The headroom bound refuses the second ACK, and a sequenced ACK keeps its headroom until it is released.
     */
    @Test
    void precommittedAckBytesNeverStarveTheCoveredUpdate() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                1L << 40, 1L << 40, 102 + TEST_SLICE, ranking.view(now::get));
        Object owner = new Object();
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(1),
                new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4), pool, ranking, owner,
                InetAddress.getLoopbackAddress(), null, now::get, 86, 16, TEST_SLICE);
        assertTrue(driver.createAck(1, frame(2).frame(), 16));
        assertEquals(16 + TEST_SLICE, pool.reserved(owner), "the ack is charged at creation");
        assertTrue(driver.coverPending(1, 42));
        driver.sealAckCoverage(1);
        assertFalse(driver.createAck(2, frame(2).frame(), 16),
                "a second precharged ack would starve the covered update, so the producer pauses");

        SentPacket covered = driver.submit(frame(80), StreamSequencer.Fence.CHUNK, 0, 0);
        assertTrue(covered.isAssigned());
        driver.assignedPending(42, covered);
        assertNotNull(driver.takeAck(1));
        assertFalse(driver.createAck(3, frame(2).frame(), 16),
                "a sequenced ack still holds its headroom until the transport releases it");

        Drain drain = drainAndRelease(driver);
        assertEquals(2, drain.writes.size(), "the covered update and the ack are both written");
        assertEquals(TEST_SLICE, pool.reserved(owner), "every record charge is released; the slice remains");
        assertTrue(driver.createAck(4, frame(2).frame(), 16), "released headroom is available again");
        driver.close();
        assertEquals(0, pool.reserved(owner));
    }

    /** The headroom check: a cap that cannot hold a maximum record beside the ACK bound is refused at construction. */
    @Test
    void connectionCapWithoutRecordAndAckHeadroomIsRefused() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                1L << 40, 1L << 40, 100 + TEST_SLICE, ranking.view(now::get));
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
        assertThrows(IllegalArgumentException.class, () -> new SessionSendDriver(SessionContext.of(1), profile,
                pool, ranking, new Object(), InetAddress.getLoopbackAddress(), null, now::get, 86, 16, TEST_SLICE));
        assertNotNull(new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, new Object(),
                InetAddress.getLoopbackAddress(), null, now::get, 84, 16, TEST_SLICE));
    }

    /**
     * Two connections share one source address and a 100-byte per-IP cap. B's request sheds A. B must
     * not consume A's shed notice: A closes with 0x12 when it polls, releases its charge, and B's record
     * is granted afterwards.
     */
    @Test
    void shedNoticeReachesOnlyTheVictimDriver() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        // The IP cap holds the two REALTIME slices beside the 100-byte budget.
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                100 + 2 * TEST_SLICE, 1L << 40, 1L << 40, ranking.view(now::get));
        InetAddress ip = InetAddress.getLoopbackAddress();
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
        Object ownerA = new Object();
        Object ownerB = new Object();
        // The 100-byte IP cap is the smallest cap, so the headroom is 84 record bytes plus 16 ACK bytes.
        SessionSendDriver a = new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, ownerA,
                ip, null, now::get, 84, 16, TEST_SLICE);
        SessionSendDriver b = new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, ownerB,
                ip, null, now::get, 84, 16, TEST_SLICE);
        assertTrue(a.submit(frame(70), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertTrue(b.submit(frame(30), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());

        assertNull(b.poll(stream -> true), "B waits; it must not take A's shed notice");
        assertFalse(b.isClosed());
        assertNull(a.poll(stream -> true), "A takes its own notice and closes");
        assertTrue(a.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, a.closeCode());
        assertEquals(0, pool.reserved(ownerA), "A's close releases its charge");
        assertNotNull(b.poll(stream -> true), "B's record is granted once A's bytes are released");
        assertFalse(b.isClosed());
    }

    /** A record whose release comes from its last slice first still waits for every earlier slice. */
    @Test
    void recordChargeWaitsForEverySliceInAnyCompletionOrder() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        driver.submit(frame(150 * 1024), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        long charged = rig.pool.reserved(rig.owner);
        List<SessionSendDriver.Write> slices = new ArrayList<SessionSendDriver.Write>();
        SessionSendDriver.Write slice;
        do {
            slice = driver.poll(stream -> true);
            assertNotNull(slice);
            slices.add(slice);
        } while (!slice.last());
        assertTrue(slices.size() >= 3);

        driver.released(slice.token());
        assertEquals(charged, rig.pool.reserved(rig.owner), "the last slice alone does not release the record");
        for (int i = slices.size() - 2; i >= 0; i--) {
            driver.released(slices.get(i).token());
            assertEquals(i == 0 ? rig.slice : charged, rig.pool.reserved(rig.owner),
                    "the charge is released only when the final outstanding slice completes");
        }
        driver.failed(slice.token());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "a repeated completion releases nothing again");
    }

    /** A pending ACK_JOIN counts against the queue limits from creation, so a saturated queue closes 0x12. */
    @Test
    void pendingAckCountsAgainstQueueLimitsFromCreation() throws Exception {
        Rig rig = new Rig(1, 2);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertFalse(driver.createAck(7, frame(2).frame(), 100), "the queue is full, so the ack is refused");
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
    }

    /** Sequencing a pending ACK transfers its queue count: it is counted once, not again. */
    @Test
    void pendingAckIsCountedOnceWhenItIsSequenced() throws Exception {
        Rig rig = new Rig(1, 3);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(7, frame(2).frame(), 100));
        assertTrue(driver.coverPending(7, 42));
        driver.sealAckCoverage(7);
        SentPacket covered = driver.submit(frame(2), StreamSequencer.Fence.CHUNK, 0, 0);
        assertTrue(covered.isAssigned());
        driver.assignedPending(42, covered);
        assertNotNull(driver.takeAck(7));
        assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned(),
                "ack and covered update make two queued packets, so one more fits");
        assertFalse(driver.isClosed());
        driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
    }

    /** A record bigger than the connection cap closes 0x12 before any sequence number is assigned. */
    @Test
    void recordLargerThanConnectionCapClosesWithoutAGap() throws Exception {
        Rig rig = new Rig(1, 1000, 100);
        SessionSendDriver driver = rig.driver;
        SentPacket oversize = driver.submit(frame(200), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertFalse(oversize.isAssigned());
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertEquals(0, rig.pool.reserved(rig.owner));
    }

    /** A bundle with a member larger than the connection cap closes 0x12 and assigns nothing. */
    @Test
    void bundleMemberLargerThanConnectionCapClosesWithoutAGap() throws Exception {
        Rig rig = new Rig(1, 1000, 100);
        SessionSendDriver driver = rig.driver;
        List<OutboundPacket> members = new ArrayList<OutboundPacket>();
        members.add(frame(1));
        members.add(frame(200));
        StreamSequencer.Fence[] scopes = {StreamSequencer.Fence.ORDINARY_CONTROL,
            StreamSequencer.Fence.ORDINARY_CONTROL};
        List<SentPacket> result = driver.submitBundle(frame(1), members, scopes, frame(1));
        assertTrue(result.isEmpty());
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertEquals(0, rig.pool.reserved(rig.owner));
    }

    /**
     * Open-bundle stall (round 3 review). Cap 100 with 86 record bytes and 14 ACK bytes. A CHUNK record is granted
     * first, then a bundle is assigned behind it. The bundle is one charge, so nothing of it is offered before the
     * whole run fits. Before the fix the opener was offered alone, the scheduler then served only that bundle, and
     * the granted CHUNK could never be written.
     */
    @Test
    void openBundleWaitsForOneChargeAndNeverStallsAnEarlierChunk() throws Exception {
        HeadroomRig rig = new HeadroomRig(100, 86, 14);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.submit(frame(40), StreamSequencer.Fence.CHUNK, 0, 0).isAssigned());
        List<OutboundPacket> members = new ArrayList<OutboundPacket>();
        members.add(frame(52));
        List<SentPacket> unit = driver.submitBundle(frame(1), members,
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL}, frame(1));
        assertEquals(3, unit.size());
        SessionSendDriver.Write early = driver.poll(stream -> stream < 2);
        if (early != null) {
            driver.released(early.token());
        }
        drainAndRelease(driver);
        assertFalse(driver.isClosed());
        assertEquals(0, driver.queuedRecords());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner));
    }

    /** The headroom check uses the smallest cap that applies to the connection: owner, IP, account or node. */
    @Test
    void headroomIsCheckedAgainstTheSmallestApplicableCap() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
        OutboundPool ipLimited = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                90, 1L << 40, 1L << 40, ranking.view(now::get));
        assertThrows(IllegalArgumentException.class, () -> new SessionSendDriver(SessionContext.of(1), profile,
                ipLimited, ranking, new Object(), InetAddress.getLoopbackAddress(), null, now::get, 86, 14, TEST_SLICE));
        OutboundPool accountLimited = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                1L << 40, 90, 1L << 40, ranking.view(now::get));
        assertThrows(IllegalArgumentException.class, () -> new SessionSendDriver(SessionContext.of(1), profile,
                accountLimited, ranking, new Object(), InetAddress.getLoopbackAddress(), "alice", now::get, 86, 14,
                TEST_SLICE));
        assertNotNull(new SessionSendDriver(SessionContext.of(1), profile, accountLimited, ranking, new Object(),
                InetAddress.getLoopbackAddress(), null, now::get, 86, 14, TEST_SLICE));
    }

    /**
     * A scoped ack resolves once its covering scope is complete and sealed, and the scope is retired
     * after the ack is sequenced (U1, 13.19). Verifies the driver delegates all scope state to the tracker.
     */
    @Test
    void scopedAckResolvesAndRetiresScopeAfterSeal() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(1, frame(2).frame(), 8));
        MultistreamSession.CausalScope scope = driver.openScope();
        assertNotNull(scope, "scope handle allocated");
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        assertNotNull(token, "token handle allocated");
        SentPacket covered = driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        driver.assignScopeToken(token, covered.logicalStream(), covered.sequence());
        driver.completeScope(scope);
        driver.coverScope(1, scope);
        driver.sealAckCoverage(1);
        SentPacket join = driver.takeAck(1);
        assertNotNull(join, "sealed + complete scope resolves the ack");
        assertTrue(join.isAssigned());
        assertEquals(0, driver.trackerScopeCount(), "scope retired after ack resolution");
        driver.close();
    }

    /**
     * A past-deadline scoped ack with an incomplete scope is dropped consistently by both the tracker
     * and the driver (Defect 4): takeAck must not orphan the ack, its scope and its tokens in the
     * tracker after the driver drops its charge.
     */
    @Test
    void expiredScopedAckDropsDriverChargeAndTrackerState() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(1, frame(2).frame(), 8));
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        assertNotNull(token, "token handle allocated");
        // Do NOT resolve the token or complete the scope: the ack is not scope-ready.
        driver.coverScope(1, scope);
        driver.sealAckCoverage(1);
        int queuedBefore = driver.queuedRecords();
        assertTrue(queuedBefore > 0, "the ack is queued from creation");
        assertEquals(1, driver.trackerRetainedTokens(), "one live token before expiry");
        // Advance the clock past the deadline so the ack is expired.
        rig.now.addAndGet(AckJoinTracker.DEADLINE_NANOS + 1);
        assertNull(driver.takeAck(1), "a past-deadline scope-unready ack never resolves");
        // The tracker dropped the ack and retired scope state (Defect 4).
        assertEquals(0, driver.trackerScopeCount(), "tracker retired the scope");
        assertEquals(0, driver.trackerRetainedTokens(), "tracker dropped live tokens");
        // The driver dropped its charge and freed the queue slot.
        assertEquals(queuedBefore - 1, driver.queuedRecords(), "driver freed the ack's slot");
        driver.close();
    }

    /** A scope-capacity failure is a typed tracker failure that the driver maps to a local 0x12 close. */
    @Test
    void scopeCapacityClosesWithResourceCode() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        for (int i = 0; i < AckJoinTracker.MAX_SCOPES; i++) {
            assertNotNull(driver.openScope());
        }
        assertNull(driver.openScope(), "the cap refuses the next scope");
        assertTrue(driver.isClosed(), "a capacity failure closes locally instead of throwing");
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertTrue(driver.closeCause() instanceof AckJoinTracker.CapacityException,
                "the original typed cause is retained, not masked");
        driver.close();
    }

    /** The per-scope token cap maps the same way: local 0x12, typed cause retained, no throw. */
    @Test
    void tokenCapacityClosesWithResourceCode() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.CausalScope scope = driver.openScope();
        for (int i = 0; i < AckJoinTracker.MAX_TOKENS_PER_SCOPE; i++) {
            assertNotNull(driver.registerUpdate(scope));
        }
        assertNull(driver.registerUpdate(scope), "the cap refuses the next token");
        assertTrue(driver.isClosed(), "a capacity failure closes locally instead of throwing");
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertTrue(driver.closeCause() instanceof AckJoinTracker.CapacityException,
                "the original typed cause is retained, not masked");
        driver.close();
    }

    /** The join's decoded record waits for the covered update's assigned stream and sequence. */
    @Test
    void coveredSendResolvesTokenAndAckCarriesItsFrontier() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(1, frame(2).frame(), 64));
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        assertEquals(1, driver.trackerRetainedTokens(), "one live token before assignment");

        SentPacket covered = driver.submitCovered(token, frame(3), StreamSequencer.Fence.CHUNK, false,
                nonzeroShardX(2), 0);
        assertTrue(covered.isAssigned());
        assertEquals(0, driver.trackerRetainedTokens(),
                "the token is consumed in the same monitor as the sequence assignment");
        assertFalse(driver.validateScopeToken(token), "a resolved token is not valid any more");

        driver.completeScope(scope);
        driver.coverScope(1, scope);
        driver.sealAckCoverage(1);
        SentPacket join = driver.takeAck(1);
        assertNotNull(join, "the complete, sealed scope releases the ack");

        Drain drain = drainAndRelease(driver);
        boolean seesCovered = false;
        for (Record.Dependency dep : recordOf(drain, join).dependencies()) {
            seesCovered |= dep.streamIndex() == covered.logicalStream() && dep.sequence() == covered.sequence();
        }
        assertTrue(seesCovered, "the join waits for the covered update's assigned frontier");
        driver.close();
    }

    /**
     * Every invalid token is rejected by the non-consuming pre-validation, before the sequencer
     * moves: foreign (ForeignHandleException), retired, suppressed and reused tokens assign no
     * sequence, leave the queue unchanged and never consume a live token.
     */
    @Test
    void coveredTokenIsValidatedBeforeAnyAssignment() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        Rig other = new Rig(2, 1000);
        MultistreamSession.UpdateToken foreign = other.driver.registerUpdate(other.driver.openScope());

        assertThrows(AckJoinTracker.ForeignHandleException.class,
                () -> driver.submitCovered(foreign, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, false, 0, 0));
        assertEquals(0, driver.queuedRecords(), "a foreign token queues nothing");
        assertEquals(1, driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).sequence(),
                "a foreign token assigned no sequence");

        // A token whose ack expires unresolved is retired with its scope.
        assertTrue(driver.createAck(7, frame(2).frame(), 8));
        MultistreamSession.CausalScope retiredScope = driver.openScope();
        MultistreamSession.UpdateToken retired = driver.registerUpdate(retiredScope);
        driver.coverScope(7, retiredScope);
        driver.sealAckCoverage(7);
        rig.now.addAndGet(AckJoinTracker.DEADLINE_NANOS + 1);
        assertNull(driver.takeAck(7));
        assertFalse(driver.validateScopeToken(retired), "the retired token is not valid");
        assertThrows(IllegalArgumentException.class,
                () -> driver.submitCovered(retired, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, false, 0, 0));
        assertEquals(2, driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).sequence(),
                "a retired token assigned no sequence");

        // Suppression makes the token invalid, and validation itself never consumes it.
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken suppressed = driver.registerUpdate(scope);
        driver.suppressUpdate(suppressed);
        assertFalse(driver.validateScopeToken(suppressed), "a suppressed token is not valid");
        assertThrows(IllegalArgumentException.class,
                () -> driver.submitCovered(suppressed, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, false, 0, 0));
        assertEquals(3, driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).sequence(),
                "a suppressed token assigned no sequence");

        MultistreamSession.UpdateToken live = driver.registerUpdate(scope);
        assertTrue(driver.validateScopeToken(live));
        assertTrue(driver.validateScopeToken(live), "validation is non-consuming");
        SentPacket covered = driver.submitCovered(live, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, false, 0, 0);
        assertTrue(covered.isAssigned(), "the still-live token assigns after repeated validation");
        assertThrows(IllegalArgumentException.class,
                () -> driver.submitCovered(live, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, false, 0, 0));
        assertEquals(covered.sequence() + 1,
                driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).sequence(),
                "reusing a resolved token assigned no sequence");
        driver.close();
        other.driver.close();
    }

    /**
     * The documented post-validation race: the token becomes unresolvable after validation but before
     * assignment. The driver closes locally 0x12 with the cause, writes nothing, and never turns the
     * failure into a suppression or a peer classification.
     */
    @Test
    void postValidationTokenRaceClosesResourceWithCause() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        assertTrue(driver.validateScopeToken(token));
        // Consume the token between validation and the assignment attempt.
        driver.assignScopeToken(token, 0, 1);

        SentPacket handle = driver.submitCoveredAssigned(token, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL,
                false, 0, 0);
        assertFalse(handle.isAssigned(), "the raced record is never handed out");
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertTrue(driver.closeCause() instanceof IllegalArgumentException,
                "the resolution failure is retained as the local cause");
        assertEquals(0, rig.pool.reserved(rig.owner), "the close releases every charge");
    }

    /** A refused route throws before assignment, leaves the token live and takes no sequence. */
    @Test
    void refusedCoveredRouteLeavesTokenLiveAndNoSequenceGap() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        assertThrows(IllegalArgumentException.class,
                () -> driver.submitCovered(token, frame(2), StreamSequencer.Fence.CHUNK, true, 0, 0));
        assertTrue(driver.validateScopeToken(token), "a refused route does not consume the token");
        assertEquals(0, driver.queuedRecords());

        SentPacket next = driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertEquals(1, next.sequence(), "the refused route assigned no sequence");
        SentPacket covered = driver.submitCovered(token, frame(2), StreamSequencer.Fence.CHUNK, false, 0, 0);
        assertTrue(covered.isAssigned());
        assertEquals(1, covered.sequence(), "the chunk sequence is contiguous");
        driver.close();
    }

    /**
     * ACK first, then a view gate, then the covered update (13.19): the pending ack stays outside
     * the FIFO under a 102-byte cap, so both non-ACK records assign, the update keeps its order
     * behind the gate, and the resolved join follows it with the update's frontier.
     */
    @Test
    void pendingAckDoesNotReorderFenceAndCoveredUpdateUnderPressure() throws Exception {
        HeadroomRig rig = new HeadroomRig(102, 86, 16);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(1, frame(2).frame(), 16));
        SentPacket fence = driver.submit(frame(1), StreamSequencer.Fence.VIEW, 0, 0);
        assertTrue(fence.isAssigned());
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        SentPacket update = driver.submitCovered(token, frame(78), StreamSequencer.Fence.CHUNK, false,
                nonzeroShardX(2), 0);
        assertTrue(update.isAssigned(), "the covered update assigns while its ACK is pending");
        assertFalse(update.logicalStream() == LogicalStream.CONTROL, "the update uses a chunk stream");
        driver.completeScope(scope);
        driver.coverScope(1, scope);
        driver.sealAckCoverage(1);
        SentPacket join = driver.takeAck(1);
        assertNotNull(join, "the ack resolves after its covered update was assigned");
        assertEquals(2, join.logicalStream(), "joins are always CHUNK[0]");
        assertTrue(update.logicalStream() != join.logicalStream(),
                "the update shard differs from CHUNK[0], so its frontier is explicit");

        Drain drain = drainAndRelease(driver);
        boolean updateWaitsForFence = false;
        for (Record.Dependency dep : recordOf(drain, update).dependencies()) {
            updateWaitsForFence |= dep.streamIndex() == LogicalStream.CONTROL && dep.sequence() == fence.sequence();
        }
        assertTrue(updateWaitsForFence, "the covered update keeps its order behind the view gate");
        boolean joinWaitsForUpdate = false;
        for (Record.Dependency dep : recordOf(drain, join).dependencies()) {
            joinWaitsForUpdate |= dep.streamIndex() == update.logicalStream()
                    && dep.sequence() == update.sequence();
        }
        assertTrue(joinWaitsForUpdate, "the join carries the covered update frontier");
        assertEquals(TEST_SLICE, rig.pool.reserved(rig.owner), "every record charge is released");
        driver.close();
    }

    /**
     * A pre-assignment queue overflow on a covered send is terminal, like every other submit path:
     * the token was validated but this submission owns no sequence, and the 0x12 close drops the
     * live scope and token as terminal connection cleanup. The retirement is not a claim that the
     * failed covered send assigned nothing; it only means the connection and its scope state end
     * together. No sequence moved for the covered record.
     */
    @Test
    void coveredOverflowBeforeAssignmentIsTerminalCleanupWithCause() throws Exception {
        Rig rig = new Rig(2, 1);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        assertTrue(driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned(),
                "the ordinary record fills the queue");
        long assignedBefore = driver.assignedSequence(LogicalStream.CONTROL);

        SentPacket overflow = driver.submitCovered(token, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL,
                false, 0, 0);

        assertFalse(overflow.isAssigned(), "the overflowing covered submission exposes no sequence");
        assertTrue(driver.isClosed(), "a producer overflow is terminal");
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode(), "overflow closes 0x12");
        assertTrue(driver.closeCause() instanceof IllegalStateException, "the overflow cause is retained");
        assertEquals("covered submission overflows the queue", driver.closeCause().getMessage());
        assertEquals(assignedBefore, driver.assignedSequence(LogicalStream.CONTROL),
                "the overflow path assigned no sequence to the covered record");
        assertEquals(0, driver.trackerScopeCount(), "the terminal close retires scopes as connection cleanup");
        assertEquals(0, driver.trackerRetainedTokens(), "the terminal close retires tokens as connection cleanup");
        assertNull(driver.poll(stream -> true), "nothing is pollable after the terminal close");
    }

    /**
     * A failure after the covered sequence was assigned but before publication (here encoding) closes
     * locally 0x12 with the original cause and exposes no half-assigned record.
     */
    @Test
    void coveredEncodeFailureAfterAssignmentClosesWithOriginalCause() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        // Inner length 5 with one body byte: the sequencer assigns, then RecordCodec rejects it.
        OutboundPacket malformed = new OutboundPacket(new byte[] {5, 7});

        SentPacket handle = driver.submitCovered(token, malformed, StreamSequencer.Fence.ORDINARY_CONTROL,
                false, 0, 0);

        assertFalse(handle.isAssigned(), "a post-assignment failure exposes no sequence");
        assertTrue(driver.isClosed(), "the driver closes locally");
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertTrue(driver.closeCause() instanceof IllegalArgumentException,
                "the encoding failure is the original cause");
        assertTrue(driver.closeCause().getCause() instanceof MultistreamProtocolException,
                "the codec failure underneath is retained");
        assertEquals(1, driver.assignedSequence(LogicalStream.CONTROL),
                "the sequence existed before encoding failed");
        assertNull(driver.poll(stream -> true), "the half-assigned record is not pollable");
        assertEquals(0, rig.pool.reserved(rig.owner), "the close releases every charge");
    }

    /**
     * A pump failure after the record was already queued closes locally 0x12 with the original cause
     * and clears the queue, so the half-assigned record is never pollable.
     */
    @Test
    void coveredPumpFailureAfterAssignmentClosesAndClearsTheQueue() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        AtomicBoolean failNextClockRead = new AtomicBoolean();
        LongSupplier clock = () -> {
            if (failNextClockRead.compareAndSet(true, false)) {
                throw new IllegalStateException("injected pump failure");
            }
            return now.get();
        };
        PressureRanking ranking = new PressureRanking();
        Object owner = new Object();
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                1L << 40, 1L << 40, 1L << 40, ranking.view(now::get));
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(2),
                new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4), pool, ranking, owner,
                InetAddress.getLoopbackAddress(), null, clock);
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        failNextClockRead.set(true);

        SentPacket handle = driver.submitCovered(token, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL,
                false, 0, 0);

        assertFalse(handle.isAssigned(), "a pump failure exposes no sequence");
        assertTrue(driver.isClosed(), "the driver closes locally");
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertTrue(driver.closeCause() instanceof IllegalStateException);
        assertEquals("injected pump failure", driver.closeCause().getMessage(),
                "the pump failure is retained as the original cause");
        assertEquals(1, driver.assignedSequence(LogicalStream.CONTROL),
                "the sequence existed before the pump failed");
        assertNull(driver.poll(stream -> true), "the queued half-assigned record is cleared");
        assertEquals(0, pool.reserved(owner), "the close releases every charge");
    }

    /**
     * Covering an ack after its membership was sealed is caller misuse: the driver translates the
     * typed tracker failure to IllegalArgumentException with the original cause, stays open, and
     * still accepts a subsequent valid submit.
     */
    @Test
    void postSealCoverScopeIsCallerMisuseAndKeepsTheDriverOpen() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(1, frame(2).frame(), 8));
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.CausalScope late = driver.openScope();
        driver.coverScope(1, scope);
        driver.sealAckCoverage(1);

        IllegalArgumentException misuse = assertThrows(IllegalArgumentException.class,
                () -> driver.coverScope(1, late));

        assertTrue(misuse.getCause() instanceof AckJoinTracker.SealedCoverageException,
                "the typed tracker failure is retained as the cause");
        assertFalse(driver.isClosed(), "caller misuse does not close the session");
        assertEquals(0, driver.closeCode(), "no local close code is set");
        assertNull(driver.closeCause(), "no local failure cause is recorded");
        SentPacket after = driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(after.isAssigned(), "the driver still accepts a valid submit");
        assertEquals(1, after.sequence(), "the misuse assigned no sequence");
        driver.close();
    }

    /** Polls every selectable slice without releasing any, collecting them in write order. */
    private static void pollAll(SessionSendDriver driver, List<SessionSendDriver.Write> into) {
        SessionSendDriver.Write write;
        while ((write = driver.poll(stream -> true)) != null) {
            into.add(write);
        }
    }

    /** True when a slice of {@code handle}'s record was written among {@code writes}. */
    private static boolean wrote(List<SessionSendDriver.Write> writes, SentPacket handle) throws Exception {
        for (SessionSendDriver.Write write : writes) {
            if (write.stream() == handle.logicalStream()
                    && RecordCodec.decodeRecord(write.bytes(), write.stream()).sequence() == handle.sequence()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A REALTIME record of at most the slice is written while an earlier CHUNK waits at the owner cap (13.12). The
     * REALTIME record never touches the ledger, so the owner cap does not hold it.
     */
    @Test
    void realtimeIsWrittenWhileOwnChunkWaitsAtOwnerCap() throws Exception {
        Rig rig = new Rig(1, 1000, 100);
        SessionSendDriver driver = rig.driver;
        SentPacket first = driver.submit(frame(80), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket waiting = driver.submit(frame(80), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket realtime = driver.submit(frame(4), StreamSequencer.Fence.REALTIME, 0, 0);
        assertTrue(first.isAssigned() && waiting.isAssigned() && realtime.isAssigned());

        List<SessionSendDriver.Write> held = new ArrayList<SessionSendDriver.Write>();
        pollAll(driver, held);
        assertTrue(wrote(held, first), "the first chunk is granted");
        assertTrue(wrote(held, realtime), "the realtime record is written while the chunk waits");
        assertFalse(wrote(held, waiting), "the second chunk waits for the owner cap");
        for (SessionSendDriver.Write write : held) {
            driver.released(write.token());
        }
        Drain rest = drainAndRelease(driver);
        assertTrue(wrote(rest.writes, waiting), "the waiting chunk is written once bytes are released");
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "only the slice is held");
    }

    /**
     * Two drivers share one IP whose cap holds both slices beside 100 record bytes. A's second chunk waits at the IP
     * cap, and B's REALTIME record is still written: it never waits for the IP ledger waiter (13.12).
     */
    @Test
    void realtimeIsWrittenWhileAnotherDriverOnTheSameIpWaitsAtTheIpCap() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                100 + 2 * TEST_SLICE, 1L << 40, 1L << 40, ranking.view(now::get));
        InetAddress ip = InetAddress.getLoopbackAddress();
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
        SessionSendDriver a = new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, new Object(),
                ip, null, now::get, 75, 25, TEST_SLICE);
        SessionSendDriver b = new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, new Object(),
                ip, null, now::get, 75, 25, TEST_SLICE);
        SentPacket chunk = a.submit(frame(80), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket waiting = a.submit(frame(80), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket realtime = b.submit(frame(4), StreamSequencer.Fence.REALTIME, 0, 0);
        assertTrue(chunk.isAssigned() && waiting.isAssigned() && realtime.isAssigned());

        List<SessionSendDriver.Write> heldA = new ArrayList<SessionSendDriver.Write>();
        List<SessionSendDriver.Write> heldB = new ArrayList<SessionSendDriver.Write>();
        pollAll(a, heldA);
        pollAll(b, heldB);
        assertTrue(wrote(heldA, chunk), "A's first chunk is granted");
        assertTrue(wrote(heldB, realtime), "B's realtime record is written while A's chunk waits at the IP cap");
        assertTrue(pool.drainShedOwners().isEmpty(), "B holds only its slice and is never shed for A's chunk");
        assertFalse(wrote(heldA, waiting), "A's second chunk waits at the IP cap");
        for (SessionSendDriver.Write write : heldA) {
            a.released(write.token());
        }
        for (SessionSendDriver.Write write : heldB) {
            b.released(write.token());
        }
        assertTrue(wrote(drainAndRelease(a).writes, waiting), "A's waiting chunk is written once the IP has room");
    }

    /**
     * A REALTIME record above the slice takes the main queue at its position, then a smaller REALTIME record that fits
     * the slice still waits for it, because REALTIME is written in stream order (13.12).
     */
    @Test
    void oversizeRealtimeTakesTheMainQueue() throws Exception {
        Rig rig = new Rig(1, 1000, 100);
        SessionSendDriver driver = rig.driver;
        SentPacket chunk = driver.submit(frame(70), StreamSequencer.Fence.CHUNK, 0, 0);
        // 32 + 77 is held by the chunk, so the 46-byte oversize record waits in the main queue.
        SentPacket oversize = driver.submit(frame(40), StreamSequencer.Fence.REALTIME, 0, 0);
        SentPacket small = driver.submit(frame(4), StreamSequencer.Fence.REALTIME, 0, 0);
        assertTrue(chunk.isAssigned() && oversize.isAssigned() && small.isAssigned());

        List<SessionSendDriver.Write> held = new ArrayList<SessionSendDriver.Write>();
        pollAll(driver, held);
        assertTrue(wrote(held, chunk), "the chunk is granted");
        assertFalse(wrote(held, oversize), "the oversize realtime record waits for the main queue");
        assertFalse(wrote(held, small), "the small realtime record waits for the earlier realtime record");
        for (SessionSendDriver.Write write : held) {
            driver.released(write.token());
        }
        Drain rest = drainAndRelease(driver);
        List<Long> realtimeOrder = new ArrayList<Long>();
        for (SessionSendDriver.Write write : rest.writes) {
            if (write.stream() == LogicalStream.REALTIME) {
                realtimeOrder.add(RecordCodec.decodeRecord(write.bytes(), write.stream()).sequence());
            }
        }
        assertEquals(Arrays.asList(oversize.sequence(), small.sequence()), realtimeOrder,
                "realtime records are written in assignment order");
        assertEquals(rig.slice, rig.pool.reserved(rig.owner));
    }

    /**
     * A CONTROL record depends on a REALTIME record, and both queues are under pressure: a CHUNK waits at the owner
     * cap, and an oversize REALTIME record waits behind it. Every record is written (13.12, 13.9).
     */
    @Test
    void interactionDependingOnRealtimeCompletesUnderPressure() throws Exception {
        Rig rig = new Rig(1, 1000, 100);
        SessionSendDriver driver = rig.driver;
        SentPacket chunkA = driver.submit(frame(70), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket realtime = driver.submit(frame(4), StreamSequencer.Fence.REALTIME, 0, 0);
        SentPacket chunkB = driver.submit(frame(70), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket control = driver.submitAfter(frame(10), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0, realtime);
        SentPacket oversize = driver.submit(frame(40), StreamSequencer.Fence.REALTIME, 0, 0);
        assertTrue(chunkA.isAssigned() && realtime.isAssigned() && chunkB.isAssigned()
                && control.isAssigned() && oversize.isAssigned());

        Drain rest = drainAndRelease(driver);
        assertEquals(5, rest.writes.size(), "every record is written");
        assertTrue(wrote(rest.writes, chunkA) && wrote(rest.writes, realtime) && wrote(rest.writes, chunkB)
                && wrote(rest.writes, control) && wrote(rest.writes, oversize));
        assertFalse(driver.isClosed());
        assertEquals(0, driver.queuedRecords());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner));
    }

    /** The slice is charged once and released exactly once: local REALTIME records never charge the pool (13.12). */
    @Test
    void realtimeSliceIsReleasedExactlyOnce() throws Exception {
        Rig rig = new Rig(1, 1000, 100);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.submit(frame(4), StreamSequencer.Fence.REALTIME, 0, 0).isAssigned());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "a local realtime record charges only the slice");
        driver.close();
        assertEquals(0, rig.pool.reserved(rig.owner), "close releases the slice");
        driver.close();
        assertEquals(0, rig.pool.reserved(rig.owner), "a second close releases nothing again");
        assertTrue(driver.isClosed());
    }

    /**
     * A bundle run whose total wire bytes exceed maxRecordBytes can never be granted, so it is refused before anything
     * is queued: no sequence is taken and the queue is unchanged (review round 5).
     */
    @Test
    void bundleRunLargerThanMaxRecordBytesIsRejectedBeforeQueueing() throws Exception {
        HeadroomRig rig = new HeadroomRig(1000, 200, 50);
        SessionSendDriver driver = rig.driver;
        List<OutboundPacket> members = new ArrayList<OutboundPacket>();
        members.add(frame(100));
        members.add(frame(100));
        StreamSequencer.Fence[] scopes = {StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.ORDINARY_CONTROL};
        assertThrows(IllegalArgumentException.class, () -> driver.submitBundle(frame(1), members, scopes, frame(1)));
        assertFalse(driver.isClosed());
        assertEquals(0, driver.queuedRecords());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "nothing beyond the slice is charged");
        SentPacket next = driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertEquals(1, next.sequence(), "the refused run took no sequence numbers");
    }

    /** Length of the first complete record in {@code bytes}: VarInt(record_length) plus its body. */
    private static int firstRecordLength(byte[] bytes) throws Exception {
        VarInt.Reader reader = new VarInt.Reader(SessionSendDriver.CLOSE_ORDERING, "13.4");
        int at = 0;
        while (!reader.done()) {
            reader.feed(bytes[at++] & 255);
        }
        return (int) (at + reader.value());
    }

    /** A cumulative driver reads its ranking source once per connect and cleanup, never a timestamp. */
    @Test
    void cumulativeDriverConnectsClosesAndReportsFromTheSharedSource() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final AtomicLong reads = new AtomicLong();
        java.util.function.LongSupplier clock = () -> {
            reads.incrementAndGet();
            return now.get();
        };
        PressureRanking ranking = new PressureRanking(new ExecutorProgressWatch(clock));
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, ranking.view());
        Object owner = new Object();
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 100, 1L << 40, 4, 4, 4);
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(2), profile, pool, ranking, owner,
                InetAddress.getLoopbackAddress(), null, clock, false, SessionResources.Accounting.CUMULATIVE);
        assertEquals(1, reads.get(), "one cumulative connect reading, no timestamped call");
        assertTrue(pool.reserved(owner) > 0, "the slice is charged");
        now.set(2_000_000_000L);
        driver.close();
        assertEquals(2, reads.get(), "one cumulative cleanup reading");
        assertEquals(0, pool.reserved(owner), "close returned every charge");
        ranking.connect(owner, InetAddress.getLoopbackAddress(), null);
        assertEquals(3, reads.get(), "the registration was released for a fresh connect");
        ranking.disconnect(owner);
    }

    /** A null mode and a ranking of the wrong mode are rejected without acquiring any charge. */
    @Test
    void driverRejectsANullModeAndAWrongModeRanking() throws Exception {
        AtomicLong now = new AtomicLong();
        PressureRanking timestamped = new PressureRanking();
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, timestamped.view(now::get));
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 100, 1L << 40, 4, 4, 4);
        Object owner = new Object();
        InetAddress ip = InetAddress.getLoopbackAddress();
        assertThrows(IllegalArgumentException.class, () -> new SessionSendDriver(SessionContext.of(2), profile,
                pool, timestamped, owner, ip, null, now::get, false, null), "a null mode is rejected");
        assertEquals(0, pool.reserved(owner));
        // A timestamped ranking cannot serve a cumulative driver: the connect rejects it before any charge.
        assertThrows(IllegalStateException.class, () -> new SessionSendDriver(SessionContext.of(2), profile, pool,
                timestamped, owner, ip, null, now::get, false, SessionResources.Accounting.CUMULATIVE));
        assertEquals(0, pool.reserved(owner));
        assertEquals(0.0, timestamped.score(owner, now.get()), "no registration was acquired");
    }

    /**
     * A duplicate construction under an owner key already in use must not touch that owner's ledger. The existing
     * driver keeps its slice charge and its queued request; only the failed constructor's own acquisition may be
     * unwound (Sol rollback finding).
     */
    @Test
    void duplicateConstructionPreservesTheExistingDriversLedger() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 4096, ranking.view(now::get));
        Object owner = new Object();
        InetAddress ip = InetAddress.getLoopbackAddress();
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1024, 1L << 40, 4, 4, 4);
        SessionSendDriver existing = new SessionSendDriver(SessionContext.of(2), profile, pool, ranking, owner, ip,
                null, now::get, 64, 64, 2048);
        assertEquals(2048, pool.reserved(owner), "the existing driver holds its slice");
        // A record beyond the owner's free bytes waits, so the existing driver also owns a queued request.
        assertTrue(existing.submit(frame(3000), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertEquals(1, pool.waitingCount(), "the existing driver's record waits");

        // The same owner key cannot register twice; the failed construction owns neither the slice nor a request.
        assertThrows(IllegalStateException.class, () -> new SessionSendDriver(SessionContext.of(2), profile, pool,
                ranking, owner, ip, null, now::get, 64, 64, 32));
        assertEquals(2048, pool.reserved(owner), "the existing driver's slice stays charged");
        assertEquals(1, pool.waitingCount(), "the existing driver's queued request keeps its place");
        existing.close();
        assertEquals(0, pool.reserved(owner), "the owner's own close ends its ledger");
        assertEquals(0, pool.waitingCount());
    }

    /** A constructor failure after its connect unwinds the registration and every charge the driver took. */
    @Test
    void driverConstructorRollsBackAfterASuccessfulConnect() throws Exception {
        SessionResourcesTest.Scripted scripted = new SessionResourcesTest.Scripted();
        scripted.reading(0, 0, true);
        scripted.reading(0, 5, true);
        scripted.reading(10_000_000_000L, 5_000_000_000L, true);
        PressureRanking ranking = new PressureRanking(scripted);
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 4096, ranking.view());
        Object owner = new Object();
        InetAddress ip = InetAddress.getLoopbackAddress();
        // The connection's byte budget is already spent, so the slice waits and its refused wait samples.
        assertTrue(pool.takeGrant(pool.reserveTicket(owner, ip, null, 4096)));
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 100, 1L << 40, 4, 4, 4);
        StallClock.UnavailableException failure = assertThrows(StallClock.UnavailableException.class, () ->
                new SessionSendDriver(SessionContext.of(2), profile, pool, ranking, owner, ip, null, () -> 0,
                        64, 64, 32, false, SessionResources.Accounting.CUMULATIVE));
        assertEquals(0, failure.getSuppressed().length, "the cleanup succeeded and masked nothing");
        // This reservation predates the failed constructor. It belongs to an existing holder of the same owner
        // key, so the rollback must leave it charged; releasing all owner bytes here erased another driver's
        // ledger (Sol rollback finding). Only the slice this constructor was granted is ever returned.
        assertEquals(4096, pool.reserved(owner), "the reservation that predates this constructor is untouched");
        // The registration is gone: a fresh cumulative connect succeeds from the cleanup's committed reading.
        scripted.reading(20_000_000_000L, 5_000_000_000L, true);
        scripted.reading(30_000_000_000L, 5_000_000_000L, true);
        ranking.connect(owner, ip, null);
        ranking.disconnect(owner);
        assertEquals(5, scripted.taken());
    }

    /** Every complete record of one stream in one drained write sequence. */
    private static List<Record> recordsOf(Drain drain, int stream) throws Exception {
        byte[] all = drain.stream(stream);
        List<Record> out = new ArrayList<Record>();
        int at = 0;
        while (at < all.length) {
            int length = firstRecordLength(Arrays.copyOfRange(all, at, all.length));
            out.add(RecordCodec.decodeRecord(Arrays.copyOfRange(all, at, at + length), stream));
            at += length;
        }
        return out;
    }

    private interface BundleCall {
        List<SentPacket> call();
    }

    @SafeVarargs
    private static List<List<MultistreamSession.UpdateToken>> coverage(
            List<MultistreamSession.UpdateToken>... entries) {
        return Arrays.asList(entries);
    }

    private static List<MultistreamSession.UpdateToken> tokens(MultistreamSession.UpdateToken... entries) {
        return Arrays.asList(entries);
    }

    private static List<OutboundPacket> packets(int... sizes) {
        List<OutboundPacket> out = new ArrayList<OutboundPacket>();
        for (int size : sizes) {
            out.add(frame(size));
        }
        return out;
    }

    /**
     * The CONTROL frontier a covering scope holds after a covered submission, read from the tracker
     * for the GLOBAL-vs-CONTROL resolution assertion.
     */
    @SuppressWarnings("unchecked")
    private static Map<Integer, Long> scopeFrontier(SessionSendDriver driver, MultistreamSession.CausalScope scope)
            throws Exception {
        Field acksField = SessionSendDriver.class.getDeclaredField("acks");
        acksField.setAccessible(true);
        Object tracker = acksField.get(driver);
        Field scopesField = AckJoinTracker.class.getDeclaredField("scopes");
        scopesField.setAccessible(true);
        Object scopeObject = ((Map<Long, Object>) scopesField.get(tracker)).get(scope.id());
        assertNotNull(scopeObject, "the scope is still retained");
        Field frontierField = scopeObject.getClass().getDeclaredField("frontier");
        frontierField.setAccessible(true);
        return (Map<Integer, Long>) frontierField.get(scopeObject);
    }

    /** A session that implements only the contract's abstract methods, so its defaults are observable. */
    private static final class AbstractOnlySession implements MultistreamSession {
        @Override public void bindGame(LaneGame game) {
            throw new UnsupportedOperationException();
        }

        @Override public void activate(ByteBuffer controlLeftover) {
            throw new UnsupportedOperationException();
        }

        @Override public void acceptStream(QuicStreamChannel stream) {
            throw new UnsupportedOperationException();
        }

        @Override public SentPacket send(ProtocolState state, int packetId, byte[] frame, SentPacket... after) {
            throw new UnsupportedOperationException();
        }

        @Override public SentPacket send(ProtocolState state, int packetId, byte[] frame, int uncompressedBytes,
                SentPacket... after) {
            throw new UnsupportedOperationException();
        }

        @Override public List<SentPacket> sendBundle(OutboundPacket open, List<OutboundPacket> members,
                OutboundPacket close) {
            throw new UnsupportedOperationException();
        }

        @Override public void end() {
            throw new UnsupportedOperationException();
        }

        @Override public void abort(int code) {
            throw new UnsupportedOperationException();
        }

        @Override public void applied(Lane lane, long sequence) {
            throw new UnsupportedOperationException();
        }

        @Override public SessionContext context() {
            return SessionContext.of(1);
        }

        @Override public Lane outboundLane(ProtocolState state, int packetId) {
            return Lane.CONTROL;
        }

        @Override public boolean isWritable() {
            return true;
        }
    }

    @Test
    void coveredBundleDefaultFailsClosed() {
        AbstractOnlySession session = new AbstractOnlySession();
        assertThrows(UnsupportedOperationException.class, () -> session.sendCoveredBundle(frame(1),
                new ArrayList<OutboundPacket>(), frame(1), new ArrayList<List<MultistreamSession.UpdateToken>>()));
    }

    /**
     * Empty, multiple and repeated coverage in one atomic run: every member is assigned on CONTROL
     * in order, a token listed twice is consumed once at its highest member, an uncovered member is
     * legal, and each covering scope keeps exactly the frontier its members resolved to.
     */
    @Test
    void coveredBundleResolvesEmptyMultipleAndRepeatedCoverageOnce() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.CausalScope first = driver.openScope();
        MultistreamSession.CausalScope second = driver.openScope();
        MultistreamSession.CausalScope third = driver.openScope();
        MultistreamSession.UpdateToken repeated = driver.registerUpdate(first);
        MultistreamSession.UpdateToken middle = driver.registerUpdate(second);
        MultistreamSession.UpdateToken alsoMiddle = driver.registerUpdate(third);
        List<OutboundPacket> members = packets(5, 6, 7);

        List<SentPacket> handles = driver.submitCoveredBundle(frame(1), members,
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL,
                    StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.ORDINARY_CONTROL},
                frame(1), coverage(tokens(repeated), tokens(middle, alsoMiddle), tokens(repeated)));

        assertEquals(5, handles.size(), "opener, three members and closer");
        for (int i = 0; i < handles.size(); i++) {
            assertEquals(LogicalStream.CONTROL, handles.get(i).logicalStream());
            assertEquals(i + 1, handles.get(i).sequence(), "the run is consecutive in wire order");
        }
        assertEquals(0, driver.trackerRetainedTokens(), "every distinct token is consumed exactly once");
        assertEquals(4L, (long) scopeFrontier(driver, first).get(LogicalStream.CONTROL),
                "the repeated token resolves at member 2, its highest covered member");
        assertEquals(3L, (long) scopeFrontier(driver, second).get(LogicalStream.CONTROL));
        assertEquals(3L, (long) scopeFrontier(driver, third).get(LogicalStream.CONTROL));
        driver.completeScope(first);
        driver.completeScope(second);
        driver.completeScope(third);

        Drain drain = drainAndRelease(driver);
        List<Record> records = recordsOf(drain, LogicalStream.CONTROL);
        assertEquals(5, records.size(), "one uninterrupted CONTROL run");
        assertEquals(RecordCodec.KIND_BUNDLE_OPEN, records.get(0).kind());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(members.get(i).frame(), records.get(i + 1).payload(), "member " + i + " in order");
        }
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "the aggregate run charge is released");
        driver.close();
    }

    /**
     * Strongest-member routing promotes a bundle to GLOBAL: every participating scope resolves to
     * the closing delimiter, even a token that only covered an earlier member, so each scope covers
     * the entire atomic run.
     */
    @Test
    void coveredBundleGlobalPromotionResolvesEveryTokenAtTheClosingDelimiter() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.CausalScope first = driver.openScope();
        MultistreamSession.CausalScope second = driver.openScope();
        MultistreamSession.UpdateToken onFirst = driver.registerUpdate(first);
        MultistreamSession.UpdateToken onSecond = driver.registerUpdate(second);
        MultistreamSession.UpdateToken repeated = driver.registerUpdate(second);

        List<SentPacket> handles = driver.submitCoveredBundle(frame(1), packets(4, 5),
                new StreamSequencer.Fence[] {StreamSequencer.Fence.CONTROL_FENCE, StreamSequencer.Fence.CHUNK},
                frame(1), coverage(tokens(onFirst, repeated), tokens(onSecond, repeated)));

        assertEquals(4, handles.size());
        long closing = handles.get(3).sequence();
        assertEquals(4L, closing);
        assertEquals(closing, (long) scopeFrontier(driver, first).get(LogicalStream.CONTROL),
                "a GLOBAL bundle resolves even a first-member token at the closer");
        assertEquals(closing, (long) scopeFrontier(driver, second).get(LogicalStream.CONTROL));
        assertEquals(0, driver.trackerRetainedTokens());
        driver.completeScope(first);
        driver.completeScope(second);
        driver.close();
    }

    /** Caller-triggered rejection leaves the driver open pending; the caller closes it explicitly. */
    @Test
    void coveredBundleRejectsBadTokenAtFirstMiddleOrLastMemberWithoutConsumingAnyToken() throws Exception {
        for (int position = 0; position < 3; position++) {
            Rig rig = new Rig(2, 1000);
            SessionSendDriver driver = rig.driver;
            MultistreamSession.CausalScope liveScope = driver.openScope();
            MultistreamSession.UpdateToken live = driver.registerUpdate(liveScope);
            MultistreamSession.UpdateToken suppressed = driver.registerUpdate(driver.openScope());
            driver.suppressUpdate(suppressed);
            List<List<MultistreamSession.UpdateToken>> lists =
                    new ArrayList<List<MultistreamSession.UpdateToken>>();
            for (int i = 0; i < 3; i++) {
                lists.add(new ArrayList<MultistreamSession.UpdateToken>());
            }
            lists.get(position).add(suppressed);
            lists.get((position + 1) % 3).add(live);
            long assignedBefore = driver.assignedSequence(LogicalStream.CONTROL);
            int queuedBefore = driver.queuedRecords();
            long chargedBefore = rig.pool.reserved(rig.owner);

            assertThrows(IllegalArgumentException.class,
                    () -> driver.submitCoveredBundle(frame(1), packets(2, 2, 2),
                            new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL,
                                StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.ORDINARY_CONTROL},
                            frame(1), lists),
                    "suppressed token at member " + position);

            assertEquals(assignedBefore, driver.assignedSequence(LogicalStream.CONTROL),
                    "position " + position + " assigned no sequence");
            assertEquals(queuedBefore, driver.queuedRecords(), "position " + position + " queued nothing");
            assertEquals(chargedBefore, rig.pool.reserved(rig.owner), "position " + position + " took no charge");
            assertEquals(1, driver.trackerRetainedTokens(), "the live token survived the rejection");
            assertTrue(driver.validateScopeToken(live), "the live token is still assignable");
            assertFalse(driver.isClosed(), "a bad token is caller misuse, not a wire refusal");
            assertTrue(driver.submitCovered(live, frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, false, 0, 0)
                    .isAssigned(), "the surviving token still resolves later");
            driver.close();
        }
    }

    /**
     * A frame that is not one complete GAME body (13.4) fails RecordCodec's own rule. Each malformed opener,
     * member or closer is refused before any sequence, queue slot, charge or token is touched, so the session
     * stays open and the live token still resolves afterwards. Covers an inner length of zero and an inner
     * length longer than the carried bytes.
     */
    @Test
    void coveredBundleRejectsMalformedGameFramesInPlaceAtEveryPosition() throws Exception {
        StreamSequencer.Fence ordinary = StreamSequencer.Fence.ORDINARY_CONTROL;
        OutboundPacket[] malformed = {
            new OutboundPacket(new byte[] {0}),
            new OutboundPacket(new byte[] {2, 7}),
        };
        for (OutboundPacket bad : malformed) {
            Rig rig = new Rig(2, 1000);
            SessionSendDriver driver = rig.driver;
            MultistreamSession.UpdateToken live = driver.registerUpdate(driver.openScope());

            assertRejectedInPlace(rig, live, "malformed opener", () -> driver.submitCoveredBundle(bad, packets(2),
                    new StreamSequencer.Fence[] {ordinary}, frame(1), emptyCoverage(1)));
            assertRejectedInPlace(rig, live, "malformed closer", () -> driver.submitCoveredBundle(frame(1),
                    packets(2), new StreamSequencer.Fence[] {ordinary}, bad, emptyCoverage(1)));
            for (int position = 0; position < 3; position++) {
                List<OutboundPacket> members = packets(2, 2, 2);
                members.set(position, bad);
                assertRejectedInPlace(rig, live, "malformed member " + position,
                        () -> driver.submitCoveredBundle(frame(1), members,
                                new StreamSequencer.Fence[] {ordinary, ordinary, ordinary}, frame(1),
                                emptyCoverage(3)));
            }
            assertTrue(driver.submitCovered(live, frame(2), ordinary, false, 0, 0).isAssigned(),
                    "the live token still resolves after every malformed refusal");
            driver.close();
        }
    }

    /**
     * The plain bundle path applies the same GAME framing rule as the covered path: each malformed opener,
     * member or closer is refused before any sequence is taken, so the CONTROL sequence, queue count, pool
     * charge and pending update token are unchanged and the driver stays open. Covers an inner length of zero
     * and an inner length longer than the carried bytes.
     */
    @Test
    void bundleRejectsMalformedGameFramesInPlaceAtEveryPosition() throws Exception {
        StreamSequencer.Fence ordinary = StreamSequencer.Fence.ORDINARY_CONTROL;
        OutboundPacket[] malformed = {
            new OutboundPacket(new byte[] {0}),
            new OutboundPacket(new byte[] {2, 7}),
        };
        for (OutboundPacket bad : malformed) {
            Rig rig = new Rig(2, 1000);
            SessionSendDriver driver = rig.driver;
            MultistreamSession.UpdateToken live = driver.registerUpdate(driver.openScope());

            assertRejectedInPlace(rig, live, "malformed opener", () -> driver.submitBundle(bad, packets(2),
                    new StreamSequencer.Fence[] {ordinary}, frame(1)));
            assertRejectedInPlace(rig, live, "malformed closer", () -> driver.submitBundle(frame(1),
                    packets(2), new StreamSequencer.Fence[] {ordinary}, bad));
            for (int position = 0; position < 3; position++) {
                List<OutboundPacket> members = packets(2, 2, 2);
                members.set(position, bad);
                assertRejectedInPlace(rig, live, "malformed member " + position,
                        () -> driver.submitBundle(frame(1), members,
                                new StreamSequencer.Fence[] {ordinary, ordinary, ordinary}, frame(1)));
            }
            assertTrue(driver.submitCovered(live, frame(2), ordinary, false, 0, 0).isAssigned(),
                    "the live token still resolves after every malformed refusal");
            driver.close();
        }
    }

    /**
     * Every member scope is inspected, not only the ones before the first CHUNK or GLOBAL. An invalid later scope
     * (REALTIME or null) refuses the whole bundle in place, and the sequencer rule agrees with the driver.
     */
    @Test
    void coveredBundleRejectsAnInvalidScopeAnywhereInTheRunInPlace() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.UpdateToken live = driver.registerUpdate(driver.openScope());
        StreamSequencer.Fence chunk = StreamSequencer.Fence.CHUNK;
        StreamSequencer.Fence global = StreamSequencer.Fence.GLOBAL;

        assertRejectedInPlace(rig, live, "CHUNK then REALTIME", () -> driver.submitCoveredBundle(frame(1),
                packets(2, 2), new StreamSequencer.Fence[] {chunk, StreamSequencer.Fence.REALTIME}, frame(1),
                emptyCoverage(2)));
        assertRejectedInPlace(rig, live, "GLOBAL then null", () -> driver.submitCoveredBundle(frame(1),
                packets(2, 2), new StreamSequencer.Fence[] {global, null}, frame(1), emptyCoverage(2)));

        assertThrows(IllegalArgumentException.class,
                () -> StreamSequencer.bundleScope(chunk, StreamSequencer.Fence.REALTIME));
        assertThrows(IllegalArgumentException.class, () -> StreamSequencer.bundleScope(global, null));
        assertEquals(StreamSequencer.Fence.GLOBAL, StreamSequencer.bundleScope(StreamSequencer.Fence.VIEW, chunk));
        assertEquals(StreamSequencer.Fence.VIEW,
                StreamSequencer.bundleScope(StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.VIEW));
        assertEquals(StreamSequencer.Fence.CONTROL_FENCE,
                StreamSequencer.bundleScope(StreamSequencer.Fence.ORDINARY_CONTROL));
        driver.close();
    }

    /**
     * A clock failure on the one reading taken inside enqueue (the pump's ranking update) is a local defect after
     * assignment. The call returns no handle, the driver closes with 0x12 and the original cause, every charge
     * including the run's is released, the queue is empty and nothing is written. Only the reachable terminal
     * cleanup is claimed here, not the post-assignment admission refusal.
     */
    @Test
    void coveredBundleClockFailureDuringEnqueueReleasesChargesQueueAndWritesNothing() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        AtomicBoolean armed = new AtomicBoolean(false);
        LongSupplier clock = () -> {
            if (armed.getAndSet(false)) {
                throw new IllegalStateException("injected clock failure");
            }
            return now.get();
        };
        PressureRanking ranking = new PressureRanking();
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, ranking.view(now::get));
        Object owner = new Object();
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
        // LEGACY reads the clock in pump(); CUMULATIVE would not, so the injected failure needs LEGACY.
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(2), profile, pool, ranking, owner,
                InetAddress.getLoopbackAddress(), null, clock, false, SessionResources.Accounting.LEGACY);
        MultistreamSession.UpdateToken token = driver.registerUpdate(driver.openScope());
        assertTrue(pool.reserved(owner) > 0, "the REALTIME slice is charged before the call");

        armed.set(true);
        List<SentPacket> handles = driver.submitCoveredBundle(frame(1), packets(2, 2),
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL,
                    StreamSequencer.Fence.ORDINARY_CONTROL}, frame(1),
                coverage(tokens(token), new ArrayList<MultistreamSession.UpdateToken>()));

        assertFalse(armed.get(), "the injected failure was reached inside enqueue, not before it");
        assertTrue(handles.isEmpty(), "a locally failed call returns no handle");
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertEquals(0, pool.reserved(owner), "terminal cleanup released the run charge and the slice");
        assertEquals(0, driver.queuedRecords(), "terminal cleanup emptied the queue");
        assertFalse(driver.validateScopeToken(token), "the token is gone with the closed driver");
        assertNull(driver.poll(stream -> true), "nothing is written after the local close");
    }

    private static Map<Integer, Long> dependencies(Record.Dependency[] dependencies) {
        Map<Integer, Long> out = new java.util.TreeMap<Integer, Long>();
        for (Record.Dependency dependency : dependencies) {
            out.put(dependency.streamIndex(), dependency.sequence());
        }
        return out;
    }

    private static Map<Integer, Long> dependencies(List<Record.Dependency> dependencies) {
        return dependencies(dependencies.toArray(new Record.Dependency[dependencies.size()]));
    }

    private static int xForShard(int shards, int shard) {
        int x = 0;
        while (ShardHash.shardOf(x, 0, 0, shards) != shard) {
            x++;
        }
        return x;
    }

    private static List<List<MultistreamSession.UpdateToken>> emptyCoverage(int members) {
        List<List<MultistreamSession.UpdateToken>> out =
                new ArrayList<List<MultistreamSession.UpdateToken>>();
        for (int i = 0; i < members; i++) {
            out.add(new ArrayList<MultistreamSession.UpdateToken>());
        }
        return out;
    }

    private static void assertRejectedInPlace(Rig rig, MultistreamSession.UpdateToken live, String why,
            BundleCall call) throws Exception {
        SessionSendDriver driver = rig.driver;
        long assigned = driver.assignedSequence(LogicalStream.CONTROL);
        int queued = driver.queuedRecords();
        long charged = rig.pool.reserved(rig.owner);
        assertThrows(IllegalArgumentException.class, call::call, why);
        assertEquals(assigned, driver.assignedSequence(LogicalStream.CONTROL), why + ": no sequence");
        assertEquals(queued, driver.queuedRecords(), why + ": no queue entry");
        assertEquals(charged, rig.pool.reserved(rig.owner), why + ": no charge");
        assertEquals(1, driver.trackerRetainedTokens(), why + ": the live token stays live");
        assertTrue(driver.validateScopeToken(live), why + ": still assignable");
        assertFalse(driver.isClosed(), why + ": caller misuse keeps the driver open");
    }

    /**
     * Nulls, malformed frames, mismatched coverage shapes, invalid scopes and run-size overflow are
     * all ordinary validation failures: they throw before any sequence, queue slot, charge or token
     * is consumed, and the live token can still be assigned afterwards.
     */
    @Test
    void coveredBundleRejectsNullsShapeAndMalformedFramesInPlace() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.UpdateToken live = driver.registerUpdate(driver.openScope());
        StreamSequencer.Fence ordinary = StreamSequencer.Fence.ORDINARY_CONTROL;
        List<MultistreamSession.UpdateToken> liveList = tokens(live);
        List<List<MultistreamSession.UpdateToken>> oneCoverage = coverage(liveList);

        assertRejectedInPlace(rig, live, "null opener", () -> driver.submitCoveredBundle(null, packets(2),
                new StreamSequencer.Fence[] {ordinary}, frame(1), emptyCoverage(1)));
        assertRejectedInPlace(rig, live, "null closer", () -> driver.submitCoveredBundle(frame(1), packets(2),
                new StreamSequencer.Fence[] {ordinary}, null, emptyCoverage(1)));
        assertRejectedInPlace(rig, live, "null members", () -> driver.submitCoveredBundle(frame(1), null,
                new StreamSequencer.Fence[] {ordinary}, frame(1), emptyCoverage(1)));
        assertRejectedInPlace(rig, live, "null scopes", () -> driver.submitCoveredBundle(frame(1), packets(2),
                null, frame(1), emptyCoverage(1)));
        assertRejectedInPlace(rig, live, "null coverage", () -> driver.submitCoveredBundle(frame(1), packets(2),
                new StreamSequencer.Fence[] {ordinary}, frame(1), null));
        assertRejectedInPlace(rig, live, "outer coverage size", () -> driver.submitCoveredBundle(frame(1),
                packets(2), new StreamSequencer.Fence[] {ordinary}, frame(1),
                emptyCoverage(2)));
        assertRejectedInPlace(rig, live, "scope count", () -> driver.submitCoveredBundle(frame(1), packets(2),
                new StreamSequencer.Fence[] {ordinary, ordinary}, frame(1), emptyCoverage(2)));
        assertRejectedInPlace(rig, live, "null inner coverage", () -> driver.submitCoveredBundle(frame(1),
                packets(2), new StreamSequencer.Fence[] {ordinary}, frame(1),
                Arrays.asList((List<MultistreamSession.UpdateToken>) null)));
        assertRejectedInPlace(rig, live, "null token", () -> driver.submitCoveredBundle(frame(1),
                packets(2), new StreamSequencer.Fence[] {ordinary}, frame(1),
                Arrays.asList(Arrays.asList((MultistreamSession.UpdateToken) null))));
        for (int position = 0; position < 3; position++) {
            List<OutboundPacket> members = packets(2, 2, 2);
            members.set(position, null);
            assertRejectedInPlace(rig, live, "null member " + position, () -> driver.submitCoveredBundle(frame(1),
                    members, new StreamSequencer.Fence[] {ordinary, ordinary, ordinary}, frame(1),
                    emptyCoverage(3)));
        }
        assertRejectedInPlace(rig, live, "empty opener", () -> driver.submitCoveredBundle(new OutboundPacket(new byte[0]),
                packets(2), new StreamSequencer.Fence[] {ordinary}, frame(1), emptyCoverage(1)));
        assertRejectedInPlace(rig, live, "empty closer", () -> driver.submitCoveredBundle(frame(1), packets(2),
                new StreamSequencer.Fence[] {ordinary}, new OutboundPacket(new byte[0]), emptyCoverage(1)));
        assertRejectedInPlace(rig, live, "null scope", () -> driver.submitCoveredBundle(frame(1), packets(2),
                new StreamSequencer.Fence[] {null}, frame(1), emptyCoverage(1)));
        assertRejectedInPlace(rig, live, "realtime scope", () -> driver.submitCoveredBundle(frame(1), packets(2),
                new StreamSequencer.Fence[] {StreamSequencer.Fence.REALTIME}, frame(1), emptyCoverage(1)));
        List<OutboundPacket> many = packets(new int[StreamSequencer.MAX_BUNDLE_MEMBERS + 1]);
        StreamSequencer.Fence[] manyScopes = new StreamSequencer.Fence[StreamSequencer.MAX_BUNDLE_MEMBERS + 1];
        Arrays.fill(manyScopes, ordinary);
        assertRejectedInPlace(rig, live, "member cap", () -> driver.submitCoveredBundle(frame(1), many,
                manyScopes, frame(1), emptyCoverage(StreamSequencer.MAX_BUNDLE_MEMBERS + 1)));

        assertTrue(driver.submitCovered(live, frame(2), ordinary, false, 0, 0).isAssigned(),
                "the untouched token resolves after every rejection");
        driver.close();
    }

    /**
     * Foreign, retired and already assigned tokens anywhere in the coverage reject the whole call;
     * an unrelated live token in the same call is not consumed as collateral.
     */
    @Test
    void coveredBundleRejectsForeignRetiredAndAssignedTokensWithoutConsumingLiveOnes() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        Rig other = new Rig(2, 1000);
        MultistreamSession.UpdateToken foreign = other.driver.registerUpdate(other.driver.openScope());
        MultistreamSession.UpdateToken live = driver.registerUpdate(driver.openScope());
        long before = driver.assignedSequence(LogicalStream.CONTROL);
        StreamSequencer.Fence ordinary = StreamSequencer.Fence.ORDINARY_CONTROL;

        assertThrows(AckJoinTracker.ForeignHandleException.class, () -> driver.submitCoveredBundle(frame(1),
                packets(2, 2), new StreamSequencer.Fence[] {ordinary, ordinary}, frame(1),
                coverage(tokens(foreign), tokens(live))));
        assertEquals(before, driver.assignedSequence(LogicalStream.CONTROL));
        assertEquals(1, driver.trackerRetainedTokens(), "the live token survived the foreign token");
        assertTrue(driver.validateScopeToken(live));

        MultistreamSession.UpdateToken assigned = driver.registerUpdate(driver.openScope());
        driver.assignScopeToken(assigned, LogicalStream.CONTROL, 7);
        long afterAssign = driver.assignedSequence(LogicalStream.CONTROL);
        assertThrows(IllegalArgumentException.class, () -> driver.submitCoveredBundle(frame(1), packets(2, 2),
                new StreamSequencer.Fence[] {ordinary, ordinary}, frame(1), coverage(tokens(assigned), tokens(live))));
        assertEquals(afterAssign, driver.assignedSequence(LogicalStream.CONTROL));
        assertEquals(1, driver.trackerRetainedTokens());

        assertTrue(driver.createAck(9, frame(2).frame(), 8));
        MultistreamSession.CausalScope retiredScope = driver.openScope();
        MultistreamSession.UpdateToken retired = driver.registerUpdate(retiredScope);
        driver.coverScope(9, retiredScope);
        driver.sealAckCoverage(9);
        rig.now.addAndGet(AckJoinTracker.DEADLINE_NANOS + 1);
        assertNull(driver.takeAck(9));
        assertFalse(driver.validateScopeToken(retired), "the retired token is gone");
        assertThrows(IllegalArgumentException.class, () -> driver.submitCoveredBundle(frame(1), packets(2, 2),
                new StreamSequencer.Fence[] {ordinary, ordinary}, frame(1), coverage(tokens(retired), tokens(live))));
        assertEquals(afterAssign, driver.assignedSequence(LogicalStream.CONTROL));
        assertEquals(1, driver.trackerRetainedTokens());
        assertTrue(driver.validateScopeToken(live), "the live token is still assignable");
        driver.close();
        other.driver.close();
    }

    /**
     * A run larger than maxRecordBytes is refused before assignment; a cap the run can never be
     * admitted by and a queue overflow are terminal resource refusals that assign no member and
     * acquire no run charge. All leave the sequences and live tokens of an ordinary rejection alone.
     */
    @Test
    void coveredBundleOversizeCapAndQueueRefusalsAssignNothing() throws Exception {
        StreamSequencer.Fence ordinary = StreamSequencer.Fence.ORDINARY_CONTROL;

        HeadroomRig oversized = new HeadroomRig(1000, 200, 50);
        MultistreamSession.UpdateToken token = oversized.driver.registerUpdate(oversized.driver.openScope());
        assertThrows(IllegalArgumentException.class, () -> oversized.driver.submitCoveredBundle(frame(1),
                packets(100, 100), new StreamSequencer.Fence[] {ordinary, ordinary}, frame(1),
                coverage(tokens(token), new ArrayList<MultistreamSession.UpdateToken>())));
        assertEquals(0, oversized.driver.assignedSequence(LogicalStream.CONTROL), "no sequence taken");
        assertEquals(0, oversized.driver.queuedRecords(), "nothing queued");
        assertEquals(oversized.slice, oversized.pool.reserved(oversized.owner), "no run charge");
        assertTrue(oversized.driver.validateScopeToken(token), "the token stays live");
        assertFalse(oversized.driver.isClosed(), "maxRecordBytes is an ordinary refusal");

        Rig capped = new Rig(2, 1000, 100);
        MultistreamSession.UpdateToken cappedToken = capped.driver.registerUpdate(capped.driver.openScope());
        assertTrue(capped.driver.submitCoveredBundle(frame(1), packets(200),
                new StreamSequencer.Fence[] {ordinary}, frame(1), coverage(tokens(cappedToken))).isEmpty());
        assertTrue(capped.driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, capped.driver.closeCode());
        assertEquals(0, capped.driver.assignedSequence(LogicalStream.CONTROL), "the refused run assigned nothing");
        assertEquals(0, capped.driver.queuedRecords());
        assertEquals(0, capped.pool.reserved(capped.owner), "the terminal close released every charge");

        Rig queued = new Rig(2, 1);
        assertTrue(queued.driver.submit(frame(2), ordinary, 0, 0).isAssigned(), "fill the one queue slot");
        MultistreamSession.UpdateToken queuedToken = queued.driver.registerUpdate(queued.driver.openScope());
        long queuedBefore = queued.driver.assignedSequence(LogicalStream.CONTROL);
        assertTrue(queued.driver.submitCoveredBundle(frame(1), packets(2, 2),
                new StreamSequencer.Fence[] {ordinary, ordinary}, frame(1),
                coverage(tokens(queuedToken), new ArrayList<MultistreamSession.UpdateToken>())).isEmpty());
        assertTrue(queued.driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, queued.driver.closeCode());
        assertEquals(queuedBefore, queued.driver.assignedSequence(LogicalStream.CONTROL),
                "the overflowing run assigned no member");
        assertEquals(0, queued.pool.reserved(queued.owner), "the terminal close released every charge");

        Rig deferred = new Rig(2, 1000, 100);
        deferred.driver.releaseOnOwnerClose();
        MultistreamSession.UpdateToken deferredToken =
                deferred.driver.registerUpdate(deferred.driver.openScope());
        assertTrue(deferred.driver.submitCoveredBundle(frame(1), packets(200),
                new StreamSequencer.Fence[] {ordinary}, frame(1), coverage(tokens(deferredToken))).isEmpty());
        assertTrue(deferred.driver.isClosed(), "the driver stops at once");
        assertEquals(0, deferred.driver.assignedSequence(LogicalStream.CONTROL));
        assertEquals(1, deferred.driver.trackerRetainedTokens(),
                "the stopped driver has not consumed the token; the owner close still cleans up");
        deferred.driver.close();
        assertEquals(0, deferred.pool.reserved(deferred.owner));
    }

    /**
     * Suppression and a covered bundle race on the same monitor: either suppression wins and the
     * whole call rejects with no sequence, or the bundle wins and the later suppression rejects.
     */
    @Test
    void coveredBundleAndSuppressionSerializeUnderOneMonitor() throws Exception {
        for (int round = 0; round < 30; round++) {
            Rig rig = new Rig(1, 1000);
            SessionSendDriver driver = rig.driver;
            MultistreamSession.UpdateToken token = driver.registerUpdate(driver.openScope());
            AtomicReference<List<SentPacket>> handles = new AtomicReference<List<SentPacket>>();
            AtomicReference<Throwable> submitFailure = new AtomicReference<Throwable>();
            AtomicReference<Throwable> suppressFailure = new AtomicReference<Throwable>();
            CountDownLatch go = new CountDownLatch(1);
            Thread submit = new Thread(() -> {
                try {
                    go.await();
                    handles.set(driver.submitCoveredBundle(frame(1), packets(2),
                            new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL}, frame(1),
                            coverage(tokens(token))));
                } catch (Throwable failure) {
                    submitFailure.set(failure);
                }
            });
            Thread suppress = new Thread(() -> {
                try {
                    go.await();
                    driver.suppressUpdate(token);
                } catch (Throwable failure) {
                    suppressFailure.set(failure);
                }
            });
            submit.start();
            suppress.start();
            go.countDown();
            submit.join(10_000);
            suppress.join(10_000);
            boolean accepted = handles.get() != null && !handles.get().isEmpty();
            if (accepted) {
                assertNull(submitFailure.get(), "round " + round);
                assertTrue(suppressFailure.get() instanceof IllegalArgumentException,
                        "round " + round + ": the bundle won, suppression must reject");
                assertEquals(3, driver.assignedSequence(LogicalStream.CONTROL),
                        "round " + round + ": opener, member and closer");
            } else {
                assertTrue(submitFailure.get() instanceof IllegalArgumentException,
                        "round " + round + ": suppression won, the bundle must reject");
                assertNull(suppressFailure.get(), "round " + round + ": suppression succeeded");
                assertEquals(0, driver.assignedSequence(LogicalStream.CONTROL), "round " + round);
            }
            assertEquals(0, driver.trackerRetainedTokens(), "round " + round + ": the token is consumed once");
            assertFalse(driver.isClosed());
            driver.close();
        }
    }

    /**
     * One CONTROL run, one aggregate charge: members appear in order between the delimiters, the
     * run's bytes stay charged until the last member's last slice completes, and duplicate or late
     * releases (and a write failure) release exactly once.
     */
    @Test
    void coveredBundleOwnsOneAggregateChargeUntilEveryMemberAndSliceFinishes() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        List<OutboundPacket> members = packets(5, 6, 7);
        List<SentPacket> handles = driver.submitCoveredBundle(frame(1), members,
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL,
                    StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.ORDINARY_CONTROL},
                frame(1), emptyCoverage(3));
        assertEquals(5, handles.size());
        long runCharge = rig.pool.reserved(rig.owner) - rig.slice;
        assertTrue(runCharge > 0, "the opener owns the whole run's bytes as one charge");

        List<SessionSendDriver.Write> writes = new ArrayList<SessionSendDriver.Write>();
        pollAll(driver, writes);
        assertEquals(5, writes.size(), "every record is one slice");
        assertEquals(RecordCodec.KIND_BUNDLE_OPEN,
                RecordCodec.decodeRecord(writes.get(0).bytes(), LogicalStream.CONTROL).kind());
        for (int i = 0; i < 3; i++) {
            Record member = RecordCodec.decodeRecord(writes.get(i + 1).bytes(), LogicalStream.CONTROL);
            assertArrayEquals(members.get(i).frame(), member.payload(), "member " + i + " wire order");
        }
        assertEquals(0,
                RecordCodec.decodeRecord(writes.get(4).bytes(), LogicalStream.CONTROL).kind());

        for (int i = 0; i < 4; i++) {
            driver.released(writes.get(i).token());
        }
        assertEquals(rig.slice + runCharge, rig.pool.reserved(rig.owner),
                "the run stays charged while the closer is outstanding");
        long closingToken = writes.get(4).token();
        driver.failed(closingToken);
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "a write failure releases the run once");
        driver.failed(closingToken);
        driver.released(closingToken);
        assertEquals(rig.slice, rig.pool.reserved(rig.owner), "late and duplicate outcomes release nothing again");
        driver.close();
        assertEquals(0, rig.pool.reserved(rig.owner));
    }

    /** A covered bundle neither completes a scope nor seals an ack: the caller still owns both. */
    @Test
    void coveredBundleLeavesScopeCompletionAndAckSealingToTheCaller() throws Exception {
        Rig rig = new Rig(2, 1000);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(1, frame(2).frame(), 16));
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        driver.coverScope(1, scope);
        assertNull(driver.takeAck(1), "an unsealed, incomplete scope never resolves");

        assertTrue(driver.submitCoveredBundle(frame(1), packets(3),
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL}, frame(1),
                coverage(tokens(token))).size() == 3);
        assertNull(driver.takeAck(1), "the bundle did not complete the scope");
        driver.completeScope(scope);
        assertNull(driver.takeAck(1), "the bundle did not seal the ack");
        driver.sealAckCoverage(1);
        assertNotNull(driver.takeAck(1), "the caller's completion and seal resolve the ack");
        driver.close();
    }

    /** Batch prevalidation is non-mutating, and nested, unmatched or invalid markers assign nothing. */
    @Test
    void batchPrevalidationIsNonMutatingAndInvalidMarkersAssignNothing() throws Exception {
        BatchJoinTracker tracker = new BatchJoinTracker();
        tracker.begin(1, 5, 7);
        tracker.chunk(1, 2, 6, 7);
        tracker.chunk(1, 3, 8, 7);
        assertThrows(IllegalArgumentException.class, () -> tracker.validateBegin(2, 7), "nested begin");
        assertEquals(5, dependencies(tracker.previewFinish(1, 7)).get(LogicalStream.CONTROL),
                "the active batch survived the nested begin");
        assertTrue(tracker.active());
        tracker.validateInstall(1, 2, 7, 4);
        assertTrue(tracker.active(), "valid install prevalidation mutates nothing");
        assertThrows(IllegalArgumentException.class, () -> tracker.validateInstall(1, 1, 7, 4), "CONTROL");
        assertThrows(IllegalArgumentException.class, () -> tracker.validateInstall(1, 9, 7, 4), "above the streams");
        assertThrows(IllegalArgumentException.class, () -> tracker.validateInstall(9, 2, 7, 4), "wrong id");
        assertThrows(IllegalArgumentException.class, () -> tracker.validateInstall(1, 2, 8, 4), "wrong epoch");
        assertTrue(tracker.active(), "rejected install prevalidation mutates nothing");
        assertThrows(IllegalArgumentException.class, () -> tracker.previewFinish(9, 7), "wrong id");
        assertTrue(tracker.active());
        Record.Dependency[] preview = tracker.previewFinish(1, 7);
        assertEquals(5, dependencies(preview).get(LogicalStream.CONTROL));
        assertEquals(6, dependencies(preview).get(2));
        assertEquals(8, dependencies(preview).get(3));
        assertTrue(tracker.active(), "a valid finish preview does not clear the batch");
        assertThrows(IllegalArgumentException.class, () -> tracker.previewFinish(1, 8), "epoch mismatch");
        assertTrue(tracker.active(), "a refused finish preview mutates nothing");
        assertThrows(IllegalArgumentException.class, () -> tracker.finish(1, 8), "epoch mismatch");
        assertFalse(tracker.active(), "the epoch mismatch on finish discards stale installs");
        tracker.begin(2, 20, 0);
        assertEquals(20, dependencies(tracker.previewFinish(2, 0)).get(LogicalStream.CONTROL));

        Rig rig = new Rig(3, 1000);
        SessionSendDriver driver = rig.driver;
        SentPacket start = driver.beginBatch(1, frame(2));
        assertTrue(start.isAssigned());
        long afterStart = driver.assignedSequence(LogicalStream.CONTROL);
        assertThrows(IllegalArgumentException.class, () -> driver.beginBatch(1, frame(2)), "nested");
        assertEquals(afterStart, driver.assignedSequence(LogicalStream.CONTROL),
                "the nested begin assigned no start record");
        assertThrows(IllegalArgumentException.class, () -> driver.batchInstall(9, frame(2), 0, 0), "unmatched install");
        assertEquals(afterStart, driver.assignedSequence(LogicalStream.CONTROL));
        long chunkBefore = driver.assignedSequence(2);
        assertThrows(IllegalArgumentException.class, () -> driver.finishBatch(9, frame(2)), "unmatched finish");
        assertEquals(chunkBefore, driver.assignedSequence(2),
                "the unmatched finish assigned no join");
        assertThrows(IllegalArgumentException.class, () -> driver.beginBatch(0, frame(2)), "invalid id");
        driver.close();
    }

    /**
     * A finish waits for the start and the per-stream maximum of every install, even across several
     * shards, and the next start keeps the previous successfully sequenced JOIN dependency.
     */
    @Test
    void batchFinishWaitsForStartAndPerStreamInstallMaximaAndNextStartKeepsTheJoin() throws Exception {
        Rig rig = new Rig(3, 1000);
        SessionSendDriver driver = rig.driver;
        SentPacket start = driver.beginBatch(1, frame(2));
        int x1 = xForShard(3, 1);
        int x2 = xForShard(3, 2);
        SentPacket first = driver.batchInstall(1, frame(3), x1, 0);
        SentPacket second = driver.batchInstall(1, frame(4), x2, 0);
        SentPacket again = driver.batchInstall(1, frame(5), x1, 0);
        SentPacket join = driver.finishBatch(1, frame(2));
        assertTrue(join.isAssigned());
        assertEquals(2, join.logicalStream());

        Drain drain = drainAndRelease(driver);
        Map<Integer, Long> finish = dependencies(recordOf(drain, join).dependencies());
        assertEquals(start.sequence(), (long) finish.get(LogicalStream.CONTROL), "finish waits for the start");
        assertEquals(again.sequence(), (long) finish.get(first.logicalStream()), "the install maximum on shard 1");
        assertEquals(second.sequence(), (long) finish.get(second.logicalStream()), "the install maximum on shard 2");

        SentPacket secondStart = driver.beginBatch(2, frame(2));
        SentPacket emptyJoin = driver.finishBatch(2, frame(2));
        Drain secondDrain = drainAndRelease(driver);
        Map<Integer, Long> next = dependencies(recordOf(secondDrain, secondStart).dependencies());
        assertEquals(join.sequence(), (long) next.get(2),
                "the next start keeps the previous JOIN even across an empty batch");
        assertEquals(emptyJoin.sequence(), (long) dependencies(recordOf(secondDrain, emptyJoin).dependencies())
                .get(LogicalStream.CONTROL));
        driver.close();
    }

    /** Transitions abort active installs before the next batch observes them, but keep the JOIN chain. */
    @Test
    void batchTransitionDiscardsActiveInstallsButKeepsThePreviousJoin() throws Exception {
        Rig rig = new Rig(3, 1000);
        SessionSendDriver driver = rig.driver;
        SentPacket start = driver.beginBatch(1, frame(2));
        SentPacket join = driver.finishBatch(1, frame(2));
        assertTrue(join.isAssigned());
        SentPacket start2 = driver.beginBatch(2, frame(2));
        assertTrue(start2.isAssigned());
        SentPacket stale = driver.batchInstall(2, frame(3), 0, 0);
        assertTrue(stale.isAssigned(), "a batch 2 install is active before the transition");
        driver.submitEpochTransition(frame(2));
        SentPacket start3 = driver.beginBatch(3, frame(2));
        assertTrue(start3.isAssigned(), "the aborted batch 2 no longer blocks a new one");
        SentPacket join3 = driver.finishBatch(3, frame(2));

        Drain drain = drainAndRelease(driver);
        Map<Integer, Long> deps = dependencies(recordOf(drain, join3).dependencies());
        assertEquals(1, deps.size(), "only the new start remains; the aborted install is gone");
        assertEquals(start3.sequence(), (long) deps.get(LogicalStream.CONTROL));
        Map<Integer, Long> startDeps = dependencies(recordOf(drain, start3).dependencies());
        assertEquals(stale.sequence(), (long) startDeps.get(2),
                "the view gate still waits for the aborted install's wire record");
        assertTrue(join.sequence() < stale.sequence(), "the stale install sequenced after the previous JOIN");
        driver.close();

        // With no install after the JOIN, the next start carries that JOIN explicitly across a transition.
        Rig retained = new Rig(3, 1000);
        assertTrue(retained.driver.beginBatch(1, frame(2)).isAssigned());
        SentPacket retainedJoin = retained.driver.finishBatch(1, frame(2));
        assertTrue(retainedJoin.isAssigned());
        assertTrue(retained.driver.beginBatch(2, frame(2)).isAssigned());
        retained.driver.submitEpochTransition(frame(2));
        SentPacket after = retained.driver.beginBatch(3, frame(2));
        Drain retainedDrain = drainAndRelease(retained.driver);
        assertEquals(retainedJoin.sequence(),
                (long) dependencies(recordOf(retainedDrain, after).dependencies()).get(2),
                "the previous JOIN chain survives the transition");
        retained.driver.close();
    }

    /** Begin/install/finish commit no batch state when their record cannot be enqueued. */
    @Test
    void batchStateCommitsOnlyAfterSuccessfulEnqueue() throws Exception {
        Rig fullStart = new Rig(2, 1);
        assertTrue(fullStart.driver.submit(frame(2), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        long controlBefore = fullStart.driver.assignedSequence(LogicalStream.CONTROL);
        SentPacket refusedStart = fullStart.driver.beginBatch(1, frame(2));
        assertFalse(refusedStart.isAssigned());
        assertTrue(fullStart.driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, fullStart.driver.closeCode());
        assertEquals(controlBefore, fullStart.driver.assignedSequence(LogicalStream.CONTROL),
                "the overflowing start assigned nothing");

        Rig fullInstall = new Rig(2, 1);
        SentPacket start = fullInstall.driver.beginBatch(1, frame(2));
        assertTrue(start.isAssigned());
        long chunkBefore = fullInstall.driver.assignedSequence(2);
        SentPacket refusedInstall = fullInstall.driver.batchInstall(1, frame(2), 0, 0);
        assertFalse(refusedInstall.isAssigned());
        assertTrue(fullInstall.driver.isClosed());
        assertEquals(chunkBefore, fullInstall.driver.assignedSequence(2),
                "the overflowing install assigned no chunk record");

        Rig fullFinish = new Rig(2, 2);
        assertTrue(fullFinish.driver.beginBatch(1, frame(2)).isAssigned());
        assertTrue(fullFinish.driver.batchInstall(1, frame(2), 0, 0).isAssigned());
        long joinBefore = fullFinish.driver.assignedSequence(2);
        SentPacket refusedJoin = fullFinish.driver.finishBatch(1, frame(2));
        assertFalse(refusedJoin.isAssigned());
        assertTrue(fullFinish.driver.isClosed());
        assertEquals(joinBefore, fullFinish.driver.assignedSequence(2),
                "the overflowing join assigned no BATCH_JOIN");
    }

    /**
     * A join that passes the queue precheck but that no applicable cap can admit is refused inside
     * enqueue, after assignJoin has taken its CHUNK[0] sequence. The refusal closes the driver with
     * 0x12, so the consumed sequence and the queued start are never written and the batch is not
     * committed (finding: finishBatch assigns before enqueue; the consumed sequence is unobservable).
     */
    @Test
    void refusedBatchJoinAfterAssignmentClosesAndWritesNothing() throws Exception {
        Rig rig = new Rig(2, 1000, 100);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.beginBatch(1, frame(2)).isAssigned());
        SentPacket join = driver.finishBatch(1, frame(200));
        assertFalse(join.isAssigned(), "the join no cap can admit is not assigned to the caller");
        assertTrue(driver.isClosed(), "a refused enqueue after assignment is a terminal 0x12 close");
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        Drain drain = drainAndRelease(driver);
        assertEquals(0, drain.writes.size(), "nothing queued before or by the join is written after the close");
        assertEquals(0, rig.pool.reserved(rig.owner), "the terminal close released every charge");
    }

    /** Inputs are snapshotted: mutating the caller's lists after the call changes nothing. */
    @Test
    void coveredBundleSnapshotsItsInputs() throws Exception {
        Rig rig = new Rig(1, 1000);
        SessionSendDriver driver = rig.driver;
        MultistreamSession.UpdateToken token = driver.registerUpdate(driver.openScope());
        List<OutboundPacket> members = new ArrayList<OutboundPacket>(packets(3));
        List<List<MultistreamSession.UpdateToken>> coverage = new ArrayList<List<MultistreamSession.UpdateToken>>();
        coverage.add(new ArrayList<MultistreamSession.UpdateToken>(Arrays.asList(token)));
        List<SentPacket> handles = driver.submitCoveredBundle(frame(1), members,
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL}, frame(1), coverage);

        assertEquals(3, handles.size());
        members.clear();
        coverage.clear();
        handles.clear();
        assertEquals(3, driver.queuedRecords(), "the driver kept its own run (opener, member, closer), not the caller's list");
        assertEquals(0, driver.trackerRetainedTokens(), "the token was consumed at submission");
        Drain drain = drainAndRelease(driver);
        assertEquals(3, recordsOf(drain, LogicalStream.CONTROL).size(), "the run is still whole");
        driver.close();
    }

    /**
     * A covered bundle never overtakes an earlier record: its single aggregate charge is requested
     * and offered only in assignment order, so the earlier record stays writable.
     */
    @Test
    void coveredBundleWaitsBehindAnEarlierRecordAndNeverStallsIt() throws Exception {
        HeadroomRig rig = new HeadroomRig(100, 86, 14);
        SessionSendDriver driver = rig.driver;
        // Large enough that the early record keeps its charge, so the run's aggregate cannot fit beside it.
        SentPacket early = driver.submit(frame(70), StreamSequencer.Fence.CHUNK, 0, 0);
        assertTrue(early.isAssigned());
        List<SentPacket> unit = driver.submitCoveredBundle(frame(1), packets(20),
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL}, frame(1),
                emptyCoverage(1));
        assertEquals(3, unit.size());
        SessionSendDriver.Write first = driver.poll(stream -> stream == early.logicalStream());
        assertNotNull(first, "the earlier chunk is offered first");
        assertEquals(early.sequence(), RecordCodec.decodeRecord(first.bytes(), first.stream()).sequence());
        assertNull(driver.poll(stream -> true), "the covered run waits behind the earlier granted record");
        driver.released(first.token());
        Drain rest = drainAndRelease(driver);
        assertEquals(3, rest.writes.size(), "the whole covered run follows once the earlier record releases");
        assertEquals(RecordCodec.KIND_BUNDLE_OPEN,
                RecordCodec.decodeRecord(rest.writes.get(0).bytes(), LogicalStream.CONTROL).kind());
        assertEquals(rig.slice, rig.pool.reserved(rig.owner));
        driver.close();
    }

    /** ACK headroom stays outside the ordinary FIFO: a pending ACK never blocks a covered bundle. */
    @Test
    void coveredBundleIsAdmittedUnderPendingAckHeadroom() throws Exception {
        HeadroomRig rig = new HeadroomRig(102, 86, 16);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(1, frame(2).frame(), 16));
        MultistreamSession.CausalScope scope = driver.openScope();
        MultistreamSession.UpdateToken token = driver.registerUpdate(scope);
        driver.coverScope(1, scope);

        List<SentPacket> handles = driver.submitCoveredBundle(frame(1), packets(10),
                new StreamSequencer.Fence[] {StreamSequencer.Fence.ORDINARY_CONTROL}, frame(1),
                coverage(tokens(token)));
        assertEquals(3, handles.size(), "the bundle fits beside the ACK's precharged bytes");
        assertNull(driver.takeAck(1), "the ACK is still pending");
        driver.completeScope(scope);
        driver.sealAckCoverage(1);
        assertNotNull(driver.takeAck(1), "the ACK resolves after its covering scope completes");
        Drain drain = drainAndRelease(driver);
        assertTrue(drain.writes.size() >= 4, "the bundle and the ACK join were written");
        assertEquals(rig.slice, rig.pool.reserved(rig.owner));
        driver.close();
    }

    /** If the rollback's own disconnect fails, the original constructor failure is still the one reported. */
    @Test
    void driverConstructorCleanupFailureDoesNotMaskTheOriginal() throws Exception {
        SessionResourcesTest.Scripted scripted = new SessionResourcesTest.Scripted();
        scripted.reading(0, 0, true);
        scripted.reading(0, 5, true);
        scripted.reading(5_000_000_000L, 50_000_000_000L, true);
        PressureRanking ranking = new PressureRanking(scripted);
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 4096, ranking.view());
        Object owner = new Object();
        InetAddress ip = InetAddress.getLoopbackAddress();
        assertTrue(pool.takeGrant(pool.reserveTicket(owner, ip, null, 4096)));
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 100, 1L << 40, 4, 4, 4);
        StallClock.UnavailableException failure = assertThrows(StallClock.UnavailableException.class, () ->
                new SessionSendDriver(SessionContext.of(2), profile, pool, ranking, owner, ip, null, () -> 0,
                        64, 64, 32, false, SessionResources.Accounting.CUMULATIVE));
        assertEquals(1, failure.getSuppressed().length, "the cleanup failure is attached, not substituted");
        assertTrue(failure.getSuppressed()[0] instanceof StallClock.UnavailableException);
        assertEquals(4096, pool.reserved(owner),
                "the pre-existing reservation is untouched even when the cleanup itself fails");
        // The malformed cleanup released nothing: a coherent reading still unregisters the connection.
        scripted.reading(10_000_000_000L, 5_000_000_000L, true);
        ranking.disconnect(owner);
        assertEquals(4, scripted.taken(), "the connection was still registered after the failed cleanup");
    }

    // --- 13.20 queue footprint, producer pause and release deadline. All time is the rig's fake clock. ---

    /** The content of a record whose frame is about one MiB. */
    private static final int ONE_MIB_CONTENT = 1 << 20;
    /** The default record and byte caps (13.20). */
    private static final long DEFAULT_QUEUE_BYTES = 16L << 20;
    private static final long RELEASE_DEADLINE = 10_000_000_000L;

    /** Polls and settles every slice of the next record, in order (13.20). */
    private static void releaseNextRecord(SessionSendDriver driver) {
        SessionSendDriver.Write write;
        do {
            write = driver.poll(stream -> true);
            assertNotNull(write, "a queued record must be selectable");
            driver.released(write.token());
        } while (!write.last());
    }

    @Test
    void thousandsOfSmallRecordsAreAcceptedAndTheAccountingDrainsToZero() throws Exception {
        Rig rig = new Rig(2, SessionResources.OUTBOUND_CONNECTION_PACKETS, 1L << 40, DEFAULT_QUEUE_BYTES);
        SessionSendDriver driver = rig.driver;
        for (int i = 0; i < 1500; i++) {
            assertTrue(driver.submit(frame(5), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned(), "record " + i);
        }
        assertFalse(driver.isClosed(), "1500 tiny records are under both caps");
        assertEquals(1500, driver.queuedRecords());
        assertFalse(driver.underPressure());
        drainAndRelease(driver);
        assertEquals(0, driver.queuedRecords());
        assertEquals(0, driver.queuedFootprint(), "every footprint is released once the transport releases it");
        assertFalse(driver.isClosed());
    }

    @Test
    void theDefaultRecordCapRefusesRecordSixteenThousandAndFiveWithQueuePacketLimit() throws Exception {
        assertEquals(16384, SessionResources.OUTBOUND_CONNECTION_PACKETS);
        // The byte limit is lifted here so that the record count, the check under test, is the one that binds.
        Rig rig = new Rig(2, SessionResources.OUTBOUND_CONNECTION_PACKETS, 1L << 40, 1L << 40);
        SessionSendDriver driver = rig.driver;
        for (int i = 0; i < SessionResources.OUTBOUND_CONNECTION_PACKETS; i++) {
            assertTrue(driver.submit(frame(5), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned(), "record " + i);
        }
        assertFalse(driver.submit(frame(5), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertTrue(driver.lastCloseReason().startsWith(
                "check=queue-packet-limit stream=-1 queuedRecords=16384 "), driver.lastCloseReason());
    }

    @Test
    void theByteCapCountsTwiceTheEncodedBytesPlusBookkeeping() throws Exception {
        // Eight records of 1,035,468 bytes encode to 7.9 MiB: a 15.8 MiB footprint, under the 16 MiB limit.
        Rig fits = new Rig(1, 1000, 1L << 40, DEFAULT_QUEUE_BYTES);
        for (int i = 0; i < 8; i++) {
            assertTrue(fits.driver.submit(frame(1_035_468), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        }
        assertFalse(fits.driver.isClosed(), "7.9 MiB encoded is 15.8 MiB of footprint");
        // Nine one-MiB records encode to 9 MiB: twice that is past 16 MiB, so the byte limit refuses the set.
        Rig over = new Rig(1, 1000, 1L << 40, DEFAULT_QUEUE_BYTES);
        for (int i = 0; i < 9 && !over.driver.isClosed(); i++) {
            over.driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        }
        assertTrue(over.driver.isClosed(), "a 9 MiB encoded set is past the 16 MiB footprint limit");
        assertTrue(over.driver.lastCloseReason().startsWith("check=queue-byte-limit "), over.driver.lastCloseReason());
    }

    @Test
    void pressureStartsAtEightMibAndClearsOnlyAtFourMib() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, DEFAULT_QUEUE_BYTES);
        SessionSendDriver driver = rig.driver;
        for (int i = 0; i < 3; i++) {
            driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        }
        assertFalse(driver.underPressure(), "six MiB of footprint is below the eight MiB line");
        driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(driver.underPressure(), "eight MiB of footprint starts the pressure");
        releaseNextRecord(driver);
        assertTrue(driver.underPressure(), "pressure does not clear at six MiB");
        releaseNextRecord(driver);
        assertTrue(driver.underPressure(), "pressure does not clear just above four MiB");
        releaseNextRecord(driver);
        assertFalse(driver.underPressure(), "pressure clears at four MiB");
        assertTrue(driver.queuedFootprint() <= (4L << 20));
    }

    @Test
    void aPressuredConnectionWithNoReleaseClosesAtTheDeadline() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, DEFAULT_QUEUE_BYTES);
        SessionSendDriver driver = rig.driver;
        for (int i = 0; i < 4; i++) {
            driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        }
        assertTrue(driver.underPressure());
        rig.now.addAndGet(9_000_000_000L);
        releaseNextRecord(driver);
        rig.now.addAndGet(RELEASE_DEADLINE - 100_000_000L);
        assertFalse(driver.checkReleaseDeadline(), "a release at nine seconds renews the deadline");
        assertFalse(driver.isClosed());
        rig.now.addAndGet(200_000_000L);
        assertTrue(driver.checkReleaseDeadline(), "ten seconds after the last release closes the connection");
        assertTrue(driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertTrue(driver.lastCloseReason().startsWith("check=release-deadline stream=-1 "), driver.lastCloseReason());
        assertEquals(0, driver.queuedFootprint(), "a close releases the footprint");
    }

    @Test
    void selectionWithoutReleaseDoesNotRenewTheDeadline() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, DEFAULT_QUEUE_BYTES);
        SessionSendDriver driver = rig.driver;
        for (int i = 0; i < 4; i++) {
            driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        }
        rig.now.addAndGet(9_000_000_000L);
        assertNotNull(driver.poll(stream -> true), "a slice is selected but never released");
        rig.now.addAndGet(RELEASE_DEADLINE);
        assertTrue(driver.checkReleaseDeadline(), "selection alone never counts as release");
    }

    @Test
    void noPressureNeverClosesOnTheReleaseDeadline() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, DEFAULT_QUEUE_BYTES);
        SessionSendDriver driver = rig.driver;
        driver.submit(frame(5), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        rig.now.addAndGet(10L * 60 * 1_000_000_000L);
        assertFalse(driver.checkReleaseDeadline());
        assertFalse(driver.isClosed());
    }

    @Test
    void aBundleThatWouldExceedTheByteCapIsRefusedWhole() throws Exception {
        Rig refused = new Rig(1, 1000, 1L << 40, 16L << 10);
        List<OutboundPacket> big = new ArrayList<OutboundPacket>();
        for (int i = 0; i < 3; i++) {
            big.add(frame(2000));
        }
        StreamSequencer.Fence[] scopes = {StreamSequencer.Fence.ORDINARY_CONTROL,
            StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.ORDINARY_CONTROL};
        refused.driver.submitBundle(frame(5), big, scopes, frame(5));
        assertTrue(refused.driver.isClosed());
        assertTrue(refused.driver.lastCloseReason().startsWith("check=queue-byte-limit "), refused.driver.lastCloseReason());
        assertEquals(0, refused.driver.queuedRecords(), "no member of the refused bundle was queued");
        assertEquals(0, refused.driver.queuedFootprint());

        Rig fits = new Rig(1, 1000, 1L << 40, 16L << 10);
        List<OutboundPacket> small = new ArrayList<OutboundPacket>();
        for (int i = 0; i < 3; i++) {
            small.add(frame(100));
        }
        assertEquals(5, fits.driver.submitBundle(frame(5), small, scopes, frame(5)).size());
        assertFalse(fits.driver.isClosed());
    }

    @Test
    void aDroppedPendingAckReleasesItsFootprint() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, DEFAULT_QUEUE_BYTES);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(1, frame(2).frame(), 16));
        assertEquals(1, driver.queuedRecords());
        assertTrue(driver.queuedFootprint() > 0);
        rig.now.addAndGet(AckJoinTracker.DEADLINE_NANOS);
        driver.expireAcks();
        assertEquals(0, driver.queuedRecords());
        assertEquals(0, driver.queuedFootprint(), "a dropped ACK gives back its footprint");
    }

    @Test
    void closingWithQueuedRecordsZeroesTheFootprint() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, DEFAULT_QUEUE_BYTES);
        for (int i = 0; i < 4; i++) {
            rig.driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        }
        assertTrue(rig.driver.queuedFootprint() > (8L << 20));
        rig.driver.close();
        assertEquals(0, rig.driver.queuedRecords());
        assertEquals(0, rig.driver.queuedFootprint());
        assertFalse(rig.driver.underPressure());
    }

    /** The encoded length of one record submitted alone to a fresh driver with no byte limit. */
    private static long encodedLengthOf(OutboundPacket packet) throws Exception {
        Rig probe = new Rig(1, 1000, 1L << 40, 1L << 40);
        assertTrue(probe.driver.submit(packet, StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        return bytesOf(drainAndRelease(probe.driver));
    }

    /** The total encoded bytes a drain wrote. */
    private static long bytesOf(Drain drain) {
        long total = 0;
        for (SessionSendDriver.Write write : drain.writes) {
            total += write.bytes().length;
        }
        return total;
    }

    /** Twenty one-MiB records under a 64 MiB byte limit: pressure begins at 32 MiB of footprint. */
    private static Rig pressuredRig() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, 64L << 20);
        for (int i = 0; i < 20; i++) {
            assertTrue(rig.driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0)
                    .isAssigned());
        }
        assertTrue(rig.driver.underPressure(), "twenty MiB records are pressured");
        return rig;
    }

    /** The charge behind a slice token that is still outstanding, read through the driver's private token map. */
    private static Object chargeOf(SessionSendDriver driver, long token) throws Exception {
        Object slice = ((Map<?, ?>) field(driver, "tokens")).get(token);
        assertNotNull(slice, "the slice is still outstanding");
        return field(slice, "charge");
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** A record whose raw frame passes the early check but whose encoding exceeds the byte limit is refused, not admitted. */
    @Test
    void aRecordWhoseEncodingOverflowsTheByteLimitIsRefusedAfterTheRawCheckPasses() throws Exception {
        OutboundPacket packet = frame(5000);
        long exact = SessionSendDriver.footprintOf(encodedLengthOf(packet));
        long raw = SessionSendDriver.footprintOf(packet.frame().length);
        assertTrue(raw < exact, "the raw frame passes the early check and its encoding does not");

        Rig refused = new Rig(1, 1000, 1L << 40, exact - 1);
        assertFalse(refused.driver.submit(packet, StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertTrue(refused.driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, refused.driver.closeCode());
        assertTrue(refused.driver.lastCloseReason().startsWith("check=queue-byte-limit "),
                refused.driver.lastCloseReason());
        assertEquals(0, refused.driver.queuedRecords());
        assertEquals(0, refused.driver.queuedFootprint());

        Rig fits = new Rig(1, 1000, 1L << 40, exact);
        assertTrue(fits.driver.submit(packet, StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertFalse(fits.driver.isClosed());
        assertEquals(exact, fits.driver.queuedFootprint(), "the footprint is exactly the encoded record's");
    }

    /** END goes through the same exact check: at one byte under its footprint it closes 0x12 and writes nothing. */
    @Test
    void endAtTheByteLimitBoundaryIsRefusedWhenItsEncodingOverflows() throws Exception {
        Rig probe = new Rig(1, 1000, 1L << 40, 1L << 40);
        assertTrue(probe.driver.submitEnd().isAssigned());
        long exact = SessionSendDriver.footprintOf(bytesOf(drainAndRelease(probe.driver)));

        Rig refused = new Rig(1, 1000, 1L << 40, exact - 1);
        assertFalse(refused.driver.submitEnd().isAssigned());
        assertTrue(refused.driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, refused.driver.closeCode());
        assertTrue(refused.driver.lastCloseReason().startsWith("check=queue-byte-limit "),
                refused.driver.lastCloseReason());
        assertEquals(0, refused.driver.queuedFootprint());

        Rig fits = new Rig(1, 1000, 1L << 40, exact);
        assertTrue(fits.driver.submitEnd().isAssigned());
        assertTrue(fits.driver.isSealed());
        assertEquals(exact, fits.driver.queuedFootprint());
    }

    /** A bundle whose encoded run overflows is refused whole: no member is queued and the raw total passed the early check. */
    @Test
    void aBundleWhoseEncodedRunOverflowsTheByteLimitIsRefusedWhole() throws Exception {
        OutboundPacket opening = frame(5);
        OutboundPacket closing = frame(5);
        List<OutboundPacket> members = packets(300, 300, 300);
        StreamSequencer.Fence[] scopes = {StreamSequencer.Fence.ORDINARY_CONTROL,
            StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.ORDINARY_CONTROL};
        Rig probe = new Rig(1, 1000, 1L << 40, 1L << 40);
        assertEquals(5, probe.driver.submitBundle(opening, members, scopes, closing).size());
        long runBytes = bytesOf(drainAndRelease(probe.driver));
        long exact = 2L * runBytes + 5L * SessionSendDriver.RECORD_BOOKKEEPING;
        long raw = opening.frame().length + closing.frame().length;
        for (OutboundPacket member : members) {
            raw += member.frame().length;
        }
        assertTrue(2L * raw + 5L * SessionSendDriver.RECORD_BOOKKEEPING < exact,
                "the raw run passes the early check and its encoding does not");

        Rig refused = new Rig(1, 1000, 1L << 40, exact - 1);
        assertTrue(refused.driver.submitBundle(opening, members, scopes, closing).isEmpty());
        assertTrue(refused.driver.isClosed());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, refused.driver.closeCode());
        assertTrue(refused.driver.lastCloseReason().startsWith("check=queue-byte-limit "),
                refused.driver.lastCloseReason());
        assertEquals(0, refused.driver.queuedRecords(), "no member of the refused bundle was queued");
        assertEquals(0, refused.driver.queuedFootprint());

        Rig fits = new Rig(1, 1000, 1L << 40, exact);
        assertEquals(5, fits.driver.submitBundle(opening, members, scopes, closing).size());
        assertFalse(fits.driver.isClosed());
        assertEquals(5, fits.driver.queuedRecords());
        assertEquals(exact, fits.driver.queuedFootprint());
    }

    /** A pending ACK's estimate is replaced by its record's exact footprint when it is sequenced, and counted once. */
    @Test
    void aPendingAckKeepsItsFootprintExactAfterItIsSequenced() throws Exception {
        Rig rig = new Rig(1, 3);
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.createAck(7, frame(2).frame(), 100));
        assertEquals(1, driver.queuedRecords());
        assertTrue(driver.queuedFootprint() > 0, "the estimate counts from creation");
        driver.sealAckCoverage(7);
        assertNotNull(driver.takeAck(7), "the ack is sequenced");
        assertEquals(1, driver.queuedRecords(), "sequencing transfers the count, it does not add one");

        SessionSendDriver.Write write = driver.poll(stream -> true);
        assertNotNull(write);
        assertTrue(write.last(), "the ack is a single slice");
        assertEquals(SessionSendDriver.footprintOf(write.bytes().length), driver.queuedFootprint(),
                "the footprint is the exact encoded record's, not the estimate");
        driver.released(write.token());
        assertEquals(0, driver.queuedRecords());
        assertEquals(0, driver.queuedFootprint());
    }

    /** Slow releases under pressure, one every nine seconds for a minute, each renew the deadline and never close. */
    @Test
    void repeatedSlowReleasesUnderPressureNeverCloseTheConnection() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, 64L << 20);
        SessionSendDriver driver = rig.driver;
        for (int i = 0; i < 20; i++) {
            driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        }
        assertTrue(driver.underPressure());
        for (int second = 9; second <= 60; second += 9) {
            rig.now.addAndGet(9_000_000_000L);
            assertFalse(driver.checkReleaseDeadline(), "nine seconds after the last release, at " + second + " s");
            releaseNextRecord(driver);
            assertFalse(driver.checkReleaseDeadline(), "a release renews the deadline, at " + second + " s");
            assertFalse(driver.isClosed());
            assertTrue(driver.underPressure(), "the footprint stays above the clear line");
        }
        assertEquals(20 * footprintOfMiB() - 6 * footprintOfMiB(), driver.queuedFootprint(), "six records released");
        rig.now.addAndGet(10_000_000_000L);
        assertTrue(driver.checkReleaseDeadline(), "ten seconds with no release closes the connection");
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
    }

    /** One MiB of content with its header, doubled, plus the allowance: the footprint of one ONE_MIB_CONTENT record. */
    private static long footprintOfMiB() throws Exception {
        return SessionSendDriver.footprintOf(encodedLengthOf(frame(ONE_MIB_CONTENT)));
    }

    /** A failed slice is not release progress: it neither frees a record still being written nor renews the deadline. */
    @Test
    void aFailedWriteUnderPressureNeverRenewsTheDeadline() throws Exception {
        Rig rig = pressuredRig();
        SessionSendDriver driver = rig.driver;
        long before = driver.queuedFootprint();
        rig.now.addAndGet(9_000_000_000L);
        SessionSendDriver.Write write = driver.poll(stream -> true);
        assertNotNull(write);
        assertFalse(write.last(), "a one-MiB record spans several 64 KiB slices");
        driver.failed(write.token());
        assertEquals(before, driver.queuedFootprint(), "the record is still charged until its last slice settles");
        rig.now.addAndGet(1_000_000_000L);
        assertTrue(driver.checkReleaseDeadline(), "the failed slice did not renew the deadline");
        assertEquals(0, driver.queuedFootprint());
    }

    /** Once sealed, a pressured driver with no release still closes at the deadline, and refuses every later submission. */
    @Test
    void theReleaseDeadlineClosesASealedPressuredDriver() throws Exception {
        Rig rig = pressuredRig();
        SessionSendDriver driver = rig.driver;
        driver.seal();
        long before = driver.queuedFootprint();
        assertFalse(driver.submit(frame(5), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0).isAssigned());
        assertEquals(before, driver.queuedFootprint());
        rig.now.addAndGet(RELEASE_DEADLINE);
        assertTrue(driver.checkReleaseDeadline());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertEquals(0, driver.queuedFootprint());
    }

    /** A pressured driver whose record waits for a grant that never comes closes at the deadline. */
    @Test
    void theReleaseDeadlineClosesAPressuredDriverWhoseRecordAwaitsAGrant() throws Exception {
        Rig rig = new Rig(1, 1000, 8L << 20, 64L << 20);
        SessionSendDriver driver = rig.driver;
        for (int i = 0; i < 17; i++) {
            assertTrue(driver.submit(frame(ONE_MIB_CONTENT), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0)
                    .isAssigned());
        }
        assertTrue(driver.underPressure());
        assertTrue(driver.awaitingGrant(), "the owner's cap holds only some of the records");
        rig.now.addAndGet(RELEASE_DEADLINE);
        assertTrue(driver.checkReleaseDeadline());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertEquals(0, driver.queuedFootprint());
    }

    /** A pressured driver with END submitted and no release closes at the deadline; END is never accepted after it. */
    @Test
    void theReleaseDeadlineClosesAPressuredDriverAfterEndWasSubmitted() throws Exception {
        Rig rig = pressuredRig();
        SessionSendDriver driver = rig.driver;
        assertTrue(driver.submitEnd().isAssigned());
        assertTrue(driver.isSealed());
        rig.now.addAndGet(RELEASE_DEADLINE);
        assertTrue(driver.checkReleaseDeadline());
        assertEquals(SessionSendDriver.CLOSE_RESOURCE, driver.closeCode());
        assertEquals(0, driver.queuedFootprint());
    }

    /**
     * Out-of-order and partial settlement of one bundle, with a duplicate settle callback, keeps the footprint exact.
     * The settled head holds no payload once its footprint leaves the count, while later members still reach it.
     */
    @Test
    void outOfOrderPartialSettlementOfABundleKeepsTheFootprintExact() throws Exception {
        Rig rig = new Rig(1, 1000, 1L << 40, 1L << 40);
        SessionSendDriver driver = rig.driver;
        StreamSequencer.Fence[] scopes = {StreamSequencer.Fence.ORDINARY_CONTROL,
            StreamSequencer.Fence.ORDINARY_CONTROL, StreamSequencer.Fence.ORDINARY_CONTROL};
        assertEquals(5, driver.submitBundle(frame(5), packets(40, 50, 60), scopes, frame(5)).size());
        long total = driver.queuedFootprint();
        List<SessionSendDriver.Write> writes = new ArrayList<SessionSendDriver.Write>();
        SessionSendDriver.Write next;
        while ((next = driver.poll(stream -> true)) != null) {
            writes.add(next);
        }
        assertEquals(5, writes.size(), "five single-slice records");
        long[] footprint = new long[5];
        long sum = 0;
        for (int i = 0; i < 5; i++) {
            footprint[i] = SessionSendDriver.footprintOf(writes.get(i).bytes().length);
            sum += footprint[i];
        }
        assertEquals(total, sum, "the run's footprint is the sum of its members'");
        Object head = chargeOf(driver, writes.get(0).token());
        assertSame(head, field(chargeOf(driver, writes.get(1).token()), "head"), "members reach the head");

        driver.released(writes.get(4).token());
        assertEquals(total - footprint[4], driver.queuedFootprint());
        driver.released(writes.get(4).token());
        driver.failed(writes.get(4).token());
        assertEquals(total - footprint[4], driver.queuedFootprint(), "a duplicate settle changes nothing");

        driver.released(writes.get(0).token());
        assertEquals(total - footprint[4] - footprint[0], driver.queuedFootprint());
        assertNull(field(head, "encoded"), "the settled head drops its payload");
        assertNull(field(head, "record"), "the settled head drops its record");

        driver.released(writes.get(2).token());
        assertEquals(footprint[1] + footprint[3], driver.queuedFootprint());
        driver.released(writes.get(1).token());
        driver.released(writes.get(3).token());
        assertEquals(0, driver.queuedFootprint());
        assertEquals(0, driver.queuedRecords());
        assertEquals(SessionSendDriver.DEFAULT_REALTIME_SLICE_BYTES, rig.pool.reserved(rig.owner),
                "the run's single pool charge is released once; only the REALTIME slice stays charged");
    }
}
