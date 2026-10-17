// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** A rig with generous pools, a manual clock and the given profile limits. */
    private static final class Rig {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final PressureRanking ranking = new PressureRanking();
        final OutboundPool pool;
        final Object owner = new Object();
        final SessionSendDriver driver;

        Rig(int shards, int queuePackets) throws Exception {
            this(shards, queuePackets, 1L << 40);
        }

        /** A rig whose per-connection outbound cap is {@code connectionCap} bytes. */
        Rig(int shards, int queuePackets, long connectionCap) throws Exception {
            NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
            pool = new OutboundPool(node, 1L << 40, 1L << 40, connectionCap, ranking.view(now::get));
            ServerProfile profile = new ServerProfile(1, 1, 1024, .5, queuePackets, 1L << 40, 4, 4, 4);
            driver = new SessionSendDriver(SessionContext.of(shards), profile, pool, ranking, owner,
                    InetAddress.getLoopbackAddress(), null, now::get);
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
        assertEquals(0, rig.pool.reserved(rig.owner), "the last slice's release frees the whole record");
        driver.released(second.token());
        driver.failed(second.token());
        assertEquals(0, rig.pool.reserved(rig.owner), "a repeated completion releases nothing again");
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
                1L << 40, 1L << 40, 100, ranking.view(now::get));
        Object owner = new Object();
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(1),
                new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4), pool, ranking, owner,
                InetAddress.getLoopbackAddress(), null, now::get);
        SentPacket first = driver.submit(frame(40), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        SentPacket second = driver.submit(frame(80), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(first.isAssigned() && second.isAssigned());
        assertTrue(pool.reserved(owner) <= 100, "only the first reservation is granted");

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
     * Charges 46, 86 and 16 under a 100-byte owner cap. The 86 waits, so it is not the aggregate that
     * decides grants: the 16 on another stream is granted and written, and the 16 behind the 86 waits
     * for it on its own stream. After the releases nothing stalls.
     */
    @Test
    void ownerCapSkipDoesNotStallOtherStreams() throws Exception {
        Rig rig = new Rig(1, 1000, 100);
        SessionSendDriver driver = rig.driver;
        SentPacket realtime = driver.submit(frame(40), StreamSequencer.Fence.REALTIME, 0, 0);
        SentPacket waiting = driver.submit(frame(80), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket behind = driver.submit(frame(10), StreamSequencer.Fence.CHUNK, 0, 0);
        SentPacket control = driver.submit(frame(10), StreamSequencer.Fence.ORDINARY_CONTROL, 0, 0);
        assertTrue(realtime.isAssigned() && waiting.isAssigned() && behind.isAssigned() && control.isAssigned());

        SessionSendDriver.Write first = driver.poll(stream -> true);
        SessionSendDriver.Write second = driver.poll(stream -> true);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(new java.util.HashSet<Integer>(Arrays.asList(0, 1)),
                new java.util.HashSet<Integer>(Arrays.asList(first.stream(), second.stream())),
                "the granted CONTROL and REALTIME records are written");
        assertNull(driver.poll(stream -> true), "the chunk stream waits for the 86-byte grant");
        driver.released(first.token());
        driver.released(second.token());

        Drain rest = drainAndRelease(driver);
        assertEquals(2, rest.writes.size(), "the 86-byte head and the record behind it are written");
        assertEquals(waiting.sequence(), RecordCodec.decodeRecord(rest.writes.get(0).bytes(), 2).sequence());
        assertEquals(behind.sequence(), RecordCodec.decodeRecord(rest.writes.get(1).bytes(), 2).sequence());
        assertEquals(0, rig.pool.reserved(rig.owner));
        assertFalse(driver.isClosed());
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
        OutboundPool pool = new OutboundPool(new NodePools(1L << 40, 0, 1L << 40, 10, false, 0),
                100, 1L << 40, 1L << 40, ranking.view(now::get));
        InetAddress ip = InetAddress.getLoopbackAddress();
        ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 1000, 1L << 40, 4, 4, 4);
        Object ownerA = new Object();
        Object ownerB = new Object();
        SessionSendDriver a = new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, ownerA,
                ip, null, now::get);
        SessionSendDriver b = new SessionSendDriver(SessionContext.of(1), profile, pool, ranking, ownerB,
                ip, null, now::get);
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
            assertEquals(i == 0 ? 0 : charged, rig.pool.reserved(rig.owner),
                    "the charge is released only when the final outstanding slice completes");
        }
        driver.failed(slice.token());
        assertEquals(0, rig.pool.reserved(rig.owner), "a repeated completion releases nothing again");
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
