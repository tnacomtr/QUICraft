// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.time;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.BlockedHeadTimer;
import rs.sudoe.quicraft.core.multistream.ExecutorProgressWatch;
import rs.sudoe.quicraft.core.resource.PressureRanking;

/**
 * Cumulative stall accounting across its consumers (13.10, 13.12): one source per executor incarnation,
 * exactly one coherent reading per operation, and an invalidated incarnation that aborts accounting while
 * still allowing the single cleanup of already-mutated state. The watch's own exclusion math is exercised
 * in {@code ExecutorProgressWatchTest}; this file covers the cross-cutting contracts through the public
 * {@link StallClock} surface.
 */
class StallClockAccountingTest {
    private static final long SEC = 1_000_000_000L;
    private static final byte[] SECRET = {1, 2, 3, 4, 5, 6, 7, 8};

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test void oneSourceDrivesATimerAndARankingWithTheSameSingleReading() throws Exception {
        Source source = new Source();
        source.set(0, 0);
        BlockedHeadTimer timer = new BlockedHeadTimer(source, true);
        timer.start();
        PressureRanking ranking = new PressureRanking(1_000_000_000L, 10, 10, SECRET, source);
        ranking.connect("c", ip("192.0.2.1"), null);
        ranking.setRetained("c", 100);
        source.set(12 * SEC, 11 * SEC);
        assertEquals(11 * SEC, timer.excludedNanos(), "the cumulative exclusion is the stall time");
        assertEquals(SEC, timer.activeNanos());
        assertTrue(timer.stallConsumed());
        assertFalse(timer.expired());
        assertEquals(100.0, ranking.score("c"), 0.01, "the ranking sees the same single effective second");
    }

    @Test void movingCoherentSourceNeverTearsThePair() throws Exception {
        MovingSource timerSource = new MovingSource();
        BlockedHeadTimer timer = new BlockedHeadTimer(timerSource, true);
        timer.start();
        assertEquals(0, timer.activeNanos(), "every second of transport time is excluded");
        assertEquals(2 * SEC, timer.excludedNanos(), "each new reading advances the cumulative exclusion");
        assertFalse(timer.expired(), "the active time stays zero");
        assertEquals(4, timerSource.samples, "each observation took exactly one reading");

        MovingSource rankingSource = new MovingSource();
        PressureRanking ranking = new PressureRanking(60_000, 10, 10, SECRET, rankingSource);
        ranking.connect("c", ip("192.0.2.2"), null);
        ranking.setRetained("c", 100);
        assertEquals(0.0, ranking.score("c"), 1e-9, "every second of transport time was excluded");
        assertEquals(3, rankingSource.samples, "connect, retain and score each read the source once");
    }

    @Test void invalidatingTheSourceAbortsConsumersAndStillAllowsOneCleanup() throws Exception {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        BlockedHeadTimer timer = new BlockedHeadTimer(watch, true);
        timer.start();
        PressureRanking ranking = new PressureRanking(1_000_000_000L, 10, 10, SECRET, watch);
        ranking.connect("c", ip("192.0.2.3"), null);
        ranking.setRetained("c", 100);
        now.set(10 * SEC);
        watch.invalidate();
        assertThrows(StallClock.UnavailableException.class, timer::excludedNanos);
        assertThrows(StallClock.UnavailableException.class, timer::activeNanos);
        assertThrows(StallClock.UnavailableException.class, timer::expired);
        assertThrows(StallClock.UnavailableException.class, () -> ranking.score("c"));
        assertThrows(StallClock.UnavailableException.class, () -> ranking.setRetained("c", 50));
        now.set(12 * SEC);
        ranking.disconnect("c");
        assertEquals(0.0, ranking.score("c"), 0.0, "the released owner scores zero without a reading");
        ranking.disconnect("c");
        assertThrows(StallClock.UnavailableException.class, timer::expired, "the invalid incarnation stays aborted");
    }

    @Test void unavailableExceptionIsAnIllegalStateException() {
        assertTrue(IllegalStateException.class.isAssignableFrom(StallClock.UnavailableException.class));
    }

    /** A moving but coherent source: every reading advances time and exclusion by one second. */
    private static final class MovingSource implements StallClock {
        int samples;

        @Override public Sample sample() {
            samples++;
            final long reading = samples * SEC;
            return new Sample() {
                @Override public long nowNanos() { return reading; }
                @Override public long excludedNanos() { return reading; }
                @Override public boolean valid() { return true; }
            };
        }
    }

    /** Scripted single-reading-at-a-time source. */
    private static final class Source implements StallClock {
        long nowNanos;
        long excludedNanos;

        void set(long nowNanos, long excludedNanos) {
            this.nowNanos = nowNanos;
            this.excludedNanos = excludedNanos;
        }

        @Override public Sample sample() {
            final long now = nowNanos;
            final long excluded = excludedNanos;
            return new Sample() {
                @Override public long nowNanos() { return now; }
                @Override public long excludedNanos() { return excluded; }
                @Override public boolean valid() { return true; }
            };
        }
    }
}
