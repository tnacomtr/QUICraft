// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The validator of docs/protocol.md 13.6 and 13.7: every rejection rule has a failing input here,
 * plus the golden digest of the shipped example resource.
 *
 * <p>The shipped resource is the spec's synthetic schema example (13.7), reflowed onto one line and
 * without a trailing newline, so digest R covers exactly those bytes. This fixture is also the base
 * of the rejection cases below.
 */
class RoutingTableLoaderTest {

    private static final String CONTROL_ENTRY =
            "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":10,"
                    + "\"lane\":\"control\",\"dependency\":\"fence\"}";
    private static final String REALTIME_ENTRY =
            "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":11,"
                    + "\"lane\":\"realtime\",\"dependency\":\"lane\"}";
    private static final String WORLD_ENTRY =
            "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":12,"
                    + "\"lane\":\"world\",\"dependency\":\"lane\"}";
    /** The shipped example resource, byte for byte. */
    private static final String EXAMPLE = "{\"schema\":1,\"protocol\":12345,\"default\":\"control\",\"routes\":["
            + CONTROL_ENTRY + "," + REALTIME_ENTRY + "," + WORLD_ENTRY + "]}";
    /** R for {@link #EXAMPLE}: SHA-256 over those exact bytes (13.7). */
    private static final String EXAMPLE_DIGEST =
            "d70cd561c56f396461639b43822493b1deb75953bc4f9c335f86ef37373cff7a";

    private static RoutingTableException reject(String json) {
        return assertThrows(RoutingTableException.class, () -> RoutingTableLoader.parse(json));
    }

    private static String entry(String state, Direction direction, int packetId, Lane lane) {
        return "{\"state\":\"" + state + "\",\"direction\":\"" + direction.wireName()
                + "\",\"packet_id\":" + packetId + ",\"lane\":\"" + lane.wireName()
                + "\",\"dependency\":\"" + (lane.isControl() ? "fence" : "lane") + "\"}";
    }

    private static byte[] readResource(String path) throws IOException {
        InputStream in = RoutingTableLoaderTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(in, "missing test resource " + path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------- the resource and digest R (13.7)

    @Test
    void exampleResourceHasExactlyTheExpectedBytes() throws IOException {
        byte[] bytes = readResource(RoutingTableLoader.resourcePath(12345));
        assertEquals(EXAMPLE, new String(bytes, StandardCharsets.UTF_8),
                "the shipped resource must stay byte-identical to this fixture");
        assertEquals(345, bytes.length, "digest R covers exactly these 345 bytes");
    }

    @Test
    void goldenDigestForTheExampleResource() throws IOException {
        RoutingTable table = RoutingTableLoader.loadResource(12345);
        assertEquals(EXAMPLE_DIGEST, table.digestHex(),
                "R changed: 13.7 defines R as SHA-256 over the exact resource bytes");
        assertEquals(EXAMPLE_DIGEST, hex(table.digest()));
        assertEquals(12345, table.protocolNumber());
    }

    @Test
    void differentBytesGiveDifferentDigests() {
        RoutingTable original = RoutingTableLoader.parse(EXAMPLE);
        RoutingTable trailingWhitespace = RoutingTableLoader.parse(EXAMPLE + " ");
        RoutingTable changedId = RoutingTableLoader.parse(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":13"));

        assertEquals(EXAMPLE_DIGEST, original.digestHex());
        assertTrue(!EXAMPLE_DIGEST.equals(trailingWhitespace.digestHex()),
                "13.7: whitespace is part of R, there is no runtime normalization");
        assertTrue(!original.digestHex().equals(changedId.digestHex()),
                "two tables with different bytes must give different digests");
        assertEquals(Lane.WORLD, changedId.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 13));
    }

    @Test
    void missingResourceIsAnIoFailure() {
        assertThrows(IOException.class, () -> RoutingTableLoader.loadResource(
                "rs/sudoe/quicraft/core/routing/protocol-1.json", Collections.<RouteKey>emptySet()));
    }

