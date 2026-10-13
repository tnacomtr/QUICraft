// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * The result of authenticating one document. {@code verified} is non-null if and only if the
 * outcome is {@link VerificationOutcome#VERIFIED}.
 */
public record DocumentVerification<T>(
        VerificationOutcome outcome, VerifiedDocument<T> verified) {

    public DocumentVerification {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == VerificationOutcome.VERIFIED) != (verified != null)) {
            throw new IllegalArgumentException("verified document only with VERIFIED outcome");
        }
    }
}
