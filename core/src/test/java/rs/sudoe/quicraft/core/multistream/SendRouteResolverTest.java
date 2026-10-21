// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RouteV2;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;

/** Send-side fence resolution against a schema-2 table (13.5-13.9, 13.6). */
final class SendRouteResolverTest {

    private static final int PROTOCOL = 12345;
    private static final ProtocolState STATE = ProtocolState.PLAY;
    private static final byte[] BYTES = {1, 2, 3};

    private static final int PID_GLOBAL = 1;
    private static final int PID_FENCE = 2;
    private static final int PID_LANE = 3;
    private static final int PID_VIEW = 4;
    private static final int PID_CONTROL = 5;
    private static final int PID_ORDINARY = 6;
    private static final int PID_INTERACTION = 7;
    private static final int PID_REALTIME = 8;
    private static final int PID_CHUNK = 9;
    private static final int PID_CHUNK_NO_EXTRACTOR = 10;
    private static final int PID_CHUNK_BAD_EXTRACTOR = 11;
    private static final int PID_CHUNK_NULL_POSITION = 12;
    private static final int PID_ACK_JOIN = 13;
    private static final int PID_BATCH_JOIN = 14;
    private static final int PID_EPOCH = 15;

    private static final Map<Integer, RouteV2> ROUTES = new HashMap<Integer, RouteV2>();
    private static final Map<String, Extractor> EXTRACTORS = new HashMap<String, Extractor>();

    static {
        route(PID_GLOBAL, RouteV2.StreamLane.CONTROL, Dependency.GLOBAL, null, false);
        route(PID_FENCE, RouteV2.StreamLane.CONTROL, Dependency.FENCE, null, false);
        route(PID_LANE, RouteV2.StreamLane.CONTROL, Dependency.LANE, null, false);
        route(PID_VIEW, RouteV2.StreamLane.CONTROL, Dependency.VIEW, null, false);
        route(PID_CONTROL, RouteV2.StreamLane.CONTROL, Dependency.CONTROL, null, false);
        route(PID_ORDINARY, RouteV2.StreamLane.CONTROL, Dependency.ORDINARY, null, false);
        route(PID_INTERACTION, RouteV2.StreamLane.CONTROL, Dependency.INTERACTION, null, false);
        route(PID_REALTIME, RouteV2.StreamLane.REALTIME, Dependency.ORDINARY, null, false);
        route(PID_CHUNK, RouteV2.StreamLane.CHUNK, Dependency.ORDINARY, "chunk", false);
        route(PID_CHUNK_NO_EXTRACTOR, RouteV2.StreamLane.CHUNK, Dependency.ORDINARY, "missing", false);
        route(PID_CHUNK_BAD_EXTRACTOR, RouteV2.StreamLane.CHUNK, Dependency.ORDINARY, "bad", false);
        route(PID_CHUNK_NULL_POSITION, RouteV2.StreamLane.CHUNK, Dependency.ORDINARY, "nil", false);
        route(PID_ACK_JOIN, RouteV2.StreamLane.CHUNK, Dependency.ACK_JOIN, "chunk", false);
        route(PID_BATCH_JOIN, RouteV2.StreamLane.CHUNK, Dependency.BATCH_JOIN, "chunk", false);
        route(PID_EPOCH, RouteV2.StreamLane.CONTROL, Dependency.GLOBAL, null, true);
        EXTRACTORS.put("chunk", bytes -> new Extractor.Position(3, -4));
        EXTRACTORS.put("bad", bytes -> {
            throw new IllegalStateException("malformed");
        });
        EXTRACTORS.put("nil", bytes -> null);
    }

    private static void route(int pid, RouteV2.StreamLane lane, Dependency dep, String extractor,
            boolean epoch) {
        ROUTES.put(pid, new RouteV2(lane, dep, extractor, null, epoch, null, null, null));
    }

    private static SessionReceiveDriver.RouteSource table() {
        return key -> key.protocolNumber() == PROTOCOL && key.direction() == Direction.SERVERBOUND
                ? ROUTES.get(key.packetId()) : null;
    }

    private static SendRouteResolver.Resolved resolve(int pid, byte[] uncompressed) {
        return SendRouteResolver.resolve(table(), EXTRACTORS, PROTOCOL, Direction.SERVERBOUND, STATE, pid,
                uncompressed);
    }

