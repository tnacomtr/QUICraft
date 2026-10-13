// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
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
        public void syncDirectory(Path dir) throws IOException {
            events.add("sync");
            if (failSync) {
                throw new IOException("injected sync failure");
            }
            DurabilityOps.system().syncDirectory(dir);
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
    void missingLiveJarIsAnIoError() throws Exception {
        UpdateDirectory d = open(DurabilityOps.system());
        assertThrows(NoSuchFileException.class, () -> d.copyBackup(TXID, tmp.resolve("absent.jar")));
    }
}
