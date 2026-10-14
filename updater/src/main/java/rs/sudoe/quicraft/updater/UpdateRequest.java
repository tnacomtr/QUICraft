// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * One update command: the running identity this process was started with, the install capability the
 * platform adapter allows, and the sink for lines logged while the transaction runs.
 */
public record UpdateRequest(RuntimeIdentity identity, InstallCapability capability,
        TransactionProgress progress) {

    public UpdateRequest {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(progress, "progress");
    }

}
