// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * One manifest document from a configured host that failed signature verification. The identity is the
 * SHA-256 of the exact document bytes, so the same bad document is recognised across runs and restarts.
 *
 * @param hostKey the configured host key ({@code modrinth}, {@code github} or {@code sudoe})
 * @param sha256 lowercase hex SHA-256 of the document bytes
 */
public record BadManifest(String hostKey, String sha256) {

    public BadManifest {
        Objects.requireNonNull(hostKey, "hostKey");
        SharedChecks.requireLowerHex(sha256, 64, "sha256");
    }
}
