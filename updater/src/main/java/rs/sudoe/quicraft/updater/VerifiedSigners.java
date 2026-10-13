// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.List;

/**
 * Every signer that verified over one exact document (docs/updater.md 12.6, signer evidence).
 *
 * <p>A document that verifies under several keys keeps all of them. Invalid or unknown entries
 * never appear here.
 */
public record VerifiedSigners(List<VerifiedSigner> signers) {

    public VerifiedSigners {
        signers = List.copyOf(signers);
    }

    /** True when at least one verified signer holds the {@code release} role. */
    public boolean hasRelease() {
        return anyHolds(KeyRole.RELEASE);
    }

    /** True when at least one verified signer holds the {@code revocation} role. */
    public boolean hasRevocation() {
        return anyHolds(KeyRole.REVOCATION);
    }

    /** True when a verified signer uses exactly this raw public key (64 lowercase hex). */
    public boolean containsPub(String pub) {
        for (VerifiedSigner signer : signers) {
            if (signer.pub().equals(pub)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyHolds(KeyRole role) {
        for (VerifiedSigner signer : signers) {
            if (signer.roles().contains(role)) {
                return true;
            }
        }
        return false;
    }
}
