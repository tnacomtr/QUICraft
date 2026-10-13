// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * The identity of the running server, built once at init from preloaded resources. Platform is one
 * of {@code velocity}, {@code fabric-26x} or {@code fabric-1.21.11}; the target is non-empty; the
 * live SHA-512 is recorded at init as 128 lowercase hex characters.
 */
public record RuntimeIdentity(
        String platform,
        String target,
        UpdateVersion runningVersion,
        UpdateDocuments.Trust runningTrust,
        String liveSha512AtInit) {

    public RuntimeIdentity {
        SharedChecks.requirePlatform(platform);
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) {
            throw new IllegalArgumentException("target must not be empty");
        }
        Objects.requireNonNull(runningVersion, "runningVersion");
        Objects.requireNonNull(runningTrust, "runningTrust");
        SharedChecks.requireLowerHex(liveSha512AtInit, 128, "liveSha512AtInit");
    }
}
