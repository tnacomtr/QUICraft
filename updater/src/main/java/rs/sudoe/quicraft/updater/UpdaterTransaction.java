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
        return stopBeforeStepFour(a);
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

    /** Placeholder until steps 4 to 6 land: the verified jar stays in staging for inspection. */
    private TransactionResult stopBeforeStepFour(Attempt a) {
        List<String> out = new ArrayList<>(a.messages);
        out.add("steps 4 to 6 are not implemented in this build");
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
        return new TransactionResult(outcome, Optional.ofNullable(a.txid), Optional.empty(),
                Optional.empty(), out);
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

        Attempt(UpdateRequest request, CancelToken token) {
            this.request = request;
            this.token = token;
        }
    }
}
