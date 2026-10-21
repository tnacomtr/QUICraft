// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import rs.sudoe.quicraft.core.time.StallClock;

/**
 * Transport-clock timer for a dependency-blocked lane head or a refused local resource wait
 * (protocol.md 13.10 and 13.12).
 *
 * <p>The wait starts once and is never reset when prerequisites change: {@link #start()} while the timer
 * is already running is ignored. The base is 10 seconds of transport time, measured from the wait's start
 * or from its last renewal. A blocked head additionally excludes recorded game-executor tick stalls, but
 * only up to {@link #STALL_ALLOWANCE_NANOS} in total per wait; after that allowance is used up, further
 * stall time counts. A blocked head also has a nonrenewable {@link #RETENTION_NANOS} active-time deadline
 * from the wait's start, so its retention is at most 120 seconds of active time plus the 60 second
 * allowance, 180 seconds. Qualified progress renews only the 10 second base, through {@link #renew()}, and
 * only when the owning receiver decides so (see {@link #addCredit(long)}). Local resource waits construct
 * the timer with {@code excludeStalls=false}, so executor stalls never pause them, and they are never
 * renewed.
 *
 * <p>The timer has two modes and never mixes them. A timer constructed with a {@link LongSupplier} is
 * boundary-driven: stall intervals are reported with {@link #stallStarted} and {@link #stallEnded} and
 * every reported interval is clipped to the running wait with wait-relative offsets: an interval that
 * starts before the wait contributes only from the wait's start, and an end reported past the current
 * clock reading contributes only up to that reading. Overlapping intervals are counted once, but only
 * under the precondition that interval pairs arrive in nondecreasing start-timestamp order (an end may
 * overlap the next start). An earlier start reported after a later one is out of order and not supported:
 * that interval counts only the time after the already covered end, not the full union. A timer
 * constructed with a {@link StallClock} is cumulative: {@link #start()} captures one valid baseline
 * reading, and every observation takes exactly one new reading of the same source from which it derives
 * the wait-relative elapsed time and the excluded stall time as differences from that baseline. The
 * cumulative timer has no boundary hooks: {@link #stallStarted()}, {@link #stallStarted(long)},
 * {@link #stallEnded()} and {@link #stallEnded(long)} throw {@link IllegalStateException} in that mode,
 * running or not. A boundary timer has no cumulative observations, so there is nothing to reject in it.
 *
 * <p>Cumulative readings are validated by subtraction under the half-range assumption: a backwards
 * time, a backwards cumulative counter, an excluded delta larger than the elapsed delta, or an invalid
 * source makes every observation throw {@link StallClock.UnavailableException}, and validation state
 * advances only after the checks pass, so a rejected reading changes nothing. Signed clock and counter
 * wrap are fine, a reading identical to the previous one is fine, and negative timestamps are never
 * sentinels: an explicit initialization flag marks the captured baseline. A cumulative {@link #start()}
 * that samples an invalid source throws and leaves the timer unstarted, so the wait can be started again
 * later; it never rebaselines from a reading it does not trust.
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
    /** Nonrenewable active-time retention of a blocked head, 120 seconds (13.10, 13.12). */
    public static final long RETENTION_NANOS = TimeUnit.SECONDS.toNanos(120);
    /** Qualifying encoded wire bytes that renew the base deadline, 64 KiB (13.10). */
    public static final long RENEWAL_BYTES = 64L * 1024L;

    /** The boundary clock; null in cumulative mode. */
    private final LongSupplier clock;
    /** The cumulative stall source; null in boundary mode. One source is one incarnation. */
    private final StallClock source;
    private final boolean excludeStalls;

    // Boundary mode state.
    private boolean running;
    private long startedAt;
    private long excluded;
    private boolean covered;
    private long coveredEndOffset;
    private boolean inStall;
    private long stallBeganOffset;

    // Renewal state, shared by both modes: active time at the last renewal (0 for the wait's start) and the
    // qualifying bytes credited since then, saturating at RENEWAL_BYTES.
    private long renewedActiveNanos;
    private long creditBytes;

    // Cumulative mode state: an explicit initialization flag, never a negative sentinel.
    private boolean initialized;
    private long baseNowNanos;
    private long baseExcludedNanos;
    private long lastNowNanos;
    private long lastExcludedNanos;

    /**
     * Creates a boundary timer driven by stall notifications.
     *
     * @param clock monotonic nanosecond transport clock, not wall time
     * @param excludeStalls true for a blocked head (stall exclusion applies), false for resource waits
     */
    public BlockedHeadTimer(LongSupplier clock, boolean excludeStalls) {
        if (clock == null) {
            throw new IllegalArgumentException("clock");
        }
        this.clock = clock;
        this.source = null;
        this.excludeStalls = excludeStalls;
    }

    /**
     * Creates a cumulative timer driven by one coherent stall accounting reading per observation.
     *
     * @param source the stall accounting source of the executor incarnation, never replaced
     * @param excludeStalls true for a blocked head (stall exclusion applies), false for resource waits
     */
    public BlockedHeadTimer(StallClock source, boolean excludeStalls) {
        if (source == null) {
            throw new IllegalArgumentException("source");
        }
        this.clock = null;
        this.source = source;
        this.excludeStalls = excludeStalls;
    }

    /** Starts the single wait. Calls while already running are ignored, so the wait never resets. */
    public void start() {
        if (running) {
            return;
        }
        if (source == null) {
            running = true;
            startedAt = clock.getAsLong();
            excluded = 0;
            covered = false;
            coveredEndOffset = 0;
            inStall = false;
            stallBeganOffset = 0;
            renewedActiveNanos = 0;
            creditBytes = 0;
            return;
        }
        StallClock.Sample sample = source.sample();
        if (!sample.valid()) {
            throw new StallClock.UnavailableException("stall source is not valid");
        }
        baseNowNanos = sample.nowNanos();
        baseExcludedNanos = sample.excludedNanos();
        lastNowNanos = baseNowNanos;
        lastExcludedNanos = baseExcludedNanos;
        initialized = true;
        renewedActiveNanos = 0;
        creditBytes = 0;
        running = true;
    }

    /**
     * Credits qualifying encoded wire bytes toward the next renewal (13.10). The receiver calls this once per
     * waiting head for each qualifying commit. Credit saturates at {@link #RENEWAL_BYTES} and is discarded by
     * {@link #renew()}.
     */
    public void addCredit(long bytes) {
        if (bytes <= 0) {
            return;
        }
        creditBytes = bytes >= RENEWAL_BYTES - creditBytes ? RENEWAL_BYTES : creditBytes + bytes;
    }

    /** Reports whether the credit since the last renewal reached {@link #RENEWAL_BYTES}. */
    public boolean creditReached() {
        return creditBytes >= RENEWAL_BYTES;
    }

    /**
     * Renews the 10 second base from the current active time and discards any surplus credit (13.10). The
     * receiver calls it only for an unexpired wait that has qualified progress; it is a no-op on a stopped
     * timer. The absolute retention deadline is unaffected.
     */
    public void renew() {
        if (!running) {
            return;
        }
        renewedActiveNanos = activeNanos();
        creditBytes = 0;
    }

    /**
     * As {@link #renew()}, from a given observation, so a renewal and the expiry decision that preceded it use the
     * same reading. A null observation (a stopped timer) is a no-op.
     */
    public void renew(Observation now) {
        if (now == null) {
            return;
        }
        renewedActiveNanos = now.activeNanos();
        creditBytes = 0;
    }

    /** As {@link #expired()}, from a given observation (null, for a stopped timer, is not expired). */
    public boolean expired(Observation now) {
        return now != null && now.activeNanos() - renewedActiveNanos >= TIMEOUT_NANOS;
    }

    /** As {@link #absoluteExpired()}, from a given observation (null, for a stopped timer, is not expired). */
    public boolean absoluteExpired(Observation now) {
        return now != null && now.activeNanos() >= RETENTION_NANOS;
    }

    /**
     * Reports whether the base deadline has been reached: 10 seconds of active time since the wait's start or
     * its last renewal. A resource wait is never renewed, so for it this is the 10 second base from the start.
     */
    public boolean expired() {
        if (source != null) {
            Observation observation = observe();
            return observation != null && observation.activeNanos() - renewedActiveNanos >= TIMEOUT_NANOS;
        }
        return running && activeNanos() - renewedActiveNanos >= TIMEOUT_NANOS;
    }

    /**
     * Reports whether the nonrenewable retention deadline has been reached: {@link #RETENTION_NANOS} of active
     * time since the wait's start, independent of renewals (13.10, 13.12).
     */
    public boolean absoluteExpired() {
        if (source != null) {
            Observation observation = observe();
            return observation != null && observation.activeNanos() >= RETENTION_NANOS;
        }
        return running && activeNanos() >= RETENTION_NANOS;
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
        requireBoundaryMode();
        stallStarted(clock.getAsLong());
    }

    /**
     * Records the start of a tick stall at an explicit monotonic time (13.10's clock discipline: the stall
     * is timestamped by the executor's own completion record, not by when a watchdog noticed it). The start
     * is taken only while the wait is running and is stored as an offset from the wait's start, so an
     * interval that began before the wait contributes only from the wait's start. While an interval is
     * open, further start notifications are ignored. A cumulative timer has no boundary notifications, so
     * this throws there.
     */
    public void stallStarted(long atNanos) {
        requireBoundaryMode();
        if (running && excludeStalls && !inStall) {
            inStall = true;
            stallBeganOffset = waitOffset(atNanos);
        }
    }

    /** Records the end of a tick stall, now (LaneGame hook). */
    public void stallEnded() {
        requireBoundaryMode();
        stallEnded(clock.getAsLong());
    }

    /**
     * Records the end of a tick stall at an explicit monotonic time; an end before the start excludes
     * nothing, and an end observed after the interval already happened excludes the same time it would
     * have excluded when reported live. A cumulative timer has no boundary notifications, so this throws
     * there.
     */
    public void stallEnded(long atNanos) {
        requireBoundaryMode();
        if (!inStall) {
            return;
        }
        inStall = false;
        record(atNanos);
    }

    /** Returns the stall time excluded so far, capped at the 60 second allowance. */
    public long excludedNanos() {
        if (source != null) {
            Observation observation = observe();
            return observation == null ? 0 : observation.exclusionNanos;
        }
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
        if (source != null) {
            Observation observation = observe();
            if (observation == null) {
                return 0;
            }
            return Math.max(0, observation.elapsedNanos - observation.exclusionNanos);
        }
        if (!running) {
            return 0;
        }
        long elapsed = waitOffset(clock.getAsLong());
        return Math.max(0, elapsed - excludedNanos());
    }

    /**
     * One coherent observation of this running wait: the active time and the excluded stall time derived from
     * one reading of the clock (boundary mode) or of one source sample (cumulative mode). Returns null while the
     * wait is not running, so a stopped timer reads nothing and throws nothing. A cumulative timer takes exactly
     * one new sample here.
     */
    public Observation observe() {
        if (!running) {
            return null;
        }
        if (source == null) {
            long offset = waitOffset(clock.getAsLong());
            return new Observation(offset, excludeStalls ? exclusionAt(offset) : 0);
        }
        return observe(source.sample());
    }

    /**
     * Reduces one given cumulative sample to an observation, validated against the last accepted reading of this
     * timer (13.10). A receiver that settles several waits in one step passes the same sample to each timer, so
     * every decision of that step is taken from one reading. Returns null while the wait is not running; throws
     * {@link StallClock.UnavailableException} for an invalid or malformed sample, leaving this timer unchanged.
     *
     * @throws IllegalStateException on a boundary timer, which has no cumulative samples
     */
    public Observation observe(StallClock.Sample sample) {
        if (source == null) {
            throw new IllegalStateException("a boundary timer has no cumulative sample");
        }
        if (!running || !initialized) {
            return null;
        }
        if (sample == null || !sample.valid()) {
            throw new StallClock.UnavailableException("stall source is not valid");
        }
        long now = sample.nowNanos();
        long total = sample.excludedNanos();
        long elapsedSince = now - lastNowNanos;
        long excludedSince = total - lastExcludedNanos;
        if (elapsedSince < 0 || excludedSince < 0 || excludedSince > elapsedSince) {
            throw new StallClock.UnavailableException("stall source went backwards or excluded more time than it spent");
        }
        lastNowNanos = now;
        lastExcludedNanos = total;
        long elapsed = now - baseNowNanos;
        long exclusion = 0;
        if (excludeStalls) {
            exclusion = Math.min(STALL_ALLOWANCE_NANOS, total - baseExcludedNanos);
        }
        return new Observation(elapsed, exclusion);
    }

    /** Excluded stall time at {@code nowOffset} of a running boundary timer, capped at the allowance. */
    private long exclusionAt(long nowOffset) {
        long total = excluded;
        if (inStall) {
            total = addCapped(total, coverage(nowOffset));
        }
        return total;
    }

    /** A rejected cumulative timer boundary notification. */
    private void requireBoundaryMode() {
        if (source != null) {
            throw new IllegalStateException("a cumulative timer has no boundary stall notifications");
        }
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

    /**
     * One coherent observation of a wait: every field comes from the same single reading. Decisions made from one
     * observation (expiry, phrase classification, renewal) therefore never disagree with each other.
     */
    public static final class Observation {
        private final long elapsedNanos;
        private final long exclusionNanos;

        Observation(long elapsedNanos, long exclusionNanos) {
            this.elapsedNanos = elapsedNanos;
            this.exclusionNanos = exclusionNanos;
        }

        /** Active time of this observation: elapsed minus excluded, never negative. */
        public long activeNanos() {
            return Math.max(0, elapsedNanos - exclusionNanos);
        }

        /** Reports whether any recorded stall time was excluded in this observation (blocks the phrase, 13.12). */
        public boolean stallConsumed() {
            return exclusionNanos > 0;
        }
    }
}
