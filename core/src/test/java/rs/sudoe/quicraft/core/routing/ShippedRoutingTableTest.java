// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;

/**
 * The routing table this build ships for Minecraft 26.1.2 (protocol 775, docs/protocol.md 13.7):
 * it parses through the production loader, its digest is SHA-256 over the exact resource bytes,
 * and every entry is a clientbound PLAY route that the ordering tests in
 * {@code ShippedRoutingOrderingTest} cover.
 */
class ShippedRoutingTableTest {
    private static final int PROTOCOL = 775;
    private static final String RESOURCE = RoutingTableLoader.resourcePath(PROTOCOL);
    private static final Map<Integer, String> PACKET_NAMES = packetNames();

    @Test
    void theShippedTableParsesAndIsTheRegistryAnswer() throws Exception {
        Faults.set();
        Optional<RoutingTable> table = RoutingRegistry.forProtocol(PROTOCOL);
        assertTrue(table.isPresent(), "26.1.2 ships a table: " + RESOURCE);
        assertEquals(PROTOCOL, table.get().protocolNumber());
        assertEquals(RoutingTableLoader.loadResource(PROTOCOL).digestHex(), table.get().digestHex());
    }

    @Test
    void theDigestIsSha256OverTheExactResourceBytes() throws Exception {
        Faults.set();
        byte[] bytes = readResource(RESOURCE);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] expected = sha256.digest(bytes);
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).get();
        assertArrayEquals(expected, table.digest(), "R is SHA-256 over the exact bytes (13.7)");
    }

    @Test
    void everyShippedRouteIsAClientboundPlayRouteWithALaneDependency() throws Exception {
        Faults.set();
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).get();
        assertFalse(table.routes().isEmpty(), "the 26.1.2 table routes packets off CONTROL");
        for (Map.Entry<RouteKey, Route> entry : table.routes().entrySet()) {
            RouteKey key = entry.getKey();
            Route route = entry.getValue();
            assertEquals(ProtocolState.PLAY, key.state(), key.toString());
            assertEquals(Direction.CLIENTBOUND, key.direction(), key.toString());
            assertFalse(route.lane().isControl(), key + " is listed only when it leaves CONTROL");
            assertEquals(Dependency.LANE, route.dependency(), key.toString());
        }
    }

    @Test
    void everyShippedPacketNameHasItsPinnedLane() throws Exception {
        Faults.set();
        Map<String, Lane> expected = new LinkedHashMap<String, Lane>();
        expected.put("animate", Lane.REALTIME);
        expected.put("hurt_animation", Lane.REALTIME);
        expected.put("level_particles", Lane.REALTIME);
        expected.put("move_entity_pos", Lane.REALTIME);
        expected.put("move_entity_pos_rot", Lane.REALTIME);
        expected.put("move_minecart_along_track", Lane.REALTIME);
        expected.put("move_entity_rot", Lane.REALTIME);
        expected.put("rotate_head", Lane.REALTIME);
        expected.put("set_entity_data", Lane.REALTIME);
        expected.put("set_entity_motion", Lane.REALTIME);
        expected.put("set_equipment", Lane.REALTIME);
        expected.put("sound_entity", Lane.REALTIME);
        expected.put("update_attributes", Lane.REALTIME);
        expected.put("block_changed_ack", Lane.WORLD);
        expected.put("block_destruction", Lane.WORLD);
        expected.put("block_entity_data", Lane.WORLD);
        expected.put("block_event", Lane.WORLD);
        expected.put("block_update", Lane.WORLD);
        expected.put("chunks_biomes", Lane.WORLD);
        expected.put("light_update", Lane.WORLD);
        expected.put("section_blocks_update", Lane.WORLD);

        RoutingTable table = RoutingTableLoader.loadResource(PROTOCOL);
        Map<String, Lane> actual = new LinkedHashMap<String, Lane>();
        for (Map.Entry<RouteKey, Route> entry : table.routes().entrySet()) {
            RouteKey key = entry.getKey();
            String name = PACKET_NAMES.get(key.packetId());
            assertTrue(name != null, "unexpected protocol-775 packet id " + key.packetId());
            actual.put(name, entry.getValue().lane());
        }
        assertEquals(expected, actual, "shipped packet name -> lane assignments changed");
    }

    private static Map<Integer, String> packetNames() {
        Map<Integer, String> names = new LinkedHashMap<Integer, String>();
        names.put(2, "animate"); names.put(42, "hurt_animation"); names.put(47, "level_particles");
        names.put(53, "move_entity_pos"); names.put(54, "move_entity_pos_rot");
        names.put(55, "move_minecart_along_track"); names.put(56, "move_entity_rot");
        names.put(83, "rotate_head"); names.put(99, "set_entity_data"); names.put(101, "set_entity_motion");
        names.put(102, "set_equipment"); names.put(116, "sound_entity"); names.put(131, "update_attributes");
        names.put(4, "block_changed_ack"); names.put(5, "block_destruction");
        names.put(6, "block_entity_data"); names.put(7, "block_event"); names.put(8, "block_update");
        names.put(13, "chunks_biomes"); names.put(48, "light_update"); names.put(84, "section_blocks_update");
        return names;
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = ShippedRoutingTableTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
