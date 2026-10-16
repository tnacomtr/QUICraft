// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

/**
 * Decayed byte-second and progress history for one identity or one connection (protocol.md 13.12
 * "Pressure ranking").
 *
 * <p>Retained bytes are a piecewise-constant function of time. Each history remembers the bytes it held and
 * the time it was last accrued, and integrates {@code bytes x seconds} exactly between changes. Decay with
 * the configured half-life (60 s provisionally) is applied at accrual points, not continuously within one
 * constant-bytes interval, so an interval's contribution is slightly overstated for long intervals. The
 * bias is conservative for ranking (it never hides a holder) and is documented in protocol.md 13.12 terms as
 * a provisional operational detail. Local executor-stall time reported to {@link PressureRanking} is
 * excluded through an effective cumulative stall counter.</p>
 *
 * <p>Progress is counted in a rolling 10-second window of one-second slots, so memory per history is
 * constant. Score is {@code decayedByteSeconds / (1 + progressBytesPerSecond)}.</p>
 *
 * <p>Not thread-safe; {@link PressureRanking} synchronises every call.</p>
 */
final class IdentityHistory {
    /** Length of the progress window in seconds (13.12). */
    static final int WINDOW_SECONDS = 10;
    /** Retention after last use before an inactive exact entry may be reclaimed (13.12: 10 minutes). */
    static final long RETENTION_NANOS = 600_000_000_000L;

    private final double halfLifeNanos;
    private final long[] slotSecond = new long[WINDOW_SECONDS];
    private final long[] slotBytes = new long[WINDOW_SECONDS];
    private final long firstSeenNanos;
    private double byteSeconds;
    private long bytes;
    private long asOfNanos;
    private long asOfStall;
    private long lastUseNanos;
    private int live;

    /** Creates a history that starts empty at {@code nowNanos}; {@code effStall} is the current stall counter. */
    IdentityHistory(long halfLifeNanos, long nowNanos, long effStall) {
        if (halfLifeNanos <= 0) throw new IllegalArgumentException("halfLifeNanos");
        this.halfLifeNanos = halfLifeNanos;
        this.firstSeenNanos = nowNanos;
        this.asOfNanos = nowNanos;
        this.asOfStall = effStall;
        this.lastUseNanos = nowNanos;
        java.util.Arrays.fill(slotSecond, Long.MIN_VALUE);
    }

    /** Integrates held bytes up to {@code nowNanos}, excluding stall time, and makes that the new reference. */
    void accrue(long nowNanos, long effStall) {
        byteSeconds = projected(nowNanos, effStall);
        asOfNanos = nowNanos;
        asOfStall = effStall;
    }

    /** Changes the held bytes at {@code nowNanos}; the old amount is integrated first. */
    void addBytes(long delta, long nowNanos, long effStall) {
        accrue(nowNanos, effStall);
        long next = bytes + delta;
        if (next < 0) throw new IllegalStateException("identity bytes went negative");
        bytes = next;
        lastUseNanos = nowNanos;
    }

    /** Records received or acknowledged progress bytes in the 10-second window. */
    void progress(long amount, long nowNanos) {
        if (amount < 0) throw new IllegalArgumentException("progress");
        long second = nowNanos / 1_000_000_000L;
        int slot = (int) Math.floorMod(second, (long) WINDOW_SECONDS);
        if (slotSecond[slot] != second) {
            slotSecond[slot] = second;
            slotBytes[slot] = 0;
        }
        slotBytes[slot] = slotBytes[slot] > Long.MAX_VALUE - amount ? Long.MAX_VALUE : slotBytes[slot] + amount;
        lastUseNanos = nowNanos;
    }

    /** Adds another history's decayed byte-seconds into this one (folding an evicted entry, 13.12). */
    void absorb(IdentityHistory other, long nowNanos, long effStall) {
        accrue(nowNanos, effStall);
        byteSeconds += other.projected(nowNanos, effStall);
    }

    /** Progress rate over the window in bytes per second, plus {@code extraRate} (a refused-wait floor shortfall). */
    double rate(long nowNanos, double extraRate) {
        long second = nowNanos / 1_000_000_000L;
        long sum = 0;
        for (int slot = 0; slot < WINDOW_SECONDS; slot++) {
            if (slotSecond[slot] > second - WINDOW_SECONDS && slotSecond[slot] <= second) {
                sum = sum > Long.MAX_VALUE - slotBytes[slot] ? Long.MAX_VALUE : sum + slotBytes[slot];
            }
        }
        double ageSeconds = Math.max(1.0, Math.min(WINDOW_SECONDS, (nowNanos - firstSeenNanos) / 1e9));
        return sum / ageSeconds + extraRate;
    }

    /**
     * Pressure score {@code decayedByteSeconds / (1 + rate)} at {@code nowNanos} (13.12 ranking). The
     * {@code extraRate} argument lifts the progress rate, used for the refused-wait floor shortfall.
     */
    double score(long nowNanos, long effStall, double extraRate) {
        return projected(nowNanos, effStall) / (1.0 + rate(nowNanos, extraRate));
    }

    /** Held bytes right now. */
    long bytes() { return bytes; }

    /** Live connections currently charged to this history (maintained by {@link PressureRanking}). */
    int live() { return live; }

    void liveDelta(int delta) {
        live += delta;
        if (live < 0) throw new IllegalStateException("live count went negative");
    }

    /** True when nothing is live or held and the history has been unused for the retention period. */
    boolean expired(long nowNanos) {
        return live == 0 && bytes == 0 && nowNanos - lastUseNanos >= RETENTION_NANOS;
    }

    /** Last use time, for least-recently-used decisions. */
    long lastUseNanos() { return lastUseNanos; }

    /** Decayed byte-seconds projected to {@code nowNanos} without changing state. */
    double projected(long nowNanos, long effStall) {
        long elapsed = nowNanos - asOfNanos;
        long excluded = effStall - asOfStall;
        long effective = Math.max(0L, elapsed - excluded);
        return byteSeconds * Math.pow(0.5, elapsed / halfLifeNanos) + bytes * (effective / 1e9);
    }
}