    @Test
    void controlRoutesMapToTheirFences() {
        assertEquals(StreamSequencer.Fence.GLOBAL, resolve(PID_GLOBAL, null).fence());
        assertEquals(StreamSequencer.Fence.GLOBAL, resolve(PID_FENCE, null).fence());
        assertEquals(StreamSequencer.Fence.GLOBAL, resolve(PID_LANE, null).fence());
        assertEquals(StreamSequencer.Fence.VIEW, resolve(PID_VIEW, null).fence());
        assertEquals(StreamSequencer.Fence.CONTROL_FENCE, resolve(PID_CONTROL, null).fence());
        assertEquals(StreamSequencer.Fence.ORDINARY_CONTROL, resolve(PID_ORDINARY, null).fence());
        assertFalse(resolve(PID_ORDINARY, null).interaction());
    }

    @Test
    void interactionIsOrdinaryControlWithTheFlagSet() {
        SendRouteResolver.Resolved r = resolve(PID_INTERACTION, null);
        assertEquals(StreamSequencer.Fence.ORDINARY_CONTROL, r.fence());
        assertTrue(r.interaction());
        assertFalse(r.failed());
    }

    @Test
    void realtimeLaneGoesToRealtimeWithoutExtraction() {
        assertEquals(StreamSequencer.Fence.REALTIME, resolve(PID_REALTIME, null).fence());
    }

    @Test
    void chunkRouteUsesTheExtractedPosition() {
        SendRouteResolver.Resolved r = resolve(PID_CHUNK, BYTES);
        assertEquals(StreamSequencer.Fence.CHUNK, r.fence());
        assertEquals(3, r.chunkX());
        assertEquals(-4, r.chunkZ());
        assertFalse(r.failed());
    }

    /** The shipped table enables only the reviewed chunk-install route. */
    @Test
    void shipped775ChunkInstallUsesReviewedChunkRouteAndNoOtherRouteDoes() throws Exception {
        RoutingTable shipped = RoutingTableLoader.loadResource(775);
        RouteKey key = new RouteKey(775, ProtocolState.PLAY, Direction.CLIENTBOUND, 45);
        RouteV2 route = shipped.routeV2For(key); // minecraft:level_chunk_with_light
        assertEquals(RouteV2.StreamLane.CHUNK, route.lane);
        assertEquals(Dependency.LANE, route.dependency);
        assertEquals("mc775.chunk_xz.v1", route.extractor);
        assertEquals("chunk_xz", route.position);
        assertFalse(route.epochChange);
        Map<String, Extractor> decoders = Collections.<String, Extractor>singletonMap("mc775.chunk_xz.v1",
                ExtractorRegistry.productionDecoder("mc775.chunk_xz.v1"));
        byte[] prefix = {45, -1, -1, -1, -3, 0, 0, 0, 17};
        SendRouteResolver.Resolved resolved = SendRouteResolver.resolve(shipped::routeV2For,
                decoders, 775, Direction.CLIENTBOUND, ProtocolState.PLAY, 45, prefix);
        assertFalse(resolved.failed(), resolved.failure());
        assertEquals(StreamSequencer.Fence.CHUNK, resolved.fence());
        assertEquals(-3, resolved.chunkX());
        assertEquals(17, resolved.chunkZ());
        assertEquals(4, shipped.routesV2().values().stream().filter(r -> r.lane == RouteV2.StreamLane.CHUNK).count());
    }

    @Test
    void shipped775ChunkInstallCoordinatesMapToTheirExtractorShard() throws Exception {
        RoutingTable shipped = RoutingTableLoader.loadResource(775);
        Map<String, Extractor> decoders = Collections.<String, Extractor>singletonMap("mc775.chunk_xz.v1",
                ExtractorRegistry.productionDecoder("mc775.chunk_xz.v1"));
        byte[][] packets = {{45, 0, 0, 0, 2, 0, 0, 0, 3}, {45, 0, 0, 0, 9, 0, 0, 0, 11}};
        for (int i = 0; i < packets.length; i++) {
            byte[] packet = packets[i];
            SendRouteResolver.Resolved resolved = SendRouteResolver.resolve(shipped::routeV2For, decoders,
                    775, Direction.CLIENTBOUND, ProtocolState.PLAY, 45, packet);
            assertFalse(resolved.failed(), resolved.failure());
            int expectedX = i == 0 ? 2 : 9;
            int expectedZ = i == 0 ? 3 : 11;
            assertEquals(expectedX, resolved.chunkX());
            assertEquals(expectedZ, resolved.chunkZ());
            assertEquals(ShardHash.shardOf(expectedX, expectedZ, 0, 5),
                    ShardHash.shardOf(resolved.chunkX(), resolved.chunkZ(), 0, 5));
        }
        SendRouteResolver.Resolved first = SendRouteResolver.resolve(shipped::routeV2For, decoders,
                775, Direction.CLIENTBOUND, ProtocolState.PLAY, 45, packets[0]);
        SendRouteResolver.Resolved second = SendRouteResolver.resolve(shipped::routeV2For, decoders,
                775, Direction.CLIENTBOUND, ProtocolState.PLAY, 45, packets[1]);
        assertNotEquals(ShardHash.shardOf(first.chunkX(), first.chunkZ(), 0, 5),
                ShardHash.shardOf(second.chunkX(), second.chunkZ(), 0, 5));
    }

