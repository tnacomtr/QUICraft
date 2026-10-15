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
import java.util.HashMap;
import java.util.HashSet;
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
 * {@code lane} and {@code dependency}. The example shipped in {@code core/src/main/resources} is the
 * spec's synthetic schema example; it is not a mapping for any released Minecraft version.
 *
 * <p>The validator rejects, with the rule it broke in the message: an unsupported {@code schema}; a
 * duplicate route; an unknown field at either level; an unknown state, lane, direction or dependency;
 * a missing or wrongly typed field; a {@code default} other than {@code control}; a packet id that is
 * not a nonnegative integer no larger than {@link Integer#MAX_VALUE} (one Minecraft VarInt); a
 * dependency that does not match the lane (13.7: {@code fence} for CONTROL, {@code lane} otherwise);
 * an auxiliary lane in a state other than PLAY, or any key the caller declares MUST use CONTROL
 * (13.5); and input that is not valid UTF-8.
 *
 * <p>JSON parsing uses the strict parser core already ships for status responses
 * ({@link rs.sudoe.quicraft.core.discovery.Json}); no dependency is added. That parser bounds the
 * decoded text at 1 MiB, which no in-tree table comes close to.
 */
public final class RoutingTableLoader {
    /** The only supported resource schema (13.7). */
    public static final int SCHEMA_VERSION = 1;
    /** The only defined default lane (13.7). */
    public static final Lane DEFAULT_LANE = Lane.CONTROL;
    /** Classpath directory holding one resource per protocol number, named {@code protocol-<n>.json}. */
    public static final String RESOURCE_DIR = "rs/sudoe/quicraft/core/routing";

    private static final String SCHEMA_FIELD = "schema";
    private static final String PROTOCOL_FIELD = "protocol";
    private static final String DEFAULT_FIELD = "default";
    private static final String ROUTES_FIELD = "routes";
    private static final String STATE_FIELD = "state";
    private static final String DIRECTION_FIELD = "direction";
    private static final String PACKET_ID_FIELD = "packet_id";
    private static final String LANE_FIELD = "lane";
    private static final String DEPENDENCY_FIELD = "dependency";

    private static final Set<String> ROOT_FIELDS = unmodifiableSet(
            SCHEMA_FIELD, PROTOCOL_FIELD, DEFAULT_FIELD, ROUTES_FIELD);
    private static final Set<String> ENTRY_FIELDS = unmodifiableSet(
            STATE_FIELD, DIRECTION_FIELD, PACKET_ID_FIELD, LANE_FIELD, DEPENDENCY_FIELD);

    private RoutingTableLoader() {}

    /** The classpath resource path of the table for {@code protocolNumber}. */
    public static String resourcePath(int protocolNumber) {
        return RESOURCE_DIR + "/protocol-" + protocolNumber + ".json";
    }

    /**
     * Loads and validates the table for {@code protocolNumber} from the classpath.
     *
     * @throws IOException if the resource is missing or unreadable
     * @throws RoutingTableException if the resource is invalid
     */
    public static RoutingTable loadResource(int protocolNumber) throws IOException {
        return loadResource(resourcePath(protocolNumber), Collections.<RouteKey>emptySet());
    }

    /**
     * Loads and validates a resource from the classpath, rejecting any {@code mandatoryControl} key
     * routed to an auxiliary lane.
     *
     * @param resourcePath classpath path, e.g. {@code rs/sudoe/quicraft/core/routing/protocol-12345.json}
     * @throws IOException if the resource is missing or unreadable
     * @throws RoutingTableException if the resource is invalid
     */
    public static RoutingTable loadResource(String resourcePath, Set<RouteKey> mandatoryControl)
            throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath");
        ClassLoader loader = RoutingTableLoader.class.getClassLoader();
        InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resourcePath)
                : loader.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException("routing resource not found on the classpath: " + resourcePath);
        }
        try {
            return parse(readAll(in), mandatoryControl);
        } finally {
            in.close();
        }
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
        Object parsed = decode(bytes);
        if (!(parsed instanceof Map)) {
            throw new RoutingTableException("13.7: the resource must be a JSON object");
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        requireFields(root, ROOT_FIELDS, "resource");

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

        Map<RouteKey, Route> routes = new HashMap<>();
        int index = 0;
        for (Object entry : (List<?>) routesValue) {
            String where = "routes[" + index + "]";
            if (!(entry instanceof Map)) {
                throw new RoutingTableException("13.7: " + where + " must be a JSON object");
            }
            Map<?, ?> object = (Map<?, ?>) entry;
            requireFields(object, ENTRY_FIELDS, where);

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

    private static Dependency dependencyField(Map<?, ?> object, String where, Lane lane) {
        Object value = object.get(DEPENDENCY_FIELD);
        if (!(value instanceof String)) {
            throw new RoutingTableException("13.7: " + where + " dependency must be a string, got "
                    + describe(value));
        }
        Dependency dependency;
        try {
            dependency = Dependency.of((String) value);
        } catch (IllegalArgumentException e) {
            throw new RoutingTableException("13.7: " + where + " " + e.getMessage());
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
        } catch (IllegalArgumentException e) {
            throw new RoutingTableException("13.7: " + where + " " + e.getMessage());
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
        } catch (IllegalArgumentException e) {
            throw new RoutingTableException("13.7: " + where + " " + e.getMessage());
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
        } catch (IllegalArgumentException e) {
            throw new RoutingTableException("13.7: " + where + " " + e.getMessage());
        }
    }

    /** A field that must be a nonnegative integer no larger than a Minecraft VarInt. */
    private static int intField(Map<?, ?> object, String field, String where) {
        Object value = object.get(field);
        if (!(value instanceof BigDecimal)) {
            throw new RoutingTableException("13.7: " + where + " " + field
                    + " must be an integer, got " + describe(value));
        }
        BigDecimal number = (BigDecimal) value;
        if (number.scale() > 0 || number.signum() < 0
                || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new RoutingTableException("13.7: " + where + " " + field
                    + " out of range: " + number.toPlainString());
        }
        return number.intValue();
    }

    private static void requireFields(Map<?, ?> object, Set<String> allowed, String where) {
        for (Object key : object.keySet()) {
            if (!(key instanceof String) || !allowed.contains(key)) {
                throw new RoutingTableException("13.7: unknown field " + describe(key) + " in " + where);
            }
        }
        for (String required : allowed) {
            if (!object.containsKey(required)) {
                throw new RoutingTableException("13.7: " + where + " is missing " + required);
            }
        }
    }

    private static Object decode(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new RoutingTableException("13.7: the resource is not valid UTF-8");
        }
        try {
            return Json.parse(text);
        } catch (IllegalArgumentException e) {
            throw new RoutingTableException("13.7: malformed JSON: " + e.getMessage(), e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }

    private static Set<String> unmodifiableSet(String... values) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, values);
        return Collections.unmodifiableSet(set);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
