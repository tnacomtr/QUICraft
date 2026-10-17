// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import rs.sudoe.quicraft.core.resource.ResourcePools;

/**
 * Staged {@code /3} admission accounting and clocks for one connection (docs/protocol.md 12.13 and
 * 13.17).
 *
 * <p>A connection is {@link State#STAGED} from its first accepted Initial. It holds the staged share
 * in the {@link ResourcePools.Pool#PRE_LOGIN} pool, plus any extra charges for early data and
 * declared-length frames. Promotion ({@link #onLoginSuccess()} online, {@link #onActivation()}
 * offline) reserves the full 24 KiB exposure from {@link ResourcePools.Pool#NODE} before it releases
 * the staged charge, so a promoted connection never runs unaccounted. {@link #close()} releases
 * whichever pool the connection currently holds.
 *
 * <p>The client-wait deadline (10 s) counts only time not spent in a {@link #pauseClientWait()}
 * interval; the 30 s hard cap counts from the first accepted packet regardless of pauses. This class
 * has no Netty dependency so platform login callbacks can pause it. It is not thread-safe: the owner
 * calls it from the connection's event loop, and {@link PreLoginRegistry} serialises shedding.
 */
public final class PreLoginTracker implements PreLoginStage, AutoCloseable {
    /**
     * Native CONTROL and REALTIME exposure, the two client-opened streams at 8 KiB each. The N
     * one-byte reverse-CHUNK guards are added per connection by {@link #stagedShare(int)} (13.13).
     */
    public static final long NATIVE_BYTES = 2L * 8192;
    /** Bounded staging read buffer (12.13). */
    public static final long READ_BUFFER_BYTES = 4096;
    /**
     * Conservative TLS, crypto and 0-RTT reservation per connection (12.13, 13.13). UNMEASURED: it
     * must be replaced by the load benchmark's measured value (13.17) before the profile is trusted.
     */
    public static final long TLS_ESTIMATE_BYTES = 4096;
    /** The largest declared login frame, in bytes, before framing: the vanilla login plugin answer bound. */
    public static final long MAX_DECLARED_FRAME_BYTES = 1L << 20;
    /** Client wait allowed from the first accepted packet, excluding paused time (12.13). */
    public static final long CLIENT_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** Unconditional cap from the first accepted packet (12.13). */
    public static final long HARD_CAP_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** The full per-connection exposure reserved from the node budget at promotion (12.13, 13.13). */
    public static final long PROMOTED_BYTES = TransportConfig.V3_INITIAL_EXPOSURE_BYTES;

    /** Where a connection's accounting lives. */
    public enum State {
        /** Charged to the pre-login pool; shedable; clocks run. */
        STAGED,
        /** Charged to the node budget (logged in or activated); never shed as pre-login. */
        PROMOTED,
        /** All charges released. */
        CLOSED
    }

    /** Outcome of a declared-length frame charge (12.13). */
    public enum ChargeResult {
        /** The frame fits the staged share, or its exact extra charge was made. */
        CHARGED,
        /** The pool is full right now: wait non-fatally under the client-wait deadline. */
        WAIT,
        /** The declared length exceeds 1 MiB: close locally, never the pre-login shed path. */
        OVERSIZED
    }

    private final ResourcePools pools;
    private final Object owner;
    private final boolean online;
    private final long stagedShare;
    private final LongSupplier clock;
    private final long acceptedAt;
    private long pausedNanos;
    private long pausedSince = -1;
    private int pauseDepth;
    private long extra;
    private boolean promotionRequested;
    private State state = State.STAGED;
    /** Set by {@link #onShed}: the staged charge stays until {@link #close()} confirms the victim's close. */
    private boolean shedding;

    private PreLoginTracker(ResourcePools pools, Object owner, int shards, boolean online, LongSupplier clock) {
        this.pools = Objects.requireNonNull(pools, "pools");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.online = online;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stagedShare = stagedShare(shards);
        this.acceptedAt = clock.getAsLong();
    }

    /**
     * Charges the staged share for a connection at its first accepted Initial (12.13). Returns null
     * when the pre-login pool cannot take it right now; the caller then sheds or refuses.
     *
     * @param shards the negotiated chunk-stream count N, 1..6
     * @param online true for online mode, where Login Success promotes; false for offline mode, which
     *     promotes only at activation
     * @param clock a monotonic nanosecond clock (tests inject one)
     */
    public static PreLoginTracker tryOpen(ResourcePools pools, Object owner, int shards, boolean online,
            LongSupplier clock) {
        PreLoginTracker tracker = new PreLoginTracker(pools, owner, shards, online, clock);
        if (!pools.reserve(ResourcePools.Pool.PRE_LOGIN, owner, tracker.stagedShare)) {
            return null;
        }
        return tracker;
    }

    /**
     * The staged reservation for a connection with {@code shards} chunk streams:
     * {@code 2*8 KiB + N + 4 KiB read buffer + TLS estimate} (12.13).
     */
    public static long stagedShare(int shards) {
        if (shards < 1 || shards > 6) {
            throw new IllegalArgumentException("shards must be in 1..6");
        }
        return NATIVE_BYTES + shards + READ_BUFFER_BYTES + TLS_ESTIMATE_BYTES;
    }

    /** The staged share this connection was opened with. */
    public long stagedShare() {
        return stagedShare;
    }

    /** Bytes this connection currently holds in whichever pool it is charged to. */
    public long charged() {
        return state == State.STAGED ? stagedShare + extra : state == State.PROMOTED ? PROMOTED_BYTES : 0;
    }

    /** Current accounting state. */
    public State state() {
        return state;
    }

