// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.Map;

import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RouteV2;

/**
 * Resolves the send-side fence of one packet from the reviewed schema-2 table, the same way
 * {@link SessionReceiveDriver} resolves the receive side (13.5, 13.6, 13.9). The receiver derives the route
 * from the table alone, so a sender that picks a different stream is rejected. Therefore this resolver never
 * falls back to CONTROL: whatever it cannot resolve fails closed.
 *
 * <p>Mapping: GLOBAL, FENCE and LANE go to GLOBAL; VIEW to VIEW; CONTROL to CONTROL_FENCE; ORDINARY and
 * INTERACTION to ORDINARY_CONTROL (INTERACTION also sets the interaction flag); REALTIME lane to REALTIME;
 * CHUNK lane to CHUNK[ShardHash] of the extracted position. An unknown packet id is a global fence (13.5).
 * ACK_JOIN, BATCH_JOIN and epoch-change routes need adapter metadata and are rejected here.
 */
public final class SendRouteResolver {

    /** The outcome of resolving one packet: a fence and position, or a fail-closed reason. */
    public static final class Resolved {
        private final StreamSequencer.Fence fence;
        private final boolean interaction;
        private final int chunkX;
        private final int chunkZ;
        private final String failure;

        private Resolved(StreamSequencer.Fence fence, boolean interaction, int chunkX, int chunkZ, String failure) {
            this.fence = fence;
            this.interaction = interaction;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.failure = failure;
        }

        /** The fence to submit with, or null when the packet must fail closed. */
        public StreamSequencer.Fence fence() { return fence; }

        /** True for a serverbound ORDINARY_CONTROL interaction record (13.9). */
        public boolean interaction() { return interaction; }

        /** Chunk X of a CHUNK fence; meaningless for other fences. */
        public int chunkX() { return chunkX; }

        /** Chunk Z of a CHUNK fence; meaningless for other fences. */
        public int chunkZ() { return chunkZ; }

        /** True when the packet must not be written and the session closes 0x11. */
        public boolean failed() { return failure != null; }

        /** Why the packet fails closed, or null when it resolved. */
        public String failure() { return failure; }

        private static Resolved of(StreamSequencer.Fence fence, boolean interaction) {
            return new Resolved(fence, interaction, 0, 0, null);
        }

        private static Resolved chunk(int x, int z) {
            return new Resolved(StreamSequencer.Fence.CHUNK, false, x, z, null);
        }

        private static Resolved failClosed(String why) {
            return new Resolved(null, false, 0, 0, why);
        }
    }

    private SendRouteResolver() {
    }

    /**
     * Resolves one packet.
     *
     * @param routes the reviewed route lookup (production passes {@code routingTable::routeV2For})
     * @param extractors reviewed extractors by name, for CHUNK positions
     * @param protocolNumber the negotiated protocol number, for route keys
     * @param direction the sending direction
     * @param state the protocol state of the packet
     * @param packetId the packet id
     * @param uncompressed the uncompressed packet-id-plus-body bytes, or null when unavailable
     */
    public static Resolved resolve(SessionReceiveDriver.RouteSource routes, Map<String, Extractor> extractors,
            int protocolNumber, Direction direction, ProtocolState state, int packetId, byte[] uncompressed) {
        RouteV2 v2 = routes.routeFor(new RouteKey(protocolNumber, state, direction, packetId));
        if (v2 == null) {
            return Resolved.of(StreamSequencer.Fence.GLOBAL, false);
        }
        boolean join = v2.dependency == Dependency.ACK_JOIN || v2.dependency == Dependency.BATCH_JOIN;
        if (join) {
            return Resolved.failClosed("join route needs adapter metadata (13.19)");
        }
        if (v2.epochChange) {
            return Resolved.failClosed("epoch change needs submitEpochTransition (13.18)");
        }
        if (v2.lane == RouteV2.StreamLane.REALTIME) {
            return Resolved.of(StreamSequencer.Fence.REALTIME, false);
        }
        if (v2.lane == RouteV2.StreamLane.CHUNK) {
            return resolveChunk(extractors, v2, uncompressed);
        }
        switch (v2.dependency) {
            case GLOBAL:
            case FENCE:
            case LANE:
                return Resolved.of(StreamSequencer.Fence.GLOBAL, false);
            case VIEW:
                return Resolved.of(StreamSequencer.Fence.VIEW, false);
            case CONTROL:
                return Resolved.of(StreamSequencer.Fence.CONTROL_FENCE, false);
            case ORDINARY:
                return Resolved.of(StreamSequencer.Fence.ORDINARY_CONTROL, false);
            case INTERACTION:
                return Resolved.of(StreamSequencer.Fence.ORDINARY_CONTROL, true);
            default:
                return Resolved.of(StreamSequencer.Fence.GLOBAL, false);
        }
    }

    private static Resolved resolveChunk(Map<String, Extractor> extractors, RouteV2 v2, byte[] uncompressed) {
        if (uncompressed == null) {
            return Resolved.failClosed("CHUNK route without uncompressed bytes (13.8)");
        }
        Extractor extractor = v2.extractor == null ? null : extractors.get(v2.extractor);
        if (extractor == null) {
            return Resolved.failClosed("CHUNK route without a reviewed extractor (13.7)");
        }
        Extractor.Position position;
        try {
            position = extractor.extract(uncompressed);
        } catch (RuntimeException e) {
            return Resolved.failClosed("CHUNK extractor failed (13.8)");
        }
        if (position == null) {
            return Resolved.failClosed("CHUNK extractor returned no position (13.8)");
        }
        return Resolved.chunk(position.chunkX, position.chunkZ);
    }
}
