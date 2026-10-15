// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The fixed 1 MiB monotone Bloom filter of docs/protocol.md 12.16, which keeps changed-pin notices
 * at most once per (normalized logical host, logical TCP port, new fingerprint) even after the
 * detailed history has evicted that tuple.
 *
 * <p>It is 8,388,608 bits and uses seven indexes derived from one SHA-256 digest of the tuple. The
 * tuple is hashed as a length-prefixed UTF-8 encoding: for each of the host, the port in decimal and
 * the fingerprint, a four-byte big-endian length followed by its UTF-8 bytes, so no two different
 * tuples can produce the same byte string. The seven indexes use Kirsch-Mitzenmacher double hashing
 * over the first two digest words; the step is forced odd so that it is coprime with the
 * power-of-two size and the seven indexes are distinct.</p>
 *
 * <p>Bits are never cleared: a false positive can suppress a notice, but a tuple that was marked once
 * can never be reported as unseen again, which is what makes "at most once" survive eviction and
 * restarts. The class does not persist itself; its owner writes {@link #copy()} atomically.</p>
 */
final class RebindBloom {
    /** 1 MiB, docs/protocol.md 12.16. */
    static final int BITS = 8 * 1024 * 1024;
    /** The exact size of the persisted filter, in bytes. */
    static final int BYTES = BITS / 8;
    private static final int HASHES = 7;

    private final byte[] bits = new byte[BYTES];

    /**
     * Sets the seven bits of {@code key} and {@code fingerprint}.
     *
     * @return false when every bit was already set, that is, the tuple has been marked before
     */
    boolean add(PinKey key, String fingerprint) {
        boolean newlySet = false;
        for (int index : indexes(key, fingerprint)) {
            int mask = 1 << (index & 7);
            int at = index >>> 3;
            if ((bits[at] & mask) == 0) {
                bits[at] = (byte) (bits[at] | mask);
                newlySet = true;
            }
        }
        return newlySet;
    }

    /** Replaces the filter with the {@link #BYTES} bytes at {@code offset} in {@code source}. */
    void read(byte[] source, int offset) {
        if (source.length - offset < BYTES || offset < 0) {
            throw new IllegalArgumentException("bloom filter is not " + BYTES + " bytes");
        }
        System.arraycopy(source, offset, bits, 0, BYTES);
    }

    /** Copies the filter into {@code target} at {@code offset}, for an atomic write. */
    void copyTo(byte[] target, int offset) {
        System.arraycopy(bits, 0, target, offset, BYTES);
    }

    private static int[] indexes(PinKey key, String fingerprint) {
        byte[] digest = sha256(lengthPrefixed(key.host(), Integer.toString(key.port()), fingerprint));
        long first = unsignedWord(digest, 0);
        long step = unsignedWord(digest, 4) | 1L;
        int[] indexes = new int[HASHES];
        for (int i = 0; i < HASHES; i++) {
            indexes[i] = (int) ((first + (long) i * step) % BITS);
        }
        return indexes;
    }

    private static byte[] lengthPrefixed(String host, String port, String fingerprint) {
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        byte[] portBytes = port.getBytes(StandardCharsets.UTF_8);
        byte[] fingerprintBytes = fingerprint.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(12 + hostBytes.length + portBytes.length + fingerprintBytes.length);
        buffer.putInt(hostBytes.length).put(hostBytes);
        buffer.putInt(portBytes.length).put(portBytes);
        buffer.putInt(fingerprintBytes.length).put(fingerprintBytes);
        return buffer.array();
    }

    private static long unsignedWord(byte[] digest, int offset) {
        return ((long) (digest[offset] & 0xFF) << 24) | ((digest[offset + 1] & 0xFF) << 16)
                | ((digest[offset + 2] & 0xFF) << 8) | (digest[offset + 3] & 0xFF);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
