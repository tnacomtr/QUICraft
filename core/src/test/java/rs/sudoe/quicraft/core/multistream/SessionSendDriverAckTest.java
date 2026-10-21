// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;
import rs.sudoe.quicraft.core.routing.Lane;

/** The send driver's ACK-stream placement and the first-install exemption of the ack dependency (13.21). */
class SessionSendDriverAckTest {
    private static final int N = 3;

    private static final class Rig {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final PressureRanking ranking = new PressureRanking();
        final SessionSendDriver driver;
        final Map<Long, Record> records = new HashMap<Long, Record>();

        Rig() throws Exception {
            NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
            OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, ranking.view(now::get));
            ServerProfile profile = new ServerProfile(1, 1, 1024, .5, 100_000, 1L << 40, 4, 4, 4);
            driver = new SessionSendDriver(SessionContext.of(N), profile, pool, ranking, new Object(),
                    InetAddress.getLoopbackAddress(), null, now::get);
        }

        SentPacket send(Fence fence, int x, int z) {
            SentPacket packet = driver.submit(frame(), fence, x, z);
            assertTrue(packet.isAssigned(), "assigned: " + fence);
            return packet;
        }

        /** Polls everything out and indexes the decoded records by (stream, sequence). */
        void drain() throws Exception {
            SessionSendDriver.Write write;
            while ((write = driver.poll(stream -> true)) != null) {
                Record record = RecordCodec.decodeRecord(write.bytes(), write.stream());
                records.put(key(record.streamIndex(), record.sequence()), record);
                driver.released(write.token());
            }
        }

        Record record(SentPacket packet) {
            Record record = records.get(key(packet.logicalStream(), packet.sequence()));
            assertTrue(record != null, "record polled for " + packet.logicalStream() + "/" + packet.sequence());
            return record;
        }

        boolean dependsOnAck(SentPacket packet) {
            for (Record.Dependency dependency : record(packet).dependencies()) {
                if (dependency.streamIndex() == N + 2) {
                    return true;
                }
            }
            return false;
        }

