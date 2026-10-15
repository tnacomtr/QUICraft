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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.Faults;

/** The persisted authenticated pin store (docs/protocol.md 12.15). */
class PinStoreTest {
    /** 2023-11-14T22:13:20Z, so the timestamps written are easy to read. */
    private static final long BASE = 1_700_000_000_000L;
    private static final String FP_A = fingerprint('a');
    private static final String FP_B = fingerprint('b');

    @Test
    void missingFileIsAnEmptyUsableStore(@TempDir Path dir) {
        Path file = dir.resolve("quicraft").resolve("pins-v2.json");
        PinStore store = PinStore.load(file, new AtomicLong(BASE)::get);
        assertTrue(store.isUsable());
        assertEquals(0, store.size());
        assertFalse(store.pin("mc.example.com", 25565).isPresent());
        assertFalse(Files.exists(file), "loading writes nothing");
    }

    @Test
    void recordsAndReloadsPins(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("quicraft").resolve("pins-v2.json");
        AtomicLong now = new AtomicLong(BASE);
        PinStore store = PinStore.load(file, now::get);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("MC.Example.COM.", 25565, FP_A));
        now.addAndGet(60_000);
        assertEquals(PinStore.RecordResult.UPDATED, store.recordPin("mc.example.com", 25565, FP_B));
        assertEquals(1, store.size(), "one name, two spellings, one pin");
        PinStore.Pin live = store.pin("mc.example.com", 25565).get();
        assertEquals("mc.example.com", live.host());
        assertEquals(25565, live.port());
        assertEquals(FP_B, live.fingerprint());
        assertEquals(Instant.ofEpochMilli(BASE), live.firstBound(), "first-bound survives a re-bind");
        assertEquals(Instant.ofEpochMilli(BASE + 60_000), live.lastBound());

        PinStore reloaded = PinStore.load(file, now::get);
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

        AtomicLong now = new AtomicLong(BASE);
        for (Map.Entry<String, String> caseFile : corrupt.entrySet()) {
            Path file = dir.resolve(caseFile.getKey() + ".json");
            byte[] content = caseFile.getValue().getBytes(StandardCharsets.UTF_8);
            Files.write(file, content);
            PinStore store = PinStore.load(file, now::get);
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
        AtomicLong now = new AtomicLong(BASE);
        Path file = notADirectory.resolve("pins-v2.json");
        PinStore store = PinStore.load(file, now::get);
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
        PinStore store = PinStore.load(file, new AtomicLong(BASE)::get);
        assertTrue(store.isUsable());
        assertEquals(PinStore.RecordResult.DISABLED, store.recordPin("mc.example.com", 25565, FP_A));
        assertFalse(store.isUsable());
        assertFalse(Files.exists(file), "the failed write left nothing behind");
    }

    @Test
    void fullStoreRefusesNewNamesAndNeverEvicts(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pins-v2.json");
        Files.write(file, pins(PinStore.MAX_PINS).getBytes(StandardCharsets.UTF_8));
        AtomicLong now = new AtomicLong(BASE);
        PinStore store = PinStore.load(file, now::get);
        assertTrue(store.isUsable());
        assertEquals(PinStore.MAX_PINS, store.size());
        assertEquals(PinStore.RecordResult.FULL, store.recordPin("new.example.com", 25565, FP_A));
        assertEquals(PinStore.MAX_PINS, store.size());
        assertEquals(PinStore.RecordResult.UPDATED, store.recordPin("host0.example.com", 25565, FP_B),
                "a name the store knows is always updatable");
        assertEquals(PinStore.MAX_PINS, store.size());

        PinStore reloaded = PinStore.load(file, now::get);
        assertEquals(PinStore.MAX_PINS, reloaded.size(), "nothing was evicted");
        assertTrue(reloaded.pin("host4095.example.com", 25565).isPresent());
        assertFalse(reloaded.pin("new.example.com", 25565).isPresent());
        assertEquals(FP_B, reloaded.pin("host0.example.com", 25565).get().fingerprint());
    }

    @Test
    void deletePinRemovesOnlyThatServer(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("quicraft").resolve("pins-v2.json");
        AtomicLong now = new AtomicLong(BASE);
        PinStore store = PinStore.load(file, now::get);
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("one.example.com", 25565, FP_A));
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("one.example.com", 25566, FP_A));
        assertEquals(PinStore.RecordResult.RECORDED, store.recordPin("two.example.com", 25565, FP_A));
        assertTrue(store.deletePin("ONE.Example.com.", 25565));
        assertEquals(2, store.size());
        assertFalse(store.deletePin("one.example.com", 25565), "already gone");
        PinStore reloaded = PinStore.load(file, now::get);
        assertFalse(reloaded.pin("one.example.com", 25565).isPresent());
        assertTrue(reloaded.pin("one.example.com", 25566).isPresent());
        assertTrue(reloaded.pin("two.example.com", 25565).isPresent());
    }

    @Test
    void pinsAreOwnerOnlyWherePosixIsSupported(@TempDir Path dir) throws IOException {
        Path quicraft = dir.resolve("quicraft");
        Path file = quicraft.resolve("pins-v2.json");
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        PinStore store = PinStore.load(file, new AtomicLong(BASE)::get);
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
        PinStore store = PinStore.load(null, new AtomicLong(BASE)::get);
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
        AtomicLong now = new AtomicLong(BASE);
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        byte[] valid = pins(1).getBytes(StandardCharsets.UTF_8);

        Path quicraft = Files.createDirectories(dir.resolve("quicraft"));
        Path locked = quicraft.resolve("pins-v2.json");
        Files.write(locked, valid);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(locked), "the process ignores the file mode (root)");
            PinStore unreadableFile = PinStore.load(locked, now::get);
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
            PinStore unreadableDirectory = PinStore.load(insideLocked, now::get);
            assertFalse(unreadableDirectory.isUsable(), "a file the process cannot even stat is not missing");
        } finally {
            Files.setPosixFilePermissions(lockedDirectory, PosixFilePermissions.fromString("rwx------"));
        }
        assertArrayEquals(valid, Files.readAllBytes(insideLocked), "the unreachable file was left alone");
    }

    @Test
    void aDirectoryWhereTheFileBelongsDisablesTheStore(@TempDir Path dir) throws IOException {
        Path asDirectory = Files.createDirectories(dir.resolve("pins-v2.json"));
        PinStore store = PinStore.load(asDirectory, new AtomicLong(BASE)::get);
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
        AtomicLong now = new AtomicLong(BASE);
        PinStore store = PinStore.load(file, now::get);
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
        PinStore moving = PinStore.load(moved, now::get);
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

    private static String entry(String host, int port, String fingerprint) {
        return "{\"host\":\"" + host + "\",\"port\":" + port + ",\"fingerprint\":\"" + fingerprint + "\""
                + ",\"firstBound\":\"2000-01-01T00:00:00Z\",\"lastBound\":\"2000-01-01T00:00:00Z\"}";
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
        StringBuilder sb = new StringBuilder(count * 200 + 64);
        sb.append("{\"version\":1,\"pins\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(entry("host" + i + ".example.com", 25565, FP_A));
        }
        return sb.append("]}").toString();
    }

    private static String fingerprint(char c) {
        char[] hex = new char[64];
        Arrays.fill(hex, c);
        return "sha256:" + new String(hex);
    }
}
