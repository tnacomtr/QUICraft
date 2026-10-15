// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.Faults;

/**
 * The 30-day expiry of an authenticated pin and the re-bind state that goes with it, with the two
 * stores run together as the connect path runs them (docs/protocol.md 12.15, 12.16): the re-bind
 * state is forgotten first, then the pin is removed, so a process that dies in between leaves an
 * expired pin rather than a re-bind entry nothing would ever remove.
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
    private static final PinKey MC = PinKey.of("mc.example.com", 25565);

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
        Set<PinKey> active = rebind.activeKeys();
        assertTrue(active.isEmpty(), "no attempt is running");
        assertEquals(Collections.singleton(MC), PinExpiry.purge(pins, rebind, active));

        assertEquals(0, pins.size(), "the pin is gone");
        assertEquals(0, rebind.size(), "and so is its re-bind state");
        assertFalse(pins.pin("mc.example.com", 25565, active).isPresent(), "the name is first contact again");
        RebindState.Attempt afterExpiry = rebind.tryBeginAttempt("mc.example.com", 25565, clock.now());
        assertNotNull(afterExpiry, "no re-bind backoff is left");
        assertTrue(rebind.endAttempt(afterExpiry, true));

        assertFalse(rebind.shouldNotify("mc.example.com", 25565, FP_B),
                "the Bloom bits stay: a notice that was shown is never shown twice");
        assertArrayEquals(bitmap, bitmapOf(Files.readAllBytes(rebindFile)), "no Bloom bit is cleared");
        assertTrue(rebind.shouldNotify("mc.example.com", 25565, FP_A), "an unseen tuple is new");

        PinStore reloadedPins = PinStore.load(pinsFile, clock);
        RebindState reloadedRebind = RebindState.load(rebindFile, clock::now);
        assertEquals(0, reloadedPins.size(), "the pin is gone from disk");
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
        RebindState.Attempt attempt = rebind.tryBeginAttempt("mc.example.com", 25565, clock.now());
        assertNotNull(attempt);

        Set<PinKey> active = rebind.activeKeys();
        assertEquals(Collections.singleton(MC), active, "the attempt's server is the active set");
        assertTrue(pins.expiredKeys(active).isEmpty(), "the attempt's pin has not expired under it");
        assertTrue(PinExpiry.purge(pins, rebind, active).isEmpty(), "so there is nothing to expire");
        assertTrue(pins.pin("mc.example.com", 25565, active).isPresent());
        assertEquals(1, pins.size());
        assertEquals(1, rebind.size(), "and the attempt's re-bind state stays");
        assertTrue(rebind.endAttempt(attempt, false), "its token still matches");
    }

    @Test
    void anAttemptOlderThanTheStalenessRuleStillHoldsItsPinAndState(@TempDir Path dir) throws IOException {
        Path pinsFile = dir.resolve("quicraft").resolve("pins-v2.json");
        Path rebindFile = dir.resolve("quicraft").resolve("rebind-v1.bin");
        TestClock clock = new TestClock(BASE);
        PinStore pins = PinStore.load(pinsFile, clock);
        RebindState rebind = RebindState.load(rebindFile, clock::now);
        assertEquals(PinStore.RecordResult.RECORDED, pins.recordPin("mc.example.com", 25565, FP_A));
        clock.advance(PinStore.PIN_TTL_MILLIS + 1);
        RebindState.Attempt attempt = rebind.tryBeginAttempt("mc.example.com", 25565, clock.now());
        assertNotNull(attempt);
        clock.advance(RebindState.STALE_ATTEMPT_MILLIS);

        Set<PinKey> active = rebind.activeKeys();
        assertEquals(Collections.singleton(MC), active,
                "an attempt nobody ended is still the attempt for its server, stale or not");
        assertTrue(pins.expiredKeys(active).isEmpty(), "so its pin is not expired under it");
        assertTrue(PinExpiry.purge(pins, rebind, active).isEmpty());
        assertEquals(0, rebind.forgetExpiredPins(active),
                "and its re-bind state is not forgotten even when a caller asks for it by name");
        assertEquals(1, rebind.size());
        assertTrue(pins.pin("mc.example.com", 25565, active).isPresent());
        assertTrue(rebind.endAttempt(attempt, false), "its token still matches");
    }

    @Test
    void aCrashBetweenTheForgetAndThePurgeLeavesTheKeyForTheNextCycle(@TempDir Path dir) throws IOException {
        Path pinsFile = dir.resolve("quicraft").resolve("pins-v2.json");
        Path rebindFile = dir.resolve("quicraft").resolve("rebind-v1.bin");
        TestClock clock = new TestClock(BASE);
        PinStore pins = PinStore.load(pinsFile, clock);
        RebindState rebind = RebindState.load(rebindFile, clock::now);
        assertEquals(PinStore.RecordResult.RECORDED, pins.recordPin("mc.example.com", 25565, FP_A));
        RebindState.Attempt failed = rebind.tryBeginAttempt("mc.example.com", 25565, clock.now());
        assertNotNull(failed);
        assertTrue(rebind.endAttempt(failed, false));
        clock.advance(PinStore.PIN_TTL_MILLIS + 1);

        Set<PinKey> active = rebind.activeKeys();
        Set<PinKey> expired = pins.expiredKeys(active);
        assertEquals(Collections.singleton(MC), expired);
        assertEquals(1, rebind.forgetExpiredPins(expired), "the re-bind state is forgotten first");
        // As if the process died here: the pin file has not been written yet.
        assertTrue(new String(Files.readAllBytes(pinsFile), StandardCharsets.UTF_8).contains("mc.example.com"),
                "the pin is still on disk, which is what the next cycle needs");

        assertEquals(Collections.singleton(MC), PinExpiry.purge(pins, rebind, active),
                "the next cycle finds the key again and finishes the expiry");
        assertEquals(0, pins.size());
        assertFalse(new String(Files.readAllBytes(pinsFile), StandardCharsets.UTF_8).contains("mc.example.com"));
        assertEquals(0, rebind.size());
        assertTrue(PinExpiry.purge(pins, rebind, active).isEmpty(), "and then there is nothing left to do");
    }

    @Test
    void aFailedRebindWriteLeavesThePinFileUntouched(@TempDir Path dir) throws IOException {
        Path pinsFile = dir.resolve("quicraft").resolve("pins-v2.json");
        Path rebindFile = dir.resolve("quicraft").resolve("rebind-v1.bin");
        TestClock clock = new TestClock(BASE);
        PinStore pins = PinStore.load(pinsFile, clock);
        RebindState rebind = RebindState.load(rebindFile, clock::now);
        assertEquals(PinStore.RecordResult.RECORDED, pins.recordPin("mc.example.com", 25565, FP_A));
        assertTrue(rebind.shouldNotify("mc.example.com", 25565, FP_B));
        byte[] pinBytes = Files.readAllBytes(pinsFile);
        clock.advance(PinStore.PIN_TTL_MILLIS + 1);

        Faults.set(StateFile.FAULT_FORCE);
        try {
            assertTrue(PinExpiry.purge(pins, rebind, Collections.<PinKey>emptySet()).isEmpty(),
                    "nothing is removed while the re-bind entries are not durably gone");
        } finally {
            Faults.set();
        }
        assertFalse(rebind.isUsable(), "the failed write fails closed");
        assertArrayEquals(pinBytes, Files.readAllBytes(pinsFile), "the pin file is untouched");
        assertEquals(1, pins.size(), "so the pin is still there for a later cycle");
        assertTrue(pins.isUsable(), "and the pin store itself was never written to, so it is still usable");
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
