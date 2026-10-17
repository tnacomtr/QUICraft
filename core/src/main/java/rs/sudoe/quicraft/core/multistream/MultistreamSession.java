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
}
