// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.routing.ProtocolState;

class BindingQuiescenceBudgetTest {
    private static OutboundUnit unit() {
        OutboundUnit unit = new OutboundUnit(false);
        unit.add(ProtocolState.PLAY, new byte[10]);
        return unit;
    }

    @Test
    void timeoutAndOverflowReleaseEveryChargedQueuedUnitBeforeDroppingTheQueue() {
        for (boolean overflow : new boolean[] {false, true}) {
            ReceiveBudget node = ReceiveBudget.bounded(4000);
            final BindingSession[] session = new BindingSession[1];
            BindingDriver.Callbacks callbacks = new BindingDriver.Callbacks() {
                @Override
                public void send(byte[] payload) { }

                @Override
                public void activateOutbound() { }

                @Override
                public void activateInbound() { }

                @Override
                public void fail(int code, String reason) {
                    assertEquals(Protocol.CLOSE_BINDING_FAILURE, code);
                    OutboundQuiescence.discard(session[0].releaseQueued());
                }
            };
            session[0] = new BindingSession(BindingStateMachine.Role.SERVER, () -> new byte[32], null,
                    BindingStateMachine.MODE_OFFLINE, 775, new byte[32], new byte[32], () -> 0L,
                    0L, callbacks, null, null);
            session[0].onConfigurationEntered(0L);
            int queued = overflow ? BindingSession.MAX_QUEUED_PACKETS : 3;
            for (int i = 0; i < queued; i++) {
                assertEquals(BindingDriver.QueueResult.QUEUED,
                        OutboundQuiescence.offer(session[0], unit(), node));
            }
            assertEquals(queued * 10L, node.reserved());

            if (overflow) {
                assertEquals(BindingDriver.QueueResult.DROPPED_FAILED,
                        OutboundQuiescence.offer(session[0], unit(), node));
            } else {
                session[0].tick(BindingSession.ACTIVATION_DEADLINE_NANOS);
            }

            assertEquals(0, node.reserved(), overflow
                    ? "overflow callback releases all charged queued units"
                    : "timeout callback releases all charged queued units");
        }
    }
}
