// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Objects;
import java.util.Set;

/**
 * Package-private validation helpers shared by the Wave 3 shared records. Null components raise
 * {@link NullPointerException}; every other rejection raises {@link IllegalArgumentException} with
 * a fixed message that never echoes the input.
 */
final class SharedChecks {

    /** The platform identifiers the updater knows. */
    static final Set<String> PLATFORMS = Set.of("velocity", "fabric-26x", "fabric-1.21.11");

    private static final String HEX = "0123456789abcdef";

    private SharedChecks() {
    }

    static void requirePlatform(String platform) {
        Objects.requireNonNull(platform, "platform");
        if (!PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("unknown platform");
        }
    }

    static void requireLowerHex(String s, int chars, String field) {
        Objects.requireNonNull(s, field);
        if (s.length() != chars) {
            throw new IllegalArgumentException("invalid " + field);
        }
        for (int i = 0; i < s.length(); i++) {
            if (HEX.indexOf(s.charAt(i)) < 0) {
                throw new IllegalArgumentException("invalid " + field);
            }
        }
    }
}
