// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;

/**
 * The reviewed schema-2 routing table this build ships for Minecraft 26.1.2 (protocol 775,
 * docs/protocol.md 13.7). It parses through the production loader, binds only the production
 * extractor identifiers, carries the spec-derived serverbound limits, and its digest is SHA-256 over
 * the exact resource bytes. Which id goes to which lane is pinned in {@link ShippedRoutingCoverageTest}.
 */
class ShippedRoutingTableTest {
    private static final int PROTOCOL = 775;
    private static final String RESOURCE = RoutingTableLoader.resourcePath(PROTOCOL);

    @Test
    void theShippedTableParsesAsReviewedSchemaTwoAndIsTheRegistryAnswer() throws Exception {
        Faults.set();
        Optional<RoutingTable> table = RoutingRegistry.forProtocol(PROTOCOL);
        assertTrue(table.isPresent(), "26.1.2 ships a table: " + RESOURCE);
        assertEquals(PROTOCOL, table.get().protocolNumber());
        assertEquals(RoutingTableLoader.SCHEMA_VERSION_V2, table.get().schema());
        assertEquals(RoutingTableLoader.loadResource(PROTOCOL).digestHex(), table.get().digestHex());
    }

    @Test
    void theOnlyBoundExtractorIsTheReviewedChunkListForTheGlobalMultiChunkPacket() throws Exception {
        Faults.set();
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("chunk_list", "mc775.chunk_list.v1");
        // The CHUNK-lane layouts (chunk_xz, block_position, section_position) and mc775.join.v1 are
        // reviewed but deliberately unbound: CHUNK routes stay out of the table (CP2, Sol), and the
        // join routes fail closed on the send path (CP1).
        assertEquals(expected, RoutingTableLoader.loadResource(PROTOCOL).extractors());
    }

    @Test
    void theTableHasNoChunkLaneRouteAtAll() throws Exception {
        Faults.set();
        for (RouteV2 route : RoutingTableLoader.loadResource(PROTOCOL).routesV2().values()) {
            assertTrue(route.lane != RouteV2.StreamLane.CHUNK, "CHUNK routes are out of the table (CP2)");
        }
    }

    /**
     * The ACK-ordering argument (CP2, Sol): covered block updates and their block_changed_ack must keep
     * game order, so the table relaxes nothing against them. Every route is either global CONTROL (ordered
     * against all records), the view gate, or REALTIME whose send waits for the latest CONTROL fence. The
     * only relaxations are REALTIME against REALTIME and REALTIME against non-global control, and no
     * CHUNK stream exists in this table, so no covered update can overtake its ack.
     */
    @Test
    void everyShippedRelaxationIsRealtimeOrViewAndNoRouteLeavesGameOrderForChunks() throws Exception {
        Faults.set();
        for (RouteV2 route : RoutingTableLoader.loadResource(PROTOCOL).routesV2().values()) {
            boolean global = route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.GLOBAL;
            boolean realtime = route.lane == RouteV2.StreamLane.REALTIME && route.dependency == Dependency.LANE;
            boolean view = route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.VIEW;
            assertTrue(global || realtime || view, "unexpected relaxation: " + route.lane + "/" + route.dependency);
        }
    }

    @Test
    void theServerboundLimitsAreTheSpecDerivedCaps() throws Exception {
        Faults.set();
        RoutingTable table = RoutingTableLoader.loadResource(PROTOCOL);
        assertEquals(5, table.serverboundLimits().size());
        for (ProtocolState state : ProtocolState.values()) {
            RouteV2.ServerboundLimits limits = table.serverboundLimits().get(state);
            assertEquals(2097208, limits.maxServerboundRecord, state.wireName());
            assertEquals(510, limits.maxServerboundRealtimeRecord, state.wireName());
            assertEquals(8388608, limits.maxDecodedFootprint, state.wireName());
            assertEquals(5619, limits.ordinaryReadAllowance, state.wireName());
            assertEquals("mc775.raw_copies.v1", limits.decodeFootprintBound, state.wireName());
        }
    }

    @Test
    void theDigestIsSha256OverTheExactResourceBytes() throws Exception {
        Faults.set();
        byte[] bytes = readResource(RESOURCE);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] expected = sha256.digest(bytes);
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).get();
        assertArrayEquals(expected, table.digest(), "R is SHA-256 over the exact bytes (13.7)");
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = ShippedRoutingTableTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
