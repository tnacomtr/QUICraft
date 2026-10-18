// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.time.StallClock;

class BlockedHeadTimerTest {
    private static final long SEC = 1_000_000_000L;

    @Test void expiresAfterTenSecondsOfTransportTimeWithoutStalls() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        now.set(9 * SEC);
        assertFalse(timer.expired());
        now.set(10 * SEC);
        assertTrue(timer.expired());
        assertFalse(timer.stallConsumed());
    }

    @Test void startIsOnceAndNeverResets() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        now.set(6 * SEC);
        timer.start();
        now.set(10 * SEC);
        assertTrue(timer.expired(), "a second start must not restart the wait");
    }

    @Test void recordedStallsAreExcludedUpToSixtySecondsThenCount() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        timer.stallStarted();
        now.set(65 * SEC);
        timer.stallEnded();
        assertEquals(60 * SEC, timer.excludedNanos());
        assertFalse(timer.expired(), "active time is 65 - 60 = 5 seconds");
        now.set(70 * SEC);
        assertTrue(timer.expired(), "active time is 70 - 60 = 10 seconds");
        assertTrue(timer.stallConsumed());
    }

    @Test void resourceWaitsIgnoreExecutorStalls() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, false);
        timer.start();
        timer.stallStarted();
        now.set(10 * SEC);
        timer.stallEnded();
        assertTrue(timer.expired());
        assertFalse(timer.stallConsumed());
    }

    @Test void resourceWaitExcludesNoTimestampedStall() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, false);
        timer.start();
        now.set(10 * SEC);
        timer.stallStarted(5 * SEC);
        now.set(20 * SEC);
        timer.stallEnded(15 * SEC);
        assertEquals(0, timer.excludedNanos());
        assertFalse(timer.stallConsumed());
        assertTrue(timer.expired());
    }

    @Test void stopClearsTheWait() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        timer.stop();
        now.set(100 * SEC);
        assertFalse(timer.expired());
    }

    @Test void intervalReportedBeforeTheWaitStartIsClippedToTheWait() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        now.set(10 * SEC);
        timer.start();
        timer.stallStarted(5 * SEC);
        now.set(20 * SEC);
        timer.stallEnded(15 * SEC);
        assertEquals(5 * SEC, timer.excludedNanos(), "[10 s, 15 s] of the reported interval is excluded");
        assertTrue(timer.stallConsumed());
        assertEquals(5 * SEC, timer.activeNanos(), "10 s elapsed minus 5 s excluded");
    }

    @Test void completedStallObservedLateMatchesTimelyEvidence() {
        AtomicLong timelyNow = new AtomicLong();
        BlockedHeadTimer timely = new BlockedHeadTimer(timelyNow::get, true);
        timely.start();
        timelyNow.set(4 * SEC);
        timely.stallStarted(4 * SEC);
        timelyNow.set(8 * SEC);
        timely.stallEnded(8 * SEC);

        AtomicLong lateNow = new AtomicLong();
        BlockedHeadTimer late = new BlockedHeadTimer(lateNow::get, true);
        late.start();
        lateNow.set(12 * SEC);
        late.stallStarted(4 * SEC);
        late.stallEnded(8 * SEC);

        assertEquals(4 * SEC, timely.excludedNanos());
        assertEquals(timely.excludedNanos(), late.excludedNanos(), "same interval evidence, same exclusion");
        assertEquals(12 * SEC - 4 * SEC, late.activeNanos());
    }

    @Test void overlappingIntervalsCountEachStallSecondOnce() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        now.set(2 * SEC);
        timer.stallStarted(2 * SEC);
        now.set(5 * SEC);
        timer.stallEnded(5 * SEC);
        assertEquals(3 * SEC, timer.excludedNanos());
        now.set(6 * SEC);
        timer.stallStarted(4 * SEC);
        now.set(7 * SEC);
        timer.stallEnded(7 * SEC);
        assertEquals(5 * SEC, timer.excludedNanos(), "the union [2 s, 7 s] excludes 5 s, not 6 s");
    }

    @Test void repeatedNotificationsAreIgnoredWhileTheIntervalStaysOpen() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        now.set(2 * SEC);
        timer.stallStarted(2 * SEC);
        now.set(3 * SEC);
        timer.stallStarted(3 * SEC);
        now.set(4 * SEC);
        timer.stallEnded(4 * SEC);
        now.set(6 * SEC);
        timer.stallEnded(6 * SEC);
        assertEquals(2 * SEC, timer.excludedNanos());
    }

    @Test void repeatedLongIntervalsStayCappedAtSixtySeconds() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        long[] ends = {61 * SEC, 122 * SEC, 183 * SEC};
        for (int i = 0; i < ends.length; i++) {
            timer.stallStarted(i == 0 ? 0 : ends[i - 1]);
            now.set(ends[i]);
            timer.stallEnded(ends[i]);
            assertEquals(60 * SEC, timer.excludedNanos(), "exclusion saturates at the allowance");
            assertTrue(timer.activeNanos() >= 0, "active time never goes negative");
        }
        assertEquals(183 * SEC - 60 * SEC, timer.activeNanos());
        assertTrue(timer.stallConsumed());
        assertTrue(timer.expired(), "123 s of active time");
    }

    @Test void futureStampedStartIsClampedNonNegative() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        now.set(1 * SEC);
        timer.stallStarted(3 * SEC);
        assertEquals(0, timer.excludedNanos(), "an unobserved future start excludes nothing yet");
        assertFalse(timer.stallConsumed());
        assertEquals(1 * SEC, timer.activeNanos(), "active time is not inflated by the future stamp");
        now.set(5 * SEC);
        timer.stallEnded(4 * SEC);
        assertEquals(1 * SEC, timer.excludedNanos(), "[3 s, 4 s] is the observed part of the open interval");
        assertEquals(4 * SEC, timer.activeNanos());
    }

    @Test void futureStampedEndIsClippedToTheObservation() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        timer.stallStarted(0);
        now.set(1 * SEC);
        timer.stallEnded(3 * SEC);
        assertEquals(1 * SEC, timer.excludedNanos(), "an end past the reading excludes only up to it");
        assertEquals(0, timer.activeNanos(), "no negative active time");
    }

    @Test void negativeClockOriginKeepsExclusionsWaitRelative() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        now.set(-20 * SEC);
        timer.start();
        now.set(-10 * SEC);
        timer.stallStarted(-15 * SEC);
        now.set(-2 * SEC);
        timer.stallEnded(-5 * SEC);
        assertEquals(10 * SEC, timer.excludedNanos(), "[-15 s, -5 s] is inside the wait starting at -20 s");
        assertEquals(8 * SEC, timer.activeNanos(), "18 s elapsed minus 10 s excluded");
        assertTrue(timer.stallConsumed());

        AtomicLong beforeNow = new AtomicLong();
        BlockedHeadTimer before = new BlockedHeadTimer(beforeNow::get, true);
        beforeNow.set(-20 * SEC);
        before.start();
        beforeNow.set(-5 * SEC);
        before.stallStarted(-30 * SEC);
        before.stallEnded(-25 * SEC);
        assertEquals(0, before.excludedNanos(), "an interval ending before the wait excludes nothing");
        assertEquals(15 * SEC, before.activeNanos());
    }

    @Test void signedClockWrapKeepsShortIntervalAndActiveTimeCorrect() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        long origin = Long.MAX_VALUE - 3 * SEC;
        now.set(origin);
        timer.start();
        now.set(origin + 2 * SEC);
        timer.stallStarted();
        now.set(origin + 5 * SEC);
        assertTrue(now.get() < 0, "the reading wrapped past Long.MAX_VALUE");
        timer.stallEnded();
        assertEquals(3 * SEC, timer.excludedNanos(), "a 3 s interval across the wrap");
        assertEquals(2 * SEC, timer.activeNanos(), "5 s elapsed minus 3 s excluded");
        assertFalse(timer.expired());
    }

    @Test void signedClockWrapKeepsTheAllowanceAndDeadlineCorrect() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        long origin = Long.MAX_VALUE - 3 * SEC;
        now.set(origin);
        timer.start();
        timer.stallStarted();
        now.set(origin + 65 * SEC);
        assertTrue(now.get() < 0, "the reading wrapped past Long.MAX_VALUE");
        timer.stallEnded();
        assertEquals(60 * SEC, timer.excludedNanos(), "the allowance still caps across the wrap");
        assertFalse(timer.expired(), "active time is 65 - 60 = 5 seconds");
        now.set(origin + 70 * SEC);
        assertTrue(timer.expired(), "active time is 70 - 60 = 10 seconds");
    }

    @Test void outOfOrderStartCountsOnlyItsUncoveredTail() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        now.set(5 * SEC);
        timer.stallStarted(5 * SEC);
        now.set(7 * SEC);
        timer.stallEnded(7 * SEC);
        assertEquals(2 * SEC, timer.excludedNanos());
        now.set(8 * SEC);
        timer.stallStarted(2 * SEC);
        now.set(9 * SEC);
        timer.stallEnded(6 * SEC);
        assertEquals(2 * SEC, timer.excludedNanos(),
                "an earlier start after a later one is out of order and is not rejoined into the union");
    }

    @Test void cumulativeTimerCountsExcludedTotalDifferences() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        timer.start();
        source.set(10 * SEC, 4 * SEC);
        assertEquals(4 * SEC, timer.excludedNanos());
        assertEquals(6 * SEC, timer.activeNanos());
        assertFalse(timer.expired(), "6 s of active time");
        assertTrue(timer.stallConsumed());
        source.set(16 * SEC, 4 * SEC);
        assertEquals(4 * SEC, timer.excludedNanos());
        assertEquals(12 * SEC, timer.activeNanos());
        assertTrue(timer.expired());
        assertTrue(timer.running());
    }

    @Test void cumulativeTimerLateAndFrequentObservationsAgree() {
        Source frequentSource = new Source();
        BlockedHeadTimer frequent = new BlockedHeadTimer(frequentSource, true);
        frequent.start();
        frequentSource.set(3 * SEC, SEC);
        frequent.excludedNanos();
        frequentSource.set(7 * SEC, 3 * SEC);
        frequent.activeNanos();
        frequentSource.set(12 * SEC, 5 * SEC);
        long frequentExcluded = frequent.excludedNanos();
        long frequentActive = frequent.activeNanos();

        Source lateSource = new Source();
        BlockedHeadTimer late = new BlockedHeadTimer(lateSource, true);
        late.start();
        lateSource.set(12 * SEC, 5 * SEC);
        assertEquals(frequentExcluded, late.excludedNanos(), "a late observer sees the same exclusion");
        assertEquals(frequentActive, late.activeNanos(), "a late observer sees the same active time");
        assertEquals(5 * SEC, late.excludedNanos());
        assertEquals(7 * SEC, late.activeNanos());
    }

    @Test void cumulativeTimerBaselineInsideAnOpenStallClipsLaterOverlap() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        source.set(2 * SEC, SEC);
        timer.start();
        source.set(6 * SEC, 5 * SEC);
        assertEquals(4 * SEC, timer.excludedNanos(), "[1 s, 6 s] qualifies, only [2 s, 6 s] is after the baseline");
        assertEquals(0, timer.activeNanos());
        source.set(10 * SEC, 5 * SEC);
        assertEquals(4 * SEC, timer.excludedNanos(), "the finished stall adds nothing more");
        assertEquals(4 * SEC, timer.activeNanos());
        assertFalse(timer.expired());
    }

    @Test void cumulativeTimerStopAndRestartRebaselines() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        timer.start();
        source.set(5 * SEC, 2 * SEC);
        assertEquals(2 * SEC, timer.excludedNanos());
        int samples = source.samples;
        timer.stop();
        assertFalse(timer.running());
        assertEquals(0, timer.excludedNanos());
        assertEquals(0, timer.activeNanos());
        assertFalse(timer.expired());
        assertFalse(timer.stallConsumed());
        assertEquals(samples, source.samples, "a stopped timer reads nothing");
        source.set(10 * SEC, 5 * SEC);
        timer.start();
        source.set(14 * SEC, 9 * SEC);
        assertEquals(4 * SEC, timer.excludedNanos(), "only the new wait's stall time counts");
        assertEquals(0, timer.activeNanos());
        assertEquals(samples + 3, source.samples, "the restart rebaselined from one reading, then two observations");
    }

    @Test void cumulativeResourceWaitExcludesNoStallTime() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, false);
        timer.start();
        source.set(9 * SEC, 8 * SEC);
        assertEquals(0, timer.excludedNanos());
        assertFalse(timer.stallConsumed());
        assertEquals(9 * SEC, timer.activeNanos());
        assertFalse(timer.expired(), "9 s of transport time, none of it excluded");
        source.set(10 * SEC, 9 * SEC);
        assertTrue(timer.expired());
        assertEquals(10 * SEC, timer.activeNanos());
        assertEquals(0, timer.excludedNanos());
    }

    @Test void cumulativeTimerExclusionStaysCappedAtSixtySeconds() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        timer.start();
        source.set(30 * SEC, 25 * SEC);
        assertEquals(25 * SEC, timer.excludedNanos());
        assertEquals(5 * SEC, timer.activeNanos());
        assertFalse(timer.expired());
        source.set(100 * SEC, 80 * SEC);
        assertEquals(60 * SEC, timer.excludedNanos(), "the allowance saturates");
        assertEquals(40 * SEC, timer.activeNanos());
        source.set(200 * SEC, 180 * SEC);
        assertEquals(60 * SEC, timer.excludedNanos());
        assertEquals(140 * SEC, timer.activeNanos());
        assertTrue(timer.expired());
    }

    @Test void cumulativeTimerExpiresAtExactlyTenActiveSeconds() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        timer.start();
        source.set(10 * SEC - 1, 0);
        assertFalse(timer.expired());
        source.set(10 * SEC, 0);
        assertTrue(timer.expired(), "10 s of active time is the deadline");

        Source stalledSource = new Source();
        BlockedHeadTimer stalled = new BlockedHeadTimer(stalledSource, true);
        stalled.start();
        stalledSource.set(6 * SEC, 3 * SEC);
        assertFalse(stalled.expired());
        stalledSource.set(12 * SEC, 3 * SEC);
        assertFalse(stalled.expired(), "9 s of active time");
        stalledSource.set(13 * SEC, 3 * SEC);
        assertTrue(stalled.expired(), "the same 3 s of stall pushes the deadline by 3 s");
    }

    @Test void cumulativeTimerRepeatedReadsSampleOncePerObservation() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        timer.start();
        source.set(6 * SEC, 5 * SEC);
        int before = source.samples;
        assertEquals(5 * SEC, timer.excludedNanos());
        assertEquals(1, source.samples - before);
        assertEquals(SEC, timer.activeNanos());
        assertEquals(2, source.samples - before);
        assertFalse(timer.expired());
        assertEquals(3, source.samples - before);
        assertTrue(timer.stallConsumed());
        assertEquals(4, source.samples - before);
        assertEquals(5 * SEC, timer.excludedNanos(), "reading again changes nothing");
        assertEquals(5, source.samples - before);
    }

    @Test void cumulativeTimerHandlesNegativeOriginAndSignedWrap() {
        Source negative = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(negative, true);
        negative.set(-20 * SEC, -3 * SEC);
        timer.start();
        negative.set(-8 * SEC, -1 * SEC);
        assertEquals(2 * SEC, timer.excludedNanos(), "12 s elapsed, 2 s of it excluded");
        assertEquals(10 * SEC, timer.activeNanos());

        Source wrap = new Source();
        BlockedHeadTimer wrapped = new BlockedHeadTimer(wrap, true);
        long origin = Long.MAX_VALUE - 3 * SEC;
        wrap.set(origin, Long.MAX_VALUE - SEC);
        wrapped.start();
        wrap.set(origin + 6 * SEC, Long.MAX_VALUE - SEC + 5 * SEC);
        assertTrue(wrap.nowNanos < 0, "the reading wrapped past Long.MAX_VALUE");
        assertEquals(5 * SEC, wrapped.excludedNanos(), "a 5 s counter progression across the wrap");
        assertEquals(SEC, wrapped.activeNanos());
    }

    @Test void cumulativeTimerRejectsInvalidSamplesWithoutMutatingState() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        timer.start();
        source.set(5 * SEC, 2 * SEC);
        assertEquals(2 * SEC, timer.excludedNanos());
        source.valid = false;
        assertThrows(StallClock.UnavailableException.class, timer::excludedNanos);
        assertThrows(StallClock.UnavailableException.class, timer::activeNanos);
        assertThrows(StallClock.UnavailableException.class, timer::expired);
        assertThrows(StallClock.UnavailableException.class, timer::stallConsumed);
        source.valid = true;
        source.set(10 * SEC, 3 * SEC);
        assertEquals(3 * SEC, timer.excludedNanos(), "the rejected readings moved nothing");
        assertEquals(7 * SEC, timer.activeNanos());
    }

    @Test void cumulativeTimerRejectsMalformedSamplesWithoutMutatingState() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        timer.start();
        source.set(5 * SEC, 2 * SEC);
        assertEquals(2 * SEC, timer.excludedNanos());
        source.set(4 * SEC, 2 * SEC);
        assertThrows(StallClock.UnavailableException.class, timer::excludedNanos, "backwards time");
        source.set(6 * SEC, SEC);
        assertThrows(StallClock.UnavailableException.class, timer::excludedNanos, "backwards cumulative counter");
        source.set(7 * SEC, 6 * SEC);
        assertThrows(StallClock.UnavailableException.class, timer::excludedNanos, "excluded delta above elapsed delta");
        source.set(8 * SEC, 5 * SEC);
        assertEquals(5 * SEC, timer.excludedNanos(), "the rejected readings moved nothing");
        assertEquals(3 * SEC, timer.activeNanos());
    }

    @Test void cumulativeTimerStartsFromOneBaselineAndIgnoresRepeatedStart() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        source.set(5 * SEC, 2 * SEC);
        timer.start();
        assertEquals(1, source.samples);
        source.set(8 * SEC, 2 * SEC);
        timer.start();
        assertEquals(1, source.samples, "a repeated start does not read the source again");
        assertEquals(3 * SEC, timer.activeNanos(), "the wait still started at the first baseline");
    }

    @Test void cumulativeTimerNotStartedObservesNothing() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        assertFalse(timer.running());
        assertEquals(0, timer.excludedNanos());
        assertEquals(0, timer.activeNanos());
        assertFalse(timer.expired());
        assertFalse(timer.stallConsumed());
        assertEquals(0, source.samples, "a wait that never started never reads its source");
    }

    @Test void cumulativeTimerStartRejectsAnInvalidSourceWithoutStarting() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        source.valid = false;
        assertThrows(StallClock.UnavailableException.class, timer::start);
        assertFalse(timer.running(), "an untrusted reading never captures a baseline");
        assertEquals(1, source.samples, "the rejected start took one reading");
        source.valid = true;
        source.set(4 * SEC, 0);
        timer.start();
        assertEquals(2, source.samples);
        source.set(5 * SEC, 0);
        assertEquals(SEC, timer.activeNanos());
    }

    @Test void cumulativeTimerRejectsBoundaryStallHooksEvenWhenStopped() {
        Source source = new Source();
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        assertThrows(IllegalStateException.class, timer::stallStarted);
        assertThrows(IllegalStateException.class, () -> timer.stallStarted(SEC));
        assertThrows(IllegalStateException.class, timer::stallEnded);
        assertThrows(IllegalStateException.class, () -> timer.stallEnded(SEC));
        timer.start();
        assertThrows(IllegalStateException.class, timer::stallStarted);
        assertThrows(IllegalStateException.class, () -> timer.stallEnded(SEC));
        timer.stop();
        assertThrows(IllegalStateException.class, timer::stallStarted);
        assertThrows(IllegalStateException.class, timer::stallEnded);
    }

    @Test void cumulativeTimerRejectsANullSource() {
        assertThrows(IllegalArgumentException.class, () -> new BlockedHeadTimer((StallClock) null, true));
    }

    /** Deterministic cumulative source: one scripted reading per {@link #sample()} call. */
    private static final class Source implements StallClock {
        long nowNanos;
        long excludedNanos;
        boolean valid = true;
        int samples;

        void set(long nowNanos, long excludedNanos) {
            this.nowNanos = nowNanos;
            this.excludedNanos = excludedNanos;
        }

        @Override public Sample sample() {
            samples++;
            final long now = nowNanos;
            final long excluded = excludedNanos;
            final boolean valid = this.valid;
            return new Sample() {
                @Override public long nowNanos() { return now; }
                @Override public long excludedNanos() { return excluded; }
                @Override public boolean valid() { return valid; }
            };
        }
    }
}
