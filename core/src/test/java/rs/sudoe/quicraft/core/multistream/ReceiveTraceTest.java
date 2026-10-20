// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceiveTraceTest {
    @Test void recordsImmediateAndHeldAcksWithoutReleasingAnything() {
        ReceiveTrace trace = new ReceiveTrace();
        trace.delivered(100);
        trace.backlog(1, 100);
        trace.acknowledged(1_000_000, 0, 2_000_000);
        trace.delivered(300);
        trace.backlog(2, 400);
        trace.backlog(0, 0);
        trace.acknowledged(3_000_000, 4_000_000, 10_000_000);
        String summary = trace.summary();
        assertTrue(summary.contains("units=2 immediateAcks=1 heldAcks=1"), summary);
        assertTrue(summary.contains("peakUnrunPackets=2 peakUnrunBytes=400"), summary);
        assertTrue(summary.contains("decodedBytes=400 meanBytesPerUnit=200 maxBytesPerUnit=300"), summary);
        assertTrue(summary.contains("heldTime={n=1,p50UpperMs=6.1,p95UpperMs=6.1,maxMs=6.0,totalMs=6.0}"), summary);
    }

    @Test void percentilesUseAllObservationsAndOverflowRemainsBounded() {
        ReceiveTrace.Latencies histogram = new ReceiveTrace.Latencies();
        for (int i = 0; i < 90; i++) histogram.add(1_000_000);
        for (int i = 0; i < 10; i++) histogram.add(900_000_000);
        assertEquals("n=100,p50UpperMs=1.1,p95UpperMs=900.0,maxMs=900.0,totalMs=9090.0", histogram.summary());
    }

    @Test void emptyHistogramAndTimingSnapshotAreDefined() {
        ReceiveTrace trace = new ReceiveTrace();
        assertTrue(trace.summary().contains("units=0 immediateAcks=0 heldAcks=0"));
        trace.ackEventLoopQueue.add(100_000);
        trace.ackToNextDelivery.add(2_000_000);
        assertTrue(trace.timingSummary().contains("ackEventLoopQueue={n=1"));
        assertTrue(trace.timingSummary().contains("ackToNextDelivery={n=1"));
    }

    @Test void starvationReasonsPartitionCompletedGapsAndDoNotCountIdleTail() {
        ReceiveTrace.Waits waits = new ReceiveTrace.Waits();
        waits.start(1_000_000);
        waits.state(2_000_000, ReceiveTrace.WaitReason.NO_LOCAL_BYTES);
        waits.state(5_000_000, ReceiveTrace.WaitReason.NO_LOCAL_BYTES); // same state keeps contiguous max
        waits.state(7_000_000, ReceiveTrace.WaitReason.PARTIAL_UNIT);
        waits.finish(9_000_000);
        waits.start(10_000_000); // no next delivery: pending gap must not inflate completed-gap totals
        waits.state(11_000_000, ReceiveTrace.WaitReason.NO_LOCAL_BYTES);
        String summary = waits.summary(99_000_000);
        assertTrue(summary.contains("ACK_EVENT_LOOP={totalMs=1.0,maxSegmentMs=1.0}"), summary);
        assertTrue(summary.contains("NO_LOCAL_BYTES={totalMs=5.0,maxSegmentMs=5.0}"), summary);
        assertTrue(summary.contains("PARTIAL_UNIT={totalMs=2.0,maxSegmentMs=2.0}"), summary);
        assertTrue(summary.contains("pendingReason=NO_LOCAL_BYTES pendingSegmentMs=88.0"), summary);
        assertEquals(summary, waits.summary(99_000_000), "snapshot does not modify accounting");
    }

    @Test void countsUnitsByLogicalStream() {
        ReceiveTrace trace = new ReceiveTrace();
        trace.deliveredOnStream(0);
        trace.deliveredOnStream(2);
        trace.deliveredOnStream(2);
        assertTrue(trace.timingSummary().contains("unitsPerStream=[1, 0, 2, 0, 0, 0, 0, 0]"));
    }
}
