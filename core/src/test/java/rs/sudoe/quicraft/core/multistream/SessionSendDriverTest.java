// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;

class SessionSendDriverTest {
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
            boolean large = connectionCap >= ReceiveSlots.BYTES_PER_STREAM + AckJoinTracker.MAX_BYTES;
            slice = large ? SessionSendDriver.DEFAULT_REALTIME_SLICE_BYTES : TEST_SLICE;
            NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
            pool = new OutboundPool(node, 1L << 40, 1L << 40, large ? connectionCap : connectionCap + TEST_SLICE,
                    ranking.view(now::get));
            ServerProfile profile = new ServerProfile(1, 1, 1024, .5, queuePackets, 1L << 40, 4, 4, 4);
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
                byte[] rest = Arrays.copyOfRange(control, at, control.length);
                last = RecordCodec.decodeRecord(rest, LogicalStream.CONTROL);
                at += firstRecordLength(rest);
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
}
