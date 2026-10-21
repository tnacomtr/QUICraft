// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.profile;

import rs.sudoe.quicraft.core.multistream.LogicalStream;
import rs.sudoe.quicraft.core.transport.PreLoginTracker;

/**
 * Immutable server policy snapshot for {@code /3} resource and scheduler limits (docs/protocol.md 12.13,
 * 13.12, 13.20). A running connection keeps the snapshot it was admitted under; a changed profile
 * applies to new connections only.
 *
 * <p>{@code maxConnections} is the logged-in target: the number of players the node serves over QUIC.
 * The listener's {@code maxOpen} must cover that target plus the pre-login connection cap
 * ({@link #preLoginConnectionCap()}), which {@link #requireOpen(int)} checks.
 */
public final class ServerProfile {
    /**
     * The total protected read and decode area P inside each connection's 24 KiB share (13.12). It
     * covers the fixed 2 KiB REALTIME slice and the CONTROL allowance. UNMEASURED profile default:
     * 6 KiB satisfies the 13.12 inequality for every N up to 5 and leaves room for prefix staging.
     */
    public static final long PROTECTED_READ_BYTES = 6L << 10;
    /** The per-connection receive share the inequality is checked against (13.12). */
    static final long RECEIVE_SHARE_BYTES = 24L << 10;

    public final int maxConnections, maxLanes;
    public final long nodeBudgetBytes;
    public final double preLoginPoolFraction;
    public final int queuePacketLimit;
    public final long queueByteLimit;
    public final int controlWeight, realtimeWeight, chunkWeight;
    /** Default cap on session-server calls in flight per coarse source prefix (12.13). */
    public static final int DEFAULT_SESSION_CALLS_PER_PREFIX = 4;
    /** Largest accepted session-call cap per coarse source prefix. */
    public static final int MAX_SESSION_CALLS_PER_PREFIX = 64;
    /**
     * Default cap on declared-length bytes in flight per coarse prefix (12.13): two maximum-size login frames.
     * UNMEASURED.
     */
    public static final long DEFAULT_DECLARED_BYTES_PER_PREFIX = 2L * PreLoginTracker.MAX_DECLARED_FRAME_BYTES;
    /**
     * Smallest accepted declared-bytes cap: one maximum-size frame plus its framing, so that a single frame
     * can always be charged. The framing margin is a varint bound (at most 5 bytes) with headroom.
     */
    public static final long MIN_DECLARED_BYTES_PER_PREFIX = PreLoginTracker.MAX_DECLARED_FRAME_BYTES + 16;
    /** Largest accepted declared-bytes cap per coarse prefix. */
    public static final long MAX_DECLARED_BYTES_PER_PREFIX = 64L << 20;
    public final int sessionCallsPerPrefix;
    public final long declaredBytesPerPrefix;

    /** A profile with the default per-prefix pre-login caps (12.13). */
    public ServerProfile(int maxConnections, int maxLanes, long nodeBudgetBytes,
            double preLoginPoolFraction, int queuePacketLimit, long queueByteLimit,
            int controlWeight, int realtimeWeight, int chunkWeight) {
        this(maxConnections, maxLanes, nodeBudgetBytes, preLoginPoolFraction, queuePacketLimit, queueByteLimit,
                controlWeight, realtimeWeight, chunkWeight, DEFAULT_SESSION_CALLS_PER_PREFIX,
                DEFAULT_DECLARED_BYTES_PER_PREFIX);
    }

    public ServerProfile(int maxConnections, int maxLanes, long nodeBudgetBytes,
            double preLoginPoolFraction, int queuePacketLimit, long queueByteLimit,
            int controlWeight, int realtimeWeight, int chunkWeight, int sessionCallsPerPrefix,
            long declaredBytesPerPrefix) {
        this.sessionCallsPerPrefix = sessionCallsPerPrefix;
        this.declaredBytesPerPrefix = declaredBytesPerPrefix;
        this.maxConnections = maxConnections;
        this.maxLanes = maxLanes;
        this.nodeBudgetBytes = nodeBudgetBytes;
        this.preLoginPoolFraction = preLoginPoolFraction;
        this.queuePacketLimit = queuePacketLimit;
        this.queueByteLimit = queueByteLimit;
        this.controlWeight = controlWeight;
        this.realtimeWeight = realtimeWeight;
        this.chunkWeight = chunkWeight;
    }