    // ------------------------------------------------------------------------- schema and shape

    @Test
    void rejectsUnsupportedSchema() {
        assertTrue(reject(EXAMPLE.replace("\"schema\":1", "\"schema\":2")).getMessage().contains("schema"));
    }

    @Test
    void rejectsMissingSchema() {
        reject(EXAMPLE.replace("\"schema\":1,", ""));
    }

    @Test
    void rejectsRootThatIsNotAnObject() {
        assertTrue(reject("[]").getMessage().contains("JSON object"));
        reject("7");
        reject("\"table\"");
    }

    @Test
    void rejectsUnknownRootField() {
        assertTrue(reject(EXAMPLE.replace("\"default\":", "\"extra\":1,\"default\":"))
                .getMessage().contains("unknown field"));
    }

    @Test
    void rejectsUnknownEntryField() {
        assertTrue(reject(EXAMPLE.replace(CONTROL_ENTRY, CONTROL_ENTRY.replace("}", ",\"note\":true}")))
                .getMessage().contains("unknown field"));
    }

    @Test
    void rejectsRoutesThatAreNotAnArray() {
        String notAnArray = "{\"schema\":1,\"protocol\":12345,\"default\":\"control\",\"routes\":{}}";
        assertTrue(reject(notAnArray).getMessage().contains("routes must be an array"));
        reject("{\"schema\":1,\"protocol\":12345,\"default\":\"control\"}");
    }

    @Test
    void rejectsMalformedJson() {
        assertTrue(reject("{\"schema\":1,").getMessage().contains("malformed JSON"));
    }

