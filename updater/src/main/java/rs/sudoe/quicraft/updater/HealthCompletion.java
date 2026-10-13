// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** The outcome of {@link StartupRecovery#completeHealth}. */
public enum HealthCompletion {
    /** The journal was deleted and the earlier backups were pruned. */
    HEALTHY,
    /** No verdict arrived within the timeout; the journal and backup are kept. */
    TIMED_OUT,
    /** The server reported unhealthy; the journal and backup are kept. */
    FAILED,
    /** The journal no longer belongs to this ticket or is not in boot-pending; nothing was touched. */
    JOURNAL_CHANGED
}
