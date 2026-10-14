// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.PipelineFixtures.ScriptedFetcher;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.TransactionFixtures.ArtifactFetcher;
import rs.sudoe.quicraft.updater.TestKeys;
import rs.sudoe.quicraft.updater.UpdaterTransaction.SameStore;

/**
 * The update transaction: the active slot and cancel (docs/updater.md 12.9), and steps 1 to 3 of
 * 12.10 (checking, downloading, verified-staged). Steps 4 to 6 are added in later commits.
 */
class UpdaterTransactionTest {

    private static final byte[] LIVE_BYTES = "the running quicraft jar".getBytes(
            java.nio.charset.StandardCharsets.UTF_8);
    private static final String VERSION = TransactionFixtures.RUNNING_VERSION;

    @TempDir
    Path tmp;

    /** A real store, pipeline, directory and fetcher, with the live jar on disk and no transaction. */
    private static final class Rig {
        Path upd;
        Path live;
        byte[] liveBytes = LIVE_BYTES;
        byte[] jar;
        byte[] manifest;
        ScriptedFetcher metadata;
        ArtifactFetcher fetcher;
        StateStore store;
        RuntimeIdentity identity;
        CheckPipeline pipeline;

        LiveJar discover() throws IOException, UpdateRejectedException {
            return new LiveJar(live, live.getParent(), liveBytes.length,
                    TransactionFixtures.sha512Hex(liveBytes), UpdateVersion.parse(VERSION));
        }
    }

    private Rig rig(byte[] jar, byte[] manifest) throws Exception {
        Rig r = new Rig();
        r.upd = PipelineFixtures.privateDir(tmp.resolve("upd"));
        r.live = tmp.resolve("live").resolve("quicraft.jar");
        Files.createDirectories(r.live.getParent());
        PipelineFixtures.writePrivate(r.live, LIVE_BYTES);
        r.jar = jar;
        r.manifest = manifest;
        r.store = new StateStore(r.upd);
        r.store.initialiseIfAbsent(new StateDelta(PipelineFixtures.seedState(), RevokedState.empty()), true);
        r.metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(r.metadata, PipelineFixtures.revocations(), manifest);
        r.fetcher = new ArtifactFetcher(r.metadata, jar);
        r.identity = new RuntimeIdentity(TransactionFixtures.PLATFORM, TransactionFixtures.TARGET,
                UpdateVersion.parse(VERSION), PipelineFixtures.trust(),
                TransactionFixtures.sha512Hex(LIVE_BYTES));
        r.pipeline = new CheckPipeline(r.store, PipelineFixtures.collector(r.fetcher), PipelineFixtures.HOSTS,
                r.identity, PipelineFixtures.CLOCK);
        return r;
    }

    /** The standard rig: a newer release that matches the jar. */
    private Rig standardRig() throws Exception {
        byte[] jar = TransactionFixtures.releaseJar(TransactionFixtures.NEW_VERSION);
        return rig(jar, TransactionFixtures.manifestFor(jar, TransactionFixtures.NEW_VERSION));
    }

    private UpdaterTransaction txn(Rig r, DurabilityOps ops, InitBarrier init, SameStore same) {
        return new UpdaterTransaction(r.upd, ops, r.store, r.pipeline, r.fetcher,
                PipelineFixtures.ENDPOINTS, Map.of(), FetchTimeouts.defaults(), r::discover, init,
                PipelineFixtures.CLOCK, same);
    }

    private UpdaterTransaction txn(Rig r) {
        return txn(r, DurabilityOps.system(), () -> true, UpdateDirectory::sameFileStore);
    }

    /** The default request stops after the step 5 checks, so step 2 to 4 tests see the staged jar. */
    private static UpdateRequest request(Rig r) {
        return request(r, InstallCapability.VERIFIED_STAGING_ONLY);
    }

    private static UpdateRequest atomic(Rig r) {
        return request(r, InstallCapability.ATOMIC_REPLACEMENT);
    }

    private static UpdateRequest request(Rig r, InstallCapability capability) {
        return new UpdateRequest(r.identity, capability);
    }

    /** Durability ops that run {@code onSecondForce} at the second force (after the step 3 force). */
    private static DurabilityOps countingForce(Runnable onSecondForce) {
        DurabilityOps system = DurabilityOps.system();
        int[] forces = {0};
        return new DurabilityOps() {
            @Override
            public void force(FileChannel ch) throws IOException {
                forces[0]++;
                if (forces[0] == 2) {
                    onSecondForce.run();
                }
                system.force(ch);
            }

            @Override
            public void atomicMove(Path from, Path to) throws IOException {
                system.atomicMove(from, to);
            }

            @Override
            public boolean syncDirectory(Path dir) throws IOException {
                return system.syncDirectory(dir);
            }
        };
    }

