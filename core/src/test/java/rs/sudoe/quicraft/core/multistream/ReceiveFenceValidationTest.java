// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.*;

import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Extractor;

class ReceiveFenceValidationTest {
    private static ReceiveRoute control(Dependency fence) {
        return new ReceiveRoute(ReceiveRoute.Lane.CONTROL, fence, null, false, false);
    }

    private static ReceiveRoute realtime() {
        return new ReceiveRoute(ReceiveRoute.Lane.REALTIME, Dependency.LANE, null, false, false);
    }

    private static void applyControl(StreamReceiver receiver, long sequence, Dependency fence) {
        assertAdmitted(receiver, rec(0, sequence, 0));
        enqueue(receiver, 0, sequence, control(fence));
        receiver.applied(0, sequence);
    }

    private static void applyRealtime(StreamReceiver receiver, long sequence, long reference) {
        assertAdmitted(receiver, rec(1, sequence, 0, dep(0, reference)));
        enqueue(receiver, 1, sequence, realtime());
        receiver.applied(1, sequence);
        assertNull(receiver.pendingViolation());
    }

    private static ReceiveRoute chunk() {
        return new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.LANE,
                new Extractor.Position(0, 0), false, false);
    }

    @Test
    void realtimeControlFenceDoesNotPruneOlderChunkGlobal() {
        StreamReceiver receiver = new StreamReceiver(1, new NStreamSession.ShardValidator(1), () -> 0L);
        applyControl(receiver, 1, Dependency.GLOBAL);
        applyControl(receiver, 2, Dependency.CONTROL);
        applyRealtime(receiver, 1, 2);
        assertAdmitted(receiver, rec(2, 1, 0, dep(0, 1)));
        enqueue(receiver, 2, 1, chunk());
        receiver.applied(2, 1);
        assertNull(receiver.pendingViolation());
    }

    @Test
    void fiveThousandControlFencesCannotEvictChunkGlobal() {
        NStreamSession.ShardValidator validator = new NStreamSession.ShardValidator(1, Direction.CLIENTBOUND, 3);
        StreamReceiver receiver = new StreamReceiver(1, validator, () -> 0L);
        applyControl(receiver, 1, Dependency.GLOBAL);
        for (int sequence = 2; sequence <= 5001; sequence++) applyControl(receiver, sequence, Dependency.CONTROL);
        assertEquals(5, validator.retainedEntries(), "three realtime, one chunk and one batch fence");
        assertAdmitted(receiver, rec(2, 1, 0, dep(0, 1)));
        enqueue(receiver, 2, 1, chunk());
        receiver.applied(2, 1);
        assertNull(receiver.pendingViolation());
    }

    @Test
    void chunkControlFenceInsideRetainedRangeClosesOrdering() {
        StreamReceiver receiver = new StreamReceiver(1, new NStreamSession.ShardValidator(1), () -> 0L);
        applyControl(receiver, 1, Dependency.GLOBAL);
        applyControl(receiver, 2, Dependency.CONTROL);
        applyControl(receiver, 3, Dependency.VIEW);
        assertAdmitted(receiver, rec(2, 1, 0, dep(0, 2)));
        assertClose(receiver.validateEligible(2, chunk()));
    }

    @Test
    void ackOrdinaryCoverageAndOlderChunkFrontierAreIndependent() {
        StreamReceiver receiver = new StreamReceiver(1, new NStreamSession.ShardValidator(1), () -> 0L);
        applyControl(receiver, 1, Dependency.GLOBAL);
        applyControl(receiver, 2, Dependency.ORDINARY);
        ReceiveRoute ack = new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.ACK_JOIN, null, false, true);
        assertAdmitted(receiver, recKind(2, 1, 0, RecordCodec.KIND_ACK_JOIN, dep(0, 2)));
        enqueue(receiver, 2, 1, ack);
        receiver.applied(2, 1);
        assertAdmitted(receiver, rec(2, 2, 0, dep(0, 1)));
        enqueue(receiver, 2, 2, chunk());
        receiver.applied(2, 2);
        assertNull(receiver.pendingViolation());
    }

    @Test
    void batchJoinUsesGlobalViewClassAndIndependentMonotonicFrontier() {
        NStreamSession.ShardValidator validator = new NStreamSession.ShardValidator(1);
        applyToValidator(validator, 1, Dependency.VIEW);
        applyToValidator(validator, 2, Dependency.CONTROL);
        applyToValidator(validator, 3, Dependency.GLOBAL);
        ReceiveRoute batch = new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.BATCH_JOIN, null, false, true);
        assertFalse(validator.valid(2, recKind(2, 1, 0, RecordCodec.KIND_BATCH_JOIN, dep(0, 2)), batch));
        assertTrue(validator.valid(2, recKind(2, 1, 0, RecordCodec.KIND_BATCH_JOIN, dep(0, 3)), batch));
        assertTrue(validator.valid(2, rec(2, 2, 0, dep(0, 1)), chunk()));
        assertFalse(validator.valid(2, recKind(2, 3, 0, RecordCodec.KIND_BATCH_JOIN, dep(0, 1)), batch));
    }

    private static void applyToValidator(NStreamSession.ShardValidator validator, long sequence, Dependency fence) {
        validator.applied(0, sequence, sequence, control(fence));
    }

    @Test
    void displacedOrdinaryControlReferenceStillClosesOrdering() {
        StreamReceiver receiver = new StreamReceiver(1, Direction.SERVERBOUND,
                new NStreamSession.ShardValidator(1, Direction.SERVERBOUND, 16), () -> 0L);
        applyControl(receiver, 1, Dependency.ORDINARY);
        applyControl(receiver, 2, Dependency.CONTROL);
        applyControl(receiver, 3, Dependency.ORDINARY);
        assertAdmitted(receiver, rec(1, 1, 0, dep(0, 1)));
        assertClose(receiver.validateEligible(1, realtime()));
    }

    @Test
    void decreasingRealtimeReferenceClosesEvenAbovePinnedFloor() {
        StreamReceiver receiver = new StreamReceiver(1, new NStreamSession.ShardValidator(1), () -> 0L);
        applyControl(receiver, 1, Dependency.GLOBAL);
        applyControl(receiver, 2, Dependency.CONTROL);
        // Both references have retained valid classes, isolating the REALTIME-kind decrease.
        assertAdmitted(receiver, rec(2, 1, 0, dep(0, 1)));
        enqueue(receiver, 2, 1, new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.LANE,
                new Extractor.Position(0, 0), false, false));
        receiver.applied(2, 1);
        applyRealtime(receiver, 1, 2);
        assertAdmitted(receiver, rec(1, 2, 0, dep(0, 1)));
        assertClose(receiver.validateEligible(1, realtime()));
    }

    @Test
    void ordinaryLongRunWithUnchangedFenceAndIdleChunkStaysOpen() {
        for (Direction direction : Direction.values()) {
            NStreamSession.ShardValidator validator = new NStreamSession.ShardValidator(1, direction, 8);
            StreamReceiver receiver = new StreamReceiver(1, direction, validator, () -> 0L);
            applyControl(receiver, 1, Dependency.GLOBAL);
            for (int sequence = 2; sequence <= 100001; sequence++) {
                applyControl(receiver, sequence, Dependency.ORDINARY);
                applyRealtime(receiver, sequence - 1, 1);
                assertNull(receiver.pendingViolation());
                assertEquals(direction == Direction.SERVERBOUND ? 1 : 3, validator.retainedEntries());
            }
        }
    }

    @Test
    void decreaseBelowEvictedFloorStillClosesOrdering() {
        NStreamSession.ShardValidator validator = new NStreamSession.ShardValidator(1, Direction.SERVERBOUND, 2);
        StreamReceiver receiver = new StreamReceiver(1, Direction.SERVERBOUND, validator, () -> 0L);
        for (int sequence = 1; sequence <= 5; sequence++) {
            applyControl(receiver, sequence, Dependency.CONTROL);
            applyRealtime(receiver, sequence, sequence);
        }
        assertEquals(2, validator.retainedEntries());
        // Lost class evidence cannot excuse a provable decrease within the same kind.
        assertAdmitted(receiver, rec(1, 6, 0, dep(0, 1)));
        assertClose(receiver.validateEligible(1, realtime()));
    }

    @Test
    void overflowEvictsWithoutClosingAndOldReferenceIsUnprovable() {
        NStreamSession.ShardValidator validator = new NStreamSession.ShardValidator(1, Direction.CLIENTBOUND, 3);
        StreamReceiver receiver = new StreamReceiver(1, Direction.CLIENTBOUND, validator, () -> 0L);
        for (int sequence = 1; sequence <= 100; sequence++) {
            applyControl(receiver, sequence, Dependency.GLOBAL);
            if (sequence == 1) applyRealtime(receiver, 1, 1);
            assertNull(receiver.pendingViolation());
            assertTrue(validator.retainedEntries() <= 9);
        }
        assertEquals(9, validator.retainedEntries());
        // The previously referencing REALTIME is idle; CHUNK has never referenced at all.
        assertAdmitted(receiver, rec(2, 1, 0, dep(0, 1)));
        assertNull(receiver.validateEligible(2, new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.LANE,
                new Extractor.Position(0, 0), false, false)));
    }

    @Test
    void neverReferencingChunkDoesNotGrowBoundedEvidence() {
        NStreamSession.ShardValidator validator = new NStreamSession.ShardValidator(1, Direction.CLIENTBOUND, 3);
        StreamReceiver receiver = new StreamReceiver(1, Direction.CLIENTBOUND, validator, () -> 0L);
        for (int sequence = 1; sequence <= 100; sequence++) {
            applyControl(receiver, sequence, Dependency.GLOBAL);
            applyRealtime(receiver, sequence, sequence);
            assertEquals(3 * Math.min(sequence, 3), validator.retainedEntries());
            assertNull(receiver.pendingViolation());
        }
    }

    @Test
    void interactionRealtimeReferencesCannotDecrease() {
        StreamReceiver receiver = new StreamReceiver(1, Direction.SERVERBOUND,
                new NStreamSession.ShardValidator(1, Direction.SERVERBOUND, 16), () -> 0L);
        for (int sequence = 1; sequence <= 2; sequence++) {
            assertAdmitted(receiver, rec(1, sequence, 0));
            enqueue(receiver, 1, sequence, realtime());
            receiver.applied(1, sequence);
        }
        assertAdmitted(receiver, rec(0, 1, 0, dep(1, 2)));
        enqueue(receiver, 0, 1, control(Dependency.INTERACTION));
        receiver.applied(0, 1);
        assertAdmitted(receiver, rec(0, 2, 0, dep(1, 1)));
        assertClose(receiver.validateEligible(0, control(Dependency.INTERACTION)));
    }

    @Test
    void appliedControlClassMustMatchRealtimeAndChunkReferences() {
        for (Direction direction : Direction.values()) {
            for (Dependency fence : new Dependency[] {Dependency.GLOBAL, Dependency.FENCE, Dependency.VIEW,
                    Dependency.CONTROL, Dependency.ORDINARY, Dependency.INTERACTION}) {
                for (int stream : new int[] {1, 2}) {
                    if (direction == Direction.SERVERBOUND && stream == 2) continue;
                    StreamReceiver receiver = new StreamReceiver(1, direction,
                            new NStreamSession.ShardValidator(1, direction), () -> 0L);
                    assertAdmitted(receiver, rec(0, 1, 0));
                    enqueue(receiver, 0, 1, new ReceiveRoute(ReceiveRoute.Lane.CONTROL, fence, null, false, false));
                    receiver.applied(0, 1);
                    assertAdmitted(receiver, rec(stream, 1, 0, dep(0, 1)));
                    ReceiveRoute route = new ReceiveRoute(stream == 1 ? ReceiveRoute.Lane.REALTIME
                            : ReceiveRoute.Lane.CHUNK, Dependency.LANE,
                            stream == 2 ? new Extractor.Position(0, 0) : null, false, false);
                    ReceiveClose close = receiver.validateEligible(stream, route);
                    boolean valid = fence == Dependency.GLOBAL || fence == Dependency.FENCE || fence == Dependency.VIEW
                            || stream == 1 && fence == Dependency.CONTROL;
                    if (valid) assertNull(close, direction + "/" + stream + "/" + fence);
                    else assertClose(close);
                }
            }
        }
    }

    @Test
    void unknownTargetAndAbsentCrossStreamFrontierAreNotMisconduct() {
        StreamReceiver receiver = new StreamReceiver(1, new NStreamSession.ShardValidator(1), () -> 0L);
        assertAdmitted(receiver, rec(1, 1, 0));
        enqueue(receiver, 1, 1, new ReceiveRoute(ReceiveRoute.Lane.REALTIME, Dependency.LANE, null, false, false));
        receiver.applied(1, 1);
        assertAdmitted(receiver, rec(1, 2, 0, dep(0, 1)));
        assertEquals(0, receiver.appliedWatermark(0));
        assertNull(receiver.pendingViolation());
        // Once the actual target arrives and applies, its class is checked, not guessed from arrival order.
        assertAdmitted(receiver, rec(0, 1, 0));
        enqueue(receiver, 0, 1, new ReceiveRoute(ReceiveRoute.Lane.CONTROL, Dependency.VIEW, null, false, false));
        receiver.applied(0, 1);
        enqueue(receiver, 1, 2, new ReceiveRoute(ReceiveRoute.Lane.REALTIME, Dependency.LANE, null, false, false));
    }
}
