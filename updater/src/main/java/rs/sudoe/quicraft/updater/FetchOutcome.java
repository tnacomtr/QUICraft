// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** The result class of one fetch. Only {@link #OK} carries bytes. */
public enum FetchOutcome {
    OK,
    TRANSPORT_ERROR,
    TIMEOUT,
    OVERSIZE,
    HTTP_STATUS,
    REDIRECT_REFUSED,
    CANCELLED
}
