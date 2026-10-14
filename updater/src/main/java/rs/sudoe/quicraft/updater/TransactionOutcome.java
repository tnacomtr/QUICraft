// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** How one update transaction ended (docs/updater.md 12.9, 12.10). */
public enum TransactionOutcome {
    /** Refused before any staging write: the admission rules, a busy lock or a second command. */
    NOT_ADMITTED,
    /** The check found nothing newer than the running and on-disk versions. */
    NO_UPDATE,
    /** A step failed; the reason is in the messages. */
    FAILED,
    /** The user cancelled before the point of no return; staging and the journal were cleaned up. */
    CANCELLED,
    /** Verified and staged only, on a platform that cannot swap the live jar. */
    STAGED_ONLY,
    /** The live jar was replaced; a restart is needed before the new jar runs. */
    INSTALLED_RESTART_PENDING
}
