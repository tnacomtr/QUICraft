// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;
import static rs.sudoe.quicraft.core.multistream.StreamReceiverTest.*;

import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Dependency;

/** 13.18: no old-epoch record admitted before the fence may execute after it. */
class EpochAdvanceStaleHeadTest {
    private static ReceiveRoute transition() {
        return new ReceiveRoute(ReceiveRoute.Lane.CONTROL, Dependency.GLOBAL, null, true, false);
    }

    private static ReceiveRoute realtime() {
        return new ReceiveRoute(ReceiveRoute.Lane.REALTIME, Dependency.LANE, null, false, false);
    }

    private static StreamReceiver receiver() {
        return new StreamReceiver(1, new NStreamSession.ShardValidator(1), () -> 0L);
    }

    @Test
    void admittedOldEpochRealtimeHeadClosesWhenTheTransitionApplies() {
        StreamReceiver rx = receiver();
        assertAdmitted(rx, rec(1, 1, 0, dep(0, 1)));
        assertAdmitted(rx, rec(0, 1, 0));
        assertEquals(0, rx.nextToEnqueue(), "only the transition is ready");
        enqueue(rx, 0, 1, transition());
        rx.applied(0, 1);
        assertEquals(1, rx.epoch());
        assertClose(rx.pendingViolation());
        assertEquals(-1, rx.nextToEnqueue(), "the stale head is never handed out");
        assertViolation(offer(rx, rec(0, 2, 1)));
    }

    @Test
    void enqueuedButUnappliedOldEpochHeadClosesWhenTheTransitionApplies() {
        StreamReceiver rx = receiver();
        assertAdmitted(rx, rec(0, 1, 0));
        enqueue(rx, 0, 1, transition());
        assertAdmitted(rx, rec(1, 1, 0));
        enqueue(rx, 1, 1, realtime());
        rx.applied(0, 1);
        assertClose(rx.pendingViolation());
        assertEquals(-1, rx.nextToEnqueue());
    }

    @Test
    void correctSenderTransitionAfterItsOldEpochRecordsRaisesNoViolation() {
        StreamReceiver rx = receiver();
        assertAdmitted(rx, rec(1, 1, 0));
        enqueue(rx, 1, 1, realtime());
        rx.applied(1, 1);
        assertAdmitted(rx, rec(0, 1, 0, dep(1, 1)));
        enqueue(rx, 0, 1, transition());
        rx.applied(0, 1);
        assertEquals(1, rx.epoch());
        assertNull(rx.pendingViolation(), "the transition's own epoch-0 record must not trip the check");
        assertAdmitted(rx, rec(1, 2, 1, dep(0, 1)));
        assertEquals(1, rx.nextToEnqueue());
    }
}
