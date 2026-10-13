// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.file.Path;
import java.util.Objects;

/** Everything startup recovery and the health completion need, injected so tests can fail any boundary. */
public record RecoveryContext(Path updateDir, DurabilityOps ops, LiveJarDiscovery discovery,
        RuntimeIdentity identity) {

    public RecoveryContext {
        Objects.requireNonNull(updateDir, "updateDir");
        Objects.requireNonNull(ops, "ops");
        Objects.requireNonNull(discovery, "discovery");
        Objects.requireNonNull(identity, "identity");
    }
}
