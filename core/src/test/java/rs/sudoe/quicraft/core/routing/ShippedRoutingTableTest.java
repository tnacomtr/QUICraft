// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /** 13.21: the CHUNK routes that add the {@code ack} dependency (6, 37, 45); 8 and 84 stay {@code lane}. */
    private static final Set<Integer> CHUNK_ACK =
            new HashSet<Integer>(Arrays.asList(45, 6, 37));

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
    void boundExtractorsAreTheReviewedChunkListChunkInstallAndForget() throws Exception {
        Faults.set();
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("chunk_list", "mc775.chunk_list.v1");
        expected.put("chunk_xz", "mc775.chunk_xz.v1");
        expected.put("chunk_pos_long", "mc775.chunk_pos_long.v1");
        expected.put("block_position", "mc775.block_position.v1");
        expected.put("section_position", "mc775.section_position.v1");
        assertEquals(expected, RoutingTableLoader.loadResource(PROTOCOL).extractors());
    }

    @Test
    void everyChunkRouteUsesTheChunkLaneWithItsReviewedDependency() throws Exception {
        Faults.set();
        int count = 0;
        for (Map.Entry<RouteKey, RouteV2> entry : RoutingTableLoader.loadResource(PROTOCOL).routesV2().entrySet()) {
            RouteV2 route = entry.getValue();
            if (route.lane == RouteV2.StreamLane.CHUNK) {
                count++;
                assertEquals(CHUNK_ACK.contains(entry.getKey().packetId()) ? Dependency.ACK : Dependency.LANE,
                        route.dependency, "chunk route dependency for " + entry.getKey());
                assertTrue(route.extractor.equals("mc775.chunk_xz.v1")
                        || route.extractor.equals("mc775.chunk_pos_long.v1")
                        || route.extractor.equals("mc775.block_position.v1")
                        || route.extractor.equals("mc775.section_position.v1"),
                        "chunk route extractor: " + route.extractor);
                assertTrue(route.position.equals("chunk_xz") || route.position.equals("chunk_pos_long")
                        || route.position.equals("block_position")
                        || route.position.equals("section_position"),
                        "chunk route position: " + route.position);
            }
        }
        assertEquals(5, count);
        assertEquals(RouteV2.StreamLane.CHUNK, RoutingTableLoader.loadResource(PROTOCOL)
                .routeV2For(new RouteKey(PROTOCOL, ProtocolState.PLAY, Direction.CLIENTBOUND, 45)).lane);
    }

    /**
     * Every shipped route is one of the reviewed classes: CONTROL/global, the view gate,
     * REALTIME/lane, a CHUNK route (45, 6, 8, 37, 84), the ACK lane (4, 11), ordinary CONTROL, or a
     * control fence. Install, block-entity data and forget carry the 13.21 {@code ack} dependency;
     * the block-state updates 8/84 stay {@code lane}. An ACK-lane record is {@code trailing}: it
     * orders after every earlier record and advances no frontier. Ordinary routes wait for their
     * CONTROL predecessor and G only; control fences additionally order later REALTIME.
     */
    @Test
    void everyShippedRelaxationIsOneOfTheReviewedClasses() throws Exception {
        Faults.set();
        for (Map.Entry<RouteKey, RouteV2> entry : RoutingTableLoader.loadResource(PROTOCOL).routesV2().entrySet()) {
            RouteV2 route = entry.getValue();
            int id = entry.getKey().packetId();
            boolean clientboundPlay = entry.getKey().direction() == Direction.CLIENTBOUND
                    && entry.getKey().state() == ProtocolState.PLAY;
            boolean global = route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.GLOBAL;
            boolean realtime = route.lane == RouteV2.StreamLane.REALTIME && route.dependency == Dependency.LANE;
            boolean view = route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.VIEW;
            boolean ordinary = route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.ORDINARY;
            boolean controlFence = route.lane == RouteV2.StreamLane.CONTROL && route.dependency == Dependency.CONTROL;
            boolean ackLane = clientboundPlay && (id == 4 || id == 11)
                    && route.lane == RouteV2.StreamLane.ACK && route.dependency == Dependency.TRAILING;
            boolean chunkRoute = clientboundPlay
                    && (id == 45 || id == 6 || id == 8 || id == 84 || id == 37)
                    && route.lane == RouteV2.StreamLane.CHUNK
                    && route.dependency == (id == 8 || id == 84 ? Dependency.LANE : Dependency.ACK);
            assertTrue(global || realtime || view || ordinary || controlFence || ackLane || chunkRoute,
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
