// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.net.IDN;
import java.util.Locale;

/**
 * The key a pin and its re-bind state are stored under: one logical server, as the player typed it.
 *
 * <p>docs/protocol.md 12.15 keys pins by the normalized logical host and the logical TCP port, and
 * 12.16 keys the re-bind backoff the same way. Never by an SRV target or a transient resolved
 * address: a server whose name resolves elsewhere, or that is reached through a service record,
 * keeps one pin and one backoff window. The API always takes the logical name, never a resolved IP.
 *
 * <p>Normalization makes every spelling of the same server share one entry:</p>
 *
 * <ul>
 *   <li>A literal IPv4 address becomes its canonical dotted-quad form, with no leading zeros.</li>
 *   <li>A literal IPv6 address becomes its canonical lowercase compressed form (RFC 5952), without
 *       brackets and without a zone identifier: {@code [2001:0DB8:0:0:0:0:0:1]} and
 *       {@code 2001:db8::1} are the same key. Brackets are only accepted around a literal.</li>
 *   <li>Any other host is {@link IDN#toASCII(String) IDNA} ASCII, lowercased, with one trailing dot
 *       removed: {@code MC.Example.COM.} and {@code mc.example.com} are the same key.</li>
 * </ul>
 *
 * <p>Anything else is rejected with an {@link IllegalArgumentException}: an empty host, a zone
 * identifier ({@code fe80::1%eth0} names a local interface, not a server), a malformed literal, an
 * empty label or a label longer than 63 characters. A host is never resolved here, so normalization
 * never touches the network and never depends on DNS.</p>
 */
public final class PinKey {
    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;

    private final String host;
    private final int port;

