// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** BATCH_JOIN edges and batch framing (docs/protocol.md 13.19). */
class BatchJoinTrackerTest {
    private static final byte[] NONE = new byte[0];

    private static Map<Integer, Long> pairs(Record.Dependency[] dependencies) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (Record.Dependency dependency : dependencies) {
            result.put(dependency.streamIndex(), dependency.sequence());
        }
        return result;
    }

    private static Map<Integer, Long> pairs(java.util.List<Record.Dependency> dependencies) {
        return pairs(dependencies.toArray(new Record.Dependency[dependencies.size()]));
    }

    private static Map<Integer, Long> expect(long... flat) {
        Map<Integer, Long> result = new TreeMap<Integer, Long>();
        for (int i = 0; i < flat.length; i += 2) {
            result.put((int) flat[i], flat[i + 1]);
        }
        return result;
    }

    @Test void finishDependsOnStartAndTheMaximumOfEveryInstallStream() {
        BatchJoinTracker batch = new BatchJoinTracker();
        batch.begin(1, 4, 0);
        batch.chunk(1, 2, 5, 0);
        batch.chunk(1, 3, 7, 0);
        batch.chunk(1, 2, 6, 0);
        assertTrue(batch.active());
        Record.Dependency[] dependencies = batch.finish(1, 0);
        assertEquals(expect(0, 4, 2, 6, 3, 7), pairs(dependencies));
        assertFalse(batch.active());
    }

    @Test void emptyBatchStillDependsOnItsStart() {
        BatchJoinTracker batch = new BatchJoinTracker();
        batch.begin(2, 9, 0);
        assertEquals(expect(0, 9), pairs(batch.finish(2, 0)));
    }

    @Test void nestingUnmatchedMarkersAndEpochMismatchAreRejected() {
        BatchJoinTracker batch = new BatchJoinTracker();
        batch.begin(1, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> batch.begin(2, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> batch.chunk(9, 2, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> batch.finish(9, 0));
        assertThrows(IllegalArgumentException.class, () -> batch.chunk(1, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> batch.chunk(1, 1, 1, 0), "CONTROL is not a chunk");
        batch.finish(1, 0);
        assertThrows(IllegalArgumentException.class, () -> batch.finish(1, 0), "unmatched finish");
        assertThrows(IllegalArgumentException.class, () -> batch.chunk(1, 2, 1, 0), "chunk after finish");
    }

    @Test void nextBatchStartLinksToPreviousJoinAcrossEmptyBatches() {
        BatchJoinTracker batch = new BatchJoinTracker();
        assertNull(batch.previousJoinDependency());
        batch.begin(1, 3, 0);
        batch.finish(1, 0);
        batch.recordJoin(5);
        assertEquals(expect(2, 5), pairs(new Record.Dependency[] {batch.previousJoinDependency()}));
        batch.begin(2, 8, 0);
        batch.finish(2, 0);
        batch.recordJoin(9);
        assertEquals(expect(2, 9), pairs(new Record.Dependency[] {batch.previousJoinDependency()}));
        assertThrows(IllegalArgumentException.class, () -> batch.recordJoin(4), "joins never move backward");
    }

    @Test void batchEdgesAreBackwardThroughTheSequencer() {
        StreamSequencer sequencer = new StreamSequencer(2);
        BatchJoinTracker batch = new BatchJoinTracker();
        Record start = sequencer.assign(0, StreamSequencer.Fence.VIEW, NONE, false,
                new Record.Dependency[0]);
        batch.begin(1, start.sequence(), start.epoch());
        Record install = sequencer.assign(2, StreamSequencer.Fence.CHUNK, NONE, false);
        batch.chunk(1, 2, install.sequence(), 0);
        Record other = sequencer.assign(3, StreamSequencer.Fence.CHUNK, NONE, false);
        batch.chunk(1, 3, other.sequence(), 0);
        Record join = sequencer.assignJoin(3, NONE, batch.finish(1, 0));
        batch.recordJoin(join.sequence());
        assertEquals(expect(0, start.sequence(), 3, other.sequence()), pairs(join.dependencies()));
        Record nextStart = sequencer.assign(0, StreamSequencer.Fence.VIEW, NONE, false,
                batch.previousJoinDependency());
        assertEquals(join.sequence(), pairs(nextStart.dependencies()).get(2));
        assertEquals(other.sequence(), pairs(nextStart.dependencies()).get(3));
    }
}
