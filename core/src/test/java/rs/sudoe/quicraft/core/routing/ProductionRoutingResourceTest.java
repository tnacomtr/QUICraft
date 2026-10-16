// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The routing resource core ships in its own jar (docs/protocol.md 13.6, 13.7): the placeholder for
 * the 26.x protocol family, which routes <em>every</em> packet to CONTROL until the real table
 * lands. Its digest R goes into the binding transcript (12.7) and into the advertisement (13.1), so
 * its exact bytes must stay identical on both peers: the hard-coded digest below is what makes any
 * change a deliberate one.
 */
class ProductionRoutingResourceTest {
    /** The 26.1.x protocol number (docs/version-matrix.md) the shipped resource is keyed by. */
    private static final int PROTOCOL = 775;

    /**
     * R, SHA-256 of {@code protocol-775.json}'s exact bytes, whitespace included (13.7). A real
     * 26.x table replaces this resource, and then replaces this constant in the same commit.
     */
    private static final String DIGEST =
            "67e21ba76079be3b939d2ce62b6cbdbb8d0e0079755cd84b0bc917e454657d99";

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

    @Test
    void everyLookupRoutesToControl() {
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).get();

        assertTrue(table.routes().isEmpty(),
                "the placeholder declares the default lane and no route, so nothing can route elsewhere");
        for (ProtocolState state : ProtocolState.values()) {
            for (Direction direction : Direction.values()) {
                for (int packetId : new int[] {0, 1, 2, 0x7F, 0x80, 1024, Integer.MAX_VALUE}) {
                    assertEquals(Lane.CONTROL, table.laneFor(state, direction, packetId),
                            "13.6: a missing entry is CONTROL, never an inferred category: " + state + "/"
                                    + direction + "/" + packetId);
                }
            }
        }
        Random random = new Random(775);
        for (int i = 0; i < 1000; i++) {
            assertEquals(Lane.CONTROL,
                    table.laneFor(ProtocolState.values()[random.nextInt(ProtocolState.values().length)],
                            Direction.values()[random.nextInt(Direction.values().length)],
                            random.nextInt(Integer.MAX_VALUE)),
                    "no packet id may route anywhere but CONTROL");
        }
    }
}
