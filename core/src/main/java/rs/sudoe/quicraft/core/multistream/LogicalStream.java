// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** /3 logical stream indices and QUIC ids (docs/protocol.md 13.2-13.3). */
public final class LogicalStream {
    public static final int CONTROL = 0;
    public static final int REALTIME = 1;
    public static final int MIN_CHUNKS = 1;
    public static final int MAX_CHUNKS = 6;
    private LogicalStream() {}

    /** Validate the negotiated shard count N. */
    public static void validateShardCount(int n) {
        if (n < MIN_CHUNKS || n > MAX_CHUNKS) throw new IllegalArgumentException("N must be 1..6");
    }

    /** Translate a logical index to its QUIC stream id. */
    public static long quicId(int logicalIndex, int n) {
        validateShardCount(n);
        if (logicalIndex < 0 || logicalIndex >= n + 2) throw new IllegalArgumentException("logical index");
        if (logicalIndex == CONTROL) return 0;
        if (logicalIndex == REALTIME) return 4;
        return 1L + 4L * (logicalIndex - 2);
    }

    /** Translate a permitted QUIC stream id to its logical index. */
    public static int logicalIndex(long quicId, int n) {
        validateShardCount(n);
        if (quicId == 0) return CONTROL;
        if (quicId == 4) return REALTIME;
        if (quicId >= 1 && (quicId - 1) % 4 == 0) {
            long index = 2 + (quicId - 1) / 4;
            if (index < n + 2) return (int) index;
        }
        throw new IllegalArgumentException("QUIC stream id");
    }
}
