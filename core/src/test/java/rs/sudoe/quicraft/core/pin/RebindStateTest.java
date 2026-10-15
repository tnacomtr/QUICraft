// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The changed-pin re-bind backoff and notice deduplication state (docs/protocol.md 12.16). */
class RebindStateTest {
    /** 2023-11-14T22:13:20Z. */
    private static final long BASE = 1_700_000_000_000L;
    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
    private static final String FP_A = fingerprint(1);
    private static final String FP_B = fingerprint(2);
    private static final String FUTURE = "9999-01-01T00:00:00Z";

    @Test
    void backoffStartsAtOneMinuteAndDoublesToAnHour() {
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(null, null, now::get);
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
        Path file = dir.resolve("rebind-v1.json");
        Path bloom = dir.resolve("rebind-bloom-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, bloom, now::get);
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

        RebindState reloaded = RebindState.load(file, bloom, now::get);
        assertFalse(reloaded.allowsAttempt("mc.example.com", 25565, now.get() - 1), "the window is persisted");
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_A));
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_B));
    }

    @Test
    void aSuccessClearsTheWindow(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.json");
        Path bloom = dir.resolve("rebind-bloom-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, bloom, now::get);
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

        RebindState reloaded = RebindState.load(file, bloom, now::get);
        assertTrue(reloaded.allowsAttempt("mc.example.com", 25565, now.get()));
    }

    @Test
    void evictsTheLeastRecentlyUsedServerThatIsNotActive(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.json");
        Path bloom = dir.resolve("rebind-bloom-v1.bin");
        Files.write(file, servers(RebindState.MAX_ENTRIES).getBytes(StandardCharsets.UTF_8));
        writeEmptyBloom(bloom);
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, bloom, now::get);
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
        Path file = dir.resolve("rebind-v1.json");
        Path bloom = dir.resolve("rebind-bloom-v1.bin");
        Files.write(file, servers(RebindState.MAX_ENTRIES).getBytes(StandardCharsets.UTF_8));
        writeEmptyBloom(bloom);
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, bloom, now::get);
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
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertFalse(json.contains("\"host\":\"s0.example.com\""), "the state of the ended attempt is gone");
    }

    @Test
    void noticeIsShownAtMostOncePerTupleAcrossRestarts(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.json");
        Path bloom = dir.resolve("rebind-bloom-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, bloom, now::get);
        assertTrue(state.shouldNotify("MC.Example.COM.", 25565, FP_A));
        assertFalse(state.shouldNotify("mc.example.com", 25565, FP_A), "the same tuple, spelled differently");
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_B), "a new fingerprint");
        assertTrue(state.shouldNotify("mc.example.com", 25566, FP_A), "another logical port");
        assertTrue(state.shouldNotify("other.example.com", 25565, FP_A), "another logical host");

        RebindState reloaded = RebindState.load(file, bloom, now::get);
        assertTrue(reloaded.isUsable());
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_A));
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_B));
        assertFalse(reloaded.shouldNotify("mc.example.com", 25566, FP_A));
        assertFalse(reloaded.shouldNotify("other.example.com", 25565, FP_A));
        assertTrue(reloaded.shouldNotify("other.example.com", 25565, FP_B), "only the exact tuple is used up");
    }

    @Test
    void bloomFilterStillSuppressesAfterTheHistoryEvictedTheTuple(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.json");
        Path bloomFile = dir.resolve("rebind-bloom-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, bloomFile, now::get);
        for (int i = 0; i < RebindState.MAX_FINGERPRINTS_PER_SERVER; i++) {
            assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(i)), "notice " + i);
        }
        byte[] before = Files.readAllBytes(bloomFile);
        assertEquals(RebindBloom.BYTES, before.length);

        assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(16)), "this evicts the oldest");
        byte[] after = Files.readAllBytes(bloomFile);
        for (int i = 0; i < before.length; i++) {
            assertFalse((before[i] & ~after[i]) != 0, "no bit is ever cleared");
        }
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(json.contains("fingerprint\":\"" + fingerprint(16)), "the newest notice is kept");
        assertFalse(json.contains("fingerprint\":\"" + fingerprint(0)), "the oldest was evicted");

        assertFalse(state.shouldNotify("a.example.com", 25565, fingerprint(0)), "the filter still knows it");
        RebindState reloaded = RebindState.load(file, bloomFile, now::get);
        assertFalse(reloaded.shouldNotify("a.example.com", 25565, fingerprint(0)), "and it survives a restart");
        assertTrue(reloaded.shouldNotify("a.example.com", 25565, fingerprint(17)), "an unseen tuple is new");
    }

    @Test
    void missingFilesAreAnEmptyUsableState(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("quicraft").resolve("rebind-v1.json");
        Path bloomFile = dir.resolve("quicraft").resolve("rebind-bloom-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, bloomFile, now::get);
        assertTrue(state.isUsable());
        assertEquals(0, state.size());
        assertTrue(state.allowsAttempt("mc.example.com", 25565, now.get()));
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_A));
        assertTrue(Files.isRegularFile(file));
        assertEquals(RebindBloom.BYTES, Files.size(bloomFile), "the filter file is exactly 1 MiB");
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"lastNoticed\":\"2023-11-14T22:13:20Z\""), json);
    }

    @Test
    void unreadableOrUnwritableStateFailsClosed(@TempDir Path dir) throws IOException {
        AtomicLong now = new AtomicLong(BASE);

        Path corruptDirectory = dir.resolve("corrupt");
        Files.createDirectories(corruptDirectory);
        Path corruptFile = corruptDirectory.resolve("rebind-v1.json");
        byte[] garbage = "{\"version\":1,\"servers\":[".getBytes(StandardCharsets.UTF_8);
        Files.write(corruptFile, garbage);
        Path corruptBloom = corruptDirectory.resolve("rebind-bloom-v1.bin");
        writeEmptyBloom(corruptBloom);
        RebindState corrupt = RebindState.load(corruptFile, corruptBloom, now::get);
        assertFalse(corrupt.isUsable());
        assertFalse(corrupt.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(corrupt.recordAttemptFailure("mc.example.com", 25565));
        assertFalse(corrupt.shouldNotify("mc.example.com", 25565, FP_A));
        assertArrayEquals(garbage, Files.readAllBytes(corruptFile), "the corrupt file was not touched");
        assertEquals(RebindBloom.BYTES, Files.size(corruptBloom), "neither half is touched");

        Path shortDirectory = dir.resolve("short-bloom");
        Files.createDirectories(shortDirectory);
        Path shortBloom = shortDirectory.resolve("rebind-bloom-v1.bin");
        byte[] shortContent = new byte[1024];
        Files.write(shortBloom, shortContent);
        Files.write(shortDirectory.resolve("rebind-v1.json"),
                "{\"version\":1,\"servers\":[]}".getBytes(StandardCharsets.UTF_8));
        RebindState truncated = RebindState.load(shortDirectory.resolve("rebind-v1.json"), shortBloom, now::get);
        assertFalse(truncated.isUsable(), "a filter of the wrong size is corrupt");
        assertFalse(truncated.allowsAttempt("mc.example.com", 25565, now.get()));
        assertArrayEquals(shortContent, Files.readAllBytes(shortBloom));

        Path notADirectory = dir.resolve("blocked");
        Files.write(notADirectory, new byte[0]);
        Path blockedBloom = dir.resolve("blocked-bloom.bin");
        RebindState unwritable = RebindState.load(notADirectory.resolve("rebind-v1.json"),
                blockedBloom, now::get);
        assertTrue(unwritable.isUsable(), "the files are missing, not corrupt");
        assertTrue(unwritable.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(unwritable.recordAttemptFailure("mc.example.com", 25565), "the write fails");
        assertFalse(unwritable.isUsable());
        assertFalse(unwritable.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(unwritable.shouldNotify("mc.example.com", 25565, FP_A));
        assertFalse(Files.exists(notADirectory.resolve("rebind-v1.json")), "the failed half was not written");
        assertEquals(RebindBloom.BYTES, Files.size(blockedBloom),
                "the filter is written first, so the state is left with exactly one half");
        RebindState half = RebindState.load(notADirectory.resolve("rebind-v1.json"), blockedBloom, now::get);
        assertFalse(half.isUsable(), "one half alone is unreliable");
        assertFalse(half.shouldNotify("mc.example.com", 25565, FP_B), "and it is never repaired by guessing");
    }

    @Test
    void readOnlyDirectoryFailsClosed(@TempDir Path dir) throws IOException {
        Path quicraft = Files.createDirectories(dir.resolve("quicraft"));
        Assumptions.assumeTrue(quicraft.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(quicraft, PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(quicraft), "the process ignores the directory mode (root)");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(quicraft.resolve("rebind-v1.json"),
                quicraft.resolve("rebind-bloom-v1.bin"), now::get);
        assertTrue(state.isUsable());
        assertFalse(state.shouldNotify("mc.example.com", 25565, FP_A), "no notice without a durable mark");
        assertFalse(state.isUsable());
        assertFalse(state.allowsAttempt("mc.example.com", 25565, now.get()));
    }

    @Test
    void oneOfTheTwoStateFilesMissingFailsClosed(@TempDir Path dir) throws IOException {
        AtomicLong now = new AtomicLong(BASE);

        Path jsonDirectory = dir.resolve("json-only");
        Files.createDirectories(jsonDirectory);
        Path jsonOnly = jsonDirectory.resolve("rebind-v1.json");
        byte[] json = "{\"version\":1,\"servers\":[]}".getBytes(StandardCharsets.UTF_8);
        Files.write(jsonOnly, json);
        RebindState withoutFilter = RebindState.load(jsonOnly,
                jsonDirectory.resolve("rebind-bloom-v1.bin"), now::get);
        assertFalse(withoutFilter.isUsable(), "the filter half is missing");
        assertFalse(withoutFilter.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(withoutFilter.recordAttemptFailure("mc.example.com", 25565));
        assertFalse(withoutFilter.shouldNotify("mc.example.com", 25565, FP_A));
        assertArrayEquals(json, Files.readAllBytes(jsonOnly), "the remaining half was not touched");
        assertFalse(Files.exists(jsonDirectory.resolve("rebind-bloom-v1.bin")),
                "the missing half was not created");

        Path bloomDirectory = dir.resolve("bloom-only");
        Files.createDirectories(bloomDirectory);
        Path bloomOnly = bloomDirectory.resolve("rebind-bloom-v1.bin");
        writeEmptyBloom(bloomOnly);
        RebindState withoutJson = RebindState.load(bloomDirectory.resolve("rebind-v1.json"), bloomOnly, now::get);
        assertFalse(withoutJson.isUsable(), "the detailed half is missing");
        assertFalse(withoutJson.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(withoutJson.recordAttemptFailure("mc.example.com", 25565));
        assertFalse(withoutJson.shouldNotify("mc.example.com", 25565, FP_A));
        assertEquals(RebindBloom.BYTES, Files.size(bloomOnly), "the remaining half was not touched");
        assertFalse(Files.exists(bloomDirectory.resolve("rebind-v1.json")),
                "the missing half was not created");
    }

    @Test
    void smallLimitsEvictTheLeastRecentlyUsedHistoryAndTheBloomStillSuppresses(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("rebind-v1.json");
        Path bloomFile = dir.resolve("rebind-bloom-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, bloomFile, now::get, 2, 4, 4);
        assertTrue(state.isUsable());
        for (int i = 0; i < 4; i++) {
            assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(i)), "notice " + i);
        }
        assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(4)), "the per-server cap evicts fp0");
        assertFalse(state.shouldNotify("a.example.com", 25565, fingerprint(0)), "the filter still knows fp0");

        assertTrue(state.shouldNotify("b.example.com", 25565, fingerprint(10)), "the total cap evicts a's oldest");
        assertFalse(state.shouldNotify("a.example.com", 25565, fingerprint(1)), "the filter still knows fp1");
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
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
    void unreadableExistingStateFailsClosed(@TempDir Path dir) throws IOException {
        AtomicLong now = new AtomicLong(BASE);
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        byte[] json = "{\"version\":1,\"servers\":[]}".getBytes(StandardCharsets.UTF_8);

        Path unreadableDirectory = Files.createDirectories(dir.resolve("unreadable"));
        Path unreadableJson = unreadableDirectory.resolve("rebind-v1.json");
        Path bloomFile = unreadableDirectory.resolve("rebind-bloom-v1.bin");
        Files.write(unreadableJson, json);
        writeEmptyBloom(bloomFile);
        Files.setPosixFilePermissions(unreadableJson, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(unreadableJson), "the process ignores the file mode (root)");
            RebindState unreadable = RebindState.load(unreadableJson, bloomFile, now::get);
            assertFalse(unreadable.isUsable(), "a file the process may not read fails closed");
            assertFalse(unreadable.allowsAttempt("mc.example.com", 25565, now.get()));
            assertFalse(unreadable.shouldNotify("mc.example.com", 25565, FP_A));
        } finally {
            Files.setPosixFilePermissions(unreadableJson, PosixFilePermissions.fromString("rw-------"));
        }
        assertArrayEquals(json, Files.readAllBytes(unreadableJson), "the unreadable file was left alone");
        assertEquals(RebindBloom.BYTES, Files.size(bloomFile), "and so was the filter");

        Path asDirectoryDirectory = Files.createDirectories(dir.resolve("directory-state"));
        Path asDirectory = Files.createDirectories(asDirectoryDirectory.resolve("rebind-v1.json"));
        RebindState directoryState = RebindState.load(asDirectory,
                asDirectoryDirectory.resolve("rebind-bloom-v1.bin"), now::get);
        assertFalse(directoryState.isUsable(), "a directory is unreadable state, not a missing file");
        assertFalse(directoryState.allowsAttempt("mc.example.com", 25565, now.get()));
        assertFalse(directoryState.shouldNotify("mc.example.com", 25565, FP_A));
        assertTrue(Files.isDirectory(asDirectory), "the directory was not touched");
    }

    /** The filter half of a state fixture: a real client always has both files. */
    private static void writeEmptyBloom(Path bloomFile) throws IOException {
        Files.write(bloomFile, new byte[RebindBloom.BYTES]);
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

    private static String fingerprint(int index) {
        return "sha256:" + String.format(Locale.ROOT, "%064x", index);
    }
}
