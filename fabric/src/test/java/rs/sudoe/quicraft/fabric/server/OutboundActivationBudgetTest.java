// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.routing.ProtocolState;

class OutboundActivationBudgetTest {
    @Test
    void failureOnThirdSubmissionReleasesEveryUnitNotTransferred() {
        ReceiveBudget node = ReceiveBudget.bounded(100);
        OutboundHold.Sender sender = new OutboundHold.Sender() {
            int submissions;

            @Override
            public boolean writable() {
                return true;
            }

            @Override
            public void send(ProtocolState state, byte[] packet) {
                if (++submissions == 3) {
                    throw new IllegalStateException("injected third submission failure");
                }
            }

            @Override
            public void sendBundle(ProtocolState state, List<byte[]> packets) { }
        };
        OutboundHold hold = new OutboundHold(sender, 16, 100, node);
        List<Object> queued = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            OutboundUnit unit = new OutboundUnit(false);
            unit.add(ProtocolState.PLAY, new byte[10]);
            assertTrue(unit.reserve(node));
            queued.add(unit);
        }
        assertEquals(50, node.reserved());

        assertThrows(IllegalStateException.class, () -> hold.submitAll(queued));

        assertEquals(0, node.reserved(), "all five units, including units never submitted, are released");
    }
}
