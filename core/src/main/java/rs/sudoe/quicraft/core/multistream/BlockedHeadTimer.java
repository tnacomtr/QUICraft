// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Transport-clock timer for a dependency-blocked lane head or a refused local resource wait
 * (protocol.md 13.10 and 13.12).
 *
 * <p>The wait starts once and is never reset when prerequisites change: {@link #start()} while the timer
 * is already running is ignored. The base is 10 seconds of transport time. A blocked head additionally
 * excludes recorded game-executor tick stalls, but only up to {@link #STALL_ALLOWANCE_NANOS} in total per
 * wait; after that allowance is used up, further stall time counts and the timer expires. The 10 second
 * base and 60 second allowance give a retention of at most 70 seconds. Local resource waits construct the
 * timer with {@code excludeStalls=false}, so executor stalls never pause them.
 *
 * <p>Stall notifications are accepted only while the wait is running, and every reported interval is
 * clipped to that wait with wait-relative offsets: an interval that starts before the wait contributes
 * only from the wait's start, and an end reported past the current clock reading contributes only up to
 * that reading. Overlapping intervals are counted once, but only under the precondition that interval
 * pairs arrive in nondecreasing start-timestamp order (an end may overlap the next start). An earlier
 * start reported after a later one is out of order and not supported: that interval counts only the time
 * after the already covered end, not the full union. The recorded exclusion saturates at the allowance
 * before any addition, so it can neither overflow nor go negative. Late observation of a completed
 * interval yields the same exclusion as timely observation; nothing is applied retroactively to a wait
 * that already ended.
 *
 * <p>All timestamps compared here are readings of the timer's clock. Offsets are differences of readings,
 * so clocks with negative origins (for example {@link System#nanoTime()}) are fine, under the assumption
 * that two readings compared for one wait are less than half a long range apart, which makes the
 * difference unambiguous (including a signed long wrap of the clock). Interval pairs are likewise
 * expected in nondecreasing start order under that same comparison.
 *
 * <p>Not thread-safe; the owning receive path drives it.
 */
public final class BlockedHeadTimer {
    /** Base expiry, 10 seconds of transport time (13.10, 13.12). */
    public static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** Total recorded tick-stall time excluded per wait, 60 seconds (13.10). */
    public static final long STALL_ALLOWANCE_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final LongSupplier clock;
    private final boolean excludeStalls;
    private boolean running;
    private long startedAt;
    private long excluded;
    private boolean covered;
    private long coveredEndOffset;
    private boolean inStall;
    private long stallBeganOffset;

    /**
     * @param clock monotonic nanosecond transport clock, not wall time
     * @param excludeStalls true for a blocked head (stall exclusion applies), false for resource waits
     */
    public BlockedHeadTimer(LongSupplier clock, boolean excludeStalls) {
        if (clock == null) {
            throw new IllegalArgumentException("clock");
        }
        this.clock = clock;
        this.excludeStalls = excludeStalls;
    }

    /** Starts the single wait. Calls while already running are ignored, so the wait never resets. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        startedAt = clock.getAsLong();
        excluded = 0;
        covered = false;
        coveredEndOffset = 0;
        inStall = false;
        stallBeganOffset = 0;
    }

    /** Stops the wait, for example when the head is enqueued (Q) or released. */
    public void stop() {
        running = false;
        inStall = false;
    }

    /** Reports whether the wait is running. */
    public boolean running() {
        return running;
    }

    /** Records the start of a tick stall observed by the game executor, now (LaneGame hook). */
    public void stallStarted() {
        stallStarted(clock.getAsLong());
    }

    /**
     * Records the start of a tick stall at an explicit monotonic time (13.10's clock discipline: the stall
     * is timestamped by the executor's own completion record, not by when a watchdog noticed it). The start
     * is taken only while the wait is running and is stored as an offset from the wait's start, so an
     * interval that began before the wait contributes only from the wait's start. While an interval is
     * open, further start notifications are ignored.
     */
    public void stallStarted(long atNanos) {
        if (running && excludeStalls && !inStall) {
            inStall = true;
            stallBeganOffset = waitOffset(atNanos);
        }
    }

    /** Records the end of a tick stall, now (LaneGame hook). */
    public void stallEnded() {
        stallEnded(clock.getAsLong());
    }

    /**
     * Records the end of a tick stall at an explicit monotonic time; an end before the start excludes
     * nothing, and an end observed after the interval already happened excludes the same time it would
     * have excluded when reported live.
     */
    public void stallEnded(long atNanos) {
        if (!inStall) {
            return;
        }
        inStall = false;
        record(atNanos);
    }

    /** Returns the stall time excluded so far, capped at the 60 second allowance. */
    public long excludedNanos() {
        if (!running || !excludeStalls) {
            return 0;
        }
        long total = excluded;
        if (inStall) {
            total = addCapped(total, coverage(waitOffset(clock.getAsLong())));
        }
        return total;
    }

    /** Reports whether any recorded stall time was excluded, which blocks the 0x12 phrase (13.12). */
    public boolean stallConsumed() {
        return excludedNanos() > 0;
    }

    /** Returns the active wait time: elapsed transport time minus the excluded stall time, never negative. */
    public long activeNanos() {
        if (!running) {
            return 0;
        }
        long elapsed = waitOffset(clock.getAsLong());
        return Math.max(0, elapsed - excludedNanos());
    }

    /** Reports whether the wait has reached its 10 second base. */
    public boolean expired() {
        return running && activeNanos() >= TIMEOUT_NANOS;
    }

    /** A clock reading relative to the wait start; a reading before the wait clips to zero. */
    private long waitOffset(long atNanos) {
        long offset = atNanos - startedAt;
        return offset < 0 ? 0 : offset;
    }

    /** Records a reported stall interval end, clipped to the observation and to already covered time. */
    private void record(long endNanos) {
        if (!running || !excludeStalls) {
            return;
        }
        long endOffset = waitOffset(endNanos);
        long nowOffset = waitOffset(clock.getAsLong());
        if (endOffset > nowOffset) {
            endOffset = nowOffset;
        }
        long began = stallBeganOffset;
        if (covered && began < coveredEndOffset) {
            began = coveredEndOffset;
        }
        if (began < 0) {
            began = 0;
        }
        if (endOffset <= began) {
            return;
        }
        excluded = addCapped(excluded, endOffset - began);
        covered = true;
        coveredEndOffset = endOffset;
    }

    /** Coverage of the currently open interval at {@code nowOffset}, clipped to this wait. */
    private long coverage(long nowOffset) {
        long began = stallBeganOffset;
        if (covered && began < coveredEndOffset) {
            began = coveredEndOffset;
        }
        if (began < 0) {
            began = 0;
        }
        return nowOffset > began ? nowOffset - began : 0;
    }

    /** Adds to the exclusion, saturating at the allowance before any addition can overflow. */
    private static long addCapped(long total, long delta) {
        if (delta <= 0) {
            return total;
        }
        long remaining = STALL_ALLOWANCE_NANOS - total;
        if (remaining <= 0 || delta >= remaining) {
            return STALL_ALLOWANCE_NANOS;
        }
        return total + delta;
    }
}
