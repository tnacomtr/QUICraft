// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.util.regex.Pattern;

/**
 * The one form of a fingerprint a pin may store (docs/protocol.md 12.15): lowercase
 * {@code sha256:} followed by 64 hexadecimal digits, exactly the form the advertisement and
 * {@code rs.sudoe.quicraft.core.tls.Fingerprint} use.
 *
 * <p>The check is strict on purpose. A pin is compared against an advertised fingerprint, so an
 * uppercase or abbreviated spelling would either never match or match the wrong thing.</p>
 */
final class Fingerprints {
    private static final Pattern SHAPE = Pattern.compile("sha256:[0-9a-f]{64}");

    private Fingerprints() {
    }

    /**
     * {@code value} when it is a canonical fingerprint.
     *
     * @throws IllegalArgumentException when it is not one
     */
    static String check(String value) {
        if (value == null || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("not a lowercase sha256 fingerprint");
        }
        return value;
    }
}
