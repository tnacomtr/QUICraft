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

    /** 13.8 REALTIME entity and animation set, plus block_destruction and block_event (13.8 names them). */
    private static final Set<Integer> REALTIME = set(2, 42, 47, 53, 54, 55, 56, 83, 99, 101, 102, 116, 131, 5);
    /**
     * Reviewed CHUNK route enabled by this unit. Block updates and acknowledgement remain global until
     * the ACK-coverage wiring required by 13.19 is on the production send path.
     */
    private static final Map<Integer, String> CHUNK = map(45, "chunk_xz", 6, "block_position", 8, "block_position", 84, "section_position");
    /** 13.8/13.19: chunk_batch_start uses the view scope. */
    private static final Set<Integer> VIEW = set(12);
    /** 13.8: a multi-chunk packet stays CONTROL/global with its reviewed list extractor. */
    private static final Map<Integer, String> LIST = map(13, "chunk_list");
    // Deliberate global deferrals (CP1), all CONTROL/global: block_changed_ack (4) and chunk_batch_finished (11)
    // join routes, because the send path fails closed on ACK_JOIN/BATCH_JOIN; login (49) and respawn (82),
    // because epoch routes are not wired; forget_level_chunk (37) and light_update (48), with no reviewed
    // layout; explode (36) and level_event (46), kept global as a safe superset of 13.8.

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
        if (route.epochChange) return "epoch change is deferred (CP1)";
        if (REALTIME.contains(id)) {
            return expect(route, RouteV2.StreamLane.REALTIME, Dependency.LANE, null);
        }
        if (CHUNK.containsKey(id)) {
            return expect(route, RouteV2.StreamLane.CHUNK, Dependency.LANE, CHUNK.get(id));
        }
        if (VIEW.contains(id)) {
            return expect(route, RouteV2.StreamLane.CONTROL, Dependency.VIEW, null);
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
