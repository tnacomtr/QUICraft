// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.Faults;

/** The persisted authenticated pin store (docs/protocol.md 12.15). */
class PinStoreTest {
    /** 2023-11-14T22:13:20Z, so the timestamps written are easy to read. */
    private static final long BASE = 1_700_000_000_000L;
    /** 2023-11-14T22:13:20Z, the bound time of a pin that is inside its TTL at {@link #BASE}. */
    private static final String FRESH_BOUND = "2023-11-14T22:13:20Z";
    /** 2000-01-01T00:00:00Z, the bound time of a pin that expired long before {@link #BASE}. */
    private static final String EXPIRED_BOUND = "2000-01-01T00:00:00Z";
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final String FP_A = fingerprint('a');
    private static final String FP_B = fingerprint('b');

    @Test
    void missingFileIsAnEmptyUsableStore(@TempDir Path dir) {
        Path file = dir.resolve("quicraft").resolve("pins-v2.json");
        PinStore store = PinStore.load(file, new TestClock(BASE));
        assertTrue(store.isUsable());
        assertEquals(0, store.size());
        assertFalse(store.pin("mc.example.com", 25565).isPresent());
        assertFalse(Files.exists(file), "loading writes nothing");
    }

    @Test
    void recordsAndReloadsPins(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("quicraft").resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("MC.Example.COM.", 25565, FP_A));
        now.advance(60_000);
        assertEquals(PinStore.RecordResult.UPDATED, store.recordPin("mc.example.com", 25565, FP_B));
        assertEquals(1, store.size(), "one name, two spellings, one pin");
        PinStore.Pin live = store.pin("mc.example.com", 25565).get();
        assertEquals("mc.example.com", live.host());
        assertEquals(25565, live.port());
        assertEquals(FP_B, live.fingerprint());
        assertEquals(Instant.ofEpochMilli(BASE), live.firstBound(), "first-bound survives a re-bind");
        assertEquals(Instant.ofEpochMilli(BASE + 60_000), live.lastBound());

