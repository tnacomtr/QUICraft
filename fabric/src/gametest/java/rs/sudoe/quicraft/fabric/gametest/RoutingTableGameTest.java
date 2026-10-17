// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.Route;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;

/**
 * Pins the shipped 26.1.2 routing table (protocol 775, docs/protocol.md 13.6 and 13.7) to the live
 * game. The play packet ids are read from the game's own protocol templates, the same registration
 * order the server and client use, and each non-CONTROL route must name the packet the table claims.
 * A version bump that shifts ids, or an edit to the JSON that moves an id, fails here by name.
 *
 * <p>The game classes are reached by reflection: this source set is shared with the 1.21.11 jar,
 * where the same names are obfuscated and the test has nothing to pin. It returns early there.
 *
 * <p>With {@code -Pquicraft.emitPlayIds} (system property {@code quicraft.gametest.emitPlayIds}, see
 * docs/platforms/fabric.md) it also writes and prints the full inventory of both directions, one
 * {@code <direction> <id> <name>} line per packet sorted by id. That is how core's
 * {@code play-ids-775.txt} fixture is regenerated from the live game instead of hand-edited. Without
 * the property the run is unchanged.
 */
public class RoutingTableGameTest implements FabricClientGameTest {
    /** The shipped 26.1.2 protocol number. */
    private static final int PROTOCOL = 775;

    /** Makes the test emit the inventory: -Pquicraft.emitPlayIds=true (default path) or a file path. */
    private static final String EMIT_PROPERTY = "quicraft.gametest.emitPlayIds";

    /** Marks the emitted inventory on stdout. */
    private static final String BLOCK = "QUICraft play packet inventory, protocol " + PROTOCOL;

    /**
     * Every non-CONTROL route, as {@code direction lane live-id name}. Anything not listed is CONTROL
     * by default (13.6). REALTIME holds entity updates whose entity was spawned on CONTROL before
     * them, plus level particles (13.8); WORLD holds block, light and biome updates, which depend on the chunk
     * install fence (13.8, 13.11). The ordering of each lane is tested in core's
     * ShippedRoutingOrderingTest. Teleports, spawns, removals, state changes, keepalive, commands and
     * serverbound packets stay CONTROL and are not listed.
     */
    private static final String[] EXPECTED = {
        "clientbound realtime 2 minecraft:animate",
        "clientbound realtime 42 minecraft:hurt_animation",
        "clientbound realtime 47 minecraft:level_particles",
        "clientbound realtime 53 minecraft:move_entity_pos",
        "clientbound realtime 54 minecraft:move_entity_pos_rot",
        "clientbound realtime 55 minecraft:move_minecart_along_track",
        "clientbound realtime 56 minecraft:move_entity_rot",
        "clientbound realtime 83 minecraft:rotate_head",
        "clientbound realtime 99 minecraft:set_entity_data",
        "clientbound realtime 101 minecraft:set_entity_motion",
        "clientbound realtime 102 minecraft:set_equipment",
        "clientbound realtime 116 minecraft:sound_entity",
        "clientbound realtime 131 minecraft:update_attributes",
        "clientbound world 4 minecraft:block_changed_ack",
        "clientbound world 5 minecraft:block_destruction",
        "clientbound world 6 minecraft:block_entity_data",
        "clientbound world 7 minecraft:block_event",
        "clientbound world 8 minecraft:block_update",
        "clientbound world 13 minecraft:chunks_biomes",
        "clientbound world 48 minecraft:light_update",
        "clientbound world 84 minecraft:section_blocks_update",
    };

