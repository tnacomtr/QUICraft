// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ReceiveAdmissionTest {
    @Test void eachLogicalStreamHasIndependentSlotAndRecords() {
        ReceiveSlots slots = new ReceiveSlots(1);
        for (int stream = 0; stream < 3; stream++) {
            assertTrue(slots.reserve(stream, ReceiveSlots.BYTES_PER_STREAM));
            assertFalse(slots.reserve(stream, 1));
            slots.release(stream, ReceiveSlots.BYTES_PER_STREAM);
        }
        for (int stream = 0; stream < 3; stream++) {
            for (int i = 0; i < 128; i++) assertTrue(slots.reserve(stream, 1));
            assertFalse(slots.reserve(stream, 1));
        }
        assertTrue(slots.reserveWorkspace(ReceiveSlots.EXECUTION_WORKSPACE));
        assertFalse(slots.reserveWorkspace(1));
    }

    @Test void serverPartitionProtectsRealtimeWireSlot() {
        ServerReadPartition partition = new ServerReadPartition(6);
        assertEquals(7667, partition.totalLimit());
        assertEquals(5619, partition.controlLimit());
        assertTrue(partition.reserve(LogicalStream.REALTIME, 512));
        assertFalse(partition.reserve(LogicalStream.REALTIME, 513));
        assertTrue(partition.reserve(LogicalStream.CONTROL, partition.controlLimit()));
        assertFalse(partition.reserve(LogicalStream.CONTROL, 1));
    }
}
