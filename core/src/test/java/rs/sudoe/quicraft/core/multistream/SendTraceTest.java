// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SendTraceTest {
    @Test void nestedBridgeScopesRestoreTheOuterWriteAndClearOnExit() {
        Long outer = SendTrace.beginGameWrite(100);
        try {
            assertEquals(100, SendTrace.gameWriteStart(999));
            Long inner = SendTrace.beginGameWrite(200);
            try {
                assertEquals(200, SendTrace.gameWriteStart(999));
            } finally {
                SendTrace.endGameWrite(200, inner);
            }
            assertEquals(100, SendTrace.gameWriteStart(999));
        } finally {
            SendTrace.endGameWrite(100, outer);
        }
        assertEquals(999, SendTrace.gameWriteStart(999));
        SendTrace.beginGameWrite(0);
        assertEquals(999, SendTrace.gameWriteStart(999));
    }

    @Test void selectionSlicesDoNotDoubleDecrementQueueAndEachWriteIsOneFlush() {
        SendTrace trace = new SendTrace();
        trace.enqueued(2, 1_000_000, 2_000_000);
        trace.enqueued(2, 1_000_000, 2_000_000);
        trace.selected(2, true, false, 1_000_000, 2_000_000, 3_000_000);
        trace.written(2, 100, 3_000_000, 1_000_000, 4_000_000);
        trace.selected(2, false, true, 1_000_000, 2_000_000, 5_000_000);
        trace.written(2, 50, 5_000_000, 1_000_000, 6_000_000);
        String summary = trace.summary(7_000_000);
        assertTrue(summary.contains("queuedPeakPerStream=[0, 0, 2, 0, 0, 0, 0, 0]"), summary);
        assertTrue(summary.contains("notFullySelectedPerStream=[0, 0, 1, 0, 0, 0, 0, 0]"), summary);
        assertTrue(summary.contains("gameToSelection={n=1"), summary);
        assertTrue(summary.contains("selectionToWrite={n=2"), summary);
        assertTrue(summary.contains("stream2={writes=2,flushes=2,bytes=150,writesPerFlush=1,meanBytesPerFlush=75"), summary);
    }

    @Test void unwritableAndGrantSnapshotsIncludeOpenIntervalsWithoutDoubleCounting() {
        SendTrace trace = new SendTrace();
        trace.writable(2, true, false, false, false, 1_000_000);
        trace.writable(2, true, false, true, true, 3_000_000);
        trace.grantWaiting(true, 1_000_000);
        trace.grantWaiting(false, 3_000_000);
        trace.tickStarted(1_000_000);
        trace.tickStarted(2_000_000);
        trace.tick(0, true);
        String summary = trace.summary(5_000_000);
        assertTrue(summary.contains("streamUnwritable={totalMs=4.0,maxMs=4.0}"), summary);
        assertTrue(summary.contains("parentUnwritable={totalMs=2.0,maxMs=2.0}"), summary);
        assertTrue(summary.contains("writeGate={totalMs=2.0,maxMs=2.0}"), summary);
        assertTrue(summary.contains("poolGrantWaiting={totalMs=2.0,maxMs=2.0}"), summary);
        assertTrue(summary.contains("ticks=1 writesInsideTick=0 ticksAwaitingGrant=1"), summary);
        assertEquals(summary, trace.summary(5_000_000));
    }
}
