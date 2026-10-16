// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

/** Additive schema-2 route shape; existing Route callers remain schema-1 compatible (13.7). */
public final class RouteV2 {
    public enum StreamLane { CONTROL, REALTIME, CHUNK }
    public final StreamLane lane;
    public final Dependency dependency;
    public final Boolean ordinarySmall;
    /** Versioned decoder id, resource-local position name, and state accounting policy (13.7). */
    public final String extractor, position, decodeFootprintBound;
    public final boolean epochChange;
    public final ServerboundLimits serverboundLimits;
    /** Creates the frozen default CONTROL/global shape (13.6-13.7). */
    public RouteV2(String extractor, String position, boolean epochChange,
                   ServerboundLimits serverboundLimits, String decodeFootprintBound) {
        this(StreamLane.CONTROL, Dependency.GLOBAL, extractor, position, epochChange,
                serverboundLimits, decodeFootprintBound, null);
    }
    /** Complete reviewed route metadata (13.7). */
    public RouteV2(StreamLane lane, Dependency dependency, String extractor, String position,
                   boolean epochChange, ServerboundLimits serverboundLimits,
                   String decodeFootprintBound, Boolean ordinarySmall) {
        this.lane=lane; this.dependency=dependency; this.ordinarySmall=ordinarySmall;
        this.extractor=extractor; this.position=position; this.epochChange=epochChange;
        this.serverboundLimits=serverboundLimits; this.decodeFootprintBound=decodeFootprintBound;
    }
    /** Reviewed serverbound limits for one protocol state (13.7, 13.12). */
    public static final class ServerboundLimits {
        public final String decodeFootprintBound;
        public final int protectedReadArea;
        public final int maxServerboundRecord, maxServerboundRealtimeRecord, maxDecodedFootprint, ordinaryReadAllowance;
        /** Creates the frozen limits shape without an accounting policy (13.7). */
        public ServerboundLimits(int control, int realtime, int decoded, int allowance) {
            this(control, realtime, decoded, allowance, null);
        }
        /** Protected area P is the CONTROL allowance plus the fixed REALTIME slice (13.7). */
        public ServerboundLimits(int control, int realtime, int decoded, int allowance, String accounting) {
            maxServerboundRecord=control; maxServerboundRealtimeRecord=realtime;
            maxDecodedFootprint=decoded; ordinaryReadAllowance=allowance;
            protectedReadArea=allowance + 2048; decodeFootprintBound=accounting;
        }
    }
}
