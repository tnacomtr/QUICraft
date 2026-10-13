// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** RFC 8032 section 7.1 vectors and rejection cases for {@link Ed25519#verify}. */
class Ed25519Test {
    private static final String PUB_1 =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String SIG_1 = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";
    private static final String PUB_2 =
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String MSG_2 = "72";
    private static final String SIG_2 = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00";
    private static final String PUB_3 =
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
    private static final String MSG_3 = "af82";
    private static final String SIG_3 = "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a";

    /** Group order L = 2^252 + 27742317777372353535851937790883648493. */
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    private static byte[] bytes(String hex) throws UpdateRejectedException {
        return Hex.decode(hex, hex.length() / 2);
    }

    @Test
    void rfc8032Test1EmptyMessageVerifies() throws Exception {
        assertTrue(Ed25519.verify(new byte[0], PUB_1, SIG_1));
    }

    @Test
    void rfc8032Test2VerifiesWithOneByteMessage() throws Exception {
        assertTrue(Ed25519.verify(bytes(MSG_2), PUB_2, SIG_2));
    }

    @Test
    void rfc8032Test3VerifiesWithTwoByteMessage() throws Exception {
        assertTrue(Ed25519.verify(bytes(MSG_3), PUB_3, SIG_3));
    }

    @Test
    void flippedMessageBitFails() throws Exception {
        byte[] msg = bytes(MSG_2);
        msg[0] ^= 0x01;
        assertFalse(Ed25519.verify(msg, PUB_2, SIG_2));
    }

    @Test
    void flippedSignatureBitFails() throws Exception {
        byte[] sig = bytes(SIG_2);
        sig[0] ^= 0x01;
        assertFalse(Ed25519.verify(bytes(MSG_2), PUB_2, Hex.encode(sig)));
    }

    @Test
    void wrongPublicKeyFails() throws Exception {
        assertFalse(Ed25519.verify(new byte[0], PUB_2, SIG_1));
    }

    @Test
    void uppercaseSignatureHexFails() throws Exception {
        assertFalse(Ed25519.verify(new byte[0], PUB_1, SIG_1.toUpperCase(Locale.ROOT)));
    }

    @Test
    void shortSignatureFails() throws Exception {
        assertFalse(Ed25519.verify(new byte[0], PUB_1, SIG_1.substring(0, 126)));
    }

    @Test
    void shortPublicKeyFails() throws Exception {
        assertFalse(Ed25519.verify(new byte[0], PUB_1.substring(0, 62), SIG_1));
    }

    @Test
    void nullMessageFails() throws Exception {
        assertFalse(Ed25519.verify(null, PUB_1, SIG_1));
    }

    @Test
    void nonCanonicalSPlusLFails() throws Exception {
        byte[] sig = bytes(SIG_1);
        BigInteger s = new BigInteger(1, reverse(sig, 32, 64));
        BigInteger sPlusL = s.add(L);
        if (sPlusL.bitLength() > 256) {
            sPlusL = L;
        }
        byte[] tampered = sig.clone();
        byte[] le = toLittleEndian32(sPlusL);
        System.arraycopy(le, 0, tampered, 32, 32);
        assertFalse(Ed25519.verify(new byte[0], PUB_1, Hex.encode(tampered)));
    }

    @Test
    void allZeroPublicKeyFailsWithoutThrowing() throws Exception {
        assertFalse(Ed25519.verify(new byte[0], "00".repeat(32), SIG_1));
    }

    /** Returns bytes {@code from..to} reversed (little-endian to big-endian). */
    private static byte[] reverse(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        for (int i = 0; i < out.length; i++) {
            out[i] = src[to - 1 - i];
        }
        return out;
    }

    /** Writes the low 32 bytes of {@code value} little-endian. */
    private static byte[] toLittleEndian32(BigInteger value) {
        byte[] be = value.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }
}
