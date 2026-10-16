// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;

class StreamReceiverTest {
    private static final long SEC = 1_000_000_000L;
    /** Record kind values (13.4): join records are chunk-only. */
    private static final int ACK_JOIN = 2;
    private static final int BATCH_JOIN = 3;

    private static final class Fixture {
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
        return rx.offer(record, route(record.streamIndex()), 100, 1);
    }

    static void assertAdmitted(StreamReceiver rx, Record record) {
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED, offer(rx, record).kind());
    }

    static void assertViolation(StreamReceiver.Admission admission) {
        assertEquals(StreamReceiver.Admission.Kind.VIOLATION, admission.kind());
        assertEquals(0x11, admission.close().errorCode());
    }

    @Test void everyStreamHoldsOneHeadAndASecondIsRefusedNotViolated() {
        Fixture f = new Fixture(6);
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
                f.receiver.enqueued(prereq, 1);
                f.receiver.applied(prereq, 1);
                assertEquals(blocked, f.receiver.nextToEnqueue());
            }
        }
    }

    @Test void realtimeIsServedFirstWhenBothHeadsAreFullAndReady() {
        Fixture f = new Fixture(2);
        long full = ReceiveSlots.BYTES_PER_STREAM;
        int records = ReceiveSlots.RECORDS_PER_STREAM;
        // REALTIME, CONTROL and a chunk head each hold a complete slot at its limit.
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(1, 1, 0), route(1), full, records).kind());
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), route(0), full, records).kind());
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(2, 1, 0), route(2), full, records).kind());
        assertEquals(1, f.receiver.nextToEnqueue(), "REALTIME before CONTROL and chunks");
        f.receiver.enqueued(1, 1);
        assertEquals(0, f.receiver.nextToEnqueue(), "CONTROL before the chunk head");
        f.receiver.enqueued(0, 1);
        assertEquals(2, f.receiver.nextToEnqueue());
    }

    @Test void transitionCarriesTheOldEpochAndApplyAdvancesItByExactlyOne() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), boundary(), 100, 1).kind());
        f.receiver.enqueued(0, 1);
        assertEquals(0, f.receiver.epoch(), "the epoch does not move until D");
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch(), "exactly one increment on apply");
    }

    @Test void transitionWithANewEpochNumberIsAViolation() {
        Fixture f = new Fixture(2);
        assertViolation(f.receiver.offer(rec(0, 1, 1), boundary(), 100, 1));
    }

    @Test void staleAndFutureEpochsAreViolationsAfterATransition() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), boundary(), 100, 1).kind());
        f.receiver.enqueued(0, 1);
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
                f.receiver.offer(rec(0, 1, 0), boundary(), 100, 1).kind());
        f.receiver.enqueued(0, 1);
        assertEquals(-1, f.receiver.nextToEnqueue(), "new-epoch chunk still waits for the transition");
        f.receiver.applied(0, 1);
        assertEquals(2, f.receiver.nextToEnqueue());
    }

    @Test void epochTwoAheadIsFutureEvenWithAPendingTransition() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), boundary(), 100, 1).kind());
        assertViolation(offer(f.receiver, rec(2, 1, 2)));
    }

    @Test void secondTransitionWhileOneIsPendingIsAViolation() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), boundary(), 100, 1).kind());
        assertViolation(f.receiver.offer(rec(0, 2, 0), boundary(), 100, 1));
    }

    @Test void wrongShardIsAViolationFromTheInjectedValidator() {
        Fixture f = new Fixture(2, (s, r, route) -> false);
        assertViolation(offer(f.receiver, rec(3, 1, 0)));
    }

    @Test void skippedAndDuplicateSequencesAreViolations() {
        Fixture f = new Fixture(2);
        assertViolation(offer(f.receiver, rec(2, 2, 0)));
        assertAdmitted(f.receiver, rec(2, 1, 0));
        f.receiver.enqueued(2, 1);
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
        assertViolation(f.receiver.offer(recKind(3, 1, 0, ACK_JOIN), join, 100, 1));
        assertViolation(f.receiver.offer(recKind(1, 1, 0, BATCH_JOIN), join, 100, 1));
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(recKind(2, 1, 0, BATCH_JOIN), join, 100, 1).kind());
    }

    @Test void joinRouteWithANonJoinKindIsAViolation() {
        Fixture f = new Fixture(2);
        ReceiveRoute join = new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.ACK_JOIN, null, false, true);
        assertViolation(f.receiver.offer(rec(2, 1, 0), join, 100, 1));
    }

    @Test void joinKindWithANonJoinRouteIsAViolation() {
        Fixture f = new Fixture(2);
        assertViolation(offer(f.receiver, recKind(2, 1, 0, ACK_JOIN)));
    }

    @Test void epochPlusOneWithoutAnyTransitionPathIsAViolation() {
        Fixture f = new Fixture(2);
        assertViolation(offer(f.receiver, rec(2, 1, 1)));
    }

    @Test void epochPlusOneAfterTransitionAppliedWithoutPathIsAViolation() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), boundary(), 100, 1).kind());
        f.receiver.enqueued(0, 1);
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch());
        assertViolation(offer(f.receiver, rec(2, 1, 2)));
        assertViolation(offer(f.receiver, rec(2, 1, 2, dep(0, 1))));
    }

    @Test void epochPlusOneWithAPendingTransitionIsAdmitted() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), boundary(), 100, 1).kind());
        assertAdmitted(f.receiver, rec(2, 1, 1));
    }

    @Test void serverboundReceiverAllowsEpochZeroOnly() {
        StreamReceiver rx = new StreamReceiver(2, Direction.SERVERBOUND,
                (s, r, route) -> true, () -> 0L);
        assertAdmitted(rx, rec(2, 1, 0));
        assertViolation(offer(rx, rec(2, 2, 1)));
        assertViolation(rx.offer(rec(0, 1, 0), boundary(), 100, 1));
    }

    @Test void serverboundTransitionRouteIsAViolation() {
        StreamReceiver rx = new StreamReceiver(2, Direction.SERVERBOUND,
                (s, r, route) -> true, () -> 0L);
        assertViolation(rx.offer(rec(0, 1, 0), boundary(), 100, 1));
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

    @Test void enqueueStopsTheTimerAndOnlyApplyReleasesDependencies() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(2, 1, 0));
        assertAdmitted(f.receiver, rec(3, 1, 0, dep(2, 1)));
        f.receiver.enqueued(2, 1);
        assertEquals(1, f.receiver.enqueuedWatermark(2));
        assertEquals(0, f.receiver.appliedWatermark(2));
        assertEquals(-1, f.receiver.nextToEnqueue(), "Q must not release the dependent");
        f.now.set(100 * SEC);
        ReceiveClose waiting = f.receiver.expiredWait();
        assertNotNull(waiting, "the dependent stays blocked until D, so its own wait expires");
        assertEquals(0x12, waiting.errorCode());
        f.receiver.applied(2, 1);
        assertEquals(1, f.receiver.appliedWatermark(2));
        assertEquals(3, f.receiver.nextToEnqueue());
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
        f.receiver.enqueued(3, 1);
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

    @Test void fullSlotsAtSixChunksExpireEveryWithheldPrerequisiteWithItsOwnCode() {
        for (int prereq = 0; prereq < 8; prereq++) {
            assertAcceptanceCase(prereq, false);
            assertAcceptanceCase(prereq, true);
        }
    }

    /** N=6, all eight streams at their slot limit; one dependent waits on prereq, which is withheld. */
    private static void assertAcceptanceCase(int prereq, boolean locallyWithheld) {
        int blocked = (prereq + 1) % 8;
        Fixture f = new Fixture(6);
        long full = ReceiveSlots.BYTES_PER_STREAM;
        int records = ReceiveSlots.RECORDS_PER_STREAM;
        for (int s = 0; s < 8; s++) {
            Record r = s == blocked ? rec(s, 1, 0, dep(prereq, 1)) : rec(s, 1, 0);
            assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                    f.receiver.offer(r, route(s), full, records).kind(), "stream " + s + " prereq " + prereq);
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
        f.receiver.enqueued(1, 1);
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
        f.receiver.enqueued(1, 1);
        // The transition arrives while REALTIME 1 is still held, so the path is reachable again.
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 0), boundary(), 100, 1).kind());
        f.receiver.applied(1, 1);
        assertNull(f.receiver.pendingViolation(), "a pending transition keeps the head legal");
        f.receiver.enqueued(0, 1);
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch());
        assertNull(f.receiver.pendingViolation());
        assertEquals(2, f.receiver.nextToEnqueue(), "the chunk head is released normally");
    }
}