    @Test
    void unknownPacketIdIsAGlobalFence() {
        assertEquals(StreamSequencer.Fence.GLOBAL, resolve(99, null).fence());
        assertFalse(resolve(99, null).failed());
    }

    @Test
    void chunkRouteWithoutUncompressedBytesFailsClosed() {
        SendRouteResolver.Resolved r = resolve(PID_CHUNK, null);
        assertTrue(r.failed());
        assertNull(r.fence());
    }

    @Test
    void chunkRouteWithoutExtractorFailsClosed() {
        assertTrue(resolve(PID_CHUNK_NO_EXTRACTOR, BYTES).failed());
    }

    @Test
    void chunkRouteWhoseExtractorThrowsFailsClosed() {
        SendRouteResolver.Resolved r = resolve(PID_CHUNK_BAD_EXTRACTOR, BYTES);
        assertTrue(r.failed());
        assertNull(r.fence());
    }

    @Test
    void chunkRouteWhoseExtractorReturnsNoPositionFailsClosed() {
        assertTrue(resolve(PID_CHUNK_NULL_POSITION, BYTES).failed());
    }

    @Test
    void joinRoutesFailClosedThroughThePlainSendPath() {
        assertTrue(resolve(PID_ACK_JOIN, BYTES).failed());
        assertTrue(resolve(PID_BATCH_JOIN, BYTES).failed());
    }

    @Test
    void epochChangeFailsClosedThroughThePlainSendPath() {
        assertTrue(resolve(PID_EPOCH, null).failed());
    }
    private static SendRouteResolver.BundleScopes bundle(int... pids) {
        return SendRouteResolver.bundleScopes(table(), PROTOCOL, Direction.SERVERBOUND, STATE, pids);
    }

    private static StreamSequencer.Fence bundleScope(int... pids) {
        SendRouteResolver.BundleScopes scopes = bundle(pids);
        assertFalse(scopes.failed(), String.valueOf(scopes.failure()));
        return StreamSequencer.bundleScope(scopes.scopes());
    }

    /** The 13.15 strongest-member mapping, without position extraction. */
    @Test
    void bundleMembersMapToTheStrongestScope() {
        assertEquals(StreamSequencer.Fence.CONTROL_FENCE, bundleScope(PID_ORDINARY), "the floor is control");
        assertEquals(StreamSequencer.Fence.CONTROL_FENCE, bundleScope(PID_CONTROL, PID_ORDINARY));
        assertEquals(StreamSequencer.Fence.VIEW, bundleScope(PID_VIEW, PID_CONTROL));
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_GLOBAL, PID_VIEW));
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_FENCE));
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_LANE), "a LANE dependency promotes");
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_ORDINARY, PID_REALTIME), "a REALTIME member");
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_CONTROL, 999), "an unknown member");
        // CHUNK members need no position here, even when their extractor would fail or is missing.
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_ORDINARY, PID_CHUNK));
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_CHUNK_BAD_EXTRACTOR));
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_CHUNK_NO_EXTRACTOR));
        // INTERACTION is above control; it and VIEW are incomparable, so both together are global.
        assertEquals(StreamSequencer.Fence.CONTROL_FENCE, bundleScope(PID_INTERACTION, PID_ORDINARY));
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_INTERACTION, PID_VIEW));
        assertEquals(StreamSequencer.Fence.GLOBAL, bundleScope(PID_VIEW, PID_ORDINARY, PID_INTERACTION));
    }

    @Test
    void joinAndEpochBundleMembersFailClosed() {
        assertTrue(bundle(PID_ORDINARY, PID_ACK_JOIN).failed());
        assertTrue(bundle(PID_BATCH_JOIN).failed());
        assertTrue(bundle(PID_CONTROL, PID_EPOCH).failed());
        assertNull(bundle(PID_EPOCH).scopes());
    }
}
