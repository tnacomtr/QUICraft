// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.multistream.AckJoinTracker;
import rs.sudoe.quicraft.core.multistream.MultistreamSession;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.routing.ProtocolState;

class OutboundCausalUnitTest {
    private static CausalSendEnvelope envelope(CausalSendEnvelope.ProducerSnapshot producer,
            CausalSendEnvelope.BundleMetadata bundle) {
        return new CausalSendContext(new Object()).newEnvelope(null, producer, bundle);
    }

    private static OutboundUnit unit(CausalSendEnvelope envelope, boolean bundle, int... sizes) {
        OutboundUnit unit = new OutboundUnit(bundle, envelope);
        for (int size : sizes) {
            unit.add(ProtocolState.PLAY, new byte[size]);
        }
        return unit;
    }

    @Test
    void sameEnvelopeObjectSurvivesQuiescenceQueueHoldAndDrain() {
        AckJoinTracker tracker = new AckJoinTracker();
        MultistreamSession.CausalScope scope = tracker.openScope();
        MultistreamSession.UpdateToken token = tracker.registerUpdate(scope);
        CausalSendEnvelope envelope = envelope(new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null),
                CausalSendEnvelope.BundleMetadata.NONE);
        OutboundUnit original = unit(envelope, false, 4);
        BindingSession binding = new BindingSession(BindingStateMachine.Role.SERVER, () -> new byte[32], null,
                BindingStateMachine.MODE_OFFLINE, 775, new byte[32], new byte[32], System::nanoTime, 0L,
                new BindingDriver.Callbacks() {
                    @Override public void send(byte[] payload) { }
                    @Override public void activateOutbound() { }
                    @Override public void activateInbound() { }
                    @Override public void fail(int code, String reason) { }
                }, null, null);
        binding.onConfigurationEntered(System.nanoTime());
        ReceiveBudget budget = ReceiveBudget.bounded(100);
        assertEquals(BindingDriver.QueueResult.QUEUED, OutboundQuiescence.offer(binding, original, budget));
        List<Object> released = binding.releaseQueued();
        OutboundUnit queued = (OutboundUnit) released.get(0);
        assertSame(envelope, queued.envelope());

        List<OutboundUnit> received = new ArrayList<>();
        AtomicBoolean writable = new AtomicBoolean(false);
        OutboundHold.Sender sender = new OutboundHold.Sender() {
            @Override public boolean writable() { return writable.get(); }
            @Override public void send(OutboundUnit whole) { received.add(whole); }
            @Override public void send(ProtocolState state, byte[] packet) { throw new AssertionError("decomposed send"); }
            @Override public void sendBundle(ProtocolState state, List<byte[]> packets) { throw new AssertionError("decomposed bundle"); }
        };
        OutboundHold hold = new OutboundHold(sender, 4, 100, budget);
        hold.submitAll(released);
        assertEquals(1, hold.heldUnits());
        assertSame(envelope, queued.envelope());
        assertEquals(4, budget.reserved());
        writable.set(true);
        hold.drain();
        assertEquals(1, received.size());
        assertSame(queued, received.get(0), "the sender receives the original whole unit");
        assertEquals(0, budget.reserved());
        assertSame(envelope, queued.envelope());
    }

    @Test
    void unsupportedCoverageShapesAreRejectedBeforeAnyMemberCanBeSent() {
        AckJoinTracker tracker = new AckJoinTracker();
        MultistreamSession.CausalScope scope = tracker.openScope();
        MultistreamSession.UpdateToken one = tracker.registerUpdate(scope);
        MultistreamSession.UpdateToken two = tracker.registerUpdate(scope);
        CausalSendEnvelope.ProducerSnapshot multiple = new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(one, two), null, null);
        CausalSendEnvelope.ProducerSnapshot scoped = new CausalSendEnvelope.ProducerSnapshot(List.of(scope), List.of(one), null, null);
        CausalSendEnvelope.ProducerSnapshot acked = new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(one),
                new CausalSendEnvelope.AckOperation(1, 0, List.of()), null);
        CausalSendEnvelope.ProducerSnapshot batched = new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(one), null,
                new CausalSendEnvelope.BatchOperation(1, CausalSendEnvelope.BatchKind.START, 0, 1));
        List<OutboundUnit> invalid = List.of(
                unit(envelope(multiple, CausalSendEnvelope.BundleMetadata.NONE), false, 1),
                unit(envelope(scoped, CausalSendEnvelope.BundleMetadata.NONE), false, 1),
                unit(envelope(acked, CausalSendEnvelope.BundleMetadata.NONE), false, 1),
                unit(envelope(batched, CausalSendEnvelope.BundleMetadata.NONE), false, 1),
                unit(envelope(new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(one), null, null),
                        CausalSendEnvelope.BundleMetadata.NONE), true, 1, 1),
                unit(envelope(new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(one), null, null),
                        CausalSendEnvelope.BundleMetadata.NONE), false, 1, 1),
                unit(envelope(new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(one), null, null),
                        CausalSendEnvelope.BundleMetadata.of(List.of())), false, 1),
                unit(envelope(new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(one), null, null),
                        CausalSendEnvelope.BundleMetadata.NONE), false));
        int heldSentinel = tracker.pendingCount();
        for (OutboundUnit bad : invalid) {
            assertThrows(IllegalStateException.class, bad::requireSupportedCoverage);
        }
        assertEquals(heldSentinel, tracker.pendingCount(), "no token was assigned or suppressed by validation");
    }

    @Test
    void abortAndDiscardReturnReservationsExactlyOnce() {
        ReceiveBudget budget = ReceiveBudget.bounded(100);
        OutboundHold.Sender sender = new OutboundHold.Sender() {
            @Override public boolean writable() { return false; }
            @Override public void send(ProtocolState state, byte[] packet) { }
            @Override public void sendBundle(ProtocolState state, List<byte[]> packets) { }
        };
        OutboundHold hold = new OutboundHold(sender, 4, 100, budget);
        hold.submit(unit(envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY, CausalSendEnvelope.BundleMetadata.NONE), false, 9));
        assertEquals(9, budget.reserved());
        hold.teardown();
        hold.teardown();
        assertEquals(0, budget.reserved());

        BindingSession binding = new BindingSession(BindingStateMachine.Role.SERVER, () -> new byte[32], null,
                BindingStateMachine.MODE_OFFLINE, 775, new byte[32], new byte[32], System::nanoTime, 0L,
                new BindingDriver.Callbacks() {
                    @Override public void send(byte[] payload) { }
                    @Override public void activateOutbound() { }
                    @Override public void activateInbound() { }
                    @Override public void fail(int code, String reason) { }
                }, null, null);
        binding.onConfigurationEntered(System.nanoTime());
        OutboundUnit queued = unit(envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE), false, 7);
        assertEquals(BindingDriver.QueueResult.QUEUED, OutboundQuiescence.offer(binding, queued, budget));
        assertEquals(7, budget.reserved());
        List<Object> discarded = binding.releaseQueued();
        OutboundQuiescence.discard(discarded);
        OutboundQuiescence.discard(discarded);
        assertEquals(0, budget.reserved(), "quiescence discard returns the reservation exactly once");
    }
}
