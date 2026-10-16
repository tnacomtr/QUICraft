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

    @Test void byteSlotAcceptsExactlyTwoMebibytesPlus128AndRejectsOneMore() {
        assertEquals(2L * 1024 * 1024 + 128, ReceiveSlots.BYTES_PER_STREAM);
        ReceiveSlots slots = new ReceiveSlots(1);
        assertFalse(slots.admitHead(0, ReceiveSlots.BYTES_PER_STREAM + 1, 1), "+1 byte must be refused");
        assertFalse(slots.holdsHead(0));
        assertTrue(slots.admitHead(0, ReceiveSlots.BYTES_PER_STREAM, 1), "exact byte limit must be accepted");
        assertEquals(ReceiveSlots.BYTES_PER_STREAM, slots.heldBytes(0));
    }

    @Test void recordSlotAcceptsExactly128AndRejectsOneMore() {
        assertEquals(128, ReceiveSlots.RECORDS_PER_STREAM);
        ReceiveSlots slots = new ReceiveSlots(1);
        assertFalse(slots.admitHead(1, 1, ReceiveSlots.RECORDS_PER_STREAM + 1), "129 records must be refused");
        assertFalse(slots.holdsHead(1));
        assertTrue(slots.admitHead(1, 1, ReceiveSlots.RECORDS_PER_STREAM), "128 records must be accepted");
        assertEquals(ReceiveSlots.RECORDS_PER_STREAM, slots.heldRecords(1));
    }

    @Test void workspaceAcceptsExactly8MebibytesAndRejectsOneByteMore() {
        assertEquals(8L * 1024 * 1024, ReceiveSlots.EXECUTION_WORKSPACE);
        ReceiveSlots slots = new ReceiveSlots(1);
        assertFalse(slots.reserveWorkspace(ReceiveSlots.EXECUTION_WORKSPACE + 1));
        assertEquals(0, slots.workspaceInUse(), "a refused reservation reserves nothing");
        assertTrue(slots.reserveWorkspace(ReceiveSlots.EXECUTION_WORKSPACE));
        assertFalse(slots.reserveWorkspace(1));
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
