// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** The outcome of comparing a staged artifact with the freshly revalidated manifest entry (docs/updater.md 12.10 step 4). */
public enum RevalidationDecision {
    /** Platform, version, size and SHA-512 all equal: the swap may continue. */
    MATCH,
    /** Same platform, but a newer or different build is now named: restart under a new txid. */
    RESTART_REQUIRED,
    /** No entry any more, or the platform differs: the transaction ends. */
    REJECT
}
