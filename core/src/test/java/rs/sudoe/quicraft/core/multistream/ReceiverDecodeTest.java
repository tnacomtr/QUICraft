// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Lane;
import static org.junit.jupiter.api.Assertions.*;

class ReceiverDecodeTest {
    private static final long T = ReceiverScheduler.TIMEOUT_NANOS;

    private static Envelope raw(Lane lane, long sequence, long c, long r, long w) {
        return new Envelope(lane, Envelope.Kind.GAME, sequence, new long[]{c, r, w}, 1,
                "opaque framed bytes");
    }

    private static void fails(Runnable action) {
        assertEquals(0x11, assertThrows(OrderingException.class, action::run).closeCode());
    }

    @Test
    void theNodeCreditProbeIsAQueryThatNeverMutatesTheSharedBudget() {
        RecordingBudget budget = new RecordingBudget(ReceiveBudget.bounded(100));
        ReceiverScheduler scheduler = new ReceiverScheduler(
                (envelope, insideBundle, now) -> { }, delivery -> { }, budget);
        assertTrue(scheduler.nodeCreditAvailable(0), "a zero-byte grant is always available");
        assertTrue(scheduler.nodeCreditAvailable(100));
        assertFalse(scheduler.nodeCreditAvailable(101));
        assertEquals(0, budget.tryReserves, "the probe must not reserve");
        assertEquals(0, budget.releases, "the probe must not release");
        assertEquals(2, budget.availableCalls);
        assertEquals(0, budget.reserved());
    }

    /** Wraps a budget and counts what a scheduler asks of it. */
    private static final class RecordingBudget extends ReceiveBudget {
        private final ReceiveBudget delegate;
        private int tryReserves;
        private int releases;
        private int availableCalls;

        RecordingBudget(ReceiveBudget delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean tryReserve(long bytes) {
            tryReserves++;
            return delegate.tryReserve(bytes);
        }

        @Override
        public void release(long bytes) {
            releases++;
            delegate.release(bytes);
        }

        @Override
        public long reserved() {
            return delegate.reserved();
        }

        @Override
        public long available() {
            availableCalls++;
            return delegate.available();
        }
    }

    private static final class Harness {
        final List<Envelope> requests = new ArrayList<Envelope>();
        final List<ReceiverScheduler.Delivery> deliveries = new ArrayList<ReceiverScheduler.Delivery>();
        final ReceiverScheduler scheduler = new ReceiverScheduler(this::decode, this::deliver);
        long stagedControl;

        private void decode(Envelope e, boolean insideBundle, long now) {
            long[] allowed = scheduler.delivered();
            if (insideBundle) {
                assertEquals(Lane.CONTROL, e.lane());
                allowed[0] = stagedControl;
            }
            assertDependencies(e, allowed);
            requests.add(e);
        }

        private void deliver(ReceiverScheduler.Delivery d) {
            long[] allowed = scheduler.delivered();
            for (Envelope e : d.records()) {
                assertDependencies(e, allowed);
                if (d.records().size() > 1) {
                    assertEquals(Lane.CONTROL, e.lane());
                    allowed[0] = e.sequence();
                }
            }
            deliveries.add(d);
        }

        private static void assertDependencies(Envelope e, long[] allowed) {
            for (Lane lane : Lane.values()) {
                assertTrue(Long.compareUnsigned(e.dependency(lane), allowed[lane.ordinal()]) <= 0,
                        "decode/delivery before dependency on " + lane);
            }
        }

        void finish(Envelope.Bundle classification, int size, long retained, boolean state, long now) {
            Envelope e = requests.remove(0);
            scheduler.reserveDecoded(e, retained, size, now);
            if (e.lane() == Lane.CONTROL) {
                stagedControl = e.sequence();
            }
            scheduler.decoded(e, classification, state, "decoded packet", now);
        }

        void apply(long now) {
            ReceiverScheduler.Delivery d = deliveries.remove(0);
            scheduler.applied(d.lane(), d.sequence(), now);
        }
    }

