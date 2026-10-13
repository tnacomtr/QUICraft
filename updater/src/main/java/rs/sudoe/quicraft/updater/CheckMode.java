// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * How strictly a manifest check gates a candidate (docs/updater.md 12.6 items 4 and 5).
 *
 * <p>{@code REPORT} needs fresh revocation state. {@code STAGE} also needs 2-of-3 host coverage of
 * the current revocations document, in this run.
 */
public enum CheckMode {
    REPORT,
    STAGE
}
