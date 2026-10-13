// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * A failed fetch. The outcome is never {@link FetchOutcome#OK}. The message is fixed per outcome and
 * never contains a URI or response data.
 */
public final class FetchException extends Exception {
    private static final long serialVersionUID = 1L;

    private final transient FetchOutcome outcome;

    /**
     * @param outcome the failure outcome; must not be null and must not be {@link FetchOutcome#OK}
     * @throws IllegalArgumentException when {@code outcome} is {@link FetchOutcome#OK}
     */
    public FetchException(FetchOutcome outcome) {
        super(message(outcome));
        this.outcome = outcome;
    }

    /** @return the failure outcome, never {@link FetchOutcome#OK} */
    public FetchOutcome outcome() {
        return outcome;
    }

    private static String message(FetchOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == FetchOutcome.OK) {
            throw new IllegalArgumentException("a failed fetch cannot be OK");
        }
        return "fetch failed: " + outcome.name();
    }
}
