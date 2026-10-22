// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.time.StallClock;

class StreamReceiverTest {
    private static final long SEC = 1_000_000_000L;
    /** Record kind values (13.4): join records are chunk-only. */
    private static final int ACK_JOIN = 2;
    private static final int BATCH_JOIN = 3;

    static final class Fixture {
        final AtomicLong now = new AtomicLong();
        final StreamReceiver receiver;

        Fixture(int chunks) {
            this(chunks, (s, r, route) -> true);
        }

        Fixture(int chunks, StreamReceiver.Validator validator) {
            receiver = new StreamReceiver(chunks, validator, now::get);
        }
    }

    static Record rec(int stream, long seq, long epoch, Record.Dependency... deps) {
        return new Record(stream, seq, epoch, Arrays.asList(deps), RecordCodec.KIND_GAME, new byte[] {1});
    }

    static Record recKind(int stream, long seq, long epoch, int kind, Record.Dependency... deps) {
        return new Record(stream, seq, epoch, Arrays.asList(deps), kind, new byte[] {1});
    }

    static Record.Dependency dep(int stream, long seq) {
        return new Record.Dependency(stream, seq);
    }

    static ReceiveRoute route(int stream) {
        ReceiveRoute.Lane lane = stream == 0 ? ReceiveRoute.Lane.CONTROL
                : stream == 1 ? ReceiveRoute.Lane.REALTIME : ReceiveRoute.Lane.CHUNK;
        return new ReceiveRoute(lane, Dependency.ORDINARY, null, false, false);
    }

    static ReceiveRoute boundary() {
        return new ReceiveRoute(ReceiveRoute.Lane.CONTROL, Dependency.GLOBAL, null, true, false);
    }

    static StreamReceiver.Admission offer(StreamReceiver rx, Record record) {
        // 50 wire bytes: under the 64-byte cap of the ACK stream (13.21), so any stream index can take it.
        return rx.offer(record, 50, 1);
    }

