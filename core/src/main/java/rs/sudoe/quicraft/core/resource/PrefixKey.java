// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;

/** Canonical fair-accounting prefix keys (protocol.md 13.12). */
public final class PrefixKey {
    private final byte[] bytes;
    private final int prefixBits;
    private PrefixKey(byte[] bytes, int prefixBits) { this.bytes = bytes; this.prefixBits = prefixBits; }

    /** Maps IPv4 to /24 and IPv6 to /64, as required for source caps. */
    public static PrefixKey of(InetAddress address) {
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address || isMapped(raw)) return new PrefixKey(Arrays.copyOfRange(raw, raw.length - 4, raw.length), 24);
        if (!(address instanceof Inet6Address)) throw new IllegalArgumentException("unsupported address");
        return new PrefixKey(Arrays.copyOf(raw, 8), 64);
    }

    /** Maps IPv4 to /32 and IPv6 to /48 for overflow history hashing. */
    public static PrefixKey overflow(InetAddress address) {
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address || isMapped(raw)) return new PrefixKey(Arrays.copyOfRange(raw, raw.length - 4, raw.length), 32);
        if (!(address instanceof Inet6Address)) throw new IllegalArgumentException("unsupported address");
        return new PrefixKey(Arrays.copyOf(raw, 6), 48);
    }

    /** Returns a keyed overflow bucket index in [0, 256). */
    public int bucket(byte[] secret) {
        if (secret == null || secret.length == 0) throw new IllegalArgumentException("secret");
        int hash = 0x811c9dc5;
        for (byte b : secret) hash = (hash ^ (b & 255)) * 0x01000193;
        for (byte b : bytes) hash = (hash ^ (b & 255)) * 0x01000193;
        hash ^= prefixBits;
        return hash & 255;
    }

    private static boolean isMapped(byte[] a) {
        if (a.length != 16) return false;
        for (int i = 0; i < 10; i++) if (a[i] != 0) return false;
        return a[10] == (byte) 255 && a[11] == (byte) 255;
    }
    @Override public boolean equals(Object o) { return o instanceof PrefixKey && prefixBits == ((PrefixKey)o).prefixBits && Arrays.equals(bytes, ((PrefixKey)o).bytes); }
    @Override public int hashCode() { return 31 * Arrays.hashCode(bytes) + prefixBits; }
}
