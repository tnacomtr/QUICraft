// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** Identifies the transaction whose health check is pending after startup recovery. */
public record RecoveryTicket(String txid) {

    private static final int TXID_CHARS = 32;

    public RecoveryTicket {
        SharedChecks.requireLowerHex(txid, TXID_CHARS, "txid");
    }
}
