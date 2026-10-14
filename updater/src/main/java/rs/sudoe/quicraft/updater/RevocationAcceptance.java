// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.List;
import java.util.Objects;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;

/**
 * The result of {@link Acceptance#evaluateRevocations}: the state change to commit and the fixed
 * diagnostic strings (host keys only, never URIs or document content).
 *
 * @param stateFull true when a revocations document was skipped because merging it would exceed the
 *     {@code revoked.json} caps. Its restrictions are then not applied, so staging must stay disabled
 *     (docs/updater.md 12.8, growth policy).
 */
public record RevocationAcceptance(StateDelta delta, List<String> diagnostics, boolean stateFull) {

    public RevocationAcceptance {
        Objects.requireNonNull(delta, "delta");
        diagnostics = List.copyOf(diagnostics);
    }
}
