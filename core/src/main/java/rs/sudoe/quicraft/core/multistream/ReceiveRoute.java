// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Extractor;

/**
 * Route information the integration unit decodes from a record's packet id, handed to the receive
 * policy (protocol.md 13.6, 13.10, 13.18, 13.19). The receiver does not decode game bodies; it only
 * checks that the declared route is consistent with the stream the record arrived on.
 *
 * <p>Immutable value type.
 */
public final class ReceiveRoute {
    /** Lane class of the stream the record must arrive on (13.3). */
    public enum Lane {
        /** CONTROL, logical stream 0. */
        CONTROL,
        /** REALTIME, logical stream 1. */
        REALTIME,
        /** CHUNK[i], logical streams 2 onward. */
        CHUNK
    }

    private final Lane lane;
    private final Dependency fence;
    private final Extractor.Position position;
    private final boolean epochChange;
    private final boolean join;

    /**
     * @param lane lane class the route requires
     * @param fence fence/route the decoded packet requires; a header cannot downgrade it (13.10)
     * @param position chunk position for shard validation, or null for global/CONTROL/REALTIME records
     * @param epochChange true if this record is the epoch boundary (its epoch is the new one, 13.18)
     * @param join true if this is an ACK_JOIN or BATCH_JOIN record, which must live on CHUNK[0] (13.19)
     */
    public ReceiveRoute(Lane lane, Dependency fence, Extractor.Position position, boolean epochChange, boolean join) {
        if (lane == null || fence == null) {
            throw new IllegalArgumentException("lane and fence are required");
        }
        this.lane = lane;
        this.fence = fence;
        this.position = position;
        this.epochChange = epochChange;
        this.join = join;
    }

    /** Returns the lane class the route requires. */
    public Lane lane() {
        return lane;
    }

    /** Returns the fence the decoded packet requires. */
    public Dependency fence() {
        return fence;
    }

    /** Returns the chunk position for shard validation, or null. */
    public Extractor.Position position() {
        return position;
    }

    /** Reports whether this record opens a new epoch. */
    public boolean epochChange() {
        return epochChange;
    }

    /** Reports whether this record is a join record. */
    public boolean join() {
        return join;
    }
}
