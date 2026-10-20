// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Ordering test: with the shipped 775 table, a global ACK after shard records carries
 *  dependencies >= every stream's assigned max, and a later chunk record depends on the
 *  ACK's global frontier (13.19 global-fence contract, no ACK_JOIN needed). */
class BlockAckChunkOrderingTest {

    @Test
    void ackAfterShardRecordsDependsOnEveryStreamAndChunkDependsOnAck() {
        // Two chunk streams and one realtime stream, matching the shipped table's lanes.
        StreamSequencer sequencer = new StreamSequencer(3);
        // Chunk updates on two different chunk shards (streams 2 and 3).
        Record chunkA = sequencer.assign(2, StreamSequencer.Fence.CHUNK, new byte[0], false);
        Record chunkB = sequencer.assign(3, StreamSequencer.Fence.CHUNK, new byte[0], false);
        // A realtime record on the realtime stream (stream 1).
        Record realtime = sequencer.assign(1, StreamSequencer.Fence.REALTIME, new byte[0], false);
        // The global ACK (packet 4, block_changed_ack) is CONTROL/GLOBAL: it waits for all.
        Record ack = sequencer.assign(0, StreamSequencer.Fence.GLOBAL, new byte[0], false);
        // The ACK's dependencies must cover every stream's max assigned sequence.
        assertEquals(pairs(1, realtime.sequence(), 2, chunkA.sequence(), 3, chunkB.sequence()),
                recordDependencies(ack), "ACK must carry dependencies >= every stream max");
        assertEquals(ack.sequence(), sequencer.globalFrontier(),
                "global frontier advances to the ACK sequence");
        // A later block_update (packet 8, chunk/shard) depends on the global frontier.
        Record laterBlockUpdate = sequencer.assign(2, StreamSequencer.Fence.CHUNK, new byte[0], false);
        assertEquals(pairs(0, ack.sequence()), recordDependencies(laterBlockUpdate),
                "later chunk record depends on the ACK's global frontier");
    }

    private static java.util.Map<Integer, Long> pairs(long... flat) {
        java.util.Map<Integer, Long> result = new java.util.TreeMap<Integer, Long>();
        for (int i = 0; i < flat.length; i += 2) {
            result.put((int) flat[i], flat[i + 1]);
        }
        return result;
    }

    private static java.util.Map<Integer, Long> recordDependencies(Record record) {
        java.util.Map<Integer, Long> result = new java.util.TreeMap<Integer, Long>();
        for (Record.Dependency d : record.dependencies()) {
            result.put(d.streamIndex(), d.sequence());
        }
        return result;
    }

    // Helper that uses the test's simple pairs format instead of the full Dependency array.
    private static java.util.Map<Integer, Long> StreamSequencerDeps(java.util.Map<Integer, Long> pairs) {
        return pairs;
    }
}
