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
        assertEquals(21, LogicalStream.quicId(7, 6));
        assertEquals(7, LogicalStream.logicalIndex(21, 6));
        for (int i=0; i<8; i++) assertEquals(i, LogicalStream.logicalIndex(LogicalStream.quicId(i, 6), 6));
    }
    @Test void rejectsInvalidNegotiationAndIds() {
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.validateShardCount(0));
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.validateShardCount(7));
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.quicId(3, 1));
        assertThrows(IllegalArgumentException.class, () -> LogicalStream.logicalIndex(2, 6));
    }
}
