// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

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
}