    /**
     * Charges accepted 0-RTT early data for its bytes beyond the staged share (12.13). Returns false
     * when the pool is full (the caller waits non-fatally) or the connection is no longer staged.
     */
    public boolean chargeEarlyData(long bytes) {
        return chargeExtra(bytes);
    }

    /**
     * Charges a declared-length frame before its body is read (12.13). {@code declaredBytes} is the
     * exact declared length of the frame body and {@code framingBytes} its framing. A frame within the
     * staged share needs no extra charge; a larger one is charged at its exact total.
     */
    public ChargeResult chargeDeclaredFrame(long declaredBytes, long framingBytes) {
        if (declaredBytes < 0 || framingBytes < 0) {
            throw new IllegalArgumentException("frame sizes must not be negative");
        }
        if (declaredBytes > MAX_DECLARED_FRAME_BYTES) {
            return ChargeResult.OVERSIZED;
        }
        long total = Math.addExact(declaredBytes, framingBytes);
        if (total <= stagedShare) {
            return ChargeResult.CHARGED;
        }
        return chargeExtra(total) ? ChargeResult.CHARGED : ChargeResult.WAIT;
    }

    /** Releases up to {@code bytes} of extra charges once early data or a completed frame is consumed. */
    public void releaseExtra(long bytes) {
        // A shed victim keeps every byte until its close is confirmed (12.13); close releases them all.
        if (bytes <= 0 || state != State.STAGED || shedding) {
            return;
        }
        long releasable = Math.min(bytes, extra);
        if (releasable > 0) {
            pools.release(ResourcePools.Pool.PRE_LOGIN, owner, releasable);
            extra -= releasable;
        }
    }

    /** §12.13: platform and session-server waits do not spend client-wait time. Nests. */
    @Override
    public void pauseClientWait() {
        if (state != State.STAGED) {
            return;
        }
        if (pauseDepth++ == 0) {
            pausedSince = clock.getAsLong();
        }
    }

    /** §12.13: resumes the client-wait clock once every pause has been resumed. */
    @Override
    public void resumeClientWait() {
        if (state != State.STAGED || pauseDepth == 0) {
            return;
        }
        if (--pauseDepth == 0) {
            pausedNanos += clock.getAsLong() - pausedSince;
            pausedSince = -1;
        }
    }

    /**
     * True once the unpaused client-wait deadline or the unconditional 30 s hard cap has passed. Only
     * a staged connection has deadlines; a promoted one is never a pre-login timeout.
     */
    public boolean expired() {
        if (state != State.STAGED) {
            return false;
        }
        long now = clock.getAsLong();
        long paused = pausedNanos + (pauseDepth > 0 ? now - pausedSince : 0);
        return now - acceptedAt >= HARD_CAP_NANOS || now - acceptedAt - paused >= CLIENT_WAIT_NANOS;
    }

    /**
     * §12.13: Login Success promotes an online connection, after session-server authentication. An
     * offline connection ignores it and stays staged until activation.
     */
    @Override
    public void onLoginSuccess() {
        if (online) {
            requestPromotion();
        }
    }

    /** §12.13: activation promotes every connection, offline ones included. */
    @Override
    public void onActivation() {
        requestPromotion();
    }

    /**
     * Retries a requested promotion that could not reserve the node share (12.13: the connection waits
     * non-fatally while it stays staged; the caller retries on each deadline tick). Returns true once
     * the connection is promoted. A connection with no request yet, or one already closed, returns false.
     */
    public boolean retryPromotion() {
        return promotionRequested && promote();
    }

    private void requestPromotion() {
        promotionRequested = true;
        promote();
    }

    /**
     * Promotes the connection: reserves the full 24 KiB exposure from the node budget, then releases
     * the staged charge (12.13). Returns false when the node budget cannot take it right now; the
     * caller waits non-fatally and retries, and the connection stays staged. Also returns false once
     * the connection has expired, so the deadline check closes it.
     */
    public boolean promote() {
        if (state == State.PROMOTED) {
            return true;
        }
        if (state != State.STAGED || shedding) {
            return false;
        }
        if (expired()) {
            // 12.13: the hard cap is unconditional; an expired staged connection closes, never promotes.
            return false;
        }
        if (!pools.reserve(ResourcePools.Pool.NODE, owner, PROMOTED_BYTES)) {
            return false;
        }
        pools.release(ResourcePools.Pool.PRE_LOGIN, owner, stagedShare + extra);
        extra = 0;
        state = State.PROMOTED;
        return true;
    }

    /**
     * §12.13: marks a staged connection as a shed victim. Its charge is NOT released here: the bytes stay
     * charged until its close is confirmed by {@link #close()}, so the requester is granted only from confirmed
     * bytes. Only {@link PreLoginRegistry} calls it, and only for a staged connection; a promoted one is never a
     * shed victim. Refuses further charges and promotion.
     */
    @Override
    public void onShed() {
        if (state == State.STAGED) {
            shedding = true;
        }
    }

    /** True once this staged connection has been selected as a shed victim and its close is not yet confirmed. */
    public boolean isShedding() {
        return shedding;
    }

    /** Releases the connection's charge from whichever pool it holds. Idempotent. */
    @Override
    public void close() {
        switch (state) {
            case STAGED:
                pools.release(ResourcePools.Pool.PRE_LOGIN, owner, stagedShare + extra);
                extra = 0;
                break;
            case PROMOTED:
                pools.release(ResourcePools.Pool.NODE, owner, PROMOTED_BYTES);
                break;
            default:
                return;
        }
        state = State.CLOSED;
    }

    private boolean chargeExtra(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        if (bytes == 0) {
            return true;
        }
        if (state != State.STAGED || shedding || !pools.reserve(ResourcePools.Pool.PRE_LOGIN, owner, bytes)) {
            return false;
        }
        extra += bytes;
        return true;
    }
}
