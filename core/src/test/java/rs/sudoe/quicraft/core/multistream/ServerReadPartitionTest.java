// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ServerReadPartitionTest {
    @Test void atSixChunksProtectedAreaIsSevenThousandSixHundredSixtySevenBytes() {
        ServerReadPartition partition = new ServerReadPartition(6);
        assertEquals(7667, partition.protectedBytes());
        assertEquals(5459, partition.controlAllowance());
        assertTrue(partition.controlAllowance() <= partition.protectedBytes() - 2048);
        assertEquals(ServerReadPartition.SHARE, ServerReadPartition.NATIVE_EXPOSURE + (2 * 6 + 1)
                + partition.protectedBytes() + ServerReadPartition.REALTIME_WIRE_CAP);
    }

    @Test void controlAllowanceRefusesExcessWhileRealtimeSliceStaysProtected() {
        ServerReadPartition partition = new ServerReadPartition(6);
        assertTrue(partition.reserve(LogicalStream.CONTROL, partition.controlAllowance()));
        assertFalse(partition.reserve(LogicalStream.CONTROL, 1), "CONTROL must use excess beyond its allowance");
        assertEquals(2048, partition.available(LogicalStream.REALTIME));
        assertTrue(partition.reserve(LogicalStream.REALTIME, 2048));
        assertFalse(partition.reserve(LogicalStream.REALTIME, 1));
    }

    @Test void prefixSliceIsFixedAndSeparate() {
        ServerReadPartition partition = new ServerReadPartition(6);
        assertTrue(partition.reservePrefix(ServerReadPartition.PREFIX_SLICE));
        assertFalse(partition.reservePrefix(1));
        partition.releasePrefix(ServerReadPartition.PREFIX_SLICE);
        assertTrue(partition.reservePrefix(1));
    }

    @Test void readGrantIsTheMinimumOfNativeCapAvailableAndUnitBytes() {
        ServerReadPartition partition = new ServerReadPartition(6);
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

    @Test void chunkStreamsGetNoServerboundAdmissionFromThePartition() {
        ServerReadPartition partition = new ServerReadPartition(2);
        assertFalse(partition.reserve(2, 1));
        assertEquals(0, partition.available(2));
    }
}
