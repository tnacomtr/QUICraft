// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * Roles a trusted updater key may hold (docs/updater.md 12.3 and the key-set fingerprint).
 *
 * <p>{@link #bit()} is the bit used in the key-set fingerprint role byte.
 */
public enum KeyRole {
    RELEASE("release", 1),
    REVOCATION("revocation", 2);

    private final String wireName;
    private final int bit;

    KeyRole(String wireName, int bit) {
        this.wireName = wireName;
        this.bit = bit;
    }

    /** The role name as it appears in the trust file and documents. */
    public String wireName() {
        return wireName;
    }

    /** The fingerprint role bit: {@code 1} for release, {@code 2} for revocation. */
    public int bit() {
        return bit;
    }
}