    @Test void opaqueHeadersBecomeBundleOnlyAfterEligibleBudgetedDecoding() {
        Harness h = new Harness();
        Envelope open = raw(Lane.CONTROL, 1, 0, 0, 1);
        Envelope member = raw(Lane.CONTROL, 2, 1, 0, 1);
        Envelope close = raw(Lane.CONTROL, 3, 2, 0, 1);
        Envelope auxiliary = raw(Lane.REALTIME, 1, 2, 0, 0);
        h.scheduler.offer(open, 0);
        h.scheduler.offer(member, 0);
        h.scheduler.offer(close, 0);
        h.scheduler.offer(auxiliary, 0);
        assertTrue(h.requests.isEmpty());
        fails(() -> h.scheduler.reserveDecoded(open, 1, 1, 0));
        h.scheduler.offer(raw(Lane.WORLD, 1, 0, 0, 0), 0);
        h.finish(Envelope.Bundle.NONE, 1, 1, false, 0);
        assertEquals(1, h.deliveries.size());
        assertTrue(h.requests.isEmpty());
        h.apply(0);
        assertSame(open, h.requests.get(0));
        h.finish(Envelope.Bundle.OPEN, 1, 1, false, 0);
        assertSame(member, h.requests.get(0));
        h.finish(Envelope.Bundle.MEMBER, 1, 1, false, 0);
        assertSame(close, h.requests.get(0));
        assertTrue(h.deliveries.isEmpty());
        h.finish(Envelope.Bundle.CLOSE, 1, 1, false, 0);
        assertTrue(h.deliveries.get(0).isBundle());
        assertEquals(3, h.deliveries.get(0).records().size());
        assertEquals("decoded packet", h.deliveries.get(0).records().get(1).body());
        assertArrayEquals(new long[]{0, 0, 1}, h.scheduler.delivered());
        assertTrue(h.requests.isEmpty(), "auxiliary cannot decode against provisional CONTROL");
        assertEquals(4 * 35L + 3, h.scheduler.pendingBytes());
        h.apply(0);
        assertSame(auxiliary, h.requests.get(0));
        h.finish(Envelope.Bundle.NONE, 1, 1, false, 0);
        h.apply(0);
        assertArrayEquals(new long[]{3, 1, 1}, h.scheduler.delivered());
        assertEquals(0, h.scheduler.pendingBytes());
    }

    @Test void metadataAttachedToAdmissionIsNotTrustedAsDecodedClassification() {
        Harness h = new Harness();
        Envelope senderMetadata = new Envelope(Lane.CONTROL, Envelope.Kind.GAME, 1,
                new long[3], 0, null, Envelope.Bundle.OPEN, false);
        h.scheduler.offer(senderMetadata, 0);
        assertSame(senderMetadata, h.requests.get(0));
        h.finish(Envelope.Bundle.NONE, 0, 0, false, 0);
        assertEquals(1, h.deliveries.get(0).records().size());
        assertFalse(h.deliveries.get(0).isBundle());
        assertEquals(Envelope.Bundle.NONE, h.deliveries.get(0).records().get(0).bundle());
    }

    @Test void ordinaryControlApplicationPrecedesDecodingWithTheNextCompressionState() {
        Harness h = new Harness();
        h.scheduler.offer(raw(Lane.CONTROL, 1, 0, 0, 0), 0);
        h.scheduler.offer(raw(Lane.CONTROL, 2, 1, 0, 0), 0);
        h.finish(Envelope.Bundle.NONE, 1, 1, true, 0);
        assertTrue(h.requests.isEmpty());
        assertTrue(h.deliveries.get(0).records().get(0).stateChange());
        h.apply(0);
        assertEquals(2, h.requests.get(0).sequence());
    }

    @Test void stateCompressionOrActivationChangeInDecodedBundleFailsBeforeAnyApplication() {
        Harness h = new Harness();
        h.scheduler.offer(raw(Lane.CONTROL, 1, 0, 0, 0), 0);
        h.finish(Envelope.Bundle.OPEN, 1, 1, false, 0);
        h.scheduler.offer(raw(Lane.CONTROL, 2, 1, 0, 0), 0);
        fails(() -> h.finish(Envelope.Bundle.MEMBER, 1, 1, true, 0));
        assertTrue(h.deliveries.isEmpty());
        assertArrayEquals(new long[3], h.scheduler.delivered());
    }

    @Test void decodingOutputBudgetIsDebitedBeforeAllocationAndHeldUntilApplication() {
        Harness h = new Harness();
        Envelope e = raw(Lane.CONTROL, 1, 0, 0, 0);
        h.scheduler.offer(e, 0);
        long cap = ReceiverScheduler.SHARED_BYTES + ReceiverScheduler.CONTROL_RESERVE_BYTES;
        h.scheduler.reserveDecoded(e, cap - e.wireBytes(), 8 * 1024 * 1024, 0);
        assertEquals(cap, h.scheduler.pendingBytes());
        fails(() -> h.scheduler.reserveDecoded(e, 1, 8 * 1024 * 1024, 0));
        fails(() -> h.scheduler.reserveDecoded(e, 0, 8 * 1024 * 1024 + 1, 0));
        h.scheduler.decoded(e, Envelope.Bundle.NONE, false, "decoded", 0);
        assertEquals(cap, h.scheduler.pendingBytes());
        h.apply(0);
        assertEquals(0, h.scheduler.pendingBytes());
    }

