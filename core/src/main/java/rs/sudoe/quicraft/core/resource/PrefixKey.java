// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Canonical source prefixes used for fair accounting and keyed overflow buckets (protocol.md 12 and 13.12).
 *
 * <p>Three granularities exist: {@link #broad} (IPv6 /48, IPv4 /24) and {@link #narrow} (IPv6 /64, IPv4 /24)
 * drive the hierarchical pre-login fair share; {@link #overflow} (IPv6 /48, IPv4 /32) keys the
 * {@code K=256} overflow history buckets. IPv4-mapped IPv6 addresses are treated as IPv4.</p>
 */
public final class PrefixKey {
    /** Number of overflow history buckets per pool (13.12). */
    public static final int OVERFLOW_BUCKETS = 256;

    private final byte[] bytes;
    private final int prefixBits;

    private PrefixKey(byte[] bytes, int prefixBits) {
        this.bytes = bytes;
        this.prefixBits = prefixBits;
    }

    /** Narrow source prefix: IPv6 /64, IPv4 /24. */
    public static PrefixKey narrow(InetAddress address) {
        return slice(address, 64, 24);
    }

    /** Broad source prefix: IPv6 /48, IPv4 /24. */
    public static PrefixKey broad(InetAddress address) {
        return slice(address, 48, 24);
    }

    /** Overflow bucket prefix: IPv6 /48, IPv4 /32 (13.12: rotating /64s cannot spread one attacker). */
    public static PrefixKey overflow(InetAddress address) {
        return slice(address, 48, 32);
    }

    /** Keyed overflow bucket for an IP address, in [0, 256). */
    public int bucket(byte[] secret) {
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length + 1);
        buffer.put((byte) prefixBits).put(bytes);
        return hmacBucket(secret, buffer.array());
    }

    /** Keyed overflow bucket for an authenticated account name, in [0, 256). */
    public static int accountBucket(byte[] secret, String account) {
        if (account == null) throw new IllegalArgumentException("account");
        byte[] name;
        try {
            name = ("account:" + account).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return hmacBucket(secret, name);
    }

    private static PrefixKey slice(InetAddress address, int v6Bits, int v4Bits) {
        if (address == null) throw new IllegalArgumentException("address");
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address || isMapped(raw)) {
            byte[] v4 = Arrays.copyOfRange(raw, raw.length - 4, raw.length);
            if (v4Bits == 24) return new PrefixKey(Arrays.copyOf(v4, 3), 24);
            return new PrefixKey(v4, 32);
        }
        if (!(address instanceof Inet6Address)) throw new IllegalArgumentException("unsupported address");
        return new PrefixKey(Arrays.copyOf(raw, v6Bits / 8), v6Bits);
    }

    private static int hmacBucket(byte[] secret, byte[] message) {
        if (secret == null || secret.length == 0) throw new IllegalArgumentException("secret");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(message);
            return digest[0] & 255;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean isMapped(byte[] a) {
        if (a.length != 16) return false;
        for (int i = 0; i < 10; i++) if (a[i] != 0) return false;
        return a[10] == (byte) 255 && a[11] == (byte) 255;
    }

    @Override public boolean equals(Object o) {
        return o instanceof PrefixKey && prefixBits == ((PrefixKey) o).prefixBits && Arrays.equals(bytes, ((PrefixKey) o).bytes);
    }

    @Override public int hashCode() { return 31 * Arrays.hashCode(bytes) + prefixBits; }

    @Override public String toString() { return "prefix/" + prefixBits + ":" + Arrays.toString(bytes); }
}
