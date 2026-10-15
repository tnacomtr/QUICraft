// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The 30-day expiry of an authenticated pin and the re-bind state that goes with it, with the two
 * stores run together as the connect path runs them (docs/protocol.md 12.15, 12.16). They live in
 * two files, so the pin's own atomic write comes first and the re-bind state's second.
 */
class PinExpiryTest {
    /** 2023-11-14T22:13:20Z. */
    private static final long BASE = 1_700_000_000_000L;
    /** The re-bind state file's header before the JSON: magic (0-3), format version (4-7), length (8-11). */
    private static final int HEADER = 12;
    /** The SHA-256 after the bitmap. */
    private static final int DIGEST = 32;
    private static final String FP_A = fingerprint('a');
    private static final String FP_B = fingerprint('b');

    @Test
    void anExpiredPinTakesItsRebindStateWithIt(@TempDir Path dir) throws IOException {
        Path pinsFile = dir.resolve("quicraft").resolve("pins-v2.json");
        Path rebindFile = dir.resolve("quicraft").resolve("rebind-v1.bin");
        TestClock clock = new TestClock(BASE);
        PinStore pins = PinStore.load(pinsFile, clock);
        RebindState rebind = RebindState.load(rebindFile, clock::now);

        assertEquals(PinStore.RecordResult.RECORDED, pins.recordPin("mc.example.com", 25565, FP_A));
        RebindState.Attempt failed = rebind.tryBeginAttempt("mc.example.com", 25565, clock.now());
        assertNotNull(failed);
        assertTrue(rebind.endAttempt(failed, false), "the changed pin's re-bind failed once");
        assertTrue(rebind.shouldNotify("mc.example.com", 25565, FP_B), "and the change was noticed once");
        byte[] bitmap = bitmapOf(Files.readAllBytes(rebindFile));

        clock.advance(PinStore.PIN_TTL_MILLIS + 1);
        Set<PinKey> expired = pins.purgeExpired(Collections.<PinKey>emptySet());
        assertEquals(Collections.singleton(PinKey.of("mc.example.com", 25565)), expired);
        assertEquals(1, rebind.forgetExpiredPins(expired), "its backoff and its notice go with the pin");

        assertFalse(pins.pin("mc.example.com", 25565).isPresent(), "the name is first contact again");
        assertEquals(0, rebind.size());
        assertNotNull(rebind.tryBeginAttempt("mc.example.com", 25565, clock.now()),
                "no re-bind backoff is left");
        assertFalse(rebind.shouldNotify("mc.example.com", 25565, FP_B),
                "the Bloom bits stay: a notice that was shown is never shown twice");
        assertArrayEquals(bitmap, bitmapOf(Files.readAllBytes(rebindFile)), "no Bloom bit is cleared");
        assertTrue(rebind.shouldNotify("mc.example.com", 25565, FP_A), "an unseen tuple is new");

        PinStore reloadedPins = PinStore.load(pinsFile, clock);
        RebindState reloadedRebind = RebindState.load(rebindFile, clock::now);
        assertEquals(0, reloadedPins.size(), "the pin is gone from disk");
        assertFalse(reloadedPins.pin("mc.example.com", 25565).isPresent());
        assertFalse(reloadedRebind.shouldNotify("mc.example.com", 25565, FP_B),
                "and the bitmap survived the restart");
    }

    @Test
    void aRunningAttemptsPinAndStateSurviveExpiry(@TempDir Path dir) throws IOException {
        Path pinsFile = dir.resolve("quicraft").resolve("pins-v2.json");
        Path rebindFile = dir.resolve("quicraft").resolve("rebind-v1.bin");
        TestClock clock = new TestClock(BASE);
        PinStore pins = PinStore.load(pinsFile, clock);
        RebindState rebind = RebindState.load(rebindFile, clock::now);
        assertEquals(PinStore.RecordResult.RECORDED, pins.recordPin("mc.example.com", 25565, FP_A));
        clock.advance(PinStore.PIN_TTL_MILLIS + 1);
        Set<PinKey> active = Collections.singleton(PinKey.of("mc.example.com", 25565));
        RebindState.Attempt attempt = rebind.tryBeginAttempt("mc.example.com", 25565, clock.now());
        assertNotNull(attempt);

        assertTrue(pins.purgeExpired(active).isEmpty(), "the attempt's pin is not expired under it");
        assertTrue(pins.pin("mc.example.com", 25565, active).isPresent());
        assertEquals(1, pins.size());
        assertEquals(0, rebind.forgetExpiredPins(active),
                "an attempt that is running keeps its re-bind state");
        assertEquals(1, rebind.size());
        assertTrue(rebind.endAttempt(attempt, false), "the attempt's token still matches");
    }

    private static byte[] bitmapOf(byte[] content) {
        return Arrays.copyOfRange(content, HEADER + ByteBuffer.wrap(content).getInt(HEADER - 4),
                content.length - DIGEST);
    }

    private static String fingerprint(char c) {
        char[] hex = new char[64];
        Arrays.fill(hex, c);
        return "sha256:" + new String(hex);
    }
}
