// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.RouteV2;
import rs.sudoe.quicraft.core.time.StallClock;

/** Serverbound serial driver: eligibility before inflation, one execution unit, terminal cleanup and END. */
class ServerReceiveDriverTest {
    private static final int PROTOCOL = 1;
    private static final int PID_CONTROL = 2;
    private static final int PID_REALTIME = 9;
    private static final int CLOSE_ORDERING = 0x11;
    private static final RouteV2.ServerboundLimits LIMITS = new RouteV2.ServerboundLimits(65536, 510, 8192, 5459);

    /** One serverbound session: a byte source, the pump and the driver, with the sink's codes recorded. */
    static final class Harness {
        final SessionReadPumpTest.Streams source = new SessionReadPumpTest.Streams(4, 1 << 20);
        final SessionReadPumpTest.Pools pools = new SessionReadPumpTest.Pools(1L << 20);
        final SessionReadPump pump;
        final SessionReceiveDriverTest.FakeGame game = new SessionReceiveDriverTest.FakeGame();
        final List<Integer> codes = new ArrayList<Integer>();
        final List<String> reasons = new ArrayList<String>();
        final long[] now = {0L};
        final CumulativeSource stalls = new CumulativeSource(now);
        final ServerReceiveDriver driver;

        Harness(boolean compressed) {
            this(compressed, false);
        }

        Harness(boolean compressed, boolean cumulative) {
            pump = new SessionReadPump(new ServerReadPartition(2), pools.node, pools.excess, "conn", "ip-a", null,
                    LIMITS, compressed, source);
            Map<Integer, RouteV2> routes = new HashMap<Integer, RouteV2>();
            routes.put(PID_CONTROL, new RouteV2(RouteV2.StreamLane.CONTROL, Dependency.ORDINARY,
                    null, null, false, null, null, null));
            routes.put(PID_REALTIME, new RouteV2(RouteV2.StreamLane.REALTIME, Dependency.ORDINARY,
                    null, null, false, null, null, null));
            SessionReceiveDriver.CloseSink sink = (code, reason) -> {
                codes.add(code);
                reasons.add(new String(reason, StandardCharsets.US_ASCII));
            };
            driver = cumulative
                    ? new ServerReceiveDriver(2, pump, (s, r, route) -> true,
                            new SessionReceiveDriverTest.RouteSourceImpl(routes), PROTOCOL, game, () -> now[0],
                            sink, stalls)
                    : new ServerReceiveDriver(2, pump, (s, r, route) -> true,
                            new SessionReceiveDriverTest.RouteSourceImpl(routes), PROTOCOL, game, () -> now[0],
                            sink);
        }

        void rounds(int n) throws Exception {
            for (int i = 0; i < n; i++) driver.receive();
        }
    }

