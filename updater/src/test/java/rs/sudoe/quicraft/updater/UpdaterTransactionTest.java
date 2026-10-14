// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.PipelineFixtures.ScriptedFetcher;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.TransactionFixtures.ArtifactFetcher;
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

    private static UpdateRequest request(Rig r) {
        return new UpdateRequest(r.identity, InstallCapability.ATOMIC_REPLACEMENT);
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
                .execute(request(r));
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
                .execute(request(r));
        assertTrue(result.messages().contains(UpdaterTransaction.DOWNGRADED), result.messages().toString());
        assertEquals(1, r.fetcher.downloads.size(), "the staged path still downloads once");
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
}