        private static long key(int stream, long sequence) {
            return ((long) stream << 40) | sequence;
        }
    }

    private static OutboundPacket frame() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.write(out, 2);
        out.write(7);
        out.write(7);
        return new OutboundPacket(out.toByteArray());
    }

    @Test
    void anAckRecordGoesToTheAckStreamAtIndexNPlusTwoOnTheControlLane() throws Exception {
        Rig rig = new Rig();
        SentPacket ack = rig.send(Fence.ACK_TRAILING, 0, 0);
        assertEquals(N + 2, ack.logicalStream());
        assertEquals(Lane.CONTROL, ack.lane());
        rig.drain();
        assertEquals(N + 2, rig.record(ack).streamIndex());
    }

    @Test
    void aFirstInstallSkipsTheAckAReplacementAndAForgetCarryIt() throws Exception {
        Rig rig = new Rig();
        rig.send(Fence.ACK_TRAILING, 0, 0);
        SentPacket first = rig.send(Fence.CHUNK_INSTALL, 10, 10);
        SentPacket replacement = rig.send(Fence.CHUNK_INSTALL, 10, 10);
        SentPacket forget = rig.send(Fence.CHUNK_FORGET, 10, 10);
        SentPacket afterForget = rig.send(Fence.CHUNK_INSTALL, 10, 10);
        SentPacket other = rig.send(Fence.CHUNK_INSTALL, 11, 10);
        SentPacket entity = rig.send(Fence.CHUNK_ACK, 11, 10);
        SentPacket plain = rig.send(Fence.CHUNK, 11, 10);
        rig.drain();
        assertFalse(rig.dependsOnAck(first), "first install of a chunk the client does not hold");
        assertTrue(rig.dependsOnAck(replacement), "a replacement of a held chunk");
        assertTrue(rig.dependsOnAck(forget), "a forget always waits");
        assertTrue(rig.dependsOnAck(afterForget), "an install after a forget still in the ring");
        assertFalse(rig.dependsOnAck(other), "another chunk is a first install again");
        assertTrue(rig.dependsOnAck(entity), "block entity data always waits");
        assertFalse(rig.dependsOnAck(plain), "a plain CHUNK record never waits for the ACK");
    }

    @Test
    void aBatchInstallCarriesTheAckAndStopsFirstInstallTracking() throws Exception {
        Rig rig = new Rig();
        rig.send(Fence.ACK_TRAILING, 0, 0);
        rig.driver.beginBatch(1, frame());
        SentPacket batched = rig.driver.batchInstall(1, frame(), 20, 20);
        assertTrue(batched.isAssigned());
        rig.driver.finishBatch(1, frame());
        SentPacket replacement = rig.send(Fence.CHUNK_INSTALL, 20, 20);
        SentPacket fresh = rig.send(Fence.CHUNK_INSTALL, 30, 30);
        rig.drain();
        assertTrue(rig.dependsOnAck(batched), "a batch install is never an exempted first install");
        assertTrue(rig.dependsOnAck(replacement), "the held set did not see the batch install");
        assertTrue(rig.dependsOnAck(fresh), "tracking stopped after the untracked batch install");
    }

    @Test
    void aForgetRingOverflowMakesEveryLaterInstallCarryTheAck() throws Exception {
        Rig rig = new Rig();
        rig.send(Fence.ACK_TRAILING, 0, 0);
        rig.send(Fence.CHUNK_FORGET, 0, 0);
        for (int i = 1; i <= HeldChunkSet.RING_CAPACITY; i++) {
            rig.send(Fence.CHUNK_FORGET, i, 0);
        }
        SentPacket aged = rig.send(Fence.CHUNK_INSTALL, 0, 0);
        SentPacket recent = rig.send(Fence.CHUNK_INSTALL, HeldChunkSet.RING_CAPACITY, 0);
        rig.drain();
        assertTrue(rig.dependsOnAck(aged), "the overflow must not let the oldest forget pass as a first install");
        assertTrue(rig.dependsOnAck(recent));
    }

    @Test
    void heldSetOverflowMakesEveryInstallCarryTheAck() throws Exception {
        Rig rig = new Rig();
        for (int i = 0; i < HeldChunkSet.HELD_CAPACITY; i++) {
            rig.driver.submit(frame(), Fence.CHUNK_INSTALL, i, 0);
        }
        rig.send(Fence.ACK_TRAILING, 0, 0);
        SentPacket overflow = rig.send(Fence.CHUNK_INSTALL, -1, -1);
        SentPacket fresh = rig.send(Fence.CHUNK_INSTALL, -2, -2);
        rig.drain();
        assertTrue(rig.dependsOnAck(overflow));
        assertTrue(rig.dependsOnAck(fresh), "fail safe: no first install is trusted any more");
    }

    @Test
    void anEpochChangeStopsTrackingBecauseASameDimensionRespawnKeepsPredictions() throws Exception {
        Rig rig = new Rig();
        rig.send(Fence.CHUNK_INSTALL, 4, 4);
        rig.send(Fence.CHUNK_FORGET, 5, 5);
        assertTrue(rig.driver.submitEpochTransition(frame()).isAssigned());
        rig.send(Fence.ACK_TRAILING, 0, 0);
        SentPacket held = rig.send(Fence.CHUNK_INSTALL, 4, 4);
        SentPacket forgotten = rig.send(Fence.CHUNK_INSTALL, 5, 5);
        SentPacket fresh = rig.send(Fence.CHUNK_INSTALL, 900, 900);
        rig.drain();
        // Astra 2026-10-21: respawn in the same dimension keeps ClientLevel and its prediction map.
        assertTrue(rig.dependsOnAck(held), "a chunk installed before the epoch change");
        assertTrue(rig.dependsOnAck(forgotten), "a chunk forgotten before the epoch change");
        assertTrue(rig.dependsOnAck(fresh), "no first install is trusted after an epoch change");
    }

    @Test
    void aChunkLifecycleRecordInsideABundleMakesLaterInstallsCarryTheAck() throws Exception {
        Rig rig = new Rig();
        rig.send(Fence.ACK_TRAILING, 0, 0);
        SentPacket before = rig.send(Fence.CHUNK_INSTALL, 1, 1);
        rig.driver.submitBundle(frame(), Arrays.asList(frame()),
                new Fence[] {Fence.CHUNK_INSTALL}, frame());
        SentPacket after = rig.send(Fence.CHUNK_INSTALL, 2, 2);
        rig.drain();
        assertFalse(rig.dependsOnAck(before));
        assertTrue(rig.dependsOnAck(after), "an untracked install may have put a chunk on the client");
    }

    @Test
    void aBundleHoldingAnAckStaysOnControlAsOneGlobalFence() throws Exception {
        Rig rig = new Rig();
        SentPacket chunk = rig.send(Fence.CHUNK, 1, 1);
        java.util.List<SentPacket> bundle = rig.driver.submitBundle(frame(), Arrays.asList(frame(), frame()),
                new Fence[] {Fence.ORDINARY_CONTROL, Fence.ACK_TRAILING}, frame());
        assertEquals(4, bundle.size());
        for (SentPacket packet : bundle) {
            assertEquals(LogicalStream.CONTROL, packet.logicalStream(), "never split across lanes");
        }
        rig.drain();
        boolean waitsForChunk = false;
        for (Record.Dependency dependency : rig.record(bundle.get(0)).dependencies()) {
            if (dependency.streamIndex() == chunk.logicalStream() && dependency.sequence() == chunk.sequence()) {
                waitsForChunk = true;
            }
        }
        assertTrue(waitsForChunk, "a bundle with an ACK is a global fence");
    }
}
