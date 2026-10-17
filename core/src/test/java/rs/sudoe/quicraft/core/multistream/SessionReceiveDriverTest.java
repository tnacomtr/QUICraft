// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RouteV2;

/** Serial delivery driver: admission, eligibility, Q/D, bundles, deadlines and the close paths. */
class SessionReceiveDriverTest {
    private static final int CHUNKS = 2;
    private static final int PROTOCOL = 1;
    private static final int PID_TRANSITION = 1;
    private static final int PID_CONTROL = 2;
    private static final int PID_CHUNK = 3;
    private static final int PID_REALTIME = 9;
    private static final int PID_VIEW = 4;
    private static final int PID_INTERACTION = 5;
    private static final int CLOSE_ORDERING = 0x11;
    private static final int CLOSE_RESOURCE = 0x12;

    /** A game that decodes by the frame's last byte (the packet id) and records what it was handed. */
    static final class FakeGame implements LaneGame {
        final Map<Long, Envelope.Bundle> markers = new HashMap<Long, Envelope.Bundle>();
        final List<String> log = new ArrayList<String>();
        /** Decode calls so far, including retried ones. */
        int decodes;
        /** When positive, every decode reserves this many bytes of decoded output before returning. */
        long reserveBytes;

        @Override
        public Decoded decode(rs.sudoe.quicraft.core.routing.Lane lane, long sequence, byte[] frame,
                              boolean insideBundle, Reservation reservation) {
            decodes++;
            if (reserveBytes > 0) reservation.reserve(reserveBytes, frame.length);
            int pid = frame[frame.length - 1] & 0xff;
            Envelope.Bundle marker = markers.containsKey(sequence) ? markers.get(sequence) : Envelope.Bundle.NONE;
            return new Decoded("p" + sequence, ProtocolState.PLAY, pid, marker, false, 0, frame.length, frame);
        }

        @Override
        public void deliver(rs.sudoe.quicraft.core.routing.Lane lane, long sequence, List<Object> packets,
                            boolean bundle) {
            log.add("deliver " + lane + " " + sequence + (bundle ? "b" : "s") + " " + packets.size());
        }

        @Override
        public void onEnqueued(rs.sudoe.quicraft.core.routing.Lane lane, int logicalStream, long sequence) {
            log.add("enqueued " + logicalStream + ":" + sequence);
        }

        @Override
        public void onApplicationComplete(rs.sudoe.quicraft.core.routing.Lane lane, int logicalStream,
                                          long sequence) {
            log.add("applied " + logicalStream + ":" + sequence);
        }

        @Override
        public void onWritabilityChanged(boolean writable) {
        }

        @Override
        public void onClosed(int code, boolean byPeer, Throwable cause) {
        }

        int deliveries() {
            int n = 0;
            for (String entry : log) {
                if (entry.startsWith("deliver ")) n++;
            }
            return n;
        }

        /** Marks the 'count' records from seq 1 as one bundle: OPEN, MEMBER..., CLOSE. */
        void markBundle(int count) {
            markers.put(1L, Envelope.Bundle.OPEN);
            for (long seq = 2; seq < count; seq++) markers.put(seq, Envelope.Bundle.MEMBER);
            markers.put((long) count, Envelope.Bundle.CLOSE);
        }
    }

    /** One connection's pump, driver and the sinks the tests read. */
    static final class Harness {
        final SessionReadPumpTest.Streams source = new SessionReadPumpTest.Streams(CHUNKS + 2, 1 << 20);
        final long[] now = {0L};
        final ReceiveSlots slots = new ReceiveSlots(CHUNKS);
        final ClientReadPump pump = new ClientReadPump(CHUNKS, slots, false, source, () -> now[0]);
        final FakeGame game = new FakeGame();
        final List<Integer> codes = new ArrayList<Integer>();
        final List<String> reasons = new ArrayList<String>();
        final Map<Integer, RouteV2> routes = new HashMap<Integer, RouteV2>();
        StreamReceiver.Validator validator = (stream, record, route) -> true;
        final SessionReceiveDriver driver;

