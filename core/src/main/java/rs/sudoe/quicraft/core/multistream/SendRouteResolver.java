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
 *
 * <p>Bundle members are mapped by {@link #bundleScopes} instead, without position extraction (13.15).
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

        private static Resolved chunk(StreamSequencer.Fence fence, int x, int z) {
            return new Resolved(fence, false, x, z, null);
        }

        private static Resolved failClosed(String why) {
            return new Resolved(null, false, 0, 0, why);
        }
    }

    /** The member scopes of one bundle for {@link StreamSequencer#bundleScope}, or a fail-closed reason. */
    public static final class BundleScopes {
        private final StreamSequencer.Fence[] scopes;
        private final String failure;

        private BundleScopes(StreamSequencer.Fence[] scopes, String failure) {
            this.scopes = scopes;
            this.failure = failure;
        }

        /** One scope per member, in order; null when the bundle must fail closed. */
        public StreamSequencer.Fence[] scopes() { return scopes; }

        /** True when the bundle must not be written and the session closes 0x11. */
        public boolean failed() { return failure != null; }

        /** Why the bundle fails closed, or null. */
        public String failure() { return failure; }
    }

    /** The protocol whose chunk lifecycle packets are known (13.21). */
    static final int LIFECYCLE_PROTOCOL = 775;
    /** level_chunk_with_light (13.21, protocol 775). */
    static final int CHUNK_INSTALL_ID = 45;
    /** forget_level_chunk (13.21, protocol 775). */
    static final int CHUNK_FORGET_ID = 37;

    private SendRouteResolver() {
    }

    /**
     * The fence of a CHUNK route that carries the {@code ack} dependency. Install and forget are resolved by the send
     * driver against its held-chunk set; every other packet always carries the dependency (13.21). A protocol without
     * a known lifecycle fails safe to the always-ack fence.
     */
    private static StreamSequencer.Fence chunkAckFence(int protocolNumber, int packetId) {
        if (protocolNumber == LIFECYCLE_PROTOCOL) {
            if (packetId == CHUNK_INSTALL_ID) return StreamSequencer.Fence.CHUNK_INSTALL;
            if (packetId == CHUNK_FORGET_ID) return StreamSequencer.Fence.CHUNK_FORGET;
        }
        return StreamSequencer.Fence.CHUNK_ACK;
    }

    /**
     * Maps each bundle member to a sequencer scope with the 13.15 strongest-member rule. No position is extracted:
     * bundle context is the only exception to individual chunk routing.
     *
     * <p>Mapping: GLOBAL and FENCE to GLOBAL; VIEW to VIEW; CONTROL to CONTROL_FENCE; ORDINARY to ORDINARY_CONTROL;
     * a REALTIME (LANE) member, an unknown packet id or an unknown dependency to GLOBAL; a CHUNK member to CHUNK,
     * which {@link StreamSequencer#bundleScope} promotes to global. INTERACTION maps to CONTROL_FENCE: the sequencer
     * has no clientbound interaction scope, the bundle floor is control anyway, and a control fence already waits
     * for the latest REALTIME record that an interaction depends on. View and interaction are incomparable, so a
     * bundle holding both is global. An ACK_JOIN or BATCH_JOIN member or an epoch change fails closed.
     *
     * @param packetIds the members' packet ids, in order
     */
    public static BundleScopes bundleScopes(SessionReceiveDriver.RouteSource routes, int protocolNumber,
            Direction direction, ProtocolState state, int[] packetIds) {
        StreamSequencer.Fence[] scopes = new StreamSequencer.Fence[packetIds.length];
        int interaction = -1;
        boolean view = false;
        for (int i = 0; i < packetIds.length; i++) {
            RouteV2 v2 = routes.routeFor(new RouteKey(protocolNumber, state, direction, packetIds[i]));
            if (v2 == null) {
                scopes[i] = StreamSequencer.Fence.GLOBAL;
                continue;
            }
            if (v2.dependency == Dependency.ACK_JOIN || v2.dependency == Dependency.BATCH_JOIN) {
                return new BundleScopes(null, "bundle member " + i + " (packet " + packetIds[i]
                        + ") is a join (13.15)");
            }
            if (v2.epochChange) {
                return new BundleScopes(null, "bundle member " + i + " (packet " + packetIds[i]
                        + ") changes the epoch (13.15)");
            }
            if (v2.lane == RouteV2.StreamLane.CHUNK) {
                // Promoted to global either way; the marker tells the driver an untracked chunk lifecycle record
                // rides this bundle (13.21).
                scopes[i] = v2.dependency == Dependency.ACK ? chunkAckFence(protocolNumber, packetIds[i])
                        : StreamSequencer.Fence.CHUNK;
                continue;
            }
            if (v2.lane != RouteV2.StreamLane.CONTROL) {
                scopes[i] = StreamSequencer.Fence.GLOBAL;
                continue;
            }
            switch (v2.dependency) {
                case VIEW:
                    scopes[i] = StreamSequencer.Fence.VIEW;
                    view = true;
                    break;
                case CONTROL:
                    scopes[i] = StreamSequencer.Fence.CONTROL_FENCE;
                    break;
                case ORDINARY:
                    scopes[i] = StreamSequencer.Fence.ORDINARY_CONTROL;
                    break;
                case INTERACTION:
                    scopes[i] = StreamSequencer.Fence.CONTROL_FENCE;
                    interaction = i;
                    break;
                default:
                    scopes[i] = StreamSequencer.Fence.GLOBAL;
                    break;
            }
        }
        if (view && interaction >= 0) {
            scopes[interaction] = StreamSequencer.Fence.GLOBAL;
        }
        return new BundleScopes(scopes, null);
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
        if (v2.lane == RouteV2.StreamLane.ACK) {
            // 13.21: sequencer places it on the ACK stream; no position.
            return Resolved.of(StreamSequencer.Fence.ACK_TRAILING, false);
        }
        if (v2.lane == RouteV2.StreamLane.CHUNK) {
            return resolveChunk(extractors, v2, uncompressed,
                    v2.dependency == Dependency.ACK ? chunkAckFence(protocolNumber, packetId)
                            : StreamSequencer.Fence.CHUNK);
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

    private static Resolved resolveChunk(Map<String, Extractor> extractors, RouteV2 v2, byte[] uncompressed,
            StreamSequencer.Fence fence) {
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
        return Resolved.chunk(fence, position.chunkX, position.chunkZ);
    }
}
