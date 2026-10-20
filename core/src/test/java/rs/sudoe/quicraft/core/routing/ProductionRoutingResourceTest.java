// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The routing resource core ships in its own jar (docs/protocol.md 13.6, 13.7): the 26.1.2 table.
 * Its digest R goes into the binding transcript (12.7) and into the advertisement (13.1), so its
 * exact bytes must stay identical on both peers: the hard-coded digest below is what makes any
 * change a deliberate one.
 */
class ProductionRoutingResourceTest {
    /** The 26.1.x protocol number (docs/version-matrix.md) the shipped resource is keyed by. */
    private static final int PROTOCOL = 775;

    /**
     * R, SHA-256 of {@code protocol-775.json}'s exact bytes, whitespace included (13.7). Any edit to
     * the table replaces this constant in the same commit.
     */
    private static final String DIGEST =
            "177f7d87948dbaa201dda848f9b852675785dab1c27f9318a705327659bbd2c1";

    @Test
    void theShippedResourceLoadsThroughTheRegistry() {
        Optional<RoutingTable> found = RoutingRegistry.forProtocol(PROTOCOL);

        assertTrue(found.isPresent(), "a production routing resource must ship in core's main resources");
        assertEquals(PROTOCOL, found.get().protocolNumber());
    }

    @Test
    void theDigestIsTheExactResourceDigestAndIsStable() throws Exception {
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).get();

        assertEquals(DIGEST, table.digestHex(), "R is over the exact bytes (13.7): update both peers");
        assertEquals(64, table.digestHex().length());
        assertEquals(RoutingTableLoader.loadResource(PROTOCOL).digestHex(), table.digestHex(),
                "the registry and the loader must agree on the same bytes");
        assertEquals(DIGEST, RoutingRegistry.forProtocol(PROTOCOL).get().digestHex(),
                "and it is stable across loads");
    }

    /**
     * Schema 2 (13.7): the table lists one explicit route per live play id, so nothing relies on the
     * default. An id it does not list has no route, and the sender and receiver treat that as the
     * 13.5/13.6 default, CONTROL/global, never an inferred category.
     */
    @Test
    void everyUnlistedLookupHasNoRouteAndDefaultsToGlobalControl() {
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).get();

        assertEquals(210, table.routesV2().size(), "one explicit route per live play id (141 clientbound + 69 serverbound)");
        Random random = new Random(775);
        for (int i = 0; i < 1000; i++) {
            ProtocolState state = ProtocolState.values()[random.nextInt(ProtocolState.values().length)];
            Direction direction = Direction.values()[random.nextInt(Direction.values().length)];
            int packetId = 141 + random.nextInt(Integer.MAX_VALUE - 141);
            RouteKey key = new RouteKey(PROTOCOL, state, direction, packetId);
            assertEquals(null, table.routesV2().get(key), "no unlisted id has an explicit route: " + key);
            RouteV2 defaulted = table.routeV2For(key);
            assertEquals(RouteV2.StreamLane.CONTROL, defaulted.lane, key.toString());
            assertEquals(Dependency.GLOBAL, defaulted.dependency, key.toString());
            assertEquals(null, defaulted.position, key.toString());
            assertEquals(false, defaulted.epochChange, key.toString());
        }
        for (Map.Entry<RouteKey, RouteV2> entry : table.routesV2().entrySet()) {
            RouteV2 route = entry.getValue();
            if (entry.getKey().direction() == Direction.SERVERBOUND) {
                assertEquals(RouteV2.StreamLane.CONTROL, route.lane,
                        "serverbound dual-lane is deferred (CP1): " + entry.getKey());
            }
        }
    }
}
