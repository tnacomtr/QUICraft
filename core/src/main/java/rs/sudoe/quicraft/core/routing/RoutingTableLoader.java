// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import rs.sudoe.quicraft.core.discovery.Json;

/**
 * Parses and validates a routing resource (docs/protocol.md 13.6, 13.7).
 *
 * <p>A resource is UTF-8 JSON with {@code schema:1}, {@code protocol:<number>}, {@code default} and
 * a list of {@code routes}, each carrying {@code state}, {@code direction}, {@code packet_id},
 * {@code lane} and {@code dependency}. What ships in core's main resources is the 26.x placeholder
 * of 13.7: one resource per protocol number that routes every packet to CONTROL, until the real
 * tables land with the version spread. The spec's synthetic schema example lives in
 * {@code core/src/test/resources} as a fixture, so a shipped jar cannot select it.
 *
 * <p>The validator rejects, with the rule it broke in the message: an unsupported {@code schema}; a
 * duplicate route; a repeated JSON member name in any object (13.7, so a duplicate is never
 * silently last-wins); a {@code protocol} other than the number {@link #loadResource(int)} was
 * asked for; an unknown field at either level; an unknown state, lane, direction or dependency;
 * a missing or wrongly typed field; a {@code default} other than {@code control}; a packet id that
 * is not a plain nonnegative integer no larger than {@link Integer#MAX_VALUE} (one Minecraft
 * VarInt), so the exponent form {@code 1e2}, the fraction {@code 1.0} and the negative zero
 * {@code -0} are rejected too; a dependency that does not match the lane (13.7: {@code fence} for
 * CONTROL, {@code lane} otherwise); an auxiliary lane in a state other than PLAY, or any key the
 * caller declares MUST use CONTROL (13.5); and input that is not valid UTF-8. Every value a
 * rejection renders into its message is truncated to {@value #MAX_DIAGNOSTIC} characters, so a
 * hostile resource cannot make a message huge.
 *
 * <p>13.5's must-use-CONTROL set is version-specific packet-id knowledge a routing resource cannot
 * express, so it is a resource of its own (13.5, {@link #mandatoryControlResourcePath(int)}).
 * {@link #loadResource(int)} always reads it and fails when it is missing: the rule is never
 * enforced against a silently empty set.
 *
 * <p>JSON parsing uses the strict parser core already ships for status responses
 * ({@link rs.sudoe.quicraft.core.discovery.Json#parseRejectingDuplicateKeys(String)}); no dependency
 * is added. That parser bounds the decoded text at 1 MiB, which no in-tree table comes close to.
 */
public final class RoutingTableLoader {
    /** The only supported resource schema (13.7). */
    public static final int SCHEMA_VERSION = 1;
    /** The only defined default lane (13.7). */
    public static final Lane DEFAULT_LANE = Lane.CONTROL;
    /** Classpath directory holding one resource per protocol number, named {@code protocol-<n>.json}. */
    public static final String RESOURCE_DIR = "rs/sudoe/quicraft/core/routing";
    /** The most characters of any single value a diagnostic message may quote back. */
    public static final int MAX_DIAGNOSTIC = 64;

    /**
     * The largest routing or mandatory-CONTROL resource accepted, in bytes. Checked while reading
     * and before UTF-8 decoding, so an oversized resource is rejected before it is buffered whole.
     */
    public static final int MAX_RESOURCE_BYTES = 1 << 20;

    private static final String SCHEMA_FIELD = "schema";
    private static final String PROTOCOL_FIELD = "protocol";
    private static final String DEFAULT_FIELD = "default";
    private static final String ROUTES_FIELD = "routes";
    private static final String STATE_FIELD = "state";
    private static final String DIRECTION_FIELD = "direction";
    private static final String PACKET_ID_FIELD = "packet_id";
    private static final String LANE_FIELD = "lane";
    private static final String DEPENDENCY_FIELD = "dependency";
    private static final String MANDATORY_CONTROL_FIELD = "mandatory_control";
    private static final String MANDATORY_CONTROL_SUFFIX = "-mandatory-control.json";
    private static final String MANDATORY_RESOURCE = "mandatory-CONTROL resource";

