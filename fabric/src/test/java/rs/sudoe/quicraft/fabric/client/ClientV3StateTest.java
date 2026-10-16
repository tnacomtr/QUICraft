// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.pin.PinStore;

/**
 * The multi-stream state files and their wiring (docs/protocol.md 12.1, 12.15): the file names, the
 * 30 day expiry at startup, and the rule that a player who never turned multistream on never has any
 * of it touched. Nothing here starts a game.
 */
class ClientV3StateTest {
    private static final String FINGERPRINT = "sha256:" + "ab".repeat(32);

    @Test
    void theJoinTakesTheMultistreamPathOnlyWithTheSettingOnAndALogicalName() {
        assertTrue(ClientV3State.useMultistream(true, true, "mc.example.com", 25565));
        assertFalse(ClientV3State.useMultistream(false, true, "mc.example.com", 25565),
                "12.1: with the setting off the join is exactly what it was before multi-stream");
        assertFalse(ClientV3State.useMultistream(true, false, "mc.example.com", 25565),
                "state files that could not be opened: the ordinary path");
        assertFalse(ClientV3State.useMultistream(true, true, null, 25565), "no name to key a pin by");
        assertFalse(ClientV3State.useMultistream(true, true, "", 25565));
        assertFalse(ClientV3State.useMultistream(true, true, "mc.example.com", 0));
        assertFalse(ClientV3State.useMultistream(true, true, "mc.example.com", 65536));
    }

    @Test
    void thePinFileIsPinsV2InTheConfigDirectory(@TempDir Path dir) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);
        ClientV3State state = ClientV3State.open(dir, clock);
        try {
            state.pins().recordPin("mc.example.com", 25565, FINGERPRINT, Collections.emptySet());
        } finally {
            state.close();
        }

        assertTrue(Files.exists(dir.resolve("pins-v2.json")), "12.15: quicraft/pins-v2.json");
        assertEquals(FINGERPRINT, PinStore.load(dir.resolve("pins-v2.json"), clock)
                .pin("mc.example.com", 25565, Collections.emptySet()).get().fingerprint());
    }

    @Test
    void aPinPastItsThirtyDaysIsGoneAfterStartup(@TempDir Path dir) throws Exception {
        Instant then = Instant.parse("2026-09-01T00:00:00Z");
        PinStore old = PinStore.load(dir.resolve("pins-v2.json"), Clock.fixed(then, ZoneOffset.UTC));
        old.recordPin("old.example.com", 25565, FINGERPRINT, Collections.emptySet());
        Clock later = Clock.fixed(then.plus(Duration.ofDays(31)), ZoneOffset.UTC);
        PinStore.load(dir.resolve("pins-v2.json"), later); // loads without error
        old.recordPin("fresh.example.com", 25565, FINGERPRINT, Collections.emptySet());

        // Startup: the purge is queued by open() and has run once close() returns.
        ClientV3State state = ClientV3State.open(dir, later);
        state.close();

        PinStore after = PinStore.load(dir.resolve("pins-v2.json"), later);
        assertTrue(after.isUsable());
        assertFalse(after.pin("old.example.com", 25565, Collections.emptySet()).isPresent(),
                "12.15: an authenticated pin last bound more than 30 days ago expires");
        assertFalse(after.pin("fresh.example.com", 25565, Collections.emptySet()).isPresent(),
                "recorded at the same old instant, so it expired too");
    }

    @Test
    void aRecentPinSurvivesTheStartupPurge(@TempDir Path dir) throws Exception {
        Instant now = Instant.parse("2026-10-16T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        PinStore.load(dir.resolve("pins-v2.json"), clock)
                .recordPin("mc.example.com", 25565, FINGERPRINT, Collections.emptySet());

        ClientV3State state = ClientV3State.open(dir, clock);
        state.close();

        assertTrue(PinStore.load(dir.resolve("pins-v2.json"), clock)
                .pin("mc.example.com", 25565, Collections.emptySet()).isPresent());
    }

    @Test
    void theRebindStateLivesBesideThePins(@TempDir Path dir) throws Exception {
        ClientV3State state = ClientV3State.open(dir, Clock.systemUTC());
        try {
            long now = System.currentTimeMillis();
            state.rebinds().tryBeginAttempt("mc.example.com", 25565, now);
        } finally {
            state.close();
        }

        assertTrue(Files.exists(dir.resolve("rebind-v1.json")), "12.16: persisted across restarts");
    }
}