    @Test void decodeAndMissingCloseDeadlinesExpireWithoutReleasingBarriers() {
        Harness decode = new Harness();
        decode.scheduler.offer(raw(Lane.CONTROL, 1, 0, 0, 0), 0);
        decode.scheduler.checkDeadlines(T - 1);
        fails(() -> decode.scheduler.checkDeadlines(T));
        Harness bundle = new Harness();
        bundle.scheduler.offer(raw(Lane.CONTROL, 1, 0, 0, 0), 0);
        bundle.finish(Envelope.Bundle.OPEN, 1, 1, false, 0);
        bundle.scheduler.checkDeadlines(T - 1);
        fails(() -> bundle.scheduler.checkDeadlines(T));
        assertArrayEquals(new long[3], bundle.scheduler.delivered());
    }

    @Test void decodeResultsRequireTheCurrentIdentityAndAnAllocationReservation() {
        Harness h = new Harness();
        Envelope e = raw(Lane.CONTROL, 1, 0, 0, 0);
        h.scheduler.offer(e, 0);
        fails(() -> h.scheduler.decoded(e, Envelope.Bundle.NONE, false, null, 0));
        fails(() -> h.scheduler.reserveDecoded(raw(Lane.CONTROL, 1, 0, 0, 0), 1, 1, 0));
        h.finish(Envelope.Bundle.NONE, 0, 0, false, 0);
        fails(() -> h.scheduler.reserveDecoded(e, 1, 1, 0));
        fails(() -> h.scheduler.decoded(e, Envelope.Bundle.NONE, false, null, 0));
    }

    @Test void stagedMemberCannotDecodeBeforeExternalWorldDependencyCommits() {
        Harness h = new Harness();
        h.scheduler.offer(raw(Lane.CONTROL, 1, 0, 0, 0), 0);
        h.finish(Envelope.Bundle.OPEN, 1, 1, false, 0);
        Envelope member = raw(Lane.CONTROL, 2, 1, 0, 1);
        h.scheduler.offer(member, 0);
        h.scheduler.offer(raw(Lane.CONTROL, 3, 2, 0, 1), 0);
        assertTrue(h.requests.isEmpty());
        h.scheduler.offer(raw(Lane.WORLD, 1, 0, 0, 0), 0);
        h.finish(Envelope.Bundle.NONE, 1, 1, false, 0);
        assertTrue(h.requests.isEmpty());
        h.apply(0);
        assertSame(member, h.requests.get(0));
        h.finish(Envelope.Bundle.MEMBER, 1, 1, false, 0);
        h.finish(Envelope.Bundle.CLOSE, 1, 1, false, 0);
        assertTrue(h.deliveries.get(0).isBundle());
    }

    @Test void aSecondRecordDecodedLongAfterTheFirstIsNotOnTheFirstsDeadline() {
        Harness h = new Harness();
        // A clock that never starts at zero, like the monotonic clock a session injects.
        long t0 = 1_000_000_000L;
        h.scheduler.offer(raw(Lane.CONTROL, 1, 0, 0, 0), t0);
        h.finish(Envelope.Bundle.NONE, 1, 1, false, t0);
        h.apply(t0);
        // A successful decode must not leave its start time behind: a later, healthy record decodes
        // and applies more than one deadline after it (13.12).
        long later = t0 + T + 1;
        h.scheduler.checkDeadlines(later);
        h.scheduler.offer(raw(Lane.CONTROL, 2, 1, 0, 0), later);
        h.finish(Envelope.Bundle.NONE, 1, 1, false, later);
        assertEquals(1, h.deliveries.size(), "the second record is delivered");
        assertEquals(2, h.deliveries.get(0).sequence());
        h.apply(later);
        h.scheduler.checkDeadlines(2 * T + t0);
        assertEquals(0, h.scheduler.pendingBytes(), "nothing is retained after both records");
    }

    @Test void closingDecodedBundleDoesNotPermitDecodingTheFollowingControlBeforeCommit() {
        Harness h = new Harness();
        h.scheduler.offer(raw(Lane.CONTROL, 1, 0, 0, 0), 0);
        h.scheduler.offer(raw(Lane.CONTROL, 2, 1, 0, 0), 0);
        h.scheduler.offer(raw(Lane.CONTROL, 3, 2, 0, 0), 0);
        h.finish(Envelope.Bundle.OPEN, 1, 1, false, 0);
        h.finish(Envelope.Bundle.CLOSE, 1, 1, false, 0);
        assertTrue(h.requests.isEmpty());
        h.apply(0);
        assertEquals(3, h.requests.get(0).sequence());
    }
}