    private static int journalCount(Rig r) throws IOException {
        try {
            return new UpdateDirectory(r.upd, DurabilityOps.system()).readJournal().isPresent() ? 1 : 0;
        } catch (UpdateRejectedException e) {
            throw new IOException(e);
        }
    }

    private static int backupCount(Rig r) throws IOException {
        try {
            return new UpdateDirectory(r.upd, DurabilityOps.system()).backupTxids().size();
        } catch (UpdateRejectedException e) {
            throw new IOException(e);
        }
    }

    private static boolean stagingIsEmpty(Rig r) throws IOException {
        try {
            return new UpdateDirectory(r.upd, DurabilityOps.system()).stagingTxids().isEmpty();
        } catch (UpdateRejectedException e) {
            throw new IOException(e);
        }
    }

    private static void assertLiveUntouched(Rig r) throws IOException {
        assertArrayEquals(LIVE_BYTES, Files.readAllBytes(r.live));
    }

    /** A durability layer that reports directory sync unsupported, so atomic replacement downgrades. */
    private static DurabilityOps noDirectorySync() {
        DurabilityOps system = DurabilityOps.system();
        return new DurabilityOps() {
            @Override
            public void force(FileChannel ch) throws IOException {
                system.force(ch);
            }

            @Override
            public void atomicMove(Path from, Path to) throws IOException {
                system.atomicMove(from, to);
            }

            @Override
            public boolean syncDirectory(Path dir) throws IOException {
                return system.syncDirectory(dir);
            }

            @Override
            public boolean directorySyncSupported(Path dir) {
                return false;
            }
        };
    }

    // ---- step 0: the active slot and cancel -------------------------------------------------

    @Test
    void cancelWithNoActiveTransactionAnswersNoActiveAndDeletesNothing() throws Exception {
        Rig r = standardRig();
        assertEquals(CancelReply.NO_ACTIVE_TRANSACTION, txn(r).cancelActive());
    }

    @Test
    void cancelWhileRunningRequestsAndTripsTheToken() throws Exception {
        UpdaterTransaction txn = txn(standardRig());
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        assertFalse(mine.token.isCancelled());
        assertEquals(CancelReply.REQUESTED, txn.cancelActive());
        assertTrue(mine.token.isCancelled());
        assertEquals(UpdaterTransaction.Phase.CANCEL_REQUESTED, mine.phase.get());
    }

    @Test
    void repeatedCancelWhileCancelRequestedIsIdempotent() throws Exception {
        UpdaterTransaction txn = txn(standardRig());
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        assertEquals(CancelReply.REQUESTED, txn.cancelActive());
        assertEquals(CancelReply.REQUESTED, txn.cancelActive());
        assertTrue(mine.token.isCancelled());
    }

    @Test
    void cancelAfterSwapStartedAnswersInstalledAndDoesNotCancel() throws Exception {
        UpdaterTransaction txn = txn(standardRig());
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        assertTrue(mine.phase.compareAndSet(UpdaterTransaction.Phase.RUNNING,
                UpdaterTransaction.Phase.SWAPPING));
        assertEquals(CancelReply.INSTALLED_RESTART_PENDING, txn.cancelActive());
        assertFalse(mine.token.isCancelled());
        assertEquals(UpdaterTransaction.Phase.SWAPPING, mine.phase.get());
    }

    @Test
    void cancelAfterTheTransactionLeftAnswersNoActive() throws Exception {
        UpdaterTransaction txn = txn(standardRig());
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        txn.leave(mine);
        assertEquals(CancelReply.NO_ACTIVE_TRANSACTION, txn.cancelActive());
    }

