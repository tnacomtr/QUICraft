// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;

/**
 * Coverage of the reviewed protocol-775 table against the live inventory (docs/protocol.md 13.7,
 * 13.8). Every play id in both directions must have exactly one intentional route; a missing or an
 * extra route fails. The per-id classes below are written out here, independently of the table
 * resource, so a reclassification in the JSON cannot pass without this file changing too.
 */
class ShippedRoutingCoverageTest {
    private static final int PROTOCOL = 775;
    private static final String INVENTORY = RoutingTableLoader.RESOURCE_DIR + "/play-ids-775.txt";

    /**
     * 13.8 REALTIME entity and animation set, plus block_destruction (13.8 names it); block_event stays global.
     * animate (2) and set_entity_data (99) are global: wake-from-bed and the sleeping-position metadata move the
     * local player against the current block state (security review 2026-10-21).
     */
    private static final Set<Integer> REALTIME = set(42, 47, 53, 54, 55, 56, 83, 101, 102, 116, 131, 5);
    /** Reviewed CHUNK routes: install (45), block updates (6, 8, 84) and forget (37). */
    private static final Map<Integer, String> CHUNK = map(45, "chunk_xz", 6, "block_position", 8, "block_position",
            84, "section_position", 37, "chunk_pos_long");
    /**
     * 13.21 {@code ack}: every CHUNK route also waits for the latest ACK-stream record. Install,
     * block-entity data, forget and the block-state updates (8, 84) must not overtake a pending
     * prediction acknowledgement: the ACK restores the saved server state and collision-snaps, so a
     * later block update applied first changes what the player sees (security review 2026-10-21).
     */
    private static final Set<Integer> CHUNK_ACK = set(45, 6, 37, 8, 84);
    /** 13.21: block_changed_ack (4) and chunk_batch_finished (11) ride the ACK stream. */
    private static final Set<Integer> ACK_LANE = set(4, 11);
    /** 13.8/13.19: chunk_batch_start uses the view scope. */
    private static final Set<Integer> VIEW = set(12);
    /** 13.5 control fences: no chunk wait, but later REALTIME waits for them (B1 minus 72). */
    private static final Set<Integer> CONTROL_FENCE = set(1, 64, 77, 100, 107, 124);
    /** 13.5 ordinary CONTROL: own CONTROL predecessor and G only; no chunk wait, no REALTIME fence. */
    private static final Set<Integer> ORDINARY = set(3, 9, 10, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 25,
            26, 27, 28, 29, 30, 31, 33, 34, 38, 39, 40, 41, 43, 44, 50, 51, 52, 58, 59, 61, 62, 63, 65,
            66, 67, 68, 69, 70, 73, 74, 75, 76, 78, 79, 80, 81, 85, 86, 87, 88, 89, 90, 91, 92, 93,
            96, 97, 98, 103, 104, 105, 106, 108, 109, 110, 112, 113, 114, 115, 117, 119, 120, 121, 122,
            123, 126, 127, 128, 130, 132, 133, 134, 135, 136, 137, 138, 139, 140);
    /** 13.8: a multi-chunk packet stays CONTROL/global with its reviewed list extractor. */
    private static final Map<Integer, String> LIST = map(13, "chunk_list");
    // Position-dependent, kept global so they never overtake a pending ACK collision snap or a block update:
    // animate (2), move_vehicle (35), player_look_at (71), set_entity_data (99) and teleport_entity (125).
    /** 13.18: the level-creating packets login (49) and respawn (82) are CONTROL/global epoch changes. */
    private static final Set<Integer> EPOCH = new HashSet<Integer>(java.util.Arrays.asList(49, 82));
    // Deliberate global deferrals (CP1), all CONTROL/global: light_update (48), with no reviewed layout;
    // explode (36) and level_event (46), kept global as a safe superset of 13.8.

    @Test
    void theShippedTableRoutesEveryInventoryIdExactlyOnce() throws IOException {
        Faults.set();
        RoutingTable table = RoutingTableLoader.loadResource(PROTOCOL);
        List<String> problems = coverage(table.routesV2(), inventory("clientbound"), inventory("serverbound"));
        assertTrue(problems.isEmpty(), "coverage: " + problems);
        assertEquals(210, table.routesV2().size(), "one explicit route per live play id (141 + 69)");
    }

    @Test
    void aMissingRouteIsDetected() throws IOException {
        Map<RouteKey, RouteV2> routes = new LinkedHashMap<RouteKey, RouteV2>(
                RoutingTableLoader.loadResource(PROTOCOL).routesV2());
        RouteKey dropped = new RouteKey(PROTOCOL, ProtocolState.PLAY, Direction.CLIENTBOUND, 45);
        assertTrue(routes.remove(dropped) != null, "the fixture has a route for id 45 to drop");
        List<String> problems = coverage(routes, inventory("clientbound"), inventory("serverbound"));
        assertTrue(problems.toString().contains("missing clientbound 45"), problems.toString());
    }

