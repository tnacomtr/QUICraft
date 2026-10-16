// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

/**
 * The pre-login accounting surface of one {@code /3} connection (docs/protocol.md 12.13), handed to the
 * platform and to the V3 read path through {@link QuicServer#preLoginTracker}. Every method is safe to
 * call from any thread: the implementation serialises on the listener's registry.
 *
 * <p>Once the connection has been promoted, charges return their failure value (no pre-login charge
 * remains), session calls are refused and pauses do nothing.
 */
public interface PreLoginConnection extends PreLoginStage {
    /**
     * Charges a declared-length login frame before its body is read (12.13). Oversized frames close the
     * connection locally; a full pool waits non-fatally.
     */
    PreLoginTracker.ChargeResult chargeDeclaredFrame(long declaredBytes, long framingBytes);

    /** Charges accepted 0-RTT early data beyond the staged share. False when the pool is full or the connection is promoted. */
    boolean chargeEarlyData(long bytes);

    /** Releases up to {@code bytes} of extra charges after early data or a completed frame is consumed. */
    void releaseExtra(long bytes);

    /**
     * Records {@code bytes} of consumed progress. The least-progressed connection in a prefix is shed
     * first (12.13), so the V3 read path reports every byte it consumes. Ignored once promoted or closed.
     */
    void recordProgress(long bytes);

    /**
     * Reserves one session-server call for this connection's coarse prefix (12.13). False when the prefix
     * already has its cap in flight. Pair every true with {@link #endSessionCall()}.
     */
    boolean tryBeginSessionCall();

    /** Ends a session-server call reserved by {@link #tryBeginSessionCall()}. Extra calls are ignored. */
    void endSessionCall();

    /**
     * Retries a promotion that a Login Success or activation requested but that the node budget could
     * not take yet (12.13). The connection stays staged until it succeeds or its deadlines expire. True
     * once promoted.
     */
    boolean retryPromotion();
}