    @Test
    void cancelDoesNotTakeTheTransactionLock() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        UpdaterTransaction.Active mine = txn.tryEnter();
        assertNotNull(mine);
        // The transaction thread owns txn.lock; cancel must answer without waiting for it.
        try (TransactionLock.TxnLease lease = TransactionLock.tryAcquire(r.upd)) {
            assertNotNull(lease);
            assertEquals(CancelReply.REQUESTED, txn.cancelActive());
        }
        assertTrue(mine.token.isCancelled());
    }

    @Test
    void secondEntryIsRefusedWhileOneIsActive() throws Exception {
        UpdaterTransaction txn = txn(standardRig());
        assertNotNull(txn.tryEnter());
        assertNull(txn.tryEnter());
    }

    // ---- step 1: checking, admission and the capability ------------------------------------

    @Test
    void notAdmittedWhenInitNotComplete_noPipelineRunNoNetworkNoStaging() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r, DurabilityOps.system(), () -> false, UpdateDirectory::sameFileStore)
                .execute(request(r));
        assertEquals(TransactionOutcome.NOT_ADMITTED, result.outcome());
        assertTrue(r.metadata.requests.isEmpty(), "the check pipeline must not run");
        assertTrue(r.fetcher.downloads.isEmpty());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void notAdmittedWhenAJournalIsPresent() throws Exception {
        Rig r = standardRig();
        String liveSha = TransactionFixtures.sha512Hex(LIVE_BYTES);
        JournalRecord journal = new JournalRecord("ab".repeat(16), JournalPhase.PREPARED,
                TransactionFixtures.PLATFORM, 42,
                new JournalLive(r.live.toString(), LIVE_BYTES.length, liveSha, UpdateVersion.parse(VERSION)),
                new JournalStaged(r.jar.length, "cd".repeat(64), UpdateVersion.parse(TransactionFixtures.NEW_VERSION)),
                liveSha, Instant.parse(SignedFixtures.TS1), 0);
        new UpdateDirectory(r.upd, DurabilityOps.system()).writeJournal(journal);
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.NOT_ADMITTED, result.outcome());
        assertTrue(result.messages().get(0).contains(UpdateAdmission.JOURNAL_PRESENT), result.messages().toString());
        assertTrue(r.metadata.requests.isEmpty());
        assertTrue(r.fetcher.downloads.isEmpty());
    }

    @Test
    void notAdmittedWhenTheLiveJarChangedSinceInit() throws Exception {
        Rig r = standardRig();
        r.liveBytes = "replaced on disk after start".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.NOT_ADMITTED, result.outcome());
        assertTrue(result.messages().get(0).contains(UpdateAdmission.JAR_CHANGED), result.messages().toString());
        assertTrue(r.metadata.requests.isEmpty());
        assertTrue(r.fetcher.downloads.isEmpty());
    }

    @Test
    void postRunAdmissionRefusesWhenTheRunReportsStateFull_withNoDownload() throws Exception {
        Rig r = standardRig();
        // Fill the revocation cap across runs, as CheckPipelineTest does; the pre-check sees a
        // non-full state, but the second admission after the run must refuse it.
        for (int run = 0; run < 4; run++) {
            TransactionFixtures.serveMetadata(r.metadata,
                    PipelineFixtures.revocations(TransactionFixtures.revokedKeys(run * 16, 16)), r.manifest);
            r.pipeline.run(CheckMode.REPORT, UpdateVersion.parse(VERSION), new MutableCancelToken());
        }
        TransactionFixtures.serveMetadata(r.metadata,
                PipelineFixtures.revocations(TransactionFixtures.revokedKeys(100, 1)), r.manifest);
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.NOT_ADMITTED, result.outcome(), result.messages().toString());
        assertTrue(result.messages().get(0).contains(UpdateAdmission.STATE_FULL), result.messages().toString());
        assertTrue(r.fetcher.downloads.isEmpty(), "no download after a refused admission");
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void noUpdateWhenNoNewerReleaseIsOffered() throws Exception {
        byte[] jar = TransactionFixtures.releaseJar(VERSION);
        Rig r = rig(jar, TransactionFixtures.manifestFor(jar, VERSION));
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.NO_UPDATE, result.outcome());
        assertTrue(r.fetcher.downloads.isEmpty());
        assertTrue(stagingIsEmpty(r));
    }

    @Test
    void cancelDuringTheCheckRunStopsBeforeAnyDownload() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        r.metadata.onFetch = uri -> txn.cancelActive();
        TransactionResult result = txn.execute(request(r));
        assertEquals(TransactionOutcome.CANCELLED, result.outcome());
        assertTrue(r.fetcher.downloads.isEmpty());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void crossStoreRefusedBeforeAnyStagingWrite() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r, DurabilityOps.system(), () -> true, (dir, live) -> false)
                .execute(atomic(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome());
        assertTrue(result.messages().get(0).contains("another file system"), result.messages().toString());
        assertTrue(result.messages().get(0).contains(r.live.toString()), "manual instructions name the live jar");
        assertTrue(r.fetcher.downloads.isEmpty());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void directorySyncNotSupportedDowngradesAtomicReplacementToStagingOnly() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r, noDirectorySync(), () -> true, UpdateDirectory::sameFileStore)
                .execute(atomic(r));
        assertEquals(TransactionOutcome.STAGED_ONLY, result.outcome(), result.messages().toString());
        assertTrue(result.messages().contains(UpdaterTransaction.DOWNGRADED), result.messages().toString());
        assertEquals(1, r.fetcher.downloads.size(), "the staged path still downloads once");
        assertEquals(0, backupCount(r), "no backup on the staging-only path");
        assertEquals(0, journalCount(r), "no journal on the staging-only path");
        assertLiveUntouched(r);
    }

    // ---- step 5: backup-ready ---------------------------------------------------------------

    @Test
    void verifiedStagingOnlyStopsAfterTheStepFiveChecks() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r).execute(request(r, InstallCapability.VERIFIED_STAGING_ONLY));
        assertEquals(TransactionOutcome.STAGED_ONLY, result.outcome(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(result.stagedJar().orElseThrow()));
        assertTrue(result.messages().get(result.messages().size() - 1).contains("install it manually"),
                result.messages().toString());
        assertEquals(0, backupCount(r));
        assertEquals(0, journalCount(r));
        assertLiveUntouched(r);
    }

    @Test
    void atomicPathCopiesTheBackupAndJournalsTheSwap() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r).execute(atomic(r));
        assertEquals(1, backupCount(r));
        assertArrayEquals(LIVE_BYTES, Files.readAllBytes(
                new UpdateDirectory(r.upd, DurabilityOps.system()).backupJar(result.txid().orElseThrow())));
        JournalRecord journal = new UpdateDirectory(r.upd, DurabilityOps.system()).readJournal().orElseThrow();
        // The swap completes in step 6, so the journal ends SWAPPED; the backup and txid stay the same.
        assertEquals(JournalPhase.SWAPPED, journal.phase());
        assertEquals(0, journal.bootAttempts());
        assertEquals(result.txid().orElseThrow(), journal.txid());
        assertTrue(result.messages().stream().anyMatch(m -> m.contains("copy ") && m.contains(r.live.toString())),
                result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
    }

    @Test
    void stagedJarWithAnUnverifiableTrustFileIsRefusedBeforeAnyBackup() throws Exception {
        // One revocation key is required; a trust file with only a release key fails the verifier.
        String releaseOnly = "{\"type\":\"quicraft.trust\",\"v\":1,\"epoch\":1,\"built\":\""
                + SignedFixtures.TS1 + "\",\"revocationsSeqFloor\":0,\"manifestSeqFloor\":0,\"keys\":["
                + "{\"kid\":\"" + TestKeys.KID_1 + "\",\"pub\":\"" + TestKeys.PUB_1
                + "\",\"roles\":[\"release\"]}]}";
        byte[] jar = TransactionFixtures.releaseJar(TransactionFixtures.NEW_VERSION, releaseOnly);
        Rig r = rig(jar, TransactionFixtures.manifestFor(jar, TransactionFixtures.NEW_VERSION));
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome());
        assertTrue(result.messages().get(0).contains("failed verification"), result.messages().toString());
        assertEquals(0, backupCount(r));
        assertEquals(0, journalCount(r));
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void cancelAtTheStepFiveBoundaryDeletesStagingAndJournalAndKeepsTheBackup() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction[] ref = new UpdaterTransaction[1];
        // The second force is the backup copy's; the journal is written after it, then the cancel check.
        DurabilityOps ops = countingForce(() -> ref[0].cancelActive());
        ref[0] = txn(r, ops, () -> true, UpdateDirectory::sameFileStore);
        TransactionResult result = ref[0].execute(atomic(r));
        assertEquals(TransactionOutcome.CANCELLED, result.outcome(), result.messages().toString());
        assertTrue(stagingIsEmpty(r));
        assertEquals(0, journalCount(r), "the journal this attempt wrote is removed");
        assertEquals(1, backupCount(r), "the backup is kept");
        assertLiveUntouched(r);
    }

    @Test
    void cancelAtTheStepFourBoundaryStopsBeforeAnyBackup() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        boolean[] armed = {false};
        r.fetcher.beforeDownload = () -> armed[0] = true;
        r.metadata.onFetch = uri -> {
            if (armed[0] && uri.getPath().endsWith("/manifest.json")) {
                txn.cancelActive();
            }
        };
        TransactionResult result = txn.execute(request(r));
        assertEquals(TransactionOutcome.CANCELLED, result.outcome(), result.messages().toString());
        assertEquals(0, backupCount(r));
        assertEquals(0, journalCount(r));
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    // ---- step 2: downloading ----------------------------------------------------------------

    @Test
    void firstSourceFailsAndSecondSucceedsStagesTheVerifiedJar() throws Exception {
        Rig r = standardRig();
        r.fetcher.failFirst = 1;
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(2, r.fetcher.downloads.size(), r.fetcher.downloads.toString());
        assertTrue(result.stagedJar().isPresent(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(result.stagedJar().get()));
        assertLiveUntouched(r);
    }

    @Test
    void everySourceFailingLeavesNoStagingAndFails() throws Exception {
        Rig r = standardRig();
        r.fetcher.failAll = true;
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome());
        assertEquals(PipelineFixtures.HOSTS.hosts().size(), r.fetcher.downloads.size());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void cancelDuringAFailedDownloadCleansUpAndReportsCancelled() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        r.fetcher.failAll = true;
        r.fetcher.beforeDownload = () -> txn.cancelActive();
        TransactionResult result = txn.execute(request(r));
        assertEquals(TransactionOutcome.CANCELLED, result.outcome());
        assertEquals(1, r.fetcher.downloads.size(), "no further source is tried after cancel");
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    // ---- step 3: verified-staged ------------------------------------------------------------

    @Test
    void sizeMismatchDeletesStaging() throws Exception {
        Rig r = standardRig();
        r.fetcher.body = Arrays.copyOf(r.jar, r.jar.length - 1);
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome());
        assertTrue(result.messages().get(0).contains("size"), result.messages().toString());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void sha512MismatchDeletesStaging() throws Exception {
        Rig r = standardRig();
        byte[] corrupted = r.jar.clone();
        corrupted[100] ^= 1;
        r.fetcher.body = corrupted;
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome());
        assertTrue(result.messages().get(0).contains("SHA-512"), result.messages().toString());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void cancelAfterAFullDownloadIsCaughtAtStepThreeAndStagingIsRemoved() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        r.fetcher.beforeDownload = () -> txn.cancelActive();
        TransactionResult result = txn.execute(request(r));
        assertEquals(TransactionOutcome.CANCELLED, result.outcome());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void verifiedJarIsStagedAndTheLiveJarIsUntouched() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r).execute(request(r));
        assertTrue(result.txid().isPresent());
        assertArrayEquals(r.jar, Files.readAllBytes(result.stagedJar().orElseThrow()));
        assertLiveUntouched(r);
    }

    /** A manifest and jar that replace the served release at the next step 4 revalidation. */
    private record Flip(byte[] manifest, byte[] jar) {
    }

    /**
     * Serves each flip at the first manifest fetch after a download, so the step 4 run sees a
     * changed release while the staged bytes were the old ones.
     */
    private static void flipOnRevalidation(Rig r, Flip... flips) {
        List<Flip> queue = new ArrayList<>(List.of(flips));
        boolean[] armed = {false};
        r.fetcher.beforeDownload = () -> armed[0] = !queue.isEmpty();
        r.metadata.onFetch = uri -> {
            if (armed[0] && uri.getPath().endsWith("/manifest.json")) {
                Flip next = queue.remove(0);
                armed[0] = false;
                try {
                    TransactionFixtures.serveMetadata(r.metadata, PipelineFixtures.revocations(), next.manifest());
                } catch (GeneralSecurityException e) {
                    throw new IllegalStateException(e);
                }
                r.fetcher.body = next.jar();
            }
        };
    }

    private static int stagedCount(Rig r) throws IOException {
        try {
            return new UpdateDirectory(r.upd, DurabilityOps.system()).stagingTxids().size();
        } catch (UpdateRejectedException e) {
            throw new IOException(e);
        }
    }

    // ---- step 4: revalidation ---------------------------------------------------------------

    @Test
    void revalidationRestartsOnceWithANewTxidAndTheFirstStagingIsRemoved() throws Exception {
        Rig r = standardRig();
        byte[] jarB = TransactionFixtures.releaseJar("0.4.1");
        flipOnRevalidation(r, new Flip(TransactionFixtures.manifestFor(jarB, "0.4.1", 43), jarB));
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(2, r.fetcher.downloads.size(), "one download per attempt");
        assertTrue(result.messages().contains(UpdaterTransaction.RESTARTING), result.messages().toString());
        assertEquals(1, stagedCount(r), "only the second attempt's staging remains");
        assertArrayEquals(jarB, Files.readAllBytes(result.stagedJar().orElseThrow()));
        assertLiveUntouched(r);
    }

    @Test
    void secondRevalidationMismatchFailsAndAsksForARerun() throws Exception {
        Rig r = standardRig();
        byte[] jarB = TransactionFixtures.releaseJar("0.4.1");
        byte[] jarC = TransactionFixtures.releaseJar("0.4.2");
        flipOnRevalidation(r,
                new Flip(TransactionFixtures.manifestFor(jarB, "0.4.1", 43), jarB),
                new Flip(TransactionFixtures.manifestFor(jarC, "0.4.2", 44), jarC));
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome());
        assertTrue(result.messages().get(0).contains("run the update command again"), result.messages().toString());
        assertEquals(2, r.fetcher.downloads.size());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void releaseNoLongerOfferedAfterTheStagedDownloadFails() throws Exception {
        Rig r = standardRig();
        flipOnRevalidation(r, new Flip(TransactionFixtures.manifestFor(r.jar, TransactionFixtures.RUNNING_VERSION, 43),
                r.jar));
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome());
        assertTrue(result.messages().get(0).contains("no longer"), result.messages().toString());
        assertEquals(1, r.fetcher.downloads.size());
        assertTrue(stagingIsEmpty(r));
        assertLiveUntouched(r);
    }

    @Test
    void matchingRevalidationContinuesWithTheStagedJar() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r).execute(request(r));
        assertEquals(1, r.fetcher.downloads.size());
        assertArrayEquals(r.jar, Files.readAllBytes(result.stagedJar().orElseThrow()));
        assertFalse(result.messages().contains(UpdaterTransaction.RESTARTING));
    }

    // ---- step 6: locked re-checks, the flip and the move ------------------------------------

    /** Durability ops whose move and sync failures are armed by the test at a chosen boundary. */
    private static final class FlakyOps implements DurabilityOps {
        private final DurabilityOps system = DurabilityOps.system();
        boolean failMove;
        int failSyncs;

        @Override
        public void force(FileChannel ch) throws IOException {
            system.force(ch);
        }

        @Override
        public void atomicMove(Path from, Path to) throws IOException {
            if (failMove) {
                throw new IOException("move refused");
            }
            system.atomicMove(from, to);
        }

        @Override
        public boolean syncDirectory(Path dir) throws IOException {
            if (failSyncs > 0) {
                failSyncs--;
                throw new IOException("sync refused");
            }
            return system.syncDirectory(dir);
        }
    }

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private UpdaterTransaction atomicTxn(Rig r, DurabilityOps ops) {
        return txn(r, ops, () -> true, UpdateDirectory::sameFileStore);
    }

    @Test
    void atomicSwapInstallsTheJarKeepsTheBackupAndMarksTheJournalSwapped() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r).execute(atomic(r));
        assertEquals(TransactionOutcome.INSTALLED_RESTART_PENDING, result.outcome(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
        UpdateDirectory dir = new UpdateDirectory(r.upd, DurabilityOps.system());
        assertArrayEquals(LIVE_BYTES, Files.readAllBytes(dir.backupJar(result.txid().orElseThrow())));
        assertEquals(JournalPhase.SWAPPED, dir.readJournal().orElseThrow().phase());
        assertTrue(stagingIsEmpty(r));
        assertTrue(result.messages().stream().anyMatch(m -> m.contains("copy ")), result.messages().toString());
    }

    @Test
    void syncFailureAfterTheRenameKeepsEverythingAndWarns() throws Exception {
        Rig r = standardRig();
        FlakyOps ops = new FlakyOps();
        UpdaterTransaction txn = atomicTxn(r, ops);
        txn.hooks.afterSwapFlip = () -> ops.failSyncs = 1;
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(TransactionOutcome.INSTALLED_RESTART_PENDING, result.outcome(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
        assertEquals(1, backupCount(r), "the backup is kept");
        assertEquals(JournalPhase.SWAPPED,
                new UpdateDirectory(r.upd, DurabilityOps.system()).readJournal().orElseThrow().phase());
        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith("WARN")), result.messages().toString());
    }

    @Test
    void moveFailureThatMovedNothingCleansUpAndFails() throws Exception {
        Rig r = standardRig();
        FlakyOps ops = new FlakyOps();
        UpdaterTransaction txn = atomicTxn(r, ops);
        txn.hooks.afterSwapFlip = () -> ops.failMove = true;
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertTrue(result.messages().contains(UpdaterTransaction.NOTHING_MOVED_TEXT), result.messages().toString());
        assertTrue(stagingIsEmpty(r));
        assertEquals(0, journalCount(r));
        assertEquals(0, backupCount(r), "the backup this attempt created is removed");
        assertLiveUntouched(r);
    }

    @Test
    void unexpectedLiveJarAfterAFailedMoveKeepsEverything() throws Exception {
        Rig r = standardRig();
        FlakyOps ops = new FlakyOps();
        UpdaterTransaction txn = atomicTxn(r, ops);
        txn.hooks.afterSwapFlip = () -> {
            ops.failMove = true;
            try {
                Files.write(r.live, "neither the old nor the new jar".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertTrue(result.messages().stream().anyMatch(m -> m.contains("recovery")), result.messages().toString());
        assertEquals(1, stagedCount(r), "staging is kept");
        assertEquals(1, journalCount(r), "the journal is kept");
        assertEquals(1, backupCount(r), "the backup is kept");
    }

    @Test
    void cancelBeforeTheFlipWinsAndCleansUpKeepingTheBackup() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        CancelReply[] reply = new CancelReply[1];
        txn.hooks.beforeSwapFlip = () -> reply[0] = txn.cancelActive();
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(CancelReply.REQUESTED, reply[0]);
        assertEquals(TransactionOutcome.CANCELLED, result.outcome(), result.messages().toString());
        assertTrue(stagingIsEmpty(r));
        assertEquals(0, journalCount(r));
        assertEquals(1, backupCount(r));
        assertLiveUntouched(r);
    }

    @Test
    void cancelAfterTheFlipIsReportedAsInstalledAndDeletesNothing() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        CancelReply[] reply = new CancelReply[1];
        txn.hooks.afterSwapFlip = () -> reply[0] = txn.cancelActive();
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(CancelReply.INSTALLED_RESTART_PENDING, reply[0]);
        assertEquals(TransactionOutcome.INSTALLED_RESTART_PENDING, result.outcome(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
        assertEquals(1, backupCount(r));
        assertEquals(JournalPhase.SWAPPED,
                new UpdateDirectory(r.upd, DurabilityOps.system()).readJournal().orElseThrow().phase());
    }

    @Test
    void unrelatedRevocationMergedAfterStepFiveDoesNotRefuseTheSameKeySet() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        // A revocation of an unrelated key merged between step 5 and step 6. The key set does not change,
        // so the key-set check passes against the freshly re-verified manifest and the swap installs.
        String unrelated = Hex.sha256("unrelated-revocation".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String unrelatedKid = Hex.kid(unrelated);
        txn.hooks.afterBackupReady = () -> mergeRevocation(r, unrelatedKid, unrelated);
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(TransactionOutcome.INSTALLED_RESTART_PENDING, result.outcome(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
        assertEquals(1, backupCount(r), "the backup is kept");
    }

    @Test
    void revocationOfTheReleaseSignerMergedAfterStepFiveStopsTheSwap() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        txn.hooks.afterBackupReady = () -> mergeRevocation(r, TestKeys.KID_1, TestKeys.PUB_1);
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertEquals(UpdaterTransaction.RELEASE_NO_LONGER_VERIFIES, result.messages().get(0));
        assertEquals(1, backupCount(r), "the backup is kept");
        assertEquals(0, journalCount(r));
        assertLiveUntouched(r);
    }

    @Test
    void clockAdvancedPastTheManifestExpiryFailsBeforeTheMove() throws Exception {
        Rig r = standardRig();
        MutableClock clock = new MutableClock(PipelineFixtures.NOW);
        UpdaterTransaction txn = new UpdaterTransaction(r.upd, DurabilityOps.system(), r.store, r.pipeline,
                r.fetcher, PipelineFixtures.ENDPOINTS, Map.of(), FetchTimeouts.defaults(), r::discover,
                () -> true, clock, UpdateDirectory::sameFileStore);
        txn.hooks.afterBackupReady = () -> clock.now = Instant.parse(SignedFixtures.TS2).plusSeconds(1);
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertTrue(result.messages().get(0).startsWith(UpdaterTransaction.MANIFEST_REFUSED),
                result.messages().toString());
        assertEquals(0, journalCount(r));
        assertEquals(1, backupCount(r));
        assertLiveUntouched(r);
    }

    @Test
    void stagedJarModifiedAfterStepFiveIsCaughtByTheRehash() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        txn.hooks.afterBackupReady = () -> {
            try {
                UpdateDirectory dir = new UpdateDirectory(r.upd, DurabilityOps.system());
                Path staged = dir.stagingJar(dir.stagingTxids().get(0));
                byte[] bytes = Files.readAllBytes(staged);
                bytes[bytes.length / 2] ^= 1;
                Files.write(staged, bytes);
            } catch (IOException | UpdateRejectedException e) {
                throw new IllegalStateException(e);
            }
        };
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertEquals(UpdaterTransaction.STAGED_CHANGED, result.messages().get(0));
        assertTrue(stagingIsEmpty(r));
        assertEquals(0, journalCount(r));
        assertEquals(1, backupCount(r));
        assertLiveUntouched(r);
    }

    @Test
    void concurrentExecuteIsRefusedWhileATransactionIsActive() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        assertNotNull(txn.tryEnter());
        TransactionResult result = txn.execute(request(r));
        assertEquals(TransactionOutcome.NOT_ADMITTED, result.outcome());
        assertEquals(UpdaterTransaction.BUSY, result.messages().get(0));
        assertTrue(r.metadata.requests.isEmpty());
    }

    // ---- execute() is exception-total ------------------------------------------------------

    @Test
    void executeWhileTheCallerHoldsTheStateLockFailsWithoutThrowing() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        // TransactionLock.tryAcquire refuses a caller that holds the state lock with an IllegalStateException.
        TransactionResult result = r.store.withLockedSnapshot(fresh -> txn.execute(request(r)));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertEquals("the update directory could not be locked", result.messages().get(0));
        assertLiveUntouched(r);
        assertTrue(stagingIsEmpty(r));
        assertEquals(0, journalCount(r));
        assertTrue(r.metadata.requests.isEmpty(), "no network before the lock is taken");
        leaveFree(txn);
    }

    @Test
    void nullRequestIsNotAdmittedAndLeavesTheSlotFree() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        TransactionResult result = txn.execute(null);
        assertEquals(TransactionOutcome.NOT_ADMITTED, result.outcome());
        assertEquals("no update request was given", result.messages().get(0));
        leaveFree(txn);
    }

    @Test
    void unexpectedErrorBeforeTheSwapCleansUpAndFails() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        txn.hooks.afterBackupReady = () -> {
            throw new AssertionError("injected before the swap");
        };
        TransactionResult result = txn.execute(atomic(r));
        assertEquals(TransactionOutcome.FAILED, result.outcome(), result.messages().toString());
        assertTrue(result.messages().get(0).startsWith("the update failed before the swap"),
                result.messages().toString());
        assertTrue(stagingIsEmpty(r));
        assertEquals(0, journalCount(r));
        assertEquals(1, backupCount(r), "the backup is kept");
        assertLiveUntouched(r);
        leaveFree(txn);
    }

    @Test
    void virtualMachineErrorIsNotSwallowedAndTheSlotIsStillReleased() throws Exception {
        Rig r = standardRig();
        UpdaterTransaction txn = txn(r);
        txn.hooks.afterBackupReady = () -> {
            throw new OutOfMemoryError("injected");
        };
        assertThrows(OutOfMemoryError.class, () -> txn.execute(atomic(r)));
        leaveFree(txn);
    }

    /** Asserts the active slot is free (a successful claim), then gives it back. */
    private static void leaveFree(UpdaterTransaction txn) {
        UpdaterTransaction.Active claimed = txn.tryEnter();
        assertNotNull(claimed, "the active slot must be released");
        txn.leave(claimed);
    }

    // ---- the pre-swap rollback line through the progress sink ------------------------------

    private static final String ROLLBACK_PREFIX = "if the update fails after the swap, stop the server, copy ";
    private static final String ROLLBACK_SUFFIX = ", start the server";

    private static UpdateRequest atomic(Rig r, TransactionProgress progress) {
        return new UpdateRequest(r.identity, InstallCapability.ATOMIC_REPLACEMENT, progress);
    }

    @Test
    void rollbackLineIsEmittedBeforeTheFlipAndBeforeStepSix() throws Exception {
        Rig r = standardRig();
        List<String> lines = new ArrayList<>();
        int[] atStepSixStart = {-1};
        int[] atFlip = {-1};
        UpdaterTransaction txn = txn(r);
        txn.hooks.afterBackupReady = () -> {
            atStepSixStart[0] = lines.size();
        };
        txn.hooks.beforeSwapFlip = () -> atFlip[0] = lines.size();
        TransactionResult result = txn.execute(atomic(r, lines::add));
        assertEquals(TransactionOutcome.INSTALLED_RESTART_PENDING, result.outcome(), result.messages().toString());
        assertEquals(1, atStepSixStart[0], "the rollback line is already emitted when step 6 begins");
        assertEquals(1, atFlip[0], "the rollback line is already emitted when the flip happens");
        assertEquals(1, lines.size(), lines.toString());
        String line = lines.get(0);
        assertTrue(line.startsWith(ROLLBACK_PREFIX) && line.endsWith(ROLLBACK_SUFFIX), line);
        assertTrue(line.contains(" over " + r.live.toRealPath() + ","), line);
        assertTrue(result.messages().contains(line), "the same line stays in the result: " + result.messages());
    }

    @Test
    void throwingProgressSinkDoesNotBreakTheTransaction() throws Exception {
        Rig r = standardRig();
        TransactionResult result = txn(r).execute(atomic(r, line -> {
            throw new IllegalStateException("the log sink is broken");
        }));
        assertEquals(TransactionOutcome.INSTALLED_RESTART_PENDING, result.outcome(), result.messages().toString());
        assertArrayEquals(r.jar, Files.readAllBytes(r.live));
        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith(ROLLBACK_PREFIX)),
                result.messages().toString());
    }

    /** Merges one revoked key into the persisted revocations, as a concurrent command would. */
    private static void mergeRevocation(Rig r, String kid, String pub) {
        try {
            r.store.commit(snap -> {
                List<UpdateDocuments.RevokedKey> keys = new ArrayList<>(snap.revoked().revokedKeys());
                keys.add(new UpdateDocuments.RevokedKey(kid, pub));
                return new Snapshot(snap.state(),
                        new RevokedState(keys, snap.revoked().blockedArtifacts()));
            });
        } catch (IOException | UpdateRejectedException e) {
            throw new IllegalStateException(e);
        }
    }
}