    @Test
    void anExtraRouteIsDetected() throws IOException {
        Map<RouteKey, RouteV2> routes = new LinkedHashMap<RouteKey, RouteV2>(
                RoutingTableLoader.loadResource(PROTOCOL).routesV2());
        RouteKey extra = new RouteKey(PROTOCOL, ProtocolState.PLAY, Direction.CLIENTBOUND, 999);
        routes.put(extra, new RouteV2(RouteV2.StreamLane.CONTROL, Dependency.GLOBAL, null, null, false, null, null, null));
        List<String> problems = coverage(routes, inventory("clientbound"), inventory("serverbound"));
        assertTrue(problems.toString().contains("extra clientbound 999"), problems.toString());
    }

    @Test
    void aReclassifiedRouteIsDetected() throws IOException {
        Map<RouteKey, RouteV2> routes = new LinkedHashMap<RouteKey, RouteV2>(
                RoutingTableLoader.loadResource(PROTOCOL).routesV2());
        // Move the chunk install onto REALTIME: that must fail, because 13.8 forbids moving an update off CHUNK.
        RouteKey install = new RouteKey(PROTOCOL, ProtocolState.PLAY, Direction.CLIENTBOUND, 45);
        routes.put(install, new RouteV2(RouteV2.StreamLane.REALTIME, Dependency.LANE, null, null, false, null, null, null));
        List<String> problems = coverage(routes, inventory("clientbound"), inventory("serverbound"));
        assertTrue(problems.toString().contains("clientbound 45"), problems.toString());
    }

    /**
     * The narrowed clientbound classes are exact id sets, not merely "some routes are ordinary":
     * 92 ordinary CONTROL ids and the six control fences of 13.5 (B1 minus player_position 72,
     * move_vehicle 35 and teleport_entity 125; player_look_at 71 is global too).
     * Every other clientbound route is global, view, list, realtime or CHUNK, and each of those is
     * pinned separately above.
     */
    @Test
    void theOrdinaryAndControlFenceSetsAreExactlyTheReviewedIds() throws IOException {
        Faults.set();
        Set<Integer> ordinary = new HashSet<Integer>();
        Set<Integer> control = new HashSet<Integer>();
        for (Map.Entry<RouteKey, RouteV2> entry : RoutingTableLoader.loadResource(PROTOCOL).routesV2().entrySet()) {
            RouteKey key = entry.getKey();
            if (key.state() != ProtocolState.PLAY || key.direction() != Direction.CLIENTBOUND) continue;
            if (entry.getValue().dependency == Dependency.ORDINARY) ordinary.add(key.packetId());
            if (entry.getValue().dependency == Dependency.CONTROL) control.add(key.packetId());
        }
        assertEquals(ORDINARY, ordinary, "the clientbound ordinary CONTROL set");
        assertEquals(CONTROL_FENCE, control, "the clientbound control-fence set");
        assertEquals(92, ordinary.size(), "92 ordinary ids (13.5)");
        assertEquals(6, control.size(), "six control fences (B1 minus 35, 72 and 125)");
    }

    /**
     * 13.21: exactly block_changed_ack (4) and chunk_batch_finished (11) use the ACK lane, both
     * {@code trailing}; every CHUNK route (45, 6, 8, 84, 37) adds the {@code ack} dependency.
     */
    @Test
    void theAckStreamAndAckDependencySetsAreExactlyTheReviewedIds() throws IOException {
        Faults.set();
        Set<Integer> ackLane = new HashSet<Integer>();
        Set<Integer> ackDependency = new HashSet<Integer>();
        for (Map.Entry<RouteKey, RouteV2> entry : RoutingTableLoader.loadResource(PROTOCOL).routesV2().entrySet()) {
            RouteKey key = entry.getKey();
            if (key.state() != ProtocolState.PLAY || key.direction() != Direction.CLIENTBOUND) continue;
            RouteV2 route = entry.getValue();
            if (route.lane == RouteV2.StreamLane.ACK) {
                ackLane.add(key.packetId());
                assertEquals(Dependency.TRAILING, route.dependency, "ACK lane dependency for " + key);
                assertEquals(null, route.position, "the ACK lane carries no position: " + key);
            }
            if (route.dependency == Dependency.ACK) {
                ackDependency.add(key.packetId());
            }
        }
        assertEquals(ACK_LANE, ackLane, "the clientbound ACK-lane set");
        assertEquals(CHUNK_ACK, ackDependency, "the clientbound ack-dependency set");
    }

