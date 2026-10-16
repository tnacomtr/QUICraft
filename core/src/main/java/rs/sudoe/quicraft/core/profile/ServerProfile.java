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
    /** Validate profile bounds and the /3 listener's admission invariant. */
    public void validate() {
        if (maxConnections < 1) throw new IllegalArgumentException("maxConnections must be positive");
        if (maxLanes < 1 || maxLanes > 6) throw new IllegalArgumentException("maxLanes must be in 1..6");
        if (nodeBudgetBytes < 1) throw new IllegalArgumentException("nodeBudgetBytes must be positive");
        if (!(preLoginPoolFraction > 0.0 && preLoginPoolFraction < 1.0))
            throw new IllegalArgumentException("preLoginPoolFraction must be in (0,1)");
        long preLoginCap = (long) (nodeBudgetBytes * preLoginPoolFraction)
                / (2L * 8192 + maxLanes + 4096 + 4096);
        if (maxConnections < maxLanes + preLoginCap)
            throw new IllegalArgumentException("maxConnections must cover logged-in target plus pre-login cap");
        if (16 * 1024L + 2L * maxLanes + 1 + 512 > 24 * 1024L)
            throw new IllegalArgumentException("maxLanes exceeds the 24 KiB receive share");
        if (queuePacketLimit < 1 || queueByteLimit < 1 || controlWeight < 1 || realtimeWeight < 1 || chunkWeight < 1)
            throw new IllegalArgumentException("queue limits and weights must be positive");
    }
}
