// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Checks a detached signature envelope against the exact bytes of one document.
 *
 * <p>A payload hash mismatch is reported as {@link EnvelopeStatus#TORN} before any signature is
 * tried. Every entry is checked independently: an entry whose kid is unknown, whose key is revoked
 * or unusable, or whose signature fails is skipped and never removes a valid entry. Every valid
 * entry is kept.
 */
public final class SignatureVerifier {

    private SignatureVerifier() {
    }

    /**
     * @param documentBytes the exact document bytes that the envelope claims to sign
     * @param envelope the parsed envelope
     * @param snapshot the trust state used for this decision
     * @return the envelope status and every signer that verified
     */
    public static EnvelopeVerification check(
            byte[] documentBytes, UpdateDocuments.SignatureEnvelope envelope,
            TrustSnapshot snapshot) {
        Objects.requireNonNull(documentBytes, "documentBytes");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(snapshot, "snapshot");

        if (!Hex.sha256(documentBytes).equals(envelope.payloadSha256())) {
            return new EnvelopeVerification(
                    EnvelopeStatus.TORN, new VerifiedSigners(List.of()));
        }

        List<VerifiedSigner> valid = new ArrayList<>();
        for (UpdateDocuments.SignatureEntry entry : envelope.signatures()) {
            String pub = snapshot.pubByKid().get(entry.kid());
            if (pub == null || !snapshot.usable(pub)) {
                continue;
            }
            if (Ed25519.verify(documentBytes, pub, entry.sig())) {
                valid.add(new VerifiedSigner(entry.kid(), pub, snapshot.rolesByPub().get(pub)));
            }
        }

        VerifiedSigners signers = new VerifiedSigners(valid);
        if (valid.isEmpty()) {
            return new EnvelopeVerification(EnvelopeStatus.NO_VALID_SIGNER, signers);
        }
        return new EnvelopeVerification(EnvelopeStatus.VERIFIED, signers);
    }
}
