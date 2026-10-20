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
}