    @Test
    void rejectsInputThatIsNotValidUtf8() {
        byte[] invalid = {'{', (byte) 0xff, (byte) 0xfe, '}'};
        assertTrue(assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.parse(invalid)).getMessage().contains("UTF-8"));
    }

    // --------------------------------------------------------------------------- field values

    @Test
    void rejectsDuplicateRoute() {
        String duplicate = EXAMPLE.replace(WORLD_ENTRY, WORLD_ENTRY + "," + entry(
                "play", Direction.CLIENTBOUND, 12, Lane.CONTROL));
        assertTrue(reject(duplicate).getMessage().contains("duplicate"));
    }

    @Test
    void rejectsUnknownLane() {
        assertTrue(reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"lane\":\"realtime\"", "\"lane\":\"fast\"")))
                .getMessage().contains("unknown lane"));
        reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"lane\":\"realtime\"", "\"lane\":\"CONTROL\"")));
    }

    @Test
    void rejectsUnknownState() {
        assertTrue(reject(EXAMPLE.replace(CONTROL_ENTRY,
                CONTROL_ENTRY.replace("\"state\":\"play\"", "\"state\":\"spawn\"")))
                .getMessage().contains("unknown state"));
    }

    @Test
    void rejectsUnknownDirection() {
        assertTrue(reject(EXAMPLE.replace(CONTROL_ENTRY,
                CONTROL_ENTRY.replace("\"direction\":\"clientbound\"", "\"direction\":\"both\"")))
                .getMessage().contains("unknown direction"));
    }

    @Test
    void rejectsUnknownDependency() {
        assertTrue(reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"dependency\":\"lane\"", "\"dependency\":\"barrier\"")))
                .getMessage().contains("unknown dependency"));
    }

    @Test
    void rejectsPacketIdOutOfRange() {
        assertTrue(reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":-1"))
                .getMessage().contains("out of range"));
        reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":2147483648"));
        reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":1.5"));
        reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":\"12\""));
        reject(EXAMPLE.replace("\"packet_id\":12", "\"packet_id\":null"));
    }

    @Test
    void rejectsProtocolNumberOutOfRange() {
        assertTrue(reject(EXAMPLE.replace("\"protocol\":12345", "\"protocol\":0"))
                .getMessage().contains("protocol number out of range"));
        reject(EXAMPLE.replace("\"protocol\":12345", "\"protocol\":-1"));
        reject(EXAMPLE.replace("\"protocol\":12345", "\"protocol\":\"12345\""));
    }

    @Test
    void rejectsDefaultThatIsNotControl() {
        assertTrue(reject(EXAMPLE.replace("\"default\":\"control\"", "\"default\":\"world\""))
                .getMessage().contains("default must be"));
    }

    @Test
    void rejectsMissingEntryField() {
        reject(EXAMPLE.replace(CONTROL_ENTRY, "{\"state\":\"play\",\"direction\":\"clientbound\","
                + "\"lane\":\"control\",\"dependency\":\"fence\"}"));
        reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"lane\":\"realtime\",", "")));
    }

    @Test
    void rejectsDependencyThatDoesNotMatchTheLane() {
        assertTrue(reject(EXAMPLE.replace(CONTROL_ENTRY,
                CONTROL_ENTRY.replace("\"dependency\":\"fence\"", "\"dependency\":\"lane\"")))
                .getMessage().contains("requires dependency"));
        reject(EXAMPLE.replace(REALTIME_ENTRY,
                REALTIME_ENTRY.replace("\"dependency\":\"lane\"", "\"dependency\":\"fence\"")));
    }

    // --------------------------------------------------- 13.5 packets that MUST use CONTROL

    @Test
    void rejectsAuxiliaryLaneInAStateOtherThanPlay() {
        // 13.5: state transitions, acknowledgements, registry/dimension and compression changes,
        // disconnects and unknown/custom packets MUST use CONTROL; 13.3 opens the auxiliary lanes
        // only after activation, which is at the end of configuration.
        for (String state : new String[] {"handshaking", "status", "login", "configuration"}) {
            assertTrue(reject(EXAMPLE.replace(REALTIME_ENTRY,
                    entry(state, Direction.CLIENTBOUND, 11, Lane.REALTIME)))
                    .getMessage().contains("must use CONTROL"), "state " + state);
            reject(EXAMPLE.replace(WORLD_ENTRY, entry(state, Direction.CLIENTBOUND, 12, Lane.WORLD)));
        }
    }

    @Test
    void acceptsControlInAnyState() {
        RoutingTable table = RoutingTableLoader.parse(EXAMPLE.replace(REALTIME_ENTRY,
                entry("configuration", Direction.CLIENTBOUND, 11, Lane.CONTROL)));
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.CONFIGURATION, Direction.CLIENTBOUND, 11));
    }

    @Test
    void rejectsAPacketKnownToMustUseControlRoutedElsewhere() {
        Set<RouteKey> mandatory = new HashSet<>();
        mandatory.add(new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 11));

        RoutingTableException e = assertThrows(RoutingTableException.class,
                () -> RoutingTableLoader.parse(EXAMPLE, mandatory));
        assertTrue(e.getMessage().contains("13.5"));
        assertTrue(e.getMessage().contains("must use CONTROL"));

        // The same table is valid once that packet is not known to be control-only.
        assertEquals(Lane.REALTIME, RoutingTableLoader.parse(EXAMPLE, Collections.<RouteKey>emptySet())
                .laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
    }

    @Test
    void mandatoryControlIsMatchedByExactKey() {
        Set<RouteKey> mandatory = new HashSet<>();
        // Another protocol number, direction, state or id: none of these is this table's key.
        mandatory.add(new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
        mandatory.add(new RouteKey(12345, ProtocolState.PLAY, Direction.SERVERBOUND, 11));
        mandatory.add(new RouteKey(12345, ProtocolState.CONFIGURATION, Direction.CLIENTBOUND, 11));
        mandatory.add(new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 14));
        RoutingTableLoader.parse(EXAMPLE, mandatory);

        // Declaring an entry that is CONTROL anyway mandatory is accepted.
        Set<RouteKey> controlEntry = new HashSet<>();
        controlEntry.add(new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 10));
        assertEquals(Lane.CONTROL, RoutingTableLoader.parse(EXAMPLE, controlEntry)
                .laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 10));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
