// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;
import rs.sudoe.quicraft.updater.StateStore.ResetDecision;
import rs.sudoe.quicraft.updater.StateStore.ResetInput;
import rs.sudoe.quicraft.updater.TrustTransition.Transition;

/**
 * The {@code /quicraft update reset-state} command (docs/updater.md 12.8). Rebuilds
 * {@code state.json} from the running trust seeds, keeps a valid {@code revoked.json} byte for byte,
 * and refetches revocations and manifests.
 *
 * <p>Known limit: an unreadable {@code revoked.json} is never rebuilt here. The reset writes nothing
 * and the updater stays disabled until the owner restores the file; a rebuild from fetched
 * revocations is deferred. Staging is enabled only when the refetch left fresh revocation state that
 * fits the persisted caps.
 */
final class StateReset {

    static final String REVOKED_UNREADABLE = "revoked.json is unreadable; restore it from a backup or "
            + "reinstall; the updater stays disabled";
    static final String NOT_INITIALISED = "state store not initialised; nothing to reset";
    static final String REFUSED_OLDER_EPOCH = "reset refused: the running jar's trust file has an older epoch "
            + "than the persisted state; install the current release first";
    static final String REFUSED_SAME_EPOCH_CONFLICT = "reset refused: the running jar's trust file conflicts "
            + "with the persisted key set at the same epoch";
    static final String REFUSED_TRUST_NOT_APPLIED = "reset refused: the running jar's trust file has not been "
            + "applied yet; restart the server first";

    private StateReset() {
    }

    /**
     * Result of the reset.
     *
     * @param stateReset true when {@code state.json} was rebuilt
     * @param stagingEnabled true when the refetch left fresh revocation state
     * @param messages fixed messages for the owner
     */
    record ResetResult(boolean stateReset, boolean stagingEnabled, List<String> messages) {
        ResetResult {
            messages = List.copyOf(messages);
        }
    }

    /**
     * Resets the state and refetches. The decision is taken by {@link #plan} under the same lock
     * acquisition that writes the state, so no commit can land between the read and the write.
     *
     * @throws IOException on a filesystem failure
     * @throws UpdateRejectedException when the store rejects the reset or the refetch
     */
    static ResetResult resetStateAndRefetch(StateStore store, CheckPipeline pipeline, RuntimeIdentity id,
            UpdateVersion onDiskVersion, CancelToken cancel) throws IOException, UpdateRejectedException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(onDiskVersion, "onDiskVersion");
        Objects.requireNonNull(cancel, "cancel");

        ResetDecision decision = store.resetLocked(input -> plan(input, id, onDiskVersion));
        if (decision.rebuilt().isEmpty()) {
            return new ResetResult(false, false, decision.refusals());
        }

        CheckResult refetch = pipeline.run(CheckMode.REPORT, onDiskVersion, cancel);
        List<String> messages = new ArrayList<>();
        messages.add("state.json rebuilt from the trust seeds; revoked.json kept");
        messages.addAll(refetch.diagnostics());
        return new ResetResult(true, refetch.freshRevocations() && !refetch.stateFull(), messages);
    }

    /**
     * Decides a reset from what the files held under the lock. Pure: it writes nothing and calls no
     * store method.
     *
     * <ul>
     *   <li>No state files at all: refused as not initialised.</li>
     *   <li>No valid {@code revoked.json}: refused as unreadable.</li>
     *   <li>A readable {@code state.json} whose trust is not NORMAL against the running trust file:
     *       refused. A higher epoch is refused because the trust must be applied first, never
     *       reseeded over.</li>
     *   <li>Otherwise rebuilt from the trust seed, keeping the withdrawn entries that still apply.</li>
     * </ul>
     */
    static ResetDecision plan(ResetInput input, RuntimeIdentity id, UpdateVersion onDiskVersion)
            throws UpdateRejectedException {
        if (!input.anyFileExists()) {
            return ResetDecision.refuse(NOT_INITIALISED);
        }
        if (input.revoked().isEmpty()) {
            return ResetDecision.refuse(REVOKED_UNREADABLE);
        }
        UpdateDocuments.Trust trust = id.runningTrust();
        UpdaterState seed = TrustTransition.apply(
                TrustTransition.classify(null, trust), trust, null).newState();
        if (input.state().isEmpty()) {
            return ResetDecision.write(seed);
        }
        Snapshot readable = new Snapshot(input.state().get(), input.revoked().get());
        Transition transition = TrustTransition.classify(readable.state().persistedTrust(), trust).transition();
        if (transition == Transition.OLDER_EPOCH_REPORT_ONLY) {
            return ResetDecision.refuse(REFUSED_OLDER_EPOCH);
        }
        if (transition == Transition.INVALID_SAME_EPOCH) {
            return ResetDecision.refuse(REFUSED_SAME_EPOCH_CONFLICT);
        }
        if (transition != Transition.NORMAL) {
            return ResetDecision.refuse(REFUSED_TRUST_NOT_APPLIED);
        }
        return ResetDecision.write(withCarriedWithdrawn(seed, readable, id, onDiskVersion));
    }

    /**
     * Keeps the withdrawn entries of a readable state that still apply: the kid is not revoked and the
     * version is newer than both the running version and the on-disk version. Everything else is
     * rebuilt from the trust seed, as before.
     */
    private static UpdaterState withCarriedWithdrawn(UpdaterState seed, Snapshot readable, RuntimeIdentity id,
            UpdateVersion onDiskVersion) {
        UpdateVersion floor = id.runningVersion().compareTo(onDiskVersion) >= 0
                ? id.runningVersion() : onDiskVersion;
        Set<String> revokedKids = readable.revoked().revokedKeys().stream()
                .map(UpdateDocuments.RevokedKey::kid)
                .collect(Collectors.toSet());
        List<WithdrawnEntry> kept = readable.state().withdrawn().stream()
                .filter(w -> !revokedKids.contains(w.kid()))
                .filter(w -> w.version().compareTo(floor) > 0)
                .toList();
        return new UpdaterState(seed.epoch(), seed.keySetFingerprint(), seed.revocationKey(),
                seed.revocations(), seed.seqByKid(), kept, seed.lastAnnounced(), seed.warnedBad(),
                seed.tornSince());
    }
}
