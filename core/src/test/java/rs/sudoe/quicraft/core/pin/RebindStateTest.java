// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.Faults;

/** The changed-pin re-bind backoff and notice deduplication state (docs/protocol.md 12.16). */
class RebindStateTest {
    /** 2023-11-14T22:13:20Z. */
    private static final long BASE = 1_700_000_000_000L;
    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
    private static final String FP_A = fingerprint(1);
    private static final String FP_B = fingerprint(2);
    private static final String FUTURE = "9999-01-01T00:00:00Z";
    /** The state file's header before the JSON: magic (0-3), format version (4-7), JSON length (8-11). */
    private static final int HEADER = 12;
    /** The SHA-256 after the bitmap. */
    private static final int DIGEST = 32;

    @Test
    void backoffStartsAtOneMinuteAndDoublesToAnHour() {
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(null, now::get);
        assertTrue(state.isUsable());
        assertTrue(state.allowsAttempt("mc.example.com", 25565, now.get()), "nothing has failed yet");
        long[] minutes = {1, 2, 4, 8, 16, 32, 60, 60};
        for (long expected : minutes) {
            assertTrue(state.recordAttemptFailure("mc.example.com", 25565));
            now.addAndGet(expected * MINUTE - 1);
            assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()),
                    "still blocked just before " + expected + " min");
            now.addAndGet(1);
            assertTrue(state.allowsAttempt("mc.example.com", 25565, now.get()),
                    "allowed again after " + expected + " min");
        }
    }

    @Test
    void backoffIsKeyedLikeAPinAndIgnoresTheFingerprint(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertTrue(state.recordAttemptFailure("MC.Example.COM.", 25565));
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()), "one key, two spellings");
        assertTrue(state.allowsAttempt("mc.example.com", 25566, now.get()), "another logical port");
        assertTrue(state.allowsAttempt("other.example.com", 25565, now.get()), "another logical host");
        // A new advertised fingerprint is a new notice, never a reset of the window: no backoff method
        // is given a fingerprint at all, so a rotating fingerprint cannot clear it.
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_A));
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_B));
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()));
        now.addAndGet(MINUTE - 1);
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()));
        now.addAndGet(1);
        assertTrue(state.allowsAttempt("mc.example.com", 25565, now.get()));

        RebindState reloaded = RebindState.load(file, now::get);
        assertFalse(reloaded.allowsAttempt("mc.example.com", 25565, now.get() - 1), "the window is persisted");
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_A));
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_B));
    }

    @Test
    void aSuccessClearsTheWindow(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertTrue(state.recordAttemptFailure("mc.example.com", 25565));
        now.addAndGet(MINUTE - 1);
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()), "inside the first window");
        state.recordSuccess("mc.example.com", 25565);
        assertTrue(state.allowsAttempt("mc.example.com", 25565, now.get()), "the entry is gone");
        assertTrue(state.recordAttemptFailure("mc.example.com", 25565));
        now.addAndGet(MINUTE - 1);
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()), "the schedule started over");
        now.addAndGet(1);
        assertTrue(state.allowsAttempt("mc.example.com", 25565, now.get()));

        RebindState reloaded = RebindState.load(file, now::get);
        assertTrue(reloaded.allowsAttempt("mc.example.com", 25565, now.get()));
    }

    @Test
    void evictsTheLeastRecentlyUsedServerThatIsNotActive(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        Files.write(file, stateFile(servers(RebindState.MAX_ENTRIES)));
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertEquals(RebindState.MAX_ENTRIES, state.size());
        assertFalse(state.allowsAttempt("s0.example.com", 25565, now.get()), "inside its window");
        assertTrue(state.beginAttempt("s0.example.com", 25565));

        assertTrue(state.recordAttemptFailure("new.example.com", 25565), "makes room by evicting");
        assertEquals(RebindState.MAX_ENTRIES, state.size());
        assertFalse(state.allowsAttempt("s0.example.com", 25565, now.get()), "the active attempt is kept");
        assertFalse(state.allowsAttempt("new.example.com", 25565, now.get()), "its failure started a window");
        assertTrue(state.allowsAttempt("s1.example.com", 25565, now.get()), "s1 was the least recent one");
    }

    @Test
    void failsWhenEveryEntryIsActiveAndNothingCanBeEvicted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        Files.write(file, stateFile(servers(RebindState.MAX_ENTRIES)));
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        for (int i = 0; i < RebindState.MAX_ENTRIES; i++) {
            assertTrue(state.beginAttempt("s" + i + ".example.com", 25565));
        }
        assertFalse(state.allowsAttempt("another.example.com", 25565, now.get()), "nothing may be evicted");
        assertFalse(state.recordAttemptFailure("another.example.com", 25565));
        assertFalse(state.shouldNotify("another.example.com", 25565, FP_A), "no room to remember a notice");
        assertEquals(RebindState.MAX_ENTRIES, state.size());

        state.endAttempt("s0.example.com", 25565);
        assertTrue(state.recordAttemptFailure("another.example.com", 25565), "the ended attempt may be evicted");
        assertEquals(RebindState.MAX_ENTRIES, state.size());
        String json = jsonOf(Files.readAllBytes(file));
        assertFalse(json.contains("\"host\":\"s0.example.com\""), "the state of the ended attempt is gone");
    }

    @Test
    void noticeIsShownAtMostOncePerTupleAcrossRestarts(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertTrue(state.shouldNotify("MC.Example.COM.", 25565, FP_A));
        assertFalse(state.shouldNotify("mc.example.com", 25565, FP_A), "the same tuple, spelled differently");
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_B), "a new fingerprint");
        assertTrue(state.shouldNotify("mc.example.com", 25566, FP_A), "another logical port");
        assertTrue(state.shouldNotify("other.example.com", 25565, FP_A), "another logical host");

        RebindState reloaded = RebindState.load(file, now::get);
        assertTrue(reloaded.isUsable());
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_A));
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_B));
        assertFalse(reloaded.shouldNotify("mc.example.com", 25566, FP_A));
        assertFalse(reloaded.shouldNotify("other.example.com", 25565, FP_A));
        assertTrue(reloaded.shouldNotify("other.example.com", 25565, FP_B), "only the exact tuple is used up");
    }

    @Test
    void theBitmapStillSuppressesAfterTheHistoryEvictedTheTuple(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        for (int i = 0; i < RebindState.MAX_FINGERPRINTS_PER_SERVER; i++) {
            assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(i)), "notice " + i);
        }
        byte[] before = Files.readAllBytes(file);
        assertEquals(RebindBloom.BYTES, bitmapOf(before).length);

        assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(16)), "this evicts the oldest");
        byte[] after = Files.readAllBytes(file);
        byte[] beforeBitmap = bitmapOf(before);
        byte[] afterBitmap = bitmapOf(after);
        for (int i = 0; i < beforeBitmap.length; i++) {
            assertFalse((beforeBitmap[i] & ~afterBitmap[i]) != 0, "no bit is ever cleared");
        }
        String json = jsonOf(after);
        assertTrue(json.contains("fingerprint\":\"" + fingerprint(16)), "the newest notice is kept");
        assertFalse(json.contains("fingerprint\":\"" + fingerprint(0)), "the oldest was evicted");

        assertFalse(state.shouldNotify("a.example.com", 25565, fingerprint(0)), "the bitmap still knows it");
        RebindState reloaded = RebindState.load(file, now::get);
        assertFalse(reloaded.shouldNotify("a.example.com", 25565, fingerprint(0)), "and it survives a restart");
        assertTrue(reloaded.shouldNotify("a.example.com", 25565, fingerprint(17)), "an unseen tuple is new");
    }

    @Test
    void missingFileIsAnEmptyUsableState(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("quicraft").resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertTrue(state.isUsable());
        assertEquals(0, state.size());
        assertTrue(state.allowsAttempt("mc.example.com", 25565, now.get()));
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_A));
        assertTrue(Files.isRegularFile(file));
        byte[] content = Files.readAllBytes(file);
        assertEquals(HEADER + jsonOf(content).getBytes(StandardCharsets.UTF_8).length
                + RebindBloom.BYTES + DIGEST, content.length, "one file holds the state and the bitmap");
        assertTrue(jsonOf(content).contains("\"lastNoticed\":\"2023-11-14T22:13:20Z\""), jsonOf(content));
    }

    @Test
    void damagedStateFailsClosedAndIsNeverOverwritten(@TempDir Path dir) throws IOException {
        AtomicLong now = new AtomicLong(BASE);
        byte[] json = "{\"version\":1,\"servers\":[]}".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> damaged = new LinkedHashMap<>();
        byte[] flippedBitmap = stateFile(json);
        flippedBitmap[HEADER + json.length + 4096] ^= 0x01;
        damaged.put("flipped-bitmap", flippedBitmap);
        byte[] flippedJson = stateFile(json);
        flippedJson[HEADER + 1] ^= 0x01;
        damaged.put("flipped-json", flippedJson);
        byte[] truncated = stateFile(json);
        damaged.put("truncated", Arrays.copyOfRange(truncated, 0, truncated.length - 1));
        byte[] wrongMagic = stateFile(json);
        wrongMagic[0] = 'X';
        damaged.put("wrong-magic", wrongMagic);
        byte[] wrongVersion = stateFile(json);
        wrongVersion[7] = 2;
        damaged.put("wrong-format-version", wrongVersion);
        byte[] wrongLength = stateFile(json);
        wrongLength[11] = 9;
        damaged.put("wrong-json-length", wrongLength);
        damaged.put("not-json", stateFile("{not json".getBytes(StandardCharsets.UTF_8)));

        for (Map.Entry<String, byte[]> entry : damaged.entrySet()) {
            Path file = dir.resolve(entry.getKey() + ".bin");
            byte[] content = entry.getValue();
            Files.write(file, content);
            RebindState state = RebindState.load(file, now::get);
            assertFalse(state.isUsable(), entry.getKey());
            assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()), entry.getKey());
            assertFalse(state.recordAttemptFailure("mc.example.com", 25565), entry.getKey());
            assertFalse(state.shouldNotify("mc.example.com", 25565, FP_A), entry.getKey());
            assertArrayEquals(content, Files.readAllBytes(file), entry.getKey() + " was not overwritten");
        }

        Path intact = dir.resolve("intact.bin");
        Files.write(intact, stateFile(json));
        assertTrue(RebindState.load(intact, now::get).isUsable(), "an undamaged file still loads");
    }

    @Test
    void unreadableExistingFileFailsClosed(@TempDir Path dir) throws IOException {
        AtomicLong now = new AtomicLong(BASE);
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        byte[] content = stateFile("{\"version\":1,\"servers\":[]}".getBytes(StandardCharsets.UTF_8));

        Path stateDirectory = Files.createDirectories(dir.resolve("unreadable"));
        Path stateFile = stateDirectory.resolve("rebind-v1.bin");
        Files.write(stateFile, content);
        Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(stateFile), "the process ignores the file mode (root)");
            RebindState unreadable = RebindState.load(stateFile, now::get);
            assertFalse(unreadable.isUsable(), "a file the process may not read fails closed");
            assertFalse(unreadable.allowsAttempt("mc.example.com", 25565, now.get()));
            assertFalse(unreadable.shouldNotify("mc.example.com", 25565, FP_A));
        } finally {
            Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-------"));
        }
        assertArrayEquals(content, Files.readAllBytes(stateFile), "the unreadable file was left alone");

        Path lockedDirectory = Files.createDirectories(dir.resolve("locked-directory"));
        Path insideLocked = lockedDirectory.resolve("rebind-v1.bin");
        Files.write(insideLocked, content);
        Files.setPosixFilePermissions(lockedDirectory, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(insideLocked), "the process ignores the directory mode (root)");
            RebindState unreachable = RebindState.load(insideLocked, now::get);
            assertFalse(unreachable.isUsable(), "a file the process cannot even stat is not missing");
        } finally {
            Files.setPosixFilePermissions(lockedDirectory, PosixFilePermissions.fromString("rwx------"));
        }
        assertArrayEquals(content, Files.readAllBytes(insideLocked), "the unreachable file was left alone");

        Path asDirectory = Files.createDirectories(dir.resolve("directory-state").resolve("rebind-v1.bin"));
        RebindState directoryState = RebindState.load(asDirectory, now::get);
        assertFalse(directoryState.isUsable(), "a directory is unreadable state, not a missing file");
        assertFalse(directoryState.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(directoryState.shouldNotify("mc.example.com", 25565, FP_A));
        assertTrue(Files.isDirectory(asDirectory), "the directory was not touched");
    }

    @Test
    void unwritableStateFailsClosed(@TempDir Path dir) throws IOException {
        AtomicLong now = new AtomicLong(BASE);
        Path notADirectory = dir.resolve("blocked");
        Files.write(notADirectory, new byte[0]);
        Path file = notADirectory.resolve("rebind-v1.bin");
        RebindState state = RebindState.load(file, now::get);
        assertTrue(state.isUsable(), "the file is missing, not corrupt");
        assertTrue(state.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(state.recordAttemptFailure("mc.example.com", 25565), "the write fails");
        assertFalse(state.isUsable());
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(state.shouldNotify("mc.example.com", 25565, FP_A));
        assertFalse(Files.exists(file));
    }

    @Test
    void readOnlyDirectoryFailsClosed(@TempDir Path dir) throws IOException {
        Path quicraft = Files.createDirectories(dir.resolve("quicraft"));
        Assumptions.assumeTrue(quicraft.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(quicraft, PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(quicraft), "the process ignores the directory mode (root)");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(quicraft.resolve("rebind-v1.bin"), now::get);
        assertTrue(state.isUsable());
        assertFalse(state.shouldNotify("mc.example.com", 25565, FP_A), "no notice without a durable mark");
        assertFalse(state.isUsable());
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()));
    }

    @Test
    void smallLimitsEvictTheLeastRecentlyUsedHistoryAndTheBitmapStillSuppresses(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get, 2, 4, 4);
        assertTrue(state.isUsable());
        for (int i = 0; i < 4; i++) {
            assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(i)), "notice " + i);
        }
        assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(4)), "the per-server cap evicts fp0");
        assertFalse(state.shouldNotify("a.example.com", 25565, fingerprint(0)), "the bitmap still knows fp0");

        assertTrue(state.shouldNotify("b.example.com", 25565, fingerprint(10)), "the total cap evicts a's oldest");
        assertFalse(state.shouldNotify("a.example.com", 25565, fingerprint(1)), "the bitmap still knows fp1");
        String json = jsonOf(Files.readAllBytes(file));
        int[] kept = {2, 3, 4, 10};
        for (int index : kept) {
            assertTrue(json.contains("fingerprint\":\"" + fingerprint(index)), "kept " + index);
        }
        assertFalse(json.contains("fingerprint\":\"" + fingerprint(0)), "evicted fp0");
        assertFalse(json.contains("fingerprint\":\"" + fingerprint(1)), "evicted fp1");

        // The server cap is injected too: two servers fill it, and only an ended attempt frees a slot.
        assertTrue(state.beginAttempt("a.example.com", 25565));
        assertTrue(state.beginAttempt("b.example.com", 25565));
        assertFalse(state.allowsAttempt("c.example.com", 25565, now.get()), "two entries, both active");
        state.endAttempt("a.example.com", 25565);
        assertTrue(state.beginAttempt("c.example.com", 25565), "the ended attempt may be evicted");
        assertEquals(2, state.size());
    }

    @Test
    void aFailedFlushOrMoveFailsClosedAndKeepsTheOldFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_A));
        byte[] before = Files.readAllBytes(file);

        Faults.set(StateFile.FAULT_FORCE);
        try {
            assertFalse(state.shouldNotify("mc.example.com", 25565, FP_B), "a failed flush shows no notice");
        } finally {
            Faults.set();
        }
        assertFalse(state.isUsable(), "a failed flush fails closed");
        assertArrayEquals(before, Files.readAllBytes(file), "the old state file is intact");
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(state.recordAttemptFailure("mc.example.com", 25565));
        assertEquals(Collections.singletonList("rebind-v1.bin"), names(dir));

        Path movedDirectory = Files.createDirectories(dir.resolve("move"));
        Path moved = movedDirectory.resolve("rebind-v1.bin");
        RebindState moving = RebindState.load(moved, now::get);
        assertTrue(moving.shouldNotify("mc.example.com", 25565, FP_A));
        byte[] beforeMove = Files.readAllBytes(moved);
        Faults.set(StateFile.FAULT_MOVE);
        try {
            assertFalse(moving.shouldNotify("mc.example.com", 25565, FP_B), "a failed rename shows no notice");
        } finally {
            Faults.set();
        }
        assertFalse(moving.isUsable(), "a failed rename fails closed");
        assertArrayEquals(beforeMove, Files.readAllBytes(moved), "the old state file is intact");
        assertEquals(Collections.singletonList("rebind-v1.bin"), names(movedDirectory));
    }

    /** A re-bind state file holding {@code count} servers, each inside a window that never ends. */
    private static String servers(int count) {
        StringBuilder sb = new StringBuilder(count * 140 + 64);
        sb.append("{\"version\":1,\"servers\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"host\":\"s").append(i).append(".example.com\",\"port\":25565,\"failures\":1");
            sb.append(",\"blockedUntil\":\"").append(FUTURE).append("\",\"notices\":[]}");
        }
        return sb.append("]}").toString();
    }

    private static byte[] stateFile(String json) {
        return stateFile(json.getBytes(StandardCharsets.UTF_8));
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

    private static byte[] stateFile(byte[] json) {
        return stateFile(json, new byte[RebindBloom.BYTES]);
    }

    /** A state file laid out exactly as {@code RebindState} writes one, so a fixture is a real file. */
    private static byte[] stateFile(byte[] json, byte[] bitmap) {
        byte[] content = new byte[HEADER + json.length + bitmap.length + DIGEST];
        ByteBuffer buffer = ByteBuffer.wrap(content);
        buffer.put(new byte[] {'Q', 'C', 'R', 'B'});
        buffer.putInt(1);
        buffer.putInt(json.length);
        buffer.put(json);
        buffer.put(bitmap);
        buffer.put(sha256(content, content.length - DIGEST));
        return content;
    }

    /** The JSON detailed state of a state file, located by its own header. */
    private static String jsonOf(byte[] content) {
        return new String(content, HEADER, ByteBuffer.wrap(content).getInt(HEADER - 4), StandardCharsets.UTF_8);
    }

    /** The Bloom bitmap of a state file, located by its own header. */
    private static byte[] bitmapOf(byte[] content) {
        return Arrays.copyOfRange(content, HEADER + ByteBuffer.wrap(content).getInt(HEADER - 4),
                content.length - DIGEST);
    }

    private static byte[] sha256(byte[] content, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static String fingerprint(int index) {
        return "sha256:" + String.format(Locale.ROOT, "%064x", index);
    }
}
