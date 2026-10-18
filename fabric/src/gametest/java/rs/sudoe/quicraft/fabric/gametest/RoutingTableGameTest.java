// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * <p>On the module's own game version it also compares the complete live play protocol, both
 * directions and every id, against the checked-in {@code play-ids-775.txt} (core's routing test
 * resources). A new, removed or renamed packet therefore fails here until the fixture is refreshed;
 * a module version bump to another protocol fails loudly too. Rows that test the same jar on another
 * release through a {@code -P<module>.minecraft} override skip visibly instead.
 *
 * <p>A refresh run ({@code -Pquicraft.emitPlayIds}) emits even when the live protocol differs from
 * {@link #PROTOCOL}, so a protocol bump can be captured before the constant, the routing resource
 * and core's expected inventory are updated; it writes {@code play-ids-<live>.txt} in that case.
 * The pins (shipped table and checked-in inventory) are skipped in a refresh run.
 *
 * <p>With {@code -Pquicraft.emitPlayIds} (system property {@code quicraft.gametest.emitPlayIds}, see
 * docs/platforms/fabric.md) it writes and prints the full inventory of both directions, one
 * {@code <direction> <id> <name>} line per packet sorted by id. That is how the fixture is
 * regenerated from the live game instead of hand-edited; a refresh run skips the comparison.
 */
public class RoutingTableGameTest implements FabricClientGameTest {
    /** The shipped 26.1.2 protocol number. */
    private static final int PROTOCOL = 775;

    /** The checked-in inventory, on the gametest runtime classpath (quicraft.fabric-conventions). */
    private static final String INVENTORY_RESOURCE =
            "rs/sudoe/quicraft/core/routing/play-ids-" + PROTOCOL + ".txt";

    /** Where a refresh run writes an inventory, from the repository root. */
    private static final String INVENTORY_DIR = "core/src/test/resources/rs/sudoe/quicraft/core/routing";

    /** Makes the test emit the inventory: -Pquicraft.emitPlayIds=true (default path) or a file path. */
    private static final String EMIT_PROPERTY = "quicraft.gametest.emitPlayIds";

    /**
     * Set by the build: whether this module is built for its own game version, with no
     * {@code -P<module>.minecraft} override. True means a protocol change is a stale fixture.
     */
    private static final String PINNED_PROPERTY = "quicraft.gametest.pinnedVersion";

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
        if (!liveProtocolAvailable()) {
            System.out.println("[QUICraft] the play protocol classes are obfuscated in this jar"
                    + " (1.21.11); the protocol-" + PROTOCOL + " inventory check does not apply");
            return;
        }
        int liveProtocol = SharedConstants.getProtocolVersion();
        boolean refresh = System.getProperty(EMIT_PROPERTY) != null;
        try {
            Map<String, Integer> clientbound = liveIds("CLIENTBOUND_TEMPLATE");
            Map<String, Integer> serverbound = liveIds("SERVERBOUND_TEMPLATE");
            if (liveProtocol != PROTOCOL) {
                if (refresh) {
                    emit(liveProtocol, clientbound, serverbound);
                    System.out.println("[QUICraft] captured protocol " + liveProtocol + "; update PROTOCOL,"
                            + " protocol-" + liveProtocol + ".json and its pins, and core's"
                            + " PlayIdInventoryTest, then re-run without -Pquicraft.emitPlayIds");
                    return;
                }
                String message = "the live game is PLAY protocol " + liveProtocol
                        + ", but the checked-in inventory pins " + PROTOCOL + " (" + INVENTORY_RESOURCE + ")";
                if (Boolean.parseBoolean(System.getProperty(PINNED_PROPERTY, "true"))) {
                    throw new AssertionError(message + ". Refresh the fixture: " + refreshCommand(liveProtocol));
                }
                System.out.println("[QUICraft] " + message + "; this row tests another release"
                        + " (a -P<module>.minecraft override), skipping");
                return;
            }
            if (refresh) {
                emit(PROTOCOL, clientbound, serverbound);
                System.out.println("[QUICraft] refresh run: the shipped-table pins and the checked-in"
                        + " inventory comparison are skipped");
                return;
            }
            pin(clientbound, serverbound);
            compareInventory(clientbound, serverbound);
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
     * Writes the full inventory of protocol {@code protocolNumber} to the file named by
     * {@link #EMIT_PROPERTY} (or {@code play-ids-<protocol>.txt}) and prints it between BEGIN/END
     * markers, so either capture path works. Called for a refresh, which is why it runs even when
     * the live protocol differs from {@link #PROTOCOL}; the pins are skipped there.
     */
    private static void emit(int protocolNumber, Map<String, Integer> clientbound,
            Map<String, Integer> serverbound) throws IOException {
        String target = System.getProperty(EMIT_PROPERTY);
        String label = "QUICraft play packet inventory, protocol " + protocolNumber;
        List<String> lines = inventory(clientbound, serverbound);
        StringBuilder block = new StringBuilder(label).append(" BEGIN\n");
        for (String line : lines) {
            block.append(line).append('\n');
        }
        block.append(label).append(" END");
        System.out.println(block);

        Path path = Paths.get(target.isEmpty() || target.equals("true")
                ? "play-ids-" + protocolNumber + ".txt"
                : target);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder file = new StringBuilder();
        file.append("# QUICraft PLAY packet inventory, protocol ").append(protocolNumber)
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

    /**
     * The complete live play protocol must equal the checked-in inventory, both directions and every
     * id. The routed subset in {@link #EXPECTED} cannot catch a new CONTROL packet, so the whole
     * mapping is compared here.
     */
    private static void compareInventory(Map<String, Integer> clientbound, Map<String, Integer> serverbound)
            throws IOException {
        Map<Direction, Map<Integer, String>> checkedIn = readInventory();
        Map<Direction, Map<Integer, String>> live =
                new EnumMap<Direction, Map<Integer, String>>(Direction.class);
        live.put(Direction.CLIENTBOUND, byId(clientbound));
        live.put(Direction.SERVERBOUND, byId(serverbound));
        for (Direction direction : Direction.values()) {
            Map<Integer, String> expected = checkedIn.get(direction);
            Map<Integer, String> actual = live.get(direction);
            check(expected.equals(actual), "the checked-in " + direction.wireName()
                    + " PLAY protocol does not match the live game (" + differences(expected, actual)
                    + "). The fixture is stale: refresh it with " + refreshCommand(PROTOCOL));
        }
    }

    /** The checked-in inventory as direction to id to name, parsed like the emitted lines. */
    private static Map<Direction, Map<Integer, String>> readInventory() throws IOException {
        InputStream in = RoutingTableGameTest.class.getClassLoader().getResourceAsStream(INVENTORY_RESOURCE);
        check(in != null, "the checked-in inventory " + INVENTORY_RESOURCE
                + " is not on the gametest classpath (quicraft.fabric-conventions.gradle.kts)");
        Map<Direction, Map<Integer, String>> byDirection =
                new EnumMap<Direction, Map<Integer, String>>(Direction.class);
        for (Direction direction : Direction.values()) {
            byDirection.put(direction, new TreeMap<Integer, String>());
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                check(parts.length == 3, INVENTORY_RESOURCE + " line " + number
                        + " is not <direction> <id> <name>: " + line);
                Direction direction = Direction.of(parts[0]);
                int id = Integer.parseInt(parts[1]);
                String previous = byDirection.get(direction).put(id, parts[2]);
                check(previous == null, INVENTORY_RESOURCE + " line " + number + " repeats "
                        + direction.wireName() + " id " + id + " (" + previous + " and " + parts[2] + ")");
            }
        }
        return byDirection;
    }

    /** What differs, compactly: missing ids, unexpected ids and ids bound to another name. */
    private static String differences(Map<Integer, String> expected, Map<Integer, String> actual) {
        StringBuilder message = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            String liveName = actual.get(entry.getKey());
            if (liveName == null) {
                shown = appendDifference(message, shown, "missing " + entry.getKey() + "=" + entry.getValue());
            } else if (!liveName.equals(entry.getValue())) {
                shown = appendDifference(message, shown, "id " + entry.getKey() + " is " + liveName
                        + ", not " + entry.getValue());
            }
        }
        for (Map.Entry<Integer, String> entry : actual.entrySet()) {
            if (!expected.containsKey(entry.getKey())) {
                shown = appendDifference(message, shown,
                        "unexpected " + entry.getKey() + "=" + entry.getValue());
            }
        }
        return shown == 0 ? "the same entries" : message.toString();
    }

    private static int appendDifference(StringBuilder message, int shown, String difference) {
        if (shown == 10) {
            message.append(", ...");
        } else if (shown < 10) {
            message.append(shown == 0 ? "" : ", ").append(difference);
        }
        return shown + 1;
    }

    /**
     * The refresh command, run from the repository root (docs/platforms/fabric.md has the caveat).
     * It emits even when the live protocol differs from {@link #PROTOCOL}, writing the inventory
     * under the live protocol's name, so it is the command a protocol-bump failure recommends.
     */
    private static String refreshCommand(int protocolNumber) {
        return "xvfb-run -a -s \"-screen 0 1280x720x24\" ./gradlew :fabric-26x:runClientGameTest"
                + " -Pquicraft.emitPlayIds=" + INVENTORY_DIR + "/play-ids-" + protocolNumber + ".txt"
                + " (it emits even on a protocol mismatch), then update PROTOCOL, protocol-"
                + protocolNumber + ".json and its pins, and core's PlayIdInventoryTest, and re-run";
    }

    /** Whether the game's play protocol can be read by name (false on 1.21.11, where it is obfuscated). */
    private static boolean liveProtocolAvailable() {
        try {
            Class.forName("net.minecraft.network.protocol.game.GameProtocols");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** The same mapping as id to name. */
    private static Map<Integer, String> byId(Map<String, Integer> ids) {
        Map<Integer, String> byId = new TreeMap<Integer, String>();
        for (Map.Entry<String, Integer> entry : ids.entrySet()) {
            byId.put(entry.getValue(), entry.getKey());
        }
        return byId;
    }

    /** One {@code <direction> <id> <name>} line per live play packet, ids ascending per direction. */
    private static List<String> inventory(Map<String, Integer> clientbound, Map<String, Integer> serverbound) {
        List<String> lines = new ArrayList<String>();
        lines.addAll(directionLines(Direction.CLIENTBOUND, clientbound));
        lines.addAll(directionLines(Direction.SERVERBOUND, serverbound));
        return lines;
    }

    private static List<String> directionLines(Direction direction, Map<String, Integer> ids) {
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<Integer, String> entry : byId(ids).entrySet()) {
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
