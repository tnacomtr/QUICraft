// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.math.BigInteger;

/**
 * Edwards25519 point checks for trusted public keys (RFC 8032 encoding, edwards25519 curve).
 *
 * <p>A trusted key must be a canonical encoding of a point on the curve that is not of small
 * order. Mixed-torsion points (a prime-order point plus a small-order point) are accepted by design;
 * only small-order and non-canonical encodings are rejected. Small-order keys (identity, order 2, order 4, order 8) make some signatures verify under
 * many messages, so they are rejected before any signature check.
 */
final class Ed25519Points {

    /** Field prime p = 2^255 - 19. */
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

    /** Curve constant d = -121665 / 121666 mod p. */
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P);

    /** sqrt(-1) mod p = 2^((p-1)/4) mod p. */
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    /** Exponent (p-5)/8 used by the square-root candidate. */
    private static final BigInteger SQRT_EXP = P.subtract(BigInteger.valueOf(5)).shiftRight(3);

    private static final int KEY_BYTES = 32;

    private Ed25519Points() {
    }

    /**
     * True when {@code pub32} is a canonical encoding of a point on edwards25519 that is not of
     * small order. Returns false for every other input, never throws.
     */
    static boolean isCanonicalPrimeOrderCandidate(byte[] pub32) {
        if (pub32 == null || pub32.length != KEY_BYTES) {
            return false;
        }
        BigInteger y = yOf(pub32);
        if (y.compareTo(P) >= 0) {
            return false;
        }
        boolean sign = (pub32[KEY_BYTES - 1] & 0x80) != 0;
        BigInteger[] point = decodePoint(pub32);
        if (point == null) {
            return false;
        }
        if (point[0].signum() == 0 && sign) {
            return false;
        }
        BigInteger[] eightA = point;
        for (int i = 0; i < 3; i++) {
            eightA = add(eightA, eightA);
        }
        return !(eightA[0].signum() == 0 && eightA[1].equals(BigInteger.ONE));
    }

    /**
     * True when {@code pub32} is a canonical y coordinate (less than p) that lies on the curve.
     * Package-private for tests; does not check the small-order rule.
     */
    static boolean decodes(byte[] pub32) {
        if (pub32 == null || pub32.length != KEY_BYTES) {
            return false;
        }
        if (yOf(pub32).compareTo(P) >= 0) {
            return false;
        }
        return decodePoint(pub32) != null;
    }

    /** Little-endian y with the sign bit (bit 255) cleared. */
    private static BigInteger yOf(byte[] pub32) {
        byte[] bigEndian = new byte[KEY_BYTES + 1];
        for (int i = 0; i < KEY_BYTES; i++) {
            bigEndian[KEY_BYTES - i] = pub32[i];
        }
        bigEndian[1] &= 0x7f;
        return new BigInteger(bigEndian);
    }

    /** Returns {x, y} for the encoded point, or null when the encoding is not on the curve. */
    private static BigInteger[] decodePoint(byte[] pub32) {
        BigInteger y = yOf(pub32);
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger v3 = v.modPow(BigInteger.valueOf(3), P);
        BigInteger v7 = v3.multiply(v3).mod(P).multiply(v).mod(P);
        BigInteger x = u.multiply(v3).mod(P)
                .multiply(u.multiply(v7).mod(P).modPow(SQRT_EXP, P))
                .mod(P);
        BigInteger check = v.multiply(x).mod(P).multiply(x).mod(P);
        if (!check.equals(u)) {
            if (!check.equals(P.subtract(u).mod(P))) {
                return null;
            }
            x = x.multiply(SQRT_M1).mod(P);
        }
        boolean sign = (pub32[KEY_BYTES - 1] & 0x80) != 0;
        if (x.testBit(0) != sign) {
            x = P.subtract(x).mod(P);
        }
        return new BigInteger[] {x, y};
    }

    /** Unified twisted Edwards addition (a = -1); complete because d is a non-square. */
    private static BigInteger[] add(BigInteger[] p1, BigInteger[] p2) {
        BigInteger x1 = p1[0];
        BigInteger y1 = p1[1];
        BigInteger x2 = p2[0];
        BigInteger y2 = p2[1];
        BigInteger xy = D.multiply(x1).mod(P).multiply(x2).mod(P).multiply(y1).mod(P).multiply(y2).mod(P);
        BigInteger x3 = x1.multiply(y2).add(y1.multiply(x2))
                .mod(P)
                .multiply(BigInteger.ONE.add(xy).mod(P).modInverse(P))
                .mod(P);
        BigInteger y3 = y1.multiply(y2).add(x1.multiply(x2))
                .mod(P)
                .multiply(BigInteger.ONE.subtract(xy).mod(P).modInverse(P))
                .mod(P);
        return new BigInteger[] {x3, y3};
    }
}