    /**
     * Checks every range and cross-field rule, throwing {@link IllegalArgumentException} with the rule
     * that failed. Nothing is partly applied: the profile is immutable and a failed check yields no
     * object to use.
     */
    public void validate() {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be positive");
        }
        if (maxLanes < 1 || maxLanes > LogicalStream.MAX_CHUNKS) {
            throw new IllegalArgumentException("maxLanes must be in 1..5, was " + maxLanes);
        }
        if (nodeBudgetBytes < 1) {
            throw new IllegalArgumentException("nodeBudgetBytes must be positive");
        }
        if (!(preLoginPoolFraction > 0.0 && preLoginPoolFraction < 1.0)) {
            throw new IllegalArgumentException("preLoginPoolFraction must be in (0,1), was " + preLoginPoolFraction);
        }
        if (preLoginConnectionCap() < 1) {
            throw new IllegalArgumentException("the pre-login pool holds no staged /3 connection: raise "
                    + "nodeBudgetBytes or preLoginPoolFraction");
        }
        // 13.12: 16 KiB + 2*(N+1) + 1 + P + 512 <= 24 KiB.
        long native16 = 16L * 1024 + 2L * (maxLanes + 1) + 1 + PROTECTED_READ_BYTES + 512;
        if (native16 > RECEIVE_SHARE_BYTES) {
            throw new IllegalArgumentException("maxLanes " + maxLanes + " with protected reads of "
                    + PROTECTED_READ_BYTES + " bytes exceeds the 24 KiB receive share (13.12)");
        }
        if (queuePacketLimit < 1 || queueByteLimit < 1) {
            throw new IllegalArgumentException("queue limits must be positive");
        }
        if (controlWeight < 1 || realtimeWeight < 1 || chunkWeight < 1) {
            throw new IllegalArgumentException("queue weights must be positive");
        }
        if (sessionCallsPerPrefix < 1 || sessionCallsPerPrefix > MAX_SESSION_CALLS_PER_PREFIX) {
            throw new IllegalArgumentException("sessionCallsPerPrefix must be in 1.."
                    + MAX_SESSION_CALLS_PER_PREFIX + ", was " + sessionCallsPerPrefix);
        }
        if (declaredBytesPerPrefix < MIN_DECLARED_BYTES_PER_PREFIX
                || declaredBytesPerPrefix > MAX_DECLARED_BYTES_PER_PREFIX) {
            throw new IllegalArgumentException("declaredBytesPerPrefix must be in " + MIN_DECLARED_BYTES_PER_PREFIX
                    + ".." + MAX_DECLARED_BYTES_PER_PREFIX + ", was " + declaredBytesPerPrefix);
        }
    }

    /** The pre-login pool's bytes: its fraction of the node budget (12.13). */
    public long preLoginPoolBytes() {
        return (long) (nodeBudgetBytes * preLoginPoolFraction);
    }

    /**
     * The pre-login connection cap: the pool's bytes divided by one staged reservation at {@code maxLanes}
     * (12.13). It is the number of connections that can be in the staged stage at once.
     */
    public long preLoginConnectionCap() {
        return preLoginPoolBytes() / PreLoginTracker.stagedShare(maxLanes);
    }

    /**
     * Requires the listener's {@code maxOpen} to cover the logged-in target plus the pre-login connection
     * cap (12.13). Throws {@link IllegalArgumentException} naming the values and the knobs to raise.
     */
    public void requireOpen(int maxOpen) {
        long required = (long) maxConnections + preLoginConnectionCap();
        if (maxOpen < required) {
            throw new IllegalArgumentException("maxOpen " + maxOpen + " is below the logged-in target "
                    + maxConnections + " plus the pre-login connection cap " + preLoginConnectionCap()
                    + " (" + required + "); raise maxOpen, maxTrackedIps and globalAttemptsPerSecond together");
        }
    }
}
