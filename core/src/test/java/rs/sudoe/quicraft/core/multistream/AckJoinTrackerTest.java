// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** ACK_JOIN pending store, causal coverage, limits and transitions (docs/protocol.md 13.19). */
class AckJoinTrackerTest {
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

    @Test void ackWaitsForCoveredUpdateThenTakesControlSnapshot() {
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 10, 100));
        acks.coverPending(1, 100L);
        assertNull(acks.take(1, 101, 3, null), "unassigned coverage must hold the ack");
        assertEquals(1, acks.pendingCount());
        acks.assignedPending(100L, 2, 7);
        AckJoinTracker.Ready ready = acks.take(1, 102, 3, null);
        assertNotNull(ready);
        assertEquals(expect(0, 3, 2, 7), pairs(ready.dependencies()));
        assertEquals(0, acks.pendingCount());
        assertEquals(0, acks.pendingBytes());
        assertNull(acks.take(1, 103, 3, null), "a taken ack cannot be taken twice");
    }

    @Test void coverAssignedKeepsMaximaAndSuppressionRemovesTheWait() {
        AckJoinTracker acks = new AckJoinTracker();
        acks.create(2, 5, 0);
        acks.coverAssigned(2, 3, 4);
        acks.coverAssigned(2, 3, 2);
        acks.coverPending(2, 200L);
        acks.suppressPending(200L);
        AckJoinTracker.Ready ready = acks.take(2, 1, 0, null);
        assertNotNull(ready, "suppressed coverage must not be awaited");
        assertEquals(expect(3, 4), pairs(ready.dependencies()));
    }

    @Test void assignmentOrderIsViewThenCoveredUpdateThenAck() {
        StreamSequencer sequencer = new StreamSequencer(2);
        AckJoinTracker acks = new AckJoinTracker();
        // The game emits the ack first; the covered update U follows a view gate V in the game order.
        assertTrue(acks.create(9, 16, 0));
        acks.coverPending(9, 5L);
        assertNull(acks.take(9, 1, 0, null));
        Record view = sequencer.assign(0, StreamSequencer.Fence.VIEW, NONE, false);
        Record update = sequencer.assign(2, StreamSequencer.Fence.CHUNK, NONE, false);
        acks.assignedPending(5L, 2, update.sequence());
        AckJoinTracker.Ready ready = acks.take(9, 2, sequencer.controlFence(), null);
        assertNotNull(ready);
        Record ack = sequencer.assignJoin(2, NONE, ready.dependencies());
        assertEquals(2, ack.sequence(), "the ack is assigned after its covered update on CHUNK[0]");
        assertEquals(expect(0, view.sequence()), pairs(ack.dependencies()));
        assertEquals(expect(), pairs(view.dependencies()), "the view gate never waits for the unassigned ack");
    }

    @Test void pendingLimitsEnforceEntriesAndBytesWithoutDisturbingExistingAcks() {
        AckJoinTracker acks = new AckJoinTracker();
        for (int i = 1; i <= AckJoinTracker.MAX_ENTRIES; i++) {
            assertTrue(acks.create(i, 1, 0), "entry " + i);
        }
        assertFalse(acks.create(129, 1, 0), "129th pending ack must be refused");
        assertEquals(AckJoinTracker.MAX_ENTRIES, acks.pendingCount());
        assertNotNull(acks.take(1, 1, 0, null));
        assertTrue(acks.create(129, 1, 0), "a released slot is reusable");

        AckJoinTracker bytes = new AckJoinTracker();
        assertTrue(bytes.create(1, AckJoinTracker.MAX_BYTES, 0));
        assertFalse(bytes.create(2, 1, 0), "byte limit is hit before entries");
        assertEquals(AckJoinTracker.MAX_BYTES, bytes.pendingBytes());
    }

    @Test void deadlineDropsUnresolvedAckCountedAndReleasesItsCharge() {
        AckJoinTracker acks = new AckJoinTracker();
        acks.create(1, 10, 100);
        acks.coverPending(1, 9L);
        long deadline = 100 + AckJoinTracker.DEADLINE_NANOS;
        assertNull(acks.take(1, deadline - 1, 0, null));
        assertEquals(1, acks.pendingCount());
        assertNull(acks.take(1, deadline, 0, null));
        assertEquals(0, acks.pendingCount());
        assertEquals(0, acks.pendingBytes());
        assertEquals(1, acks.droppedCount());
        acks.assignedPending(9L, 2, 1);

        acks.create(2, 4, 0);
        assertEquals(0, acks.expire(AckJoinTracker.DEADLINE_NANOS - 1, 0, null).size());
        assertEquals(1, acks.expire(AckJoinTracker.DEADLINE_NANOS, 0, null).size(),
                "the fully covered ack resolves at its deadline instead of being dropped");
        assertEquals(1, acks.droppedCount());
        assertEquals(0, acks.pendingCount());
    }

    @Test void deadlineResolvesCompleteAcksAndDropsOnlyUnassignedOnes() {
        AckJoinTracker acks = new AckJoinTracker();
        acks.create(1, 4, 100);
        acks.coverPending(1, 50L);
        acks.assignedPending(50L, 3, 6);
        acks.create(2, 4, 100);
        acks.coverPending(2, 51L);
        long deadline = 100 + AckJoinTracker.DEADLINE_NANOS;
        List<AckJoinTracker.Ready> ready = acks.expire(deadline, 4, null);
        assertEquals(1, ready.size(), "the complete ack is resolved at the deadline");
        assertEquals(1L, ready.get(0).id());
        assertEquals(expect(0, 4, 3, 6), pairs(ready.get(0).dependencies()));
        assertEquals(1, acks.droppedCount(), "only the unassigned ack is dropped");
        assertEquals(0, acks.pendingCount());
        assertEquals(0, acks.pendingBytes());
    }

    @Test void completeAckResolvesAtTheDeadlineThroughTake() {
        AckJoinTracker acks = new AckJoinTracker();
        acks.create(7, 4, 0);
        acks.coverPending(7, 70L);
        acks.assignedPending(70L, 2, 9);
        AckJoinTracker.Ready ready = acks.take(7, AckJoinTracker.DEADLINE_NANOS, 0, null);
        assertNotNull(ready, "complete coverage is not discarded by the deadline");
        assertEquals(0, acks.droppedCount());
    }

    @Test void ackProducerIsBackpressuredByFifoLimitAndResumesAfterRelease() {
        AckJoinTracker acks = new AckJoinTracker();
        StreamSequencer sequencer = new StreamSequencer(2);
        // Outbound FIFO stalled: every covered update is queued but not yet assigned to the wire.
        long token = 0;
        int refused = -1;
        for (int i = 1; i <= AckJoinTracker.MAX_ENTRIES + 1; i++) {
            if (!acks.create(i, 1, 0)) {
                refused = i;
                break;
            }
            acks.coverPending(i, ++token);
        }
        assertEquals(AckJoinTracker.MAX_ENTRIES + 1, refused, "the producer is refused at the limit");
        // Assignment of covered updates proceeds independently of the pending acks.
        Record update = sequencer.assign(2, StreamSequencer.Fence.CHUNK, new byte[0], false);
        assertEquals(1, update.sequence());
        acks.assignedPending(1L, 2, update.sequence());
        // Releasing one ack (its covered updates are now assigned) lets the producer resume.
        assertNotNull(acks.take(1, 1, 0, null));
        assertTrue(acks.create(refused, 1, 0), "the producer resumes after a release");
    }

    @Test void transitionSettlesCompleteAcksAndDropsTheRest() {
        AckJoinTracker acks = new AckJoinTracker();
        acks.create(1, 4, 0);
        acks.create(2, 4, 0);
        acks.coverPending(2, 77L);
        acks.create(3, 4, 0);
        acks.markInexact(3);
        acks.coverPending(3, 78L);
        acks.assignedPending(78L, 2, 5);
        Record.Dependency[] superset = new Record.Dependency[] {
            new Record.Dependency(0, 1), new Record.Dependency(2, 5)};
        List<AckJoinTracker.Ready> settled = acks.settleForTransition(2, superset);
        List<Long> ids = new ArrayList<Long>();
        for (AckJoinTracker.Ready ready : settled) {
            ids.add(ready.id());
        }
        assertEquals(java.util.Arrays.asList(1L, 3L), ids);
        assertEquals(expect(0, 2), pairs(settled.get(0).dependencies()));
        assertEquals(expect(0, 2, 2, 5), pairs(settled.get(1).dependencies()),
                "an inexact ack uses the safe superset merged with the control frontier");
        assertEquals(1, acks.droppedCount(), "the unresolved ack is dropped and counted");
        assertEquals(0, acks.pendingCount());
        assertEquals(0, acks.pendingBytes());
    }

    @Test void inexactAckWithoutSafeSupersetIsRejected() {
        AckJoinTracker acks = new AckJoinTracker();
        acks.create(4, 1, 0);
        acks.markInexact(4);
        assertThrows(IllegalArgumentException.class, () -> acks.take(4, 0, 0, null));
    }

    @Test void negativeClockOriginIsAcceptedAndDeadlineUsesDifferences() {
        // System.nanoTime() may be negative. Only differences from the creation instant count (13.19).
        long origin = -20_000_000_000L;
        AckJoinTracker acks = new AckJoinTracker();
        assertTrue(acks.create(1, 4, origin));
        acks.coverPending(1, 10L);
        assertNull(acks.take(1, origin + AckJoinTracker.DEADLINE_NANOS - 1, 0, null));
        assertEquals(1, acks.pendingCount(), "not yet at the deadline");
        assertNull(acks.take(1, origin + AckJoinTracker.DEADLINE_NANOS, 0, null));
        assertEquals(0, acks.pendingCount(), "unresolved ack dropped at the deadline");
        assertEquals(1, acks.droppedCount());
        assertEquals(0, acks.pendingBytes());

        assertTrue(acks.create(2, 4, origin));
        assertNotNull(acks.take(2, origin, 0, null), "a complete ack resolves at once");
    }

    @Test void coverPendingReportsWhetherTheCoverWasRecorded() {
        AckJoinTracker acks = new AckJoinTracker();
        acks.create(1, 4, 0);
        assertTrue(acks.coverPending(1, 5L), "first cover is recorded");
        assertFalse(acks.coverPending(1, 5L), "duplicate cover is not recorded twice");
        acks.assignedPending(5L, 2, 3);
        assertNotNull(acks.take(1, 0, 0, null));
    }

    @Test void unknownAcksAreRejectedAndTokensWithoutWaitersAreIgnored() {
        AckJoinTracker acks = new AckJoinTracker();
        assertThrows(IllegalArgumentException.class, () -> acks.coverPending(42, 1L));
        assertThrows(IllegalArgumentException.class, () -> acks.create(1, -1, 0));
        acks.assignedPending(999L, 2, 1);
        acks.suppressPending(999L);
        assertEquals(0, acks.pendingCount());
    }
}
