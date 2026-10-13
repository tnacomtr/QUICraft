// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** Small-order and canonical-encoding rejection for trusted Ed25519 public keys. */
class Ed25519PointsTest {
    private static final String RFC_PUB_1 =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String RFC_PUB_2 =
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String RFC_PUB_3 =
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

    private static byte[] bytes(String hex) throws UpdateRejectedException {
        return Hex.decode(hex, hex.length() / 2);
    }

    /** Little-endian 32-byte encoding of {@code y} with the sign bit set to {@code sign}. */
    private static byte[] encode(BigInteger y, boolean sign) {
        byte[] be = y.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) {
            out[i] = be[be.length - 1 - i];
        }
        if (sign) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    @Test
    void rfc8032PublicKeysAreAccepted() throws Exception {
        assertTrue(Ed25519Points.isCanonicalPrimeOrderCandidate(bytes(RFC_PUB_1)));
        assertTrue(Ed25519Points.isCanonicalPrimeOrderCandidate(bytes(RFC_PUB_2)));
        assertTrue(Ed25519Points.isCanonicalPrimeOrderCandidate(bytes(RFC_PUB_3)));
    }

    @Test
    void identityIsRejected() throws Exception {
        assertFalse(Ed25519Points.isCanonicalPrimeOrderCandidate(bytes("01" + "00".repeat(31))));
    }

    @Test
    void allZeroKeyIsRejected() throws Exception {
        assertFalse(Ed25519Points.isCanonicalPrimeOrderCandidate(bytes("00".repeat(32))));
    }

    @Test
    void orderTwoPointIsRejected() throws Exception {
        // y = p - 1 = -1, x = 0: the order-2 point.
        assertFalse(Ed25519Points.isCanonicalPrimeOrderCandidate(bytes("ec" + "ff".repeat(30) + "7f")));
    }

    @Test
    void nonCanonicalYEqualToPIsRejected() throws Exception {
        assertFalse(Ed25519Points.isCanonicalPrimeOrderCandidate(bytes("ed" + "ff".repeat(30) + "7f")));
    }

    @Test
    void identityWithSignBitIsRejected() throws Exception {
        // y = 1 with x = 0 and sign bit 1: a non-canonical encoding of the identity.
        assertFalse(Ed25519Points.isCanonicalPrimeOrderCandidate(bytes("01" + "00".repeat(30) + "80")));
    }

    @Test
    void wrongLengthAndNullAreRejected() {
        assertFalse(Ed25519Points.isCanonicalPrimeOrderCandidate(null));
        assertFalse(Ed25519Points.isCanonicalPrimeOrderCandidate(new byte[31]));
    }

    @Test
    void offCurveYDoesNotDecode() throws Exception {
        boolean foundOffCurve = false;
        for (int y = 2; y < 50 && !foundOffCurve; y++) {
            byte[] enc = encode(BigInteger.valueOf(y), false);
            if (!Ed25519Points.decodes(enc)) {
                foundOffCurve = true;
                assertFalse(Ed25519Points.isCanonicalPrimeOrderCandidate(enc));
            }
        }
        assertTrue(foundOffCurve, "expected at least one y in 2..49 that is not on the curve");
    }

    @Test
    void rfc8032PublicKeysDecode() throws Exception {
        assertTrue(Ed25519Points.decodes(bytes(RFC_PUB_1)));
        assertTrue(Ed25519Points.decodes(bytes(RFC_PUB_2)));
        assertTrue(Ed25519Points.decodes(bytes(RFC_PUB_3)));
    }

    @Test
    void edVerifyRefusesSmallOrderKey() throws Exception {
        String sig = "00".repeat(64);
        assertFalse(Ed25519.verify(new byte[0], "00".repeat(32), sig));
        assertFalse(Ed25519.verify(new byte[0], "01" + "00".repeat(31), sig));
    }
}
