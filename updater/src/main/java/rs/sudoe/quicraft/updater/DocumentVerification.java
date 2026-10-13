// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * The result of authenticating one document. {@code verified} is non-null if and only if the
 * outcome is {@link VerificationOutcome#VERIFIED}. Only AuthenticatedDocuments and
 * SignatureVerifier create instances.
 */
public final class DocumentVerification<T> {

    private final VerificationOutcome outcome;
    private final VerifiedDocument<T> verified;

    DocumentVerification(VerificationOutcome outcome, VerifiedDocument<T> verified) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        if ((outcome == VerificationOutcome.VERIFIED) != (verified != null)) {
            throw new IllegalArgumentException("verified document only with VERIFIED outcome");
        }
        this.verified = verified;
    }

    /** The authentication outcome. */
    public VerificationOutcome outcome() {
        return outcome;
    }

    /** The verified document, or null unless the outcome is VERIFIED. */
    public VerifiedDocument<T> verified() {
        return verified;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DocumentVerification<?> other)) {
            return false;
        }
        return outcome == other.outcome && Objects.equals(verified, other.verified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outcome, verified);
    }

    @Override
    public String toString() {
        return "DocumentVerification[outcome=" + outcome + ", verified=" + verified + "]";
    }
}
