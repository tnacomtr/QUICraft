// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;

/**
 * One check run (docs/updater.md 12.6 and 12.12): collect revocations, commit their restrictions,
 * collect manifests, then evaluate manifests inside a second commit so that a manifest signed by a
 * key revoked in the same run is never a candidate.
 *
 * <p>No lock is held during network collection. Each evaluation runs inside {@link StateStore#commit}
 * on a freshly read snapshot, and the candidate comes from that same evaluation.
 */
final class CheckPipeline {

    private static final String CANCELLED = "cancelled";
    private static final String NOT_INITIALISED = "state store not initialised";

    private final StateStore store;
    private final MetadataCollector collector;
    private final ReleaseHosts hosts;
    private final RuntimeIdentity identity;
    private final Clock clock;

    CheckPipeline(StateStore store, MetadataCollector collector, ReleaseHosts hosts,
            RuntimeIdentity identity, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs one check.
     *
     * @throws IllegalStateException if the state store was never initialised
     */
    CheckResult run(CheckMode mode, UpdateVersion onDiskVersion, CancelToken cancel)
            throws IOException, UpdateRejectedException {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(onDiskVersion, "onDiskVersion");
        Objects.requireNonNull(cancel, "cancel");
        if (store.load().isEmpty()) {
            throw new IllegalStateException(NOT_INITIALISED);
        }

        HostCollection revocationResponses = collector.collectRevocations(hosts, cancel);
        if (cancel.isCancelled()) {
            return cancelled(mode);
        }
        AtomicReference<RevocationAcceptance> revocations = new AtomicReference<>();
        commitRevocations(revocationResponses, revocations);

        HostCollection manifestResponses = collector.collectManifests(hosts, cancel);
        if (cancel.isCancelled()) {
            return cancelled(mode);
        }
        AtomicReference<ManifestAcceptance> manifests = new AtomicReference<>();
        Snapshot finalSnapshot = commitManifests(revocationResponses, manifestResponses, onDiskVersion,
                mode, manifests);

        ManifestAcceptance manifest = manifests.get();
        List<String> diagnostics = new ArrayList<>(revocations.get().diagnostics());
        diagnostics.addAll(manifest.diagnostics());
        boolean fresh = revocationsFresh(finalSnapshot, identity.runningTrust(), clock.instant())
                && manifest.candidate().map(c -> finalSnapshot.state().revocations().seq()
                        >= c.manifest().document().minRevocationsSeq()).orElse(true);
        // A skipped revocations document leaves restrictions unapplied: never offer a candidate then.
        boolean stateFull = manifest.stateFull() || revocations.get().stateFull();
        return new CheckResult(mode, stateFull ? Optional.empty() : manifest.candidate(), stateFull, fresh,
                diagnostics);
    }

    private void commitRevocations(HostCollection responses, AtomicReference<RevocationAcceptance> out)
            throws IOException, UpdateRejectedException {
        try {
            store.commit(snap -> {
                try {
                    RevocationAcceptance accepted = Acceptance.evaluateRevocations(
                            responses, hosts, snap, identity.runningTrust(), clock.instant());
                    out.set(accepted);
                    return new Snapshot(accepted.delta().newState(), accepted.delta().newRevoked());
                } catch (UpdateRejectedException e) {
                    throw new Unchecked(e);
                }
            });
        } catch (Unchecked u) {
            throw unwrap(u);
        }
    }

    private Snapshot commitManifests(HostCollection revocationResponses, HostCollection manifestResponses,
            UpdateVersion onDiskVersion, CheckMode mode, AtomicReference<ManifestAcceptance> out)
            throws IOException, UpdateRejectedException {
        try {
            return store.commit(snap -> {
                try {
                    ManifestAcceptance accepted = Acceptance.evaluateManifests(revocationResponses,
                            manifestResponses, hosts, snap, identity, onDiskVersion, mode, clock.instant());
                    out.set(accepted);
                    return new Snapshot(accepted.delta().newState(), accepted.delta().newRevoked());
                } catch (UpdateRejectedException e) {
                    throw new Unchecked(e);
                }
            });
        } catch (Unchecked u) {
            throw unwrap(u);
        }
    }

    /**
     * Fresh revocation state after the run (12.6 item 3) without the manifest term: the current
     * document is not the seed, is unexpired at the effective time and meets the trust floor. The caller
     * adds {@code minRevocationsSeq} when a candidate exists. Staging itself is gated by
     * {@link Acceptance}, which applies the full test; this flag only reports.
     */
    private static boolean revocationsFresh(Snapshot snap, UpdateDocuments.Trust trust, Instant systemNow)
            throws UpdateRejectedException {
        StateRecords.CurrentRevocations current = snap.state().revocations();
        if (current.seed()) {
            return false;
        }
        Set<String> revokedKids = new HashSet<>();
        for (String pub : snap.revoked().revokedPubs()) {
            revokedKids.add(Hex.kid(pub));
        }
        Instant clockFloor = ClockFloor.of(trust.built(), current, snap.state().seqByKid(), revokedKids);
        Instant effectiveNow = FreshnessPolicy.effectiveNow(systemNow, clockFloor);
        return FreshnessPolicy.unexpired(current.expires(), effectiveNow)
                && current.seq() >= trust.revocationsSeqFloor();
    }

    private static CheckResult cancelled(CheckMode mode) {
        return new CheckResult(mode, Optional.empty(), false, false, List.of(CANCELLED));
    }

    /** Carries a checked exception out of a {@link StateStore#commit} merge function. */
    private static final class Unchecked extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unchecked(UpdateRejectedException cause) {
            super(cause);
        }
    }

    private static RuntimeException unwrap(Unchecked u) throws UpdateRejectedException {
        if (u.getCause() instanceof UpdateRejectedException e) {
            throw e;
        }
        return u;
    }
}
