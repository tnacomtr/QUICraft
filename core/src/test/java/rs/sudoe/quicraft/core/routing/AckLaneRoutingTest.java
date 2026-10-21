// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The schema rules of the ACK lane and the {@code trailing} and {@code ack} dependencies (13.7, 13.21). */
class AckLaneRoutingTest {
    private static final String LIMIT = "{\"maxServerboundRecord\":100,\"maxServerboundRealtimeRecord\":510,"
            + "\"maxDecodedFootprint\":300,\"ordinaryReadAllowance\":5619,"
            + "\"decodeFootprintBound\":\"primitive.raw_copies.v1\"}";

    private static String route(String state, String direction, int id, String lane, String dependency,
            String position, boolean epoch) {
        return "{\"state\":\"" + state + "\",\"direction\":\"" + direction + "\",\"packet_id\":" + id
                + ",\"lane\":\"" + lane + "\",\"dependency\":\"" + dependency + "\",\"position\":" + position
                + ",\"epoch_change\":" + epoch + (direction.equals("serverbound") && lane.equals("control")
                ? ",\"ordinarySmall\":true" : "") + "}";
    }

    private static String table(String route) {
        StringBuilder limits = new StringBuilder("{");
        for (String state : new String[] {"handshaking", "status", "login", "configuration", "play"}) {
            if (limits.length() > 1) limits.append(',');
            limits.append('"').append(state).append("\":").append(LIMIT);
        }
        limits.append('}');
        return "{\"schema\":2,\"protocol\":12345,\"default\":\"global\",\"extractors\":{"
                + "\"one\":\"primitive.chunk_xz.v1\"},\"serverboundLimits\":" + limits + ",\"routes\":[" + route + "]}";
    }

    private static void reject(String json) {
        assertThrows(RoutingTableException.class, () -> RoutingTableLoader.parse(json), json);
    }

    @Test
    void anAckLaneRouteForPackets4And11IsLegalAndCarriesNoPosition() {
        for (int id : new int[] {4, 11}) {
            RoutingTable parsed = RoutingTableLoader.parse(table(
                    route("play", "clientbound", id, "ack", "trailing", "null", false)));
            RouteV2 route = parsed.routesV2().values().iterator().next();
            assertEquals(RouteV2.StreamLane.ACK, route.lane);
            assertEquals(Dependency.TRAILING, route.dependency);
        }
    }

    @Test
    void theAckLaneIsOnlyForClientboundPlayPackets4And11() {
        reject(table(route("play", "clientbound", 5, "ack", "trailing", "null", false)));
        reject(table(route("play", "clientbound", 3, "ack", "trailing", "null", false)));
        reject(table(route("play", "serverbound", 4, "ack", "trailing", "null", false)));
        reject(table(route("configuration", "clientbound", 4, "ack", "trailing", "null", false)));
        reject(table(route("play", "clientbound", 4, "ack", "trailing", "\"one\"", false)));
        reject(table(route("play", "clientbound", 4, "ack", "trailing", "null", true)));
    }

    @Test
    void trailingIsLegalOnlyOnTheAckLane() {
        reject(table(route("play", "clientbound", 4, "ack", "global", "null", false)));
        reject(table(route("play", "clientbound", 4, "ack", "lane", "null", false)));
        reject(table(route("play", "clientbound", 4, "ack", "ack", "null", false)));
        reject(table(route("play", "clientbound", 4, "control", "trailing", "null", false)));
        reject(table(route("play", "clientbound", 4, "realtime", "trailing", "null", false)));
        reject(table(route("play", "clientbound", 4, "chunk", "trailing", "\"one\"", false)));
    }

    @Test
    void theAckDependencyIsLegalOnlyOnTheChunkLane() {
        assertDoesNotThrow(() -> RoutingTableLoader.parse(table(
                route("play", "clientbound", 45, "chunk", "ack", "\"one\"", false))));
        reject(table(route("play", "clientbound", 45, "chunk", "ack", "null", false)));
        reject(table(route("play", "clientbound", 45, "control", "ack", "null", false)));
        reject(table(route("play", "clientbound", 45, "realtime", "ack", "null", false)));
        reject(table(route("play", "clientbound", 45, "ack", "ack", "null", false)));
        reject(table(route("play", "serverbound", 45, "chunk", "ack", "\"one\"", false)));
    }
}
