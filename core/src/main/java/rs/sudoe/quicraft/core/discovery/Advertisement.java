// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.discovery;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * The {@code "quicraft:quic"} member of the status JSON (docs/protocol.md §1).
 *
 * <p>Server side: {@link #insertInto} adds it to a serialized status response unless that would
 * break the 32767-character cap vanilla clients enforce. Client side: {@link #extract} reads it
 * from the raw status JSON. Any problem means "no advertisement", which means TCP.
 */
public final class Advertisement {
    /** Status response cap in UTF-16 code units (verified for 26.1.2, docs/protocol.md §1). */
    public static final int STATUS_MAX_LENGTH = 32767;

    private final int version;
    private final int port;
    private final String alpn;
    private final Fingerprint fingerprint;

    private Advertisement(int version, int port, String alpn, Fingerprint fingerprint) {
        this.version = version;
        this.port = port;
        this.alpn = alpn;
        this.fingerprint = fingerprint;
    }

    public static Advertisement v1(int port, Fingerprint fingerprint) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        return new Advertisement(Protocol.VERSION, port, Protocol.ALPN, Objects.requireNonNull(fingerprint));
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

    /** The member's value, e.g. {@code {"v":1,"port":25565,"alpn":"quicraft/1","fp":"sha256:…"}}. */
    public String toJson() {
        return "{\"v\":" + version + ",\"port\":" + port + ",\"alpn\":" + Json.quote(alpn)
                + ",\"fp\":" + Json.quote(fingerprint.toString()) + "}";
    }

    /**
     * Returns {@code statusJson} with this advertisement added as a top-level member. Returns
     * the input unchanged if it is not a JSON object, already has the member, or would exceed
     * {@link #STATUS_MAX_LENGTH}. Never throws.
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
     * fields inside the member are ignored. Never throws.
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
                    || !Protocol.ALPN.equals(alpn) || !(fp instanceof String)) {
                return Optional.empty();
            }
            return Optional.of(new Advertisement(version, port, (String) alpn, Fingerprint.parse((String) fp)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
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
        return version == a.version && port == a.port && alpn.equals(a.alpn) && fingerprint.equals(a.fingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, port, alpn, fingerprint);
    }

    @Override
    public String toString() {
        return toJson();
    }
}