    private PinKey(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * The key for {@code host} and {@code port}.
     *
     * @throws IllegalArgumentException when the host cannot be normalized or the port is not 1..65535
     */
    public static PinKey of(String host, int port) {
        return new PinKey(normalizeHost(host), checkPort(port));
    }

    /** The normalized logical host: a canonical IP literal, or lowercase IDNA ASCII. */
    public String host() {
        return host;
    }

    /** The logical TCP port the player connects to. */
    public int port() {
        return port;
    }

    /**
     * The normalized form of {@code host}.
     *
     * @throws IllegalArgumentException when {@code host} is null, empty or not a usable host
     */
    public static String normalizeHost(String host) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host is required");
        }
        if (host.indexOf('%') >= 0) {
            throw new IllegalArgumentException("a zone identifier is not part of a server name: " + host);
        }
        char first = host.charAt(0);
        if (first == '[' && host.length() >= 2 && host.charAt(host.length() - 1) == ']') {
            String inner = host.substring(1, host.length() - 1);
            if (!isLiteralCandidate(inner)) {
                throw new IllegalArgumentException("brackets are only allowed around an IP literal: " + host);
            }
            return canonicalLiteral(inner);
        }
        if (isLiteralCandidate(host)) {
            return canonicalLiteral(host);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a usable host name: " + host, e);
        }
        ascii = ascii.toLowerCase(Locale.ROOT);
        if (ascii.endsWith(".")) {
            ascii = ascii.substring(0, ascii.length() - 1);
        }
        checkName(ascii, host);
        return ascii;
    }

    /** The map key of a normalized {@code key}; a normalized host never contains {@code |}. */
    static String mapKey(PinKey key) {
        return key.host + "|" + key.port;
    }

    /** The port for a key, or an {@link IllegalArgumentException} when it is not a TCP port. */
    static int checkPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port is not a TCP port: " + port);
        }
        return port;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PinKey)) {
            return false;
        }
        PinKey key = (PinKey) other;
        return port == key.port && host.equals(key.host);
    }

    @Override
    public int hashCode() {
        return 31 * host.hashCode() + port;
    }

    /** {@code host:port}, with an IPv6 host in brackets so the port stays readable. */
    @Override
    public String toString() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }

    /**
     * Whether {@code value} must be parsed as an IP literal rather than as a name: it has a colon,
     * or it is made only of digits and dots. Mixed spellings are rejected rather than resolved.
     */
    private static boolean isLiteralCandidate(String value) {
        if (value.indexOf(':') >= 0) {
            return true;
        }
        boolean dotted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                dotted = true;
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return dotted;
    }

    private static String canonicalLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            return formatIpv6(parseIpv6(value));
        }
        return formatIpv4(parseIpv4(value));
    }

    /**
     * A name that is not an IP literal: lowercase ASCII, at most 253 characters, every label
     * non-empty and at most 63 characters, and every character one of {@code a-z 0-9 . _ -}.
     * Underscores are accepted because a real server name may contain them; uppercase and non-ASCII
     * never reach this check, since {@link IDN#toASCII(String)} has already folded them.
     */
    private static void checkName(String name, String original) {
        if (name.isEmpty() || name.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("host name is empty or longer than " + MAX_HOST_LENGTH + ": " + original);
        }
        if (name.charAt(0) == '.' || name.charAt(name.length() - 1) == '.') {
            throw new IllegalArgumentException("host name has an empty label: " + original);
        }
        int labelLength = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                if (labelLength == 0) {
                    throw new IllegalArgumentException("host name has an empty label: " + original);
                }
                labelLength = 0;
                continue;
            }
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' && c != '_') {
                throw new IllegalArgumentException("host name has a character that is not allowed: " + original);
            }
            labelLength++;
            if (labelLength > MAX_LABEL_LENGTH) {
                throw new IllegalArgumentException("host name label is longer than " + MAX_LABEL_LENGTH + ": " + original);
            }
        }
    }

    /** The 32 bits of a literal IPv4 address, rejecting leading zeros and anything out of range. */
    private static int parseIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("not an IPv4 literal: " + value);
        }
        int address = 0;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                throw new IllegalArgumentException("not an IPv4 literal: " + value);
            }
            if (octet.length() > 1 && octet.charAt(0) == '0') {
                throw new IllegalArgumentException("IPv4 literal has a leading zero: " + value);
            }
            int number = 0;
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException("not an IPv4 literal: " + value);
                }
                number = number * 10 + (c - '0');
            }
            if (number > 255) {
                throw new IllegalArgumentException("IPv4 literal is out of range: " + value);
            }
            address = (address << 8) | number;
        }
        return address;
    }

    private static String formatIpv4(int address) {
        return ((address >>> 24) & 0xFF) + "." + ((address >>> 16) & 0xFF) + "."
                + ((address >>> 8) & 0xFF) + "." + (address & 0xFF);
    }

    /** The eight 16-bit groups of a literal IPv6 address, one {@code ::} allowed. */
    private static int[] parseIpv6(String value) {
        int lastColon = value.lastIndexOf(':');
        if (lastColon < 0) {
            throw new IllegalArgumentException("not an IPv6 literal: " + value);
        }
        String last = value.substring(lastColon + 1);
        int[] trailing = new int[0];
        String rest = value;
        if (last.indexOf('.') >= 0) {
            int quad = parseIpv4(last);
            trailing = new int[] {(quad >>> 16) & 0xFFFF, quad & 0xFFFF};
            rest = value.substring(0, lastColon);
            if (rest.endsWith(":")) {
                // The colon before the quad is the second half of "::"; keep the pair intact.
                rest = rest + ":";
            }
        }
        int compress = rest.indexOf("::");
        if (compress >= 0 && rest.indexOf("::", compress + 2) >= 0) {
            throw new IllegalArgumentException("IPv6 literal has more than one :: : " + value);
        }
        int[] head = groups(compress < 0 ? rest : rest.substring(0, compress), value);
        int[] tail = compress < 0 ? new int[0] : groups(rest.substring(compress + 2), value);
        int count = head.length + tail.length + trailing.length;
        if (compress < 0 && count != 8) {
            throw new IllegalArgumentException("IPv6 literal needs eight groups: " + value);
        }
        if (compress >= 0 && count > 7) {
            throw new IllegalArgumentException("IPv6 literal :: stands for no zero group: " + value);
        }
        int[] address = new int[8];
        System.arraycopy(head, 0, address, 0, head.length);
        System.arraycopy(tail, 0, address, 8 - tail.length - trailing.length, tail.length);
        System.arraycopy(trailing, 0, address, 8 - trailing.length, trailing.length);
        return address;
    }

    private static int[] groups(String text, String whole) {
        if (text.isEmpty()) {
            return new int[0];
        }
        String[] parts = text.split(":", -1);
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = group(parts[i], whole);
        }
        return values;
    }

    private static int group(String part, String whole) {
        if (part.isEmpty() || part.length() > 4) {
            throw new IllegalArgumentException("IPv6 literal has a bad group: " + whole);
        }
        int value = 0;
        for (int i = 0; i < part.length(); i++) {
            int digit = hexDigit(part.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("IPv6 literal has a bad group: " + whole);
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    /**
     * The canonical text of an IPv6 address: lowercase, no leading zeros in a group, and the
     * leftmost longest run of two or more zero groups replaced by {@code ::} (RFC 5952).
     */
    private static String formatIpv6(int[] address) {
        int bestStart = -1;
        int bestLength = 0;
        int i = 0;
        while (i < 8) {
            if (address[i] != 0) {
                i++;
                continue;
            }
            int end = i;
            while (end < 8 && address[end] == 0) {
                end++;
            }
            if (end - i > bestLength) {
                bestStart = i;
                bestLength = end - i;
            }
            i = end;
        }
        if (bestLength < 2) {
            bestStart = -1;
        }
        StringBuilder sb = new StringBuilder(39);
        for (int at = 0; at < 8; at++) {
            if (at == bestStart) {
                sb.append("::");
                at += bestLength - 1;
                continue;
            }
            if (at > 0 && at != bestStart + bestLength) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(address[at]));
        }
        return sb.toString();
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
