// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/** The identity of one artifact: platform, version, size in bytes and SHA-512 (128 lowercase hex). */
public record ArtifactIdentity(String platform, UpdateVersion version, long size, String sha512) {

    public ArtifactIdentity {
        SharedChecks.requirePlatform(platform);
        Objects.requireNonNull(version, "version");
        if (size <= 0) {
            throw new IllegalArgumentException("invalid size");
        }
        SharedChecks.requireLowerHex(sha512, 128, "sha512");
    }
}
