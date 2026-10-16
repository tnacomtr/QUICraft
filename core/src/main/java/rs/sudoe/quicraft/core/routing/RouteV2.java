// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

/** Additive schema-2 route shape; existing Route/loader remain schema-1 compatible (13.7). */
public final class RouteV2 {
    public final String extractor, position, decodeFootprintBound;
    public final boolean epochChange;
    public final ServerboundLimits serverboundLimits;
    public RouteV2(String extractor, String position, boolean epochChange,
                   ServerboundLimits serverboundLimits, String decodeFootprintBound) {
        this.extractor=extractor; this.position=position; this.epochChange=epochChange;
        this.serverboundLimits=serverboundLimits; this.decodeFootprintBound=decodeFootprintBound;
    }
    /** Reviewed serverbound limits for one protocol state (13.7, 13.12). */
    public static final class ServerboundLimits {
        public final int maxServerboundRecord, maxServerboundRealtimeRecord, maxDecodedFootprint, ordinaryReadAllowance;
        public ServerboundLimits(int control, int realtime, int decoded, int allowance) {
            maxServerboundRecord=control; maxServerboundRealtimeRecord=realtime;
            maxDecodedFootprint=decoded; ordinaryReadAllowance=allowance;
        }
    }
}
