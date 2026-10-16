// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

/** Decayed byte-second pressure history (protocol.md 13.12). */
public final class IdentityHistory {
    private final double halfLifeNanos;
    private double byteSeconds;
    private long lastNanos;
    private long progressBytes;
    private long progressStartNanos;

    /** Creates a history with a decay half-life. */
    public IdentityHistory(long halfLifeMillis, long nowNanos) {
        if (halfLifeMillis <= 0) throw new IllegalArgumentException("halfLifeMillis");
        halfLifeNanos = halfLifeMillis * 1_000_000.0;
        lastNanos = progressStartNanos = nowNanos;
    }

    /** Integrates retained bytes, excluding explicitly known local stall time. */
    public synchronized void integrate(long retainedBytes, long elapsedNanos, long excludedNanos) {
        if (retainedBytes < 0 || elapsedNanos < 0 || excludedNanos < 0 || excludedNanos > elapsedNanos)
            throw new IllegalArgumentException("invalid integration");
        double seconds = (elapsedNanos - excludedNanos) / 1_000_000_000.0;
        byteSeconds = byteSeconds * Math.pow(0.5, elapsedNanos / halfLifeNanos) + retainedBytes * seconds;
    }

    /** Adds newly received or acknowledged progress bytes. */
    public synchronized void progress(long bytes, long nowNanos) {
        if (bytes < 0) throw new IllegalArgumentException("bytes");
        if (nowNanos - progressStartNanos >= 10_000_000_000L) {
            progressBytes = 0;
            progressStartNanos = nowNanos;
        }
        progressBytes = bytes > Long.MAX_VALUE - progressBytes ? Long.MAX_VALUE : progressBytes + bytes;
        lastNanos = nowNanos;
    }

    /** Returns the pressure score used by pool victim ranking. */
    public synchronized double score(long nowNanos) {
        double ageSeconds = Math.max(0, nowNanos - progressStartNanos) / 1_000_000_000.0;
        double rate = progressBytes / Math.max(1.0, Math.min(10.0, ageSeconds));
        return byteSeconds * Math.pow(0.5, Math.max(0, nowNanos - lastNanos) / halfLifeNanos) / (1.0 + rate);
    }

    /** Returns accumulated decayed byte-seconds. */
    public synchronized double byteSeconds() { return byteSeconds; }
}
