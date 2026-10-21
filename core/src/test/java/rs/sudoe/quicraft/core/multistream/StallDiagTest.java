// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Trigger, rate limit, line cap and formatting of the /3 stall watchdog, driven by an injected clock. */
class StallDiagTest {
    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong();
    private final List<String> sink = new ArrayList<String>();
    private final AtomicInteger detailCalls = new AtomicInteger();

    private StallDiag diag() {
        return new StallDiag(now::get, sink::add);
    }

    private List<String> details(String... lines) {
        detailCalls.incrementAndGet();
        return new ArrayList<String>(Arrays.asList(lines));
    }

    private void tickAt(StallDiag diag, long atNanos, boolean blocked, boolean queued, String... lines) {
        now.set(atNanos);
        diag.tick("client", blocked, queued, () -> details(lines));
    }

    @Test
    void offByDefaultTheWatchdogIsNotEnabled() {
        assumeTrue(System.getProperty("quicraft.v3.stalldiag") == null, "property set for this run");
        assertFalse(StallDiag.ENABLED);
    }

    @Test
    void noEventBeforeThreeSecondsWithoutCommit() {
        StallDiag diag = diag();
        tickAt(diag, 2_900_000_000L, true, true, "recv x");
        assertTrue(sink.isEmpty());
        assertEquals(0, detailCalls.get(), "details must not be built when nothing is due");
    }

    @Test
    void firstEventAtThreeSecondsWithBlockedHead() {
        StallDiag diag = diag();
        tickAt(diag, 3 * SECOND, true, false, "recv s0 head=5");
        assertEquals(2, sink.size());
        assertEquals("QUICraft v3 stall: side=client idleMs=3000 receiverBlocked=true senderQueued=false", sink.get(0));
        assertEquals("QUICraft v3 stall: side=client recv s0 head=5", sink.get(1));
        assertEquals(1, detailCalls.get());
    }

    @Test
    void queuedSenderAloneTriggers() {
        StallDiag diag = diag();
        tickAt(diag, 3 * SECOND, false, true, "send queuedRecords=4");
        assertEquals(2, sink.size());
        assertTrue(sink.get(0).endsWith("receiverBlocked=false senderQueued=true"));
    }

    @Test
    void noEventWithoutBlockedHeadOrQueuedBytes() {
        StallDiag diag = diag();
        tickAt(diag, 10 * SECOND, false, false, "never");
        assertTrue(sink.isEmpty());
        assertEquals(0, detailCalls.get());
    }

    @Test
    void progressResetsTheStallWindow() {
        StallDiag diag = diag();
        now.set(3_500_000_000L);
        diag.progress();
        tickAt(diag, 4 * SECOND, true, true);
        assertTrue(sink.isEmpty(), "0.5 s after a commit is not a stall");
        tickAt(diag, 6_500_000_000L, true, true);
        assertEquals(1, sink.stream().filter(s -> s.contains("idleMs=3000")).count());
    }

    @Test
    void eventsAreRateLimitedToOnePerTwoSeconds() {
        StallDiag diag = diag();
        tickAt(diag, 3 * SECOND, true, true);
        int afterFirst = sink.size();
        tickAt(diag, 4_900_000_000L, true, true);
        assertEquals(afterFirst, sink.size(), "no second event within 2 s");
        tickAt(diag, 5 * SECOND, true, true);
        assertEquals(afterFirst * 2, sink.size(), "second event exactly 2 s after the first");
    }

    @Test
    void connectionCapsAtTwentyLines() {
        StallDiag diag = diag();
        for (int i = 0; i < 10; i++) {
            tickAt(diag, (3 + 2L * i) * SECOND, true, true, "a", "b", "c");
        }
        assertEquals(StallDiag.MAX_LINES, sink.size());
        assertEquals(StallDiag.MAX_LINES, diag.linesWritten());
        tickAt(diag, 100 * SECOND, true, true, "after the cap");
        assertEquals(StallDiag.MAX_LINES, sink.size());
    }

    @Test
    void longLinesAreCutToTheLimit() {
        StallDiag diag = diag();
        String huge = new String(new char[5000]).replace('\0', 'x');
        tickAt(diag, 3 * SECOND, true, false, huge);
        String cut = sink.get(1);
        assertEquals(StallDiag.MAX_LINE_CHARS, cut.length());
        assertTrue(cut.endsWith("..."));
    }
}
