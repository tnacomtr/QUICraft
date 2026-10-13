// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * Status of one envelope check plus the signers that verified. The signer list is empty unless the
 * status is {@link EnvelopeStatus#VERIFIED}.
 */
public record EnvelopeVerification(EnvelopeStatus status, VerifiedSigners signers) {

    public EnvelopeVerification {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(signers, "signers");
        if (status != EnvelopeStatus.VERIFIED && !signers.signers().isEmpty()) {
            throw new IllegalArgumentException("signers present without a verified envelope");
        }
    }
}
