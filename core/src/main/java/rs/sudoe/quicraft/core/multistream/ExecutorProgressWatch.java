// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.function.LongSupplier;

/**
 * Cumulative progress accounting for one actual game-executor incarnation (docs/protocol.md 13.10).
 *
 * <p>A probe submitted to the game executor is the evidence the watch works from. While exactly one
 * probe is pending, every interval in which the executor completed nothing for at least
 * {@link #THRESHOLD_NANOS} qualifies: it begins one second after the probe submission or after the
 * latest later executor completion, whichever is later, so the first qualifying second is never
 * excluded, and it ends at the completion that closes it. Closed intervals accumulate into
 * {@link Snapshot#excludedTotalNanos}; a sample additionally reports the currently open interval as
 * of its reading, without consuming it, so repeated or late sampling never double counts. A matching
 * {@link #probeCompleted(long)} accrues the open interval, clears the pending probe and marks the
 * watch proven; readiness is therefore a probe that demonstrably completed on this executor
 * incarnation, never the existence of the watch or any flag an owner set. Proven is sticky: a later
 * reservation does not clear it, and consumers treat readiness as {@code valid && proven}.
 *
 * <p>One watch belongs to one actual executor incarnation. When the game executor is replaced, the
 * owner creates a new watch and discards the old one; tokens and accounting never carry over. Probe
 * tokens are positive; {@code 0} is the documented "no new probe" answer of
 * {@link #probeSubmitted()} (one is already pending, or the watch is invalid) and is never issued as
 * a token. An unrelated completion can restart the pending probe's one-second clock but can never
 * prove readiness.
 *
 * <p>Clock discipline: all state and time acquisition happen on this watch's monitor. Timestamps are
 * readings of the supplied monotonic clock, compared as signed differences of readings under the
 * assumption that two readings compared for one interval are less than half a long range apart, so a
 * negative clock origin ({@link System#nanoTime()}) and a signed wrap are fine. Closing or sampling
 * recomputes the open interval from elapsed subtraction, never from stored negative sentinels, so a
 * delayed observer sees the same exclusion as a timely one. Cumulative totals are plain long sums
 * and may wrap modulo long; consumers compare bounded differences.
 *
 * <p>Invalidation is final: the open interval is accrued through the reading at which it happens,
 * later time and completions are ignored, and {@link Snapshot#valid} stays false, so an invalid
 * watch can never become ready again. Invalidation preserves the accrued accounting and the proven
 * flag for the record, but a snapshot is only ready when {@code valid && proven}.
 *
 * <p>Thread-safe: probes are reserved and completed across the owner and executor threads.
 */
public final class ExecutorProgressWatch {
    /** 13.10: a stall is known only after the executor completes nothing for at least one second. */
    public static final long THRESHOLD_NANOS = 1_000_000_000L;

    private final LongSupplier clock;

    private boolean valid = true;
    private boolean proven;
    private long pendingToken;
    private long nextToken = 1;
    private long lastProgressNanos;
    private long excludedTotalNanos;

    /** @param clock the monotonic transport clock shared with the sessions */
    public ExecutorProgressWatch(LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock");
        }
        this.clock = clock;
    }

    /**
     * An immutable reading of the accounting. {@code sampledAtNanos} is the clock reading the sample
     * was taken at, {@code excludedTotalNanos} the cumulative qualifying intervals through that
     * reading with the currently open interval included.
     */
    public static final class Snapshot {
        /** The monotonic clock reading this snapshot was taken at. */
        public final long sampledAtNanos;
        /** Cumulative qualifying stall time through {@link #sampledAtNanos}, open interval included. */
        public final long excludedTotalNanos;
        /** False once the watch was invalidated; the accounting is then final and unusable. */
        public final boolean valid;
        /** True once a matching probe completed while the watch was valid; readiness is {@code valid && proven}. */
        public final boolean proven;

        private Snapshot(long sampledAtNanos, long excludedTotalNanos, boolean valid, boolean proven) {
            this.sampledAtNanos = sampledAtNanos;
            this.excludedTotalNanos = excludedTotalNanos;
            this.valid = valid;
            this.proven = proven;
        }

        @Override
        public String toString() {
            return "Snapshot[sampledAtNanos=" + sampledAtNanos
                    + ", excludedTotalNanos=" + excludedTotalNanos
                    + ", valid=" + valid + ", proven=" + proven + ']';
        }
    }

    /** Takes a reading of the cumulative accounting, the currently open interval included. */
    public synchronized Snapshot sample() {
        long now = clock.getAsLong();
        long excluded = excludedTotalNanos;
        if (pendingToken != 0) {
            long elapsed = now - lastProgressNanos;
            if (elapsed > THRESHOLD_NANOS) {
                excluded += elapsed - THRESHOLD_NANOS;
            }
        }
        return new Snapshot(now, excluded, valid, proven);
    }

    /**
     * Records that the executor completed a task now. While a probe is pending this closes the
     * qualifying interval (when there is one), accrues it and restarts qualification from this
     * completion; with no probe pending, or with a stale or duplicate reading, it does nothing.
     */
    public synchronized void taskCompleted() {
        if (pendingToken == 0) {
            return;
        }
        long now = clock.getAsLong();
        long elapsed = now - lastProgressNanos;
        if (elapsed <= 0) {
            return;
        }
        if (elapsed > THRESHOLD_NANOS) {
            excludedTotalNanos += elapsed - THRESHOLD_NANOS;
        }
        lastProgressNanos = now;
    }

    /**
     * Reserves the single pending probe and returns its positive token, or {@code 0} when no probe
     * can be issued: one is already pending, or the watch is invalid. The reserved token must be
     * submitted to the executor and reported back to {@link #probeCompleted(long)}; a rejected
     * submission is handled by the owner invalidating the watch, and further calls while a probe is
     * pending never replace it.
     */
    synchronized long probeSubmitted() {
        if (!valid || pendingToken != 0) {
            return 0;
        }
        long token = nextToken;
        nextToken = token == Long.MAX_VALUE ? 1 : token + 1;
        pendingToken = token;
        lastProgressNanos = clock.getAsLong();
        return token;
    }

    /**
     * Reports the completion of the probe reserved under {@code token}. A matching token accrues the
     * open interval through now, clears the pending probe and marks the watch proven. A stale or
     * duplicate token, a token of {@code 0}, a completion after invalidation and a completion with no
     * pending probe are all ignored: only the one reserved token can prove readiness.
     */
    public synchronized void probeCompleted(long token) {
        if (!valid || token == 0 || token != pendingToken) {
            return;
        }
        long now = clock.getAsLong();
        long elapsed = now - lastProgressNanos;
        if (elapsed > THRESHOLD_NANOS) {
            excludedTotalNanos += elapsed - THRESHOLD_NANOS;
        }
        pendingToken = 0;
        proven = true;
    }

    /**
     * Invalidates the watch forever: the open interval is accrued through now, no later interval
     * time or completion is ever accounted, and snapshots stay {@code valid=false}. Idempotent.
     */
    public synchronized void invalidate() {
        if (!valid) {
            return;
        }
        if (pendingToken != 0) {
            long now = clock.getAsLong();
            long elapsed = now - lastProgressNanos;
            if (elapsed > THRESHOLD_NANOS) {
                excludedTotalNanos += elapsed - THRESHOLD_NANOS;
            }
            pendingToken = 0;
        }
        valid = false;
    }
}
