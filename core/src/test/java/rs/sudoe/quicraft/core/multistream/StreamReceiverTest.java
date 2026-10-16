// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Dependency;

class StreamReceiverTest {
    private static final long SEC = 1_000_000_000L;

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

    @Test void realtimeIsServedFirstWhenBothHeadsAreReady() {
        Fixture f = new Fixture(2);
        assertAdmitted(f.receiver, rec(0, 1, 0));
        assertAdmitted(f.receiver, rec(1, 1, 0));
        assertAdmitted(f.receiver, rec(2, 1, 0));
        assertEquals(1, f.receiver.nextToEnqueue());
        f.receiver.enqueued(1, 1);
        assertEquals(0, f.receiver.nextToEnqueue());
    }

    @Test void staleAndFutureEpochsAreViolations() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 1), boundary(), 100, 1).kind());
        f.receiver.enqueued(0, 1);
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch());
        assertViolation(offer(f.receiver, rec(2, 1, 0)));
        assertViolation(offer(f.receiver, rec(2, 1, 3)));
    }

    @Test void epochBoundaryOpensTheNewEpochOnlyAfterItIsApplied() {
        Fixture f = new Fixture(2);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(rec(0, 1, 1), boundary(), 100, 1).kind());
        assertAdmitted(f.receiver, rec(2, 1, 1, dep(0, 1)));
        assertEquals(0, f.receiver.nextToEnqueue(), "boundary is ready");
        f.receiver.enqueued(0, 1);
        assertEquals(-1, f.receiver.nextToEnqueue(), "new-epoch chunk waits for the respawn");
        f.receiver.applied(0, 1);
        assertEquals(1, f.receiver.epoch());
        assertEquals(2, f.receiver.nextToEnqueue());
    }

    @Test void newEpochRecordWithoutBoundaryIsAViolation() {
        Fixture f = new Fixture(2);
        assertViolation(offer(f.receiver, rec(2, 1, 1)));
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

    @Test void joinRecordsLiveOnlyOnChunkZero() {
        Fixture f = new Fixture(2);
        ReceiveRoute join = new ReceiveRoute(ReceiveRoute.Lane.CHUNK, Dependency.ACK_JOIN, null, false, true);
        assertViolation(f.receiver.offer(rec(3, 1, 0), join, 100, 1));
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED, f.receiver.offer(rec(2, 1, 0), join, 100, 1).kind());
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
        withheld.receiver.markLocallyWithheld();
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
}
