// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/** docs/protocol.md 12.13 and 13.12: profile ranges, cross-field rules and the maxOpen invariant. */
class ServerProfileTest {
    private static final long MIB256 = 256L << 20;

    static ServerProfile valid() {
        return new ServerProfile(50_000, 6, MIB256, 0.10, 4096, 8L << 20, 8, 4, 2);
    }

    @Test
    void theDefaultProfileValidatesAndItsPreLoginCapIsTheMeasuredArithmetic() {
        ServerProfile profile = valid();
        profile.validate();
        // 10% of 256 MiB over one 24582-byte staged share at N=6.
        assertEquals((long) (MIB256 * 0.10) / (2L * 8192 + 6 + 4096 + 4096), profile.preLoginConnectionCap());
    }

    @Test
    void maxOpenMustCoverTheLoggedInTargetPlusThePreLoginCap() {
        ServerProfile profile = valid();
        profile.requireOpen(65_536);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> profile.requireOpen(50_000));
        assertEquals(true, refused.getMessage().contains("raise maxOpen"));
    }

    @Test
    void rangesAndCrossFieldRulesAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(0, 6, MIB256, 0.1, 1, 1, 1, 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 0, MIB256, 0.1, 1, 1, 1, 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 7, MIB256, 0.1, 1, 1, 1, 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 6, 0, 0.1, 1, 1, 1, 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 6, MIB256, 0.0, 1, 1, 1, 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 6, MIB256, 1.0, 1, 1, 1, 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 6, MIB256, Double.NaN, 1, 1, 1, 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 6, 1000, 0.1, 1, 1, 1, 1, 1).validate(),
                "a pool too small for one staged share");
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 6, MIB256, 0.1, 0, 1, 1, 1, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new ServerProfile(1, 6, MIB256, 0.1, 1, 1, 0, 1, 1).validate());
    }

    @Test
    void theListenerRefusesAMaxOpenThatCannotCoverTheProfileAtBuildTime() {
        ServerProfile profile = valid();
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TransportConfig.builder().maxOpen(50_000).serverProfile(profile).build());
        assertEquals(true, refused.getMessage().contains("raise maxOpen"));
        assertEquals(65_536, TransportConfig.builder().maxOpen(65_536).serverProfile(profile).build().maxOpen());
    }
}
