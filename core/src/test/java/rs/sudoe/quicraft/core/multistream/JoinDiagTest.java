// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The /3 join marks: order, the disabled switch, one line per connection, and failing sinks. */
class JoinDiagTest {
    private static final long MILLI = 1_000_000L;

    private final AtomicLong now = new AtomicLong();
    private final List<String> sink = new ArrayList<String>();

    private JoinDiag diag(JoinDiag.Side side) {
        return JoinDiag.create(side, true, now::get, sink::add);
    }

    @Test
    void offByDefaultNothingIsRecorded() {
        assumeTrue(System.getProperty(JoinDiag.PROPERTY) == null, "property set for this run");
        assertFalse(JoinDiag.ENABLED, "the marks are off unless the property asks for them");
        assertNull(JoinDiag.create(JoinDiag.Side.CLIENT), "off means no instance at all");
        JoinDiag.mark(null, "anything");
        JoinDiag.finish(null);
        assertTrue(sink.isEmpty());
    }

    @Test
    void marksAreFormattedInArrivalOrderSinceTheFirst() {
        JoinDiag diag = diag(JoinDiag.Side.CLIENT);
        now.set(10 * MILLI);
        JoinDiag.mark(diag, "connect start");
        now.set(13 * MILLI);
        JoinDiag.mark(diag, "QUIC handshake done");
        now.set(40 * MILLI);
        JoinDiag.mark(diag, "OFFER received");
        JoinDiag.finish(diag);

        assertEquals(1, sink.size());
        assertEquals("QUICraft v3 join phases (client): connect start=0 QUIC handshake done=3 OFFER received=30",
                sink.get(0));
    }

    @Test
    void theServerSideNamesItself() {
        JoinDiag diag = diag(JoinDiag.Side.SERVER);
        JoinDiag.mark(diag, "connection accepted");
        JoinDiag.finish(diag);

        assertEquals("QUICraft v3 join phases (server): connection accepted=0", sink.get(0));
    }

    @Test
    void oneLineIsWrittenOnceWhateverComesAfter() {
        JoinDiag diag = diag(JoinDiag.Side.SERVER);
        JoinDiag.mark(diag, "connection accepted");
        JoinDiag.finish(diag);
        JoinDiag.mark(diag, "late mark");
        JoinDiag.finish(diag);
        JoinDiag.finish(diag);

        assertEquals(1, sink.size(), "the line is emitted once per connection");
        assertFalse(sink.get(0).contains("late mark"));
        assertEquals(1, diag.markCount(), "marks after the line are dropped");
    }

    @Test
    void theLineIsStillWrittenWhenNothingWasMarked() {
        JoinDiag diag = diag(JoinDiag.Side.CLIENT);
        JoinDiag.finish(diag);

        assertEquals(1, sink.size());
        assertEquals("QUICraft v3 join phases (client):", sink.get(0));
    }

    @Test
    void aThrowingLoggerSinkNeverPropagatesAndIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        JoinDiag diag = JoinDiag.create(JoinDiag.Side.CLIENT, true, now::get, line -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("sink");
        });
        assertNotNull(diag);
        JoinDiag.mark(diag, "connect start");
        JoinDiag.finish(diag);
        JoinDiag.finish(diag);

        assertEquals(1, attempts.get(), "a throwing sink is called once and never again");
    }

    @Test
    void aThrowingClockNeverPropagatesAndSilencesTheConnection() {
        AtomicInteger reads = new AtomicInteger();
        JoinDiag diag = JoinDiag.create(JoinDiag.Side.CLIENT, true, () -> {
            if (reads.incrementAndGet() > 1) {
                throw new IllegalStateException("clock");
            }
            return now.get();
        }, sink::add);
        JoinDiag.mark(diag, "connect start");
        JoinDiag.mark(diag, "handshake"); // the clock throws here
        JoinDiag.finish(diag);

        assertTrue(sink.isEmpty(), "a failed clock keeps the connection's marks out of the stream");
    }

    @Test
    void aNullNameIsIgnored() {
        JoinDiag diag = diag(JoinDiag.Side.CLIENT);
        JoinDiag.mark(diag, null);
        JoinDiag.mark(diag, "connect start");
        JoinDiag.finish(diag);

        assertEquals("QUICraft v3 join phases (client): connect start=0", sink.get(0));
    }
}
