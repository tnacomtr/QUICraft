// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/** Compares the staged artifact with the revalidated manifest entry (docs/updater.md 12.10 step 4). */
final class Revalidation {

    private Revalidation() {
    }

    /**
     * MATCH when platform, version, size and SHA-512 all equal. RESTART_REQUIRED when the revalidated
     * entry is for the same platform but any of version, size or SHA-512 differs. REJECT when there is
     * no entry, or when the platform differs from {@code platform} or from the staged identity.
     */
    static RevalidationDecision compare(ArtifactIdentity staged,
            UpdateDocuments.ReleaseEntry revalidated, String platform) {
        Objects.requireNonNull(staged, "staged");
        SharedChecks.requirePlatform(platform);
        if (revalidated == null) {
            return RevalidationDecision.REJECT;
        }
        if (!platform.equals(staged.platform()) || !platform.equals(revalidated.platform())) {
            return RevalidationDecision.REJECT;
        }
        boolean same = staged.version().equals(revalidated.version())
                && staged.size() == revalidated.size()
                && staged.sha512().equals(revalidated.sha512());
        return same ? RevalidationDecision.MATCH : RevalidationDecision.RESTART_REQUIRED;
    }
}
