// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The checked-in live-game PLAY packet inventory for protocol 775
 * ({@code rs/sudoe/quicraft/core/routing/play-ids-775.txt}). It is captured from the game's own
 * protocol templates by the Fabric client gametest {@code RoutingTableGameTest}
 * ({@code -Pquicraft.emitPlayIds}, see docs/platforms/fabric.md), never hand-written. Each data line
 * is {@code <direction> <id> <name>}; lines starting with {@code #} are provenance comments.
 *
 * <p>This pins the fixture to the shipped table: every packet protocol-775.json routes must appear
 * in the clientbound list under the name {@link ShippedRoutingTableTest#PACKET_NAMES} pins.
 */
class PlayIdInventoryTest {
    private static final int PROTOCOL = 775;
    private static final String RESOURCE = RoutingTableLoader.RESOURCE_DIR + "/play-ids-775.txt";

    @Test
    void bothDirectionsArePresentAndNonEmpty() throws Exception {
        Map<Direction, List<Packet>> byDirection = byDirection();
        for (Direction direction : Direction.values()) {
            assertFalse(byDirection.get(direction).isEmpty(),
                    "no " + direction.wireName() + " packets in " + RESOURCE);
        }
    }

    @Test
    void everyIdIsAnIntegerWithANameAndUniquePerDirection() throws Exception {
        for (Map.Entry<Direction, List<Packet>> entry : byDirection().entrySet()) {
            Map<Integer, String> seen = new TreeMap<Integer, String>();
            for (Packet packet : entry.getValue()) {
                assertFalse(packet.name.isEmpty(), "no name for " + entry.getKey().wireName()
                        + " id " + packet.id + " in " + RESOURCE);
                String previous = seen.put(packet.id, packet.name);
                assertTrue(previous == null, "duplicate " + entry.getKey().wireName() + " id "
                        + packet.id + " (" + previous + " and " + packet.name + ") in " + RESOURCE);
            }
        }
    }

    @Test
    void everyPacketTheShippedTableRoutesIsInTheClientboundInventory() throws Exception {
        Map<Integer, String> clientbound = new TreeMap<Integer, String>();
        for (Packet packet : byDirection().get(Direction.CLIENTBOUND)) {
            clientbound.put(packet.id, packet.name);
        }
        RoutingTable table = RoutingTableLoader.loadResource(PROTOCOL);
        int routed = 0;
        for (RouteKey key : table.routes().keySet()) {
            assertEquals(Direction.CLIENTBOUND, key.direction(), key.toString());
            String name = clientbound.get(key.packetId());
            assertNotNull(name, "the clientbound inventory has no id " + key.packetId()
                    + ", which protocol-" + PROTOCOL + ".json routes");
            String pinned = ShippedRoutingTableTest.PACKET_NAMES.get(key.packetId());
            assertNotNull(pinned, "unexpected routed id " + key.packetId());
            assertEquals("minecraft:" + pinned, name, "id " + key.packetId()
                    + " is not the packet protocol-" + PROTOCOL + ".json routes");
            routed++;
        }
        assertEquals(ShippedRoutingTableTest.PACKET_NAMES.size(), routed,
                "protocol-" + PROTOCOL + ".json no longer routes every packet ShippedRoutingTableTest pins");
    }

    private static Map<Direction, List<Packet>> byDirection() throws IOException {
        Map<Direction, List<Packet>> byDirection = new EnumMap<Direction, List<Packet>>(Direction.class);
        for (Direction direction : Direction.values()) {
            byDirection.put(direction, new ArrayList<Packet>());
        }
        try (InputStream in = PlayIdInventoryTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "missing " + RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                assertEquals(3, parts.length,
                        "line " + number + " is not <direction> <id> <name>: " + line);
                Direction direction;
                try {
                    direction = Direction.of(parts[0]);
                } catch (IllegalArgumentException e) {
                    throw new AssertionError("line " + number + " has an unknown direction: " + line, e);
                }
                int id;
                try {
                    id = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new AssertionError("line " + number + " has no integer id: " + line, e);
                }
                assertTrue(id >= 0, "line " + number + " has a negative id: " + line);
                byDirection.get(direction).add(new Packet(id, parts[2]));
            }
        }
        return byDirection;
    }

    /** One inventory line. */
    private static final class Packet {
        private final int id;
        private final String name;

        Packet(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
