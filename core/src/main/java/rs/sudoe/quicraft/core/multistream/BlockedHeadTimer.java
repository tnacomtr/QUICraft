// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Transport-clock timer for a dependency-blocked lane head or a refused local resource wait
 * (protocol.md 13.10 and 13.12).
 *
 * <p>The wait starts once and is never reset when prerequisites change. It runs on the local
 * monotonic transport clock. For a blocked head it excludes recorded game-executor tick stalls,
 * but only up to {@link #STALL_ALLOWANCE_NANOS} in total per wait; after that allowance is used up,
 * further stall time counts and the timer expires. The 10 second base and 60 second allowance give
 * a retention of at most 70 seconds. Local resource waits construct the timer with
 * {@code excludeStalls=false}, so executor stalls never pause them.
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
    private long stallTotal;
    private boolean inStall;
    private long stallBeganAt;

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
        stallTotal = 0;
        inStall = false;
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

    /** Records the start of a tick stall observed by the game executor (LaneGame hook). */
    public void stallStarted() {
        if (running && excludeStalls && !inStall) {
            inStall = true;
            stallBeganAt = clock.getAsLong();
        }
    }

    /** Records the end of a tick stall observed by the game executor (LaneGame hook). */
    public void stallEnded() {
        if (inStall) {
            stallTotal += clock.getAsLong() - stallBeganAt;
            inStall = false;
        }
    }

    /** Returns the stall time excluded so far, capped at the 60 second allowance. */
    public long excludedNanos() {
        if (!running || !excludeStalls) {
            return 0;
        }
        long total = stallTotal + (inStall ? clock.getAsLong() - stallBeganAt : 0);
        return Math.min(total, STALL_ALLOWANCE_NANOS);
    }

    /** Reports whether any recorded stall time was excluded, which blocks the 0x12 phrase (13.12). */
    public boolean stallConsumed() {
        return excludedNanos() > 0;
    }

    /** Returns the active wait time: elapsed transport time minus the excluded stall time. */
    public long activeNanos() {
        if (!running) {
            return 0;
        }
        return (clock.getAsLong() - startedAt) - excludedNanos();
    }

    /** Reports whether the wait has reached its 10 second base. */
    public boolean expired() {
        return running && activeNanos() >= TIMEOUT_NANOS;
    }
}
