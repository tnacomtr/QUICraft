// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;

/**
 * The result of {@link Acceptance#evaluateManifests}: the folded state change (returned even when
 * there is no candidate), the optional candidate, whether a state cap stopped acceptance, and the
 * fixed diagnostic strings.
 */
public record ManifestAcceptance(
        StateDelta delta,
        Optional<AcceptedCandidate> candidate,
        boolean stateFull,
        List<String> diagnostics) {

    public ManifestAcceptance {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(candidate, "candidate");
        diagnostics = List.copyOf(diagnostics);
    }
}
