// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * Authenticates release manifests and revocations documents: envelope parse, signature check,
 * required role, then document parse (docs/updater.md 12.6).
 *
 * <p>Never throws on bad input. A document is returned only with outcome {@link
 * VerificationOutcome#VERIFIED}. Freshness, sequence, lifetime and key-set rules are separate
 * policy layers and are not decided here.
 */
public final class AuthenticatedDocuments {

    private AuthenticatedDocuments() {
    }

    /** Authenticates a release manifest. The signers must include a {@code release} key. */
    public static DocumentVerification<UpdateDocuments.ReleaseManifest> verifyRelease(
            byte[] documentBytes, byte[] sigBytes, TrustSnapshot snapshot) {
        return verify(documentBytes, sigBytes, snapshot, Kind.RELEASE);
    }

    /** Authenticates a revocations document. The signers must include a {@code revocation} key. */
    public static DocumentVerification<UpdateDocuments.Revocations> verifyRevocations(
            byte[] documentBytes, byte[] sigBytes, TrustSnapshot snapshot) {
        return verify(documentBytes, sigBytes, snapshot, Kind.REVOCATIONS);
    }

    private enum Kind {
        RELEASE, REVOCATIONS
    }

    private static <T> DocumentVerification<T> verify(
            byte[] documentBytes, byte[] sigBytes, TrustSnapshot snapshot, Kind kind) {
        if (documentBytes == null || sigBytes == null || snapshot == null) {
            return rejected(VerificationOutcome.MALFORMED);
        }
        try {
            UpdateDocuments.SignatureEnvelope envelope = DocumentParser.parseSignatures(sigBytes);
            EnvelopeVerification envelopeCheck =
                    SignatureVerifier.check(documentBytes, envelope, snapshot);
            if (envelopeCheck.status() == EnvelopeStatus.TORN) {
                return rejected(VerificationOutcome.TORN);
            }
            if (envelopeCheck.status() == EnvelopeStatus.NO_VALID_SIGNER) {
                return rejected(VerificationOutcome.BAD_SIGNATURE);
            }
            VerifiedSigners signers = envelopeCheck.signers();
            boolean roleOk = kind == Kind.RELEASE ? signers.hasRelease() : signers.hasRevocation();
            if (!roleOk) {
                return rejected(VerificationOutcome.BAD_SIGNATURE);
            }
            Object document = kind == Kind.RELEASE
                    ? DocumentParser.parseRelease(documentBytes)
                    : DocumentParser.parseRevocations(documentBytes);
            @SuppressWarnings("unchecked")
            T typed = (T) document;
            VerifiedDocument<T> verified =
                    new VerifiedDocument<>(typed, documentBytes, signers, snapshot);
            return new DocumentVerification<>(VerificationOutcome.VERIFIED, verified);
        } catch (UpdateRejectedException e) {
            return rejected(VerificationOutcome.MALFORMED);
        }
    }

    private static <T> DocumentVerification<T> rejected(VerificationOutcome outcome) {
        return new DocumentVerification<>(outcome, null);
    }
}
