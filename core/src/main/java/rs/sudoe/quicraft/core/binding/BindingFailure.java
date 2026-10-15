// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

/**
 * Fatal binding outcome reason codes (docs/protocol.md §12.9, §12.14). Every one closes the whole
 * QUIC connection with application error {@code 0x10}; the numeric code here is what the server
 * logs instead of any message contents. No reason carries secret material.
 */
public enum BindingFailure {
    /** Payload was not exactly 103 bytes (truncated or trailing bytes, §12.8). */
    BAD_LENGTH(1),
    /** Version byte was not 2 (§12.8). */
    BAD_VERSION(2),
    /** Kind byte was not one of the five known kinds (§12.8). */
    UNKNOWN_KIND(3),
    /** A message arrived in the direction the receiver does not accept it from (§12.8, §12.9). */
    WRONG_DIRECTION(4),
    /** A kind already accepted was received again (§12.9). */
    DUPLICATE(5),
    /** The expected next kind of the fixed order was not the one received (§12.9). */
    WRONG_ORDER(6),
    /** Mode byte did not match the locally expected mode, including mode 0 offered online (§12.19). */
    WRONG_MODE(7),
    /** Protocol number did not match the local value (§12.9). */
    WRONG_PROTOCOL(8),
    /** Certificate fingerprint {@code F} did not match the local value (§12.7, §12.9). */
    WRONG_FINGERPRINT(9),
    /** Routing digest {@code R} did not match the local value (§12.7, §12.9). */
    WRONG_ROUTING(10),
    /** MAC was wrong, or a tag was present where the spec requires zeros (§12.8, §12.9). */
    BAD_TAG(11),
    /** The exporter value could not be obtained or was not 32 bytes (§12.3, §12.14). */
    EXPORTER_ERROR(12),
    /** A message arrived after the exchange had already settled (§12.9). */
    ALREADY_SETTLED(13);

    private final int code;

    BindingFailure(int code) {
        this.code = code;
    }

    /** Stable numeric reason code for logs. */
    public int code() {
        return code;
    }
}