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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RouteV2;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;

/**
 * Pins the shipped 26.1.2 routing table (protocol 775, docs/protocol.md 13.6 to 13.8) to the live
 * game. The play packet ids are read from the game's own protocol templates, the same registration
 * order the server and client use, and the live maps alone define the keyset every route must cover.
 * Each route's lane, dependency and position are compared against the literal classes written out
 * below, never read out of the resource. A version bump that shifts ids, or a resource edit that
 * moves or reclassifies a route, fails here by name.
 *
 * <p>The table is schema 2 (13.7): 210 explicit routes, one per live play id. REALTIME is exactly
 * the 14 entity and animation updates 13.8 names; chunk_batch_start is the one VIEW route and
 * chunks_biomes the one listed-control route ({@code chunk_list}). Five chunk routes (45, 6, 8,
 * 37, 84) are CHUNK/lane with their reviewed position layouts; 93 clientbound routes are ordinary
 * CONTROL and eight are control fences (13.5, B1 minus player_position 72). Every other clientbound
 * route is CONTROL/global, no route carries an epoch change, and every serverbound route is
 * CONTROL/global with {@code ordinarySmall} false. The ack/batch-finished joins, light updates and
 * login/respawn stay CONTROL/global until their wiring lands. A future CHUNK or epoch flip must
 * deliberately revise these pins together with its wiring tests.
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

    /** The live play inventory on the pinned version: 141 clientbound and 69 serverbound ids. */
    private static final int CLIENTBOUND_ROUTES = 141;
    private static final int SERVERBOUND_ROUTES = 69;
    private static final int TOTAL_ROUTES = CLIENTBOUND_ROUTES + SERVERBOUND_ROUTES;

    /**
     * The literal REALTIME set, as {@code live-id name}: entity updates whose entity was spawned on
     * CONTROL before them, plus level particles and the two block-change records 13.8 names. Exactly
     * these may use REALTIME, dependency LANE, no position and no epoch change; anything else is
     * CONTROL (13.6, 13.8). The lane's ordering is tested in core's ShippedRoutingOrderingTest.
     */
    private static final String[] REALTIME = {
        "2 minecraft:animate",
        "5 minecraft:block_destruction",
        "42 minecraft:hurt_animation",
        "47 minecraft:level_particles",
        "53 minecraft:move_entity_pos",
        "54 minecraft:move_entity_pos_rot",
        "55 minecraft:move_minecart_along_track",
        "56 minecraft:move_entity_rot",
        "83 minecraft:rotate_head",
        "99 minecraft:set_entity_data",
        "101 minecraft:set_entity_motion",
        "102 minecraft:set_equipment",
        "116 minecraft:sound_entity",
        "131 minecraft:update_attributes",
    };

    /** chunk_batch_start: CONTROL waiting for the view frontier (13.5, 13.8). */
    private static final int VIEW_ID = 12;

    /** chunks_biomes: CONTROL/global carrying its reviewed list extractor (13.8). */
    private static final int LIST_ID = 13;
    private static final String LIST_POSITION = "chunk_list";

    /** The five reviewed CHUNK routes, as {@code live-id name position} (13.7). */
    private static final String[] CHUNK = {
        "45 minecraft:level_chunk_with_light chunk_xz",
        "6 minecraft:block_entity_data block_position",
        "8 minecraft:block_update block_position",
        "84 minecraft:section_blocks_update section_position",
        "37 minecraft:forget_level_chunk chunk_pos_long",
    };

    /** The 93 clientbound ordinary CONTROL ids (13.5): no chunk wait, no later-REALTIME fence. */
    private static final int[] ORDINARY = {3, 9, 10, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 25,
        26, 27, 28, 29, 30, 31, 33, 34, 38, 39, 40, 41, 43, 44, 50, 51, 52, 58, 59, 61, 62, 63, 65,
        66, 67, 68, 69, 70, 71, 73, 74, 75, 76, 78, 79, 80, 81, 85, 86, 87, 88, 89, 90, 91, 92, 93,
        96, 97, 98, 103, 104, 105, 106, 108, 109, 110, 112, 113, 114, 115, 117, 119, 120, 121, 122,
        123, 126, 127, 128, 130, 132, 133, 134, 135, 136, 137, 138, 139, 140};

    /** The eight clientbound control fences (13.5, B1 minus player_position 72). */
    private static final int[] CONTROL_FENCE = {1, 35, 64, 77, 100, 107, 124, 125};

    /**
     * The 13.5 must-use-CONTROL keys, already enforced by the loader from
     * {@code protocol-775-mandatory-control.json}; re-checked here on the loaded table.
     */
    private static final int[] MANDATORY_CONTROL_CLIENTBOUND = {32, 49, 82, 118, 129};
    private static final int MANDATORY_CONTROL_SERVERBOUND = 16;

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
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL)
                .orElseThrow(() -> new AssertionError("no routing table ships for protocol " + PROTOCOL));
        check(table.schema() == 2, "the shipped protocol-" + PROTOCOL + " table is schema "
                + table.schema() + ", expected schema 2 (13.7)");
        Map<RouteKey, RouteV2> routes = table.routesV2();

        // The live maps are the only source of the keyset: every live play id must have exactly one
        // explicit route, and the table must not route an id the live game does not have (13.8).
        Map<RouteKey, String> live = new LinkedHashMap<RouteKey, String>();
        for (Map.Entry<String, Integer> entry : clientbound.entrySet()) {
            live.put(new RouteKey(PROTOCOL, ProtocolState.PLAY, Direction.CLIENTBOUND, entry.getValue()),
                    entry.getKey());
        }
        for (Map.Entry<String, Integer> entry : serverbound.entrySet()) {
            live.put(new RouteKey(PROTOCOL, ProtocolState.PLAY, Direction.SERVERBOUND, entry.getValue()),
                    entry.getKey());
        }
        check(clientbound.size() == CLIENTBOUND_ROUTES, "the live clientbound play protocol has "
                + clientbound.size() + " packets, expected " + CLIENTBOUND_ROUTES);
        check(serverbound.size() == SERVERBOUND_ROUTES, "the live serverbound play protocol has "
                + serverbound.size() + " packets, expected " + SERVERBOUND_ROUTES);
        check(live.size() == TOTAL_ROUTES, "the live play protocol has " + live.size()
                + " distinct play ids, expected " + TOTAL_ROUTES);
        check(routes.keySet().equals(live.keySet()), "the shipped table keys are not the live play"
                + " ids: " + keyDifferences(routes.keySet(), live.keySet()));

        // The literal REALTIME pins: exactly these 14 clientbound names, LANE, no position, no epoch.
        Set<Integer> realtime = new HashSet<Integer>();
        for (String line : REALTIME) {
            String[] parts = line.split(" ", 2);
            int id = Integer.parseInt(parts[0]);
            realtime.add(id);
            expectLive(routes, clientbound, Direction.CLIENTBOUND, id, parts[1],
                    RouteV2.StreamLane.REALTIME, Dependency.LANE, null);
        }
        // The five reviewed CHUNK routes, each with its live name and position layout.
        Set<Integer> chunk = new HashSet<Integer>();
        for (String line : CHUNK) {
            String[] parts = line.split(" ");
            int id = Integer.parseInt(parts[0]);
            chunk.add(id);
            expectLive(routes, clientbound, Direction.CLIENTBOUND, id, parts[1],
                    RouteV2.StreamLane.CHUNK, Dependency.LANE, parts[2]);
        }
        Set<Integer> ordinary = ids(ORDINARY);
        Set<Integer> controlFence = ids(CONTROL_FENCE);
        // chunk_batch_start is the one VIEW route, chunks_biomes the one listed CONTROL route.
        expectLive(routes, clientbound, Direction.CLIENTBOUND, VIEW_ID, "minecraft:chunk_batch_start",
                RouteV2.StreamLane.CONTROL, Dependency.VIEW, null);
        expectLive(routes, clientbound, Direction.CLIENTBOUND, LIST_ID, "minecraft:chunks_biomes",
                RouteV2.StreamLane.CONTROL, Dependency.GLOBAL, LIST_POSITION);
        for (Map.Entry<String, Integer> entry : clientbound.entrySet()) {
            int id = entry.getValue();
            if (realtime.contains(id) || chunk.contains(id) || id == VIEW_ID || id == LIST_ID) {
                continue;
            }
            if (ordinary.contains(id)) {
                expect(routes, Direction.CLIENTBOUND, id, entry.getKey(), RouteV2.StreamLane.CONTROL,
                        Dependency.ORDINARY, null);
            } else if (controlFence.contains(id)) {
                expect(routes, Direction.CLIENTBOUND, id, entry.getKey(), RouteV2.StreamLane.CONTROL,
                        Dependency.CONTROL, null);
            } else {
                expect(routes, Direction.CLIENTBOUND, id, entry.getKey(), RouteV2.StreamLane.CONTROL,
                        Dependency.GLOBAL, null);
            }
        }

        // "Exactly these" for each narrowed class: the routed sets are the literal ones, and no
        // other id uses the class (13.5, 13.8).
        Set<Integer> routedRealtime = new HashSet<Integer>();
        Set<Integer> routedChunk = new HashSet<Integer>();
        Set<Integer> routedOrdinary = new HashSet<Integer>();
        Set<Integer> routedControl = new HashSet<Integer>();
        for (Map.Entry<RouteKey, RouteV2> entry : routes.entrySet()) {
            RouteKey key = entry.getKey();
            RouteV2 route = entry.getValue();
            if (route.lane == RouteV2.StreamLane.REALTIME) {
                routedRealtime.add(key.packetId());
            }
            if (route.lane == RouteV2.StreamLane.CHUNK) {
                routedChunk.add(key.packetId());
            }
            if (key.state() == ProtocolState.PLAY && key.direction() == Direction.CLIENTBOUND) {
                if (route.dependency == Dependency.ORDINARY) {
                    routedOrdinary.add(key.packetId());
                } else if (route.dependency == Dependency.CONTROL) {
                    routedControl.add(key.packetId());
                }
            }
        }
        check(routedRealtime.equals(realtime), "the shipped table routes REALTIME ids "
                + routedRealtime + ", expected " + realtime);
        check(routedChunk.equals(chunk), "the shipped table routes CHUNK ids "
                + routedChunk + ", expected " + chunk);
        check(routedOrdinary.equals(ordinary), "the shipped table routes ordinary CONTROL ids "
                + routedOrdinary + ", expected " + ordinary);
        check(routedControl.equals(controlFence), "the shipped table routes control-fence ids "
                + routedControl + ", expected " + controlFence);

        // Every serverbound route is CONTROL/global, no position, no epoch, ordinarySmall false.
        for (Map.Entry<String, Integer> entry : serverbound.entrySet()) {
            RouteV2 route = expect(routes, Direction.SERVERBOUND, entry.getValue(), entry.getKey(),
                    RouteV2.StreamLane.CONTROL, Dependency.GLOBAL, null);
            check(Boolean.FALSE.equals(route.ordinarySmall), "the shipped table gives serverbound "
                    + entry.getKey() + " ordinarySmall " + route.ordinarySmall + ", expected false");
        }

        // The 13.5 must-use-CONTROL pins still fence globally, as the loader required at load time.
        for (int id : MANDATORY_CONTROL_CLIENTBOUND) {
            requireGlobal(routes, Direction.CLIENTBOUND, id);
        }
        requireGlobal(routes, Direction.SERVERBOUND, MANDATORY_CONTROL_SERVERBOUND);

        System.out.println("[QUICraft] schema 2 pins passed: " + routes.size() + " routes, "
                + routedRealtime.size() + " REALTIME, " + routedChunk.size() + " CHUNK, "
                + routedOrdinary.size() + " ordinary, " + routedControl.size() + " control fences");
    }

    /** The live name must bind to {@code id}, and the route must carry the literal classification. */
    private static RouteV2 expectLive(Map<RouteKey, RouteV2> routes, Map<String, Integer> live,
            Direction direction, int id, String name, RouteV2.StreamLane lane, Dependency dependency,
            String position) {
        Integer liveId = live.get(name);
        check(liveId != null && liveId == id, "the live " + direction.wireName()
                + " play protocol names " + name + " as id " + liveId + ", the expected id is " + id);
        return expect(routes, direction, id, name, lane, dependency, position);
    }

    /** The route for one live play id, with the literal classification every pin requires. */
    private static RouteV2 expect(Map<RouteKey, RouteV2> routes, Direction direction, int id,
            String name, RouteV2.StreamLane lane, Dependency dependency, String position) {
        RouteKey key = new RouteKey(PROTOCOL, ProtocolState.PLAY, direction, id);
        RouteV2 route = routes.get(key);
        check(route != null, "the shipped table has no route for " + name + " (" + key + ")");
        check(route.lane == lane, "the shipped table routes " + name + " to " + route.lane
                + ", expected " + lane);
        check(route.dependency == dependency, "the shipped table gives " + name + " dependency "
                + route.dependency + ", expected " + dependency);
        check(Objects.equals(route.position, position), "the shipped table gives " + name
                + " position " + route.position + ", expected " + position);
        check(!route.epochChange, "the shipped table marks " + name + " as an epoch change; epoch"
                + " changes are a deliberate deferral (13.8-13.19)");
        return route;
    }

    /** A 13.5 must-use-CONTROL key must be an explicit CONTROL/global route. */
    private static void requireGlobal(Map<RouteKey, RouteV2> routes, Direction direction, int id) {
        RouteKey key = new RouteKey(PROTOCOL, ProtocolState.PLAY, direction, id);
        RouteV2 route = routes.get(key);
        check(route != null, "the 13.5 must-use-CONTROL pin " + key + " has no shipped route");
        check(route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.GLOBAL,
                "the 13.5 must-use-CONTROL pin " + key + " is " + route.lane + "/"
                        + route.dependency + ", expected CONTROL/global");
    }

    /** The literal id arrays above as a set, so routed and pinned sets compare directly. */
    private static Set<Integer> ids(int[] values) {
        Set<Integer> set = new HashSet<Integer>();
        for (int value : values) {
            set.add(value);
        }
        return set;
    }

    /** What differs between the shipped keys and the live ones, like {@link #differences}. */
    private static String keyDifferences(Set<RouteKey> shipped, Set<RouteKey> live) {
        StringBuilder message = new StringBuilder();
        int shown = 0;
        for (RouteKey key : live) {
            if (!shipped.contains(key)) {
                shown = appendDifference(message, shown, "missing " + key);
            }
        }
        for (RouteKey key : shipped) {
            if (!live.contains(key)) {
                shown = appendDifference(message, shown, "unexpected " + key);
            }
        }
        return shown == 0 ? "the same entries" : message.toString();
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
     * id. The literal route pins cannot catch a new CONTROL packet, so the whole mapping is compared
     * here.
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
