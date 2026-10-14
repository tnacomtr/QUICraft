// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * One update command: the running identity this process was started with and the install
 * capability the platform adapter allows.
 */
public record UpdateRequest(RuntimeIdentity identity, InstallCapability capability) {

    public UpdateRequest {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(capability, "capability");
    }
}
