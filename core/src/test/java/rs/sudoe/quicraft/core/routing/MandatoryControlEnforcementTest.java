// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;

/**
 * 13.5/13.7: the reviewed mandatory-CONTROL set for protocol 775 enforces a global fence. Each listed
 * key must be CONTROL/global in the table, and routing one of them off CONTROL must be rejected.
 * The enforcement tests run through {@link RoutingTableLoader#parse(byte[], Set)} on mutated copies
 * of the shipped bytes, so a passing test cannot come from an empty set.
 */
class MandatoryControlEnforcementTest {
    private static final int PROTOCOL = 775;
    private static final String TABLE = RoutingTableLoader.resourcePath(PROTOCOL);
    private static final String MANDATORY = RoutingTableLoader.mandatoryControlResourcePath(PROTOCOL);

    /**
     * The reviewed set: disconnect (32), login (49), respawn (82), start_configuration (118) and
     * transfer (129) clientbound; configuration_acknowledged (16) serverbound. Login and respawn are the
     * level replacements (13.5); transfer ends the connection like disconnect.
     */
    private static final Set<RouteKey> EXPECTED = new HashSet<RouteKey>(Arrays.asList(
            key(Direction.CLIENTBOUND, 32), key(Direction.CLIENTBOUND, 49), key(Direction.CLIENTBOUND, 82),
            key(Direction.CLIENTBOUND, 118), key(Direction.CLIENTBOUND, 129), key(Direction.SERVERBOUND, 16)));

    @Test
    void theShippedMandatorySetIsTheReviewedSix() throws IOException {
        Faults.set();
        Set<RouteKey> set = RoutingTableLoader.parseMandatoryControlSet(bytes(MANDATORY), PROTOCOL, MANDATORY);
        assertEquals(EXPECTED, new HashSet<RouteKey>(set));
    }

    @Test
    void everyListedKeyIsCarriedByTheShippedTableAsGlobalControl() throws IOException {
        Faults.set();
        RoutingTable table = RoutingTableLoader.loadResource(PROTOCOL);
        for (RouteKey key : EXPECTED) {
            RouteV2 route = table.routeV2For(key);
            assertEquals(RouteV2.StreamLane.CONTROL, route.lane, key.toString());
            assertEquals(Dependency.GLOBAL, route.dependency, key.toString());
        }
    }

    @Test
    void routingAnyListedKeyOffControlIsRejected() throws IOException {
        Faults.set();
        String table = new String(bytes(TABLE), StandardCharsets.UTF_8);
        for (RouteKey key : EXPECTED) {
            // A serverbound route carries ordinarySmall, which only a CONTROL route may have (13.7), so the
            // rewrite to REALTIME drops it. The match includes the whole route tail, so it is exact.
            // Clientbound login (49) and respawn (82) are epoch changes (13.18); the rewrite clears that flag too,
            // so the mandatory-CONTROL rule is what rejects it, not the earlier epoch_change rule (13.7).
            boolean epochKey = key.direction() == Direction.CLIENTBOUND
                    && table.contains("\"packet_id\":" + key.packetId()
                    + ",\"lane\":\"control\",\"dependency\":\"global\",\"position\":null,\"epoch_change\":true");
            String tail = key.direction() == Direction.SERVERBOUND
                    ? ",\"position\":null,\"epoch_change\":false,\"ordinarySmall\":false"
                    : epochKey ? ",\"position\":null,\"epoch_change\":true" : "";
            String rewritten = key.direction() == Direction.SERVERBOUND
                    ? ",\"position\":null,\"epoch_change\":false"
                    : epochKey ? ",\"position\":null,\"epoch_change\":false" : "";
            String from = "\"direction\":\"" + key.direction().wireName() + "\",\"packet_id\":"
                    + key.packetId() + ",\"lane\":\"control\",\"dependency\":\"global\"" + tail;
            String to = "\"direction\":\"" + key.direction().wireName() + "\",\"packet_id\":"
                    + key.packetId() + ",\"lane\":\"realtime\",\"dependency\":\"lane\"" + rewritten;
            assertTrue(table.contains(from), "the shipped table routes " + key + " as global control");
            String mutated = table.replace(from, to);
            RoutingTableException e = org.junit.jupiter.api.Assertions.assertThrows(
                    RoutingTableException.class,
                    () -> RoutingTableLoader.parse(mutated.getBytes(StandardCharsets.UTF_8), EXPECTED));
            assertTrue(e.getMessage().contains("mandatory CONTROL requires global fence"), e.getMessage());
        }
    }

    /** Negative control: the same rewrite of an unlisted id parses under the same set, so the rejection above is the listed key's doing. */
    @Test
    void theSameRewriteOfAnUnlistedIdIsAcceptedWithoutTheSet() throws IOException {
        Faults.set();
        String table = new String(bytes(TABLE), StandardCharsets.UTF_8);
        // custom_payload (24) is not in the mandatory set and stays CONTROL/global in the shipped table.
        String from = "\"direction\":\"clientbound\",\"packet_id\":24,\"lane\":\"control\",\"dependency\":\"global\"";
        String to = "\"direction\":\"clientbound\",\"packet_id\":24,\"lane\":\"realtime\",\"dependency\":\"lane\"";
        assertTrue(table.contains(from), "custom_payload (24) is routed as global control");
        RoutingTable parsed = RoutingTableLoader.parse(table.replace(from, to).getBytes(StandardCharsets.UTF_8), EXPECTED);
        assertEquals(RouteV2.StreamLane.REALTIME, parsed.routeV2For(key(Direction.CLIENTBOUND, 24)).lane);
    }

    private static RouteKey key(Direction direction, int packetId) {
        return new RouteKey(PROTOCOL, ProtocolState.PLAY, direction, packetId);
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream in = MandatoryControlEnforcementTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