    @Override
    public void runTest(ClientGameTestContext context) {
        if (SharedConstants.getProtocolVersion() != PROTOCOL) {
            return;
        }
        try {
            Map<String, Integer> clientbound = liveIds("CLIENTBOUND_TEMPLATE");
            Map<String, Integer> serverbound = liveIds("SERVERBOUND_TEMPLATE");
            pin(clientbound, serverbound);
            emit(clientbound, serverbound);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void pin(Map<String, Integer> clientbound, Map<String, Integer> serverbound)
            throws Exception {
        Map<RouteKey, Route> routes = RoutingRegistry.forProtocol(PROTOCOL)
                .orElseThrow(() -> new AssertionError("no routing table ships for protocol " + PROTOCOL))
                .routes();
        int nonControl = 0;
        for (Map.Entry<RouteKey, Route> entry : routes.entrySet()) {
            check(entry.getValue().lane() != Lane.CONTROL,
                    "the shipped table lists a CONTROL route, which is the default: " + entry.getKey());
            nonControl++;
        }
        check(nonControl == EXPECTED.length, "the shipped table has " + nonControl
                + " non-CONTROL routes, expected " + EXPECTED.length);
        for (String line : EXPECTED) {
            String[] parts = line.split(" ", 4);
            Direction direction = Direction.of(parts[0]);
            Lane lane = Lane.of(parts[1]);
            int id = Integer.parseInt(parts[2]);
            String name = parts[3];
            Map<String, Integer> live = direction == Direction.CLIENTBOUND ? clientbound : serverbound;
            Integer liveId = live.get(name);
            check(liveId != null && liveId == id,
                    "the live " + direction.wireName() + " play protocol names " + name + " as id "
                            + liveId + ", the expected id is " + id);
            Route route = routes.get(new RouteKey(PROTOCOL, ProtocolState.PLAY, direction, id));
            check(route != null, "the shipped table has no route for " + name + " at id " + id);
            check(route.lane() == lane, "the shipped table routes " + name + " to " + route.lane()
                    + ", expected " + lane);
            Dependency expectedDependency = Dependency.LANE;
            check(route.dependency() == expectedDependency,
                    "the shipped table gives " + name + " the dependency " + route.dependency());
        }
    }

    /**
     * With {@link #EMIT_PROPERTY} set, writes the full inventory to a file and prints it between
     * BEGIN/END markers so either capture path works. Emission happens after the pins pass, so a
     * version whose ids don't match the shipped table emits nothing.
     */
    private static void emit(Map<String, Integer> clientbound, Map<String, Integer> serverbound)
            throws IOException {
        String target = System.getProperty(EMIT_PROPERTY);
        if (target == null) {
            return;
        }
        List<String> lines = inventory(clientbound, serverbound);
        StringBuilder block = new StringBuilder(BLOCK).append(" BEGIN\n");
        for (String line : lines) {
            block.append(line).append('\n');
        }
        block.append(BLOCK).append(" END");
        System.out.println(block);

        Path path = Paths.get(target.isEmpty() || target.equals("true")
                ? "play-ids-" + PROTOCOL + ".txt"
                : target);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder file = new StringBuilder();
        file.append("# QUICraft PLAY packet inventory, protocol ").append(PROTOCOL)
                .append(", live game, both directions.\n");
        file.append("# <direction> <id> <name>, one line per packet;")
                .append(" ids ascending per direction, clientbound first.\n");
        file.append("# emitted by fabric/src/gametest RoutingTableGameTest")
                .append(" from the game's own protocol templates.\n");
        file.append("# regenerate: ./gradlew :<module>:runClientGameTest")
                .append(" -Pquicraft.emitPlayIds=<file>\n");
        for (String line : lines) {
            file.append(line).append('\n');
        }
        Files.write(path, file.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[QUICraft] play packet inventory written to " + path.toAbsolutePath());
    }

    /** One {@code <direction> <id> <name>} line per live play packet, ids ascending per direction. */
    private static List<String> inventory(Map<String, Integer> clientbound, Map<String, Integer> serverbound) {
        List<String> lines = new ArrayList<String>();
        lines.addAll(directionLines(Direction.CLIENTBOUND, clientbound));
        lines.addAll(directionLines(Direction.SERVERBOUND, serverbound));
        return lines;
    }

    private static List<String> directionLines(Direction direction, Map<String, Integer> ids) {
        Map<Integer, String> byId = new TreeMap<Integer, String>();
        for (Map.Entry<String, Integer> entry : ids.entrySet()) {
            byId.put(entry.getValue(), entry.getKey());
        }
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<Integer, String> entry : byId.entrySet()) {
            lines.add(direction.wireName() + " " + entry.getKey() + " " + entry.getValue());
        }
        return lines;
    }

    /** Packet name to id for one template of the live play protocol, read through the game's own API. */
    private static Map<String, Integer> liveIds(String field) throws Exception {
        Map<String, Integer> ids = new HashMap<String, Integer>();
        Class<?> owner = Class.forName("net.minecraft.network.protocol.game.GameProtocols");
        Object template = owner.getField(field).get(null);
        Class<?> provider = Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider");
        Object details = provider.getMethod("details").invoke(template);
        Class<?> detailsClass = Class.forName("net.minecraft.network.ProtocolInfo$Details");
        Class<?> visitorClass = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
        Object visitor = Proxy.newProxyInstance(visitorClass.getClassLoader(), new Class<?>[] {visitorClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("accept")) {
                        Object type = args[0];
                        Object identifier = type.getClass().getMethod("id").invoke(type);
                        ids.put(identifier.toString(), (Integer) args[1]);
                    }
                    return null;
                });
        Method listPackets = detailsClass.getMethod("listPackets", visitorClass);
        listPackets.invoke(details, visitor);
        return ids;
    }
}
