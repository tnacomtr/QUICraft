// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * The result of fetching one document from one host. The pair is present exactly when the outcome
 * is {@link FetchOutcome#OK}.
 *
 * <p>One outcome covers both the document and its signature fetch. A failed {@code .sig} makes the
 * whole evidence a failure, with no pair.
 */
public record HostEvidence(ReleaseHost host, FetchOutcome outcome, DocumentPair pair) {

    public HostEvidence {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == FetchOutcome.OK && pair == null) {
            throw new IllegalArgumentException("successful evidence needs a pair");
        }
        if (outcome != FetchOutcome.OK && pair != null) {
            throw new IllegalArgumentException("failed evidence must not carry a pair");
        }
    }
}
