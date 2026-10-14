// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    static final String SAME_STORE_DOWNGRADED = "the update directory is on another file system than the live "
            + "jar, so the live swap was skipped; the verified jar is staged instead";
    static final String NO_UPDATE = "no newer release is available";
    static final String RELEASE_NO_LONGER_VERIFIES = "the release manifest no longer verifies under the current "
            + "revocations";
    static final String MANIFEST_REFUSED = "the release manifest is refused under the current state: ";
    static final String REVOCATIONS_STALE = "the revocation state is no longer fresh";
    static final String NOT_LISTED = "the verified manifest no longer lists the staged jar for this target";
    static final String BLOCKED = "the staged jar is blocked by a revocation";
    static final String WITHDRAWN = "the staged jar was withdrawn";
    static final String STAGED_CHANGED = "the staged jar changed after verification";
    static final String LIVE_CHANGED = "the live jar changed during the update; nothing was installed";
    static final String ROLLBACK_NOT_LOGGED = "the rollback instructions could not be logged; nothing was installed";
    static final String MOVED_MISMATCH = "WARN: the live jar does not match the staged jar after the move; "
            + "nothing was deleted; recovery will check the installed jar at next start";
    static final String NOTHING_MOVED_TEXT = "the swap did not happen; nothing was installed";
    static final String RECOVERY_TEXT = "the swap may have started; nothing was deleted; recovery will check the "
            + "installed jar at next start";
    static final String DURABILITY_UNCERTAIN = "WARN: the live jar was replaced but the directory sync failed; "
            + "durability is uncertain; recovery will check the installed jar at next start";
    static final String JOURNAL_SWAP_FAILED = "the journal could not be marked swapped; recovery will check the "
            + "installed jar at next start";
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

    /** Test seams at the step 6 boundaries. Production leaves them empty. */
    static final class Hooks {
        Runnable afterBackupReady = () -> { };
        Runnable beforeSwapFlip = () -> { };
        Runnable afterSwapFlip = () -> { };
    }

    final Hooks hooks = new Hooks();

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
     * Runs one update command from step 1 to the installed-pending-restart step (docs/updater.md 12.10).
     * Never throws except for a {@link VirtualMachineError}: any other runtime failure or error becomes
     * a FAILED result. A null request is NOT_ADMITTED. The active slot is always released. Failures
     * before the swap clean up this attempt's staging and journal (the backup is kept); a failure after
     * the swap started deletes nothing and leaves the check to startup recovery.
     */
    TransactionResult execute(UpdateRequest req) {
        if (req == null) {
            return TransactionResult.of(TransactionOutcome.NOT_ADMITTED, "no update request was given");
        }
        Active mine = tryEnter();
        if (mine == null) {
            return TransactionResult.of(TransactionOutcome.NOT_ADMITTED, BUSY);
        }
        try {
            return lockedRun(req, mine);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (RuntimeException | Error e) {
            return TransactionResult.of(TransactionOutcome.FAILED,
                    "the update stopped unexpectedly: " + messageOf(e) + "; files were left in place for "
                            + "startup recovery to check");
        } finally {
            leave(mine);
        }
    }

    private TransactionResult lockedRun(UpdateRequest req, Active mine) {
        TransactionLock.TxnLease lease;
        try {
            lease = TransactionLock.tryAcquire(updateDir);
        } catch (UpdateRejectedException e) {
            return TransactionResult.of(TransactionOutcome.NOT_ADMITTED, messageOf(e));
        } catch (IOException | RuntimeException e) {
            return TransactionResult.of(TransactionOutcome.FAILED, "the update directory could not be locked");
        }
        Attempt a = new Attempt(req, mine);
        try {
            return runSteps(a);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (IOException | UpdateRejectedException | RuntimeException | Error e) {
            if (a.swapStarted) {
                // Past the point of no return nothing is deleted; recovery checks the installed jar.
                return new TransactionResult(TransactionOutcome.FAILED, Optional.ofNullable(a.txid),
                        Optional.empty(), Optional.empty(), List.of(RECOVERY_TEXT, messageOf(e)));
            }
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
            // A move across file stores cannot be atomic (12.14). Downgrade like the fsync case above:
            // the jar is downloaded, verified and staged, and the outcome STAGED_ONLY names the staged
            // jar and the manual steps. Refusing here would name a release jar this run never fetched.
            capability = InstallCapability.VERIFIED_STAGING_ONLY;
            a.messages.add(SAME_STORE_DOWNGRADED);
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
        // Logged before anything else of step 6 so a crash after the move still leaves the command on screen.
        String rollback = "if the update fails after the swap, stop the server, copy " + backup + " over "
                + a.live.realPath() + ", start the server";
        a.messages.add(rollback);
        if (!emit(a, rollback)) {
            // Without the manual rollback command on record, nothing may be installed.
            a.dir.deleteBackup(a.txid);
            return abort(a, TransactionOutcome.FAILED, ROLLBACK_NOT_LOGGED);
        }
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        return installPending(a);
    }

    /**
     * Step 6 (installed-pending-restart). Under the state lock, with no network I/O, re-authorise from
     * the freshly read state, re-hash the staged jar, flip to SWAPPING and move it over the live jar.
     * Nothing is deleted after the flip starts.
     */
    private TransactionResult installPending(Attempt a) throws IOException, UpdateRejectedException {
        hooks.afterBackupReady.run();
        if (a.token.isCancelled()) {
            return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
        }
        Swap swap = store.withLockedSnapshot(fresh -> swapUnderLock(a, fresh));
        List<String> out = new ArrayList<>(a.messages);
        out.addAll(swap.messages());
        Optional<Path> backup = Optional.of(a.dir.backupJar(a.txid));
        switch (swap.kind()) {
            case INSTALLED:
                return new TransactionResult(TransactionOutcome.INSTALLED_RESTART_PENDING,
                        Optional.of(a.txid), Optional.empty(), backup, out);
            case KEPT:
                return new TransactionResult(TransactionOutcome.FAILED, Optional.of(a.txid),
                        Optional.empty(), backup, out);
            case CANCELLED:
                return abort(a, TransactionOutcome.CANCELLED, CANCELLED_BEFORE_SWAP);
            case LIVE_CHANGED:
                // Nothing moved; this attempt's backup is not needed either.
                a.dir.deleteBackup(a.txid);
                return abort(a, TransactionOutcome.FAILED, LIVE_CHANGED);
            case NOTHING_MOVED:
                // The move did not happen, so the backup this attempt created is not needed.
                a.dir.deleteBackup(a.txid);
                return abort(a, TransactionOutcome.FAILED, NOTHING_MOVED_TEXT);
            default:
                return abort(a, TransactionOutcome.FAILED, swap.messages().get(0));
        }
    }

    /** Runs under the state lock: the re-authorisation, the flip and the move. No network. */
    private Swap swapUnderLock(Attempt a, StateRecords.Snapshot fresh) throws IOException, UpdateRejectedException {
        String rejection = authorizeForSwap(a, fresh);
        if (rejection != null) {
            return new Swap(SwapKind.REJECTED, List.of(rejection));
        }
        hooks.beforeSwapFlip.run();
        if (!liveUnchanged(a)) {
            return new Swap(SwapKind.LIVE_CHANGED, List.of(LIVE_CHANGED));
        }
        if (!a.active.phase.compareAndSet(Phase.RUNNING, Phase.SWAPPING)) {
            return new Swap(SwapKind.CANCELLED, List.of(CANCELLED_BEFORE_SWAP));
        }
        a.swapStarted = true;
        hooks.afterSwapFlip.run();
        try {
            a.dir.moveOverLive(a.txid, a.live.realPath());
        } catch (IOException moveFailure) {
            return classifyFailedMove(a);
        }
        // The move returned, but the live path must now hold the staged bytes. Anything else is kept
        // for the operator and recovery, and the journal stays as it is.
        if (!liveHoldsStaged(a)) {
            return new Swap(SwapKind.KEPT, List.of(MOVED_MISMATCH));
        }
        return journalSwapped(a, List.of());
    }

    /**
     * Re-authorises the staged release from the freshly read state. Returns the refusal reason, or null
     * when every check passes. The checks: the manifest verifies under the fresh trust snapshot, its
     * times and sequence are accepted, the revocations are fresh, the staged identity is still listed
     * for this target, the artifact is neither blocked nor withdrawn, the staged trust still passes,
     * the key-set change is authorised under the fresh snapshot, and the staged bytes are unchanged.
     */
    private String authorizeForSwap(Attempt a, StateRecords.Snapshot fresh) throws IOException, UpdateRejectedException {
        UpdateDocuments.Trust running = a.request.identity().runningTrust();
        TrustSnapshot currentTrust = TrustSnapshot.of(running, fresh.revoked().revokedPubs());
        AcceptedCandidate candidate = a.candidate;
        DocumentVerification<UpdateDocuments.ReleaseManifest> verification = AuthenticatedDocuments.verifyRelease(
                candidate.evidence().exactManifestBytes(), candidate.evidence().exactSignatureBytes(),
                currentTrust);
        if (verification.outcome() != VerificationOutcome.VERIFIED) {
            return RELEASE_NO_LONGER_VERIFIES;
        }
        VerifiedDocument<UpdateDocuments.ReleaseManifest> reverified = verification.verified();
        Instant systemNow = clock.instant();
        Set<String> revokedKids = new HashSet<>();
        for (String pub : fresh.revoked().revokedPubs()) {
            revokedKids.add(Hex.kid(pub));
        }
        Instant clockFloor = ClockFloor.of(running.built(), fresh.state().revocations(),
                fresh.state().seqByKid(), revokedKids);
        Instant effectiveNow = FreshnessPolicy.effectiveNow(systemNow, clockFloor);
        SequencePolicy.ManifestDecision decision = SequencePolicy.manifestDecision(reverified, fresh, running,
                currentTrust, systemNow, effectiveNow);
        if (decision != SequencePolicy.ManifestDecision.ACCEPT
                && decision != SequencePolicy.ManifestDecision.REPLAY_SAME) {
            return MANIFEST_REFUSED + decision;
        }
        if (!SequencePolicy.freshRevocations(fresh, reverified.document(), running, effectiveNow)) {
            return REVOCATIONS_STALE;
        }
        ArtifactIdentity staged = a.staged.identity();
        boolean listed = false;
        for (ReleaseEntry e : reverified.document().releases()) {
            if (e.platform().equals(staged.platform()) && e.version().equals(staged.version())
                    && e.size() == staged.size() && e.sha512().equals(staged.sha512())
                    && e.targets().contains(a.request.identity().target())) {
                listed = true;
            }
        }
        if (!listed) {
            return NOT_LISTED;
        }
        if (fresh.revoked().blockedArtifacts().contains(staged.sha512())) {
            return BLOCKED;
        }
        for (StateRecords.WithdrawnEntry w : fresh.state().withdrawn()) {
            if (w.sha512().equals(staged.sha512())) {
                return WITHDRAWN;
            }
        }
        try {
            ArtifactVerifier.validateStagedTrust(a.staged.stagedTrust(), fresh);
        } catch (UpdateRejectedException e) {
            return messageOf(e);
        }
        // The manifest just verified under the fresh trust snapshot. Using the step 4 evidence instead would
        // refuse every revocation merged since then, even one for an unrelated key.
        if (!KeySetChangeAuthorization.check(reverified, a.staged.stagedKeySetFingerprint(),
                fresh.state().persistedTrust(), currentTrust)) {
            return KEY_SET_REFUSED;
        }
        Path jar = a.dir.stagingJar(a.txid);
        if (Files.size(jar) != staged.size() || !UpdateDirectory.sha512Of(jar).equals(staged.sha512())) {
            return STAGED_CHANGED;
        }
        return null;
    }

    /**
     * The move threw. Re-reads the live jar: equal to the staged jar means the move happened; equal to
     * the pre-move jar means nothing moved; anything else, or a failed read, keeps every file.
     */
    private Swap classifyFailedMove(Attempt a) throws UpdateRejectedException {
        String now;
        try {
            now = UpdateDirectory.sha512Of(a.live.realPath());
        } catch (IOException readFailure) {
            return new Swap(SwapKind.KEPT, List.of(RECOVERY_TEXT));
        }
        if (now.equals(a.staged.identity().sha512())) {
            return journalSwapped(a, List.of(DURABILITY_UNCERTAIN));
        }
        if (now.equals(a.live.sha512())) {
            return new Swap(SwapKind.NOTHING_MOVED, List.of());
        }
        return new Swap(SwapKind.KEPT, List.of(RECOVERY_TEXT));
    }

    /** Records the swap in the journal. A failed write keeps the installed jar and says so. */
    private Swap journalSwapped(Attempt a, List<String> notes) {
        List<String> out = new ArrayList<>(notes);
        try {
            a.dir.writeJournal(a.journal.withPhase(JournalPhase.SWAPPED));
        } catch (IOException writeFailure) {
            out.add(JOURNAL_SWAP_FAILED);
        }
        return new Swap(SwapKind.INSTALLED, out);
    }

    /** True when the live jar, read back after the move, has the staged SHA-512. A read failure counts as no. */
    private static boolean liveHoldsStaged(Attempt a) {
        try {
            return a.staged.identity().sha512().equals(UpdateDirectory.sha512Of(a.live.realPath()));
        } catch (IOException readFailure) {
            return false;
        }
    }

    /**
     * True when the live jar still has the SHA-512 this process read at step 1. A read failure counts as
     * a change: the swap is refused when the live jar cannot be checked.
     */
    private static boolean liveUnchanged(Attempt a) {
        try {
            return a.live.sha512().equals(UpdateDirectory.sha512Of(a.live.realPath()));
        } catch (IOException readFailure) {
            return false;
        }
    }

    private enum SwapKind {
        REJECTED,
        CANCELLED,
        LIVE_CHANGED,
        NOTHING_MOVED,
        INSTALLED,
        KEPT
    }

    /** The result of the locked step 6 section, decided before anything outside the lock runs. */
    private record Swap(SwapKind kind, List<String> messages) {
        Swap {
            messages = List.copyOf(messages);
        }
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
        } catch (IOException | RuntimeException ignored) {
            // The OS releases the file lock when the process exits; a busy lock is reported next time.
        }
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Sends one progress line. Returns false when the sink throws (a RuntimeException or a non-VM Error);
     * the caller decides what that means for the transaction.
     */
    private static boolean emit(Attempt a, String line) {
        try {
            a.request.progress().line(line);
            return true;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    /** Per-command state carried through the steps. */
    private static final class Attempt {
        final UpdateRequest request;
        final Active active;
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
        boolean swapStarted;
        StagedArtifact staged;
        JournalRecord journal;

        Attempt(UpdateRequest request, Active active) {
            this.request = request;
            this.active = active;
            this.token = active.token;
        }
    }
}
