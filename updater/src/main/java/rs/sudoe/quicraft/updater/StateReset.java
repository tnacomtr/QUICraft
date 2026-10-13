// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;

/**
 * The {@code /quicraft update reset-state} command (docs/updater.md 12.8). Rebuilds
 * {@code state.json} from the running trust seeds, keeps a valid {@code revoked.json} byte for byte,
 * and refetches revocations and manifests.
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

        Probe probe = probe(store);
        if (probe == Probe.REVOKED_UNREADABLE) {
            return new ResetResult(false, false, List.of(REVOKED_UNREADABLE));
        }
        if (probe == Probe.NOT_INITIALISED) {
            return new ResetResult(false, false, List.of(NOT_INITIALISED));
        }

        UpdateDocuments.Trust trust = id.runningTrust();
        UpdaterState seed = TrustTransition.apply(
                TrustTransition.classify(null, trust), trust, null).newState();
        store.resetState(seed);

        CheckResult refetch = pipeline.run(CheckMode.REPORT, onDiskVersion, cancel);
        List<String> messages = new ArrayList<>();
        messages.add("state.json rebuilt from the trust seeds; revoked.json kept");
        messages.addAll(refetch.diagnostics());
        return new ResetResult(true, refetch.freshRevocations(), messages);
    }

    private enum Probe {
        READY,
        NOT_INITIALISED,
        REVOKED_UNREADABLE
    }

    /**
     * Decides whether a reset may proceed. A valid {@code revoked.json} is required; an invalid
     * {@code state.json} is fine, because the reset rebuilds it.
     */
    private static Probe probe(StateStore store) throws IOException, UpdateRejectedException {
        try {
            return store.load().isPresent() ? Probe.READY : Probe.NOT_INITIALISED;
        } catch (StateUnavailableException e) {
            return e.revokedValid() ? Probe.READY : Probe.REVOKED_UNREADABLE;
        }
    }
}
