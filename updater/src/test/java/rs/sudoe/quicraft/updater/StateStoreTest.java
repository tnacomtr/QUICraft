// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.KidSeq;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;

/** Store tests: atomic commits, corruption fallback, locking, filesystem safety and clamping. */
class StateStoreTest {

    @TempDir
    Path tmp;

    private static final String HEX_A = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);
    private static final String HEX_C = "c".repeat(64);
    private static final Instant T1 = Instant.parse("2026-11-01T12:00:00Z");
    private static final String KID_1 = "0123456789abcdef";
    private static final String KID_2 = "fedcba9876543210";

    private static UpdaterState seedState(long seq) {
        return new UpdaterState(1, HEX_A, HEX_B,
                new CurrentRevocations(seq, "", null, null, 0),
                Map.of(), List.of(), "", List.of(), Map.of());
    }

    private static StateDelta seedDelta() {
        return new StateDelta(seedState(0), RevokedState.empty());
    }

    private Path updateDir() throws IOException {
        return privateDir(Path.of(tmp.toString(), "upd"));
    }

    /** Writes a file with owner-only permissions on POSIX, as the store itself would. */
    static void writePrivate(Path p, byte[] bytes) throws IOException {
        Files.write(p, bytes);
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        }
    }

    static Path privateDirFor(Path p) throws IOException {
        return privateDir(p);
    }

    /** Creates a directory the way the store does: owner-only on POSIX (umask-independent). */
    static Path privateDir(Path p) throws IOException {
        Files.createDirectories(p);
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        }
        return p;
    }


    /** Adds one kid to seqByKid and bumps the revocations seq. */
    private static UpdaterState withKid(UpdaterState s, String kid, long seq) {
        Map<String, KidSeq> map = new java.util.HashMap<>(s.seqByKid());
        map.put(kid, new KidSeq(seq, HEX_C, T1));
        return new UpdaterState(s.epoch(), s.keySetFingerprint(), s.revocationKey(),
                s.revocations(), map, s.withdrawn(), s.lastAnnounced(), s.warnedBad(),
                s.tornSince());
    }

    private static Snapshot addKid(Snapshot before, String kid, long seq) {
        return new Snapshot(withKid(before.state(), kid, seq), before.revoked());
    }

    private static Snapshot blockOne(Snapshot before, String sha512) {
        RevokedState r = new RevokedState(before.revoked().revokedKeys(),
                List.of(sha512));
        return new Snapshot(before.state(), r);
    }

    @Test
    void initialiseWritesBothFilesAndReturnsSeeds() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        Snapshot s = store.initialiseIfAbsent(seedDelta(), true);
        assertEquals(seedDelta().newState(), s.state());
        assertTrue(Files.exists(dir.resolve("state.json")));
        assertTrue(Files.exists(dir.resolve("revoked.json")));
        assertEquals(Optional.of(s), new StateStore(dir).load());
    }

    @Test
    void initialiseKeepsExistingStateUnchanged() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seedDelta(), true);
        StateDelta other = new StateDelta(seedState(9), RevokedState.empty());
        Snapshot again = store.initialiseIfAbsent(other, true);
        assertEquals(seedState(0), again.state());
    }

    @Test
    void loadOnEmptyDirectoryIsEmpty() throws Exception {
        assertEquals(Optional.empty(), new StateStore(updateDir()).load());
    }

    @Test
    void commitWritesOnlyChangedFilesAndReturnsNewSnapshot() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        Snapshot initial = store.initialiseIfAbsent(seedDelta(), true);
        Snapshot next = store.commit(before -> addKid(before, KID_1, 1));
        assertEquals(1, next.state().seqByKid().get(KID_1).seq());
        assertEquals(Optional.of(next), new StateStore(dir).load());
        assertNotEquals(initial, next);
    }

    @Test
    void commitWithNoChangeDoesNothing() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seedDelta(), true);
        byte[] before = Files.readAllBytes(dir.resolve("state.json"));
        store.commit(s -> s);
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("state.json")));
        assertFalse(Files.exists(dir.resolve("state.json.prev")));
    }

    @Test
    void crashAfterRevokedWriteLeavesNewRevokedOldStateAndNextCommitConverges() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        String blocked = "d".repeat(128);
        StateStore crashing = new StateStore(dir, () -> {
            throw new IllegalStateException("simulated crash");
        });
        assertThrows(IllegalStateException.class,
                () -> crashing.commit(before -> blockOne(addKid(before, KID_1, 1), blocked)));

        StateStore after = new StateStore(dir);
        Snapshot seen = after.load().orElseThrow();
        assertEquals(List.of(blocked), seen.revoked().blockedArtifacts());
        assertFalse(seen.state().seqByKid().containsKey(KID_1));

        Snapshot converged = after.commit(before -> blockOne(addKid(before, KID_1, 1), blocked));
        assertEquals(1, converged.state().seqByKid().get(KID_1).seq());
        assertEquals(List.of(blocked), converged.revoked().blockedArtifacts());
        assertEquals(Optional.of(converged), new StateStore(dir).load());
    }

    @Test
    void corruptStateUsesPrevWithWarning() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seedDelta(), true);
        Snapshot first = store.commit(before -> addKid(before, KID_1, 1));
        store.commit(before -> addKid(before, KID_2, 2));
        Files.write(dir.resolve("state.json"), "{ not json".getBytes(StandardCharsets.UTF_8));

        StateStore reader = new StateStore(dir);
        Snapshot loaded = reader.load().orElseThrow();
        assertEquals(first.state(), loaded.state());
        assertEquals(1, reader.warnings().size());
        assertTrue(reader.warnings().get(0).contains("state.json"), reader.warnings().toString());
        assertFalse(reader.warnings().get(0).contains("not json"));
    }

    @Test
    void invalidMainIsNotCopiedOverGoodPrev() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seedDelta(), true);
        Snapshot first = store.commit(before -> addKid(before, KID_1, 1));
        store.commit(before -> addKid(before, KID_2, 2));
        byte[] goodPrev = Files.readAllBytes(dir.resolve("state.json.prev"));
        Files.write(dir.resolve("state.json"), "garbage".getBytes(StandardCharsets.UTF_8));

        StateStore writer = new StateStore(dir);
        writer.commit(before -> addKid(before, "1111111111111111", 3));

        assertArrayEquals(goodPrev, Files.readAllBytes(dir.resolve("state.json.prev")));
        assertEquals(first.state(), StateCodec.decode(goodPrev));
    }

    @Test
    void bothStateCopiesCorruptRaisesUnavailableWithRevokedValid() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seedDelta(), true);
        store.commit(before -> addKid(before, KID_1, 1));
        Files.write(dir.resolve("state.json"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("state.json.prev"), "y".getBytes(StandardCharsets.UTF_8));

        StateUnavailableException e = assertThrows(StateUnavailableException.class,
                () -> new StateStore(dir).load());
        assertTrue(e.revokedValid());
    }

    @Test
    void bothRevokedCopiesCorruptRaisesUnavailableWithRevokedInvalid() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seedDelta(), true);
        store.commit(before -> addKid(before, KID_1, 1));
        Files.write(dir.resolve("revoked.json"), "x".getBytes(StandardCharsets.UTF_8));
        writePrivate(dir.resolve("revoked.json.prev"), "y".getBytes(StandardCharsets.UTF_8));

        StateUnavailableException e = assertThrows(StateUnavailableException.class,
                () -> new StateStore(dir).load());
        assertFalse(e.revokedValid());
    }

    @Test
    void stateFileMissingWithRevokedPresentIsUnavailable() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Files.delete(dir.resolve("state.json"));
        StateUnavailableException e = assertThrows(StateUnavailableException.class,
                () -> new StateStore(dir).load());
        assertTrue(e.revokedValid());
    }

    @Test
    void resetStateKeepsRevokedByteIdentical() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seedDelta(), true);
        store.commit(before -> blockOne(before, "e".repeat(128)));
        byte[] revokedBefore = Files.readAllBytes(dir.resolve("revoked.json"));
        Files.write(dir.resolve("state.json"), "broken".getBytes(StandardCharsets.UTF_8));

        UpdaterState rebuilt = seedState(5);
        Snapshot s = new StateStore(dir).resetState(rebuilt);
        assertEquals(rebuilt, s.state());
        assertArrayEquals(revokedBefore, Files.readAllBytes(dir.resolve("revoked.json")));
        assertEquals(Optional.of(s), new StateStore(dir).load());
    }

    @Test
    void resetStateRefusesWhenRevokedInvalid() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Files.write(dir.resolve("revoked.json"), "x".getBytes(StandardCharsets.UTF_8));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> new StateStore(dir).resetState(seedState(5)));
        assertEquals("revoked.json invalid; refetch required", e.getMessage());
    }

    @Test
    void concurrentCommitsInOneJvmAreSerialised() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Snapshot> a = pool.submit(() -> {
                start.await();
                return new StateStore(dir).commit(before -> addKid(before, KID_1, 1));
            });
            Future<Snapshot> b = pool.submit(() -> {
                start.await();
                return new StateStore(dir).commit(before -> addKid(before, KID_2, 2));
            });
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        Snapshot finalState = new StateStore(dir).load().orElseThrow();
        assertEquals(Map.of(KID_1, 1L, KID_2, 2L), seqs(finalState));
    }

    @Test
    void concurrentPreparationsToleratePeerProbesVanishing() throws Exception {
        // Each operation creates and deletes an owner probe before taking the in-process guard,
        // while sweeping the probes it lists; a peer's probe may vanish in between.
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 300; i++) {
                        new StateStore(dir).load();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleProbeSweepIgnoresAMissingProbe() throws Exception {
        Path dir = updateDir();
        Files.createDirectories(dir);
        StateStore.deleteIfStale(dir.resolve(".owner-gone.probe"), Instant.now());
    }

    private static Map<String, Long> seqs(Snapshot s) {
        Map<String, Long> out = new java.util.HashMap<>();
        s.state().seqByKid().forEach((k, v) -> out.put(k, v.seq()));
        return out;
    }

    @Test
    void lockHeldByAnotherProcessIsRefusedWithoutWaiting() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolderMain.class.getName(), dir.toString())
                .redirectErrorStream(true).start();
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("locked", out.readLine());
            StoreBusyException e = assertThrows(StoreBusyException.class,
                    () -> new StateStore(dir).load());
            assertEquals("another server uses this update directory", e.getMessage());
            assertThrows(StoreBusyException.class,
                    () -> new StateStore(dir).commit(s -> s));
        } finally {
            child.getOutputStream().close();
            child.waitFor();
        }
        assertTrue(new StateStore(dir).load().isPresent());
    }

    @Test
    void symlinkedUpdateDirIsRefused() throws Exception {
        Path real = Files.createDirectories(Path.of(tmp.toString(), "real"));
        Path link = Path.of(tmp.toString(), "link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks unsupported here");
        }
        assertThrows(UpdateRejectedException.class,
                () -> new StateStore(link).initialiseIfAbsent(seedDelta(), true));
    }

    @Test
    void symlinkedStateFileIsRefused() throws Exception {
        Path dir = updateDir();
        Path elsewhere = Files.writeString(Path.of(tmp.toString(), "elsewhere.json"), "x");
        try {
            Files.createSymbolicLink(dir.resolve("state.json"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks unsupported here");
        }
        assertThrows(UpdateRejectedException.class, () -> new StateStore(dir).load());
    }

    @Test
    void symlinkedLockFileIsRefused() throws Exception {
        Path dir = updateDir();
        Path elsewhere = Files.writeString(Path.of(tmp.toString(), "lock-target"), "");
        try {
            Files.createSymbolicLink(dir.resolve("state.lock"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symlinks unsupported here");
        }
        assertThrows(UpdateRejectedException.class,
                () -> new StateStore(dir).initialiseIfAbsent(seedDelta(), true));
    }

    @Test
    void ownerOfStoreFilesIsTheProcessUserPositivePath() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        UserPrincipal owner = Files.getOwner(dir.resolve("state.json"));
        StateStore.requireOwner(dir.resolve("state.json"), owner);
        StateStore.requireOwner(dir, owner);
    }

    @Test
    void ownerMismatchIsRefused() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        UserPrincipal other = new UserPrincipal() {
            @Override
            public String getName() {
                return "someone-else";
            }
        };
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> StateStore.requireOwner(dir.resolve("state.json"), other));
        assertEquals("update directory not owned by the server user", e.getMessage());
    }

    @Test
    void posixPermissionsAre700And600WhenSupported() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path dir = Path.of(tmp.toString(), "perm");
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(dir));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(dir.resolve("state.json")));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(dir.resolve("revoked.json")));
    }

    @Test
    void clampRaisesSeqAndManifestFloorWithoutTouchingHashWhenSeqUnchanged() {
        UpdaterState s = new UpdaterState(1, HEX_A, HEX_B,
                new CurrentRevocations(3, HEX_C, T1, T1, 2), Map.of(), List.of(), "", List.of(),
                Map.of());
        UpdateDocuments.Trust trust = new UpdateDocuments.Trust(1, T1, 3, 7, List.of());
        UpdaterState clamped = StateStore.clampToTrust(s, trust);
        assertEquals(new CurrentRevocations(3, HEX_C, T1, T1, 7), clamped.revocations());
    }

    @Test
    void clampResetsToSeedFormWhenSeqRaisesOverStoredHash() {
        UpdaterState s = new UpdaterState(1, HEX_A, HEX_B,
                new CurrentRevocations(3, HEX_C, T1, T1, 2), Map.of(), List.of(), "", List.of(),
                Map.of());
        UpdateDocuments.Trust trust = new UpdateDocuments.Trust(1, T1, 5, 0, List.of());
        UpdaterState clamped = StateStore.clampToTrust(s, trust);
        assertEquals(new CurrentRevocations(5, "", null, null, 2), clamped.revocations());
        assertTrue(clamped.revocations().seed());
    }

    @Test
    void clampLeavesStateAloneWhenAlreadyAboveFloors() {
        UpdaterState s = seedState(4);
        UpdateDocuments.Trust trust = new UpdateDocuments.Trust(1, T1, 4, 0, List.of());
        assertTrue(s == StateStore.clampToTrust(s, trust));
    }

    @Test
    void initialisationCrashAfterRevokedWriteConvergesByReseeding() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Files.delete(dir.resolve("state.json"));
        StateStore store = new StateStore(dir);
        Snapshot s = store.initialiseIfAbsent(seedDelta(), true);
        assertEquals(seedDelta().newState(), s.state());
        assertTrue(store.warnings().contains(
                "state.json missing after interrupted initialisation; reseeded"),
                store.warnings().toString());
        assertEquals(Optional.of(s), new StateStore(dir).load());
    }

    @Test
    void interruptedInitialisationIsNotReseededWhenFirstRunNotAllowed() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Files.delete(dir.resolve("state.json"));
        StateStore store = new StateStore(dir);
        assertThrows(StateUnavailableException.class,
                () -> store.initialiseIfAbsent(seedDelta(), false));
        assertFalse(Files.exists(dir.resolve("state.json")));
        assertFalse(store.warnings().contains(
                "state.json missing after interrupted initialisation; reseeded"));
    }

    @Test
    void emptyDirectoryIsNotInitialisedWhenFirstRunNotAllowed() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        StateUnavailableException e = assertThrows(StateUnavailableException.class,
                () -> store.initialiseIfAbsent(seedDelta(), false));
        assertFalse(e.revokedValid());
        assertFalse(Files.exists(dir.resolve("state.json")));
        assertFalse(Files.exists(dir.resolve("revoked.json")));
    }

    @Test
    void atomicWriteRefusesContentThatFailsTheReadValidator() throws Exception {
        Path dir = updateDir();
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(seedDelta(), true);
        byte[] before = Files.readAllBytes(dir.resolve("state.json"));
        IOException e = assertThrows(IOException.class, () -> store.atomicWrite(StateStore.STATE,
                "not a state file".getBytes(StandardCharsets.UTF_8), StateStore::validState,
                new ArrayList<>()));
        assertEquals("refusing to write an invalid state file", e.getMessage());
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("state.json")));
        assertFalse(Files.exists(dir.resolve("state.json.tmp")));
        assertFalse(Files.exists(dir.resolve("state.json.prev")));
    }

    @Test
    void initialiseWithCorruptRevokedAndMissingStateStaysUnavailable() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Files.delete(dir.resolve("state.json"));
        Files.write(dir.resolve("revoked.json"), "x".getBytes(StandardCharsets.UTF_8));
        StateUnavailableException e = assertThrows(StateUnavailableException.class,
                () -> new StateStore(dir).initialiseIfAbsent(seedDelta(), true));
        assertFalse(e.revokedValid());
    }

    @Test
    void directoryFsyncIsSupportedOnLinuxTempDir() throws Exception {
        assertTrue(StateStore.directoryFsyncSupported(updateDir()));
    }

    @Test
    void failingDirectoryForcePropagatesToCaller() throws Exception {
        Path dir = updateDir();
        StateStore failing = new StateStore(dir, () -> { }, ch -> {
            throw new IOException("fsync failed");
        });
        IOException e = assertThrows(IOException.class,
                () -> failing.initialiseIfAbsent(seedDelta(), true));
        assertEquals("fsync failed", e.getMessage());
    }

    @Test
    void groupOrOtherBitsOnDirectoryAreRefused() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> new StateStore(dir).load());
        assertEquals("update directory permissions too open", e.getMessage());
    }

    @Test
    void groupOrOtherBitsOnStateFileAreRefused() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Files.setPosixFilePermissions(dir.resolve("state.json"),
                PosixFilePermissions.fromString("rw-r--r--"));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> new StateStore(dir).load());
        assertEquals("update directory permissions too open", e.getMessage());
    }

    @Test
    void oversizedStateFileIsTreatedAsInvalid() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Files.write(dir.resolve("state.json"), new byte[300_000]);
        StateUnavailableException e = assertThrows(StateUnavailableException.class,
                () -> new StateStore(dir).load());
        assertTrue(e.revokedValid());
    }

    @Test
    void staleProbeFilesAreSweptButFreshOnesKept() throws Exception {
        Path dir = updateDir();
        new StateStore(dir).initialiseIfAbsent(seedDelta(), true);
        Path stale = Files.createFile(dir.resolve(".owner-stale.probe"));
        Files.setLastModifiedTime(stale,
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(600)));
        Path fresh = Files.createFile(dir.resolve(".owner-fresh.probe"));
        new StateStore(dir).load();
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
    }
}
