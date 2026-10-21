// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.StreamReceiverTest.Fixture;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.RouteV2;

/** The receiver's rules for the ACK stream: what it accepts and when it closes 0x11 (13.21). */
class AckLaneReceiveTest {
    private static final int CLOSE_ORDERING = 0x11;
    /** SessionReceiveDriverTest's harness uses two shards: CONTROL 0, REALTIME 1, shards 2..3, ACK 4. */
    private static final int ACK = 4;
    private static final int PID_ACK = 40;
    private static final int PID_OTHER_ACK = 41;
    private static final List<Record.Dependency> NO_DEPS = Collections.<Record.Dependency>emptyList();

    private static ReceiveRoute ackRoute() {
        return new ReceiveRoute(ReceiveRoute.Lane.ACK, Dependency.TRAILING, null, false, false);
    }

    private static SessionReceiveDriverTest.Harness harness() {
        SessionReceiveDriverTest.Harness h = new SessionReceiveDriverTest.Harness();
        h.routes.put(PID_ACK, new RouteV2(RouteV2.StreamLane.ACK, Dependency.TRAILING, null, null, false, null,
                null, null));
        h.routes.put(PID_OTHER_ACK, new RouteV2(RouteV2.StreamLane.ACK, Dependency.TRAILING, null, null, false,
                null, null, null));
        return h;
    }

    // --- Envelope admission and route checks (StreamReceiver).

    @Test
    void aSmallGameRecordIsAdmittedOnTheAckStreamAndItsAckRouteValidates() {
        Fixture f = new Fixture(3);
        int ack = 5;
        StreamReceiverTest.assertAdmitted(f.receiver, StreamReceiverTest.rec(ack, 1, 0));
        assertEquals(ack, f.receiver.nextToEnqueue());
        assertNull(f.receiver.validateEligible(ack, ackRoute()));
        assertEquals(ReceiveSlots.ACK_RECORD_MAX_BYTES, 64);
    }

    @Test
    void theWireCapIs64BytesAndOneByteMoreCloses0x11() {
        Fixture f = new Fixture(3);
        assertEquals(StreamReceiver.Admission.Kind.ADMITTED,
                f.receiver.offer(StreamReceiverTest.rec(5, 1, 0), 64, 1).kind());
        StreamReceiver.Admission over = f.receiver.offer(StreamReceiverTest.rec(5, 2, 0), 65, 1);
        assertEquals(StreamReceiver.Admission.Kind.VIOLATION, over.kind());
        assertEquals(CLOSE_ORDERING, over.close().errorCode());
    }

    @Test
    void aRecordOfAnotherKindOnTheAckStreamCloses0x11() {
        for (int kind : new int[] {RecordCodec.KIND_END, RecordCodec.KIND_ACK_JOIN, RecordCodec.KIND_BATCH_JOIN,
                RecordCodec.KIND_BUNDLE_OPEN}) {
            Fixture f = new Fixture(3);
            StreamReceiver.Admission admission = StreamReceiverTest.offer(f.receiver,
                    StreamReceiverTest.recKind(5, 1, 0, kind));
            assertEquals(StreamReceiver.Admission.Kind.VIOLATION, admission.kind(), "kind " + kind);
            assertEquals(CLOSE_ORDERING, admission.close().errorCode());
        }
    }

    @Test
    void aSelfDependencyOnTheAckStreamCloses0x11() {
        Fixture f = new Fixture(3);
        StreamReceiver.Admission admission = StreamReceiverTest.offer(f.receiver,
                StreamReceiverTest.rec(5, 2, 0, StreamReceiverTest.dep(5, 1)));
        assertEquals(StreamReceiver.Admission.Kind.VIOLATION, admission.kind());
        assertEquals(CLOSE_ORDERING, admission.close().errorCode());
    }

    @Test
    void aMultiRecordUnitOnTheAckStreamCloses0x11() {
        Fixture f = new Fixture(3);
        StreamReceiver.Admission admission = f.receiver.offer(StreamReceiverTest.rec(5, 1, 0), 2, 40, 2);
        assertEquals(StreamReceiver.Admission.Kind.VIOLATION, admission.kind());
    }

