// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one {@link CheckPipeline#run}.
 *
 * @param mode the check mode that was run
 * @param candidate the candidate chosen inside the manifest commit, if any
 * @param stateFull true when a state cap stopped manifest acceptance or skipped a revocations document; no candidate is offered then
 * @param freshRevocations true when the persisted revocations are fresh after this run
 * @param diagnostics fixed diagnostic strings (host keys only)
 */
public record CheckResult(
        CheckMode mode,
        Optional<AcceptedCandidate> candidate,
        boolean stateFull,
        boolean freshRevocations,
        List<String> diagnostics,
        List<BadManifest> badManifests) {

    public CheckResult {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(candidate, "candidate");
        diagnostics = List.copyOf(diagnostics);
        badManifests = List.copyOf(badManifests);
    }

    /** The shape before bad-manifest events were added: no events. */
    public CheckResult(CheckMode mode, Optional<AcceptedCandidate> candidate, boolean stateFull,
            boolean freshRevocations, List<String> diagnostics) {
        this(mode, candidate, stateFull, freshRevocations, diagnostics, List.of());
    }
}
