// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReceiveSlotsTest {
    @Test void eachStreamHoldsOneHeadAndSlotsAreIndependent() {
        ReceiveSlots slots = new ReceiveSlots(6);
        for (int stream = 0; stream < 8; stream++) {
            assertTrue(slots.admitHead(stream, ReceiveSlots.BYTES_PER_STREAM, ReceiveSlots.RECORDS_PER_STREAM));
            assertFalse(slots.admitHead(stream, 1, 1), "second head on stream " + stream);
        }
        slots.releaseHead(3);
        assertFalse(slots.holdsHead(3));
        assertTrue(slots.holdsHead(4), "releasing one stream must not free another");
    }

    @Test void unitsLargerThanTheSlotAreRefusedWithoutReservation() {
        ReceiveSlots slots = new ReceiveSlots(1);
        assertFalse(slots.admitHead(0, ReceiveSlots.BYTES_PER_STREAM + 1, 1));
        assertFalse(slots.admitHead(0, 1, ReceiveSlots.RECORDS_PER_STREAM + 1));
        assertFalse(slots.holdsHead(0));
    }

    @Test void aggregateWireCeilingAtSixChunksIsSixteenMebibytesPlusOneKibibyte() {
        long total = (6 + 2) * ReceiveSlots.BYTES_PER_STREAM;
        assertEquals(16L * 1024 * 1024 + 1024, total);
    }

    @Test void executionWorkspaceIsSharedAndFailsClosed() {
        ReceiveSlots slots = new ReceiveSlots(2);
        assertTrue(slots.reserveWorkspace(ReceiveSlots.EXECUTION_WORKSPACE));
        assertFalse(slots.reserveWorkspace(1));
        slots.releaseWorkspace(ReceiveSlots.EXECUTION_WORKSPACE);
        assertEquals(0, slots.workspaceInUse());
    }
}
