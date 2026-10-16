// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.routing.ProtocolState;

class OutboundHoldBudgetTest {
    private static final class Sender implements OutboundHold.Sender {
        @Override
        public boolean writable() {
            return false;
        }

        @Override
        public void send(ProtocolState state, byte[] packet) { }

        @Override
        public void sendBundle(ProtocolState state, List<byte[]> packets) { }
    }

    private static OutboundUnit unit(int bytes) {
        OutboundUnit unit = new OutboundUnit(false);
        unit.add(ProtocolState.PLAY, new byte[bytes]);
        return unit;
    }

    @Test
    void packetAndByteCapRejectionsReleaseTheirSuccessfulNodeReservation() {
        ReceiveBudget packetsNode = ReceiveBudget.bounded(100);
        OutboundHold packets = new OutboundHold(new Sender(), 2, 100, packetsNode);
        packets.submit(unit(40));
        packets.submit(unit(40));
        assertThrows(IllegalStateException.class, () -> packets.submit(unit(1)));
        assertEquals(80, packetsNode.reserved(), "packet-cap refusal releases only the rejected unit");
        packets.teardown();
        assertEquals(0, packetsNode.reserved());

        ReceiveBudget bytesNode = ReceiveBudget.bounded(200);
        OutboundHold bytes = new OutboundHold(new Sender(), 10, 100, bytesNode);
        bytes.submit(unit(60));
        assertThrows(IllegalStateException.class, () -> bytes.submit(unit(60)));
        assertEquals(60, bytesNode.reserved(), "byte-cap refusal releases only the rejected unit");
        bytes.teardown();
        assertEquals(0, bytesNode.reserved());
    }
}
