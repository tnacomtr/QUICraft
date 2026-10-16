// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Schema2RoutingTest {
    private static final String STATE_LIMIT = "{\"maxServerboundRecord\":100,"
            + "\"maxServerboundRealtimeRecord\":510,\"maxDecodedFootprint\":300,"
            + "\"ordinaryReadAllowance\":5619,\"decodeFootprintBound\":\"primitive.raw_copies.v1\"}";
    /** serverboundLimits with exactly these states; every supported state is required (13.7). */
    private static String limits(String... states) {
        StringBuilder out = new StringBuilder("{");
        for (String state : states) {
            if (out.length() > 1) out.append(',');
            out.append('"').append(state).append("\":").append(STATE_LIMIT);
        }
        return out.append('}').toString();
    }
    private static final String LIMITS = limits("handshaking", "status", "login", "configuration", "play");

    private static String route(String direction, String lane, String dependency, String position) {
        return "{\"state\":\"play\",\"direction\":\"" + direction + "\",\"packet_id\":1,\"lane\":\""
                + lane + "\",\"dependency\":\"" + dependency + "\",\"position\":" + position
                + ",\"epoch_change\":false" + (direction.equals("serverbound") && lane.equals("control")
                ? ",\"ordinarySmall\":true" : "") + "}";
    }

    private static String table(String route) {
        return "{\"schema\":2,\"protocol\":12345,\"default\":\"global\",\"extractors\":{"
                + "\"one\":\"primitive.chunk_xz.v1\",\"many\":\"primitive.chunk_list.v1\","
                + "\"join\":\"primitive.join.v1\"},\"serverboundLimits\":" + LIMITS + ",\"routes\":[" + route + "]}";
    }

    @Test void validatesEveryDirectionLaneDependencyTuple() {
        for (String direction : new String[] {"clientbound", "serverbound"}) {
            for (String lane : new String[] {"control", "realtime", "chunk", "world", "unknown"}) {
                for (Dependency dep : Dependency.values()) {
                    boolean control = lane.equals("control");
                    boolean chunk = lane.equals("chunk");
                    boolean join = dep == Dependency.ACK_JOIN || dep == Dependency.BATCH_JOIN;
                    boolean valid = control ? dep == Dependency.GLOBAL || dep == Dependency.VIEW
                            || dep == Dependency.CONTROL || dep == Dependency.ORDINARY
                            || (direction.equals("serverbound") && dep == Dependency.INTERACTION)
                            : (lane.equals("realtime") && dep == Dependency.LANE)
                            || (chunk && direction.equals("clientbound") && (dep == Dependency.LANE || join));
                    String json = table(route(direction, lane, dep.wireName(), chunk ? (join ? "\"join\"" : "\"one\"") : "null"));
                    if (valid) assertDoesNotThrow(() -> RoutingTableLoader.parse(json), json);
                    else assertThrows(RoutingTableException.class, () -> RoutingTableLoader.parse(json), json);
                }
            }
        }
    }

    @Test void preservesMetadataDefaultAndExactByteDigest() throws Exception {
        String json = table(route("serverbound", "control", "ordinary", "null"));
        RoutingTable table = RoutingTableLoader.parse(json);
        assertEquals(2, table.schema());
        RouteV2 route = table.routesV2().values().iterator().next();
        assertEquals(Dependency.ORDINARY, route.dependency);
        assertTrue(route.ordinarySmall);
        assertEquals(7667, route.serverboundLimits.protectedReadArea);
        assertEquals(Dependency.GLOBAL, table.routeV2For(new RouteKey(12345, ProtocolState.PLAY,
                Direction.CLIENTBOUND, 99)).dependency);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)), table.digest());
        assertFalse(table.digestHex().equals(RoutingTableLoader.parse(json + " ").digestHex()));
        assertFalse(table.digestHex().equals(RoutingTableLoader.parse(json.replace("primitive.chunk_xz.v1", "test.chunk_xz.v1")).digestHex()));
        assertThrows(UnsupportedOperationException.class, () -> table.routesV2().clear());
    }

    @Test void rejectsInvalidPositionAndEpochCombinations() {
        for (String position : new String[] {"null", "\"many\"", "\"join\"", "\"absent\"", "4"})
            reject(table(route("clientbound", "chunk", "lane", position)));
        for (String position : new String[] {"null", "\"one\"", "\"many\""})
            reject(table(route("clientbound", "chunk", "ack_join", position)));
        for (String lane : new String[] {"control", "realtime"})
            reject(table(route("clientbound", lane, lane.equals("control") ? "ordinary" : "lane", "\"one\"")));
        assertDoesNotThrow(() -> RoutingTableLoader.parse(table(route("clientbound", "control", "global", "\"many\""))));
        reject(table(route("clientbound", "control", "ordinary", "\"many\"")));
        String global = table(route("clientbound", "control", "global", "null"));
        assertDoesNotThrow(() -> RoutingTableLoader.parse(global.replace("false", "true")));
        reject(table(route("clientbound", "realtime", "lane", "null")).replace("false", "true"));
        reject(table(route("serverbound", "control", "global", "null")).replace("epoch_change\":false", "epoch_change\":true"));
        reject(table(route("clientbound", "control", "ordinary", "null")).replace("false", "true"));
        for (String state : new String[] {"login", "configuration", "status", "handshaking"}) {
            reject(table(route("clientbound", "realtime", "lane", "null")).replace("\"state\":\"play\"", "\"state\":\"" + state + "\""));
            reject(table(route("clientbound", "control", "view", "null")).replace("\"state\":\"play\"", "\"state\":\"" + state + "\""));
        }
    }

    @Test void rejectsMissingUnknownDuplicateAndWronglyTypedFields() {
        String json = table(route("clientbound", "control", "global", "null"));
        for (String fragment : new String[] {"\"schema\":2,", "\"protocol\":12345,", "\"default\":\"global\",",
                "\"state\":\"play\",", "\"direction\":\"clientbound\",", "\"packet_id\":1,",
                "\"lane\":\"control\",", "\"dependency\":\"global\",", "\"position\":null,"}) {
            reject(json.replace(fragment, ""));
            reject(json.replace(fragment, fragment + fragment));
        }
        for (String fragment : new String[] {"\"schema\":2", "\"state\":\"play\"", "\"maxServerboundRecord\":100"})
            reject(json.replace(fragment, fragment + ",\"unknown\":0"));
        for (String replacement : new String[] {"null", "1", "\"false\""})
            reject(json.replace("\"epoch_change\":false", "\"epoch_change\":" + replacement));
        reject(json.replace("\"epoch_change\":false", "\"ordinarySmall\":false"));
        reject(json.replace("\"epoch_change\":false", "\"epoch_change\":false,\"ordinarySmall\":true"));
        reject(json.replace("\"global\"", "\"control\""));
        reject(json.replace("\"protocol\":12345", "\"protocol\":0"));
        reject(json.replace("\"schema\":2", "\"schema\":3"));
        reject(json.replace("\"packet_id\":1", "\"packet_id\":1.0"));
        reject(json.replace("\"packet_id\":1", "\"packet_id\":-1"));
        reject(json.replace("primitive.chunk_xz.v1", "unknown.v1"));
        reject(json.replace("\"one\":\"primitive.chunk_xz.v1\"", "\"one\":4"));
        reject(json.replace("\"one\":\"primitive.chunk_xz.v1\"", "\"one\":\"primitive.chunk_xz.v1\",\"one\":\"primitive.chunk_xz.v1\""));
        reject(table(route("clientbound", "control", "global", "null") + "," + route("clientbound", "realtime", "lane", "null")));
        assertThrows(RoutingTableException.class, () -> RoutingTableLoader.parse(
                table(route("clientbound", "control", "ordinary", "null")), Collections.singleton(
                        new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 1))));
    }

    @Test void validatesLimitsAccountingAndSmallGuarantees() {
        String json = table(route("serverbound", "control", "ordinary", "null"));
        for (String field : new String[] {"maxServerboundRecord", "maxServerboundRealtimeRecord",
                "maxDecodedFootprint", "ordinaryReadAllowance"}) {
            for (String invalid : new String[] {"0", "-1", "1.0", "true", "2147483648"})
                reject(json.replaceAll("\\\"" + field + "\\\":[0-9]+", "\"" + field + "\":" + invalid));
            reject(json.replaceAll("\\\"" + field + "\\\":[0-9]+,", ""));
        }
        reject(json.replace("\"maxServerboundRecord\":100", "\"maxServerboundRecord\":2097209"));
        reject(json.replace("\"maxServerboundRealtimeRecord\":510", "\"maxServerboundRealtimeRecord\":511"));
        reject(json.replace("\"maxDecodedFootprint\":300", "\"maxDecodedFootprint\":8388609"));
        reject(json.replace("\"ordinaryReadAllowance\":5619", "\"ordinaryReadAllowance\":5620"));
        reject(json.replace("\"ordinaryReadAllowance\":5619", "\"ordinaryReadAllowance\":159"));
        reject(json.replace("primitive.raw_copies.v1", "unreviewed.v1"));
        reject(json.replace("\"decodeFootprintBound\":\"primitive.raw_copies.v1\"", "\"decodeFootprintBound\":null"));
        reject(json.replace(LIMITS, "{}"));
        reject(json.replace("\"play\":{", "\"unknown\":{"));
        reject(json.replace("\"maxDecodedFootprint\":300", "\"maxDecodedFootprint\":8388608"));
        assertDoesNotThrow(() -> RoutingTableLoader.parse(json.replace("\"maxDecodedFootprint\":300", "\"maxDecodedFootprint\":8388608").replace("\"ordinarySmall\":true", "\"ordinarySmall\":false")));
        reject(json.replace(",\"ordinarySmall\":true", ""));
        reject(json.replace("\"ordinarySmall\":true", "\"ordinarySmall\":1"));
    }

    @Test void requiresAllContainersAndEnforcesNonPlayAndMandatoryFenceRules() {
        String route = route("clientbound", "control", "global", "null");
        String json = table(route);
        for (String container : new String[] {"extractors", "routes", "serverboundLimits"}) {
            reject(json.replace("\"" + container + "\":", "\"absent\":"));
        }
        reject(json.replace("\"routes\":[" + route + "]", "\"routes\":{}"));
        reject(json.replace("\"serverboundLimits\":" + LIMITS, "\"serverboundLimits\":[]"));
        reject(json.replace("\"epoch_change\":false", "\"epoch_change\":false,\"epoch_change\":false"));
        reject(json.replace("\"maxServerboundRecord\":100", "\"maxServerboundRecord\":100,\"maxServerboundRecord\":100"));
        reject(json.replace("\"state\":\"play\"", "\"state\":\"unknown\""));
        reject(json.replace("\"direction\":\"clientbound\"", "\"direction\":\"unknown\""));
        reject(json.replace("\"dependency\":\"global\"", "\"dependency\":null"));
        String loginRoute = route.replace("\"state\":\"play\"", "\"state\":\"login\"");
        assertDoesNotThrow(() -> RoutingTableLoader.parse(table(loginRoute)));
        String serverLogin = route("serverbound", "control", "global", "null")
                .replace("\"state\":\"play\"", "\"state\":\"login\"");
        assertDoesNotThrow(() -> RoutingTableLoader.parse(table(serverLogin)));
        reject(table(serverLogin).replace(LIMITS, limits("handshaking", "status", "configuration", "play")));
        assertDoesNotThrow(() -> RoutingTableLoader.parse(json, Collections.singleton(
                new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 1))));
        for (String lane : new String[] {"realtime", "chunk"})
            assertThrows(RoutingTableException.class, () -> RoutingTableLoader.parse(table(
                    route("clientbound", lane, "lane", lane.equals("chunk") ? "\"one\"" : "null")),
                    Collections.singleton(new RouteKey(12345, ProtocolState.PLAY, Direction.CLIENTBOUND, 1))));
    }

    @Test void rejectsAnyStateWithoutLimitsEvenWithoutExplicitRoutes() {
        String playRoute = route("clientbound", "control", "global", "null");
        String json = table(playRoute);
        for (ProtocolState state : ProtocolState.values()) {
            String without = limits(java.util.Arrays.stream(ProtocolState.values())
                    .filter(other -> other != state).map(ProtocolState::wireName).toArray(String[]::new));
            reject(json.replace(LIMITS, without));
        }
        assertDoesNotThrow(() -> RoutingTableLoader.parse(json));
    }

    @Test void defaultRoutesForUnknownIdsCarryTheirStateAccounting() {
        RoutingTable table = RoutingTableLoader.parse(table(route("serverbound", "control", "ordinary", "null")));
        for (ProtocolState state : ProtocolState.values()) {
            for (Direction direction : Direction.values()) {
                RouteV2 route = table.routeV2For(new RouteKey(12345, state, direction, 999));
                assertEquals(RouteV2.StreamLane.CONTROL, route.lane, state + " " + direction);
                assertEquals(Dependency.GLOBAL, route.dependency, state + " " + direction);
                assertNull(route.ordinarySmall, state + " " + direction);
                assertEquals("primitive.raw_copies.v1", route.decodeFootprintBound, state + " " + direction);
                assertSame(table.serverboundLimits().get(state), route.serverboundLimits, state + " " + direction);
                assertNotNull(route.serverboundLimits, state + " " + direction);
            }
        }
    }

    private static void reject(String json) {
        assertThrows(RoutingTableException.class, () -> RoutingTableLoader.parse(json), json);
    }
}
