// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ServerReadPartitionTest {
    @Test void atFiveChunksAndTheAckStreamProtectedAreaIsSevenThousandSixHundredSixtySevenBytes() {
        ServerReadPartition partition = new ServerReadPartition(5);
        assertEquals(7667, partition.protectedBytes());
        assertEquals(5459, partition.controlAllowance());
        assertTrue(partition.controlAllowance() <= partition.protectedBytes() - 2048);
        assertEquals(ServerReadPartition.SHARE, ServerReadPartition.NATIVE_EXPOSURE + (2 * (5 + 1) + 1)
                + partition.protectedBytes() + ServerReadPartition.REALTIME_WIRE_CAP);
    }

    @Test void controlAllowanceRefusesExcessWhileRealtimeSliceStaysProtected() {
        ServerReadPartition partition = new ServerReadPartition(5);
        assertTrue(partition.reserve(LogicalStream.CONTROL, partition.controlAllowance()));
        assertFalse(partition.reserve(LogicalStream.CONTROL, 1), "CONTROL must use excess beyond its allowance");
        assertEquals(2048, partition.available(LogicalStream.REALTIME));
        assertTrue(partition.reserve(LogicalStream.REALTIME, 2048));
        assertFalse(partition.reserve(LogicalStream.REALTIME, 1));
    }

    @Test void prefixSliceIsFixedAndSeparate() {
        ServerReadPartition partition = new ServerReadPartition(5);
        assertTrue(partition.reservePrefix(ServerReadPartition.PREFIX_SLICE));
        assertFalse(partition.reservePrefix(1));
        partition.releasePrefix(ServerReadPartition.PREFIX_SLICE);
        assertTrue(partition.reservePrefix(1));
    }

    @Test void readGrantIsTheMinimumOfNativeCapAvailableAndUnitBytes() {
        ServerReadPartition partition = new ServerReadPartition(5);
        assertEquals(5459, partition.readGrant(LogicalStream.CONTROL, 1 << 20), "capped by the CONTROL allowance");
        assertEquals(2048, partition.readGrant(LogicalStream.REALTIME, 1 << 20), "capped by the REALTIME slice");
        assertEquals(100, partition.readGrant(LogicalStream.CONTROL, 100));
        assertTrue(partition.reserve(LogicalStream.CONTROL, 5000));
        assertEquals(459, partition.readGrant(LogicalStream.CONTROL, 100000));
        assertEquals(0, partition.readGrant(LogicalStream.CONTROL, 0));
    }

    @Test void realtimeRecordCapsAreHardLimits() {
        assertTrue(ServerReadPartition.realtimeRecordLegal(510, 0, 1024));
        assertFalse(ServerReadPartition.realtimeRecordLegal(511, 0, 100));
        assertFalse(ServerReadPartition.realtimeRecordLegal(100, 1025, 100));
        assertFalse(ServerReadPartition.realtimeRecordLegal(100, 0, 1025));
    }

    @Test void protectedRealtimeReadIsServedBeforeControl() {
        ServerReadPartition partition = new ServerReadPartition(5);
        assertEquals(LogicalStream.REALTIME, partition.nextReadStream(100, 100));
        assertTrue(partition.reserve(LogicalStream.REALTIME, 2048));
        assertEquals(LogicalStream.CONTROL, partition.nextReadStream(100, 100), "full REALTIME slice yields to CONTROL");
        assertEquals(LogicalStream.CONTROL, partition.nextReadStream(0, 100));
    }

    @Test void readGrantsComeOnlyFromProtectedPAndNeverFromExcess() {
        ServerReadPartition partition = new ServerReadPartition(5);
        assertTrue(partition.reserve(LogicalStream.CONTROL, partition.controlAllowance()));
        // CONTROL's in-share allowance is exhausted: no protected grant, so the caller must use excess.
        assertEquals(0, partition.readGrant(LogicalStream.CONTROL, 1000));
        assertEquals(-1, partition.nextReadStream(0, 1000));
        // REALTIME still has its own protected slice, which CONTROL excess cannot borrow.
        assertEquals(LogicalStream.REALTIME, partition.nextReadStream(1000, 1000));
        assertEquals(2048, partition.readGrant(LogicalStream.REALTIME, 1 << 20));
        // Every grant reserved is accounted against P: the sum never exceeds the protected area.
        int granted = partition.controlAllowance() + partition.readGrant(LogicalStream.REALTIME, 1 << 20);
        assertTrue(granted <= partition.protectedBytes());
    }

    @Test void chunkStreamsGetNoServerboundAdmissionFromThePartition() {
        ServerReadPartition partition = new ServerReadPartition(2);
        assertFalse(partition.reserve(2, 1));
        assertEquals(0, partition.available(2));
    }
}
