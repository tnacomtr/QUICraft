// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** What {@code /quicraft update cancel} answers (docs/updater.md 12.9). */
public enum CancelReply {
    /** The active transaction was asked to stop; it cleans up at its next boundary. */
    REQUESTED,
    /** No transaction is active, so nothing was deleted. */
    NO_ACTIVE_TRANSACTION,
    /** The live jar was already replaced; cancel does not roll back. */
    INSTALLED_RESTART_PENDING
}
