// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.*;

import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Extractor;

class ReceiveFenceValidationTest {
    @Test
    void appliedControlClassMustMatchRealtimeAndChunkReferences() {
        for (Direction direction : Direction.values()) {
            for (Dependency fence : new Dependency[] {Dependency.GLOBAL, Dependency.FENCE, Dependency.VIEW,
                    Dependency.CONTROL, Dependency.ORDINARY, Dependency.INTERACTION}) {
                for (int stream : new int[] {1, 2}) {
                    if (direction == Direction.SERVERBOUND && stream == 2) continue;
                    StreamReceiver receiver = new StreamReceiver(1, direction,
                            new NStreamSession.ShardValidator(1), () -> 0L);
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
