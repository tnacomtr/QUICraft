// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupRecoveryTest {

    private static final String TXID = "0123456789abcdef0123456789abcdef";
    private static final String TXID_OLD = "fedcba9876543210fedcba9876543210";
    private static final String TXID_NEW = "11112222333344445555666677778888";
    private static final String OLD = "aa".repeat(64);
    private static final String NEW = "bb".repeat(64);
    private static final String OTHER = "cc".repeat(64);
    private static final Instant CREATED = Instant.parse("2026-10-13T00:00:00Z");
    private static final UpdateVersion V1 = parse("1.0.0");
    private static final UpdateVersion V2 = parse("2.0.0");
    private static final UpdateVersion V3 = parse("3.0.0");
    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir
    Path tmp;

    private Path updateDir;
    private Path liveJar;
    private FaultOps ops;
    private FakeDiscovery discovery;

    private static UpdateVersion parse(String text) {
        try {
            return UpdateVersion.parse(text);
        } catch (UpdateRejectedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records nothing; can fail a force, or a directory sync after the operation itself succeeded. */
    static final class FaultOps implements DurabilityOps {
        boolean failForce;
        boolean failSync;
        Error seamError;
        volatile String syncThread;

        @Override
        public void force(FileChannel ch) throws IOException {
            if (failForce) {
                throw new IOException("injected force failure");
            }
            DurabilityOps.system().force(ch);
        }

        @Override
        public void atomicMove(Path from, Path to) throws IOException {
            DurabilityOps.system().atomicMove(from, to);
        }

        @Override
        public boolean syncDirectory(Path dir) throws IOException {
            syncThread = Thread.currentThread().getName();
            if (seamError != null) {
                throw seamError;
            }
            if (failSync) {
                throw new IOException("injected sync failure");
            }
            return DurabilityOps.system().syncDirectory(dir);
        }
    }

    static final class FakeDiscovery implements LiveJarDiscovery {
        LiveJar result;
        IOException ioFailure;
        UpdateRejectedException rejected;
        RuntimeException runtime;

        @Override
        public LiveJar discover() throws IOException, UpdateRejectedException {
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (rejected != null) {
                throw rejected;
            }
            if (runtime != null) {
                throw runtime;
            }
            return result;
        }
    }

    static final class FakeSignal implements HealthSignal {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();

        @Override
        public CompletionStage<Boolean> awaitHealthy(Duration timeout) {
            return future;
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        updateDir = tmp.resolve("upd");
        liveJar = tmp.resolve("server.jar").toAbsolutePath();
        Files.write(liveJar, new byte[] {1, 2, 3});
        new UpdateDirectory(updateDir, DurabilityOps.system());
        ops = new FaultOps();
        discovery = new FakeDiscovery();
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, OLD, V1);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private RuntimeIdentity identity(String platform, UpdateVersion running) {
        return new RuntimeIdentity(platform, "target", running,
                new UpdateDocuments.Trust(1, Instant.EPOCH, 0, 0, List.of()), OLD);
    }

    private RecoveryContext ctx(UpdateVersion running) {
        return new RecoveryContext(updateDir, ops, discovery, identity("velocity", running));
    }

    private static JournalRecord journal(String txid, JournalPhase phase, String livePath, String liveSha,
            UpdateVersion stagedVersion, String stagedSha, int bootAttempts) {
        return new JournalRecord(txid, phase, "velocity", 7,
                new JournalLive(livePath, 3, liveSha, V1),
                new JournalStaged(5, stagedSha, stagedVersion),
                liveSha, CREATED, bootAttempts);
    }

    private JournalRecord journal(JournalPhase phase, UpdateVersion stagedVersion, String stagedSha,
            int bootAttempts) {
        return journal(TXID, phase, liveJar.toString(), OLD, stagedVersion, stagedSha, bootAttempts);
    }

    private void writeJournal(JournalRecord record) throws Exception {
        new UpdateDirectory(updateDir, DurabilityOps.system()).writeJournal(record);
    }

    /** Runs the body while this test holds the transaction lock, releasing it afterwards. */
    private void holdingTxnLock(LockBody body) throws Exception {
        TransactionLock.TxnLease lease = TransactionLock.tryAcquire(updateDir);
        try {
            body.run();
        } finally {
            lease.close();
        }
    }

    /** Fails unless {@code txn.lock} is free again, both in-process and at the OS level. */
    private void assertTxnLockFree() throws Exception {
        TransactionLock.tryAcquire(updateDir).close();
    }

    @FunctionalInterface
    private interface LockBody {
        void run() throws Exception;
    }

    private Path put(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        if (POSIX) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        }
        return p;
    }

    private Path staging(String txid, String ext) {
        return updateDir.resolve("staging").resolve(txid + ext);
    }

    private Path backup(String txid) {
        return updateDir.resolve("backup").resolve(txid + ".jar");
    }

    private static String sha512Of(String text) throws Exception {
        return Hex.encode(MessageDigest.getInstance("SHA-512").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private Path journalPath() {
        return updateDir.resolve("journal.json");
    }

    /** A staging jar and a backup for TXID, plus the journal in the given state. */
    private void setUpTransaction(JournalRecord record) throws Exception {
        put(staging(TXID, ".jar"), "staged");
        put(backup(TXID), "old");
        writeJournal(record);
    }

    private byte[] journalBytes() throws IOException {
        return Files.readAllBytes(journalPath());
    }

    private JournalRecord readJournal() throws Exception {
        return new UpdateDirectory(updateDir, DurabilityOps.system()).readJournal().orElseThrow();
    }

    private void assertAllKept(String txid) {
        assertTrue(Files.exists(staging(txid, ".jar")));
        assertTrue(Files.exists(backup(txid)));
        assertTrue(Files.exists(journalPath()));
    }

    private static void assertMessageContains(RecoveryResult result, String part) {
        assertTrue(result.messages().stream().anyMatch(m -> m.contains(part)),
                () -> "messages " + result.messages() + " do not contain " + part);
    }

    // ---- step 1 and preconditions ------------------------------------------------------------

    @Test
    void discoveryIoFailureDisablesAndTouchesNothing() throws Exception {
        setUpTransaction(journal(JournalPhase.SWAPPED, V2, NEW, 0));
        byte[] before = journalBytes();
        discovery.result = null;
        discovery.ioFailure = new IOException("boom");

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.DISABLED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
    }

    @Test
    void discoveryRejectionDisablesAndTouchesNothing() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));
        discovery.result = null;
        discovery.rejected = new UpdateRejectedException("nope");

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.DISABLED, result.status());
        assertAllKept(TXID);
    }

    @Test
    void discoveryRuntimeFailureDisablesWithoutThrowing() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));
        discovery.result = null;
        discovery.runtime = new IllegalStateException("bug");

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.DISABLED, result.status());
        assertAllKept(TXID);
    }

    @Test
    void ownedContentsFailureDisablesAndTouchesNothing() throws Exception {
        assumeTrue(POSIX, "symbolic links need POSIX");
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));
        Files.createSymbolicLink(updateDir.resolve("link"), tmp.resolve("server.jar"));

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.DISABLED, result.status());
        assertTrue(Files.isSymbolicLink(updateDir.resolve("link")));
        assertAllKept(TXID);
    }

    @Test
    void lockHeldInThisJvmDisables() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));
        holdingTxnLock(() -> {
            RecoveryResult result = StartupRecovery.run(ctx(V1));
            assertEquals(RecoveryStatus.DISABLED, result.status());
        });
        assertAllKept(TXID);
    }

    @Test
    void lockHeldByAnotherChannelDisablesWithBusyMessage() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));
        FileChannel other = StateStore.openLock(updateDir, StateStore.TXN_LOCK);
        try {
            FileLock held = other.lock();
            try {
                RecoveryResult result = StartupRecovery.run(ctx(V1));
                assertEquals(RecoveryStatus.DISABLED, result.status());
                assertMessageContains(result, "another server uses this update directory");
            } finally {
                held.release();
            }
        } finally {
            other.close();
        }
        assertAllKept(TXID);
    }

    @Test
    void staleJournalTmpIsRemoved() throws Exception {
        put(updateDir.resolve("journal.json.tmp"), "partial");

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.IDLE, result.status());
        assertFalse(Files.exists(updateDir.resolve("journal.json.tmp")));
    }

    // ---- step 2: no journal ------------------------------------------------------------------

    @Test
    void noJournalDeletesOrphanStagingKeepsUnexpectedAndAllBackups() throws Exception {
        put(staging(TXID, ".part"), "half");
        put(staging(TXID, ".jar"), "whole");
        put(updateDir.resolve("staging").resolve("junk.txt"), "unexpected");
        put(backup(TXID_OLD), "older");

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.IDLE, result.status());
        assertFalse(Files.exists(staging(TXID, ".part")));
        assertFalse(Files.exists(staging(TXID, ".jar")));
        assertTrue(Files.exists(updateDir.resolve("staging").resolve("junk.txt")));
        assertTrue(Files.exists(backup(TXID_OLD)));
        assertMessageContains(result, "junk.txt");
    }

    @Test
    void noJournalWithNothingToDoIsIdle() {
        RecoveryResult result = StartupRecovery.run(ctx(V1));
        assertEquals(RecoveryStatus.IDLE, result.status());
        assertTrue(result.ticket().isEmpty());
        assertTrue(result.live().isPresent());
    }

    // ---- step 3: unreadable or mismatched journal --------------------------------------------

    @Test
    void corruptJournalKeepsEverything() throws Exception {
        put(staging(TXID, ".jar"), "staged");
        put(backup(TXID), "old");
        put(journalPath(), "{ not a journal");
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
    }

    @Test
    void journalForAnotherPlatformKeepsEverything() throws Exception {
        JournalRecord base = journal(TXID, JournalPhase.PREPARED, liveJar.toString(), OLD, V2, NEW, 0);
        setUpTransaction(new JournalRecord(TXID, JournalPhase.PREPARED, "fabric-26x", 7, base.live(),
                base.staged(), OLD, CREATED, 0));
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
    }

    @Test
    void journalForAnotherLivePathKeepsEverything() throws Exception {
        setUpTransaction(journal(TXID, JournalPhase.PREPARED,
                tmp.resolve("other.jar").toAbsolutePath().toString(), OLD, V2, NEW, 0));
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
    }

    // ---- step 4: manual restore ---------------------------------------------------------------

    @Test
    void manualRestoreDiscardsStagedAndJournalKeepsBackup() throws Exception {
        setUpTransaction(journal(JournalPhase.SWAPPED, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, OLD, V1);

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.IDLE, result.status());
        assertFalse(Files.exists(staging(TXID, ".jar")));
        assertFalse(Files.exists(journalPath()));
        assertTrue(Files.exists(backup(TXID)));
        assertMessageContains(result, "restored by hand");
    }

    // ---- manual-restore message: offered only when the backup matches the journal ----

    @Test
    void recoveryRequiredOffersRestoreWhenBackupMatchesJournal() throws Exception {
        String oldReal = sha512Of("old");
        put(staging(TXID, ".jar"), "staged");
        put(backup(TXID), "old");
        writeJournal(journal(TXID, JournalPhase.RECOVERY_REQUIRED, liveJar.toString(), oldReal, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertMessageContains(result, "copy " + backup(TXID));
        assertMessageContains(result, "Expected backup SHA-512: " + oldReal);
        assertFalse(result.messages().stream().anyMatch(m -> m.contains(StartupRecovery.MSG_BACKUP_MISMATCH)));
    }

    @Test
    void recoveryRequiredRefusesRestoreWhenBackupDiffersFromJournal() throws Exception {
        String oldReal = sha512Of("old");
        put(staging(TXID, ".jar"), "staged");
        put(backup(TXID), "xyz"); // same size as the journaled live jar, different bytes
        writeJournal(journal(TXID, JournalPhase.RECOVERY_REQUIRED, liveJar.toString(), oldReal, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertMessageContains(result, StartupRecovery.MSG_BACKUP_MISMATCH);
        assertMessageContains(result, "Expected backup SHA-512: " + oldReal);
        assertFalse(result.messages().stream().anyMatch(m -> m.contains("copy ")));
    }

    @Test
    void recoveryRequiredRefusesRestoreWhenBackupIsMissing() throws Exception {
        String oldReal = sha512Of("old");
        put(staging(TXID, ".jar"), "staged");
        writeJournal(journal(TXID, JournalPhase.RECOVERY_REQUIRED, liveJar.toString(), oldReal, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertTrue(Files.exists(staging(TXID, ".jar")));
        assertTrue(Files.exists(journalPath()));
        assertFalse(Files.exists(backup(TXID)));
        assertMessageContains(result, StartupRecovery.MSG_BACKUP_MISMATCH);
        assertFalse(result.messages().stream().anyMatch(m -> m.contains("copy ")));
    }

    // ---- unexpected staging names: printable and capped ----

    @Test
    void unexpectedStagingNameWithControlCharactersIsSanitized() throws Exception {
        try {
            put(updateDir.resolve("staging").resolve("evil\nline\rend\u0001.txt"), "x");
        } catch (IOException | InvalidPathException e) {
            assumeTrue(false, "filesystem refuses control characters in file names");
        }

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertTrue(result.messages().contains(StartupRecovery.MSG_UNEXPECTED_STAGING + "evil?line?end?.txt"),
                () -> "messages " + result.messages());
        assertTrue(result.messages().stream().noneMatch(m -> m.contains("\n") || m.contains("\r")));
    }

    @Test
    void unexpectedStagingNameIsCappedAtSixtyFourCharacters() throws Exception {
        put(updateDir.resolve("staging").resolve("x".repeat(100) + ".bin"), "x");

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertTrue(result.messages().contains(StartupRecovery.MSG_UNEXPECTED_STAGING + "x".repeat(64)),
                () -> "messages " + result.messages());
    }

    @Test
    void printableNameReplacesEveryNonPrintableCharacter() {
        assertEquals("a?b?c?d", StartupRecovery.printableName("a\nb\rc\u007fd"));
        assertEquals("~ !", StartupRecovery.printableName("~ !"));
        assertEquals(StartupRecovery.MAX_LOGGED_NAME_CHARS, StartupRecovery.printableName("q".repeat(300)).length());
    }

    // ---- step 5: prepared ---------------------------------------------------------------------

    @Test
    void preparedWithOldLiveAndNewStagedIsRestoredAndCleaned() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.IDLE, result.status());
        assertFalse(Files.exists(staging(TXID, ".jar")));
        assertFalse(Files.exists(journalPath()));
        assertTrue(Files.exists(backup(TXID)));
        assertMessageContains(result, "not installed");
    }

    @Test
    void preparedWithOldLiveAndStagedEqualToOldCleansUp() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, OLD, 0));

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.IDLE, result.status());
        assertFalse(Files.exists(staging(TXID, ".jar")));
        assertFalse(Files.exists(journalPath()));
        assertTrue(Files.exists(backup(TXID)));
    }

    @Test
    void preparedWithStagedLiveContinuesToBootPending() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.HEALTH_PENDING, result.status());
        assertEquals(TXID, result.ticket().orElseThrow().txid());
        JournalRecord written = readJournal();
        assertEquals(JournalPhase.BOOT_PENDING, written.phase());
        assertEquals(1, written.bootAttempts());
    }

    @Test
    void preparedWithUnknownLiveIsRecoveryRequired() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, OTHER, V1);
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
    }

    // ---- step 6: swapped ----------------------------------------------------------------------

    @Test
    void swappedWithMatchingVersionAndHashStartsBootPendingAttemptOne() throws Exception {
        setUpTransaction(journal(JournalPhase.SWAPPED, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.HEALTH_PENDING, result.status());
        JournalRecord written = readJournal();
        assertEquals(JournalPhase.BOOT_PENDING, written.phase());
        assertEquals(1, written.bootAttempts());
        assertEquals(TXID, result.ticket().orElseThrow().txid());
    }

    @Test
    void swappedWithMismatchingVersionIsRecoveryRequiredAndJournalUntouched() throws Exception {
        setUpTransaction(journal(JournalPhase.SWAPPED, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
    }

    @Test
    void swappedWithMismatchingHashIsRecoveryRequiredAndJournalUntouched() throws Exception {
        setUpTransaction(journal(JournalPhase.SWAPPED, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, OTHER, V2);
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
    }

    // ---- step 7: boot-pending -----------------------------------------------------------------

    @Test
    void bootPendingAttemptOneBecomesAttemptTwo() throws Exception {
        setUpTransaction(journal(JournalPhase.BOOT_PENDING, V2, NEW, 1));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.HEALTH_PENDING, result.status());
        assertEquals(2, readJournal().bootAttempts());
        assertEquals(JournalPhase.BOOT_PENDING, readJournal().phase());
    }

    @Test
    void bootPendingAttemptTwoIsRecoveryRequiredAndJournalUntouched() throws Exception {
        setUpTransaction(journal(JournalPhase.BOOT_PENDING, V2, NEW, 2));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
    }

    @Test
    void bootPendingWithMismatchedRunningVersionIsRecoveryRequired() throws Exception {
        setUpTransaction(journal(JournalPhase.BOOT_PENDING, V2, NEW, 1));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V3));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertArrayEquals(before, journalBytes());
    }

    // ---- recovery-required and write failures -------------------------------------------------

    @Test
    void journalAlreadyRecoveryRequiredKeepsEverythingWithManualSteps() throws Exception {
        // Live is the new jar, so the manual-restore check (step 4) does not apply. The backup holds
        // "old", so the journal records its real SHA-512; the restore is only offered when it matches.
        String oldReal = sha512Of("old");
        put(staging(TXID, ".jar"), "staged");
        put(backup(TXID), "old");
        writeJournal(journal(TXID, JournalPhase.RECOVERY_REQUIRED, liveJar.toString(), oldReal, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V1));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertAllKept(TXID);
        assertArrayEquals(before, journalBytes());
        assertMessageContains(result, backup(TXID).toString());
        assertMessageContains(result, "copy");
    }

    @Test
    void journalWriteFailureIsRecoveryRequiredAndKeepsSwappedJournal() throws Exception {
        setUpTransaction(journal(JournalPhase.SWAPPED, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);
        ops.failForce = true;
        byte[] before = journalBytes();

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertMessageContains(result, "recovery could not confirm durable state");
        assertArrayEquals(before, journalBytes());
        assertAllKept(TXID);
    }

    @Test
    void syncFailureAfterJournalRenameNeverReportsIdle() throws Exception {
        setUpTransaction(journal(JournalPhase.SWAPPED, V2, NEW, 0));
        discovery.result = new LiveJar(liveJar, tmp.toAbsolutePath(), 3, NEW, V2);
        ops.failSync = true;

        RecoveryResult result = StartupRecovery.run(ctx(V2));

        assertEquals(RecoveryStatus.RECOVERY_REQUIRED, result.status());
        assertMessageContains(result, "recovery could not confirm durable state");
        // The rename may have happened; the journal on disk is either the old or the new one.
        JournalPhase onDisk = readJournal().phase();
        assertTrue(onDisk == JournalPhase.SWAPPED || onDisk == JournalPhase.BOOT_PENDING);
        assertAllKept(TXID);
    }

    @Test
    void runNeverThrowsOnUnexpectedFailures() throws Exception {
        setUpTransaction(journal(JournalPhase.PREPARED, V2, NEW, 0));
        discovery.result = null;
        discovery.runtime = new NullPointerException("boom");

        assertEquals(RecoveryStatus.DISABLED, StartupRecovery.run(ctx(V1)).status());
        assertEquals(RecoveryStatus.DISABLED, StartupRecovery.run(null).status());
    }

    @Test
    void runReportsDisabledForUnreadableDirectory() throws Exception {
        Path notADir = tmp.resolve("plain-file");
        Files.write(notADir, new byte[] {1});
        RecoveryContext bad = new RecoveryContext(notADir, ops, discovery, identity("velocity", V1));

        RecoveryResult result = StartupRecovery.run(bad);

        assertEquals(RecoveryStatus.DISABLED, result.status());
        assertTrue(Files.isRegularFile(notADir, LinkOption.NOFOLLOW_LINKS));
    }

    // ---- completeHealth -----------------------------------------------------------------------

    /** Runs the recording step on the completing thread. */
    private static final Executor DIRECT = Runnable::run;

    private CompletionStage<HealthCompletion> completeWith(RecoveryTicket ticket, FakeSignal signal,
            Duration timeout) {
        return StartupRecovery.completeHealth(ctx(V2), ticket, signal, timeout, DIRECT);
    }

    private void setUpBootPending(String txid, String backupTxid) throws Exception {
        put(backup(backupTxid), "older");
        put(backup(txid), "previous");
        put(staging(txid, ".jar"), "moved-already-but-leftover");
        writeJournal(journal(txid, JournalPhase.BOOT_PENDING, liveJar.toString(), OLD, V2, NEW, 1));
    }

    @Test
    void healthyDeletesJournalAndOnlyEarlierBackups() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();

        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        signal.future.complete(true);

        assertEquals(HealthCompletion.HEALTHY, stage.toCompletableFuture().get());
        assertFalse(Files.exists(journalPath()));
        assertFalse(Files.exists(backup(TXID_OLD)));
        assertTrue(Files.exists(backup(TXID)));
        assertFalse(Files.exists(staging(TXID, ".jar")));
    }

    @Test
    void unhealthyKeepsJournalAndBackups() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();

        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        signal.future.complete(false);

        assertEquals(HealthCompletion.FAILED, stage.toCompletableFuture().get());
        assertTrue(Files.exists(journalPath()));
        assertTrue(Files.exists(backup(TXID)));
        assertTrue(Files.exists(backup(TXID_OLD)));
    }

    @Test
    void timeoutKeepsJournalAndBackups() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();

        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofMillis(50));

        assertEquals(HealthCompletion.TIMED_OUT, stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(Files.exists(journalPath()));
        assertTrue(Files.exists(backup(TXID)));
        assertTrue(Files.exists(backup(TXID_OLD)));
    }

    @Test
    void signalTimeoutExceptionCountsAsTimedOut() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();

        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        signal.future.completeExceptionally(new java.util.concurrent.TimeoutException());

        assertEquals(HealthCompletion.TIMED_OUT, stage.toCompletableFuture().get());
        assertTrue(Files.exists(journalPath()));
    }

    @Test
    void journalReplacedByAnotherTxidIsJournalChangedAndUntouched() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        writeJournal(journal(TXID_NEW, JournalPhase.BOOT_PENDING, liveJar.toString(), OLD, V2, NEW, 1));
        byte[] before = journalBytes();
        FakeSignal signal = new FakeSignal();

        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        signal.future.complete(true);

        assertEquals(HealthCompletion.JOURNAL_CHANGED, stage.toCompletableFuture().get());
        assertArrayEquals(before, journalBytes());
        assertTrue(Files.exists(backup(TXID_OLD)));
    }

    @Test
    void journalNotBootPendingIsJournalChanged() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        writeJournal(journal(TXID, JournalPhase.SWAPPED, liveJar.toString(), OLD, V2, NEW, 0));
        FakeSignal signal = new FakeSignal();

        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        signal.future.complete(true);

        assertEquals(HealthCompletion.JOURNAL_CHANGED, stage.toCompletableFuture().get());
        assertTrue(Files.exists(journalPath()));
        assertTrue(Files.exists(backup(TXID_OLD)));
    }

    @Test
    void missingJournalIsJournalChanged() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        Files.delete(journalPath());
        FakeSignal signal = new FakeSignal();

        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        signal.future.complete(true);

        assertEquals(HealthCompletion.JOURNAL_CHANGED, stage.toCompletableFuture().get());
        assertTrue(Files.exists(backup(TXID_OLD)));
    }

    @Test
    void noLockIsHeldWhileTheHealthVerdictIsPending() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();

        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));

        // The test thread must be able to take the transaction lock while the verdict is pending.
        holdingTxnLock(() -> assertFalse(stage.toCompletableFuture().isDone()));
        signal.future.complete(true);
        assertEquals(HealthCompletion.HEALTHY, stage.toCompletableFuture().get());
    }

    @Test
    void unexpectedFailureCompletesWithFixedRejection() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();
        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        ops.failSync = true;
        signal.future.complete(true);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get());
        assertTrue(failure.getCause() instanceof UpdateRejectedException);
        assertEquals(StartupRecovery.MSG_HEALTH_UNCONFIRMED, failure.getCause().getMessage());
        assertTxnLockFree();
    }

    @Test
    void signalFailureCountsAsFailedAndKeepsFiles() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();
        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        signal.future.completeExceptionally(new IllegalStateException("health probe broke"));

        assertEquals(HealthCompletion.FAILED, stage.toCompletableFuture().get());
        assertTrue(Files.exists(journalPath()));
        assertTxnLockFree();
    }

    @Test
    void nonPositiveTimeoutIsRejectedSynchronously() {
        assertThrows(IllegalArgumentException.class,
                () -> completeWith(new RecoveryTicket(TXID), new FakeSignal(), Duration.ZERO));
    }

    @Test
    void completionResultsAreUsableAsCompletionException() throws Exception {
        // The stage must be readable with join() as well, with the fixed rejection as the cause.
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();
        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        ops.failSync = true;
        signal.future.complete(true);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof UpdateRejectedException);
    }

    @Test
    void recoveryTicketRejectsMalformedTxid() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryTicket("ABC"));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryTicket(TXID.toUpperCase()));
        assertEquals(TXID, new RecoveryTicket(TXID).txid());
    }

    private static HealthCompletion awaitHealth(CompletionStage<HealthCompletion> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void busyLockThenFreeSucceedsOnRetry() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();
        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));

        // The first attempt sees the lock busy and schedules a retry; the lock is free again before it runs.
        holdingTxnLock(() -> signal.future.complete(true));

        assertEquals(HealthCompletion.HEALTHY, awaitHealth(stage));
        assertFalse(Files.exists(journalPath()));
        assertFalse(Files.exists(backup(TXID_OLD)));
    }

    @Test
    void busyLockForEveryAttemptCompletesExceptionallyAndKeepsBootPending() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        FakeSignal signal = new FakeSignal();
        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));
        byte[] before = journalBytes();

        holdingTxnLock(() -> {
            signal.future.complete(true);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof UpdateRejectedException, "cause " + failure.getCause());
        });

        assertArrayEquals(before, journalBytes());
        assertEquals(JournalPhase.BOOT_PENDING, readJournal().phase());
        assertTrue(Files.exists(backup(TXID_OLD)));
    }

    @Test
    void executorRejectingTheRetryStillCompletesTheStage() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor firstOnly = task -> {
            if (calls.getAndIncrement() > 0) {
                throw new java.util.concurrent.RejectedExecutionException("shut down");
            }
            task.run();
        };
        FakeSignal signal = new FakeSignal();
        CompletionStage<HealthCompletion> stage = StartupRecovery.completeHealth(ctx(V2),
                new RecoveryTicket(TXID), signal, Duration.ofSeconds(5), firstOnly);

        holdingTxnLock(() -> {
            signal.future.complete(true);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> stage.toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof UpdateRejectedException, "cause " + failure.getCause());
        });
        assertEquals(JournalPhase.BOOT_PENDING, readJournal().phase());
    }

    @Test
    void recordingRunsOnTheSuppliedExecutor() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "health-worker");
            t.setDaemon(true);
            return t;
        });
        try {
            FakeSignal signal = new FakeSignal();
            CompletionStage<HealthCompletion> stage = StartupRecovery.completeHealth(ctx(V2),
                    new RecoveryTicket(TXID), signal, Duration.ofSeconds(5), worker);
            signal.future.complete(true);
            assertEquals(HealthCompletion.HEALTHY, awaitHealth(stage));
            assertEquals("health-worker", ops.syncThread);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void nonVirtualMachineErrorInRecordingStillCompletesTheStage() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        ops.seamError = new AssertionError("injected seam failure");
        FakeSignal signal = new FakeSignal();
        CompletionStage<HealthCompletion> stage = completeWith(new RecoveryTicket(TXID), signal,
                Duration.ofSeconds(5));

        signal.future.complete(true);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof UpdateRejectedException);
        assertEquals(StartupRecovery.MSG_HEALTH_UNCONFIRMED, failure.getCause().getMessage());
    }

    @Test
    void virtualMachineErrorInRecordingCompletesTheStageBeforeRethrowing() throws Exception {
        setUpBootPending(TXID, TXID_OLD);
        ops.seamError = new OutOfMemoryError("injected seam failure");
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "health-worker-vm");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, e) -> {
                // The rethrown error is expected here; the stage is what the test checks.
            });
            return t;
        });
        try {
            FakeSignal signal = new FakeSignal();
            CompletionStage<HealthCompletion> stage = StartupRecovery.completeHealth(ctx(V2),
                    new RecoveryTicket(TXID), signal, Duration.ofSeconds(5), worker);

            signal.future.complete(true);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof UpdateRejectedException);
            assertTxnLockFree();
        } finally {
            worker.shutdownNow();
        }
    }
}
