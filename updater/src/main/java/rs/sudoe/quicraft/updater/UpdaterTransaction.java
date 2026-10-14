// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import rs.sudoe.quicraft.updater.UpdateAdmission.AdmissionContext;
import rs.sudoe.quicraft.updater.UpdateAdmission.AdmissionDecision;
import rs.sudoe.quicraft.updater.UpdateDocuments.ReleaseEntry;

/**
 * Runs one update transaction (docs/updater.md 12.9, 12.10). The only code that replaces the live
 * jar. One transaction at a time per instance; {@link #cancelActive} never takes the transaction lock.
 *
 * <p>The update directory is created before the cheap refusals. Its owner-private staging and backup
 * directories are a harmless side effect of a refused run.
 */
final class UpdaterTransaction {

    /** Where the active transaction is. {@code RUNNING} is the only phase cancel can leave. */
    enum Phase {
        RUNNING,
        CANCEL_REQUESTED,
        SWAPPING
    }

    /** The in-process state of one active transaction: its cancel token and its phase. */
    static final class Active {
        final MutableCancelToken token = new MutableCancelToken();
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);
    }

    /** Whether the update directory and the live jar share one file store (a seam for tests). */
    interface SameStore {
        boolean test(UpdateDirectory dir, Path livePath) throws IOException;
    }

    static final String BUSY = "an update transaction is already active";
    static final String NOT_INITIALISED = "updater state is not initialised";
    static final String JOURNAL_UNREADABLE = "a journal exists but cannot be read";
    static final String CANCELLED_BEFORE_SWAP = "update cancelled before the swap; nothing was installed";
    static final String DOWNGRADED = "directory sync is not supported here; the jar is staged and verified "
            + "but will not be installed";
    static final String SAME_STORE_REFUSED = "the update directory is on another file system than the live "
            + "jar, so the swap cannot be atomic";
    static final String NO_UPDATE = "no newer release is available";
    static final String KEY_SET_REFUSED = "the staged key-set change is not authorized by the backup key";
    static final String STAGED_ONLY_TEXT = "the verified jar is staged but not installed; install it manually: "
            + "stop the server, copy the staged jar over the live jar, start the server. Recovery deletes "
            + "orphan staging on the next start, so install it before restarting";
    static final String NO_LONGER_OFFERED = "the release no longer matches the staged jar; nothing was installed";
    static final String RESTARTING = "the release changed during the check; restarting the update once";
    static final String RELEASE_CHANGED_TWICE = "the release changed again during the update; run the update "
            + "command again";

    private final Path updateDir;
    private final DurabilityOps ops;
    private final StateStore store;
    private final CheckPipeline pipeline;
    private final SourceFetcher fetcher;
    private final ReleaseEndpoints endpoints;
    private final Map<String, RedirectPolicy> redirects;
    private final FetchTimeouts timeouts;
    private final LiveJarDiscovery discovery;
    private final InitBarrier init;
    private final Clock clock;
    private final SameStore sameStore;
    private final AtomicReference<Active> active = new AtomicReference<>();

    UpdaterTransaction(Path updateDir, DurabilityOps ops, StateStore store, CheckPipeline pipeline,
            SourceFetcher fetcher, ReleaseEndpoints endpoints, Map<String, RedirectPolicy> redirects,
            FetchTimeouts timeouts, LiveJarDiscovery discovery, InitBarrier init, Clock clock) {
        this(updateDir, ops, store, pipeline, fetcher, endpoints, redirects, timeouts, discovery, init,
                clock, UpdateDirectory::sameFileStore);
    }

    UpdaterTransaction(Path updateDir, DurabilityOps ops, StateStore store, CheckPipeline pipeline,
            SourceFetcher fetcher, ReleaseEndpoints endpoints, Map<String, RedirectPolicy> redirects,
            FetchTimeouts timeouts, LiveJarDiscovery discovery, InitBarrier init, Clock clock,
            SameStore sameStore) {
        this.updateDir = Objects.requireNonNull(updateDir, "updateDir");
        this.ops = Objects.requireNonNull(ops, "ops");
        this.store = Objects.requireNonNull(store, "store");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.redirects = Map.copyOf(Objects.requireNonNull(redirects, "redirects"));
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.init = Objects.requireNonNull(init, "init");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sameStore = Objects.requireNonNull(sameStore, "sameStore");
    }

    /**
     * Claims the single in-process transaction slot.
     *
     * @return the active record, or {@code null} when another transaction is already active
     */
    Active tryEnter() {
        Active fresh = new Active();
        return active.compareAndSet(null, fresh) ? fresh : null;
    }

    /** Releases the slot claimed by {@link #tryEnter}; a no-op when the slot holds another record. */
    void leave(Active mine) {
        active.compareAndSet(mine, null);
    }

    /**
     * Asks the active transaction to stop before the point of no return. Never takes the transaction
     * lock and never deletes anything itself.
     */
    CancelReply cancelActive() {
        Active a = active.get();
        if (a == null) {
            return CancelReply.NO_ACTIVE_TRANSACTION;
        }
        if (a.phase.compareAndSet(Phase.RUNNING, Phase.CANCEL_REQUESTED)) {
            a.token.cancel();
            return CancelReply.REQUESTED;
        }
        return a.phase.get() == Phase.CANCEL_REQUESTED
                ? CancelReply.REQUESTED
                : CancelReply.INSTALLED_RESTART_PENDING;
    }

    /**
     * Runs the transaction up to the point the later steps will add. Steps 4 to 6 are not written
     * yet: a run that gets past step 3 stops with FAILED and keeps the verified staged jar, so the
     * tests can inspect it.
     */
    TransactionResult execute(UpdateRequest req) {
        Objects.requireNonNull(req, "req");
        Active mine = tryEnter();
        if (mine == null) {
            return TransactionResult.of(TransactionOutcome.NOT_ADMITTED, BUSY);
        }
        try {
            return lockedRun(req, mine.token);
        } finally {
            leave(mine);
        }
    }

    private TransactionResult lockedRun(UpdateRequest req, CancelToken token) {
        TransactionLock.TxnLease lease;
        try {
            lease = TransactionLock.tryAcquire(updateDir);
        } catch (UpdateRejectedException e) {
            return TransactionResult.of(TransactionOutcome.NOT_ADMITTED, messageOf(e));
        } catch (IOException e) {
            return TransactionResult.of(TransactionOutcome.FAILED, "the update directory could not be locked");
        }
        Attempt a = new Attempt(req, token);
        try {
            return runSteps(a);
        } catch (IOException | UpdateRejectedException | RuntimeException e) {
            return abort(a, TransactionOutcome.FAILED, "the update failed before the swap: " + messageOf(e));
        } finally {
            releaseQuietly(lease);
        }
    }

    private TransactionResult runSteps(Attempt a) throws IOException, UpdateRejectedException {
        while (true) {
            TransactionResult stop = checkAndAdmit(a);
            if (stop != null) {
                return stop;
            }
            stop = downloadArtifact(a);
            if (stop != null) {
                return stop;
            }
            stop = verifyStaged(a);
            if (stop != null) {
                return stop;
            }
            stop = revalidate(a);
            if (stop != null) {
                return stop;
            }
            if (!a.restartRequested) {
                return backupReady(a);
            }
            // The release changed under us. Restart once from step 1 under the same lease with a new
            // txid; the first attempt's staging goes first and the backup is never touched.
            a.restartRequested = false;
            if (a.restarts >= 1) {
                return abort(a, TransactionOutcome.FAILED, RELEASE_CHANGED_TWICE);
            }
            a.restarts++;
            cleanupBeforeSwap(a);
            a.messages.add(RESTARTING);
        }
    }

    /**
     * Step 4 (revalidation): a fresh check run must offer a release that matches the staged identity.
     * MATCH continues with the new candidate's evidence. RESTART_REQUIRED asks the caller to restart once.
     * Anything else ends the attempt.
     */
    private TransactionResult revalidate(Attempt a) throws IOException, UpdateRejectedException {
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        CheckResult check = pipeline.run(CheckMode.STAGE, a.live.onDiskVersion(), a.token);
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        if (check.candidate().isEmpty()) {
            return abort(a, TransactionOutcome.FAILED, NO_LONGER_OFFERED);
        }
        AcceptedCandidate fresh = check.candidate().get();
        UpdateDocuments.ReleaseEntry staged = a.candidate.entry();
        ArtifactIdentity stagedIdentity = new ArtifactIdentity(a.request.identity().platform(),
                staged.version(), staged.size(), staged.sha512());
        RevalidationDecision decision = Revalidation.compare(stagedIdentity, fresh.entry(),
                a.request.identity().platform());
        if (decision == RevalidationDecision.MATCH) {
            a.candidate = fresh;
            return null;
        }
        if (decision == RevalidationDecision.RESTART_REQUIRED) {
            a.restartRequested = true;
            return null;
        }
        return abort(a, TransactionOutcome.FAILED, NO_LONGER_OFFERED);
    }

    /** Step 1 (checking): admission, the check pipeline, the second admission and the capability. */
    private TransactionResult checkAndAdmit(Attempt a) throws IOException, UpdateRejectedException {
        a.dir = new UpdateDirectory(updateDir, ops);
        LiveJar before = discovery.discover();
        Optional<StateRecords.Snapshot> snap = store.load();
        if (snap.isEmpty()) {
            return notAdmitted(List.of(NOT_INITIALISED));
        }
        // Cheap refusals first: no pipeline run and no network when these fail.
        AdmissionDecision pre = admit(a, before, snap.get(), false);
        if (!pre.admitted()) {
            return notAdmitted(pre.reasons());
        }
        CheckResult check = pipeline.run(CheckMode.STAGE, before.onDiskVersion(), a.token);
        LiveJar live = discovery.discover();
        Optional<StateRecords.Snapshot> fresh = store.load();
        if (fresh.isEmpty()) {
            return notAdmitted(List.of(NOT_INITIALISED));
        }
        // The second decision is authoritative: it uses the state the run just merged.
        AdmissionDecision post = admit(a, live, fresh.get(), check.stateFull());
        if (!post.admitted()) {
            return notAdmitted(post.reasons());
        }
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        if (check.candidate().isEmpty()) {
            List<String> out = new ArrayList<>();
            out.add(NO_UPDATE);
            out.addAll(check.diagnostics());
            return new TransactionResult(TransactionOutcome.NO_UPDATE, Optional.empty(), Optional.empty(),
                    Optional.empty(), out);
        }
        InstallCapability capability = a.request.capability();
        if (capability == InstallCapability.ATOMIC_REPLACEMENT && !a.dir.directorySyncSupported()) {
            capability = InstallCapability.VERIFIED_STAGING_ONLY;
            a.messages.add(DOWNGRADED);
        }
        if (capability == InstallCapability.ATOMIC_REPLACEMENT && !sameStore.test(a.dir, live.realPath())) {
            return abort(a, TransactionOutcome.FAILED, SAME_STORE_REFUSED
                    + "; install it manually: stop the server, copy the release jar over " + live.realPath()
                    + ", start the server");
        }
        a.live = live;
        a.capability = capability;
        a.candidate = check.candidate().get();
        return null;
    }

    /** Builds the admission context from the freshly read facts and applies the admission rules. */
    private AdmissionDecision admit(Attempt a, LiveJar live, StateRecords.Snapshot snap, boolean stateFull)
            throws IOException, UpdateRejectedException {
        boolean journalPresent;
        try {
            journalPresent = a.dir.readJournal().isPresent();
        } catch (UpdateRejectedException e) {
            // A journal that exists but cannot be read still blocks the update.
            return new AdmissionDecision(false, List.of(JOURNAL_UNREADABLE));
        }
        AdmissionContext ctx = new AdmissionContext(init.readyForStaging(), journalPresent,
                a.request.identity().liveSha512AtInit(), live.sha512(), stateFull);
        return UpdateAdmission.admit(ctx, a.request.identity(), snap);
    }

    /** Step 2 (downloading): the part file, then each source in order until one delivers. */
    private TransactionResult downloadArtifact(Attempt a) throws IOException {
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        a.txid = UpdateDirectory.newTxid();
        a.part = a.dir.createPart(a.txid);
        ReleaseEntry entry = a.candidate.entry();
        for (ReleaseHost host : a.candidate.artifactSources()) {
            try {
                FetchRequest request = new FetchRequest(host, endpoints.artifact(host, entry),
                        entry.size() + 1, timeouts.connect(), timeouts.idle(), timeouts.overall(),
                        redirects.getOrDefault(host.key(), RedirectPolicy.denyAll()));
                fetcher.download(request, a.part, a.token);
                return null;
            } catch (FetchException | IllegalArgumentException e) {
                a.part.truncate(0);
                a.part.position(0);
                if (a.token.isCancelled()) {
                    return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
                }
                a.messages.add("release source " + host.key() + " did not deliver the artifact");
            }
        }
        return abort(a, TransactionOutcome.FAILED, "no release source delivered the artifact");
    }

    /** Step 3 (verified-staged): size and SHA-512 against the manifest, then the rename to .jar. */
    private TransactionResult verifyStaged(Attempt a) throws IOException {
        ReleaseEntry entry = a.candidate.entry();
        Path part = a.dir.stagingPart(a.txid);
        if (Files.size(part) != entry.size()) {
            return abort(a, TransactionOutcome.FAILED, "the downloaded size does not match the release manifest");
        }
        if (!UpdateDirectory.sha512Of(part).equals(entry.sha512())) {
            return abort(a, TransactionOutcome.FAILED,
                    "the downloaded SHA-512 does not match the release manifest");
        }
        a.dir.commitPart(a.txid, a.part);
        a.part = null;
        a.stagedJar = a.dir.stagingJar(a.txid);
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        return null;
    }

    /**
     * Step 5 (backup-ready): authenticate the staged jar against the state, then either stop as
     * STAGED_ONLY or copy the live jar to the backup and write the prepared journal.
     */
    private TransactionResult backupReady(Attempt a) throws IOException, UpdateRejectedException {
        Optional<StateRecords.Snapshot> loaded = store.load();
        if (loaded.isEmpty()) {
            return abort(a, TransactionOutcome.FAILED, NOT_INITIALISED);
        }
        StateRecords.Snapshot snap = loaded.get();
        StagedArtifact staged;
        try {
            staged = ArtifactVerifier.verify(a.stagedJar, a.candidate, a.request.identity(),
                    a.live.onDiskVersion(), snap);
        } catch (UpdateRejectedException e) {
            return abort(a, TransactionOutcome.FAILED, "the staged jar failed verification: " + messageOf(e));
        }
        TrustSnapshot current = TrustSnapshot.of(a.request.identity().runningTrust(),
                snap.revoked().revokedPubs());
        if (!KeySetChangeAuthorization.check(a.candidate.manifest(), staged.stagedKeySetFingerprint(),
                snap.state().persistedTrust(), current)) {
            return abort(a, TransactionOutcome.FAILED, KEY_SET_REFUSED);
        }
        a.staged = staged;
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        if (a.capability == InstallCapability.VERIFIED_STAGING_ONLY) {
            List<String> out = new ArrayList<>(a.messages);
            out.add(STAGED_ONLY_TEXT + " Staged jar: " + a.stagedJar + "; live jar: " + a.live.realPath());
            return new TransactionResult(TransactionOutcome.STAGED_ONLY, Optional.of(a.txid),
                    Optional.of(a.stagedJar), Optional.empty(), out);
        }
        String backupSha = a.dir.copyBackup(a.txid, a.live.realPath());
        Path backup = a.dir.backupJar(a.txid);
        if (!backupSha.equals(a.live.sha512())) {
            a.dir.deleteBackup(a.txid);
            return abort(a, TransactionOutcome.FAILED, "the backup copy does not match the live jar");
        }
        UpdateDocuments.ReleaseEntry entry = a.candidate.entry();
        JournalRecord journal = new JournalRecord(a.txid, JournalPhase.PREPARED,
                a.request.identity().platform(), a.candidate.manifest().document().seq(),
                new JournalLive(a.live.realPath().toString(), a.live.size(), a.live.sha512(),
                        a.live.onDiskVersion()),
                new JournalStaged(entry.size(), entry.sha512(), entry.version()),
                backupSha, clock.instant(), 0);
        a.journalWritten = true;
        a.dir.writeJournal(journal);
        a.journal = journal;
        a.messages.add("if the update fails after the swap, stop the server, copy " + backup + " over "
                + a.live.realPath() + ", start the server");
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        return stopBeforeStepSix(a);
    }

    /** Placeholder until step 6 lands: the staged jar, the backup and the journal stay for inspection. */
    private TransactionResult stopBeforeStepSix(Attempt a) {
        List<String> out = new ArrayList<>(a.messages);
        out.add("step 6 is not implemented in this build");
        return new TransactionResult(TransactionOutcome.FAILED, Optional.of(a.txid),
                Optional.of(a.stagedJar), Optional.empty(), out);
    }

    /**
     * Ends the attempt before the point of no return: closes the part file, deletes staging, then the
     * journal when this attempt wrote one. The backup is never touched here.
     */
    private TransactionResult abort(Attempt a, TransactionOutcome outcome, String message) {
        List<String> out = new ArrayList<>();
        out.add(message);
        out.addAll(a.messages);
        cleanupBeforeSwap(a, out);
        return new TransactionResult(outcome, Optional.ofNullable(a.txid), Optional.empty(),
                Optional.empty(), out);
    }

    /** Removes this attempt's staging files and the journal it wrote; keeps the backup. */
    private void cleanupBeforeSwap(Attempt a) {
        cleanupBeforeSwap(a, new ArrayList<>());
    }

    private void cleanupBeforeSwap(Attempt a, List<String> out) {
        closeQuietly(a.part);
        a.part = null;
        if (a.dir != null && a.txid != null) {
            try {
                a.dir.deleteStaging(a.txid);
            } catch (IOException e) {
                out.add("staging files could not be removed; delete them from the update directory");
            }
            if (a.journalWritten) {
                try {
                    a.dir.deleteJournal();
                } catch (IOException e) {
                    out.add("the journal could not be removed; delete it from the update directory");
                }
            }
        }
        a.txid = null;
        a.stagedJar = null;
    }

    private static TransactionResult notAdmitted(List<String> reasons) {
        return TransactionResult.of(TransactionOutcome.NOT_ADMITTED,
                "update not admitted: " + String.join("; ", reasons));
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) {
            return;
        }
        try {
            ch.close();
        } catch (IOException ignored) {
            // The file is closed by the process at worst; the caller removes the file by name.
        }
    }

    private static void releaseQuietly(TransactionLock.TxnLease lease) {
        try {
            lease.close();
        } catch (IOException ignored) {
            // The OS releases the file lock when the process exits; a busy lock is reported next time.
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Per-command state carried through the steps. */
    private static final class Attempt {
        final UpdateRequest request;
        final CancelToken token;
        final List<String> messages = new ArrayList<>();
        UpdateDirectory dir;
        LiveJar live;
        InstallCapability capability;
        AcceptedCandidate candidate;
        String txid;
        FileChannel part;
        Path stagedJar;
        boolean journalWritten;
        boolean restartRequested;
        int restarts;
        StagedArtifact staged;
        JournalRecord journal;

        Attempt(UpdateRequest request, CancelToken token) {
            this.request = request;
            this.token = token;
        }
    }
}
