// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link ClockFloor}. Only fixed Instants are used; no clock is read. */
class ClockFloorTest {

    private static final Instant BUILT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REV_ISSUED = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant KID_A_ISSUED = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant KID_B_ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final String SHA = "a".repeat(64);
    private static final String KID_A = "0123456789abcdef";
    private static final String KID_B = "fedcba9876543210";

    private static StateRecords.CurrentRevocations seedRevocations() {
        return new StateRecords.CurrentRevocations(0, "", null, null, 0);
    }

    private static StateRecords.CurrentRevocations acceptedRevocations(Instant issued) {
        return new StateRecords.CurrentRevocations(
                3, SHA, issued, issued.plus(Duration.ofDays(365)), 0);
    }

    private static StateRecords.KidSeq kidSeq(long seq, Instant issued) {
        return new StateRecords.KidSeq(seq, SHA, issued);
    }

    @Test
    void returnsBuiltWhenNothingElseIsPresent() {
        assertEquals(BUILT, ClockFloor.of(BUILT, seedRevocations(), Map.of(), Set.of()));
    }

    @Test
    void seedRevocationsWithNullIssuedDoesNotContribute() {
        Instant floor = ClockFloor.of(BUILT, seedRevocations(), Map.of(KID_A, kidSeq(1, KID_A_ISSUED)),
                Set.of());
        assertEquals(KID_A_ISSUED, floor);
    }

    @Test
    void acceptedRevocationsIssuedRaisesFloor() {
        assertEquals(REV_ISSUED, ClockFloor.of(BUILT, acceptedRevocations(REV_ISSUED), Map.of(), Set.of()));
    }

    @Test
    void floorIsMaximumOfAllContributors() {
        Instant floor = ClockFloor.of(
                BUILT,
                acceptedRevocations(REV_ISSUED),
                Map.of(KID_A, kidSeq(4, KID_A_ISSUED)),
                Set.of());
        assertEquals(KID_A_ISSUED, floor);
    }

    @Test
    void revokedKidContributesNothingEvenWhenItsIssuedIsTheMaximum() {
        Instant floor = ClockFloor.of(
                BUILT,
                acceptedRevocations(REV_ISSUED),
                Map.of(KID_A, kidSeq(4, KID_A_ISSUED), KID_B, kidSeq(9, KID_B_ISSUED)),
                Set.of(KID_B));
        assertEquals(KID_A_ISSUED, floor);
    }

    @Test
    void revokedKidWithOnlyLargestIssuedFallsBackToOtherContributors() {
        Instant floor = ClockFloor.of(
                BUILT,
                seedRevocations(),
                Map.of(KID_B, kidSeq(9, KID_B_ISSUED)),
                Set.of(KID_B));
        assertEquals(BUILT, floor);
    }

    @Test
    void nonRevokedKidAtExactlyBuiltDoesNotLowerFloor() {
        Instant floor = ClockFloor.of(BUILT, seedRevocations(), Map.of(KID_A, kidSeq(1, BUILT)), Set.of());
        assertEquals(BUILT, floor);
    }

    @Test
    void nullArgumentsRaiseNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> ClockFloor.of(null, seedRevocations(), Map.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> ClockFloor.of(BUILT, null, Map.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> ClockFloor.of(BUILT, seedRevocations(), null, Set.of()));
        assertThrows(NullPointerException.class,
                () -> ClockFloor.of(BUILT, seedRevocations(), Map.of(), null));
    }
}