        PinStore reloaded = PinStore.load(file, now);
        assertTrue(reloaded.isUsable());
        assertEquals(1, reloaded.size());
        assertEquals(PinStore.RecordResult.UPDATED, reloaded.recordPin("MC.EXAMPLE.COM", 25565, FP_A));
        PinStore.Pin reloadedPin = reloaded.pin("MC.Example.COM.", 25565).get();
        assertEquals(FP_A, reloadedPin.fingerprint(), "lookup normalizes the same way the store does");
        assertEquals(Instant.ofEpochMilli(BASE), reloadedPin.firstBound());
        assertEquals(Instant.ofEpochMilli(BASE + 60_000), reloadedPin.lastBound(),
                "a re-bind after the reload must not have moved the clock");

        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertEquals("{\"version\":1,\"pins\":[{\"host\":\"mc.example.com\",\"port\":25565"
                + ",\"fingerprint\":\"" + FP_A + "\""
                + ",\"firstBound\":\"2023-11-14T22:13:20Z\""
                + ",\"lastBound\":\"2023-11-14T22:14:20Z\"}]}", json);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(file.getParent())) {
            for (Path leftover : stream) {
                assertEquals("pins-v2.json", leftover.getFileName().toString(), "no temporary file is left behind");
            }
        }
    }

    @Test
    void corruptionDisablesTheStoreInsteadOfReadingItAsEmpty(@TempDir Path dir) throws IOException {
        Map<String, String> corrupt = new LinkedHashMap<>();
        corrupt.put("truncated", "{");
        corrupt.put("not-json", "pins");
        corrupt.put("wrong-version", "{\"version\":2,\"pins\":[]}");
        corrupt.put("missing-version", "{\"pins\":[]}");
        corrupt.put("duplicate-member", "{\"version\":1,\"version\":1,\"pins\":[]}");
        corrupt.put("trailing-data", "{\"version\":1,\"pins\":[]}x");
        corrupt.put("duplicate-pin", "{\"version\":1,\"pins\":["
                + entry("a.example.com", 25565, FP_A) + "," + entry("A.Example.com", 25565, FP_A) + "]}");
        corrupt.put("bad-fingerprint", "{\"version\":1,\"pins\":["
                + "{\"host\":\"a.example.com\",\"port\":25565,\"fingerprint\":\"sha256:ABCD\""
                + ",\"firstBound\":\"2000-01-01T00:00:00Z\",\"lastBound\":\"2000-01-01T00:00:00Z\"}]}");
        corrupt.put("offset-timestamp", "{\"version\":1,\"pins\":["
                + "{\"host\":\"a.example.com\",\"port\":25565,\"fingerprint\":\"" + FP_A + "\""
                + ",\"firstBound\":\"2000-01-01T00:00:00+02:00\",\"lastBound\":\"2000-01-01T00:00:00Z\"}]}");
        corrupt.put("port-as-decimal", "{\"version\":1,\"pins\":["
                + "{\"host\":\"a.example.com\",\"port\":25565.0,\"fingerprint\":\"" + FP_A + "\""
                + ",\"firstBound\":\"2000-01-01T00:00:00Z\",\"lastBound\":\"2000-01-01T00:00:00Z\"}]}");
        corrupt.put("bad-host", "{\"version\":1,\"pins\":[" + entry("fe80::1%eth0", 25565, FP_A) + "]}");
        corrupt.put("over-capacity", pins(4097));

        TestClock now = new TestClock(BASE);
        for (Map.Entry<String, String> caseFile : corrupt.entrySet()) {
            Path file = dir.resolve(caseFile.getKey() + ".json");
            byte[] content = caseFile.getValue().getBytes(StandardCharsets.UTF_8);
            Files.write(file, content);
            PinStore store = PinStore.load(file, now);
            assertFalse(store.isUsable(), caseFile.getKey());
            assertEquals(PinStore.RecordResult.DISABLED, store.recordPin("new.example.com", 25565, FP_A),
                    caseFile.getKey());
            assertFalse(store.deletePin("a.example.com", 25565), caseFile.getKey());
            assertFalse(store.pin("a.example.com", 25565).isPresent(), caseFile.getKey());
            assertArrayEquals(content, Files.readAllBytes(file), caseFile.getKey() + " was left untouched");
        }
    }

    @Test
    void failedWriteDisablesTheStore(@TempDir Path dir) throws IOException {
        Path notADirectory = dir.resolve("blocked");
        Files.write(notADirectory, new byte[0]);
        TestClock now = new TestClock(BASE);
        Path file = notADirectory.resolve("pins-v2.json");
        PinStore store = PinStore.load(file, now);
        assertTrue(store.isUsable(), "the file is missing, not corrupt");
        assertEquals(PinStore.RecordResult.DISABLED, store.recordPin("mc.example.com", 25565, FP_A));
        assertFalse(store.isUsable());
        assertEquals(PinStore.RecordResult.DISABLED, store.recordPin("other.example.com", 25565, FP_B),
                "a disabled store keeps refusing every write");
        assertFalse(store.deletePin("mc.example.com", 25565));
        assertFalse(store.pin("mc.example.com", 25565).isPresent(), "nothing was recorded");
    }

    @Test
    void readOnlyDirectoryDisablesTheStore(@TempDir Path dir) throws IOException {
        Path quicraft = Files.createDirectories(dir.resolve("quicraft"));
        Path file = quicraft.resolve("pins-v2.json");
        Assumptions.assumeTrue(quicraft.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(quicraft, PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(quicraft), "the process ignores the directory mode (root)");
        PinStore store = PinStore.load(file, new TestClock(BASE));
        assertTrue(store.isUsable());
        assertEquals(PinStore.RecordResult.DISABLED, store.recordPin("mc.example.com", 25565, FP_A));
        assertFalse(store.isUsable());
        assertFalse(Files.exists(file), "the failed write left nothing behind");
    }

    @Test
    void fullStoreWithNoExpiredPinRefusesNewNames(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        Files.write(file, pins(PinStore.MAX_PINS).getBytes(StandardCharsets.UTF_8));
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertTrue(store.isUsable());
        assertEquals(PinStore.MAX_PINS, store.size());
        assertEquals(PinStore.RecordResult.FULL, store.recordPin("new.example.com", 25565, FP_A),
                "every pin is inside its TTL, so nothing may be evicted");
        assertEquals(PinStore.MAX_PINS, store.size());
        assertEquals(PinStore.RecordResult.UPDATED, store.recordPin("host0.example.com", 25565, FP_B),
                "a name the store knows is always updatable");
        assertEquals(PinStore.MAX_PINS, store.size());

        PinStore reloaded = PinStore.load(file, now);
        assertEquals(PinStore.MAX_PINS, reloaded.size(), "nothing was evicted");
        assertTrue(reloaded.pin("host4095.example.com", 25565).isPresent());
        assertFalse(reloaded.pin("new.example.com", 25565).isPresent());
        assertEquals(FP_B, reloaded.pin("host0.example.com", 25565).get().fingerprint());
    }

    @Test
    void deletePinRemovesOnlyThatServer(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("quicraft").resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("one.example.com", 25565, FP_A));
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("one.example.com", 25566, FP_A));
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("two.example.com", 25565, FP_A));
        assertTrue(store.deletePin("ONE.Example.com.", 25565));
        assertEquals(2, store.size());
        assertFalse(store.deletePin("one.example.com", 25565), "already gone");
        PinStore reloaded = PinStore.load(file, now);
        assertFalse(reloaded.pin("one.example.com", 25565).isPresent());
        assertTrue(reloaded.pin("one.example.com", 25566).isPresent());
        assertTrue(reloaded.pin("two.example.com", 25565).isPresent());
    }

    @Test
    void pinsAreOwnerOnlyWherePosixIsSupported(@TempDir Path dir) throws IOException {
        Path quicraft = dir.resolve("quicraft");
        Path file = quicraft.resolve("pins-v2.json");
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        PinStore store = PinStore.load(file, new TestClock(BASE));
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(quicraft)) {
            for (Path path : stream) {
                names.add(path.getFileName().toString());
            }
        }
        assertEquals(Collections.singletonList("pins-v2.json"), names);
    }

    @Test
    void rejectsHostsAndFingerprintsItCannotCanonicalize() {
        PinStore store = PinStore.load(null, new TestClock(BASE));
        assertTrue(store.isUsable(), "a store with no file is usable");
        assertThrows(IllegalArgumentException.class, () -> store.recordPin("example..com", 25565, FP_A));
        assertThrows(IllegalArgumentException.class, () -> store.recordPin("mc.example.com", 0, FP_A));
        assertThrows(IllegalArgumentException.class, () -> store.recordPin("mc.example.com", 25565, "Sha256::0"));
        assertThrows(IllegalArgumentException.class,
                () -> store.recordPin("mc.example.com", 25565, FP_A.toUpperCase(Locale.ROOT)));
        assertThrows(IllegalArgumentException.class, () -> store.recordPin("mc.example.com", 25565, "sha256:abc"));
        assertThrows(IllegalArgumentException.class, () -> store.pin("fe80::1%eth0", 25565));
        assertThrows(IllegalArgumentException.class, () -> store.deletePin("mc.example.com", 70000));
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        assertEquals(1, store.size());
    }

    @Test
    void unreadableExistingStateIsNotAnEmptyStore(@TempDir Path dir) throws IOException {
        TestClock now = new TestClock(BASE);
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        byte[] valid = pins(1).getBytes(StandardCharsets.UTF_8);

        Path quicraft = Files.createDirectories(dir.resolve("quicraft"));
        Path locked = quicraft.resolve("pins-v2.json");
        Files.write(locked, valid);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "the process ignores the file mode (root)");
            PinStore unreadableFile = PinStore.load(locked, now);
            assertFalse(unreadableFile.isUsable(), "a file the process may not read is not an empty store");
            assertEquals(PinStore.RecordResult.DISABLED, unreadableFile.recordPin("new.example.com", 25565, FP_A));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
        }
        assertArrayEquals(valid, Files.readAllBytes(locked), "the unreadable file was left alone");

        Path lockedDirectory = Files.createDirectories(dir.resolve("locked-directory"));
        Path insideLocked = lockedDirectory.resolve("pins-v2.json");
        Files.write(insideLocked, valid);
        Files.setPosixFilePermissions(lockedDirectory, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(insideLocked), "the process ignores the directory mode (root)");
            PinStore unreadableDirectory = PinStore.load(insideLocked, now);
            assertFalse(unreadableDirectory.isUsable(), "a file the process cannot even stat is not missing");
        } finally {
            Files.setPosixFilePermissions(lockedDirectory, PosixFilePermissions.fromString("rwx------"));
        }
        assertArrayEquals(valid, Files.readAllBytes(insideLocked), "the unreachable file was left alone");
    }

    @Test
    void aDirectoryWhereTheFileBelongsDisablesTheStore(@TempDir Path dir) throws IOException {
        Path asDirectory = Files.createDirectories(dir.resolve("pins-v2.json"));
        PinStore store = PinStore.load(asDirectory, new TestClock(BASE));
        assertFalse(store.isUsable(), "a directory is unreadable state, not a missing file");
        assertEquals(PinStore.RecordResult.DISABLED, store.recordPin("mc.example.com", 25565, FP_A));
        assertFalse(store.pin("mc.example.com", 25565).isPresent());
        assertTrue(Files.isDirectory(asDirectory), "the directory was not touched");
        assertFalse(store.deletePin("mc.example.com", 25565));
    }

    @Test
    void aFailedFlushOrMoveDisablesTheStoreAndKeepsTheOldFile(@TempDir Path dir) throws IOException {
        Path quicraft = Files.createDirectories(dir.resolve("quicraft"));
        Path file = quicraft.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        byte[] before = Files.readAllBytes(file);

        Faults.set(StateFile.FAULT_FORCE);
        try {
            assertEquals(PinStore.RecordResult.DISABLED, store.recordPin("flushed.example.com", 25565, FP_A));
        } finally {
            Faults.set();
        }
        assertFalse(store.isUsable(), "a failed flush disables the store");
        assertArrayEquals(before, Files.readAllBytes(file), "the old pins file is intact");
        assertFalse(store.deletePin("mc.example.com", 25565), "and the store keeps refusing writes");
        assertEquals(Collections.singletonList("pins-v2.json"), names(quicraft), "no temporary file is left behind");

        Path moved = dir.resolve("move").resolve("pins-v2.json");
        PinStore moving = PinStore.load(moved, now);
        assertEquals(PinStore.RecordResult.RECORDED, moving.recordPin("mc.example.com", 25565, FP_B));
        byte[] beforeMove = Files.readAllBytes(moved);
        Faults.set(StateFile.FAULT_MOVE);
        try {
            assertEquals(PinStore.RecordResult.DISABLED, moving.recordPin("other.example.com", 25565, FP_A));
        } finally {
            Faults.set();
        }
        assertFalse(moving.isUsable(), "a failed rename disables the store");
        assertArrayEquals(beforeMove, Files.readAllBytes(moved), "the old pins file is intact");
        assertEquals(Collections.singletonList("pins-v2.json"), names(moved.getParent()));
    }

    @Test
    void loadSweepsItsOwnTemporaryLeftovers(@TempDir Path dir) throws IOException {
        Path quicraft = Files.createDirectories(dir.resolve("quicraft"));
        Path file = quicraft.resolve("pins-v2.json");
        Path leftover = quicraft.resolve("pins-v2.json1234567890.tmp");
        Files.write(leftover, "{stale".getBytes(StandardCharsets.UTF_8));
        Path notOurs = quicraft.resolve("pins-v2.json.tmp");
        Files.write(notOurs, "keep".getBytes(StandardCharsets.UTF_8));
        Path otherFile = quicraft.resolve("rebind-v1.bin");
        Files.write(otherFile, "keep".getBytes(StandardCharsets.UTF_8));

        PinStore store = PinStore.load(file, new TestClock(BASE));
        assertTrue(store.isUsable());
        assertFalse(Files.exists(leftover), "our own leftover was removed");
        assertTrue(Files.exists(notOurs), "a name we never create is left alone");
        assertTrue(Files.exists(otherFile), "another file in the directory is left alone");
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        assertEquals(Arrays.asList("pins-v2.json", "pins-v2.json.tmp", "rebind-v1.bin"), names(quicraft));
    }

    @Test
    void aPinInsideTheTtlIsKeptAndWritesNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        byte[] before = Files.readAllBytes(file);

        now.advance(PinStore.PIN_TTL_MILLIS - TimeUnit.HOURS.toMillis(1));
        assertTrue(store.pin("mc.example.com", 25565).isPresent(), "29d23h is inside the 30 day TTL");
        assertEquals(1, store.size());
        assertArrayEquals(before, Files.readAllBytes(file), "a lookup that expires nothing writes nothing");
    }

    @Test
    void aPinPastTheTtlIsRemovedAndPersisted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));

        now.advance(PinStore.PIN_TTL_MILLIS + 1);
        assertFalse(store.pin("mc.example.com", 25565).isPresent(),
                "30 days and 1 ms after its last binding it has expired");
        assertEquals(0, store.size());
        assertEquals("{\"version\":1,\"pins\":[]}",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8), "the removal was written");

        PinStore reloaded = PinStore.load(file, now);
        assertTrue(reloaded.isUsable());
        assertEquals(0, reloaded.size());
        assertFalse(reloaded.pin("mc.example.com", 25565).isPresent(), "a reload from disk shows it gone");
    }

    @Test
    void aPinExactlyAtTheTtlIsKeptAndOneMillisecondPastItIsNot(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));

        now.advance(PinStore.PIN_TTL_MILLIS);
        assertTrue(store.pin("mc.example.com", 25565).isPresent(),
                "exactly 30 days is not yet more than 30 days");
        now.advance(1);
        assertFalse(store.pin("mc.example.com", 25565).isPresent(), "one millisecond more has expired");
        assertEquals(0, store.size());
    }

    @Test
    void expiredKeysAnswersWithoutChangingAnything(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        byte[] before = Files.readAllBytes(file);

        now.advance(PinStore.PIN_TTL_MILLIS + 1);
        Set<PinKey> expired = store.expiredKeys(Collections.<PinKey>emptySet());
        assertEquals(Collections.singleton(PinKey.of("mc.example.com", 25565)), expired);
        assertEquals(1, store.size(), "asking which pins expired expires nothing");
        assertArrayEquals(before, Files.readAllBytes(file), "and writes nothing");
        assertTrue(store.expiredKeys(expired).isEmpty(), "a key in the active set has not expired");
        assertEquals(1, store.size(), "and the pin is still there until something removes it");
        assertFalse(store.pin("mc.example.com", 25565).isPresent(),
                "a lookup without the active set removes it, and answers empty");
        assertEquals(0, store.size());
    }

    @Test
    void aLastBoundTimeInTheFutureHasExpired(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));

        now.advance(-1);
        assertFalse(store.pin("mc.example.com", 25565).isPresent(),
                "a last-bound time one millisecond in the future has expired");
        assertEquals("{\"version\":1,\"pins\":[]}",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        now.advance(-DAY);
        assertFalse(store.pin("mc.example.com", 25565).isPresent(),
                "and so has one a day in the future, after the clock moved backwards");

        now.advance(DAY + PinStore.PIN_TTL_MILLIS + 1);
        assertFalse(store.pin("mc.example.com", 25565).isPresent(),
                "moving the clock forward again never revives a pin that expired");
        assertEquals(0, PinStore.load(file, now).size());
    }

    @Test
    void aFullStoreWithOneExpiredPinAcceptsANewName(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        Files.write(file, fullStoreWithOneExpiredPin().getBytes(StandardCharsets.UTF_8));
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.MAX_PINS, store.size());

        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("new.example.com", 25565, FP_A),
                "the expired pin made room");
        assertEquals(PinStore.MAX_PINS, store.size(), "one expired pin out, one new pin in");

        PinStore reloaded = PinStore.load(file, now);
        assertEquals(PinStore.MAX_PINS, reloaded.size());
        assertFalse(reloaded.pin("host0.example.com", 25565).isPresent(), "the expired pin is gone");
        assertTrue(reloaded.pin("host1.example.com", 25565).isPresent(), "a pin inside its TTL stays");
        assertTrue(reloaded.pin("new.example.com", 25565).isPresent());
    }

    @Test
    void aLookupRemovesAnExpiredPinFromAFullStore(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        Files.write(file, fullStoreWithOneExpiredPin().getBytes(StandardCharsets.UTF_8));
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);

        assertTrue(store.pin("host1.example.com", 25565).isPresent());
        assertEquals(PinStore.MAX_PINS - 1, store.size(), "the lookup expired the one expired pin");
        PinStore reloaded = PinStore.load(file, now);
        assertEquals(PinStore.MAX_PINS - 1, reloaded.size());
        assertFalse(reloaded.pin("host0.example.com", 25565).isPresent());
    }

    @Test
    void rebindingRefreshesLastBoundSoThePinSurvivesAnotherTtl(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));

        now.advance(PinStore.PIN_TTL_MILLIS - DAY);
        assertEquals(PinStore.RecordResult.UPDATED, store.recordPin("mc.example.com", 25565, FP_B),
                "a re-bind refreshes the last-bound time");
        now.advance(PinStore.PIN_TTL_MILLIS - DAY);
        assertTrue(store.pin("mc.example.com", 25565).isPresent(),
                "nearly 60 days after the first binding, but less than 30 after the last");

        PinStore.Pin pin = store.pin("mc.example.com", 25565).get();
        assertEquals(FP_B, pin.fingerprint());
        assertEquals(Instant.ofEpochMilli(BASE), pin.firstBound(), "first-bound still survives the re-bind");
        assertEquals(Instant.ofEpochMilli(BASE + PinStore.PIN_TTL_MILLIS - DAY), pin.lastBound());

        now.advance(DAY + 1);
        assertFalse(store.pin("mc.example.com", 25565).isPresent(), "30 days after the last bind it expires");
        assertEquals(0, store.size());
    }

    @Test
    void anActiveAttemptsPinIsNeverExpired(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("other.example.com", 25565, FP_A));
        now.advance(PinStore.PIN_TTL_MILLIS + 1);

        Set<PinKey> active = Collections.singleton(PinKey.of("mc.example.com", 25565));
        assertTrue(store.pin("mc.example.com", 25565, active).isPresent(),
                "an attempt for it is running, so it is not expired under that attempt");
        assertEquals(1, store.size(), "the other expired pin went");
        assertTrue(store.purgeExpired(active).isEmpty(), "nothing else has expired");
        assertTrue(store.pin("mc.example.com", 25565, active).isPresent());

        assertTrue(store.purgeExpired(Collections.<PinKey>emptySet())
                        .contains(PinKey.of("mc.example.com", 25565)),
                "once no attempt is running, it expires like any other pin");
        assertFalse(store.pin("mc.example.com", 25565).isPresent());
        assertEquals(0, store.size());

        PinStore reloaded = PinStore.load(file, now);
        assertEquals(0, reloaded.size(), "both removals are on disk");
        assertFalse(reloaded.pin("other.example.com", 25565).isPresent());
    }

    @Test
    void recordPinKeepsThePinOfARunningAttemptAndPurgesTheRest(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        Files.write(file, pins(PinStore.MAX_PINS, EXPIRED_BOUND).getBytes(StandardCharsets.UTF_8));
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);

        Set<PinKey> active = Collections.singleton(PinKey.of("host0.example.com", 25565));
        assertEquals(PinStore.RecordResult.UPDATED,
                store.recordPin("host0.example.com", 25565, FP_B, active),
                "its own attempt is running, so its expired pin is kept and updated");
        assertEquals(1, store.size(), "every other expired pin made room");
        assertEquals(FP_B, store.pin("host0.example.com", 25565, active).get().fingerprint());
    }

    @Test
    void aFailedWriteDuringPurgeDisablesTheStoreAndKeepsTheOldFile(@TempDir Path dir) throws IOException {
        Path quicraft = Files.createDirectories(dir.resolve("quicraft"));
        Path file = quicraft.resolve("pins-v2.json");
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("mc.example.com", 25565, FP_A));
        byte[] before = Files.readAllBytes(file);
        now.advance(PinStore.PIN_TTL_MILLIS + 1);

        Faults.set(StateFile.FAULT_FORCE);
        try {
            assertTrue(store.purgeExpired(Collections.<PinKey>emptySet()).isEmpty(),
                    "a removal that could not be written removed nothing");
            assertFalse(store.pin("mc.example.com", 25565).isPresent(),
                    "a store that just became disabled answers empty, not the pin it still holds");
        } finally {
            Faults.set();
        }
        assertFalse(store.isUsable(), "a failed write disables the store");
        assertArrayEquals(before, Files.readAllBytes(file), "the old pins file is intact");
        assertEquals(1, store.size(), "the removal was not applied, like every other failed write");
        assertEquals(PinStore.RecordResult.DISABLED, store.recordPin("other.example.com", 25565, FP_B));
        assertFalse(store.deletePin("mc.example.com", 25565));
        assertEquals(Collections.singletonList("pins-v2.json"), names(quicraft), "no temporary is left");
    }

    @Test
    void aDisabledStoreExpiresNothingAndWritesNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        Files.write(file, "{".getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(file);
        TestClock now = new TestClock(BASE);
        PinStore store = PinStore.load(file, now);
        assertFalse(store.isUsable());
        assertTrue(store.purgeExpired(Collections.<PinKey>emptySet()).isEmpty());
        assertFalse(store.pin("mc.example.com", 25565).isPresent());
        assertArrayEquals(before, Files.readAllBytes(file), "a disabled store writes nothing");
    }

    /** A pin entry last bound at {@code lastBound}, as a run of the client would have written it. */
    private static String entry(String host, int port, String fingerprint, String lastBound) {
        return "{\"host\":\"" + host + "\",\"port\":" + port + ",\"fingerprint\":\"" + fingerprint + "\""
                + ",\"firstBound\":\"" + lastBound + "\",\"lastBound\":\"" + lastBound + "\"}";
    }

    /** A pin entry that expired long before any test clock. */
    private static String entry(String host, int port, String fingerprint) {
        return entry(host, port, fingerprint, EXPIRED_BOUND);
    }

    /** The file names in {@code directory}, sorted. */
    private static List<String> names(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                names.add(path.getFileName().toString());
            }
        }
        Collections.sort(names);
        return names;
    }

    /** A pins file holding {@code count} pins, as an older run of the client would have written it. */
    private static String pins(int count) {
        return pins(count, FRESH_BOUND);
    }

    /** A pins file holding {@code count} pins, every one of them last bound at {@code lastBound}. */
    private static String pins(int count, String lastBound) {
        StringBuilder sb = new StringBuilder(count * 200 + 64);
        sb.append("{\"version\":1,\"pins\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(entry("host" + i + ".example.com", 25565, FP_A, lastBound));
        }
        return sb.append("]}").toString();
    }

    /** A full pins file whose first pin, the least recently used one, has expired. */
    private static String fullStoreWithOneExpiredPin() {
        StringBuilder sb = new StringBuilder(PinStore.MAX_PINS * 200 + 64);
        sb.append("{\"version\":1,\"pins\":[")
                .append(entry("host0.example.com", 25565, FP_A, EXPIRED_BOUND));
        for (int i = 1; i < PinStore.MAX_PINS; i++) {
            sb.append(',').append(entry("host" + i + ".example.com", 25565, FP_A, FRESH_BOUND));
        }
        return sb.append("]}").toString();
    }

    private static String fingerprint(char c) {
        char[] hex = new char[64];
        Arrays.fill(hex, c);
        return "sha256:" + new String(hex);
    }
}
