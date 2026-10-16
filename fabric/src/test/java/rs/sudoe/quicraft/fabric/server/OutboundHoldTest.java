// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * Whole units to the lane layer (docs/protocol.md 13.15), backpressure (13.12) and the quiescence
 * queue's real byte count (12.10).
 */
class OutboundHoldTest {
    /** A lane layer that records what it was given and can refuse or stop being writable. */
    private static final class Lanes implements OutboundHold.Sender {
        final List<String> events = new ArrayList<>();
        boolean writable = true;
        int refuseBundlesAbove = Integer.MAX_VALUE;

        @Override
        public boolean writable() {
            return writable;
        }

        @Override
        public void send(ProtocolState state, byte[] packet) {
            events.add("send " + packet.length);
        }

        @Override
        public void sendBundle(ProtocolState state, List<byte[]> packets) {
            if (packets.size() > refuseBundlesAbove) {
                throw new IllegalStateException("bundle refused (13.15)");
            }
            events.add("bundle " + packets.size());
        }
    }

    private static OutboundUnit unit(boolean bundle, int... sizes) {
        OutboundUnit unit = new OutboundUnit(bundle);
        for (int size : sizes) {
            unit.add(ProtocolState.PLAY, new byte[size]);
        }
        return unit;
    }

    @Test
    void aBundleIsSubmittedWholeOrNotAtAll() {
        Lanes lanes = new Lanes();
        lanes.refuseBundlesAbove = 3;
        OutboundHold hold = new OutboundHold(lanes, 16, 1 << 20);

        // Admission would refuse this bundle part way through if it were fed member by member.
        assertThrows(IllegalStateException.class, () -> hold.submit(unit(true, 10, 10, 10, 10, 10)));

        assertEquals(List.of(), lanes.events, "no member of the refused bundle reached the lane layer");
        hold.submit(unit(true, 10, 10, 10));
        assertEquals(List.of("bundle 3"), lanes.events, "an admitted bundle is one atomic submission");
    }

    @Test
    void unitsAreHeldWhileTheLayerIsNotWritableAndReleasedInOrder() {
        Lanes lanes = new Lanes();
        OutboundHold hold = new OutboundHold(lanes, 16, 1 << 20);
        lanes.writable = false;

        hold.submit(unit(false, 1));
        hold.submit(unit(true, 2, 3));
        hold.submit(unit(false, 4));
        assertEquals(List.of(), lanes.events, "nothing produced while the layer is full");
        assertEquals(3, hold.heldUnits());

        lanes.writable = true;
        hold.drain();

        assertEquals(List.of("send 1", "bundle 2", "send 4"), lanes.events);
        assertEquals(0, hold.heldUnits());
    }

    @Test
    void aLaterUnitDoesNotOvertakeAHeldOne() {
        Lanes lanes = new Lanes();
        OutboundHold hold = new OutboundHold(lanes, 16, 1 << 20);
        lanes.writable = false;
        hold.submit(unit(false, 1));
        lanes.writable = true;

        hold.submit(unit(false, 2));

        assertEquals(List.of(), lanes.events, "order is kept: the held unit goes first, on drain");
        hold.drain();
        assertEquals(List.of("send 1", "send 2"), lanes.events);
    }

    @Test
    void theHoldIsBoundedLikeTheLanesOwnQueue() {
        Lanes lanes = new Lanes();
        lanes.writable = false;
        OutboundHold hold = new OutboundHold(lanes, 2, 100);

        hold.submit(unit(false, 40));
        hold.submit(unit(false, 40));
        assertThrows(IllegalStateException.class, () -> hold.submit(unit(false, 1)), "third unit: over the unit cap");

        OutboundHold bytes = new OutboundHold(lanes, 10, 100);
        bytes.submit(unit(false, 60));
        assertThrows(IllegalStateException.class, () -> bytes.submit(unit(false, 60)), "over the byte cap");
    }

