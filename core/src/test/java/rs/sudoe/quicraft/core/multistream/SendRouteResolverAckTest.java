// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;

/** Send-side resolution of the ACK lane and the ack dependency against the synthetic table (13.21). */
class SendRouteResolverAckTest {
    private static final RoutingTable TABLE = AckLaneTables.table();
    private static final Map<String, Extractor> EXTRACTORS = extractorsOf(TABLE);

    private static Map<String, Extractor> extractorsOf(RoutingTable table) {
        Map<String, Extractor> result = new java.util.HashMap<String, Extractor>();
        for (String identifier : table.extractors().values()) {
            result.put(identifier, ExtractorRegistry.decoder(identifier));
        }
        return result;
    }

    private static SendRouteResolver.Resolved resolve(int id, byte[] uncompressed) {
        return SendRouteResolver.resolve(TABLE::routeV2For, EXTRACTORS, 775, Direction.CLIENTBOUND,
                ProtocolState.PLAY, id, uncompressed);
    }

    @Test
    void packets4And11ResolveToTheTrailingAckFenceWithoutAPosition() {
        for (int id : new int[] {AckLaneTables.BLOCK_ACK, AckLaneTables.BATCH_FINISHED}) {
            SendRouteResolver.Resolved resolved = resolve(id, null);
            assertFalse(resolved.failed(), resolved.failure());
            assertEquals(Fence.ACK_TRAILING, resolved.fence());
        }
    }

    @Test
    void installForgetAndBlockEntityDataResolveToTheirAckFencesWithTheirPositions() {
        SendRouteResolver.Resolved install = resolve(AckLaneTables.INSTALL, AckLaneTables.chunkUncompressed(45, 6, -9));
        assertEquals(Fence.CHUNK_INSTALL, install.fence());
        assertEquals(6, install.chunkX());
        assertEquals(-9, install.chunkZ());
        SendRouteResolver.Resolved forget = resolve(AckLaneTables.FORGET, AckLaneTables.chunkUncompressed(37, 1, 2));
        assertEquals(Fence.CHUNK_FORGET, forget.fence());
        SendRouteResolver.Resolved entity = resolve(AckLaneTables.BLOCK_ENTITY, AckLaneTables.chunkUncompressed(6, 3, 4));
        assertEquals(Fence.CHUNK_ACK, entity.fence(), "6 always carries the ack dependency");
        assertEquals(3, entity.chunkX());
        SendRouteResolver.Resolved plain = resolve(AckLaneTables.CHUNK_PLAIN, AckLaneTables.chunkUncompressed(3, 3, 4));
        assertEquals(Fence.CHUNK_ACK, plain.fence(), "only the driver's proven first install skips the ack (13.21)");
    }

    @Test
    void anAckChunkRouteWithoutBytesFailsClosed() {
        assertEquals(true, resolve(AckLaneTables.INSTALL, null).failed());
    }

    @Test
    void aBundleHoldingAnAckResolvesToAScopeThatPromotesTheWholeBundleToGlobal() {
        SendRouteResolver.BundleScopes scopes = SendRouteResolver.bundleScopes(TABLE::routeV2For, 775,
                Direction.CLIENTBOUND, ProtocolState.PLAY,
                new int[] {AckLaneTables.ORDINARY, AckLaneTables.BLOCK_ACK});
        assertFalse(scopes.failed(), scopes.failure());
        assertEquals(Fence.GLOBAL, scopes.scopes()[1]);
        assertEquals(Fence.GLOBAL, StreamSequencer.bundleScope(scopes.scopes()));
    }

    @Test
    void aBundleHoldingAChunkInstallCarriesTheLifecycleMarker() {
        SendRouteResolver.BundleScopes scopes = SendRouteResolver.bundleScopes(TABLE::routeV2For, 775,
                Direction.CLIENTBOUND, ProtocolState.PLAY,
                new int[] {AckLaneTables.INSTALL, AckLaneTables.FORGET, AckLaneTables.BLOCK_ENTITY});
        assertEquals(Fence.CHUNK_INSTALL, scopes.scopes()[0]);
        assertEquals(Fence.CHUNK_FORGET, scopes.scopes()[1]);
        assertEquals(Fence.CHUNK_ACK, scopes.scopes()[2]);
        assertEquals(Fence.GLOBAL, StreamSequencer.bundleScope(scopes.scopes()));
    }

    @Test
    void anotherProtocolFailsSafeToTheAlwaysAckFence() throws Exception {
        RoutingTable other = rs.sudoe.quicraft.core.routing.RoutingTableLoader.parse(
                AckLaneTables.json().replace("\"protocol\":775", "\"protocol\":776"));
        SendRouteResolver.Resolved install = SendRouteResolver.resolve(other::routeV2For, extractorsOf(other), 776,
                Direction.CLIENTBOUND, ProtocolState.PLAY, AckLaneTables.INSTALL,
                AckLaneTables.chunkUncompressed(45, 1, 1));
        assertEquals(Fence.CHUNK_ACK, install.fence());
    }
}
