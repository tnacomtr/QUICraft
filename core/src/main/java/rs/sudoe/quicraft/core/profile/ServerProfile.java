// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.profile;

/** Immutable server policy snapshot for /3 resource and scheduler limits (13.12, 13.20). */
public final class ServerProfile {
    public final int maxConnections, maxLanes;
    public final long nodeBudgetBytes;
    public final double preLoginPoolFraction;
    public final int queuePacketLimit;
    public final long queueByteLimit;
    public final int controlWeight, realtimeWeight, chunkWeight;
    public ServerProfile(int maxConnections, int maxLanes, long nodeBudgetBytes,
            double preLoginPoolFraction, int queuePacketLimit, long queueByteLimit,
            int controlWeight, int realtimeWeight, int chunkWeight) {
        this.maxConnections=maxConnections; this.maxLanes=maxLanes; this.nodeBudgetBytes=nodeBudgetBytes;
        this.preLoginPoolFraction=preLoginPoolFraction; this.queuePacketLimit=queuePacketLimit;
        this.queueByteLimit=queueByteLimit; this.controlWeight=controlWeight;
        this.realtimeWeight=realtimeWeight; this.chunkWeight=chunkWeight;
    }
    /** Validate profile bounds; policy-specific validation remains to be implemented. */
    public void validate() { throw new UnsupportedOperationException("profile validation pending"); }
}