        Harness() {
            routes.put(PID_TRANSITION, new RouteV2(RouteV2.StreamLane.CONTROL, Dependency.GLOBAL,
                    null, null, true, null, null, null));
            routes.put(PID_CONTROL, new RouteV2(RouteV2.StreamLane.CONTROL, Dependency.ORDINARY,
                    null, null, false, null, null, null));
            routes.put(PID_VIEW, new RouteV2(RouteV2.StreamLane.CONTROL, Dependency.VIEW,
                    null, null, false, null, null, null));
            routes.put(PID_INTERACTION, new RouteV2(RouteV2.StreamLane.CONTROL, Dependency.INTERACTION,
                    null, null, false, null, null, null));
            routes.put(PID_CHUNK, new RouteV2(RouteV2.StreamLane.CHUNK, Dependency.ORDINARY,
                    "chunk", "chunk", false, null, null, null));
            routes.put(PID_REALTIME, new RouteV2(RouteV2.StreamLane.REALTIME, Dependency.ORDINARY,
                    null, null, false, null, null, null));
            Map<String, Extractor> extractors = new HashMap<String, Extractor>();
            extractors.put("chunk", bytes -> new Extractor.Position(0, 0));
            RouteSourceImpl source = new RouteSourceImpl(routes);
            driver = new SessionReceiveDriver(CHUNKS, pump, slots, (s, r, route) -> validator.valid(s, r, route),
                    source, PROTOCOL, extractors, game, () -> now[0],
                    (code, reason) -> {
                        codes.add(code);
                        reasons.add(new String(reason, StandardCharsets.US_ASCII));
                    });
        }

        void rounds(int n) throws Exception {
            for (int i = 0; i < n; i++) driver.receive();
        }
    }

    static final class RouteSourceImpl implements SessionReceiveDriver.RouteSource {
        private final Map<Integer, RouteV2> routes;

        RouteSourceImpl(Map<Integer, RouteV2> routes) {
            this.routes = routes;
        }

        @Override
        public RouteV2 routeFor(RouteKey key) {
            return routes.get(key.packetId());
        }
    }

    /** A single GAME record on a stream, carrying its epoch and dependencies and the packet id pid. */
    static byte[] rec(int stream, long seq, long epoch, List<Record.Dependency> deps, int pid) {
        return RecordCodec.encodeRecord(new Record(stream, seq, epoch, deps, RecordCodec.KIND_GAME,
                SessionReadPumpTest.inner(new byte[] {(byte) pid})));
    }

    static byte[] member(long seq, int pid) {
        return SessionReadPumpTest.gameWire(LogicalStream.CONTROL, seq, new byte[] {(byte) pid});
    }

    /** A bundle of {@code records} CONTROL records from seq 1 to {@code records}, the opener first, members pid. */
    static byte[] bundle(int records, List<Record.Dependency> deps, int openerPid, int memberPid) {
        byte[] opener = null;
        byte[] tail = new byte[0];
        for (long seq = 2; seq <= records; seq++) {
            tail = SessionReadPumpTest.concat(tail, member(seq, memberPid));
        }
        long total = tail.length + 16;
        for (int i = 0; i < 16; i++) {
            opener = RecordCodec.encodeBundleOpen(LogicalStream.CONTROL, 1, 0, deps, records, total,
                    SessionReadPumpTest.inner(new byte[] {(byte) openerPid}));
            if (opener.length + tail.length == total) break;
            total = opener.length + tail.length;
        }
        return SessionReadPumpTest.concat(opener, tail);
    }

    static List<Record.Dependency> dep(int stream, long seq) {
        return Collections.singletonList(new Record.Dependency(stream, seq));
    }

