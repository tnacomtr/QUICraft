// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A staged jar that passed {@link ArtifactVerifier#verify}: its path, its identity, the trust file
 * it carries and the key-set fingerprint computed from that trust file.
 */
public record StagedArtifact(
        Path path,
        ArtifactIdentity identity,
        UpdateDocuments.Trust stagedTrust,
        String stagedKeySetFingerprint) {

    public StagedArtifact {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(stagedTrust, "stagedTrust");
        SharedChecks.requireLowerHex(stagedKeySetFingerprint, 64, "stagedKeySetFingerprint");
    }
}