    @Test
    void onlyAnAckRouteMayArriveOnTheAckStreamAndAnAckRouteMayArriveNowhereElse() {
        for (ReceiveRoute.Lane lane : new ReceiveRoute.Lane[] {ReceiveRoute.Lane.CONTROL,
                ReceiveRoute.Lane.REALTIME, ReceiveRoute.Lane.CHUNK}) {
            Fixture f = new Fixture(3);
            StreamReceiverTest.assertAdmitted(f.receiver, StreamReceiverTest.rec(5, 1, 0));
            ReceiveClose close = f.receiver.validateEligible(5,
                    new ReceiveRoute(lane, Dependency.ORDINARY, null, false, false));
            assertNotNull(close, lane + " route on the ACK stream");
            assertEquals(CLOSE_ORDERING, close.errorCode());
        }
        for (int stream = 0; stream < 5; stream++) {
            Fixture f = new Fixture(3);
            StreamReceiverTest.assertAdmitted(f.receiver, StreamReceiverTest.rec(stream, 1, 0));
            ReceiveClose close = f.receiver.validateEligible(stream, ackRoute());
            assertNotNull(close, "ACK route on stream " + stream);
            assertEquals(CLOSE_ORDERING, close.errorCode());
        }
    }

    @Test
    void anUnseenCrossStreamSequenceIsWaitedForNotRejected() {
        Fixture f = new Fixture(3);
        StreamReceiverTest.assertAdmitted(f.receiver, StreamReceiverTest.rec(5, 1, 0, StreamReceiverTest.dep(2, 7)));
        assertEquals(-1, f.receiver.nextToEnqueue(), "chunk shard record 7 has not been seen, so the ACK waits");
        assertNull(f.receiver.pendingViolation());
    }

    @Test
    void aProvableCycleThroughTheAckStreamStillCloses0x11() {
        Fixture f = new Fixture(3);
        StreamReceiverTest.assertAdmitted(f.receiver, StreamReceiverTest.rec(5, 1, 0, StreamReceiverTest.dep(2, 1)));
        StreamReceiver.Admission cycle = StreamReceiverTest.offer(f.receiver,
                StreamReceiverTest.rec(2, 1, 0, StreamReceiverTest.dep(5, 1)));
        assertEquals(StreamReceiver.Admission.Kind.VIOLATION, cycle.kind());
    }

    @Test
    void anEpochTransitionWaitsForAnAckThatIsOnlyQueuedUntilItIsApplied() {
        Fixture f = new Fixture(3);
        StreamReceiverTest.assertAdmitted(f.receiver, StreamReceiverTest.rec(5, 1, 0));
        // The transition depends on the ACK stream, as every global record does (13.21).
        StreamReceiverTest.assertAdmitted(f.receiver, StreamReceiverTest.rec(0, 1, 0, StreamReceiverTest.dep(5, 1)));
        assertEquals(5, f.receiver.nextToEnqueue());
        StreamReceiverTest.enqueue(f.receiver, 5, 1, ackRoute());
        assertEquals(-1, f.receiver.nextToEnqueue(), "queued is not applied: the transition still waits");
        f.receiver.applied(5, 1);
        assertEquals(0, f.receiver.nextToEnqueue());
    }

    // --- Pump-level checks (the record is refused before a body is read).

    @Test
    void thePumpClosesAnOversizeOrWrongKindAckRecordWith0x11NotAResourceClose() throws Exception {
        byte[] oversize = SessionReadPumpTest.gameWire(ACK, 1, new byte[80]);
        // A GAME record whose kind byte is patched to END: the encoder itself refuses END off CONTROL.
        byte[] end = SessionReceiveDriverTest.rec(ACK, 1, 0, NO_DEPS, PID_ACK);
        end[1] = (byte) RecordCodec.KIND_END;
        for (byte[] wire : new byte[][] {oversize, end}) {
            SessionReadPumpTest.Streams source = new SessionReadPumpTest.Streams(2 + 2 + 1, 1 << 20);
            source.put(ACK, wire);
            ClientReadPump pump = new ClientReadPump(2, new ReceiveSlots(2), false, source);
            MultistreamProtocolException e = org.junit.jupiter.api.Assertions.assertThrows(
                    MultistreamProtocolException.class, () -> SessionReadPumpTest.nextClientUnit(pump));
            assertEquals(CLOSE_ORDERING, e.closeCode());
            if (wire == oversize) {
                assertTrue(e.getMessage().contains("ACK stream"), e.getMessage());
            }
        }
    }

    @Test
    void theAckSlotIsSmallAndSeparateFromTheShardSlots() {
        ReceiveSlots slots = new ReceiveSlots(2);
        assertEquals(ACK, slots.ackStream());
        assertTrue(ReceiveSlots.ACK_SLOT_BYTES < 1024, "protected, tiny, not a 2 MiB shard slot");
        assertFalse(slots.admitHead(ACK, ReceiveSlots.ACK_SLOT_BYTES + 1, 1));
        assertTrue(slots.admitHead(2, ReceiveSlots.BYTES_PER_STREAM, ReceiveSlots.RECORDS_PER_STREAM),
                "a full shard slot leaves the ACK slot free");
        assertTrue(slots.admitHead(ACK, ReceiveSlots.ACK_SLOT_BYTES, 1));
    }