    @Test void applyDiscoveredViolationClosesBeforeNextDelivery() throws Exception {
        Harness h = new Harness();
        // Stream 2's epoch-2 record waits on stream 3, which is the only way out of the held epoch.
        h.source.put(LogicalStream.CONTROL, rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_TRANSITION));
        // An epoch+1 record must depend on its transition (13.18), or it is a path violation on arrival.
        h.source.put(3, rec(3, 1, 1, dep(LogicalStream.CONTROL, 1), PID_CHUNK));
        h.rounds(30);
        assertTrue(h.game.log.contains("deliver CONTROL 1s 1"), h.game.log.toString());
        h.driver.acknowledged(LogicalStream.CONTROL, 1);
        h.source.put(2, rec(2, 1, 2, dep(3, 1), PID_CHUNK));
        h.rounds(30);
        assertTrue(h.codes.isEmpty(), "no close before the stream 3 apply");
        assertEquals(2, h.game.deliveries(), h.game.log.toString());
        h.driver.acknowledged(3, 1);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertEquals(2, h.game.deliveries(), "the epoch-2 record on stream 2 is never delivered");
        assertFalse(h.game.log.contains("applied 3:1"), "a violated apply is not reported as completed");
        assertFalse(h.slots.holdsHead(3), "a violated apply releases the pump slot once, through the terminal cleanup");
        assertEquals(0L, h.slots.workspaceInUse(), "a violated apply returns the workspace");
        h.driver.close();
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes, "a close after the violation adds no close");
    }

    @Test void oneExecutionUnitAtATime() throws Exception {
        Harness h = new Harness();
        // Two independently eligible heads: REALTIME and CHUNK[0]. The second waits for the first's acknowledgement.
        h.source.put(LogicalStream.REALTIME, rec(1, 1, 0, Collections.<Record.Dependency>emptyList(), PID_REALTIME));
        h.source.put(2, rec(2, 1, 0, Collections.<Record.Dependency>emptyList(), PID_CHUNK));
        h.rounds(30);
        assertTrue(h.game.log.contains("enqueued 1:1"), h.game.log.toString());
        assertFalse(h.game.log.contains("enqueued 2:1"), h.game.log.toString());
        assertEquals(1, h.game.decodes, "the second eligible head is not decoded before the first is applied");
        h.driver.acknowledged(LogicalStream.REALTIME, 1);
        h.rounds(30);
        assertTrue(h.game.log.contains("enqueued 2:1"), h.game.log.toString());
        assertEquals(2, h.game.decodes);
        assertTrue(h.codes.isEmpty(), h.codes.toString());
    }

    @Test void validationFailureReleasesWorkspaceOnce() throws Exception {
        Harness h = new Harness();
        // A REALTIME route on the CONTROL stream fails validation after the workspace was charged.
        h.source.put(LogicalStream.CONTROL, rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_REALTIME));
        h.rounds(30);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertEquals(0L, h.slots.workspaceInUse());
        assertFalse(h.slots.holdsHead(LogicalStream.CONTROL));
        h.driver.close();
        assertEquals(1, h.codes.size());
    }

    @Test void expiredWaitWhileExecutingReleasesEverything() throws Exception {
        Harness h = new Harness();
        // The transition executes (D is not reached); CHUNK[0] waits on a CONTROL sequence that never arrives.
        h.source.put(LogicalStream.CONTROL, rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_TRANSITION));
        h.source.put(2, rec(2, 1, 0, dep(LogicalStream.CONTROL, 2), PID_CHUNK));
        h.rounds(30);
        assertTrue(h.game.log.contains("enqueued 0:1"), h.game.log.toString());
        assertTrue(h.slots.workspaceInUse() > 0);
        h.now[0] = TimeUnit.SECONDS.toNanos(11);
        h.rounds(1);
        assertEquals(Collections.singletonList(CLOSE_RESOURCE), h.codes);
        assertEquals(0L, h.slots.workspaceInUse(), "the executing unit's workspace is returned");
        assertFalse(h.slots.holdsHead(LogicalStream.CONTROL));
        assertFalse(h.slots.holdsHead(2));
    }

    @Test void externalCloseReleasesOnceAndNeverCallsTheSink() throws Exception {
        Harness h = new Harness();
        h.source.put(LogicalStream.CONTROL, rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_TRANSITION));
        h.rounds(30);
        assertTrue(h.slots.workspaceInUse() > 0);
        h.driver.close();
        assertEquals(0L, h.slots.workspaceInUse());
        assertFalse(h.slots.holdsHead(LogicalStream.CONTROL));
        assertTrue(h.codes.isEmpty(), "an external close does not call the sink");
        assertTrue(h.driver.closed());
        h.driver.close();
        h.driver.receive();
        assertEquals(0L, h.slots.workspaceInUse());
        assertTrue(h.codes.isEmpty());
    }

    @Test void missingCloseAfterFailureReleasesEverythingOnce() throws Exception {
        Harness h = new Harness();
        // A partial bundle holds the CONTROL slot; the missing-close timer closes the driver.
        byte[] full = bundle(3, Collections.<Record.Dependency>emptyList(), PID_CONTROL, PID_CONTROL);
        h.source.put(LogicalStream.CONTROL, Arrays.copyOf(full, full.length - 1));
        h.rounds(10);
        h.now[0] = TimeUnit.SECONDS.toNanos(11);
        h.rounds(1);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertFalse(h.slots.holdsHead(LogicalStream.CONTROL), "the pump's held slot is released by the cleanup");
        assertEquals(0L, h.slots.workspaceInUse());
        h.driver.close();
        assertEquals(1, h.codes.size());
    }

    @Test void endAppliesAsAFenceAndClosesCleanly() throws Exception {
        Harness h = new Harness();
        h.source.put(LogicalStream.CONTROL, endWire(1));
        h.rounds(30);
        assertEquals(Collections.singletonList(0), h.codes);
        assertEquals(Collections.singletonList(""), h.reasons);
        assertEquals(0, h.game.decodes, "END is never decoded");
        assertEquals(0, h.game.deliveries(), "END is never delivered");
        assertEquals(0L, h.slots.workspaceInUse());
    }

    @Test void endPostApplyViolationWinsOverTheCleanClose() throws Exception {
        Harness h = new Harness();
        // The epoch+1 chunk waits on END (a transition-less epoch change). Applying END leaves it without a path.
        h.source.put(LogicalStream.CONTROL, endWire(1));
        h.source.put(3, rec(3, 1, 1, dep(LogicalStream.CONTROL, 1), PID_CHUNK));
        h.rounds(30);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertEquals(0, h.game.decodes);
    }

    @Test void decodeRetriesWhenTheWorkspaceIsFullThenSucceeds() throws Exception {
        Harness h = new Harness();
        long occupied = ReceiveSlots.EXECUTION_WORKSPACE - 100;
        assertTrue(h.slots.reserveWorkspace(occupied), "another holder takes most of the workspace");
        h.game.reserveBytes = 200;
        byte[] wire = rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_CONTROL);
        h.source.put(LogicalStream.CONTROL, wire);
        h.rounds(30);
        assertTrue(h.codes.isEmpty(), "a full workspace is a retry, not a close");
        assertEquals(0, h.game.deliveries(), "the unit is not applied or delivered yet");
        assertEquals(occupied, h.slots.workspaceInUse(), "the attempt's own charges are returned");
        assertTrue(h.slots.holdsHead(LogicalStream.CONTROL), "the unit stays queued in its slot");
        h.slots.releaseWorkspace(occupied);
        h.rounds(1);
        assertEquals(1, h.game.deliveries(), h.game.log.toString());
        assertTrue(h.codes.isEmpty());
        assertEquals(wire.length + 200L, h.slots.workspaceInUse(), "the delivered unit holds exactly its charges");
    }

    @Test void closeWhileWaitingForWorkspaceReleasesOnce() throws Exception {
        Harness h = new Harness();
        long occupied = ReceiveSlots.EXECUTION_WORKSPACE - 100;
        assertTrue(h.slots.reserveWorkspace(occupied));
        h.game.reserveBytes = 200;
        h.source.put(LogicalStream.CONTROL, rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_CONTROL));
        h.rounds(30);
        assertTrue(h.slots.holdsHead(LogicalStream.CONTROL));
        h.driver.close();
        assertFalse(h.slots.holdsHead(LogicalStream.CONTROL), "the queued unit's slot is released");
        assertEquals(occupied, h.slots.workspaceInUse(), "only the other holder's bytes remain");
        assertTrue(h.codes.isEmpty());
        h.slots.releaseWorkspace(occupied);
        assertEquals(0L, h.slots.workspaceInUse());
    }

    /** A single END record on CONTROL at {@code seq} (kind 1, empty body, no dependencies). */
    static byte[] endWire(long seq) {
        return RecordCodec.encodeRecord(new Record(LogicalStream.CONTROL, seq, 0,
                Collections.<Record.Dependency>emptyList(), RecordCodec.KIND_END, new byte[0]));
    }

    @Test void wrongRouteNeverReachesGameQueue() throws Exception {
        Harness h = new Harness();
        // A REALTIME route on the CONTROL stream: opaque at offer, rejected at eligibility.
        h.source.put(LogicalStream.CONTROL, rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_REALTIME));
        h.rounds(30);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertEquals(0, h.game.deliveries(), h.game.log.toString());
    }

    @Test void queuedIsNotApplied() throws Exception {
        Harness h = new Harness();
        h.source.put(LogicalStream.CONTROL, rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_TRANSITION));
        byte[] dependent = rec(2, 1, 1, dep(0, 1), PID_CHUNK);
        h.source.put(2, dependent);
        h.rounds(30);
        assertTrue(h.game.log.contains("enqueued 0:1"), h.game.log.toString());
        assertEquals(0L, h.driver.receiver().epoch(), "Q does not advance the epoch");
        assertEquals(0L, h.driver.receiver().appliedWatermark(LogicalStream.CONTROL), "Q does not set D");
        assertTrue(h.slots.holdsHead(LogicalStream.CONTROL), "Q does not free the slot");
        assertTrue(h.slots.workspaceInUse() > 0, "Q does not free the workspace");
        assertEquals(1, h.game.deliveries(), "the dependent chunk waits for D");
        h.driver.acknowledged(LogicalStream.CONTROL, 1);
        assertEquals(1L, h.driver.receiver().epoch());
        assertFalse(h.slots.holdsHead(LogicalStream.CONTROL));
        // D released the transition's workspace; the now-ready dependent is enqueued and holds its own.
        assertEquals(dependent.length, h.slots.workspaceInUse());
        h.rounds(30);
        assertEquals(2, h.game.deliveries(), h.game.log.toString());
        assertTrue(h.codes.isEmpty());
    }

    @Test void slotIsChargedOnceByThePump() throws Exception {
        Harness h = new Harness();
        byte[] wire = rec(0, 1, 0, Collections.<Record.Dependency>emptyList(), PID_CONTROL);
        h.source.put(LogicalStream.CONTROL, wire);
        h.rounds(30);
        // Enqueued and held: the slot holds exactly the record's bytes once, never twice.
        assertTrue(h.game.log.contains("enqueued 0:1"), h.game.log.toString());
        assertEquals(1, h.slots.heldRecords(LogicalStream.CONTROL));
        assertEquals(wire.length, h.slots.heldBytes(LogicalStream.CONTROL));
    }

    @Test void bundleCloserWithheldSuppressesUnseenPhrase() throws Exception {
        Harness h = new Harness();
        // The opener waits on stream 2, which never arrives. Its closer is withheld locally.
        h.source.put(LogicalStream.CONTROL, bundle(3, dep(2, 1), PID_CONTROL, PID_CONTROL));
        h.rounds(10);
        h.driver.markWithheld(LogicalStream.CONTROL, 3);
        h.now[0] = TimeUnit.SECONDS.toNanos(11);
        h.rounds(1);
        assertEquals(Collections.singletonList(CLOSE_RESOURCE), h.codes);
        assertEquals(Collections.singletonList("resource"), h.reasons, "normalised to the opener, no unseen phrase");
    }

    @Test void blockedDeadlineNeverResetsAndExcludesAtMostSixtySeconds() throws Exception {
        Harness h = new Harness();
        h.source.put(2, rec(2, 1, 0, dep(LogicalStream.CONTROL, 1), PID_CHUNK));
        h.rounds(5);
        h.driver.stallStarted();
        h.now[0] = TimeUnit.SECONDS.toNanos(65);
        h.driver.stallEnded();
        h.rounds(1);
        assertTrue(h.codes.isEmpty(), "65 s with 60 s excluded leaves 5 s active");
        h.now[0] = TimeUnit.SECONDS.toNanos(69);
        h.rounds(1);
        assertTrue(h.codes.isEmpty(), "active time is 9 s at 69 s");
        h.now[0] = TimeUnit.SECONDS.toNanos(70);
        h.rounds(1);
        assertEquals(Collections.singletonList(CLOSE_RESOURCE), h.codes);
    }

    @Test void stallEndStopsTheExclusion() throws Exception {
        Harness h = new Harness();
        h.source.put(2, rec(2, 1, 0, dep(LogicalStream.CONTROL, 1), PID_CHUNK));
        h.rounds(5);
        h.driver.stallStarted();
        h.now[0] = TimeUnit.SECONDS.toNanos(5);
        h.driver.stallEnded();
        h.now[0] = TimeUnit.SECONDS.toNanos(14);
        h.rounds(1);
        assertTrue(h.codes.isEmpty(), "a 5 s stall leaves 9 s of active time at 14 s");
        h.now[0] = TimeUnit.SECONDS.toNanos(15);
        h.rounds(1);
        assertEquals(Collections.singletonList(CLOSE_RESOURCE), h.codes);
    }

    @Test void bundleIsAtomicAndBounded() throws Exception {
        Harness h = new Harness();
        // 128 records: opener, 126 members and the closer (13.15).
        byte[] full = bundle(128, Collections.<Record.Dependency>emptyList(), PID_CONTROL, PID_CONTROL);
        byte[] partial = Arrays.copyOf(full, full.length / 2);
        h.source.put(LogicalStream.CONTROL, partial);
        h.rounds(20);
        assertEquals(0, h.game.deliveries(), "no partial bundle is delivered");
        assertTrue(h.codes.isEmpty());
        h.source.put(LogicalStream.CONTROL, full);
        h.game.markBundle(128);
        h.rounds(3000);
        assertEquals(1, h.game.deliveries(), h.game.log.toString());
        assertTrue(h.game.log.contains("deliver CONTROL 128b 128"), h.game.log.toString());
        assertTrue(h.codes.isEmpty());
    }

    @Test void bundleNestedDelimiterCloses0x11() throws Exception {
        Harness h = new Harness();
        h.game.markers.put(1L, Envelope.Bundle.OPEN);
        h.game.markers.put(2L, Envelope.Bundle.OPEN);
        h.game.markers.put(3L, Envelope.Bundle.CLOSE);
        h.source.put(LogicalStream.CONTROL, bundle(3, Collections.<Record.Dependency>emptyList(), PID_CONTROL,
                PID_CONTROL));
        h.rounds(40);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertEquals(0, h.game.deliveries());
    }

    @Test void bundleScopeFollowsTheMapping() throws Exception {
        Harness h = new Harness();
        final ReceiveRoute[] captured = new ReceiveRoute[1];
        h.validator = (s, r, route) -> {
            if (r.sequence() == 1) captured[0] = route;
            return true;
        };
        // Members with VIEW and INTERACTION: holding both promotes the bundle to global.
        h.game.markBundle(3);
        h.source.put(LogicalStream.CONTROL, bundleMixed(PID_VIEW, PID_INTERACTION));
        h.rounds(40);
        assertNotNull(captured[0], h.codes.toString());
        assertEquals(Dependency.GLOBAL, captured[0].fence());
    }

    @Test void bundleScopeViewBeatsControl() throws Exception {
        Harness h = new Harness();
        final ReceiveRoute[] captured = new ReceiveRoute[1];
        h.validator = (s, r, route) -> {
            if (r.sequence() == 1) captured[0] = route;
            return true;
        };
        h.game.markBundle(3);
        h.source.put(LogicalStream.CONTROL, bundleMixed(PID_CONTROL, PID_VIEW));
        h.rounds(40);
        assertNotNull(captured[0], h.codes.toString());
        assertEquals(Dependency.VIEW, captured[0].fence());
    }

    /** A three-record bundle whose opener is pidA and whose members are pidA then pidB. */
    static byte[] bundleMixed(int pidA, int pidB) {
        byte[] m2 = member(2, pidA);
        byte[] m3 = member(3, pidB);
        byte[] tail = SessionReadPumpTest.concat(m2, m3);
        long total = tail.length + 16;
        byte[] opener = null;
        for (int i = 0; i < 16; i++) {
            opener = RecordCodec.encodeBundleOpen(LogicalStream.CONTROL, 1, 0,
                    Collections.<Record.Dependency>emptyList(), 3, total,
                    SessionReadPumpTest.inner(new byte[] {(byte) pidA}));
            if (opener.length + tail.length == total) break;
            total = opener.length + tail.length;
        }
        return SessionReadPumpTest.concat(opener, tail);
    }

    @Test void missingBundleCloseAfterTenSecondsCloses0x11() throws Exception {
        Harness h = new Harness();
        byte[] full = bundle(3, Collections.<Record.Dependency>emptyList(), PID_CONTROL, PID_CONTROL);
        // Opener and first member only: the closer never arrives.
        h.source.put(LogicalStream.CONTROL, Arrays.copyOf(full, full.length - 1));
        h.rounds(10);
        h.now[0] = TimeUnit.SECONDS.toNanos(9);
        h.rounds(1);
        assertTrue(h.codes.isEmpty(), "no close before 10 s");
        h.now[0] = TimeUnit.SECONDS.toNanos(10) + 1;
        h.rounds(1);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
    }
}
