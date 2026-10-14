// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Lowercase-hex and digest helpers (docs/updater.md 12.2, 12.3).
 *
 * <p>The updater's own {@code Hex} helper is package-private, so the tool repeats the few rules it
 * needs here. Output is always lowercase hex, the one encoding the schemas allow.
 */
final class Hexes {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    /** Raw Ed25519 public key length in bytes. */
    private static final int PUBLIC_KEY_BYTES = 32;

    /** Number of leading SHA-256 bytes used as a key identifier. */
    private static final int KID_BYTES = 8;

    private Hexes() {
    }

    /** True when {@code text} is exactly {@code chars} lowercase hex characters. */
    static boolean isLowerHex(String text, int chars) {
        if (text == null || text.length() != chars) {
            return false;
        }
        for (int i = 0; i < chars; i++) {
            char c = text.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes strict lowercase hex of exactly {@code byteLength} bytes.
     *
     * @throws ToolException on any length or character violation
     */
    static byte[] decode(String text, int byteLength) throws ToolException {
        if (!isLowerHex(text, byteLength * 2)) {
            throw new ToolException("expected " + (byteLength * 2) + " lowercase hex characters");
        }
        byte[] out = new byte[byteLength];
        for (int i = 0; i < byteLength; i++) {
            out[i] = (byte) ((digit(text.charAt(2 * i)) << 4) | digit(text.charAt(2 * i + 1)));
        }
        return out;
    }

    /** Returns the lowercase hex encoding of {@code bytes}. */
    static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            out[2 * i] = DIGITS[b >>> 4];
            out[2 * i + 1] = DIGITS[b & 0x0f];
        }
        return new String(out);
    }

    /** Returns the lowercase hex SHA-256 digest of {@code data} (64 characters). */
    static String sha256Hex(byte[] data) {
        return encode(digest("SHA-256", data));
    }

    /** Returns the lowercase hex SHA-512 digest of {@code data} (128 characters). */
    static String sha512Hex(byte[] data) {
        return encode(digest("SHA-512", data));
    }

    /**
     * Derives the key id of a raw Ed25519 public key: the first 8 bytes of SHA-256 over the 32 raw
     * public-key bytes, as 16 lowercase hex characters (docs/updater.md 12.2).
     *
     * @throws ToolException if {@code pubHex} is not 64 lowercase hex characters
     */
    static String kid(String pubHex) throws ToolException {
        byte[] digest = digest("SHA-256", decode(pubHex, PUBLIC_KEY_BYTES));
        return encode(Arrays.copyOf(digest, KID_BYTES));
    }

    private static int digit(char c) {
        return c <= '9' ? c - '0' : c - 'a' + 10;
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