    private static final Set<String> ROOT_FIELDS = unmodifiableSet(
            SCHEMA_FIELD, PROTOCOL_FIELD, DEFAULT_FIELD, ROUTES_FIELD);
    private static final Set<String> ENTRY_FIELDS = unmodifiableSet(
            STATE_FIELD, DIRECTION_FIELD, PACKET_ID_FIELD, LANE_FIELD, DEPENDENCY_FIELD);
    private static final Set<String> MANDATORY_ROOT_FIELDS = unmodifiableSet(
            SCHEMA_FIELD, PROTOCOL_FIELD, MANDATORY_CONTROL_FIELD);
    private static final Set<String> MANDATORY_KEY_FIELDS = unmodifiableSet(
            STATE_FIELD, DIRECTION_FIELD, PACKET_ID_FIELD);

    private RoutingTableLoader() {}

    /** The classpath resource path of the table for {@code protocolNumber}. */
    public static String resourcePath(int protocolNumber) {
        return RESOURCE_DIR + "/protocol-" + protocolNumber + ".json";
    }

    /**
     * The classpath resource path of the 13.5 must-use-CONTROL set for {@code protocolNumber}. It
     * sits next to the table, because it is knowledge about the same protocol number.
     */
    public static String mandatoryControlResourcePath(int protocolNumber) {
        return RESOURCE_DIR + "/protocol-" + protocolNumber + MANDATORY_CONTROL_SUFFIX;
    }

    /**
     * Loads and validates the table for {@code protocolNumber} from the classpath, together with
     * that protocol's 13.5 must-use-CONTROL set.
     *
     * <p>13.7 stores one resource per protocol number, so the resource's own {@code protocol} field
     * must equal {@code protocolNumber}; a mismatch is rejected rather than used. The 13.5 set is
     * read from {@link #mandatoryControlResourcePath(int)}; when it is missing or invalid the load
     * fails, because an empty set would silently stop enforcing 13.5.
     *
     * @throws IOException if the table resource is missing or unreadable
     * @throws RoutingTableException if the table, or its mandatory-CONTROL set, is missing or invalid
     */
    public static RoutingTable loadResource(int protocolNumber) throws IOException {
        String path = resourcePath(protocolNumber);
        byte[] bytes = readResource(path);
        if (bytes == null) {
            throw new IOException("routing resource not found on the classpath: " + path);
        }
        Set<RouteKey> mandatoryControl = loadMandatoryControl(protocolNumber);
        RoutingTable table = parse(bytes, mandatoryControl);
        if (table.protocolNumber() != protocolNumber) {
            throw new RoutingTableException("13.7: " + path + " declares protocol "
                    + table.protocolNumber() + ", not the requested " + protocolNumber);
        }
        return table;
    }

