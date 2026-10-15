// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** A backoff window that never ends, so an entry with it is not admissible. */
    private static final String FUTURE = "9999-01-01T00:00:00Z";
    /** A backoff window that ended long before any test clock, so an entry with it is admissible. */
    private static final String PAST = "2000-01-01T00:00:00Z";
    /** The state file's header before the JSON: magic (0-3), format version (4-7), JSON length (8-11). */
    private static final int HEADER = 12;
    /** The SHA-256 after the bitmap. */
    private static final int DIGEST = 32;

    @Test
    void backoffStartsAtOneMinuteAndDoublesToAnHour() {
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(null, now::get);
        assertTrue(state.isUsable());
        long[] minutes = {1, 2, 4, 8, 16, 32, 60, 60};
        for (long expected : minutes) {
            RebindState.Attempt attempt = state.tryBeginAttempt("mc.example.com", 25565, now.get());
            assertNotNull(attempt, "an attempt once the previous window passed");
            assertTrue(state.endAttempt(attempt, false),
                    "a failure, which starts the " + expected + " min window");
            now.addAndGet(expected * MINUTE - 1);
            assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()),
                    "still blocked just before " + expected + " min");
            now.addAndGet(1);
        }
        assertNotNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "and open again");
    }

    @Test
    void anAttemptThatNeverEndedStillHoldsItsProvisionalWindow(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertNotNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "the attempt starts");

        // As if the game exited or crashed before the attempt ended: the provisional window outlives it.
        RebindState reloaded = RebindState.load(file, now::get);
        assertNull(reloaded.tryBeginAttempt("mc.example.com", 25565, now.get()), "the window is on disk");
        now.addAndGet(MINUTE - 1);
        assertNull(reloaded.tryBeginAttempt("mc.example.com", 25565, now.get()), "and holds");
        now.addAndGet(1);
        assertNotNull(reloaded.tryBeginAttempt("mc.example.com", 25565, now.get()), "until it passes");
    }

    @Test
    void aStaleAttemptIsTreatedAsAFailedOne(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        RebindState.Attempt attempt = state.tryBeginAttempt("mc.example.com", 25565, now.get());
        assertNotNull(attempt);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "still live");

        now.addAndGet(RebindState.STALE_ATTEMPT_MILLIS);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()),
                "stale: it counts as a failure, whose window starts now");
        now.addAndGet(MINUTE - 1);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()),
                "inside the window the failure earned");
        now.addAndGet(1);
        assertNotNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "and open again");
        assertFalse(state.endAttempt(attempt, false), "the stale token was already released");
    }

    @Test
    void aStaleAttemptsStateMayBeEvicted(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get, 2, 4, 4);
        assertNotNull(state.tryBeginAttempt("a.example.com", 25565, now.get()));
        assertNotNull(state.tryBeginAttempt("b.example.com", 25565, now.get()));
        assertNull(state.tryBeginAttempt("c.example.com", 25565, now.get()), "both entries have live attempts");

        now.addAndGet(RebindState.STALE_ATTEMPT_MILLIS);
        assertNotNull(state.tryBeginAttempt("c.example.com", 25565, now.get()),
                "both attempts are stale now, so their state may be evicted");
        assertEquals(2, state.size());
    }

    @Test
    void aStoredWindowFarInTheFutureIsClampedToTheMaximumBackoff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        Files.write(file, stateFile(servers(1, FUTURE)));
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertNull(state.tryBeginAttempt("s0.example.com", 25565, now.get()), "inside its window");
        now.addAndGet(TimeUnit.HOURS.toMillis(1));
        assertNotNull(state.tryBeginAttempt("s0.example.com", 25565, now.get()),
                "a clock that moved forward and back cannot block longer than one hour");
    }

    @Test
    void backoffIsKeyedLikeAPinAndIgnoresTheFingerprint(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        RebindState.Attempt attempt = state.tryBeginAttempt("MC.Example.COM.", 25565, now.get());
        assertNotNull(attempt);
        assertTrue(state.endAttempt(attempt, false), "the failure starts the window");
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "one key, two spellings");
        RebindState.Attempt otherPort = state.tryBeginAttempt("mc.example.com", 25566, now.get());
        assertNotNull(otherPort, "another logical port");
        assertTrue(state.endAttempt(otherPort, false));
        RebindState.Attempt otherHost = state.tryBeginAttempt("other.example.com", 25565, now.get());
        assertNotNull(otherHost, "another logical host");
        assertTrue(state.endAttempt(otherHost, false));
        // A new advertised fingerprint is a new notice, never a reset of the window: no backoff method
        // is given a fingerprint at all, so a rotating fingerprint cannot clear it.
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_A));
        assertTrue(state.shouldNotify("mc.example.com", 25565, FP_B));
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()));
        now.addAndGet(MINUTE - 1);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()));
        now.addAndGet(1);
        assertNotNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()));

        RebindState reloaded = RebindState.load(file, now::get);
        assertNull(reloaded.tryBeginAttempt("mc.example.com", 25565, now.get() - 1), "the window is persisted");
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_A));
        assertFalse(reloaded.shouldNotify("mc.example.com", 25565, FP_B));
    }

    @Test
    void aSuccessClearsTheWindow(@TempDir Path dir) {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        RebindState.Attempt failed = state.tryBeginAttempt("mc.example.com", 25565, now.get());
        assertNotNull(failed);
        assertTrue(state.endAttempt(failed, false), "the failure starts the first window");
        now.addAndGet(MINUTE - 1);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "inside the first window");
        now.addAndGet(1);
        RebindState.Attempt attempt = state.tryBeginAttempt("mc.example.com", 25565, now.get());
        assertNotNull(attempt);
        assertTrue(state.endAttempt(attempt, true), "a successful end clears the server's entry");
        now.addAndGet(1);
        RebindState.Attempt afterSuccess = state.tryBeginAttempt("mc.example.com", 25565, now.get());
        assertNotNull(afterSuccess, "the window is gone");
        assertTrue(state.endAttempt(afterSuccess, false));

        now.addAndGet(MINUTE - 1);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "the schedule started over");
        now.addAndGet(1);
        RebindState.Attempt reopened = state.tryBeginAttempt("mc.example.com", 25565, now.get());
        assertNotNull(reopened);

        RebindState reloaded = RebindState.load(file, now::get);
        assertNull(reloaded.tryBeginAttempt("mc.example.com", 25565, now.get()),
                "the open attempt's provisional window is what is on disk");
        assertTrue(state.endAttempt(reopened, true), "a second success clears it again");
        RebindState afterBoth = RebindState.load(file, now::get);
        assertNotNull(afterBoth.tryBeginAttempt("mc.example.com", 25565, now.get()),
                "and the reloaded state has no window");
    }

    @Test
    void recordSuccessKeepsAnActiveAttemptAndOnlyClearsItsWindow(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        RebindState.Attempt first = state.tryBeginAttempt("mc.example.com", 25565, now.get());
        assertNotNull(first);
        state.recordSuccess("mc.example.com", 25565);
        String json = jsonOf(Files.readAllBytes(file));
        assertTrue(json.contains("\"failures\":0"), "the window was cleared: " + json);
        assertTrue(json.contains("\"blockedUntil\":\"1970-01-01T00:00:00Z\""), json);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()),
                "the first attempt is still active, so there is no second token");
        assertTrue(state.endAttempt(first, true), "the token that started the attempt still matches");

        RebindState.Attempt next = state.tryBeginAttempt("mc.example.com", 25565, now.get());
        assertNotNull(next, "the entry went with the successful end");
        assertTrue(state.endAttempt(next, false), "which starts the first window");
        now.addAndGet(MINUTE - 1);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "the schedule started over");
        now.addAndGet(1);
        assertNotNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()));
    }

    @Test
    void evictsTheLeastRecentlyUsedServerThatIsNotActive(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        Files.write(file, stateFile(serversWithOpenFirst(RebindState.MAX_ENTRIES)));
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        assertEquals(RebindState.MAX_ENTRIES, state.size());
        RebindState.Attempt active = state.tryBeginAttempt("s0.example.com", 25565, now.get());
        assertNotNull(active, "s0's window has passed");
        assertNull(state.tryBeginAttempt("s0.example.com", 25565, now.get()), "its attempt is running");

        assertNotNull(state.tryBeginAttempt("new.example.com", 25565, now.get()), "makes room by evicting");
        assertEquals(RebindState.MAX_ENTRIES, state.size());
        assertNull(state.tryBeginAttempt("new.example.com", 25565, now.get()), "its attempt is running");
        assertNotNull(state.tryBeginAttempt("s1.example.com", 25565, now.get()), "s1 was the least recent one");
        assertNull(state.tryBeginAttempt("s0.example.com", 25565, now.get()), "the active attempt is kept");
    }

    @Test
    void failsWhenEveryEntryIsActiveAndNothingCanBeEvicted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        Files.write(file, stateFile(servers(3, PAST)));
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get, 3, 4, 4);
        List<RebindState.Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RebindState.Attempt attempt = state.tryBeginAttempt("s" + i + ".example.com", 25565, now.get());
            assertNotNull(attempt, "server " + i);
            attempts.add(attempt);
        }
        assertEquals(3, state.size());
        byte[] before = Files.readAllBytes(file);
        for (int i = 0; i < 8; i++) {
            assertNull(state.tryBeginAttempt("another.example.com", 25565, now.get()), "nothing may be evicted");
            assertFalse(state.shouldNotify("another.example.com", 25565, FP_A), "no room to remember a notice");
        }
        assertEquals(3, state.size(), "queries at capacity evict nothing");
        assertArrayEquals(before, Files.readAllBytes(file), "queries at capacity write nothing");
        assertNull(state.tryBeginAttempt("s0.example.com", 25565, now.get()), "its attempt is already running");

        assertTrue(state.endAttempt(attempts.get(0), false));
        assertNotNull(state.tryBeginAttempt("another.example.com", 25565, now.get()),
                "the ended attempt may be evicted");
        assertEquals(3, state.size());
        String json = jsonOf(Files.readAllBytes(file));
        assertFalse(json.contains("\"host\":\"s0.example.com\""), "the state of the ended attempt is gone");
        assertTrue(json.contains("\"host\":\"another.example.com\""));
    }

    @Test
    void twoConcurrentAdmissionsForOneServerOnlyOneWins(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (state.tryBeginAttempt("mc.example.com", 25565, now.get()) != null) {
                        granted.incrementAndGet();
                    }
                }
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(1, granted.get(), "exactly one admission wins");
        assertNotNull(state.tryBeginAttempt("other.example.com", 25565, now.get()), "another server is free");
    }

    @Test
    void failingAnAttemptClosesTheWindowBeforeAnotherThreadCanBeAdmitted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get);
        RebindState.Attempt attempt = state.tryBeginAttempt("mc.example.com", 25565, now.get());
        assertNotNull(attempt);

        // The racing thread spins on admission while this thread ends its attempt as a failure. Its
        // calls queue on the monitor behind endAttempt, which sets the window before it releases the
        // lock, so none of them may be admitted; a release-then-window pair would hand out a token.
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stop = new AtomicInteger();
        AtomicInteger tries = new AtomicInteger();
        AtomicInteger granted = new AtomicInteger();
        Thread racing = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (stop.get() == 0) {
                    tries.incrementAndGet();
                    if (state.tryBeginAttempt("mc.example.com", 25565, now.get()) != null) {
                        granted.incrementAndGet();
                    }
                }
            }
        });
        racing.setDaemon(true);
        racing.start();
        start.countDown();
        assertTrue(state.endAttempt(attempt, false), "the first attempt failed and set the window");
        for (int i = 0; i < 1000 && granted.get() == 0; i++) {
            Thread.yield();
        }
        stop.set(1);
        racing.join(TimeUnit.SECONDS.toMillis(10));
        assertEquals(0, granted.get(), "no admission may slip into the window");
        assertTrue(tries.get() > 0, "the racing thread really ran");
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "the window is up");
        now.addAndGet(MINUTE - 1);
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "and holds");
        now.addAndGet(1);
        assertNotNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), "until it passes");
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
        assertNotNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()));
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
        byte[] full = stateFile(json);
        damaged.put("truncated", Arrays.copyOfRange(full, 0, full.length - 1));
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
            assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()), entry.getKey());
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
            assertNull(unreadable.tryBeginAttempt("mc.example.com", 25565, now.get()));
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
        assertNull(directoryState.tryBeginAttempt("mc.example.com", 25565, now.get()));
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
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()),
                "its provisional window cannot be stored, so no attempt");
        assertFalse(state.isUsable(), "which fails closed");
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()));
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
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()));
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
        assertNull(state.tryBeginAttempt("mc.example.com", 25565, now.get()));
        assertFalse(state.shouldNotify("mc.example.com", 25565, FP_A));
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
        RebindState.Attempt first = state.tryBeginAttempt("a.example.com", 25565, now.get());
        assertNotNull(first);
        RebindState.Attempt second = state.tryBeginAttempt("b.example.com", 25565, now.get());
        assertNotNull(second);
        assertNull(state.tryBeginAttempt("c.example.com", 25565, now.get()), "two entries, both active");
        assertEquals(2, state.size());
        assertTrue(state.endAttempt(first, false));
        assertNotNull(state.tryBeginAttempt("c.example.com", 25565, now.get()), "the ended attempt may be evicted");
        assertEquals(2, state.size());
    }

    @Test
    void anActiveAttemptsNoticesAreNeverTrimmed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get, 1, 2, 2);
        assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(0)));
        assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(1)));
        RebindState.Attempt attempt = state.tryBeginAttempt("a.example.com", 25565, now.get());
        assertNotNull(attempt);

        assertFalse(state.shouldNotify("a.example.com", 25565, fingerprint(2)),
                "the history is full and an active attempt's notices may not be trimmed");
        String json = jsonOf(Files.readAllBytes(file));
        assertTrue(json.contains("fingerprint\":\"" + fingerprint(0)), "the oldest notice is still there");
        assertTrue(json.contains("fingerprint\":\"" + fingerprint(1)));
        assertFalse(json.contains("fingerprint\":\"" + fingerprint(2)), "no notice was recorded");
        assertFalse(state.shouldNotify("a.example.com", 25565, fingerprint(2)),
                "and the refused tuple stays unmarked");

        assertTrue(state.endAttempt(attempt, false));
        assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(2)), "once the attempt has ended");
        String trimmed = jsonOf(Files.readAllBytes(file));
        assertFalse(trimmed.contains("fingerprint\":\"" + fingerprint(0)), "the oldest notice was trimmed");
        assertTrue(trimmed.contains("fingerprint\":\"" + fingerprint(2)));
    }

    @Test
    void aNoticeIsRefusedWhenOnlyAnActiveAttemptCouldGiveRoom(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("rebind-v1.bin");
        AtomicLong now = new AtomicLong(BASE);
        RebindState state = RebindState.load(file, now::get, 3, 2, 2);
        assertTrue(state.shouldNotify("a.example.com", 25565, fingerprint(0)));
        assertTrue(state.shouldNotify("b.example.com", 25565, fingerprint(1)));
        RebindState.Attempt a = state.tryBeginAttempt("a.example.com", 25565, now.get());
        assertNotNull(a);
        RebindState.Attempt b = state.tryBeginAttempt("b.example.com", 25565, now.get());
        assertNotNull(b);
        byte[] before = Files.readAllBytes(file);

        assertFalse(state.shouldNotify("c.example.com", 25565, fingerprint(2)),
                "the total cap can only be met by trimming an active attempt");
        assertArrayEquals(before, Files.readAllBytes(file), "a refused notice changes nothing");

        assertTrue(state.endAttempt(a, false));
        assertTrue(state.shouldNotify("c.example.com", 25565, fingerprint(2)),
                "the ended attempt's oldest notice may give room");
        String json = jsonOf(Files.readAllBytes(file));
        assertFalse(json.contains("fingerprint\":\"" + fingerprint(0)), "a's only notice was trimmed");
        assertTrue(json.contains("fingerprint\":\"" + fingerprint(1)), "b's notice stays");
        assertTrue(json.contains("fingerprint\":\"" + fingerprint(2)), "c's notice was recorded");
    }

    /** A re-bind state fixture with {@code count} servers, each inside {@code blockedUntil}. */
    private static String servers(int count, String blockedUntil) {
        StringBuilder sb = new StringBuilder(count * 140 + 64);
        sb.append("{\"version\":1,\"servers\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(server(i, blockedUntil));
        }
        return sb.append("]}").toString();
    }

    /** The same, but the first (least recently used) server is outside any window. */
    private static String serversWithOpenFirst(int count) {
        StringBuilder sb = new StringBuilder(count * 140 + 64);
        sb.append("{\"version\":1,\"servers\":[").append(server(0, PAST));
        for (int i = 1; i < count; i++) {
            sb.append(',').append(server(i, FUTURE));
        }
        return sb.append("]}").toString();
    }

    private static String server(int index, String blockedUntil) {
        return "{\"host\":\"s" + index + ".example.com\",\"port\":25565,\"failures\":1"
                + ",\"blockedUntil\":\"" + blockedUntil + "\",\"notices\":[]}";
    }

    private static byte[] stateFile(String json) {
        return stateFile(json.getBytes(StandardCharsets.UTF_8));
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
