// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * THROWAWAY TEST KEYS. The private seeds are the published RFC 8032 section 7.1 TEST 1 and TEST 2
 * seeds. Never use for releases; owner keys are generated offline later.
 *
 * <p>Test sources only. No private key material exists in the main sources.
 */
final class TestKeys {

    /** RFC 8032 section 7.1 TEST 1 seed (throwaway, published). */
    static final String SEED_1 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    /** RFC 8032 section 7.1 TEST 1 public key. */
    static final String PUB_1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    static final String KID_1 = "21fe31dfa154a261";

    /** RFC 8032 section 7.1 TEST 2 seed (throwaway, published). */
    static final String SEED_2 = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";
    /** RFC 8032 section 7.1 TEST 2 public key. */
    static final String PUB_2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    static final String KID_2 = "39f713d0a644253f";

    /** PKCS#8 DER prefix for an Ed25519 private key; the 32 seed bytes follow. */
    private static final String PKCS8_PREFIX = "302e020100300506032b657004220420";

    private TestKeys() {
    }

    /** Returns the hex Ed25519 signature of {@code message} made with the given seed. */
    static String sign(String seedHex, byte[] message) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(pkcs8(seedHex)));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(message);
        return Hex.encode(signer.sign());
    }

    private static byte[] pkcs8(String seedHex) {
        String full = PKCS8_PREFIX + seedHex;
        byte[] out = new byte[full.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(full.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
