// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Strict lowercase hex helpers and SHA-256 based key identifiers for the updater schemas.
 *
 * <p>Only the JDK is used. Decoding accepts exactly {@code 2 * byteLength} characters from the
 * set {@code [0-9a-f]}; uppercase, whitespace and any other text are rejected. Failures carry
 * fixed messages that never echo the input.
 */
final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    /** Upper bound for a requested byte length; keeps {@code 2 * byteLength} free of overflow. */
    private static final int MAX_BYTE_LENGTH = Integer.MAX_VALUE / 4;

    /** Number of leading SHA-256 bytes used as a key identifier. */
    private static final int KID_BYTES = 8;

    /** Raw Ed25519 public key length in bytes. */
    static final int PUBLIC_KEY_BYTES = 32;

    private Hex() {
    }

    /**
     * Decodes strict lowercase hex of exactly {@code byteLength} bytes.
     *
     * @throws UpdateRejectedException if the text is null, has the wrong length, contains any
     *     character outside {@code [0-9a-f]}, or {@code byteLength} is negative or too large
     */
    static byte[] decode(String text, int byteLength) throws UpdateRejectedException {
        if (byteLength < 0 || byteLength > MAX_BYTE_LENGTH) {
            throw new UpdateRejectedException("invalid hex length");
        }
        if (text == null || text.length() != byteLength * 2) {
            throw new UpdateRejectedException("invalid hex length");
        }
        byte[] out = new byte[byteLength];
        for (int i = 0; i < byteLength; i++) {
            int high = digitValue(text.charAt(2 * i));
            int low = digitValue(text.charAt(2 * i + 1));
            out[i] = (byte) ((high << 4) | low);
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
    static String sha256(byte[] data) {
        return encode(digest(data));
    }

    /**
     * Derives the key identifier for a raw Ed25519 public key given as 64 lowercase hex characters:
     * the first 8 bytes of SHA-256 over the 32 raw public-key bytes, as 16 lowercase hex characters.
     *
     * @throws UpdateRejectedException if {@code rawPublicHex} is not 32 bytes of strict lowercase hex
     */
    static String kid(String rawPublicHex) throws UpdateRejectedException {
        byte[] pub = decode(rawPublicHex, PUBLIC_KEY_BYTES);
        byte[] digest = digest(pub);
        return encode(Arrays.copyOf(digest, KID_BYTES));
    }

    private static int digitValue(char c) throws UpdateRejectedException {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        throw new UpdateRejectedException("invalid hex character");
    }

    private static byte[] digest(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256 (JCA standard algorithm names).
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
