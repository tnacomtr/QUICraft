// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 5869 HKDF instantiated with SHA-256, implemented on {@code javax.crypto} only. Used for the
 * binding key derivation of docs/protocol.md §12.6.
 */
public final class HkdfSha256 {
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final int HASH_LENGTH = 32;
    private static final int MAX_OUTPUT = 255 * HASH_LENGTH;

    private HkdfSha256() {
    }

    /** RFC 5869 §2.2 {@code HKDF-Extract}: HMAC-SHA256(salt, IKM). */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LENGTH] : salt;
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(effectiveSalt, MAC_ALGORITHM));
            return mac.doFinal(ikm);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(MAC_ALGORITHM + " is required by every Java platform", e);
        }
    }

    /** RFC 5869 §2.3 {@code HKDF-Expand}. */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 0 || length > MAX_OUTPUT) {
            throw new IllegalArgumentException("HKDF output length out of range: " + length);
        }
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(prk, MAC_ALGORITHM));
            byte[] okm = new byte[length];
            byte[] block = new byte[0];
            int offset = 0;
            int counter = 1;
            while (offset < length) {
                mac.reset();
                mac.update(block);
                mac.update(info == null ? new byte[0] : info);
                mac.update((byte) counter);
                block = mac.doFinal();
                int copy = Math.min(block.length, length - offset);
                System.arraycopy(block, 0, okm, offset, copy);
                offset += copy;
                counter++;
            }
            wipe(block);
            return okm;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(MAC_ALGORITHM + " is required by every Java platform", e);
        }
    }

    /** Extract then Expand, wiping the intermediate PRK. */
    public static byte[] derive(byte[] salt, byte[] ikm, byte[] info, int length) {
        byte[] prk = extract(salt, ikm);
        try {
            return expand(prk, info, length);
        } finally {
            wipe(prk);
        }
    }

    /** SHA-256 digest of {@code input}. */
    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** Best-effort erasure of a secret. Java gives no guarantee the JVM will not have copied it. */
    public static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}