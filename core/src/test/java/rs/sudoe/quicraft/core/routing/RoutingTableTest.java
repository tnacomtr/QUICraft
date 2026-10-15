// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Lookup semantics (13.6) and the immutability of the parsed table. */
class RoutingTableTest {

    private static final String JSON = "{\"schema\":1,\"protocol\":763,\"default\":\"control\",\"routes\":["
            + "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":10,"
            + "\"lane\":\"control\",\"dependency\":\"fence\"},"
            + "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":11,"
            + "\"lane\":\"realtime\",\"dependency\":\"lane\"},"
            + "{\"state\":\"play\",\"direction\":\"serverbound\",\"packet_id\":26,"
            + "\"lane\":\"world\",\"dependency\":\"lane\"}]}";

    private static RoutingTable table() {
        return RoutingTableLoader.parse(JSON);
    }

    @Test
    void missingEntriesDefaultToControl() {
        RoutingTable table = table();
        // 13.6: missing entries default to CONTROL, never an inferred category.
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 99));
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.LOGIN, Direction.CLIENTBOUND, 11));
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.PLAY, Direction.SERVERBOUND, 11));
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.HANDSHAKING, Direction.SERVERBOUND, 0));
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, Integer.MAX_VALUE));
    }

    @Test
    void anEmptyRouteListIsAllControl() {
        RoutingTable table = RoutingTableLoader.parse(
                "{\"schema\":1,\"protocol\":763,\"default\":\"control\",\"routes\":[]}");
        assertTrue(table.routes().isEmpty());
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 10));
    }

    @Test
    void lookupIsExactOnAllFourComponents() {
        RoutingTable table = table();
        assertEquals(Lane.CONTROL, table.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 10));
        assertEquals(Lane.REALTIME, table.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
        assertEquals(Lane.WORLD, table.laneFor(ProtocolState.PLAY, Direction.SERVERBOUND, 26));
    }

    @Test
    void routeForReportsLaneAndDependency() {
        RoutingTable table = table();
        Optional<Route> route = table.routeFor(
                new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 10));
        assertTrue(route.isPresent());
        assertEquals(Lane.CONTROL, route.get().lane());
        assertEquals(Dependency.FENCE, route.get().dependency());

        Optional<Route> realtime = table.routeFor(
                new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
        assertTrue(realtime.isPresent());
        assertEquals(Dependency.LANE, realtime.get().dependency());

        assertFalse(table.routeFor(new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 12)).isPresent());
        // Another protocol number is a different table's key (13.6), so it is not this entry.
        assertFalse(table.routeFor(new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 10)).isPresent());
    }

    @Test
    void routesViewIsUnmodifiable() {
        RoutingTable table = table();
        Map<RouteKey, Route> routes = table.routes();
        assertEquals(3, routes.size());
        assertThrows(UnsupportedOperationException.class, routes::clear);
        assertThrows(UnsupportedOperationException.class, () -> routes.put(
                new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 40),
                new Route(Lane.WORLD, Dependency.LANE)));
    }

    @Test
    void routesKeepResourceOrder() {
        RoutingTable table = table();
        List<RouteKey> keys = new ArrayList<>(table.routes().keySet());
        assertEquals(Arrays.asList(
                new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 10),
                new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 11),
                new RouteKey(763, ProtocolState.PLAY, Direction.SERVERBOUND, 26)), keys);

        List<Lane> lanes = new ArrayList<>();
        for (Route route : table.routes().values()) {
            lanes.add(route.lane());
        }
        assertEquals(Arrays.asList(Lane.CONTROL, Lane.REALTIME, Lane.WORLD), lanes);
    }

    @Test
    void routesKeepResourceOrderForManyEntries() {
        // Descending packet ids: a hash-ordered map would essentially never reproduce this order,
        // so this catches a regression from resource order to hash order.
        StringBuilder json = new StringBuilder(
                "{\"schema\":1,\"protocol\":763,\"default\":\"control\",\"routes\":[");
        List<Integer> expected = new ArrayList<>();
        for (int id = 40; id > 30; id--) {
            if (!expected.isEmpty()) {
                json.append(',');
            }
            json.append("{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":")
                    .append(id)
                    .append(",\"lane\":\"realtime\",\"dependency\":\"lane\"}");
            expected.add(id);
        }
        json.append("]}");

        List<Integer> actual = new ArrayList<>();
        for (RouteKey key : RoutingTableLoader.parse(json.toString()).routes().keySet()) {
            actual.add(key.packetId());
        }
        assertEquals(expected, actual);
    }

    @Test
    void digestIsDefensive() {
        RoutingTable table = table();
        byte[] first = table.digest();
        assertEquals(32, first.length, "SHA-256");
        String digestHex = table.digestHex();
        byte[] untouched = first.clone();

        java.util.Arrays.fill(first, (byte) 0);

        assertArrayEquals(untouched, table.digest(), "mutating the returned array must not change the table");
        assertEquals(digestHex, table.digestHex());
    }

    @Test
    void concurrentLookupsAreStable() throws Exception {
        RoutingTable table = table();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                jobs.add(() -> {
                    for (int n = 0; n < 5000; n++) {
                        if (table.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 11) != Lane.REALTIME
                                || table.laneFor(ProtocolState.PLAY, Direction.CLIENTBOUND, 99) != Lane.CONTROL
                                || table.protocolNumber() != 763
                                || !table.digestHex().equals(hex(table.digest()))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (Future<Boolean> result : pool.invokeAll(jobs)) {
                assertTrue(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void routeKeyEqualityIsValueBased() {
        RouteKey key = new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 11);
        assertEquals(key, new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
        assertEquals(key.hashCode(), new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 11).hashCode());
        assertNotEquals(key, new RouteKey(763, ProtocolState.PLAY, Direction.SERVERBOUND, 11));
        assertNotEquals(key, new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, 12));
        assertNotEquals(key, new RouteKey(764, ProtocolState.PLAY, Direction.CLIENTBOUND, 11));
        assertThrows(IllegalArgumentException.class,
                () -> new RouteKey(763, ProtocolState.PLAY, Direction.CLIENTBOUND, -1));
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
