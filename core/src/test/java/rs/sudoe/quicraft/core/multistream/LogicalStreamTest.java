// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogicalStreamTest {
    @Test void mapsMinimumNegotiation() {
        assertEquals(0, LogicalStream.quicId(0, 1));
        assertEquals(4, LogicalStream.quicId(1, 1));
        assertEquals(1, LogicalStream.quicId(2, 1));
        assertEquals(2, LogicalStream.logicalIndex(1, 1));
    }
    @Test void mapsMaximumNegotiation() {
        // N=5: CONTROL, REALTIME, shards 2..6 and the ACK stream 7 = the sixth server-opened stream, QUIC id 21.
        assertEquals(21, LogicalStream.quicId(7, 5));
        assertEquals(7, LogicalStream.logicalIndex(21, 5));
        assertEquals(7, LogicalStream.ackIndex(5));
        assertEquals(8, LogicalStream.streamCount(5));
        for (int i=0; i<8; i++) assertEquals(i, LogicalStream.logicalIndex(LogicalStream.quicId(i, 5), 5));
    }
    @Test void theAckStreamFollowsTheShardsForEveryN() {
        for (int n = 1; n <= 5; n++) {
            int ack = LogicalStream.ackIndex(n);
            assertEquals(n + 2, ack);
            assertEquals(1L + 4L * n, LogicalStream.quicId(ack, n), "ACK is QUIC id 1+4N");
            assertEquals(ack, LogicalStream.logicalIndex(1L + 4L * n, n));
            assertEquals(n + 3, LogicalStream.streamCount(n));
            // The id after the ACK stream is outside the session, and shard ids are unchanged.
            final int count = n;
            assertThrows(IllegalArgumentException.class, () -> LogicalStream.logicalIndex(5L + 4L * count, count));
            for (int shard = 2; shard < ack; shard++) assertEquals(1L + 4L * (shard - 2), LogicalStream.quicId(shard, n));
        }
    }
    @Test void rejectsInvalidNegotiationAndIds() {
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.validateShardCount(0));
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.validateShardCount(6));
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.quicId(4, 1));
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.logicalIndex(9, 1));
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.logicalIndex(25, 5));
    }
}
