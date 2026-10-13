// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;

/** The verified staged jar as recorded in the journal. */
public record JournalStaged(long size, String sha512, UpdateVersion version) {

    static final long MAX_SIZE = JournalLive.MAX_SIZE;

    public JournalStaged {
        Objects.requireNonNull(version, "version");
        if (size < 0 || size > MAX_SIZE) {
            throw new IllegalArgumentException("invalid size");
        }
        SharedChecks.requireLowerHex(sha512, 128, "sha512");
    }
}
