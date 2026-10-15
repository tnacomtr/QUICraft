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
 *
 * <p>The parse entry points differ in the ALPNs they accept: {@link #extract(String)} keeps the
 * shipped {@code quicraft/2}-only behaviour and reads no routing, so a {@code quicraft/3}
 * advertisement is no advertisement to it and every shipped caller stays on TCP (13.1), while
 * {@link #extractForSelection(String)} also reads {@code quicraft/3} and its routing member for the
 * 12/13 selection.
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
        return toJson(routing);
    }

    private String toJson(Map<Integer, String> routing) {
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
     * {@link #STATUS_MAX_LENGTH} even without the routing member. A response that overflows only
     * because of routing is retried once with the routing member dropped: routing is optional and
     * only a {@code /3} selection needs it, so that still advertises a valid listener (13.1, §1).
     * Never throws.
     */
    public String insertInto(String statusJson) {
        try {
            Object parsed = Json.parse(statusJson);
            if (!(parsed instanceof Map) || ((Map<?, ?>) parsed).containsKey(Protocol.ADVERTISEMENT_KEY)) {
                return statusJson;
            }
            int close = statusJson.lastIndexOf('}');
            String separator = ((Map<?, ?>) parsed).isEmpty() ? "" : ",";
            String result = splice(statusJson, close, separator, toJson());
            if (result.length() <= STATUS_MAX_LENGTH) {
                return result;
            }
            if (!routing.isEmpty()) {
                String withoutRouting = splice(statusJson, close, separator,
                        toJson(Collections.<Integer, String>emptyMap()));
                if (withoutRouting.length() <= STATUS_MAX_LENGTH) {
                    return withoutRouting;
                }
            }
            return statusJson;
        } catch (RuntimeException e) {
            return statusJson;
        }
    }

    /** {@code statusJson} with the advertisement member built from {@code memberJson} spliced in. */
    private static String splice(String statusJson, int close, String separator, String memberJson) {
        String member = Json.quote(Protocol.ADVERTISEMENT_KEY) + ":" + memberJson;
        return statusJson.substring(0, close) + separator + member + statusJson.substring(close);
    }

    /**
     * Reads a v1 advertisement from a raw status response, for the ALPN this build offers:
     * {@code quicraft/2} only, exactly as before 13.1. Empty if the member is absent, malformed, of
     * an unknown version, or for another ALPN; a {@code quicraft/3} advertisement is deliberately
     * not recognized here, so every shipped caller (13.1: clients that do not recognize {@code /3})
     * treats it as no advertisement and uses TCP. Unknown fields inside the member are ignored, and
     * neither is the routing member read: it only matters for the {@code /3} selection, so the value
     * this returns carries no routing (13.1). Never throws.
     *
     * <p>Callers that implement the pre-connect selection of 13.1 use
     * {@link #extractForSelection(String)} instead.
     */
    public static Optional<Advertisement> extract(String statusJson) {
        return extract(statusJson, false);
    }

    /**
     * Reads a v1 advertisement for the pre-connect selection of 13.1: the same rules as
     * {@link #extract(String)}, but {@code quicraft/3} is accepted alongside {@code quicraft/2}, and
     * the optional routing member is parsed. A caller that races or connects from this advertisement
     * MUST have the {@code /3} rules of 12 and 13, which is why the shipped parse entry point stays
     * {@link #extract(String)}. Never throws.
     */
    public static Optional<Advertisement> extractForSelection(String statusJson) {
        return extract(statusJson, true);
    }

    private static Optional<Advertisement> extract(String statusJson, boolean acceptV3) {
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
                    || !isKnownAlpn(alpn, acceptV3) || !(fp instanceof String)) {
                return Optional.empty();
            }
            return Optional.of(new Advertisement(version, port, (String) alpn,
                    Fingerprint.parse((String) fp),
                    acceptV3 ? parseRouting(ad.get(ROUTING_KEY)) : Collections.<Integer, String>emptyMap()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The ALPNs this build understands in the member: the shipped {@code quicraft/2} always, and the
     * proposed {@code quicraft/3} only for the selection parse (13.1). Any other value keeps the v1
     * behaviour, "no advertisement".
     */
    private static boolean isKnownAlpn(Object alpn, boolean acceptV3) {
        return Protocol.ALPN.equals(alpn) || (acceptV3 && Protocol.ALPN_V3.equals(alpn));
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
