// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutorProgressWatchTest {
    private static final long SEC = 1_000_000_000L;

    @Test void probePendingForTwelveSecondsExcludesEleven() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        assertTrue(token > 0, "a fresh watch issues a positive token");
        now.set(12 * SEC);
        watch.probeCompleted(token);
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertEquals(12 * SEC, snapshot.sampledAtNanos);
        assertEquals(11 * SEC, snapshot.excludedTotalNanos, "[1 s, 12 s] is the only qualifying interval");
        assertTrue(snapshot.valid);
        assertTrue(snapshot.proven, "the matching probe completion proves readiness");
    }

    @Test void unrelatedCompletionEndsTheIntervalWhileTheProbeStaysPending() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        watch.probeSubmitted();
        now.set(12 * SEC);
        watch.taskCompleted();
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertEquals(11 * SEC, snapshot.excludedTotalNanos, "[1 s, 12 s] closed by the completion");
        assertFalse(snapshot.proven, "only the probe completing proves readiness");
        now.set(13 * SEC);
        assertEquals(11 * SEC, watch.sample().excludedTotalNanos, "the next interval qualifies only from 13 s");
        now.set(20 * SEC);
        assertEquals(18 * SEC, watch.sample().excludedTotalNanos, "[13 s, 20 s] adds 7 s");
    }

    @Test void intermediateCompletionSplitsTheExclusion() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(4 * SEC);
        watch.taskCompleted();
        now.set(12 * SEC);
        watch.probeCompleted(token);
        assertEquals(10 * SEC, watch.sample().excludedTotalNanos, "[1 s, 4 s] plus [5 s, 12 s]");
        assertTrue(watch.sample().proven);
    }

    @Test void completionBeforeTheThresholdRestartsQualification() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(SEC / 2);
        watch.taskCompleted();
        now.set(12 * SEC);
        watch.probeCompleted(token);
        assertEquals(10 * SEC + SEC / 2, watch.sample().excludedTotalNanos, "[1.5 s, 12 s]");
    }

    @Test void completionAtExactlyTheThresholdExcludesNothing() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(SEC);
        watch.probeCompleted(token);
        assertEquals(0, watch.sample().excludedTotalNanos, "the first second is never excluded");
        assertTrue(watch.sample().proven, "a fast probe still proves readiness");

        AtomicLong boundaryNow = new AtomicLong();
        ExecutorProgressWatch boundary = new ExecutorProgressWatch(boundaryNow::get);
        long boundaryToken = boundary.probeSubmitted();
        boundaryNow.set(SEC);
        boundary.taskCompleted();
        boundaryNow.set(2 * SEC);
        boundary.taskCompleted();
        boundaryNow.set(3 * SEC);
        boundary.probeCompleted(boundaryToken);
        assertEquals(0, boundary.sample().excludedTotalNanos, "each completion landed exactly on the threshold");
        assertTrue(boundary.sample().proven);
    }

    @Test void oneNanosecondPastTheThresholdIsExcluded() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(SEC + 1);
        watch.probeCompleted(token);
        assertEquals(1, watch.sample().excludedTotalNanos, "exclusion is exactly the time past the threshold");
    }

    @Test void idleWatchExcludesNothingAndProvesNothing() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        now.set(500 * SEC);
        watch.taskCompleted();
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertEquals(500 * SEC, snapshot.sampledAtNanos);
        assertEquals(0, snapshot.excludedTotalNanos);
        assertTrue(snapshot.valid);
        assertFalse(snapshot.proven, "construction and stray completions are not proof");
    }

    @Test void repeatedAndLateSamplesAgreeWithoutDoubleCounting() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(6 * SEC);
        assertEquals(5 * SEC, watch.sample().excludedTotalNanos);
        now.set(10 * SEC);
        assertEquals(9 * SEC, watch.sample().excludedTotalNanos, "the open interval is recomputed, not replayed");
        assertEquals(9 * SEC, watch.sample().excludedTotalNanos, "sampling twice at one reading changes nothing");
        now.set(12 * SEC);
        watch.probeCompleted(token);
        assertEquals(11 * SEC, watch.sample().excludedTotalNanos, "the closed total never included the samples");
    }

    @Test void lateObservationMatchesATimelySample() {
        AtomicLong timelyNow = new AtomicLong();
        ExecutorProgressWatch timely = new ExecutorProgressWatch(timelyNow::get);
        long timelyToken = timely.probeSubmitted();
        timelyNow.set(6 * SEC);
        long midObservation = timely.sample().excludedTotalNanos;
        timelyNow.set(9 * SEC);
        timely.probeCompleted(timelyToken);

        AtomicLong lateNow = new AtomicLong();
        ExecutorProgressWatch late = new ExecutorProgressWatch(lateNow::get);
        long lateToken = late.probeSubmitted();
        lateNow.set(9 * SEC);
        late.probeCompleted(lateToken);

        assertEquals(5 * SEC, midObservation);
        assertEquals(8 * SEC, timely.sample().excludedTotalNanos);
        assertEquals(timely.sample().excludedTotalNanos, late.sample().excludedTotalNanos,
                "a midway observer and a completion-only observer agree");
    }

    @Test void negativeClockOriginKeepsDurationsRelative() {
        AtomicLong now = new AtomicLong(-20 * SEC);
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(-8 * SEC);
        watch.probeCompleted(token);
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertEquals(-8 * SEC, snapshot.sampledAtNanos, "readings stay in the clock's own domain");
        assertEquals(11 * SEC, snapshot.excludedTotalNanos, "12 s elapsed, minus the first second");
        assertTrue(snapshot.proven);
    }

    @Test void signedClockWrapKeepsIntervalsCorrect() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 3 * SEC);
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(now.get() + 2 * SEC);
        watch.taskCompleted();
        now.set(now.get() + 5 * SEC);
        assertTrue(now.get() < 0, "the reading wrapped past Long.MAX_VALUE");
        watch.probeCompleted(token);
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertEquals(5 * SEC, snapshot.excludedTotalNanos, "[1 s, 2 s] plus [3 s, 7 s]");
        assertTrue(snapshot.proven);
    }

    @Test void staleAndDuplicateTokensDoNothing() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        assertEquals(0, watch.probeSubmitted(), "a pending probe rejects another reservation, keeping its own token");
        now.set(2 * SEC);
        watch.probeCompleted(0);
        watch.probeCompleted(token + 1);
        assertFalse(watch.sample().proven, "unknown and zero tokens never prove readiness");
        assertEquals(SEC, watch.sample().excludedTotalNanos, "the reserved probe is still pending");
        now.set(3 * SEC);
        watch.probeCompleted(token);
        assertEquals(2 * SEC, watch.sample().excludedTotalNanos, "[1 s, 3 s] closes");
        assertTrue(watch.sample().proven);
        watch.probeCompleted(token);
        now.set(9 * SEC);
        assertEquals(2 * SEC, watch.sample().excludedTotalNanos, "a duplicate token does not reopen accounting");
        assertTrue(watch.probeSubmitted() > 0, "after a completion the owner may reserve the next probe");
    }

    @Test void provenStaysTrueAcrossAFollowingReservation() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long first = watch.probeSubmitted();
        now.set(2 * SEC);
        watch.probeCompleted(first);
        assertTrue(watch.probeSubmitted() > 0);
        now.set(20 * SEC);
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertEquals(18 * SEC, snapshot.excludedTotalNanos, "[1 s, 2 s] then the open [3 s, 20 s]");
        assertTrue(snapshot.proven, "an earlier probe completion remains proof");
        assertTrue(snapshot.valid);
    }

    @Test void invalidationAccountsThroughNowAndIgnoresLaterCompletions() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(5 * SEC);
        watch.invalidate();
        ExecutorProgressWatch.Snapshot atInvalidation = watch.sample();
        assertFalse(atInvalidation.valid, "the watch is invalid forever");
        assertEquals(5 * SEC, atInvalidation.sampledAtNanos);
        assertEquals(4 * SEC, atInvalidation.excludedTotalNanos, "[1 s, 5 s] accrued before invalidation");
        assertFalse(atInvalidation.proven);

        now.set(9 * SEC);
        watch.taskCompleted();
        watch.probeCompleted(token);
        watch.invalidate();
        assertEquals(0, watch.probeSubmitted(), "an invalid watch issues no token");
        ExecutorProgressWatch.Snapshot later = watch.sample();
        assertEquals(9 * SEC, later.sampledAtNanos);
        assertEquals(4 * SEC, later.excludedTotalNanos, "later time and completions add nothing");
        assertFalse(later.valid);
        assertFalse(later.proven);
    }

    @Test void invalidationPreservesClosedIntervals() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        long token = watch.probeSubmitted();
        now.set(4 * SEC);
        watch.taskCompleted();
        now.set(10 * SEC);
        watch.invalidate();
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertEquals(8 * SEC, snapshot.excludedTotalNanos, "[1 s, 4 s] plus the open [5 s, 10 s]");
        assertFalse(snapshot.valid);
        assertFalse(snapshot.proven, "invalidation came before the probe completed");
    }
}
