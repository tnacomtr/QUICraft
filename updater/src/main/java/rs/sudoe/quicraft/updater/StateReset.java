// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;

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
     * Resets the state and refetches.
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

        Probe probe;
        Snapshot readable = null;
        try {
            Optional<Snapshot> loaded = store.load();
            if (loaded.isEmpty()) {
                probe = Probe.NOT_INITIALISED;
            } else {
                probe = Probe.READY;
                readable = loaded.get();
            }
        } catch (StateUnavailableException e) {
            probe = e.revokedValid() ? Probe.READY : Probe.REVOKED_UNREADABLE;
        }
        if (probe == Probe.REVOKED_UNREADABLE) {
            return new ResetResult(false, false, List.of(REVOKED_UNREADABLE));
        }
        if (probe == Probe.NOT_INITIALISED) {
            return new ResetResult(false, false, List.of(NOT_INITIALISED));
        }

        UpdateDocuments.Trust trust = id.runningTrust();
        UpdaterState seed = TrustTransition.apply(
                TrustTransition.classify(null, trust), trust, null).newState();
        if (readable != null) {
            seed = withCarriedWithdrawn(seed, readable, id, onDiskVersion);
        }
        store.resetState(seed);

        CheckResult refetch = pipeline.run(CheckMode.REPORT, onDiskVersion, cancel);
        List<String> messages = new ArrayList<>();
        messages.add("state.json rebuilt from the trust seeds; revoked.json kept");
        messages.addAll(refetch.diagnostics());
        return new ResetResult(true, refetch.freshRevocations() && !refetch.stateFull(), messages);
    }

    private enum Probe {
        READY,
        NOT_INITIALISED,
        REVOKED_UNREADABLE
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
