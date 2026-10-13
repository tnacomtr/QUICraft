// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure freshness and time-structure rules for updater documents (docs/updater.md 12.5, R6 and M6).
 *
 * <p>Every method takes its clock values as arguments. Nothing here reads the system clock, does
 * I/O, or keeps state. Null arguments raise {@link NullPointerException}.
 */
public final class FreshnessPolicy {

    /** Future tolerance for {@code issued}: {@code issued <= systemNow + 24 h}. */
    public static final Duration FUTURE_TOLERANCE = Duration.ofHours(24);

    /** Clock-behind threshold: {@code systemNow < clockFloor - 24 h} triggers a WARN. */
    public static final Duration CLOCK_WARN = Duration.ofHours(24);

    /** Maximum lifetime of a manifest ({@code expires - issued}). */
    public static final Duration MANIFEST_MAX_LIFETIME = Duration.ofDays(45);

    /** Maximum lifetime of a revocations document ({@code expires - issued}). */
    public static final Duration REVOCATIONS_MAX_LIFETIME = Duration.ofDays(400);

    private FreshnessPolicy() {
    }

    /**
     * Returns the time the policy uses for expiry checks: {@code max(systemNow, clockFloor)}. A
     * clock that is behind cannot make expired documents look fresh, and the floor is never derived
     * from the system clock.
     */
    public static Instant effectiveNow(Instant systemNow, Instant clockFloor) {
        Objects.requireNonNull(systemNow, "systemNow");
        Objects.requireNonNull(clockFloor, "clockFloor");
        return systemNow.isAfter(clockFloor) ? systemNow : clockFloor;
    }

    /**
     * Returns true when {@code issued} is more than 24 h after {@code systemNow}. Exactly 24 h
     * ahead is not future-issued.
     */
    public static boolean futureIssued(Instant issued, Instant systemNow) {
        Objects.requireNonNull(issued, "issued");
        Objects.requireNonNull(systemNow, "systemNow");
        return Duration.between(systemNow, issued).compareTo(FUTURE_TOLERANCE) > 0;
    }

    /**
     * Returns true when the document has not expired: {@code expires > effectiveNow}. There is no
     * grace period, so {@code expires} equal to {@code effectiveNow} is expired.
     */
    public static boolean unexpired(Instant expires, Instant effectiveNow) {
        Objects.requireNonNull(expires, "expires");
        Objects.requireNonNull(effectiveNow, "effectiveNow");
        return expires.isAfter(effectiveNow);
    }

    /**
     * Returns true when {@code systemNow < clockFloor - 24 h}, that is, the system clock is more
     * than 24 h behind the clock floor. The updater logs one WARN per day for this case.
     */
    public static boolean clockBehind(Instant systemNow, Instant clockFloor) {
        Objects.requireNonNull(systemNow, "systemNow");
        Objects.requireNonNull(clockFloor, "clockFloor");
        return Duration.between(systemNow, clockFloor).compareTo(CLOCK_WARN) > 0;
    }

    /**
     * Returns true when {@code issued < expires} and the lifetime {@code expires - issued} is at
     * most {@code maxLifetime} (rules R6 and M6).
     */
    public static boolean structuralTimesValid(Instant issued, Instant expires, Duration maxLifetime) {
        Objects.requireNonNull(issued, "issued");
        Objects.requireNonNull(expires, "expires");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        if (!issued.isBefore(expires)) {
            return false;
        }
        return Duration.between(issued, expires).compareTo(maxLifetime) <= 0;
    }
}
