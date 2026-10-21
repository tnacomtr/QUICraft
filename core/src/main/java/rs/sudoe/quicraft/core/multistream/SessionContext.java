// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/**
 * The negotiated shape of one {@code /3} connection (docs/protocol.md 13.2-13.3): the selected
 * shard count N and the N+3 logical streams it implies. Created once, when the preambles have
 * been exchanged, and never changed afterwards. Holds no connection state, so any thread may read it.
 */
public final class SessionContext {
    /** The shard count of a session that never negotiated one: the single-shard default of 12.8. */
    public static final int DEFAULT_SHARD_COUNT = 1;

    private final int shardCount;

    private SessionContext(int shardCount) {
        this.shardCount = shardCount;
    }

    /**
     * The context for an already selected N.
     *
     * @throws IllegalArgumentException if N is outside 1..5 (local misuse, never a wire event)
     */
    public static SessionContext of(int shardCount) {
        LogicalStream.validateShardCount(shardCount);
        return new SessionContext(shardCount);
    }

    /**
     * Server side (13.2): selects N from the client's parsed offer, the configured maximum clamped
     * against the offer, preferring {@code preference}.
     *
     * @throws MultistreamProtocolException with 0x10 when the offer and the configured maximum share
     *     no shard count (never clamped outside the offer)
     */
    public static SessionContext select(PreambleV2.Parser offer, int configuredMax, int preference)
            throws MultistreamProtocolException {
        return of(offer.select(configuredMax, preference));
    }

    /**
     * Client side (13.2): accepts the server's reply, which must name one shard count in both
     * range fields and must lie inside the client's own offer.
     *
     * @param selectedMin the reply's first range field
     * @param selectedMax the reply's second range field
     * @param offerMin the client's offered minimum (1 in the draft)
     * @param offerMax the client's offered maximum (5)
     * @throws MultistreamProtocolException with 0x1 when the two fields differ or name no legal
     *     shard count, and with 0x10 when the selection lies outside the offer
     */
    public static SessionContext accept(int selectedMin, int selectedMax, int offerMin, int offerMax)
            throws MultistreamProtocolException {
        if (offerMin < LogicalStream.MIN_CHUNKS || offerMax > LogicalStream.MAX_CHUNKS || offerMin > offerMax) {
            throw new IllegalArgumentException("offer range " + offerMin + ".." + offerMax);
        }
        if (selectedMin != selectedMax || selectedMin < LogicalStream.MIN_CHUNKS
                || selectedMin > LogicalStream.MAX_CHUNKS) {
            throw new MultistreamProtocolException(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                    "13.2", "selection " + selectedMin + ".." + selectedMax + " is not one shard count");
        }
        if (selectedMin < offerMin || selectedMin > offerMax) {
            throw new MultistreamProtocolException(MultistreamProtocolException.CLOSE_NO_COMMON_SHARDS,
                    "13.2", "selection " + selectedMin + " lies outside the offer " + offerMin + ".." + offerMax);
        }
        return of(selectedMin);
    }

    /** The selected shard count N, 1..5. */
    public int shardCount() {
        return shardCount;
    }

    /** The number of logical streams, N+3: CONTROL, REALTIME, one per chunk shard and the ACK stream (13.3). */
    public int logicalStreamCount() {
        return shardCount + 3;
    }

    /** The logical index of the ACK stream, N+2 (13.3). */
    public int ackIndex() {
        return shardCount + 2;
    }

    /** The QUIC stream id of a logical index (13.3). */
    public long quicIdOf(int logicalIndex) {
        return LogicalStream.quicId(logicalIndex, shardCount);
    }

    /** The logical index of a permitted QUIC stream id (13.3). */
    public int logicalIndexOf(long quicId) {
        return LogicalStream.logicalIndex(quicId, shardCount);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionContext && ((SessionContext) other).shardCount == shardCount;
    }

    @Override
    public int hashCode() {
        return shardCount;
    }

    @Override
    public String toString() {
        return "SessionContext{N=" + shardCount + "}";
    }
}
