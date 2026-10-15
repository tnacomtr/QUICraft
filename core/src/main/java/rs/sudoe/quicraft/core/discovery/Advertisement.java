// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * The {@code "quicraft:quic"} member of the status JSON (docs/protocol.md §1, 13.1).
 *
 * <p>Server side: {@link #insertInto} adds it to a serialized status response unless that would
 * break the 32767-character cap vanilla clients enforce. Client side: {@link #extract} reads it
 * from the raw status JSON. Any problem means "no advertisement", which means TCP.
 *
 * <p>The optional {@code "routing"} member (13.1) maps decimal unsigned protocol-number strings to
 * lowercase {@code sha256:} routing-resource digests (13.7). It is used by the pre-connect
 * selection of {@code core.connect.TransportSelection}. A malformed routing member never
 * invalidates an otherwise valid advertisement: it is treated as absent, so {@code quicraft/2} and
 * TCP keep working exactly as before (12.1, 13.1). The same holds for the entry bound
 * {@link #MAX_ROUTING_ENTRIES}.
 */
public final class Advertisement {
    /** Status response cap in UTF-16 code units (verified for 26.1.2, docs/protocol.md §1). */
    public static final int STATUS_MAX_LENGTH = 32767;
    /** Optional member holding protocol-number to routing-digest pairs (13.1). */
    public static final String ROUTING_KEY = "routing";
    /**
     * The most routing entries an advertisement may carry. More entries make the routing member
     * unusable (treated as absent), never invalidate the advertisement as a whole.
     */
    public static final int MAX_ROUTING_ENTRIES = 64;

    private static final String DIGEST_PREFIX = "sha256:";
    private static final int DIGEST_HEX_LENGTH = 64;
    private static final int MAX_PROTOCOL_NUMBER_DIGITS = 10;

    private final int version;
    private final int port;
    private final String alpn;
    private final Fingerprint fingerprint;
    private final Map<Integer, String> routing;

    private Advertisement(int version, int port, String alpn, Fingerprint fingerprint,
            Map<Integer, String> routing) {
        this.version = version;
        this.port = port;
        this.alpn = alpn;
        this.fingerprint = fingerprint;
        this.routing = routing;
    }

    public static Advertisement v1(int port, Fingerprint fingerprint) {
        return of(Protocol.ALPN, port, fingerprint, Collections.<Integer, String>emptyMap());
    }

    /**
     * An advertisement with an explicit ALPN and the optional routing digests (13.1).
     *
     * @param alpn {@link Protocol#ALPN} or {@link Protocol#ALPN_V3}; anything else is rejected, so
     *     an advertisement this build can serve is always one {@link #extract} would accept
     * @param routing protocol number to {@code sha256:<64 lowercase hex>} digest, at most
     *     {@link #MAX_ROUTING_ENTRIES} entries; empty for the unchanged v1 advertisement
     * @throws IllegalArgumentException if the ALPN, port or a routing entry is invalid
     */
    public static Advertisement of(String alpn, int port, Fingerprint fingerprint,
            Map<Integer, String> routing) {
        Objects.requireNonNull(alpn, "alpn");
        if (!Protocol.ALPN.equals(alpn) && !Protocol.ALPN_V3.equals(alpn)) {
            throw new IllegalArgumentException("unknown alpn: " + alpn);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        Objects.requireNonNull(fingerprint, "fingerprint");
        return new Advertisement(Protocol.VERSION, port, alpn, fingerprint, validRouting(routing));
    }

    public int port() {
        return port;
    }

    public Fingerprint fingerprint() {
        return fingerprint;
    }

    public String alpn() {
        return alpn;
    }

    public int version() {
        return version;
    }

    /**
     * The routing digests for {@code quicraft/3}, keyed by Minecraft protocol number, ascending.
     * Empty when the member is absent, malformed or over {@link #MAX_ROUTING_ENTRIES} entries
     * (13.1). Unmodifiable and immutable.
     */
    public Map<Integer, String> routing() {
        return routing;
    }

    /**
     * The advertised digest for {@code protocolNumber}, i.e.
     * {@code "sha256:<64 lowercase hex>"}, or empty when the advertisement has no usable entry for
     * it (13.1: never guess a routing table).
     */
    public Optional<String> routingDigest(int protocolNumber) {
        return Optional.ofNullable(routing.get(protocolNumber));
    }

    /**
     * The member's value, e.g. {@code {"v":1,"port":25565,"alpn":"quicraft/2","fp":"sha256:…"}}, with
     * the routing member appended when present. Routing keys are written in ascending protocol
     * order, so the encoding of a given advertisement is deterministic.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"v\":").append(version).append(",\"port\":").append(port)
                .append(",\"alpn\":").append(Json.quote(alpn))
                .append(",\"fp\":").append(Json.quote(fingerprint.toString()));
        if (!routing.isEmpty()) {
            sb.append(',').append(Json.quote(ROUTING_KEY)).append(":{");
            boolean first = true;
            for (Map.Entry<Integer, String> entry : routing.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(Json.quote(entry.getKey().toString())).append(':')
                        .append(Json.quote(entry.getValue()));
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    /**
     * Returns {@code statusJson} with this advertisement added as a top-level member. Returns
     * the input unchanged if it is not a JSON object, already has the member, or would exceed
     * {@link #STATUS_MAX_LENGTH} (13.1: a routing member obeys the same cap). Never throws.
     */
    public String insertInto(String statusJson) {
        try {
            Object parsed = Json.parse(statusJson);
            if (!(parsed instanceof Map) || ((Map<?, ?>) parsed).containsKey(Protocol.ADVERTISEMENT_KEY)) {
                return statusJson;
            }
            int close = statusJson.lastIndexOf('}');
            String member = Json.quote(Protocol.ADVERTISEMENT_KEY) + ":" + toJson();
            String separator = ((Map<?, ?>) parsed).isEmpty() ? "" : ",";
            String result = statusJson.substring(0, close) + separator + member + statusJson.substring(close);
            return result.length() <= STATUS_MAX_LENGTH ? result : statusJson;
        } catch (RuntimeException e) {
            return statusJson;
        }
    }

    /**
     * Reads a v1 advertisement from a raw status response. Empty if the member is absent,
     * malformed, of an unknown version, or for an ALPN this client doesn't offer. Unknown
     * fields inside the member are ignored, and so is an unusable routing member, which never
     * invalidates the rest (13.1). Never throws.
     */
    public static Optional<Advertisement> extract(String statusJson) {
        try {
            Object parsed = Json.parse(statusJson);
            if (!(parsed instanceof Map)) {
                return Optional.empty();
            }
            Object member = ((Map<?, ?>) parsed).get(Protocol.ADVERTISEMENT_KEY);
            if (!(member instanceof Map)) {
                return Optional.empty();
            }
            Map<?, ?> ad = (Map<?, ?>) member;
            Integer version = intValue(ad.get("v"));
            Integer port = intValue(ad.get("port"));
            Object alpn = ad.get("alpn");
            Object fp = ad.get("fp");
            if (version == null || version != Protocol.VERSION || port == null || port < 1 || port > 65535
                    || !isKnownAlpn(alpn) || !(fp instanceof String)) {
                return Optional.empty();
            }
            return Optional.of(new Advertisement(version, port, (String) alpn,
                    Fingerprint.parse((String) fp), parseRouting(ad.get(ROUTING_KEY))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The ALPNs this build understands: the shipped {@code quicraft/2} and the proposed
     * {@code quicraft/3} (13.1). Any other value keeps the v1 behaviour, "no advertisement".
     */
    private static boolean isKnownAlpn(Object alpn) {
        return Protocol.ALPN.equals(alpn) || Protocol.ALPN_V3.equals(alpn);
    }

    /**
     * The routing member, or an empty map when it is absent, not a JSON object, over
     * {@link #MAX_ROUTING_ENTRIES} entries, or holds one malformed entry. Any of those makes the
     * field unusable as a whole (13.1): a partial mapping would silently drop digests, and
     * treating a malformed field as fatal would break otherwise valid {@code /2} advertisements.
     * Keys are canonical decimal protocol numbers, values lowercase {@code sha256:} digests.
     */
    private static Map<Integer, String> parseRouting(Object value) {
        if (!(value instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<?, ?> map = (Map<?, ?>) value;
        if (map.size() > MAX_ROUTING_ENTRIES) {
            return Collections.emptyMap();
        }
        Map<Integer, String> routing = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Integer protocolNumber = protocolNumber(entry.getKey());
            Object digest = entry.getValue();
            if (protocolNumber == null || !isRoutingDigest(digest)) {
                return Collections.emptyMap();
            }
            routing.put(protocolNumber, (String) digest);
        }
        return Collections.unmodifiableMap(routing);
    }

    /** The routing map of {@link #of}, rejected entry by entry because a caller built it. */
    private static Map<Integer, String> validRouting(Map<Integer, String> routing) {
        if (routing == null || routing.isEmpty()) {
            return Collections.emptyMap();
        }
        if (routing.size() > MAX_ROUTING_ENTRIES) {
            throw new IllegalArgumentException("more than " + MAX_ROUTING_ENTRIES
                    + " routing entries: " + routing.size());
        }
        Map<Integer, String> valid = new TreeMap<>();
        for (Map.Entry<Integer, String> entry : routing.entrySet()) {
            Integer protocolNumber = entry.getKey();
            if (protocolNumber == null || protocolNumber < 0) {
                throw new IllegalArgumentException("routing protocol number out of range: "
                        + protocolNumber);
            }
            if (!isRoutingDigest(entry.getValue())) {
                throw new IllegalArgumentException("routing digest for protocol " + protocolNumber
                        + " is not \"sha256:<64 lowercase hex>\": " + entry.getValue());
            }
            valid.put(protocolNumber, entry.getValue());
        }
        return Collections.unmodifiableMap(valid);
    }

    /**
     * The protocol number a routing key spells, or {@code null} when it is not a canonical decimal
     * unsigned integer that fits in an {@code int}: a sign, a leading zero other than {@code "0"}
     * and a value over {@link Integer#MAX_VALUE} are all rejected.
     */
    private static Integer protocolNumber(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        String text = (String) key;
        if (text.isEmpty() || text.length() > MAX_PROTOCOL_NUMBER_DIGITS) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        if (text.charAt(0) == '0' && text.length() != 1) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True for exactly {@code sha256:} followed by 64 lowercase hex digits (13.1, 13.7). */
    private static boolean isRoutingDigest(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String text = (String) value;
        if (!text.startsWith(DIGEST_PREFIX) || text.length() != DIGEST_PREFIX.length() + DIGEST_HEX_LENGTH) {
            return false;
        }
        for (int i = DIGEST_PREFIX.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static Integer intValue(Object value) {
        if (!(value instanceof BigDecimal)) {
            return null;
        }
        try {
            return ((BigDecimal) value).intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Advertisement)) {
            return false;
        }
        Advertisement a = (Advertisement) o;
        return version == a.version && port == a.port && alpn.equals(a.alpn)
                && fingerprint.equals(a.fingerprint) && routing.equals(a.routing);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, port, alpn, fingerprint, routing);
    }

    @Override
    public String toString() {
        return toJson();
    }
}
