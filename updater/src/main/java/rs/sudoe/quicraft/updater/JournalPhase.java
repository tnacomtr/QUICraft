// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** The phase of an update transaction as recorded in {@code journal.json} (docs/updater.md 12.8). */
public enum JournalPhase {
    PREPARED("prepared"),
    SWAPPED("swapped"),
    BOOT_PENDING("boot-pending"),
    RECOVERY_REQUIRED("recovery-required");

    private final String wire;

    JournalPhase(String wire) {
        this.wire = wire;
    }

    /** The exact string written to and read from {@code journal.json}. */
    public String wire() {
        return wire;
    }

    static JournalPhase fromWire(String text) throws UpdateRejectedException {
        for (JournalPhase p : values()) {
            if (p.wire.equals(text)) {
                return p;
            }
        }
        throw new UpdateRejectedException("invalid journal");
    }
}
