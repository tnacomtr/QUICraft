// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.List;
import java.util.Objects;
import rs.sudoe.quicraft.updater.UpdateDocuments.ReleaseEntry;
import rs.sudoe.quicraft.updater.UpdateDocuments.ReleaseManifest;

/**
 * The update that a manifest check selected: the winning verified manifest, its exact evidence
 * bytes, the selected release entry, and the configured hosts that served byte-identical manifests
 * (candidate artifact sources, in configured order, never empty).
 */
public record AcceptedCandidate(
        VerifiedDocument<ReleaseManifest> manifest,
        ManifestEvidence evidence,
        ReleaseEntry entry,
        List<ReleaseHost> artifactSources) {

    public AcceptedCandidate {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(entry, "entry");
        artifactSources = List.copyOf(artifactSources);
        if (artifactSources.isEmpty()) {
            throw new IllegalArgumentException("artifactSources must not be empty");
        }
    }
}