    @Test
    void theQuiescenceQueueCountsRealEncodedBytesNotAGuessPerPacket() {
        // Offline server-role session: quiescent from the moment configuration was entered.
        BindingSession session = new BindingSession(BindingStateMachine.Role.SERVER, () -> new byte[32], null,
                BindingStateMachine.MODE_OFFLINE, 775, new byte[32], new byte[32], System::nanoTime, 0L,
                new BindingDriver.Callbacks() {
                    @Override
                    public void send(byte[] payload) { }

                    @Override
                    public void activateOutbound() { }

                    @Override
                    public void activateInbound() { }

                    @Override
                    public void fail(int code, String reason) { }
                }, null, null);
        session.onConfigurationEntered(System.nanoTime());
        assertTrue(session.isQuiescent());

        // Three 3 MiB units are three packets: a flat 1 KiB each would never get near the 8 MiB bound.
        assertEquals(BindingDriver.QueueResult.QUEUED, OutboundQuiescence.offer(session, unit(false, 3 << 20), ReceiveBudget.unlimited()));
        assertEquals(BindingDriver.QueueResult.QUEUED, OutboundQuiescence.offer(session, unit(false, 3 << 20), ReceiveBudget.unlimited()));
        assertEquals(BindingDriver.QueueResult.DROPPED_FAILED, OutboundQuiescence.offer(session, unit(false, 3 << 20), ReceiveBudget.unlimited()),
                "9 MiB of real bytes crosses 12.10's 8 MiB bound");
    }

    @Test
    void aUnitsSizeIsTheSumOfItsEncodedPackets() {
        assertEquals(30, unit(true, 10, 15, 5).bytes());
    }

    @Test
    void heldAndQueuedUnitsAreReservedAgainstTheNodeBudgetAndEveryPathGivesThemBack() {
        ReceiveBudget node = ReceiveBudget.bounded(1000);
        Lanes lanes = new Lanes();
        lanes.writable = false;
        OutboundHold hold = new OutboundHold(lanes, 16, 1 << 20, node);

        hold.submit(unit(false, 300));
        hold.submit(unit(true, 100, 100));
        assertEquals(500, node.reserved(), "held units are reserved at their real size");
        assertThrows(IllegalStateException.class, () -> hold.submit(unit(false, 600)), "the node refuses the rest");
        assertEquals(500, node.reserved());

        lanes.writable = true;
        hold.drain();
        assertEquals(0, node.reserved(), "sending gives the bytes back");

        lanes.writable = false;
        hold.submit(unit(false, 200));
        assertEquals(200, node.reserved());
        hold.teardown();
        assertEquals(0, node.reserved(), "teardown gives back what was still held");
    }

    @Test
    void theQuiescenceQueueReservesWhileQueuedAndEveryPathReleases() {
        ReceiveBudget node = ReceiveBudget.bounded(10 << 20);
        BindingSession session = new BindingSession(BindingStateMachine.Role.SERVER, () -> new byte[32], null,
                BindingStateMachine.MODE_OFFLINE, 775, new byte[32], new byte[32], System::nanoTime, 0L,
                new BindingDriver.Callbacks() {
                    @Override
                    public void send(byte[] payload) { }

                    @Override
                    public void activateOutbound() { }

                    @Override
                    public void activateInbound() { }

                    @Override
                    public void fail(int code, String reason) { }
                }, null, null);
        session.onConfigurationEntered(System.nanoTime());

        assertEquals(BindingDriver.QueueResult.QUEUED, OutboundQuiescence.offer(session, unit(false, 1000), node));
        assertEquals(BindingDriver.QueueResult.QUEUED, OutboundQuiescence.offer(session, unit(false, 2000), node));
        assertEquals(3000, node.reserved());

        OutboundQuiescence.discard(session.releaseQueued());
        assertEquals(0, node.reserved(), "teardown of a join that never activated returns everything");

        // A unit that overflows the queue is not left reserved.
        assertEquals(BindingDriver.QueueResult.DROPPED_FAILED, OutboundQuiescence.offer(session, unit(false, 9 << 20), node));
        assertEquals(0, node.reserved());
    }
}
