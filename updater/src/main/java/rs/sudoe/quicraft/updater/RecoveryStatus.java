// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** The outcome of startup recovery (docs/updater.md 12.11). */
public enum RecoveryStatus {
    /** No transaction is pending; the updater may run normally. */
    IDLE,
    /** A new generation booted after a swap; the health check must complete (see completeHealth). */
    HEALTH_PENDING,
    /** The files were kept as they are; no new downloads, the owner must act. */
    RECOVERY_REQUIRED,
    /** The updater is off for this start; no file was touched. */
    DISABLED
}
