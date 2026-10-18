// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.time;

/**
 * Cumulative stall accounting source for one actual executor incarnation (docs/protocol.md 13.10).
 *
 * <p>A consumer takes one {@link #sample()} per operation and derives everything from that single
 * atomic reading: the transport time it may count ({@link Sample#nowNanos()}) and the cumulative
 * stall time it must exclude ({@link Sample#excludedNanos()}). Consumers never keep a second
 * reading and never invent a boundary timestamp from a total, so an observation taken late yields
 * exactly what a timely one would have seen.
 *
 * <p>Readings are compared by subtraction and are expected to be less than half a long range
 * apart, so a negative clock origin (for example {@link System#nanoTime()}) and a signed wrap of
 * the clock or of the cumulative counter are fine. Cumulative totals may wrap modulo long;
 * only bounded differences are meaningful, and a negative sentinel value is never used.
 *
 * <p>{@link Sample#valid()} is false once the source's incarnation is gone, for example when the
 * game executor was replaced and its accounting can no longer describe the new one. Consumers must
 * fail safe on an invalid reading: continue only for release of already-mutated state, never to
 * score, select or extend a deadline. One source object is one incarnation and is never replaced
 * under a consumer.
 *
 * <p>This package depends on nothing outside the JDK: no multistream or resource types.
 */
public interface StallClock {
    /** Takes one reading of the cumulative accounting. */
    Sample sample();

    /** One atomic cumulative reading: every component describes the same instant. */
    interface Sample {
        /** Monotonic transport clock reading in nanoseconds; signed differences are elapsed time. */
        long nowNanos();

        /** Cumulative stall time excluded through {@link #nowNanos()}, modulo long. */
        long excludedNanos();

        /** False once this source's incarnation is gone; never valid again. */
        boolean valid();
    }

    /**
     * Thrown when an accounting consumer cannot trust its source: the reading is invalid, or its
     * differences are malformed (time went backwards, the cumulative counter went backwards, or the
     * excluded delta is larger than the elapsed delta). A consumer must not mutate accounting when
     * this is thrown.
     */
    final class UnavailableException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** @param message what made the reading unusable */
        public UnavailableException(String message) {
            super(message);
        }
    }
}
