// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * Status of one envelope check plus the signers that verified. The signer list is empty unless the
 * status is {@link EnvelopeStatus#VERIFIED}. Only AuthenticatedDocuments and SignatureVerifier
 * create instances.
 */
public final class EnvelopeVerification {

    private final EnvelopeStatus status;
    private final VerifiedSigners signers;

    EnvelopeVerification(EnvelopeStatus status, VerifiedSigners signers) {
        this.status = Objects.requireNonNull(status, "status");
        this.signers = Objects.requireNonNull(signers, "signers");
        if (status != EnvelopeStatus.VERIFIED && !signers.signers().isEmpty()) {
            throw new IllegalArgumentException("signers present without a verified envelope");
        }
    }

    /** The envelope status. */
    public EnvelopeStatus status() {
        return status;
    }

    /** The verified signers (empty unless the status is VERIFIED). */
    public VerifiedSigners signers() {
        return signers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EnvelopeVerification other)) {
            return false;
        }
        return status == other.status && signers.equals(other.signers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, signers);
    }

    @Override
    public String toString() {
        return "EnvelopeVerification[status=" + status + ", signers=" + signers + "]";
    }
}
