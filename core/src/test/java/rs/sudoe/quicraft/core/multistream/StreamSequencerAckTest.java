// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence;

/** The sequencer rules of the ACK stream: trailing records and the ack dependency (13.21). */
class StreamSequencerAckTest {
    private static final byte[] NONE = new byte[0];
    /** N=3: CONTROL 0, REALTIME 1, shards 2..4, ACK 5. */
    private static final int N = 3;

    private static StreamSequencer sequencer() {
        return new StreamSequencer(N);
    }

    private static Map<Integer, Long> deps(Record record) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (Record.Dependency d : record.dependencies()) {
            result.put(d.streamIndex(), d.sequence());
        }
        return result;
    }

    private static Map<Integer, Long> pairs(long... flat) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (int i = 0; i < flat.length; i += 2) {
            result.put((int) flat[i], flat[i + 1]);
        }
        return result;
    }

    @Test
    void theAckStreamIsLogicalIndexNPlusTwoAfterTheShards() {
        assertEquals(N + 2, sequencer().ackIndex());
    }

    @Test
    void aTrailingRecordCarriesEveryStreamsMaxAndAdvancesNoFrontier() {
        StreamSequencer s = sequencer();
        Record control = s.assign(0, Fence.GLOBAL, NONE, false);
        Record realtime = s.assign(1, Fence.REALTIME, NONE, false);
        Record chunkA = s.assign(2, Fence.CHUNK, NONE, false);
        Record chunkB = s.assign(4, Fence.CHUNK, NONE, false);
        long global = s.globalFrontier();
        long view = s.viewFrontier();
        long fence = s.controlFence();

        Record ack = s.assign(s.ackIndex(), Fence.ACK_TRAILING, NONE, false);
        assertEquals(pairs(0, control.sequence(), 1, realtime.sequence(), 2, chunkA.sequence(),
                4, chunkB.sequence()), deps(ack), "every stream's latest record, the same footprint as GLOBAL");
        assertEquals(global, s.globalFrontier(), "G does not move");
        assertEquals(view, s.viewFrontier(), "V does not move");
        assertEquals(fence, s.controlFence(), "C does not move");
        assertEquals(ack.sequence(), s.lastAck());
    }

    @Test
    void aSecondTrailingRecordAlsoDependsOnTheFirstThroughItsOwnStreamOrder() {
        StreamSequencer s = sequencer();
        Record first = s.assign(s.ackIndex(), Fence.ACK_TRAILING, NONE, false);
        Record second = s.assign(s.ackIndex(), Fence.ACK_TRAILING, NONE, false);
        assertEquals(first.sequence() + 1, second.sequence());
        assertEquals(second.sequence(), s.lastAck());
    }

    /**
     * Security review 2026-10-21 (r3): nothing assigned after an ACK may run before it, or a later mount, equipment,
     * attribute or block change could alter the ACK's rollback and collision snap. Only plain CHUNK, the exempted
     * first install, skips it.
     */
    @Test
    void everyLaterRecordExceptAPlainChunkWaitsForTheLastAck() {
        StreamSequencer s = sequencer();
        s.assign(2, Fence.CHUNK, NONE, false);
        Record ack = s.assign(s.ackIndex(), Fence.ACK_TRAILING, NONE, false);
        int ackStream = s.ackIndex();
        Long expected = Long.valueOf(ack.sequence());
        for (Fence fence : new Fence[] {Fence.ORDINARY_CONTROL, Fence.CONTROL_FENCE}) {
            assertEquals(expected, deps(s.assign(0, fence, NONE, false)).get(ackStream), fence + " waits for the ACK");
        }
        assertEquals(expected, deps(s.assign(1, Fence.REALTIME, NONE, false)).get(ackStream), "REALTIME waits for the ACK");
        assertEquals(expected, deps(s.assign(3, Fence.CHUNK_ACK, NONE, false)).get(ackStream), "CHUNK_ACK waits for the ACK");
        assertTrue(!deps(s.assign(2, Fence.CHUNK, NONE, false)).containsKey(ackStream), "plain CHUNK waits for no ACK");
        assertEquals(ack.sequence(), s.lastAck());
    }

    @Test
    void beforeAnyAckNoRecordCarriesAnAckDependency() {
        StreamSequencer s = sequencer();
        int ackStream = s.ackIndex();
        for (Fence fence : new Fence[] {Fence.ORDINARY_CONTROL, Fence.CONTROL_FENCE}) {
            assertTrue(!deps(s.assign(0, fence, NONE, false)).containsKey(ackStream), fence);
        }
        assertTrue(!deps(s.assign(1, Fence.REALTIME, NONE, false)).containsKey(ackStream));
        assertTrue(!deps(s.assign(2, Fence.CHUNK_ACK, NONE, false)).containsKey(ackStream));
    }

    @Test
    void globalAndViewRecordsDependOnTheAckStream() {
        for (Fence fence : new Fence[] {Fence.GLOBAL, Fence.VIEW}) {
            StreamSequencer s = sequencer();
            Record ack = s.assign(s.ackIndex(), Fence.ACK_TRAILING, NONE, false);
            Record record = s.assign(0, fence, NONE, false);
            assertEquals(Long.valueOf(ack.sequence()), deps(record).get(s.ackIndex()), fence + " includes the ACK stream");
        }
    }

    @Test
    void anAckChunkRecordCarriesTheLastAckAndAPlainOneDoesNot() {
        StreamSequencer s = sequencer();
        Record before = s.assign(2, Fence.CHUNK_ACK, NONE, false);
        assertTrue(!deps(before).containsKey(s.ackIndex()), "no ACK yet, nothing to wait for");
        s.assign(s.ackIndex(), Fence.ACK_TRAILING, NONE, false);
        Record second = s.assign(s.ackIndex(), Fence.ACK_TRAILING, NONE, false);
        Record withAck = s.assign(3, Fence.CHUNK_ACK, NONE, false);
        assertEquals(Long.valueOf(second.sequence()), deps(withAck).get(s.ackIndex()));
        Record plain = s.assign(3, Fence.CHUNK, NONE, false);
        assertTrue(!deps(plain).containsKey(s.ackIndex()));
    }

    @Test
    void anAckChunkRecordStillWaitsForTheViewFrontier() {
        StreamSequencer s = sequencer();
        Record view = s.assign(0, Fence.VIEW, NONE, false);
        Record record = s.assign(2, Fence.CHUNK_ACK, NONE, false);
        assertEquals(Long.valueOf(view.sequence()), deps(record).get(0));
    }

    @Test
    void theLifecycleFencesAreDriverLevelAndTheSequencerRefusesThem() {
        StreamSequencer s = sequencer();
        assertThrows(IllegalArgumentException.class, () -> s.assign(2, Fence.CHUNK_INSTALL, NONE, false));
        assertThrows(IllegalArgumentException.class, () -> s.assign(2, Fence.CHUNK_FORGET, NONE, false));
    }

    @Test
    void anAckIndexIsNeverAChunkIndexAndTheOtherWayRound() {
        StreamSequencer s = sequencer();
        int ack = s.ackIndex();
        assertThrows(IllegalArgumentException.class, () -> s.assign(ack, Fence.CHUNK, NONE, false));
        assertThrows(IllegalArgumentException.class, () -> s.assign(ack, Fence.CHUNK_ACK, NONE, false));
        assertThrows(IllegalArgumentException.class, () -> s.assign(2, Fence.ACK_TRAILING, NONE, false));
        assertThrows(IllegalArgumentException.class, () -> s.assign(0, Fence.ACK_TRAILING, NONE, false));
        assertThrows(IllegalArgumentException.class, () -> s.assign(ack, Fence.GLOBAL, NONE, false));
    }

    @Test
    void theAckStreamIsClientboundOnly() {
        StreamSequencer serverbound = new StreamSequencer(N, true);
        assertThrows(IllegalArgumentException.class,
                () -> serverbound.assign(serverbound.ackIndex(), Fence.ACK_TRAILING, NONE, false));
    }

    @Test
    void aBundleHoldingAnAckIsPromotedWholeToAGlobalFenceOnControl() {
        assertEquals(Fence.GLOBAL, StreamSequencer.bundleScope(Fence.ORDINARY_CONTROL, Fence.ACK_TRAILING));
        assertEquals(Fence.GLOBAL, StreamSequencer.bundleScope(Fence.CHUNK_ACK));
        assertEquals(Fence.GLOBAL, StreamSequencer.bundleScope(Fence.CHUNK_INSTALL, Fence.ORDINARY_CONTROL));

        StreamSequencer s = sequencer();
        Record chunk = s.assign(2, Fence.CHUNK, NONE, false);
        List<byte[]> members = Arrays.asList(NONE, NONE);
        List<Record> bundle = s.assignBundle(NONE, members,
                new Fence[] {Fence.ORDINARY_CONTROL, Fence.ACK_TRAILING}, NONE);
        assertEquals(4, bundle.size());
        for (Record record : bundle) {
            assertEquals(0, record.streamIndex(), "never split across lanes");
        }
        assertEquals(Long.valueOf(chunk.sequence()), deps(bundle.get(0)).get(2), "a global opener waits for every stream");
        assertEquals(bundle.get(3).sequence(), s.globalFrontier(), "the bundle is a global fence");
        Record later = s.assign(2, Fence.CHUNK, NONE, false);
        assertEquals(Long.valueOf(bundle.get(3).sequence()), deps(later).get(0));
        assertEquals(0, s.lastAck(), "a bundled ACK is not an ACK-stream record");
    }
}
