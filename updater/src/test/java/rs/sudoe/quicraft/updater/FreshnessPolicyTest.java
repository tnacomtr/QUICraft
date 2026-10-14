// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Boundary tests for the freshness policy. All clock values are fixed Instants. */
class FreshnessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @Test
    void futureToleranceAcceptsExactlyTwentyFourHoursAhead() {
        assertFalse(FreshnessPolicy.futureIssued(NOW.plus(Duration.ofHours(24)), NOW));
    }

    @Test
    void futureToleranceRejectsOneSecondBeyondTwentyFourHours() {
        assertTrue(FreshnessPolicy.futureIssued(NOW.plus(Duration.ofHours(24)).plus(ONE_SECOND), NOW));
    }

    @Test
    void issuedInThePastOrNowIsNotFuture() {
        assertFalse(FreshnessPolicy.futureIssued(NOW, NOW));
        assertFalse(FreshnessPolicy.futureIssued(NOW.minus(Duration.ofDays(3)), NOW));
    }

    @Test
    void unexpiredIsStrictWithNoGrace() {
        assertTrue(FreshnessPolicy.unexpired(NOW.plus(ONE_SECOND), NOW));
        assertFalse(FreshnessPolicy.unexpired(NOW, NOW));
        assertFalse(FreshnessPolicy.unexpired(NOW.minus(ONE_SECOND), NOW));
    }

    @Test
    void clockBehindWarnsOnlyBeyondTwentyFourHours() {
        Instant floor = NOW.plus(Duration.ofHours(24));
        // exactly 24 h behind the floor: not behind
        assertFalse(FreshnessPolicy.clockBehind(NOW, floor));
        // one second more than 24 h behind: behind
        assertTrue(FreshnessPolicy.clockBehind(NOW.minus(ONE_SECOND), floor));
        assertFalse(FreshnessPolicy.clockBehind(floor, NOW));
    }

    @Test
    void effectiveNowIsTheMaximumOfClockAndFloor() {
        Instant floor = NOW.plus(Duration.ofHours(2));
        assertEquals(floor, FreshnessPolicy.effectiveNow(NOW, floor));
        assertEquals(NOW, FreshnessPolicy.effectiveNow(NOW, NOW.minus(Duration.ofHours(2))));
        assertEquals(NOW, FreshnessPolicy.effectiveNow(NOW, NOW));
    }

    @Test
    void clockBehindFloorMakesDocumentStillUnexpiredViaFloor() {
        Instant floor = NOW.plus(Duration.ofDays(10));
        Instant effective = FreshnessPolicy.effectiveNow(NOW.minus(Duration.ofDays(5)), floor);
        // system clock is 5 days behind, but the floor keeps expiry decisions at the floor time
        assertEquals(floor, effective);
        assertFalse(FreshnessPolicy.unexpired(NOW.plus(Duration.ofDays(9)), effective));
    }

    @Test
    void clockAheadMakesDocumentsLookExpired() {
        Instant expires = NOW.plus(Duration.ofDays(30));
        Instant clockAhead = NOW.plus(Duration.ofDays(31));
        assertTrue(FreshnessPolicy.unexpired(expires, NOW));
        assertFalse(FreshnessPolicy.unexpired(expires, FreshnessPolicy.effectiveNow(clockAhead, NOW)));
    }

    @Test
    void manifestLifetimeAcceptsExactlyFortyFiveDays() {
        Instant issued = NOW;
        assertTrue(FreshnessPolicy.structuralTimesValid(
                issued, issued.plus(Duration.ofDays(45)), FreshnessPolicy.MANIFEST_MAX_LIFETIME));
    }

    @Test
    void manifestLifetimeRejectsOneSecondOverFortyFiveDays() {
        Instant issued = NOW;
        assertFalse(FreshnessPolicy.structuralTimesValid(
                issued, issued.plus(Duration.ofDays(45)).plus(ONE_SECOND),
                FreshnessPolicy.MANIFEST_MAX_LIFETIME));
    }

    @Test
    void revocationsLifetimeAcceptsExactlyFourHundredDays() {
        Instant issued = NOW;
        assertTrue(FreshnessPolicy.structuralTimesValid(
                issued, issued.plus(Duration.ofDays(400)), FreshnessPolicy.REVOCATIONS_MAX_LIFETIME));
    }

    @Test
    void revocationsLifetimeRejectsOneSecondOverFourHundredDays() {
        Instant issued = NOW;
        assertFalse(FreshnessPolicy.structuralTimesValid(
                issued, issued.plus(Duration.ofDays(400)).plus(ONE_SECOND),
                FreshnessPolicy.REVOCATIONS_MAX_LIFETIME));
    }

    @Test
    void issuedEqualToExpiresIsInvalid() {
        assertFalse(FreshnessPolicy.structuralTimesValid(NOW, NOW, FreshnessPolicy.MANIFEST_MAX_LIFETIME));
    }

    @Test
    void expiresBeforeIssuedIsInvalid() {
        assertFalse(FreshnessPolicy.structuralTimesValid(
                NOW, NOW.minus(ONE_SECOND), FreshnessPolicy.MANIFEST_MAX_LIFETIME));
    }

    @Test
    void shortValidLifetimeIsAccepted() {
        assertTrue(FreshnessPolicy.structuralTimesValid(
                NOW, NOW.plus(ONE_SECOND), FreshnessPolicy.MANIFEST_MAX_LIFETIME));
    }

    @Test
    void constantsMatchTheSpecification() {
        assertEquals(Duration.ofHours(24), FreshnessPolicy.FUTURE_TOLERANCE);
        assertEquals(Duration.ofHours(24), FreshnessPolicy.CLOCK_WARN);
        assertEquals(Duration.ofDays(45), FreshnessPolicy.MANIFEST_MAX_LIFETIME);
        assertEquals(Duration.ofDays(400), FreshnessPolicy.REVOCATIONS_MAX_LIFETIME);
    }

    @Test
    void nullArgumentsRaiseNullPointerException() {
        assertThrows(NullPointerException.class, () -> FreshnessPolicy.effectiveNow(null, NOW));
        assertThrows(NullPointerException.class, () -> FreshnessPolicy.effectiveNow(NOW, null));
        assertThrows(NullPointerException.class, () -> FreshnessPolicy.futureIssued(null, NOW));
        assertThrows(NullPointerException.class, () -> FreshnessPolicy.futureIssued(NOW, null));
        assertThrows(NullPointerException.class, () -> FreshnessPolicy.unexpired(null, NOW));
        assertThrows(NullPointerException.class, () -> FreshnessPolicy.unexpired(NOW, null));
        assertThrows(NullPointerException.class, () -> FreshnessPolicy.clockBehind(null, NOW));
        assertThrows(NullPointerException.class, () -> FreshnessPolicy.clockBehind(NOW, null));
        assertThrows(NullPointerException.class,
                () -> FreshnessPolicy.structuralTimesValid(null, NOW, Duration.ofDays(1)));
        assertThrows(NullPointerException.class,
                () -> FreshnessPolicy.structuralTimesValid(NOW, null, Duration.ofDays(1)));
        assertThrows(NullPointerException.class,
                () -> FreshnessPolicy.structuralTimesValid(NOW, NOW.plus(ONE_SECOND), null));
    }
}
