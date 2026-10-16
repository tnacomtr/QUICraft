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

    @Test void stopClearsTheWait() {
        AtomicLong now = new AtomicLong();
        BlockedHeadTimer timer = new BlockedHeadTimer(now::get, true);
        timer.start();
        timer.stop();
        now.set(100 * SEC);
        assertFalse(timer.expired());
    }
}