    // --- Driver-level checks, with the decoded packet id and size.

    @Test
    void anAckRecordIsDeliveredOnTheControlLaneAndNothingCloses() throws Exception {
        SessionReceiveDriverTest.Harness h = harness();
        h.source.put(ACK, SessionReceiveDriverTest.rec(ACK, 1, 0, NO_DEPS, PID_ACK));
        h.rounds(30);
        assertEquals(1, h.game.deliveries(), h.game.log.toString());
        assertTrue(h.game.log.contains("deliver CONTROL 1s 1"), h.game.log.toString());
        assertTrue(h.codes.isEmpty(), h.codes.toString());
    }

    @Test
    void aPacketThatIsNotAnAckRouteOnTheAckStreamCloses0x11() throws Exception {
        for (int pid : new int[] {2, 3, 9, 4, 1, 77}) {
            // 2 CONTROL, 3 CHUNK, 9 REALTIME, 4 VIEW, 1 the epoch transition, 77 an unknown id (CONTROL/global).
            SessionReceiveDriverTest.Harness h = harness();
            h.source.put(ACK, SessionReceiveDriverTest.rec(ACK, 1, 0, NO_DEPS, pid));
            h.rounds(30);
            assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes, "packet " + pid);
            assertEquals(0, h.game.deliveries(), "packet " + pid);
        }
    }

    @Test
    void anAckPacketOnAnotherStreamCloses0x11() throws Exception {
        for (int stream : new int[] {0, 1, 2, 3}) {
            SessionReceiveDriverTest.Harness h = harness();
            h.source.put(stream, SessionReceiveDriverTest.rec(stream, 1, 0, NO_DEPS, PID_ACK));
            h.rounds(30);
            assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes, "stream " + stream);
        }
    }

    @Test
    void anOversizeAckRecordCloses0x11() throws Exception {
        SessionReceiveDriverTest.Harness h = harness();
        byte[] body = new byte[80];
        body[body.length - 1] = (byte) PID_ACK;
        h.source.put(ACK, SessionReadPumpTest.gameWire(ACK, 1, body));
        h.rounds(30);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertEquals(0, h.game.deliveries());
    }

    @Test
    void anAckRecordThatInflatesPast64BytesCloses0x11() throws Exception {
        SessionReceiveDriverTest.Harness h = harness();
        h.game.inflatedOverride = ReceiveSlots.ACK_RECORD_MAX_BYTES + 1;
        h.source.put(ACK, SessionReceiveDriverTest.rec(ACK, 1, 0, NO_DEPS, PID_ACK));
        h.rounds(30);
        assertEquals(Collections.singletonList(CLOSE_ORDERING), h.codes);
        assertEquals(0, h.game.deliveries());
    }

    @Test
    void anAckRecordThatInflatesTo64BytesIsAccepted() throws Exception {
        SessionReceiveDriverTest.Harness h = harness();
        h.game.inflatedOverride = ReceiveSlots.ACK_RECORD_MAX_BYTES;
        h.source.put(ACK, SessionReceiveDriverTest.rec(ACK, 1, 0, NO_DEPS, PID_ACK));
        h.rounds(30);
        assertTrue(h.codes.isEmpty(), h.codes.toString());
        assertEquals(1, h.game.deliveries());
    }

    @Test
    void anAckRecordWaitsForAnUnseenShardRecordWithoutClosing() throws Exception {
        SessionReceiveDriverTest.Harness h = harness();
        h.source.put(ACK, SessionReceiveDriverTest.rec(ACK, 1, 0, SessionReceiveDriverTest.dep(2, 9), PID_ACK));
        h.rounds(30);
        assertTrue(h.codes.isEmpty(), h.codes.toString());
        assertEquals(0, h.game.deliveries(), "waiting for chunk shard record 9");
    }

    @Test
    void twoAckRecordsKeepTheirStreamOrder() throws Exception {
        SessionReceiveDriverTest.Harness h = harness();
        h.source.put(ACK, SessionReadPumpTest.concat(
                SessionReceiveDriverTest.rec(ACK, 1, 0, NO_DEPS, PID_ACK),
                SessionReceiveDriverTest.rec(ACK, 2, 0, NO_DEPS, PID_OTHER_ACK)));
        h.rounds(30);
        assertEquals(1, h.game.deliveries(), "one unit at a time: the second waits for the first");
        h.driver.acknowledged(ACK, 1);
        h.rounds(30);
        assertEquals(2, h.game.deliveries(), h.game.log.toString());
        assertTrue(h.game.log.toString().indexOf("deliver CONTROL 1s")
                < h.game.log.toString().indexOf("deliver CONTROL 2s"), "stream order");
        assertTrue(h.codes.isEmpty(), h.codes.toString());
    }
}
