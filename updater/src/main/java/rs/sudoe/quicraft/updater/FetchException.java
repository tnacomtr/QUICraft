// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/**
 * A failed fetch. The outcome is never {@link FetchOutcome#OK}. The message is fixed per outcome and
 * never contains a URI or response data. The optional HTTP status is {@code -1} when not applicable.
 */
public final class FetchException extends Exception {
    private static final long serialVersionUID = 1L;

    private final FetchOutcome outcome;
    private final int httpStatus;

    /**
     * @param outcome the failure outcome; must not be null and must not be {@link FetchOutcome#OK}
     * @throws IllegalArgumentException when {@code outcome} is {@link FetchOutcome#OK}
     */
    public FetchException(FetchOutcome outcome) {
        this(outcome, -1);
    }

    /**
     * @param outcome the failure outcome; must not be null and must not be {@link FetchOutcome#OK}
     * @param httpStatus the HTTP status code, 100..599, or {@code -1} when not applicable
     * @throws IllegalArgumentException when {@code outcome} is OK or {@code httpStatus} is out of range
     */
    public FetchException(FetchOutcome outcome, int httpStatus) {
        super(message(outcome));
        if (httpStatus != -1 && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("invalid http status");
        }
        this.outcome = outcome;
        this.httpStatus = httpStatus;
    }

    /** @return the failure outcome, never {@link FetchOutcome#OK} */
    public FetchOutcome outcome() {
        return outcome;
    }

    /** @return the HTTP status code, or {@code -1} when not applicable */
    public int httpStatus() {
        return httpStatus;
    }

    private static String message(FetchOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == FetchOutcome.OK) {
            throw new IllegalArgumentException("a failed fetch cannot be OK");
        }
        return "fetch failed: " + outcome.name();
    }
}
