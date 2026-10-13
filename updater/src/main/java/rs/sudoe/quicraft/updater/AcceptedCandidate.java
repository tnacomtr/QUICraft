// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.List;
import java.util.Objects;

/**
 * A release entry that policy has accepted for staging (docs/updater.md 12.2 and 12.10). It carries
 * the verified manifest and its evidence, the chosen entry and the hosts the artifact may be fetched
 * from. The list is copied defensively.
 */
public record AcceptedCandidate(
        VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest,
        ManifestEvidence evidence,
        UpdateDocuments.ReleaseEntry entry,
        List<ReleaseHost> artifactSources) {

    public AcceptedCandidate {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(entry, "entry");
        artifactSources = List.copyOf(artifactSources);
    }
}
