// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Ed25519 signature verification on the JDK provider (RFC 8032).
 *
 * <p>{@link #verify} never throws: any malformed input, non-canonical signature or provider
 * failure yields {@code false}.
 */
public final class Ed25519 {

    /** Group order L = 2^252 + 27742317777372353535851937790883648493. */
    private static final BigInteger GROUP_ORDER = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo (followed by the 32 raw key bytes). */
    private static final String X509_PREFIX_HEX = "302a300506032b6570032100";

    private static final int PUBLIC_KEY_BYTES = 32;
    private static final int SIGNATURE_BYTES = 64;

    private Ed25519() {
    }

    /**
     * Verifies {@code sigHex} over {@code message} with the raw public key {@code pubHex}.
     *
     * @return true only for a valid, canonical signature; false otherwise, never an exception
     */
    public static boolean verify(byte[] message, String pubHex, String sigHex) {
        try {
            if (message == null) {
                return false;
            }
            byte[] pub = Hex.decode(pubHex, PUBLIC_KEY_BYTES);
            byte[] sig = Hex.decode(sigHex, SIGNATURE_BYTES);
            if (!hasCanonicalS(sig)) {
                return false;
            }
            byte[] prefix = Hex.decode(X509_PREFIX_HEX, X509_PREFIX_HEX.length() / 2);
            byte[] der = new byte[prefix.length + pub.length];
            System.arraycopy(prefix, 0, der, 0, prefix.length);
            System.arraycopy(pub, 0, der, prefix.length, pub.length);

            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(der));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(sig);
        } catch (UpdateRejectedException | GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** S is the little-endian integer in signature bytes 32..63; it must be below L. */
    private static boolean hasCanonicalS(byte[] sig) {
        byte[] bigEndian = new byte[SIGNATURE_BYTES / 2 + 1];
        for (int i = 0; i < SIGNATURE_BYTES / 2; i++) {
            bigEndian[SIGNATURE_BYTES / 2 - i] = sig[SIGNATURE_BYTES / 2 + i];
        }
        return new BigInteger(bigEndian).compareTo(GROUP_ORDER) < 0;
    }
}