    /**
     * Problems between the table and the inventory: a missing or extra id, or a route that does not
     * match the class written above for its id. Empty when the table is exactly the reviewed one.
     */
    static List<String> coverage(Map<RouteKey, RouteV2> routes, Map<Integer, String> clientbound,
            Map<Integer, String> serverbound) {
        List<String> problems = new ArrayList<String>();
        Map<Integer, RouteV2> cb = new LinkedHashMap<Integer, RouteV2>();
        Map<Integer, RouteV2> sb = new LinkedHashMap<Integer, RouteV2>();
        for (Map.Entry<RouteKey, RouteV2> entry : routes.entrySet()) {
            RouteKey key = entry.getKey();
            if (key.state() != ProtocolState.PLAY) {
                problems.add("non-play route " + key);
                continue;
            }
            Map<Integer, RouteV2> target = key.direction() == Direction.CLIENTBOUND ? cb : sb;
            target.put(key.packetId(), entry.getValue());
        }
        for (Map.Entry<Integer, String> id : clientbound.entrySet()) {
            RouteV2 route = cb.remove(id.getKey());
            if (route == null) {
                problems.add("missing clientbound " + id.getKey() + " " + id.getValue());
                continue;
            }
            String wrong = clientboundMismatch(id.getKey(), route);
            if (wrong != null) problems.add("clientbound " + id.getKey() + " " + id.getValue() + ": " + wrong);
        }
        for (Integer extra : cb.keySet()) problems.add("extra clientbound " + extra);
        for (Map.Entry<Integer, String> id : serverbound.entrySet()) {
            RouteV2 route = sb.remove(id.getKey());
            if (route == null) {
                problems.add("missing serverbound " + id.getKey() + " " + id.getValue());
                continue;
            }
            // Serverbound dual-lane is deferred (CP1): every serverbound id is CONTROL/global, ordinarySmall false.
            if (route.lane != RouteV2.StreamLane.CONTROL || route.dependency != Dependency.GLOBAL
                    || route.position != null || route.epochChange || !Boolean.FALSE.equals(route.ordinarySmall)) {
                problems.add("serverbound " + id.getKey() + " " + id.getValue() + " is not control/global");
            }
        }
        for (Integer extra : sb.keySet()) problems.add("extra serverbound " + extra);
        return problems;
    }

    /** What the reviewed class of a clientbound id is, or null when the route matches it. */
    private static String clientboundMismatch(int id, RouteV2 route) {
        if (route.epochChange != EPOCH.contains(id)) {
            return "epoch_change is " + route.epochChange + ", expected " + EPOCH.contains(id) + " (13.18)";
        }
        if (REALTIME.contains(id)) {
            return expect(route, RouteV2.StreamLane.REALTIME, Dependency.LANE, null);
        }
        if (ACK_LANE.contains(id)) {
            return expect(route, RouteV2.StreamLane.ACK, Dependency.TRAILING, null);
        }
        if (CHUNK.containsKey(id)) {
            return expect(route, RouteV2.StreamLane.CHUNK,
                    CHUNK_ACK.contains(id) ? Dependency.ACK : Dependency.LANE, CHUNK.get(id));
        }
        if (VIEW.contains(id)) {
            return expect(route, RouteV2.StreamLane.CONTROL, Dependency.VIEW, null);
        }
        if (CONTROL_FENCE.contains(id)) {
            return expect(route, RouteV2.StreamLane.CONTROL, Dependency.CONTROL, null);
        }
        if (ORDINARY.contains(id)) {
            return expect(route, RouteV2.StreamLane.CONTROL, Dependency.ORDINARY, null);
        }
        if (LIST.containsKey(id)) {
            return expect(route, RouteV2.StreamLane.CONTROL, Dependency.GLOBAL, LIST.get(id));
        }
        // Every other id, including the deferrals: CONTROL/global with no position (13.5 default).
        return expect(route, RouteV2.StreamLane.CONTROL, Dependency.GLOBAL, null);
    }

    private static String expect(RouteV2 route, RouteV2.StreamLane lane, Dependency dependency, String position) {
        if (route.lane != lane) return "lane " + route.lane + ", expected " + lane;
        if (route.dependency != dependency) return "dependency " + route.dependency + ", expected " + dependency;
        if (!java.util.Objects.equals(route.position, position)) {
            return "position " + route.position + ", expected " + position;
        }
        return null;
    }

    /** The inventory's ids and names for one direction, read from the checked-in fixture. */
    private static Map<Integer, String> inventory(String direction) throws IOException {
        Map<Integer, String> ids = new LinkedHashMap<Integer, String>();
        for (String line : read(INVENTORY).split("\n")) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] fields = line.trim().split("\\s+");
            if (fields[0].equals(direction)) {
                ids.put(Integer.parseInt(fields[1]), fields[2]);
            }
        }
        assertTrue(!ids.isEmpty(), "no " + direction + " ids in " + INVENTORY);
        return ids;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = ShippedRoutingCoverageTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Set<Integer> set(Integer... values) {
        return new HashSet<Integer>(Arrays.asList(values));
    }

    private static Map<Integer, String> map(Object... pairs) {
        Map<Integer, String> map = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Integer) pairs[i], (String) pairs[i + 1]);
        }
        return map;
    }
}
