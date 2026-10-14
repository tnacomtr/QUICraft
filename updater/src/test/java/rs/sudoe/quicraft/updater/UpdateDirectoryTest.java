// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateDirectoryTest {

    private static final String TXID = "0123456789abcdef0123456789abcdef";
    private static final String TXID2 = "fedcba9876543210fedcba9876543210";
    private static final String HEX_A = "ab".repeat(64);
    private static final String HEX_C = "cd".repeat(64);

    @TempDir
    Path tmp;

    /** Records the order of durability calls and optionally fails one boundary. */
    static final class FakeOps implements DurabilityOps {
        final List<String> events = new ArrayList<>();
        boolean failMove;
        boolean failSync;
        boolean syncSupported = true;

        @Override
        public void force(FileChannel ch) throws IOException {
            events.add("force");
            DurabilityOps.system().force(ch);
        }

        @Override
        public void atomicMove(Path from, Path to) throws IOException {
            events.add("move");
            if (failMove) {
                throw new IOException("injected move failure");
            }
            DurabilityOps.system().atomicMove(from, to);
        }

        @Override
        public boolean syncDirectory(Path dir) throws IOException {
            events.add("sync");
            if (failSync) {
                throw new IOException("injected sync failure");
            }
            return syncSupported && DurabilityOps.system().syncDirectory(dir);
        }

        @Override
        public boolean directorySyncSupported(Path dir) {
            return syncSupported;
        }
    }

    private static JournalRecord journal(String txid, JournalPhase phase) throws UpdateRejectedException {
        return new JournalRecord(txid, phase, "fabric-26x", 7,
                new JournalLive("/srv/mc/mods/quicraft.jar", 10L, HEX_A, UpdateVersion.parse("0.3.1")),
                new JournalStaged(20L, HEX_C, UpdateVersion.parse("0.4.0")), HEX_A,
                Instant.parse("2026-11-02T09:00:00Z"), 0);
    }

    private UpdateDirectory open(DurabilityOps ops) throws Exception {
        return new UpdateDirectory(tmp.resolve("upd"), ops);
    }

    private static String sha512(byte[] data) throws Exception {
        return Hex.encode(MessageDigest.getInstance("SHA-512").digest(data));
    }

    /** Deletes a path from inside a test hook, which cannot throw a checked exception. */
    private static void deleteUnchecked(Path p) {
        try {
            Files.delete(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void createsStagingAndBackupSubdirectories() throws Exception {
        open(DurabilityOps.system());
        assertTrue(Files.isDirectory(tmp.resolve("upd/staging")));
        assertTrue(Files.isDirectory(tmp.resolve("upd/backup")));
    }

    @Test
    void pathHelpersRejectBadTxids() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        for (String bad : new String[] {null, "", "../../etc/passwd", TXID.toUpperCase(),
                TXID.substring(1), TXID + "0", "0123456789abcdef0123456789abcdeg"}) {
            assertThrows(IllegalArgumentException.class, () -> d.stagingPart(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> d.stagingJar(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> d.backupJar(bad), bad);
        }
    }

    @Test
    void pathHelpersUseFixedNames() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        assertEquals(tmp.resolve("upd/staging/" + TXID + ".part"), d.stagingPart(TXID));
        assertEquals(tmp.resolve("upd/staging/" + TXID + ".jar"), d.stagingJar(TXID));
        assertEquals(tmp.resolve("upd/backup/" + TXID + ".jar"), d.backupJar(TXID));
        assertEquals(tmp.resolve("upd/journal.json"), d.journal());
    }

    @Test
    void newTxidIsThirtyTwoLowerHexCharacters() throws Exception {
        String a = UpdateDirectory.newTxid();
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertFalse(a.equals(UpdateDirectory.newTxid()));
    }

    @Test
    void createPartRefusesAnExistingFile() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        d.createPart(TXID).close();
        assertThrows(FileAlreadyExistsException.class, () -> d.createPart(TXID));
    }

    @Test
    void createPartRefusesASymlink() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        UpdateDirectory d = open(DurabilityOps.system());
        Path victim = tmp.resolve("victim.bin");
        Files.write(victim, new byte[] {1});
        Files.createSymbolicLink(d.stagingPart(TXID), victim);
        assertThrows(IOException.class, () -> d.createPart(TXID));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(victim));
    }

    @Test
    void commitPartForcesThenMovesThenSyncsStaging() throws Exception {
        FakeOps ops = new FakeOps();
        UpdateDirectory d = open(ops);
        try (FileChannel ch = d.createPart(TXID)) {
            ch.write(ByteBuffer.wrap(new byte[] {9, 9}));
        }
        try (FileChannel ch = FileChannel.open(d.stagingPart(TXID), StandardOpenOption.WRITE)) {
            ops.events.clear();
            d.commitPart(TXID, ch);
        }
        assertEquals(List.of("force", "move", "sync"), ops.events);
        assertFalse(Files.exists(d.stagingPart(TXID)));
        assertArrayEquals(new byte[] {9, 9}, Files.readAllBytes(d.stagingJar(TXID)));
    }

    @Test
    void copyBackupHashEqualsSourceHash() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        byte[] live = new byte[200_000];
        for (int i = 0; i < live.length; i++) {
            live[i] = (byte) (i * 31);
        }
        Path liveJar = tmp.resolve("quicraft.jar");
        Files.write(liveJar, live);
        String copy = d.copyBackup(TXID, liveJar);
        assertEquals(sha512(live), copy);
        assertArrayEquals(live, Files.readAllBytes(d.backupJar(TXID)));
    }

    @Test
    void copyBackupRefusesAnExistingBackup() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        Path liveJar = tmp.resolve("quicraft.jar");
        Files.write(liveJar, new byte[] {1, 2});
        d.copyBackup(TXID, liveJar);
        assertThrows(FileAlreadyExistsException.class, () -> d.copyBackup(TXID, liveJar));
    }

    @Test
    void writeJournalFailureLeavesThePreviousJournalIntact() throws Exception {
        UpdateDirectory good = open(DurabilityOps.system());
        JournalRecord before = journal(TXID, JournalPhase.PREPARED);
        good.writeJournal(before);

        FakeOps ops = new FakeOps();
        ops.failMove = true;
        UpdateDirectory failing = open(ops);
        assertThrows(IOException.class,
                () -> failing.writeJournal(journal(TXID, JournalPhase.SWAPPED)));

        assertEquals(Optional.of(before), good.readJournal());
        assertFalse(Files.exists(tmp.resolve("upd/journal.json.tmp")));
    }

    @Test
    void syncDirectoryFailurePropagatesFromCommitPart() throws Exception {
        FakeOps ops = new FakeOps();
        UpdateDirectory d = open(ops);
        try (FileChannel ch = d.createPart(TXID)) {
            ops.failSync = true;
            assertThrows(IOException.class, () -> d.commitPart(TXID, ch));
        }
    }

    @Test
    void syncDirectoryFailurePropagatesFromWriteJournal() throws Exception {
        FakeOps ops = new FakeOps();
        ops.failSync = true;
        UpdateDirectory d = open(ops);
        assertThrows(IOException.class, () -> d.writeJournal(journal(TXID, JournalPhase.PREPARED)));
    }

    @Test
    void journalRoundTripsThroughTheDirectory() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        assertEquals(Optional.empty(), d.readJournal());
        JournalRecord r = journal(TXID, JournalPhase.BOOT_PENDING).withBootAttempts(2);
        d.writeJournal(r);
        assertEquals(Optional.of(r), d.readJournal());
        d.deleteJournal();
        assertEquals(Optional.empty(), d.readJournal());
    }

    @Test
    void corruptJournalIsRejectedNotIgnored() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        Files.write(d.journal(), "{ not a journal".getBytes(StandardCharsets.UTF_8));
        assertThrows(UpdateRejectedException.class, d::readJournal);
    }

    @Test
    void stagingTxidsIgnoresUnrelatedNamesAndReportsThemAsUnexpected() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        Files.write(d.stagingPart(TXID), new byte[] {1});
        Files.write(d.stagingJar(TXID2), new byte[] {2});
        Files.write(tmp.resolve("upd/staging/readme.txt"), new byte[] {3});
        Files.write(tmp.resolve("upd/staging/" + TXID + ".tmp"), new byte[] {4});

        assertEquals(List.of(TXID, TXID2), d.stagingTxids());
        List<String> unexpected = new ArrayList<>();
        for (Path p : d.unexpectedStagingEntries()) {
            unexpected.add(p.getFileName().toString());
        }
        assertEquals(List.of(TXID + ".tmp", "readme.txt"), unexpected);
    }

    @Test
    void deleteStagingIgnoresMissingFiles() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        d.deleteStaging(TXID);
        Files.write(d.stagingJar(TXID), new byte[] {1});
        d.deleteStaging(TXID);
        assertFalse(Files.exists(d.stagingJar(TXID)));
    }

    @Test
    void sameFileStoreIsTrueWithinOneDirectory() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        Path live = tmp.resolve("quicraft.jar");
        Files.write(live, new byte[] {1});
        assertTrue(d.sameFileStore(live));
    }

    @Test
    void refusesAnUpdateDirectoryThatIsASymlink() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path real = Files.createDirectory(tmp.resolve("real"));
        Files.createSymbolicLink(tmp.resolve("upd"), real);
        assertThrows(UpdateRejectedException.class,
                () -> new UpdateDirectory(tmp.resolve("upd"), DurabilityOps.system()));
    }

    @Test
    void requireOwnedContentsRefusesALinkInsideBackup() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        UpdateDirectory d = open(DurabilityOps.system());
        d.requireOwnedContents();
        Path victim = tmp.resolve("victim.bin");
        Files.write(victim, new byte[] {1});
        Files.createSymbolicLink(d.backupJar(TXID), victim);
        assertThrows(UpdateRejectedException.class, d::requireOwnedContents);
    }

    @Test
    void requireOwnedContentsSkipsAnEntryThatVanishesAfterTheListing() throws Exception {
        // StateStore.prepareDirectory creates and removes a .owner-*.probe file in the update
        // directory, so the scan can be handed an entry that is already gone when it is checked.
        UpdateDirectory d = open(DurabilityOps.system());
        Path probe = tmp.resolve("upd/.owner-vanished.probe");
        Files.write(probe, new byte[] {1});
        Files.write(tmp.resolve("upd/staging/readme.txt"), new byte[] {2});
        Files.write(tmp.resolve("upd/backup/notes.txt"), new byte[] {3});

        List<String> listed = new ArrayList<>();
        d.requireOwnedContents(p -> {
            listed.add(p.getFileName().toString());
            if (".owner-vanished.probe".equals(p.getFileName().toString())) {
                deleteUnchecked(p);
            }
        });

        assertTrue(listed.contains(".owner-vanished.probe"), "the probe was listed");
        assertFalse(Files.exists(probe), "the probe was removed by the hook");
        assertTrue(listed.contains("readme.txt"), "the scan continued into staging/");
        assertTrue(listed.contains("notes.txt"), "the scan continued into backup/");
    }

    @Test
    void requireOwnedContentsStillRefusesALinkAfterAVanishedEntry() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        UpdateDirectory d = open(DurabilityOps.system());
        Path probe = tmp.resolve("upd/.owner-vanished.probe");
        Files.write(probe, new byte[] {1});
        Path victim = tmp.resolve("victim.bin");
        Files.write(victim, new byte[] {2});
        Files.createSymbolicLink(d.backupJar(TXID), victim);

        assertThrows(UpdateRejectedException.class, () -> d.requireOwnedContents(p -> {
            if (".owner-vanished.probe".equals(p.getFileName().toString())) {
                deleteUnchecked(p);
            }
        }));
    }

    @Test
    void requireOwnedContentsStillFailsWhenASubdirectoryVanishesDuringTheScan() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        Files.write(tmp.resolve("upd/.owner-vanished.probe"), new byte[] {1});
        // The hook fires while upd/ is listed, which is before upd/staging/ is listed. The vanished
        // probe is skipped, but a directory that is gone must still fail closed.
        assertThrows(NoSuchFileException.class, () -> d.requireOwnedContents(p -> {
            if ("staging".equals(p.getFileName().toString())) {
                deleteUnchecked(p);
            }
        }));
    }

    @Test
    void missingLiveJarIsAnIoError() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        assertThrows(NoSuchFileException.class, () -> d.copyBackup(TXID, tmp.resolve("absent.jar")));
    }

    @Test
    void unsupportedDirectorySyncRecordsTheWarningAndContinues() throws Exception {
        FakeOps ops = new FakeOps();
        ops.syncSupported = false;
        UpdateDirectory d = open(ops);
        assertFalse(d.directorySyncSupported());
        try (FileChannel ch = d.createPart(TXID)) {
            ch.write(ByteBuffer.wrap(new byte[] {5}));
            d.commitPart(TXID, ch);
        }
        assertEquals(List.of(UpdateDirectory.DIRECTORY_SYNC_WARNING), d.warnings());
        assertArrayEquals(new byte[] {5}, Files.readAllBytes(d.stagingJar(TXID)));
    }

    @Test
    void systemSyncToleratesOnlyAFailureToOpenTheDirectory() throws Exception {
        assertFalse(DurabilityOps.system().syncDirectory(tmp.resolve("absent")));
        assertTrue(DurabilityOps.system().syncDirectory(tmp));
        assertTrue(DurabilityOps.system().directorySyncSupported(tmp));
        assertFalse(DurabilityOps.system().directorySyncSupported(tmp.resolve("absent")));
    }

    @Test
    void backupTxidsAndUnexpectedBackupEntriesAreReported() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        Files.write(d.backupJar(TXID2), new byte[] {1});
        Files.write(d.backupJar(TXID), new byte[] {2});
        Files.write(tmp.resolve("upd/backup/notes.txt"), new byte[] {3});
        assertEquals(List.of(TXID, TXID2), d.backupTxids());
        List<String> unexpected = new ArrayList<>();
        for (Path p : d.unexpectedBackupEntries()) {
            unexpected.add(p.getFileName().toString());
        }
        assertEquals(List.of("notes.txt"), unexpected);
    }

    @Test
    void deleteBackupRemovesTheFileSyncsAndIgnoresMissing() throws Exception {
        FakeOps ops = new FakeOps();
        UpdateDirectory d = open(ops);
        Files.write(d.backupJar(TXID), new byte[] {1});
        ops.events.clear();
        d.deleteBackup(TXID);
        assertFalse(Files.exists(d.backupJar(TXID)));
        assertEquals(List.of("sync"), ops.events);
        d.deleteBackup(TXID);
        assertEquals(List.of("sync"), ops.events, "a missing backup is a no-op");
    }

    @Test
    void deleteBackupRefusesASymlinkAndLeavesItsTarget() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        UpdateDirectory d = open(DurabilityOps.system());
        Path victim = tmp.resolve("victim.bin");
        Files.write(victim, new byte[] {7});
        Files.createSymbolicLink(d.backupJar(TXID), victim);
        assertThrows(IOException.class, () -> d.deleteBackup(TXID));
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(victim));
        assertTrue(Files.isSymbolicLink(d.backupJar(TXID)));
    }

    @Test
    void deleteBackupRejectsAnInvalidTxid() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        assertThrows(IllegalArgumentException.class, () -> d.deleteBackup("../escape"));
    }

    @Test
    void deleteJournalTmpRemovesAStaleTmpAndSyncsOnlyWhenItRemovedSomething() throws Exception {
        FakeOps ops = new FakeOps();
        UpdateDirectory d = open(ops);
        ops.events.clear();
        d.deleteJournalTmp();
        assertEquals(List.of(), ops.events, "nothing to delete, no sync");
        Files.write(tmp.resolve("upd/journal.json.tmp"), new byte[] {1});
        d.deleteJournalTmp();
        assertFalse(Files.exists(tmp.resolve("upd/journal.json.tmp")));
        assertEquals(List.of("sync"), ops.events);
    }

    @Test
    void writeJournalCleanupFailureNeverMasksTheOriginalException() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        // A non-empty directory named like the temporary file: opening it fails first, and removing
        // it fails second. The second failure must be attached, not thrown instead.
        Path tmpAsDir = tmp.resolve("upd/journal.json.tmp");
        Files.createDirectory(tmpAsDir);
        Files.write(tmpAsDir.resolve("child"), new byte[] {1});
        IOException thrown = assertThrows(IOException.class,
                () -> d.writeJournal(journal(TXID, JournalPhase.PREPARED)));
        assertFalse(thrown instanceof DirectoryNotEmptyException, "original failure must surface");
        assertTrue(thrown instanceof FileSystemException);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof DirectoryNotEmptyException);
    }

    @Test
    void moveOverLiveReplacesTheLiveJarAndSyncsItsDirectory() throws Exception {
        FakeOps ops = new FakeOps();
        UpdateDirectory d = open(ops);
        Path mods = Files.createDirectory(tmp.resolve("mods"));
        Path live = mods.resolve("quicraft.jar");
        Files.write(live, new byte[] {1, 1});
        Files.write(d.stagingJar(TXID), new byte[] {2, 2, 2});
        ops.events.clear();
        d.moveOverLive(TXID, live);
        assertArrayEquals(new byte[] {2, 2, 2}, Files.readAllBytes(live));
        assertFalse(Files.exists(d.stagingJar(TXID)));
        assertEquals(List.of("move", "sync"), ops.events);
        assertEquals(List.of(), d.warnings());
    }

    @Test
    void moveOverLiveRequiresAnAbsolutePath() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        assertThrows(IllegalArgumentException.class, () -> d.moveOverLive(TXID, Path.of("quicraft.jar")));
    }

    @Test
    void syncFailureAfterMoveOverLiveMeansTheMoveMayHaveHappened() throws Exception {
        FakeOps ops = new FakeOps();
        UpdateDirectory d = open(ops);
        Path live = Files.createDirectory(tmp.resolve("mods")).resolve("quicraft.jar");
        Files.write(live, new byte[] {1});
        Files.write(d.stagingJar(TXID), new byte[] {9});
        ops.failSync = true;
        assertThrows(IOException.class, () -> d.moveOverLive(TXID, live));
        // The documented contract: the rename already happened, so the caller must re-check the file.
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(live));
    }

    @Test
    void sha512OfRefusesLinksAndNonRegularFiles() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        UpdateDirectory d = open(DurabilityOps.system());
        Path file = tmp.resolve("data.bin");
        Files.write(file, new byte[] {3});
        Path link = tmp.resolve("link.bin");
        Files.createSymbolicLink(link, file);
        assertThrows(IOException.class, () -> UpdateDirectory.sha512Of(link));
        assertThrows(IOException.class, () -> UpdateDirectory.sha512Of(tmp));
        assertEquals(sha512(new byte[] {3}), UpdateDirectory.sha512Of(file));
        assertTrue(d.backupTxids().isEmpty());
    }
}