    /** A compressed GAME record whose body is the zlib form of {@code packet}, with dependencies {@code deps}. */
    static byte[] compressedRec(int stream, long seq, List<Record.Dependency> deps, byte[] packet) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        VarInt.write(content, packet.length);
        byte[] z = SessionReadPumpTest.deflate(packet);
        content.write(z, 0, z.length);
        return RecordCodec.encodeRecord(new Record(stream, seq, 0, deps, RecordCodec.KIND_GAME,
                SessionReadPumpTest.inner(content.toByteArray())));
    }

    /** A stored GAME record in a compressed session: Data Length 0, so nothing is inflated (13.12). */
    static byte[] storedRec(int stream, long seq, List<Record.Dependency> deps, byte[] packet) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        VarInt.write(content, 0);
        content.write(packet, 0, packet.length);
        return RecordCodec.encodeRecord(new Record(stream, seq, 0, deps, RecordCodec.KIND_GAME,
                SessionReadPumpTest.inner(content.toByteArray())));
    }

    @Test void serverboundUnitRunsEndToEnd() throws Exception {
        Harness h = new Harness(false);
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_CONTROL));
        h.rounds(30);
        assertTrue(h.game.log.contains("enqueued 0:1"), h.game.log.toString());
        assertTrue(h.game.log.contains("deliver CONTROL 1s 1"), h.game.log.toString());
        h.driver.acknowledged(LogicalStream.CONTROL, 1);
        assertTrue(h.game.log.contains("applied 0:1"), h.game.log.toString());
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL), "applied releases the slot");
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse());
        assertTrue(h.codes.isEmpty(), h.codes.toString());
    }

    @Test void blockedCompressedHeadIsNeverInflated() throws Exception {
        Harness h = new Harness(true);
        // REALTIME goes first and holds the executor; the compressed CONTROL head waits on its apply.
        h.source.put(LogicalStream.REALTIME,
                storedRec(1, 1, Collections.<Record.Dependency>emptyList(), new byte[] {(byte) PID_REALTIME}));
        h.source.put(LogicalStream.CONTROL,
                compressedRec(0, 1, SessionReceiveDriverTest.dep(1, 1), new byte[] {(byte) PID_CONTROL}));
        h.rounds(30);
        assertTrue(h.game.log.contains("deliver REALTIME 1s 1"), h.game.log.toString());
        assertEquals(0, h.pump.inflations(), "a blocked compressed head is never inflated");
        assertFalse(h.game.log.contains("enqueued 0:1"), h.game.log.toString());
        h.driver.acknowledged(LogicalStream.REALTIME, 1);
        h.rounds(30);
        assertEquals(1, h.pump.inflations(), "the head is inflated once it is eligible");
        assertTrue(h.game.log.contains("deliver CONTROL 1s 1"), h.game.log.toString());
        h.driver.acknowledged(LogicalStream.CONTROL, 1);
        assertTrue(h.codes.isEmpty(), h.codes.toString());
    }

    @Test void heldCommitPastBlockedHeadDeadlineNeverBlamesPeer() throws Exception {
        Harness h = new Harness(false);
        h.source.put(LogicalStream.REALTIME,
                SessionReceiveDriverTest.rec(1, 1, 0, Collections.<Record.Dependency>emptyList(), PID_REALTIME));
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, SessionReceiveDriverTest.dep(1, 1), PID_CONTROL));
        h.rounds(30);
        assertTrue(h.game.log.contains("deliver REALTIME 1s 1"));
        h.now[0] = TimeUnit.SECONDS.toNanos(11);
        h.rounds(1);
        assertEquals(Collections.singletonList(0x12), h.codes);
        assertEquals(Collections.singletonList("resource"), h.reasons,
                "our held commit must not emit unseen-prerequisite");
    }

    @Test void routeViolationClosesAndReleasesTheSlot() throws Exception {
        Harness h = new Harness(false);
        // A REALTIME route on the CONTROL stream fails the route check before enqueue.
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_REALTIME));
        h.rounds(30);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertEquals(0, h.game.deliveries());
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL));
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse());
    }

    @Test void closeDuringExecutionReleasesEverythingOnce() throws Exception {
        Harness h = new Harness(false);
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_CONTROL));
        h.rounds(30);
        assertTrue(h.game.log.contains("enqueued 0:1"), h.game.log.toString());
        assertTrue(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL));
        h.driver.close();
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL), "the receiver slot is released");
        assertNull(h.driver.receiver().head(LogicalStream.CONTROL), "the receiver retains no head after close");
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse());
        assertTrue(h.driver.closed());
        assertTrue(h.codes.isEmpty(), "an external close does not call the sink");
        h.driver.close();
        h.driver.receive();
        assertTrue(h.codes.isEmpty());
    }

    @Test void serverboundEndAppliesAsAFenceAndClosesCleanly() throws Exception {
        Harness h = new Harness(false);
        h.source.put(LogicalStream.CONTROL, SessionReceiveDriverTest.endWire(1));
        h.rounds(30);
        assertEquals(Collections.singletonList(0), h.codes);
        assertEquals(0, h.game.decodes, "END is never decoded");
        assertEquals(0, h.game.deliveries(), "END is never delivered");
    }

    @Test void cumulativeServerboundExpiryUsesTheSharedSourceAndSuppressesThePhrase() throws Exception {
        Harness h = new Harness(false, true);
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, SessionReceiveDriverTest.dep(1, 1), PID_CONTROL));
        h.rounds(30);
        assertNotNull(h.driver.receiver().head(LogicalStream.CONTROL), "the blocked head owns the wait");
        h.now[0] = TimeUnit.SECONDS.toNanos(20);
        h.stalls.excluded = TimeUnit.SECONDS.toNanos(20);
        h.rounds(1);
        assertTrue(h.codes.isEmpty(), "every second of the wait is excluded");
        h.now[0] = TimeUnit.SECONDS.toNanos(30);
        h.stalls.excluded = TimeUnit.SECONDS.toNanos(20);
        h.rounds(1);
        assertEquals(Collections.singletonList(0x12), h.codes);
        assertEquals(Collections.singletonList("resource"), h.reasons,
                "consumed stall time removes the phrase, so the generic resource reason is sent");
    }

    @Test void legacyServerboundDriverStillForwardsBoundaryStalls() throws Exception {
        Harness h = new Harness(false);
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, SessionReceiveDriverTest.dep(1, 1), PID_CONTROL));
        h.rounds(30);
        assertNotNull(h.driver.receiver().head(LogicalStream.CONTROL), "the blocked head owns the wait");
        h.driver.stallStarted(0);
        h.now[0] = TimeUnit.SECONDS.toNanos(10);
        h.driver.stallEnded(TimeUnit.SECONDS.toNanos(10));
        h.now[0] = TimeUnit.SECONDS.toNanos(20);
        h.rounds(1);
        assertEquals(Collections.singletonList(0x12), h.codes, "10 s excluded leaves 10 s active");
        assertEquals(Collections.singletonList("resource"), h.reasons, "consumed stall time blocks the phrase");
    }

    @Test void cumulativeServerboundDriverRejectsBoundaryHooksWithoutHeads() throws Exception {
        Harness h = new Harness(false, true);
        assertThrows(IllegalStateException.class, () -> h.driver.stallStarted(1L));
        assertThrows(IllegalStateException.class, () -> h.driver.stallEnded(1L));
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_CONTROL));
        h.rounds(30);
        assertTrue(h.game.log.contains("enqueued 0:1"), h.game.log.toString());
        h.driver.acknowledged(LogicalStream.CONTROL, 1);
        assertTrue(h.codes.isEmpty(), "the rejected hooks changed nothing");
    }

    @Test void invalidCumulativeSourcePropagatesFromTheServerboundDriver() throws Exception {
        Harness h = new Harness(false, true);
        h.stalls.valid = false;
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, SessionReceiveDriverTest.dep(1, 1), PID_CONTROL));
        assertThrows(StallClock.UnavailableException.class, () -> h.rounds(30));
        assertTrue(h.codes.isEmpty(), "an unavailable source is not converted to a close");
        assertFalse(h.driver.closed());
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL),
                "a rejected start charges no slot");
        h.driver.close();
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse());
    }

    /** A REALTIME GAME record of about 515 wire bytes (near the REALTIME cap); its packet id is the last byte. */
    static byte[] realtimeRecSized(long seq) {
        byte[] payload = new byte[500];
        payload[499] = (byte) PID_REALTIME;
        return RecordCodec.encodeRecord(new Record(LogicalStream.REALTIME, seq, 0,
                Collections.<Record.Dependency>emptyList(), RecordCodec.KIND_GAME, SessionReadPumpTest.inner(payload)));
    }

    /**
     * Commits {@code count} REALTIME records from sequence {@code from} at {@code second}, each applied through the
     * driver. Returns the next unused sequence. Each commit is qualifying progress for a CONTROL head that waits on
     * a far REALTIME sequence, so about 128 of them (64 KiB) renew that head's base deadline.
     */
    private static long commitRealtime(Harness h, long second, long from, int count) throws Exception {
        h.now[0] = TimeUnit.SECONDS.toNanos(second);
        long seq = from;
        for (int i = 0; i < count; i++, seq++) {
            // put() replaces the bytes but not the read position; the previous record was fully consumed.
            h.source.put(LogicalStream.REALTIME, realtimeRecSized(seq));
            h.source.pos[LogicalStream.REALTIME] = 0;
            String enqueued = "enqueued " + LogicalStream.REALTIME + ":" + seq;
            for (int r = 0; r < 100 && !h.game.log.contains(enqueued); r++) {
                h.rounds(1);
            }
            assertTrue(h.game.log.contains(enqueued), enqueued);
            h.driver.acknowledged(LogicalStream.REALTIME, seq);
        }
        return seq;
    }

    @Test void renewalThroughTheServerDriverThenSilenceClosesOnceAtTheNoProgressDeadline() throws Exception {
        Harness h = new Harness(false, true);
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, SessionReceiveDriverTest.dep(LogicalStream.REALTIME, 100000),
                        PID_CONTROL));
        h.rounds(30);
        assertNotNull(h.driver.receiver().head(LogicalStream.CONTROL), "the blocked head owns the wait");
        commitRealtime(h, 5, 1, 140);
        assertTrue(h.codes.isEmpty(), "about 70 KiB of qualifying commits renewed the base at 5 s");
        h.now[0] = TimeUnit.SECONDS.toNanos(14);
        h.rounds(1);
        assertTrue(h.codes.isEmpty(), "the renewed base runs to 15 s, not 10 s");
        h.now[0] = TimeUnit.SECONDS.toNanos(15);
        h.rounds(5);
        h.rounds(5);
        assertEquals(Collections.singletonList(0x12), h.codes, "exactly one close");
        // Every renewing REALTIME commit was an executor-held prerequisite (13.12): generic, and never counted.
        assertEquals(Collections.singletonList("resource"), h.reasons);
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse(), "the close returns the workspace reservations");
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL), "the blocked head's slot is released");
    }

    @Test void absoluteRetentionClosesOnceGenericWhileRenewalsContinueOnTheServerDriver() throws Exception {
        Harness h = new Harness(false, true);
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, SessionReceiveDriverTest.dep(LogicalStream.REALTIME, 100000),
                        PID_CONTROL));
        h.rounds(30);
        long seq = 1;
        for (int second = 5; second <= 115; second += 5) {
            seq = commitRealtime(h, second, seq, 140);
            assertTrue(h.codes.isEmpty(), "qualifying commits renewed the base at " + second + " s");
        }
        h.now[0] = TimeUnit.SECONDS.toNanos(120);
        h.rounds(5);
        h.rounds(5);
        assertEquals(Collections.singletonList(0x12), h.codes, "exactly one close, at the 120 s retention");
        assertEquals(Collections.singletonList("resource"), h.reasons,
                "absolute retention is generic: no unseen-prerequisite phrase");
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse(), "the close returns the workspace reservations");
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL), "the blocked head's slot is released");
    }

    /**
     * A blocked CONTROL head expires between the last receive step and the game's acknowledgement of the executing
     * REALTIME unit. The lapsed wait retains the executing head and its slot (13.10); the terminal cleanup must
     * release every head slot and workspace byte, once, and a repeated close must stay harmless.
     */
    @Test void lapsedExpiryAtAcknowledgementReleasesEveryHeadSlotOnce() throws Exception {
        Harness h = new Harness(false);
        h.source.put(LogicalStream.REALTIME,
                SessionReceiveDriverTest.rec(1, 1, 0, Collections.<Record.Dependency>emptyList(), PID_REALTIME));
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, SessionReceiveDriverTest.dep(1, 1), PID_CONTROL));
        h.rounds(30);
        assertTrue(h.game.log.contains("deliver REALTIME 1s 1"), h.game.log.toString());
        assertTrue(h.driver.receiver().slots().holdsHead(LogicalStream.REALTIME));
        assertTrue(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL));
        h.now[0] = TimeUnit.SECONDS.toNanos(11);
        h.driver.acknowledged(LogicalStream.REALTIME, 1);
        assertEquals(Collections.singletonList(0x12), h.codes, "the lapsed wait is the only close");
        assertEquals(Collections.singletonList("resource"), h.reasons, "the locally withheld commit is generic");
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.REALTIME),
                "the retained executing head's slot is released");
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL),
                "the blocked head's slot is released");
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse(), "every workspace byte is returned");
        h.driver.close();
        h.driver.acknowledged(LogicalStream.REALTIME, 1);
        h.driver.receive();
        assertEquals(Collections.singletonList(0x12), h.codes, "a repeated close is harmless");
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.REALTIME));
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse());
    }

    /**
     * The stall source becomes unavailable during the settlement of the executing REALTIME unit. The receiver keeps
     * the executing head as a generic 0x12 wait; the driver must release every head slot and workspace byte once.
     */
    @Test void unavailableSourceAtAcknowledgementReleasesEveryHeadSlotOnce() throws Exception {
        Harness h = new Harness(false, true);
        h.source.put(LogicalStream.REALTIME,
                SessionReceiveDriverTest.rec(1, 1, 0, Collections.<Record.Dependency>emptyList(), PID_REALTIME));
        h.source.put(LogicalStream.CONTROL,
                SessionReceiveDriverTest.rec(0, 1, 0, SessionReceiveDriverTest.dep(1, 1), PID_CONTROL));
        h.rounds(30);
        assertTrue(h.game.log.contains("deliver REALTIME 1s 1"), h.game.log.toString());
        assertTrue(h.driver.receiver().slots().holdsHead(LogicalStream.REALTIME));
        assertTrue(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL));
        h.stalls.valid = false;
        h.driver.acknowledged(LogicalStream.REALTIME, 1);
        assertEquals(Collections.singletonList(0x12), h.codes, "the unavailable source closes once");
        assertEquals(Collections.singletonList("resource"), h.reasons, "an unavailable source has no phrase");
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.REALTIME),
                "the retained executing head's slot is released");
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.CONTROL),
                "the blocked head's slot is released");
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse(), "every workspace byte is returned");
        h.driver.close();
        h.driver.acknowledged(LogicalStream.REALTIME, 1);
        h.driver.receive();
        assertEquals(Collections.singletonList(0x12), h.codes, "a repeated close is harmless");
        assertFalse(h.driver.receiver().slots().holdsHead(LogicalStream.REALTIME));
        assertEquals(0L, h.driver.receiver().slots().workspaceInUse());
    }

    /** A cumulative source coherent with the harness's non-excluding transport clock. */
    private static final class CumulativeSource implements StallClock {
        private final long[] now;
        long excluded;
        boolean valid = true;

        CumulativeSource(long[] now) {
            this.now = now;
        }

        @Override public Sample sample() {
            final long nowReading = now[0];
            final long excludedReading = excluded;
            final boolean validReading = valid;
            return new Sample() {
                @Override public long nowNanos() { return nowReading; }
                @Override public long excludedNanos() { return excludedReading; }
                @Override public boolean valid() { return validReading; }
            };
        }
    }
}
