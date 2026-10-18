// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.handler.codec.quic.QuicStreamChannel;
import java.nio.ByteBuffer;
import java.util.List;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The activated {@code /3} session of one connection, as the transport and the game bridge see it
 * (docs/protocol.md 13). The N+2 {@link NStreamSession} is the implementation; callers use only
 * this interface.
 *
 * <p>Threading and lifecycle are those of {@link NStreamSession}: {@link #acceptStream} runs on the
 * connection's event loop. The send operations, including the ACK_JOIN and BATCH_JOIN passthroughs
 * and {@link #end}, submit on the caller's thread, so their handles and answers are available at
 * once and their order is the call order, and the session drains on that loop afterwards. Lifecycle
 * and receive operations ({@link #bindGame}, {@link #activate}, {@link #abort} and {@link #applied})
 * are marshalled onto the loop in call order.
 *
 * <p>The ACK_JOIN and BATCH_JOIN passthroughs (13.19) and the send overload that carries the
 * uncompressed packet bytes are additive. An implementation that does not support one fails
 * closed: the default throws {@link UnsupportedOperationException}.
 */
public interface MultistreamSession {

    /** Attaches the game side, at most once, before or after {@link #activate}. */
    void bindGame(LaneGame game);

    /** Activation (12.11/12.12): takes over stream 0 and opens this side's streams. */
    void activate(ByteBuffer controlLeftover);

    /** Hands over a peer-created stream other than stream 0; event loop only. */
    void acceptStream(QuicStreamChannel stream);

    /** Submits one outbound GAME packet in the game's original order. */
    SentPacket send(ProtocolState state, int packetId, byte[] frame, SentPacket... after);

    /** As {@link #send(ProtocolState, int, byte[], SentPacket...)} with an explicit uncompressed size. */
    SentPacket send(ProtocolState state, int packetId, byte[] frame, int uncompressedBytes, SentPacket... after);

    /**
     * As {@link #send(ProtocolState, int, byte[], SentPacket...)}, with the packet's uncompressed
     * packet-id-plus-body bytes, from which a CHUNK route's position is extracted (13.8).
     */
    default SentPacket send(ProtocolState state, int packetId, byte[] frame, byte[] uncompressedPacket,
            SentPacket... after) {
        throw new UnsupportedOperationException("send with uncompressed bytes");
    }

    /** Submits one vanilla bundle (13.15); returns the handles in wire order. */
    List<SentPacket> sendBundle(OutboundPacket open, List<OutboundPacket> members, OutboundPacket close);

    /** Normal shutdown (13.14): END as a full fence after everything submitted so far. */
    void end();

    /** Closes the connection now with an application error code. */
    void abort(int code);

    /** The game applied the delivered unit with this lane and final sequence (13.10). */
    void applied(Lane lane, long sequence);

    /** The negotiated shard count and logical stream layout (13.2-13.3). */
    SessionContext context();

    /** The lane the routing table assigns to one outbound packet (13.6), without submitting it. */
    Lane outboundLane(ProtocolState state, int packetId);

    /** True while the unsent queue is below its backpressure mark. */
    boolean isWritable();

    /**
     * The game executor stalled from {@code atNanos} (13.10). Additive: the default throws, so a session never
     * silently counts executor time toward its close clocks from a report it cannot trust. A cumulative session
     * derives its exclusion from the listener-owned accounting source; explicit reports remain unsupported and
     * the default keeps throwing.
     */
    default void stallStarted(long atNanos) {
        throw new UnsupportedOperationException("executor stall reporting");
    }

    /**
     * The executor stall that began with {@link #stallStarted(long)} ended at {@code atNanos} (13.10). Additive:
     * the default throws, as for {@link #stallStarted(long)}; a cumulative session takes no explicit report.
     */
    default void stallEnded(long atNanos) {
        throw new UnsupportedOperationException("executor stall reporting");
    }

    // Additive ACK_JOIN and BATCH_JOIN passthroughs (13.19), mirroring SessionSendDriver.

    default boolean createAck(long id, byte[] payload, int chargedBytes) {
        throw new UnsupportedOperationException("createAck");
    }

    default void coverAssigned(long ackId, SentPacket covered) {
        throw new UnsupportedOperationException("coverAssigned");
    }

    default boolean coverPending(long ackId, long updateToken) {
        throw new UnsupportedOperationException("coverPending");
    }

    default void assignedPending(long updateToken, SentPacket covered) {
        throw new UnsupportedOperationException("assignedPending");
    }

    default void suppressPending(long updateToken) {
        throw new UnsupportedOperationException("suppressPending");
    }

    default void markInexact(long ackId) {
        throw new UnsupportedOperationException("markInexact");
    }

    default SentPacket takeAck(long ackId) {
        throw new UnsupportedOperationException("takeAck");
    }

    default void expireAcks() {
        throw new UnsupportedOperationException("expireAcks");
    }

    default SentPacket beginBatch(long batchId, OutboundPacket start) {
        throw new UnsupportedOperationException("beginBatch");
    }

    default SentPacket batchInstall(long batchId, OutboundPacket install, int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("batchInstall");
    }

    default SentPacket finishBatch(long batchId, OutboundPacket join) {
        throw new UnsupportedOperationException("finishBatch");
    }

    // --- Causal scope API (U1, 13.19) ---

    /**
     * Opens a new causal scope and returns its opaque handle (U1, 13.19). The session allocates
     * scope identities monotonically and never reuses them.
     */
    default CausalScope openScope() {
        throw new UnsupportedOperationException("openScope");
    }

    /**
     * Registers one update inside the live scope, returning an opaque token (U1, 13.19). The token
     * is resolved by {@link #sendCovered} (assignment) or {@link #suppressUpdate} (suppression).
     */
    default UpdateToken registerUpdate(CausalScope scope) {
        throw new UnsupportedOperationException("registerUpdate");
    }

    /**
     * Submits one GAME packet covered by the registered update, resolving the token by assignment
     * (U1, 13.19). The token is consumed: it can never later be suppressed or re-resolved. A token
     * that is foreign, already resolved, suppressed or retired is rejected with an
     * {@link IllegalArgumentException} before any sequence is assigned (U2).
     */
    default SentPacket sendCovered(UpdateToken token, ProtocolState state, int packetId, byte[] frame,
            byte[] uncompressedPacket) {
        throw new UnsupportedOperationException("sendCovered");
    }

    /**
     * Removes the registered update from coverage: it has no wire frontier and is never awaited
     * (U1, 13.19). Valid only for tokens that were never assigned.
     */
    default void suppressUpdate(UpdateToken token) {
        throw new UnsupportedOperationException("suppressUpdate");
    }

    /**
     * Marks a scope as complete, the explicit producer-completeness event (U1, 13.19). Accepted
     * only after all registered tokens resolve; afterwards neither registration nor assignment is legal.
     */
    default void completeScope(CausalScope scope) {
        throw new UnsupportedOperationException("completeScope");
    }

    /**
     * Registers a live or completed scope as covering {@code ackId} (U1, 13.19). Callers must
     * register every covering scope before ACK resolution.
     */
    default void coverScope(long ackId, CausalScope scope) {
        throw new UnsupportedOperationException("coverScope");
    }

    /**
     * Seals an ack's coverage membership: the producer declares no additional covering scopes will
     * arise (U1, 13.19). Idempotent. {@code takeAck} resolution requires a sealed set plus all
     * covering scopes complete; empty coverage alone never establishes readiness.
     */
    default void sealAckCoverage(long ackId) {
        throw new UnsupportedOperationException("sealAckCoverage");
    }

    /**
     * Opaque handle for one causal scope, allocated monotonically by the session's tracker and never
     * reused (U1, 13.19). Carries no game coordinates or prediction numbers; identity is an internal
     * token bound to the tracker/session that issued it. Two sessions' local ids may collide, but
     * their handles never compare equal and a foreign handle is rejected by every operation.
     */
    final class CausalScope {
        private final Object owner;
        private final long id;

        CausalScope(Object owner, long id) {
            this.owner = owner;
            this.id = id;
        }

        long id() {
            return id;
        }

        Object owner() {
            return owner;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CausalScope)) {
                return false;
            }
            CausalScope other = (CausalScope) o;
            return id == other.id && owner == other.owner;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(owner) + Long.hashCode(id);
        }

        @Override
        public String toString() {
            return "CausalScope{" + id + "}";
        }
    }

    /**
     * Opaque handle for one update registered inside a causal scope (U1, 13.19). Allocated
     * monotonically per session's tracker and never reused. Once resolved by {@link #sendCovered}
     * or {@link #suppressUpdate} it is consumed and cannot be reused or re-resolved. The handle is
     * bound to its owning tracker/session like {@link CausalScope}.
     */
    final class UpdateToken {
        private final Object owner;
        private final long id;

        UpdateToken(Object owner, long id) {
            this.owner = owner;
            this.id = id;
        }

        long id() {
            return id;
        }

        Object owner() {
            return owner;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof UpdateToken)) {
                return false;
            }
            UpdateToken other = (UpdateToken) o;
            return id == other.id && owner == other.owner;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(owner) + Long.hashCode(id);
        }

        @Override
        public String toString() {
            return "UpdateToken{" + id + "}";
        }
    }
}