    static void assertAdmitted(StreamReceiver rx, Record record) {
        StreamReceiver.Admission admission = offer(rx, record);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED, admission.kind(),
                () -> "stream " + record.streamIndex() + " seq " + record.sequence() + " epoch "
                        + record.epoch() + ": " + (admission.close() == null ? "" : admission.close().detail()));
    }

    static void assertClose(ReceiveClose close) {
        assertNotNull(close, "a violation must close the connection");
        assertEquals(0x11, close.errorCode());
    }

    static void assertViolation(StreamReceiver.Admission admission) {
        assertEquals(StreamReceiver.Admission.Kind.VIOLATION, admission.kind());
        assertEquals(0x11, admission.close().errorCode());
    }

    /** Body validation with the default route for the stream, then enqueue (the route is known only now). */
    static void enqueue(StreamReceiver rx, int stream, long sequence) {
        enqueue(rx, stream, sequence, route(stream));
    }

    static void enqueue(StreamReceiver rx, int stream, long sequence, ReceiveRoute route) {
        assertNull(rx.validateEligible(stream, route), "eligible head must validate on stream " + stream);
        rx.enqueued(stream, sequence);
    }

    @Test void everyStreamHoldsOneHeadAndASecondIsRefusedNotViolated() {
        Fixture f = new Fixture(5);
        for (int stream = 0; stream < 8; stream++) {
            assertAdmitted(f.receiver, rec(stream, 1, 0));
        }
        assertEquals(StreamReceiver.Admission.Kind.BUSY, offer(f.receiver, rec(2, 2, 0)).kind());
    }

    @Test void eachPrerequisiteIsWithheldIndependently() {
        for (int blocked = 0; blocked < 4; blocked++) {
            for (int prereq = 0; prereq < 4; prereq++) {
                if (prereq == blocked) {
                    continue;
                }
                Fixture f = new Fixture(2);
                assertAdmitted(f.receiver, rec(prereq, 1, 0));
                assertAdmitted(f.receiver, rec(blocked, 1, 0, dep(prereq, 1)));
                assertEquals(prereq, f.receiver.nextToEnqueue(), "blocked " + blocked + " prereq " + prereq);
                enqueue(f.receiver, prereq, 1);
                f.receiver.applied(prereq, 1);
                assertEquals(blocked, f.receiver.nextToEnqueue());
            }
        }
    }

    @Test void realtimeIsServedFirstWhenBothHeadsAreFullAndReady() {
        Fixture f = new Fixture(2);
        long full = ReceiveSlots.BYTES_PER_STREAM;
        int records = ReceiveSlots.RECORDS_PER_STREAM;
        // REALTIME, CONTROL and a chunk head each hold a complete slot at its byte limit (only CONTROL bundles span records).
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(1, 1, 0), 1, full, 1).kind());
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), records, full, records).kind());
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(2, 1, 0), 1, full, 1).kind());
        assertEquals(1, f.receiver.nextToEnqueue(), "REALTIME before CONTROL and chunks");
        enqueue(f.receiver, 1, 1);
        assertEquals(0, f.receiver.nextToEnqueue(), "CONTROL before the chunk head");
        enqueue(f.receiver, 0, 1);
        assertEquals(2, f.receiver.nextToEnqueue());
    }

    @Test void transitionCarriesTheOldEpochAndApplyAdvancesItByExactlyOne() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), 100, 1).kind());
        enqueue(f.receiver, 0, 1, boundary());
        assertEquals(0, f.receiver.epoch(), "the epoch does not move until D");
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch(), "exactly one increment on apply");
    }

    @Test void transitionWithANewEpochNumberIsAViolation() {
        Fixture f = new Fixture(2);
        assertViolation(f.receiver.offer(rec(0, 1, 1), 100, 1));
    }

    @Test void staleAndFutureEpochsAreViolationsAfterATransition() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), 100, 1).kind());
        enqueue(f.receiver, 0, 1, boundary());
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch());
        assertViolation(offer(f.receiver, rec(2, 1, 0)));
        assertViolation(offer(f.receiver, rec(2, 1, 3)));
    }

    @Test void newEpochRecordsArriveEarlyButStayOpaqueUntilTheTransitionApplies() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 1, dep(0, 1)));
        assertEquals(-1, f.receiver.nextToEnqueue(), "held until the epoch opens");
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), 100, 1).kind());
        enqueue(f.receiver, 0, 1, boundary());
        assertEquals(-1, f.receiver.nextToEnqueue(), "new-epoch chunk still waits for the transition");
        f.receiver.applied(0, 1);
        assertEquals(2, f.receiver.nextToEnqueue());
    }

    @Test void epochTwoAheadIsFutureEvenWithAPendingTransition() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), 100, 1).kind());
        assertViolation(offer(f.receiver, rec(2, 1, 2)));
    }

    @Test void secondTransitionWhileOneIsPendingIsRefusedByTheControlHead() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(0, 1, 0));
        assertNull(f.receiver.validateEligible(0, boundary()));
        // Transitions are CONTROL-only, so a held transition blocks the next CONTROL head (BUSY, not a violation).
        assertEquals(StreamReceiver.Admission.Kind.BUSY, f.receiver.offer(rec(0, 2, 0), 100, 1).kind());
    }

    @Test void wrongShardIsAViolationFromTheInjectedValidator() {
        Fixture f = new Fixture(2, (s, r, route) -> false);
        assertAdmitted(f.receiver, rec(3, 1, 0));
        assertClose(f.receiver.validateEligible(3, route(3)));
    }

    @Test void skippedAndDuplicateSequencesAreViolations() {
        Fixture f = new Fixture(2);
        assertViolation(offer(f.receiver, rec(2, 2, 0)));
        assertAdmitted(f.receiver, rec(2, 1, 0));
        enqueue(f.receiver, 2, 1);
        f.receiver.applied(2, 1);
        assertViolation(offer(f.receiver, rec(2, 1, 0)));
    }

    @Test void sameStreamUnsortedAndOutOfRangeDependenciesAreViolations() {
        Fixture f = new Fixture(2);
        assertViolation(offer(f.receiver, rec(2, 1, 0, dep(2, 1))));
        assertViolation(offer(f.receiver, rec(2, 1, 0, dep(9, 1))));
        assertViolation(offer(f.receiver, rec(2, 1, 0, dep(3, 1), dep(0, 1))));
    }

    @Test void joinKindRecordsLiveOnlyOnChunkZero() {
        Fixture f = new Fixture(2);
        ReceiveRoute join = new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.ACK_JOIN, null, false, true);
        assertViolation(f.receiver.offer(recKind(3, 1, 0, ACK_JOIN), 100, 1));
        assertViolation(f.receiver.offer(recKind(1, 1, 0, BATCH_JOIN), 100, 1));
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(recKind(2, 1, 0, BATCH_JOIN), 100, 1).kind());
        assertNull(f.receiver.validateEligible(2, join));
    }

    @Test void joinRouteWithANonJoinKindIsAViolation() {
        Fixture f = new Fixture(2);
        ReceiveRoute join = new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.ACK_JOIN, null, false, true);
        assertAdmitted(f.receiver, rec(2, 1, 0));
        assertClose(f.receiver.validateEligible(2, join));
    }

    @Test void joinKindWithANonJoinRouteIsAViolation() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, recKind(2, 1, 0, ACK_JOIN));
        assertClose(f.receiver.validateEligible(2, route(2)));
    }

    @Test void epochPlusOneWithoutAnyTransitionPathIsAViolation() {
        Fixture f = new Fixture(2);
        assertViolation(offer(f.receiver, rec(2, 1, 1)));
    }

    @Test void epochPlusOneAfterTransitionAppliedWithoutPathIsAViolation() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), 100, 1).kind());
        enqueue(f.receiver, 0, 1, boundary());
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch());
        assertViolation(offer(f.receiver, rec(2, 1, 2)));
        assertViolation(offer(f.receiver, rec(2, 1, 2, dep(0, 1))));
    }

    @Test void epochPlusOneWithAPendingTransitionIsAdmitted() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), 100, 1).kind());
        assertAdmitted(f.receiver, rec(2, 1, 1));
    }

    @Test void bundleAdvancesTheFrontierToItsClosingSequenceOnEnqueueAndApply() {
        Fixture f = new Fixture(2);
        // CONTROL bundle: opener at 1, one member at 2, closer at 3, admitted as one unit.
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), 3, 300, 3).kind());
        assertEquals(0, f.receiver.nextToEnqueue());
        // A later record on the same stream must open at closing + 1, not at opening + 1.
        assertViolation(offer(f.receiver, rec(0, 2, 0)));
        // A dependency on the bundle's closing sequence waits for the whole bundle to apply.
        assertAdmitted(f.receiver, rec(1, 1, 0, dep(0, 3)));
        enqueue(f.receiver, 0, 1);
        assertEquals(3, f.receiver.enqueuedWatermark(0));
        assertEquals(-1, f.receiver.nextToEnqueue(), "closing sequence is not applied yet");
        f.receiver.applied(0, 1);
        assertEquals(3, f.receiver.appliedWatermark(0));
        assertEquals(1, f.receiver.nextToEnqueue(), "dependency on the closer is released by apply");
        // The next CONTROL unit opens at 4.
        assertAdmitted(f.receiver, rec(0, 4, 0));
    }

    @Test void unitWithAWrongRecordCountOrClosingSequenceIsAViolation() {
        Fixture f = new Fixture(2);
        assertViolation(f.receiver.offer(rec(0, 1, 0), 3, 300, 2));
        assertViolation(f.receiver.offer(rec(0, 1, 0), 0, 300, 1));
        assertViolation(f.receiver.offer(rec(0, 1, 0), 1, 300, 0));
        assertAdmitted(f.receiver, rec(0, 1, 0));
    }

    @Test void multiRecordUnitOutsideClientboundControlIsAViolation() {
        Fixture f = new Fixture(2);
        assertViolation(f.receiver.offer(rec(1, 1, 0), 3, 300, 3));
        Fixture g = new Fixture(2);
        assertViolation(g.receiver.offer(rec(2, 1, 0), 2, 200, 2));
        StreamReceiver rx = new StreamReceiver(2, Direction.SERVERBOUND, (s, r, route) -> true, () -> 0L);
        assertViolation(rx.offer(rec(0, 1, 0), 3, 300, 3));
    }

    @Test void aBlockedNonControlHeadIsNoTransitionPath() {
        Fixture f = new Fixture(2);
        // REALTIME head at epoch 0 waits on a CONTROL record that never comes; it can never be a transition.
        assertAdmitted(f.receiver, rec(1, 1, 0, dep(0, 1)));
        assertViolation(offer(f.receiver, rec(2, 1, 1)));
    }

    @Test void transitionRouteOnAChunkHeadIsAViolation() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0));
        assertClose(f.receiver.validateEligible(2,
                new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.ORDINARY, null, true, false)));
    }

    @Test void admissionNeverRunsTheBodyValidatorAndAnEligibleHeadRunsItExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        Fixture f = new Fixture(2, (s, r, route) -> {
            calls.incrementAndGet();
            return true;
        });
        assertAdmitted(f.receiver, rec(0, 1, 0));
        assertAdmitted(f.receiver, rec(1, 1, 1, dep(0, 1)));
        assertAdmitted(f.receiver, rec(2, 1, 1));
        assertEquals(0, calls.get(), "blocked and future-epoch admission must not touch the body");
        assertNull(f.receiver.validateEligible(0, boundary()));
        assertEquals(1, calls.get());
        assertNull(f.receiver.validateEligible(0, boundary()), "a second call is a no-op");
        assertEquals(1, calls.get(), "the validator runs exactly once per head");
        f.receiver.enqueued(0, 1);
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.nextToEnqueue(), "REALTIME waits on its dependency, then is eligible");
        assertNull(f.receiver.validateEligible(1, route(1)));
        assertEquals(2, calls.get());
        assertNull(f.receiver.validateEligible(2, route(2)));
        assertEquals(3, calls.get(), "the epoch+1 record is validated once the transition applied");
    }

    @Test void serverboundReceiverRejectsEveryChunkStreamRecord() {
        StreamReceiver rx = new StreamReceiver(2, Direction.SERVERBOUND,
                (s, r, route) -> true, () -> 0L);
        assertViolation(offer(rx, rec(2, 1, 0)));
        assertViolation(offer(rx, rec(3, 1, 0)));
        assertViolation(offer(rx, recKind(2, 1, 0, BATCH_JOIN)));
        assertAdmitted(rx, rec(0, 1, 0));
        assertViolation(offer(rx, rec(1, 1, 0, dep(2, 1))));
        assertViolation(offer(rx, rec(1, 1, 0, dep(3, 1))));
        assertAdmitted(rx, rec(1, 1, 0));
    }

    @Test void serverboundReceiverAllowsEpochZeroOnly() {
        StreamReceiver rx = new StreamReceiver(2, Direction.SERVERBOUND,
                (s, r, route) -> true, () -> 0L);
        assertViolation(offer(rx, rec(1, 1, 1)));
        assertAdmitted(rx, rec(0, 1, 0));
        assertClose(rx.validateEligible(0, boundary()));
    }

    @Test void serverboundTransitionRouteIsAViolation() {
        StreamReceiver rx = new StreamReceiver(2, Direction.SERVERBOUND,
                (s, r, route) -> true, () -> 0L);
        assertAdmitted(rx, rec(0, 1, 0));
        assertClose(rx.validateEligible(0, boundary()));
    }

    @Test void provableCyclesCloseWithZeroX11() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        assertViolation(offer(f.receiver, rec(3, 1, 0, dep(2, 1))));
    }

    @Test void cycleThroughAnUnseenFutureRecordIsDetected() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 2)));
        assertViolation(offer(f.receiver, rec(3, 1, 0, dep(2, 1))));
    }

    @Test void chainWithoutBackEdgeIsAdmitted() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        assertAdmitted(f.receiver, rec(3, 1, 0));
    }

    @Test void blockedHeadExpiresAfterTenSecondsWithThePhrase() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        f.now.set(9 * SEC);
        assertNull(f.receiver.expiredWait());
        f.now.set(10 * SEC);
        ReceiveClose close = f.receiver.expiredWait();
        assertEquals(0x12, close.errorCode());
        assertEquals("unseen-prerequisite", new String(close.reason(), java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test void stallOrLocalWithholdingExpiryDropsThePhrase() {
        Fixture stalled = new Fixture(2);
        assertAdmitted(stalled.receiver, rec(2, 1, 0, dep(3, 1)));
        stalled.receiver.stallStarted();
        stalled.now.set(10 * SEC);
        stalled.receiver.stallEnded();
        stalled.now.set(20 * SEC);
        ReceiveClose consumed = stalled.receiver.expiredWait();
        assertNotNull(consumed, "10 s of stall excluded, 10 s active");
        assertEquals(0x12, consumed.errorCode());
        assertNull(consumed.reason(), "recorded stall time blocks the phrase");

        Fixture withheld = new Fixture(2);
        assertAdmitted(withheld.receiver, rec(2, 1, 0, dep(3, 1)));
        withheld.receiver.markLocallyWithheld(3, 1);
        withheld.now.set(10 * SEC);
        ReceiveClose local = withheld.receiver.expiredWait();
        assertNotNull(local);
        assertNull(local.reason(), "local withholding blocks the phrase");
    }

    /** The local diagnostic of an expiry, which every expiry close carries. */
    private static String diagnosticOf(ReceiveClose close) {
        assertNotNull(close, "expected an expiry");
        assertNotNull(close.diagnostic(), "an expiry carries the local diagnostic");
        return close.diagnostic();
    }

    @Test void expiryDiagnosticReportsAHeldReadyEnqueuedPrerequisiteAndTheExecutingHead() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(1, 1, 0));
        enqueue(f.receiver, 1, 1);
        assertAdmitted(f.receiver, rec(0, 1, 0, dep(1, 1)));
        f.now.set(10 * SEC);
        String detail = diagnosticOf(f.receiver.expiredWait());
        assertTrue(detail.contains("unresolved=[stream=1 need=1 applied=0 queued=1 admitted=1 held=yes ready=yes enq=yes seq=1]"),
                detail);
        assertTrue(detail.endsWith(" executing=1:1 executor-queued-units=1 ms-since-last-apply-any-stream=none"
                + " last-renewal-source=none"), detail);
    }

    @Test void expiryDiagnosticTellsAHeldUnitThatIsNotReadyFromAMissingOne() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(1, 1, 0, dep(3, 1)));
        assertAdmitted(f.receiver, rec(0, 1, 0, dep(1, 1), dep(2, 1)));
        f.now.set(10 * SEC);
        String detail = diagnosticOf(f.receiver.expiredWait());
        assertTrue(detail.startsWith("expiry=no-progress lapsed=no head=0:1 "), detail);
        assertTrue(detail.contains("stream=1 need=1 applied=0 queued=0 admitted=1 held=yes ready=no enq=no seq=1"), detail);
        assertTrue(detail.contains("stream=2 need=1 applied=0 queued=0 admitted=0 held=no ready=no enq=no seq=none"),
                detail);
        assertTrue(detail.endsWith(" executing=none executor-queued-units=0 ms-since-last-apply-any-stream=none"
                + " last-renewal-source=none"), detail);
    }

    @Test void expiryDiagnosticNamesTheLastApplyAndTheCommitThatRenewedTheWait() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(1, 1), dep(3, 1)));
        assertAdmitted(f.receiver, rec(3, 1, 0));
        enqueue(f.receiver, 3, 1);
        f.now.set(5 * SEC);
        f.receiver.applied(3, 1);
        f.now.set(15 * SEC);
        String detail = diagnosticOf(f.receiver.expiredWait());
        assertTrue(detail.contains("unresolved=[stream=1 need=1 applied=0 queued=0 admitted=0 held=no ready=no enq=no"
                + " seq=none]"), detail);
        assertTrue(detail.contains("blocked-ms=15000 since-renewal-ms=10000"), detail);
        assertTrue(detail.endsWith(" executing=none executor-queued-units=0 ms-since-last-apply-any-stream=10000"
                + " last-renewal-source=3:1"), detail);
    }

    @Test void cumulativeReceiverTimesTheLastApplyWithoutTheStallSource() {
        final AtomicLong clock = new AtomicLong();
        StallClock source = new StallClock() {
            @Override public Sample sample() {
                final long reading = clock.get();
                return new Sample() {
                    @Override public long nowNanos() { return reading; }
                    @Override public long excludedNanos() { return 0; }
                    @Override public boolean valid() { return true; }
                };
            }
        };
        StreamReceiver rx = new StreamReceiver(2, Direction.CLIENTBOUND, (s, r, route) -> true, source);
        assertAdmitted(rx, rec(2, 1, 0, dep(1, 1)));
        assertAdmitted(rx, rec(3, 1, 0));
        enqueue(rx, 3, 1);
        clock.set(4 * SEC);
        rx.applied(3, 1);
        clock.set(10 * SEC);
        // The apply's time comes from System.nanoTime(), a different timebase from the stall source, so only the
        // format and the absence of "none" are fixed here.
        String detail = diagnosticOf(rx.expiredWait());
        assertTrue(detail.matches("(?s).*ms-since-last-apply-any-stream=\\d+ last-renewal-source=none"), detail);
    }

    @Test void applyWithoutADependencyBlockedHeadReadsNoSourceEvenWhenReadsFail() {
        FailingSource source = new FailingSource();
        StreamReceiver rx = cumulative(Direction.CLIENTBOUND, source);
        assertAdmitted(rx, rec(3, 1, 0));
        enqueue(rx, 3, 1);
        int before = source.reads;
        source.failing = true;
        rx.applied(3, 1);
        assertEquals(before, source.reads, "an apply with no blocked head takes no stall-source reading");
        assertNull(rx.head(3), "the apply committed");
        assertNull(rx.pendingViolation());
    }

    @Test void expiryCloseIsUnchangedUnderAThrowingLogHandler() {
        java.util.logging.Logger quicraft = java.util.logging.Logger.getLogger("QUICraft");
        java.util.logging.Level priorLevel = quicraft.getLevel();
        java.util.logging.Handler broken = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                throw new IllegalStateException("broken log handler");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        quicraft.setLevel(java.util.logging.Level.INFO);
        quicraft.addHandler(broken);
        try {
            Fixture f = new Fixture(2);
            assertAdmitted(f.receiver, rec(0, 1, 0, dep(1, 1)));
            f.now.set(10 * SEC);
            ReceiveClose close = f.receiver.expiredWait();
            assertNotNull(close, "the expiry still closes the receiver");
            assertEquals(ReceiveClose.RESOURCE, close.errorCode());
            assertTrue(close.diagnostic() != null, "the local diagnostic is still built");
        } finally {
            quicraft.removeHandler(broken);
            quicraft.setLevel(priorLevel);
        }
    }

    @Test void enqueueStopsTheTimerAndOnlyApplyReleasesDependencies() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0));
        assertAdmitted(f.receiver, rec(3, 1, 0, dep(2, 1)));
        enqueue(f.receiver, 2, 1);
        assertEquals(1, f.receiver.enqueuedWatermark(2));
        assertEquals(0, f.receiver.appliedWatermark(2));
        assertEquals(-1, f.receiver.nextToEnqueue(), "Q must not release the dependent");
        f.receiver.applied(2, 1);
        assertEquals(1, f.receiver.appliedWatermark(2));
        assertEquals(3, f.receiver.nextToEnqueue());

        Fixture late = new Fixture(2);
        assertAdmitted(late.receiver, rec(2, 1, 0));
        assertAdmitted(late.receiver, rec(3, 1, 0, dep(2, 1)));
        enqueue(late.receiver, 2, 1);
        late.now.set(100 * SEC);
        ReceiveClose waiting = late.receiver.expiredWait();
        assertNotNull(waiting, "the dependent stays blocked until D, so its own wait expires");
        assertEquals(0x12, waiting.errorCode());
        late.receiver.applied(2, 1);
        assertEquals(0, late.receiver.appliedWatermark(2), "a lapsed wait is terminal: the commit is not applied");
        assertEquals(0x12, late.receiver.pendingViolation().errorCode());
    }

    @Test void headAdmittedDuringAStallStartsExcludedUntilTheStallEnds() {
        Fixture f = new Fixture(2);
        f.receiver.stallStarted();
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        f.now.set(10 * SEC);
        assertNull(f.receiver.expiredWait(), "the stall is excluded while it lasts");
        f.receiver.stallEnded();
        f.now.set(15 * SEC);
        assertNull(f.receiver.expiredWait(), "5 s of active time after the stall");
        f.now.set(20 * SEC);
        ReceiveClose close = f.receiver.expiredWait();
        assertNotNull(close);
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason(), "consumed stall time blocks the phrase");
    }

    @Test void aReadyHeadNeverStartsOrKeepsAnExpiringTimer() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0));
        f.now.set(100 * SEC);
        assertNull(f.receiver.expiredWait(), "a ready head is not waiting on a prerequisite");
    }

    @Test void aHeadThatBecomesReadyStopsItsTimerBeforeQ() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(3, 1, 0));
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        enqueue(f.receiver, 3, 1);
        f.receiver.applied(3, 1);
        f.now.set(100 * SEC);
        assertNull(f.receiver.expiredWait(), "resolved prerequisites stop the wait");
        assertEquals(2, f.receiver.nextToEnqueue());
    }

    @Test void withholdingAnUnrelatedRecordDoesNotDropThePhrase() {
        Fixture f = new Fixture(2);
        f.receiver.markLocallyWithheld(0, 1);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        f.now.set(10 * SEC);
        ReceiveClose close = f.receiver.expiredWait();
        assertEquals("unseen-prerequisite", new String(close.reason(), java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test void withholdingIsNotClearedByAnUnrelatedAdmission() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        f.receiver.markLocallyWithheld(3, 1);
        assertAdmitted(f.receiver, rec(0, 1, 0));
        f.now.set(10 * SEC);
        ReceiveClose close = f.receiver.expiredWait();
        assertNotNull(close);
        assertNull(close.reason(), "a withheld prerequisite on the chain blocks the phrase");
    }

    @Test void withholdingTheExpiringRecordItselfBlocksThePhrase() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        f.receiver.markLocallyWithheld(2, 1);
        f.now.set(10 * SEC);
        assertNull(f.receiver.expiredWait().reason());
    }

    @Test void withholdingHistorySurvivesTheWithheldPrerequisiteApplying() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(0, 1), dep(1, 1)));
        f.receiver.markLocallyWithheld(0, 1);
        assertAdmitted(f.receiver, rec(0, 1, 0));
        enqueue(f.receiver, 0, 1);
        f.receiver.applied(0, 1);
        f.now.set(10 * SEC);
        ReceiveClose close = f.receiver.expiredWait();
        assertNotNull(close, "REALTIME 1 is still missing, so the wait expires");
        assertNull(close.reason(), "CONTROL 1 was withheld during this wait, so no phrase (13.12)");
    }

    @Test void fullSlotsAtSixChunksExpireEveryWithheldPrerequisiteWithItsOwnCode() {
        for (int prereq = 0; prereq < 8; prereq++) {
            assertAcceptanceCase(prereq, false);
            assertAcceptanceCase(prereq, true);
        }
    }

    /** N=5, all eight streams at their slot limit; one dependent waits on prereq, which is withheld. */
    private static void assertAcceptanceCase(int prereq, boolean locallyWithheld) {
        int blocked = (prereq + 1) % 8;
        Fixture f = new Fixture(5);
        long full = ReceiveSlots.BYTES_PER_STREAM;
        int records = ReceiveSlots.RECORDS_PER_STREAM;
        for (int s = 0; s < 8; s++) {
            Record r = s == blocked ? rec(s, 1, 0, dep(prereq, 1)) : rec(s, 1, 0);
            // The ACK stream (7) has its own small slot, so it is "full" at one record of ACK_RECORD_MAX_BYTES (13.21).
            long wire = s == 7 ? ReceiveSlots.ACK_RECORD_MAX_BYTES : full;
            assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                    s == 0 ? f.receiver.offer(r, records, full, records).kind() : f.receiver.offer(r, 1, wire, 1).kind(),
                    "stream " + s + " prereq " + prereq);
        }
        if (locallyWithheld) {
            f.receiver.markLocallyWithheld(prereq, 1);
        }
        f.now.set(10 * SEC);
        ReceiveClose close = f.receiver.expiredWait();
        String label = "prereq " + prereq + " withheld " + locallyWithheld;
        assertNotNull(close, label);
        assertEquals(0x12, close.errorCode(), label);
        if (locallyWithheld) {
            assertNull(close.reason(), label);
        } else {
            assertEquals("unseen-prerequisite", new String(close.reason(), java.nio.charset.StandardCharsets.US_ASCII), label);
        }
    }

    @Test void heldEpochPlusOneHeadWithoutAnyTransitionIsAViolationOnceItsPrerequisiteApplies() {
        Fixture f = new Fixture(2);
        // The CHUNK head at epoch 1 is admitted while REALTIME 1 is unseen, so a path still exists.
        assertAdmitted(f.receiver, rec(2, 1, 1, dep(1, 1)));
        assertAdmitted(f.receiver, rec(1, 1, 0));
        enqueue(f.receiver, 1, 1);
        f.receiver.applied(1, 1);
        // No transition was admitted, so the held head can never reach its epoch: 0x11, not a 0x12 expiry.
        ReceiveClose close = f.receiver.pendingViolation();
        assertNotNull(close, "the apply must surface the violation");
        assertEquals(0x11, close.errorCode());
        assertEquals(StreamReceiver.Admission.Kind.VIOLATION, offer(f.receiver, rec(0, 1, 0)).kind(),
                "the violation is sticky for later offers");
    }

    @Test void heldEpochPlusOneHeadIsReleasedWhenATransitionArrivesBeforeTheApply() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 1, dep(1, 1)));
        assertAdmitted(f.receiver, rec(1, 1, 0));
        enqueue(f.receiver, 1, 1);
        // The transition arrives while REALTIME 1 is still held, so the path is reachable again.
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), 100, 1).kind());
        f.receiver.applied(1, 1);
        assertNull(f.receiver.pendingViolation(), "a pending transition keeps the head legal");
        enqueue(f.receiver, 0, 1, boundary());
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch());
        assertNull(f.receiver.pendingViolation());
        assertEquals(2, f.receiver.nextToEnqueue(), "the chunk head is released normally");
    }

    @Test void lapsedExpiryClosesBeforeAPostCommitOrderingViolation() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 1, dep(1, 1)));
        assertAdmitted(f.receiver, rec(1, 1, 0));
        enqueue(f.receiver, 1, 1);
        // Without the lapsed wait, this apply would raise 0x11 (no transition path to epoch 1, 13.18).
        f.now.set(10 * SEC);
        f.receiver.applied(1, 1);
        assertUnseen(f.receiver.pendingViolation());
        assertNotNull(f.receiver.head(1), "the lapsed commit changed no state");
        assertNotNull(f.receiver.head(2), "the waiting head is still held for the close");
    }

    @Test void settlementReadsTheSourceOnceAndRenewsFromThatReading() {
        SteppingSource source = new SteppingSource(0, 9 * SEC, 20 * SEC);
        StreamReceiver rx = cumulative(Direction.CLIENTBOUND, source);
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1000)));
        int before = source.samples;
        commit(rx, rec(3, 1, 0), 64 * KIB, route(3));
        assertEquals(before + 1, source.samples, "one source reading for the whole settlement");
        // The renewal used the 9 s settlement reading. The next reading (20 s) is 11 s past it, so the base has
        // expired. Had the renewal taken its own reading at 20 s, the base would still be live here.
        assertUnseen(rx.expiredWait());
    }

    @Test void expiryAtTheSettlementReadingIsNeverRenewed() {
        SteppingSource source = new SteppingSource(0, 20 * SEC);
        StreamReceiver rx = cumulative(Direction.CLIENTBOUND, source);
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1000)));
        assertAdmitted(rx, rec(3, 1, 0));
        enqueue(rx, 3, 1, route(3));
        rx.applied(3, 1);
        assertUnseen(rx.pendingViolation());
        assertNotNull(rx.head(3), "the lapsed commit changed no state");
    }

    @Test void unavailableSourceDuringSettlementClosesGenericWithNoStateChange() {
        ScriptedSource source = new ScriptedSource().set(0, 0);
        int[] validatorCalls = new int[1];
        StreamReceiver rx = new StreamReceiver(2, Direction.CLIENTBOUND,
                (s, r, route) -> { validatorCalls[0]++; return true; }, source);
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1000)));
        assertAdmitted(rx, rec(3, 1, 0));
        enqueue(rx, 3, 1, route(3));
        int callsBefore = validatorCalls[0];
        source.valid = false;
        rx.applied(3, 1);
        ReceiveClose close = rx.pendingViolation();
        assertNotNull(close, "the unavailable source must close the connection");
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason(), "an unavailable source is a generic resource close, no phrase");
        assertEquals(callsBefore, validatorCalls[0], "the commit made no callback");
        assertNotNull(rx.head(3), "the commit did not run");
        assertNotNull(rx.head(2), "the waiting head is untouched");
    }

    @Test void cumulativeConstructorsRejectANullSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new StreamReceiver(2, Direction.CLIENTBOUND, (s, r, route) -> true, (StallClock) null));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamReceiver(2, new ReceiveSlots(2), (s, r, route) -> true, (StallClock) null));
    }

    @Test void cumulativeReceiverCoversBothDirectionsFromOneSharedSource() {
        ScriptedSource source = new ScriptedSource();
        StreamReceiver clientbound = cumulative(Direction.CLIENTBOUND, source);
        StreamReceiver serverbound = cumulative(Direction.SERVERBOUND, source);
        assertAdmitted(clientbound, rec(2, 1, 0, dep(3, 1)));
        assertAdmitted(serverbound, rec(1, 1, 0, dep(0, 1)));
        source.set(15 * SEC, 10 * SEC);
        assertNull(clientbound.expiredWait(), "5 s of active time on the shared source");
        assertNull(serverbound.expiredWait());
        source.set(20 * SEC, 10 * SEC);
        ReceiveClose clientClose = clientbound.expiredWait();
        ReceiveClose serverClose = serverbound.expiredWait();
        assertNotNull(clientClose);
        assertNotNull(serverClose);
        assertEquals(0x12, clientClose.errorCode());
        assertEquals(0x12, serverClose.errorCode());
        assertNull(clientClose.reason(), "consumed stall time blocks the phrase");
        assertNull(serverClose.reason());
    }

    @Test void cumulativeWaitStartingInsideAnOpenStallExcludesOnlyItsOwnStallTime() {
        ScriptedSource source = new ScriptedSource().set(5 * SEC, 4 * SEC);
        StreamReceiver rx = cumulative(Direction.CLIENTBOUND, source);
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1)));
        source.set(9 * SEC, 8 * SEC);
        assertNull(rx.expiredWait(), "the stall time before the wait started is not excluded");
        source.set(16 * SEC, 8 * SEC);
        assertNull(rx.expiredWait(), "7 s of active time after the stall ended");
        source.set(19 * SEC, 8 * SEC);
        ReceiveClose close = rx.expiredWait();
        assertNotNull(close);
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason());
    }

    @Test void cumulativeLateObservationMatchesFrequentObservation() {
        ScriptedSource frequent = new ScriptedSource();
        ScriptedSource late = new ScriptedSource();
        StreamReceiver observed = cumulative(Direction.CLIENTBOUND, frequent);
        StreamReceiver unobserved = cumulative(Direction.CLIENTBOUND, late);
        assertAdmitted(observed, rec(2, 1, 0, dep(3, 1)));
        assertAdmitted(unobserved, rec(2, 1, 0, dep(3, 1)));
        frequent.set(4 * SEC, 0);
        assertNull(observed.expiredWait());
        frequent.set(8 * SEC, 4 * SEC);
        assertNull(observed.expiredWait(), "4 s of active time");
        frequent.set(12 * SEC, 5 * SEC);
        assertNull(observed.expiredWait(), "7 s of active time");
        frequent.set(15 * SEC, 5 * SEC);
        ReceiveClose frequently = observed.expiredWait();
        late.set(15 * SEC, 5 * SEC);
        ReceiveClose once = unobserved.expiredWait();
        assertNotNull(frequently);
        assertNotNull(once, "a late single observation excludes the same 5 s");
        assertEquals(frequently.errorCode(), once.errorCode());
        assertEquals(frequently.unseenPrerequisite(), once.unseenPrerequisite());
    }

    @Test void cumulativeStallCompletedBetweenObservationsStillExcludes() {
        ScriptedSource source = new ScriptedSource().set(2 * SEC, 0);
        StreamReceiver rx = cumulative(Direction.CLIENTBOUND, source);
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1)));
        source.set(15 * SEC, 7 * SEC);
        assertNull(rx.expiredWait(), "a 7 s stall completed entirely between the two observations");
        source.set(19 * SEC, 7 * SEC);
        ReceiveClose close = rx.expiredWait();
        assertNotNull(close);
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason(), "consumed stall time blocks the phrase");
    }

    @Test void cumulativeDeadlineCapAndResourceClassificationMatchTheBoundaryRules() {
        ScriptedSource source = new ScriptedSource();
        StreamReceiver rx = cumulative(Direction.CLIENTBOUND, source);
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1)));
        source.set(65 * SEC, 65 * SEC);
        assertNull(rx.expiredWait(), "60 of the 65 s is excluded, leaving 5 s active");
        source.set(70 * SEC, 70 * SEC);
        ReceiveClose capped = rx.expiredWait();
        assertNotNull(capped);
        assertEquals(0x12, capped.errorCode());
        assertNull(capped.reason(), "consumed stall time blocks the phrase");

        ScriptedSource plain = new ScriptedSource();
        StreamReceiver waiting = cumulative(Direction.CLIENTBOUND, plain);
        assertAdmitted(waiting, rec(2, 1, 0, dep(3, 1)));
        plain.set(10 * SEC, 0);
        ReceiveClose unseen = waiting.expiredWait();
        assertNotNull(unseen, "10 s of transport time without any stall time");
        assertEquals(0x12, unseen.errorCode());
        assertEquals("unseen-prerequisite",
                new String(unseen.reason(), java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test void cumulativeReceiverRejectsBoundaryHooksEvenWithNoHeads() {
        ScriptedSource source = new ScriptedSource();
        StreamReceiver rx = cumulative(Direction.CLIENTBOUND, source);
        assertThrows(IllegalStateException.class, rx::stallStarted);
        assertThrows(IllegalStateException.class, () -> rx.stallStarted(5 * SEC));
        assertThrows(IllegalStateException.class, rx::stallEnded);
        assertThrows(IllegalStateException.class, () -> rx.stallEnded(6 * SEC));
        assertEquals(0, source.samples, "a rejected boundary hook takes no source reading");
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1)));
        assertThrows(IllegalStateException.class, rx::stallStarted, "a held head changes nothing");
        assertThrows(IllegalStateException.class, () -> rx.stallEnded(6 * SEC));
        source.set(10 * SEC, 0);
        ReceiveClose close = rx.expiredWait();
        assertNotNull(close, "the rejected hooks changed no state");
        assertEquals(0x12, close.errorCode());
        assertEquals("unseen-prerequisite",
                new String(close.reason(), java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test void invalidCumulativeSourcePropagatesWithoutAFallbackOutcome() {
        ScriptedSource invalid = new ScriptedSource();
        invalid.valid = false;
        StreamReceiver refused = cumulative(Direction.CLIENTBOUND, invalid);
        assertThrows(StallClock.UnavailableException.class, () -> offer(refused, rec(2, 1, 0, dep(3, 1))));
        assertNull(refused.head(2), "a rejected start publishes no head");
        assertFalse(refused.slots().holdsHead(2), "a rejected start charges no slot");

        ScriptedSource goingInvalid = new ScriptedSource();
        StreamReceiver running = cumulative(Direction.CLIENTBOUND, goingInvalid);
        assertAdmitted(running, rec(2, 1, 0, dep(3, 1)));
        goingInvalid.valid = false;
        assertThrows(StallClock.UnavailableException.class, running::expiredWait);
        assertNull(running.pendingViolation(), "an unavailable source is not a peer ordering violation");
        assertThrows(StallClock.UnavailableException.class, running::expiredWait,
                "the invalid incarnation stays unavailable");
    }

    @Test void legacyTimestampedStallHooksStillExcludeThroughTheBoundaryClock() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        f.receiver.stallStarted(0);
        f.now.set(10 * SEC);
        f.receiver.stallEnded(10 * SEC);
        f.now.set(20 * SEC);
        ReceiveClose close = f.receiver.expiredWait();
        assertNotNull(close, "10 s of stall excluded, 10 s active");
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason(), "consumed stall time blocks the phrase");
    }

    @Test void refusedLegacySlotReturnsBusyWithoutReadingTheClock() {
        CountingClock clock = new CountingClock();
        StreamReceiver rx = new StreamReceiver(2, (s, r, route) -> true, clock);
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1)));
        assertEquals(1, clock.reads, "the blocked wait start read the clock once");
        rx.discard(); // drops the head; by contract it releases no slot
        clock.armed = true; // any further read is a regression
        assertEquals(StreamReceiver.Admission.Kind.BUSY, offer(rx, rec(2, 2, 0, dep(3, 1))).kind());
        assertEquals(1, clock.reads, "a refused slot must perform zero clock reads");
    }

    @Test void legacyNoArgBoundaryHooksReadEachRunningTimersClockAndNothingWithoutHeads() {
        CountingClock clock = new CountingClock();
        StreamReceiver rx = new StreamReceiver(2, (s, r, route) -> true, clock);
        rx.stallStarted();
        rx.stallEnded();
        assertEquals(0, clock.reads, "a receiver with no heads reads no clock at all");

        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1)));
        rx.stallStarted();
        clock.nowNanos = 10 * SEC;
        rx.stallEnded();
        clock.nowNanos = 20 * SEC;
        ReceiveClose close = rx.expiredWait();
        assertNotNull(close, "the per-timer no-arg hooks recorded the stall");
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason(), "consumed stall time blocks the phrase");
    }

    @Test void legacyNoArgBoundaryHooksReadEveryRunningTimerAtItsOwnClock() {
        CountingClock one = new CountingClock();
        StreamReceiver single = new StreamReceiver(2, (s, r, route) -> true, one);
        assertAdmitted(single, rec(2, 1, 0, dep(3, 1)));
        int oneBefore = one.reads;
        single.stallStarted();
        int startReads = one.reads - oneBefore;
        one.nowNanos = 5 * SEC;
        single.stallEnded();
        int endReads = one.reads - oneBefore - startReads;
        assertTrue(startReads > 0, "a held timer's no-arg hook reads its own clock");

        CountingClock two = new CountingClock();
        StreamReceiver pair = new StreamReceiver(2, (s, r, route) -> true, two);
        assertAdmitted(pair, rec(2, 1, 0, dep(3, 1)));
        assertAdmitted(pair, rec(3, 1, 0, dep(0, 1)));
        int pairBefore = two.reads;
        pair.stallStarted();
        assertEquals(2 * startReads, two.reads - pairBefore, "each held timer runs its own no-arg hook");
        two.nowNanos = 5 * SEC;
        pair.stallEnded();
        assertEquals(2 * (startReads + endReads), two.reads - pairBefore,
                "each held timer ends its own interval at its own clock");
    }

    /** A cumulative receiver over {@code source}. */
    private static StreamReceiver cumulative(Direction direction, StallClock source) {
        return new StreamReceiver(2, direction, (s, r, route) -> true, source);
    }

    /** A transport clock that counts its reads and can refuse to be read at all. */
    private static final class CountingClock implements LongSupplier {
        long nowNanos;
        int reads;
        boolean armed;

        @Override public long getAsLong() {
            if (armed) {
                throw new IllegalStateException("the clock must not be read here");
            }
            reads++;
            return nowNanos;
        }
    }

    /** A scripted cumulative source whose every reading is one coherent pair. */
    private static final long KIB = 1024L;

    /** The ACK stream's route (13.21); ACK records are one small GAME record at a time. */
    private static ReceiveRoute ackRoute() {
        return new ReceiveRoute(ReceiveRoute.Lane.ACK, Dependency.ORDINARY, null, false, false);
    }

    /** Admits, validates, enqueues and applies one single-record unit: a commit (D) of wireBytes. */
    private static void commit(StreamReceiver rx, Record record, long wireBytes, ReceiveRoute route) {
        StreamReceiver.Admission admission = rx.offer(record, wireBytes, 1);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED, admission.kind(),
                () -> "commit of stream " + record.streamIndex() + " seq " + record.sequence());
        enqueue(rx, record.streamIndex(), record.sequence(), route);
        rx.applied(record.streamIndex(), record.sequence());
    }

    private static void assertUnseen(ReceiveClose close) {
        assertNotNull(close, "the no-progress deadline must expire");
        assertEquals(0x12, close.errorCode());
        assertEquals("unseen-prerequisite", new String(close.reason(), java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void assertGenericRetention(ReceiveClose close) {
        assertNotNull(close, "the absolute retention deadline must expire");
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason(), "absolute retention carries no phrase");
        assertFalse(close.unseenPrerequisite());
    }

    @Test void honestRelevantCommitsSustainAWaitPastTheTenSecondBase() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 100)));
        for (int k = 1; k <= 10; k++) {
            f.now.set(4L * k * SEC);
            commit(f.receiver, rec(3, k, 0), 40 * KIB, route(3));
            assertNull(f.receiver.expiredWait(), "two 40 KiB commits renew, t=" + 4 * k + " s");
        }
        f.now.set(45 * SEC);
        assertNull(f.receiver.expiredWait(), "the last renewal at 40 s leaves 5 s of base");
    }

    @Test void completingTheRequiredSequenceRenewsTheBaseDeadline() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(0, 1), dep(3, 1)));
        f.now.set(9 * SEC);
        commit(f.receiver, rec(3, 1, 0), 1, route(3));
        f.now.set(18 * SEC + SEC - 1);
        assertNull(f.receiver.expiredWait(), "the watermark commit renewed the base at 9 s");
        f.now.set(19 * SEC);
        assertUnseen(f.receiver.expiredWait());
    }

    @Test void transitivePrerequisiteProgressRenewsOnlyByByteCredit() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1)));
        assertAdmitted(f.receiver, rec(3, 1, 0, dep(0, 1)));
        f.now.set(9 * SEC);
        commit(f.receiver, rec(0, 1, 0), 64 * KIB, route(0));
        f.now.set(10 * SEC);
        assertNull(f.receiver.expiredWait(), "CONTROL 1 is transitive progress worth 64 KiB for the head on CHUNK 3 1");
        f.now.set(19 * SEC);
        assertUnseen(f.receiver.expiredWait());
    }

    @Test void tinyRecordsOnATransitivePrerequisiteCannotRenewThroughAnIntermediateHead() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 100)));
        assertAdmitted(f.receiver, rec(3, 1, 0, dep(0, 2)));
        f.now.set(4 * SEC);
        commit(f.receiver, rec(0, 1, 0), 1, route(0));
        f.now.set(9 * SEC);
        // This 1-byte commit reaches the transitive watermark CONTROL 2, which must not renew (13.10 arm a).
        commit(f.receiver, rec(0, 2, 0), 1, route(0));
        f.now.set(10 * SEC);
        assertUnseen(f.receiver.expiredWait());
    }

    @Test void directEntryRenewsOnlyWhenTheCommitReachesItsOwnSequence() {
        Fixture unreached = new Fixture(2);
        assertAdmitted(unreached.receiver, rec(2, 1, 0, dep(0, 2), dep(3, 100)));
        unreached.now.set(9 * SEC);
        commit(unreached.receiver, rec(0, 1, 0), 1, route(0));
        unreached.now.set(10 * SEC);
        assertUnseen(unreached.receiver.expiredWait());

        Fixture reached = new Fixture(2);
        assertAdmitted(reached.receiver, rec(2, 1, 0, dep(0, 2), dep(3, 100)));
        reached.now.set(9 * SEC);
        commit(reached.receiver, rec(0, 1, 0), 1, route(0));
        reached.now.set(9 * SEC + SEC / 2);
        commit(reached.receiver, rec(0, 2, 0), 1, route(0));
        reached.now.set(19 * SEC);
        assertNull(reached.receiver.expiredWait(), "CONTROL 2 reaches the direct entry, so the base renewed at 9.5 s");
        reached.now.set(19 * SEC + SEC / 2);
        assertUnseen(reached.receiver.expiredWait());
    }

    @Test void aOneByteEveryNineSecondsTrickleIsClosedAndCannotBeRescued() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 100)));
        f.now.set(9 * SEC);
        commit(f.receiver, rec(3, 1, 0), 1, route(3));
        f.now.set(9 * SEC + 1);
        assertNull(f.receiver.expiredWait(), "a 1-byte commit earns no renewal, the base is not reached yet");
        f.now.set(10 * SEC);
        assertUnseen(f.receiver.expiredWait());
        f.now.set(18 * SEC);
        commit(f.receiver, rec(3, 2, 0), 1, route(3));
        assertUnseen(f.receiver.expiredWait());
    }

    @Test void unrelatedAndAckTrafficEarnsNoCredit() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 100)));
        for (int k = 1; k <= 9; k++) {
            f.now.set(k * SEC);
            commit(f.receiver, rec(4, k, 0), 50, ackRoute());
        }
        f.now.set(9 * SEC + SEC / 2);
        commit(f.receiver, rec(0, 1, 0), 100 * KIB, route(0));
        f.now.set(10 * SEC);
        assertUnseen(f.receiver.expiredWait());
    }

    @Test void repeatedTinyValidPredecessorsBelowTheThresholdDoNotRenew() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 100)));
        for (int k = 1; k <= 4; k++) {
            f.now.set(2L * k * SEC);
            commit(f.receiver, rec(3, k, 0), KIB, route(3));
        }
        f.now.set(9 * SEC);
        assertNull(f.receiver.expiredWait());
        f.now.set(10 * SEC);
        assertUnseen(f.receiver.expiredWait());
    }

    @Test void renewalDiscardsTheSurplusCredit() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 100)));
        f.now.set(1 * SEC);
        commit(f.receiver, rec(3, 1, 0), 60 * KIB, route(3));
        f.now.set(2 * SEC);
        commit(f.receiver, rec(3, 2, 0), 60 * KIB, route(3));
        f.now.set(3 * SEC);
        commit(f.receiver, rec(3, 3, 0), 10 * KIB, route(3));
        f.now.set(11 * SEC);
        assertNull(f.receiver.expiredWait(), "renewed at 2 s, so the base runs to 12 s");
        f.now.set(12 * SEC);
        assertUnseen(f.receiver.expiredWait());
    }

    @Test void absoluteRetentionClosesGenericEvenWhileQualifyingTrafficRenews() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1000)));
        for (int k = 1; k <= 14; k++) {
            f.now.set(8L * k * SEC);
            commit(f.receiver, rec(3, k, 0), 70 * KIB, route(3));
            assertNull(f.receiver.expiredWait(), "each 70 KiB commit renews, t=" + 8 * k + " s");
        }
        f.now.set(120 * SEC - 1);
        assertNull(f.receiver.expiredWait(), "119.999 s of active time is inside the retention bound");
        f.now.set(120 * SEC);
        commit(f.receiver, rec(3, 15, 0), 70 * KIB, route(3));
        assertGenericRetention(f.receiver.expiredWait());
    }

    @Test void commitBeforeTheBoundaryRenewsAndAtTheBoundaryCannotRescue() {
        Fixture early = new Fixture(2);
        assertAdmitted(early.receiver, rec(2, 1, 0, dep(0, 1), dep(3, 1)));
        early.now.set(10 * SEC - 1);
        commit(early.receiver, rec(3, 1, 0), 1, route(3));
        early.now.set(10 * SEC);
        assertNull(early.receiver.expiredWait(), "one nanosecond before the deadline the watermark renews");

        Fixture exact = new Fixture(2);
        assertAdmitted(exact.receiver, rec(2, 1, 0, dep(0, 1), dep(3, 1)));
        exact.now.set(10 * SEC);
        commit(exact.receiver, rec(3, 1, 0), 1, route(3));
        assertUnseen(exact.receiver.expiredWait());
    }

    @Test void theSixtySecondStallAllowanceIsNotReplenishedByRenewal() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0, dep(3, 1000)));
        f.receiver.stallStarted();
        f.now.set(60 * SEC);
        f.receiver.stallEnded();
        commit(f.receiver, rec(3, 1, 0), 70 * KIB, route(3));
        f.receiver.stallStarted();
        f.now.set(69 * SEC);
        assertNull(f.receiver.expiredWait(), "the allowance is spent: 9 s of active time since the renewal");
        f.now.set(70 * SEC);
        ReceiveClose close = f.receiver.expiredWait();
        assertNotNull(close, "a second stall beyond the spent allowance counts");
        assertEquals(0x12, close.errorCode());
        assertNull(close.reason(), "consumed stall time blocks the phrase");
    }

    @Test void cumulativeQualifyingProgressRenewsTheBaseDeadline() {
        ScriptedSource source = new ScriptedSource().set(0, 0);
        StreamReceiver rx = cumulative(Direction.CLIENTBOUND, source);
        assertAdmitted(rx, rec(2, 1, 0, dep(3, 1000)));
        source.set(9 * SEC, 0);
        commit(rx, rec(3, 1, 0), 70 * KIB, route(3));
        source.set(18 * SEC, 0);
        assertNull(rx.expiredWait(), "renewed at 9 s");
        source.set(19 * SEC, 0);
        assertUnseen(rx.expiredWait());
    }

    /** A cumulative source that counts its reads and, once {@link #failing} is set, throws on every read. */
    private static final class FailingSource implements StallClock {
        int reads;
        boolean failing;

        @Override public Sample sample() {
            reads++;
            if (failing) {
                throw new IllegalStateException("stall source read during an apply");
            }
            return new Sample() {
                @Override public long nowNanos() { return 0; }
                @Override public long excludedNanos() { return 0; }
                @Override public boolean valid() { return true; }
            };
        }
    }

    /** A cumulative source that returns its scripted clock readings in order, then repeats the last one. */
    private static final class SteppingSource implements StallClock {
        private final long[] nows;
        int samples;

        SteppingSource(long... nows) {
            this.nows = nows;
        }

        @Override public Sample sample() {
            final long now = nows[Math.min(samples, nows.length - 1)];
            samples++;
            return new Sample() {
                @Override public long nowNanos() { return now; }
                @Override public long excludedNanos() { return 0; }
                @Override public boolean valid() { return true; }
            };
        }
    }

    private static final class ScriptedSource implements StallClock {
        long nowNanos;
        long excludedNanos;
        boolean valid = true;
        int samples;

        ScriptedSource set(long nowNanos, long excludedNanos) {
            this.nowNanos = nowNanos;
            this.excludedNanos = excludedNanos;
            return this;
        }

        @Override public Sample sample() {
            samples++;
            final long now = nowNanos;
            final long excluded = excludedNanos;
            final boolean validReading = valid;
            return new Sample() {
                @Override public long nowNanos() { return now; }
                @Override public long excludedNanos() { return excluded; }
                @Override public boolean valid() { return validReading; }
            };
        }
    }
}
