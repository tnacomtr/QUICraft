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
    void boundExtractorsAreTheReviewedChunkListAndChunkInstall() throws Exception {
        Faults.set();
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("chunk_list", "mc775.chunk_list.v1");
        expected.put("chunk_xz", "mc775.chunk_xz.v1");
        assertEquals(expected, RoutingTableLoader.loadResource(PROTOCOL).extractors());
    }

    @Test
    void onlyTheReviewedChunkInstallUsesTheChunkLane() throws Exception {
        Faults.set();
        int count = 0;
        for (RouteV2 route : RoutingTableLoader.loadResource(PROTOCOL).routesV2().values()) {
            if (route.lane == RouteV2.StreamLane.CHUNK) {
                count++;
                assertEquals(Dependency.LANE, route.dependency);
                assertEquals("mc775.chunk_xz.v1", route.extractor);
                assertEquals("chunk_xz", route.position);
            }
        }
        assertEquals(1, count);
        assertEquals(RouteV2.StreamLane.CHUNK, RoutingTableLoader.loadResource(PROTOCOL)
                .routeV2For(new RouteKey(PROTOCOL, ProtocolState.PLAY, Direction.CLIENTBOUND, 45)).lane);
    }

    /**
     * Covered block updates and block_changed_ack remain global until 13.19 is wired. The chunk install
     * is safe on CHUNK because later block updates, forget and light remain global, and the global fence
     * orders them after every shard record. Every other route is either global CONTROL (ordered
     * against all records), the view gate, or REALTIME whose send waits for the latest CONTROL fence. The
     * only relaxations are REALTIME against REALTIME, REALTIME against non-global control, and install
     * records against global records; global records wait for every shard record.
     */
    @Test
    void everyShippedRelaxationPreservesGlobalFencesAroundChunkInstall() throws Exception {
        Faults.set();
        for (Map.Entry<RouteKey, RouteV2> entry : RoutingTableLoader.loadResource(PROTOCOL).routesV2().entrySet()) {
            RouteV2 route = entry.getValue();
            boolean global = route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.GLOBAL;
            boolean realtime = route.lane == RouteV2.StreamLane.REALTIME && route.dependency == Dependency.LANE;
            boolean view = route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.VIEW;
            boolean chunkInstall = entry.getKey().direction() == Direction.CLIENTBOUND
                    && entry.getKey().state() == ProtocolState.PLAY && entry.getKey().packetId() == 45
                    && route.lane == RouteV2.StreamLane.CHUNK && route.dependency == Dependency.LANE;
            assertTrue(global || realtime || view || chunkInstall,
                    "unexpected relaxation: " + route.lane + "/" + route.dependency);
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
