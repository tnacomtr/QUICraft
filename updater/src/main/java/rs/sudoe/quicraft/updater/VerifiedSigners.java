// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.List;
import java.util.Objects;

/**
 * Every signer that verified over one exact document (docs/updater.md 12.6, signer evidence).
 *
 * <p>A document that verifies under several keys keeps all of them. Invalid or unknown entries
 * never appear here. Only AuthenticatedDocuments and SignatureVerifier create instances.
 */
public final class VerifiedSigners {

    private final List<VerifiedSigner> signers;

    VerifiedSigners(List<VerifiedSigner> signers) {
        this.signers = List.copyOf(signers);
    }

    /** The verified signers, in verification order. */
    public List<VerifiedSigner> signers() {
        return signers;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof VerifiedSigners other && signers.equals(other.signers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(signers);
    }

    @Override
    public String toString() {
        return "VerifiedSigners" + signers;
    }
}