    /**
     * Loads and validates a resource from the classpath, rejecting any {@code mandatoryControl} key
     * routed to an auxiliary lane.
     *
     * @param resourcePath classpath path, e.g. {@code rs/sudoe/quicraft/core/routing/protocol-763.json}
     * @param mandatoryControl lookup keys that MUST use CONTROL for this protocol number (13.5); an
     *     explicit empty set means the caller knows of none
     * @throws IOException if the resource is missing or unreadable
     * @throws RoutingTableException if the resource is invalid
     */
    public static RoutingTable loadResource(String resourcePath, Set<RouteKey> mandatoryControl)
            throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(mandatoryControl, "mandatoryControl");
        byte[] bytes = readResource(resourcePath);
        if (bytes == null) {
            throw new IOException("routing resource not found on the classpath: " + resourcePath);
        }
        return parse(bytes, mandatoryControl);
    }

    /** Parses {@code json} as UTF-8 resource bytes. Digest R covers those exact bytes. */
    public static RoutingTable parse(String json) {
        Objects.requireNonNull(json, "json");
        return parse(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses the exact resource bytes {@code bytes}; digest R is SHA-256 over them (13.7). */
    public static RoutingTable parse(byte[] bytes) {
        return parse(bytes, Collections.<RouteKey>emptySet());
    }

    /** Parses {@code json} as UTF-8 resource bytes, rejecting any {@code mandatoryControl} key
     * routed to an auxiliary lane (13.5). */
    public static RoutingTable parse(String json, Set<RouteKey> mandatoryControl) {
        Objects.requireNonNull(json, "json");
        return parse(json.getBytes(StandardCharsets.UTF_8), mandatoryControl);
    }

    /**
     * Parses the exact resource bytes {@code bytes}, rejecting any {@code mandatoryControl} key
     * routed to an auxiliary lane (13.5).
     *
     * @param mandatoryControl lookup keys that MUST use CONTROL for this protocol number; the
     *     version-specific packet-id knowledge behind 13.5 that a routing resource cannot express
     */
    public static RoutingTable parse(byte[] bytes, Set<RouteKey> mandatoryControl) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(mandatoryControl, "mandatoryControl");
        Object parsed = decode(bytes, "13.7");
        if (!(parsed instanceof Map)) {
            throw new RoutingTableException("13.7: the resource must be a JSON object");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        requireFields(root, ROOT_FIELDS, "resource", "13.7");

        int schema = intField(root, SCHEMA_FIELD, "resource");
        if (schema != SCHEMA_VERSION) {
            throw new RoutingTableException("13.7: unsupported schema " + schema
                    + ", only schema " + SCHEMA_VERSION + " is supported");
        }

        int protocolNumber = intField(root, PROTOCOL_FIELD, "resource");
        if (protocolNumber < 1) {
            throw new RoutingTableException("13.7: protocol number out of range: " + protocolNumber);
        }

        Object defaultLane = root.get(DEFAULT_FIELD);
        if (!(defaultLane instanceof String) || !DEFAULT_LANE.wireName().equals(defaultLane)) {
            throw new RoutingTableException("13.7: default must be \"" + DEFAULT_LANE.wireName()
                    + "\", got " + describe(defaultLane));
        }

        Object routesValue = root.get(ROUTES_FIELD);
        if (!(routesValue instanceof List)) {
            throw new RoutingTableException("13.7: routes must be an array, got "
                    + describe(routesValue));
        }

        Map<RouteKey, Route> routes = new LinkedHashMap<>();
        int index = 0;
        for (Object entry : (List<?>) routesValue) {
            String where = "routes[" + index + "]";
            if (!(entry instanceof Map)) {
                throw new RoutingTableException("13.7: " + where + " must be a JSON object");
            }
            Map<?, ?> object = (Map<?, ?>) entry;
            requireFields(object, ENTRY_FIELDS, where, "13.7");

            ProtocolState state = stateField(object, where);
            Direction direction = directionField(object, where);
            int packetId = intField(object, PACKET_ID_FIELD, where);
            Lane lane = laneField(object, where);
            Dependency dependency = dependencyField(object, where, lane);

            RouteKey key = new RouteKey(protocolNumber, state, direction, packetId);
            Route previous = routes.put(key, new Route(lane, dependency));
            if (previous != null) {
                throw new RoutingTableException("13.7: duplicate route for " + key + " in " + where);
            }
            if (!lane.isControl()) {
                if (!state.isPlay()) {
                    throw new RoutingTableException("13.5/13.7: " + key + " must use CONTROL in state "
                            + state.wireName() + ", got lane \"" + lane.wireName() + "\"");
                }
                if (mandatoryControl.contains(key)) {
                    throw new RoutingTableException("13.5: " + key
                            + " must use CONTROL, got lane \"" + lane.wireName() + "\"");
                }
            }
            index++;
        }
        return new RoutingTable(protocolNumber, sha256(bytes), routes);
    }

    /**
     * Parses the 13.5 must-use-CONTROL set of one protocol number.
     *
     * <p>Package-private so the validator rules of the set itself can be tested without a classpath
     * fixture per rule; {@link #loadResource(int)} is the caller that matters.
     *
     * @param bytes the set resource's exact bytes
     * @param protocolNumber the protocol number the set was requested for; the resource must declare
     *     the same number, so a set can never be applied to another protocol's table
     * @param path classpath path of {@code bytes}, used in diagnostics only
     */
    static Set<RouteKey> parseMandatoryControlSet(byte[] bytes, int protocolNumber, String path) {
        Objects.requireNonNull(bytes, "bytes");
        Object parsed = decode(bytes, "13.5");
        if (!(parsed instanceof Map)) {
            throw new RoutingTableException("13.5: " + path + " must be a JSON object");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        requireFields(root, MANDATORY_ROOT_FIELDS, MANDATORY_RESOURCE, "13.5");

        int schema = intField(root, SCHEMA_FIELD, MANDATORY_RESOURCE);
        if (schema != SCHEMA_VERSION) {
            throw new RoutingTableException("13.5: " + path + " has unsupported schema " + schema
                    + ", only schema " + SCHEMA_VERSION + " is supported");
        }

        int declared = intField(root, PROTOCOL_FIELD, MANDATORY_RESOURCE);
        if (declared != protocolNumber) {
            throw new RoutingTableException("13.5: " + path + " declares protocol " + declared
                    + ", not the requested " + protocolNumber);
        }

        Object keys = root.get(MANDATORY_CONTROL_FIELD);
        if (!(keys instanceof List)) {
            throw new RoutingTableException("13.5: " + path + " " + MANDATORY_CONTROL_FIELD
                    + " must be an array, got " + describe(keys));
        }

        Set<RouteKey> mandatoryControl = new LinkedHashSet<>();
        int index = 0;
        for (Object entry : (List<?>) keys) {
            String where = path + " " + MANDATORY_CONTROL_FIELD + "[" + index + "]";
            if (!(entry instanceof Map)) {
                throw new RoutingTableException("13.5: " + where + " must be a JSON object");
            }
            Map<?, ?> object = (Map<?, ?>) entry;
            requireFields(object, MANDATORY_KEY_FIELDS, where, "13.5");

            ProtocolState state = stateField(object, where);
            Direction direction = directionField(object, where);
            int packetId = intField(object, PACKET_ID_FIELD, where);
            if (!mandatoryControl.add(new RouteKey(protocolNumber, state, direction, packetId))) {
                throw new RoutingTableException("13.5: duplicate key in " + where);
            }
            index++;
        }
        return Collections.unmodifiableSet(mandatoryControl);
    }

    /** Loads the 13.5 must-use-CONTROL set of {@code protocolNumber}, never an assumed empty one. */
    private static Set<RouteKey> loadMandatoryControl(int protocolNumber) throws IOException {
        String path = mandatoryControlResourcePath(protocolNumber);
        byte[] bytes = readResource(path);
        if (bytes == null) {
            throw new RoutingTableException("13.5: protocol " + protocolNumber
                    + " has no mandatory-CONTROL set; expected the classpath resource " + path);
        }
        return parseMandatoryControlSet(bytes, protocolNumber, path);
    }

    private static Dependency dependencyField(Map<?, ?> object, String where, Lane lane) {
        Object value = object.get(DEPENDENCY_FIELD);
        if (!(value instanceof String)) {
            throw new RoutingTableException("13.7: " + where + " dependency must be a string, got "
                    + describe(value));
        }
        Dependency dependency;
        try {
            dependency = Dependency.of((String) value);
        } catch (IllegalArgumentException ignored) {
            throw new RoutingTableException("13.7: " + where + " unknown dependency: "
                    + describe(value));
        }
        Dependency required = lane.isControl() ? Dependency.FENCE : Dependency.LANE;
        if (dependency != required) {
            throw new RoutingTableException("13.7: " + where + " lane \"" + lane.wireName()
                    + "\" requires dependency \"" + required.wireName() + "\", got \""
                    + dependency.wireName() + "\"");
        }
        return dependency;
    }

    private static Lane laneField(Map<?, ?> object, String where) {
        Object value = object.get(LANE_FIELD);
        if (!(value instanceof String)) {
            throw new RoutingTableException("13.7: " + where + " lane must be a string, got "
                    + describe(value));
        }
        try {
            return Lane.of((String) value);
        } catch (IllegalArgumentException ignored) {
            throw new RoutingTableException("13.7: " + where + " unknown lane: " + describe(value));
        }
    }

    private static ProtocolState stateField(Map<?, ?> object, String where) {
        Object value = object.get(STATE_FIELD);
        if (!(value instanceof String)) {
            throw new RoutingTableException("13.7: " + where + " state must be a string, got "
                    + describe(value));
        }
        try {
            return ProtocolState.of((String) value);
        } catch (IllegalArgumentException ignored) {
            throw new RoutingTableException("13.7: " + where + " unknown state: " + describe(value));
        }
    }

    private static Direction directionField(Map<?, ?> object, String where) {
        Object value = object.get(DIRECTION_FIELD);
        if (!(value instanceof String)) {
            throw new RoutingTableException("13.7: " + where + " direction must be a string, got "
                    + describe(value));
        }
        try {
            return Direction.of((String) value);
        } catch (IllegalArgumentException ignored) {
            throw new RoutingTableException("13.7: " + where + " unknown direction: "
                    + describe(value));
        }
    }

    /** A field that must be a plain nonnegative integer no larger than a Minecraft VarInt. */
    private static int intField(Map<?, ?> object, String field, String where) {
        Object value = object.get(field);
        if (!(value instanceof BigDecimal)) {
            throw new RoutingTableException("13.7: " + where + " " + field
                    + " must be an integer, got " + describe(value));
        }
        String text = Json.numberText(value);
        if (!isPlainInteger(text)) {
            // 13.7 spells an integer field as digits, so 1e2, 1E2, 1.0 and -0 are all rejected.
            throw new RoutingTableException("13.7: " + where + " " + field
                    + " must be a plain integer, got " + describe(text));
        }
        BigDecimal number = (BigDecimal) value;
        if (number.signum() < 0
                || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            // toString, never toPlainString: an exponent like 1e-1000000000 would expand into a
            // billion characters.
            throw new RoutingTableException("13.7: " + where + " " + field
                    + " out of range: " + describe(number));
        }
        return number.intValue();
    }

    /**
     * True when {@code text} spells a plain JSON integer, {@code -?(0|[1-9][0-9]*)}, except that a
     * negative zero is not one: 13.7 wants a nonnegative integer, and {@code -0} is a second
     * spelling of zero. A fraction, an exponent and a leading zero are not plain integers either.
     */
    private static boolean isPlainInteger(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int start = text.charAt(0) == '-' ? 1 : 0;
        if (start == text.length()) {
            return false;
        }
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        if (text.charAt(start) != '0') {
            return true;
        }
        return start == 0 && text.length() == 1;
    }

    private static void requireFields(Map<?, ?> object, Set<String> allowed, String where,
            String rule) {
        for (Object key : object.keySet()) {
            if (!(key instanceof String) || !allowed.contains(key)) {
                throw new RoutingTableException(rule + ": unknown field " + describe(key)
                        + " in " + where);
            }
        }
        for (String required : allowed) {
            if (!object.containsKey(required)) {
                throw new RoutingTableException(rule + ": " + where + " is missing " + required);
            }
        }
    }

    private static Object decode(byte[] bytes, String rule) {
        if (bytes.length > MAX_RESOURCE_BYTES) {
            throw new RoutingTableException(rule + ": the resource is larger than "
                    + MAX_RESOURCE_BYTES + " bytes");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new RoutingTableException(rule + ": the resource is not valid UTF-8");
        }
        try {
            return Json.parseRejectingDuplicateKeys(text);
        } catch (IllegalArgumentException e) {
            throw new RoutingTableException(rule + ": malformed JSON: " + e.getMessage(), e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    /**
     * A diagnostic rendering of {@code value}, at most {@link #MAX_DIAGNOSTIC} characters. The value
     * comes from an untrusted resource, so it must never be able to make the message huge; a
     * {@link BigDecimal} is rendered with {@code toString}, which stays short for every scale.
     */
    private static String describe(Object value) {
        String text = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        if (text.length() <= MAX_DIAGNOSTIC) {
            return text;
        }
        return text.substring(0, MAX_DIAGNOSTIC - 3) + "...";
    }

    private static Set<String> unmodifiableSet(String... values) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, values);
        return Collections.unmodifiableSet(set);
    }

    /** The resource's bytes, or {@code null} when the classpath has no such resource. */
    private static byte[] readResource(String path) throws IOException {
        ClassLoader loader = RoutingTableLoader.class.getClassLoader();
        InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(path)
                : loader.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_RESOURCE_BYTES) {
                throw new RoutingTableException("13.7: the resource is larger than "
                        + MAX_RESOURCE_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
