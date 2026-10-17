// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.binding.BindingFailure;
import rs.sudoe.quicraft.core.binding.BindingMachineResult;
import rs.sudoe.quicraft.core.binding.BindingResult;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.binding.ExporterSource;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;

/** The U8a integration contract: selected N, logical stream identity and additive API freezes. */
class SessionContractTest {
    private static final int PROTOCOL = 775;
    private static final int ONLINE = BindingStateMachine.MODE_ONLINE;
    private static final byte[] SECRET = filled(16, 0x11);
    private static final byte[] EXPORTER = filled(32, 0x22);
    private static final byte[] FINGERPRINT = filled(32, 0x33);
    private static final byte[] ROUTING = filled(32, 0x44);

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    /** Parses a raw preamble body as the server would receive a client's offer. */
    private static PreambleV2.Parser parse(byte[] wire) throws MultistreamProtocolException {
        PreambleV2.Parser parser = new PreambleV2.Parser();
        assertTrue(parser.feed(ByteBuffer.wrap(wire)));
        return parser;
    }

    private static int closeCodeOf(byte[] wire) {
        return assertThrows(MultistreamProtocolException.class, () -> parse(wire)).closeCode();
    }

    // --- Selected N (13.2).

    @Test
    void selectedNIsImmutableAndWithinOffer() throws Exception {
        for (int n = 1; n <= 6; n++) {
            // Client offers 1..6; the server's configured maximum and preference both equal n.
            SessionContext selected = SessionContext.select(parse(PreambleV2.encode(null, 1, 6)), n, n);
            assertEquals(n, selected.shardCount(), "N=" + n);
            assertEquals(n + 2, selected.logicalStreamCount());
            assertEquals(selected, SessionContext.of(n));
            assertEquals(n, selected.shardCount(), "the selection never changes after it is made");
            // The server's reply names N in both fields, and the client accepts it inside its offer.
            assertEquals(selected, SessionContext.accept(n, n, 1, 6));
        }
        // A narrow offer clamps the preference into the intersection, never outside it.
        assertEquals(5, SessionContext.select(parse(PreambleV2.encode(null, 5, 6)), 6, 3).shardCount());
        assertEquals(4, SessionContext.select(parse(PreambleV2.encode(null, 1, 4)), 6, 6).shardCount());
    }

    @Test
    void malformedRangesCloseWithProtocolViolation() {
        // Length 4, version 2, no token, then min/max that the parser must refuse (13.2).
        assertEquals(0x1, closeCodeOf(new byte[] {4, 2, 0, 0, 6}), "min 0");
        assertEquals(0x1, closeCodeOf(new byte[] {4, 2, 0, 1, 7}), "max 7");
        assertEquals(0x1, closeCodeOf(new byte[] {4, 2, 0, 5, 3}), "min above max");
        assertEquals(0x1, closeCodeOf(new byte[] {4, 3, 0, 1, 6}), "unknown version");
        // The client side refuses a reply that is not one shard count.
        assertEquals(0x1, reply(2, 3));
        assertEquals(0x1, reply(0, 0));
        assertEquals(0x1, reply(7, 7));
    }

    @Test
    void emptyIntersectionOrSelectionOutsideTheOfferCloseWithNoCommonShards() throws Exception {
        // Offer 5..6 against a configured maximum of 4 leaves no common shard count.
        MultistreamProtocolException none = assertThrows(MultistreamProtocolException.class,
                () -> SessionContext.select(parse(PreambleV2.encode(null, 5, 6)), 4, 3));
        assertEquals(0x10, none.closeCode());
        // A reply naming 4 when the client offered only 1..3 is an invalid selection.
        assertEquals(0x10, reply(4, 4, 1, 3));
    }

    @Test
    void invalidSharedCountIsLocalMisuse() {
        assertThrows(IllegalArgumentException.class, () -> SessionContext.of(0));
        assertThrows(IllegalArgumentException.class, () -> SessionContext.of(7));
        assertThrows(IllegalArgumentException.class, () -> SessionContext.legacyLogicalIndex(null));
    }

    private static int reply(int min, int max) {
        return reply(min, max, 1, 6);
    }

    private static int reply(int min, int max, int offerMin, int offerMax) {
        try {
            SessionContext.accept(min, max, offerMin, offerMax);
            return -1;
        } catch (MultistreamProtocolException e) {
            return e.closeCode();
        }
    }

    // --- Logical streams (13.3).

    @Test
    void logicalStreamIdentityFollowsTheNegotiatedCount() {
        SessionContext two = SessionContext.of(2);
        assertEquals(0L, two.quicIdOf(0));
        assertEquals(4L, two.quicIdOf(1));
        assertEquals(1L, two.quicIdOf(2));
        assertEquals(5L, two.quicIdOf(3));
        assertEquals(3, two.logicalIndexOf(5L));
        assertThrows(IllegalArgumentException.class, () -> two.quicIdOf(4));
        assertThrows(IllegalArgumentException.class, () -> two.logicalIndexOf(9L));
    }

    @Test
    void legacyCategoriesMapToTheirFixedLogicalIndices() {
        assertEquals(0, SessionContext.legacyLogicalIndex(Lane.CONTROL));
        assertEquals(1, SessionContext.legacyLogicalIndex(Lane.REALTIME));
        assertEquals(2, SessionContext.legacyLogicalIndex(Lane.WORLD));
    }

    @Test
    void sentPacketCarriesLogicalStreamIdentityNotOnlyItsCategory() {
        SentPacket first = new SentPacket();
        SentPacket second = new SentPacket();
        assertEquals(-1, first.logicalStream(), "unassigned");
        assertFalse(first.isAssigned());
        // Two chunk shards with the same sequence: the category matches, the stream does not.
        first.assign(Lane.WORLD, 2, 5L);
        second.assign(Lane.WORLD, 3, 5L);
        assertEquals(Lane.WORLD, first.lane());
        assertEquals(Lane.WORLD, second.lane());
        assertEquals(5L, first.sequence());
        assertEquals(5L, second.sequence());
        assertNotEquals(first.logicalStream(), second.logicalStream());
        // The legacy assignment derives the logical stream from the category.
        SentPacket legacy = new SentPacket();
        legacy.assign(Lane.REALTIME, 9L);
        assertEquals(1, legacy.logicalStream());
        assertEquals(Lane.REALTIME, legacy.lane());
    }

    // --- Binding owner (12.7, 12.8).

    @Test
    void bindingRejectsDifferentSelectedN() {
        ExporterSource exporter = () -> EXPORTER.clone();
        BindingStateMachine server = machine(BindingStateMachine.server(exporter, SECRET, ONLINE, PROTOCOL,
                3, FINGERPRINT, ROUTING));
        BindingResult offer = server.start();
        assertTrue(offer.isSend(), "the server opens with its OFFER");
        byte[] payload = offer.message().encode();

        BindingStateMachine sameN = machine(BindingStateMachine.client(exporter, SECRET, ONLINE, PROTOCOL,
                3, FINGERPRINT, ROUTING));
        assertTrue(sameN.receive(payload).isSend(), "the same N is accepted");

        BindingStateMachine otherN = machine(BindingStateMachine.client(exporter, SECRET, ONLINE, PROTOCOL,
                2, FINGERPRINT, ROUTING));
        BindingResult rejected = otherN.receive(payload);
        assertTrue(rejected.isFailed(), "a different N must fail the exchange");
        assertEquals(BindingFailure.WRONG_SHARD_COUNT, rejected.failure());
    }

    @Test
    void bindingSessionRequiresAValidSelectedN() {
        assertThrows(IllegalArgumentException.class, () -> new BindingSession(BindingStateMachine.Role.CLIENT,
                () -> EXPORTER.clone(), SECRET, ONLINE, PROTOCOL, 0, FINGERPRINT, ROUTING, () -> 0L, 0L,
                null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new BindingSession(BindingStateMachine.Role.CLIENT,
                () -> EXPORTER.clone(), SECRET, ONLINE, PROTOCOL, 7, FINGERPRINT, ROUTING, () -> 0L, 0L,
                null, null, null));
    }

    private static BindingStateMachine machine(BindingMachineResult result) {
        assertNotNull(result.machine(), "expected a machine but got " + result);
        return result.machine();
    }

    // --- Legacy acknowledgement and compatibility (13.10, 13.6).

    @Test
    void legacyApplyAcknowledgesActualOutstandingShard() {
        ReceiverHarness h = new ReceiverHarness();
        h.offer(Lane.CONTROL, 1);
        h.offer(Lane.WORLD, 1);
        assertEquals(1, h.calls.size(), "one delivery is outstanding at a time");
        ReceiverScheduler.Delivery first = h.calls.get(0);
        assertEquals(Lane.CONTROL, first.lane());
        // Same sequence, other lane: not the outstanding delivery, so it is refused (0x11).
        assertEquals(0x11, assertThrows(OrderingException.class,
                () -> h.scheduler.applied(Lane.WORLD, 1, 0)).closeCode());
        assertEquals(1, h.calls.size(), "a refused acknowledgement releases nothing");
        h.scheduler.applied(first.lane(), first.sequence(), 0);
        assertEquals(2, h.calls.size(), "the acknowledgement releases the next delivery");
        ReceiverScheduler.Delivery second = h.calls.get(1);
        assertEquals(Lane.WORLD, second.lane());
        h.scheduler.applied(second.lane(), second.sequence(), 0);
    }

    @Test
    void oldSessionSignaturesRemainCallable() throws Exception {
        Class<?> session = LaneSession.class;
        Class<?> role = LaneSession.Role.class;
        assertNotNull(session.getConstructor(QuicChannel.class, role, QuicStreamChannel.class, RoutingTable.class,
                LaneGame.class));
        assertNotNull(session.getConstructor(QuicChannel.class, role, QuicStreamChannel.class, RoutingTable.class,
                LaneGame.class, rs.sudoe.quicraft.core.multistream.ReceiveBudget.class));
        assertNotNull(session.getConstructor(QuicChannel.class, role, QuicStreamChannel.class, RoutingTable.class,
                LaneGame.class, rs.sudoe.quicraft.core.multistream.ReceiveBudget.class, SessionContext.class));
        assertNotNull(session.getMethod("send", ProtocolState.class, int.class, byte[].class, SentPacket[].class));
        assertNotNull(session.getMethod("sendBundle", OutboundPacket.class, java.util.List.class,
                OutboundPacket.class));
        assertNotNull(session.getMethod("end"));
        assertNotNull(session.getMethod("abort", int.class));
        assertNotNull(session.getMethod("applied", Lane.class, long.class));
        assertNotNull(session.getMethod("outboundLane", ProtocolState.class, int.class));
        assertNotNull(session.getMethod("bindGame", LaneGame.class));
        assertNotNull(session.getMethod("context"));
        for (String name : new String[] {"onExecutorTickStallStart", "onExecutorTickStallEnd"}) {
            assertDefaultMethod(LaneGame.class, name);
        }
        assertDefaultMethod(LaneGame.class, "onDecodedUncompressed", Lane.class, long.class, byte[].class);
        assertDefaultMethod(LaneGame.class, "onEnqueued", Lane.class, long.class);
        assertDefaultMethod(LaneGame.class, "onApplicationComplete", Lane.class, long.class);
        assertTrue(LaneSession.class.getMethod("context").getReturnType() == SessionContext.class);
    }

    private static void assertDefaultMethod(Class<?> type, String name, Class<?>... params) throws Exception {
        Method method = type.getMethod(name, params);
        assertTrue(method.isDefault(), name + " must be an additive default method");
    }

    /**
     * Drives the real receiver through its public surface. Each offered record is a CONTROL or
     * WORLD envelope with no dependencies, so only the outstanding delivery is ever in {@code calls}.
     */
    private static final class ReceiverHarness {
        final java.util.List<ReceiverScheduler.Delivery> calls = new java.util.ArrayList<ReceiverScheduler.Delivery>();
        final ReceiverScheduler scheduler = new ReceiverScheduler(this::onDecode, this::onDelivery);

        private void onDecode(Envelope e, boolean insideBundle, long now) {
            scheduler.reserveDecoded(e, 0, e.bodyLength(), now);
            scheduler.decoded(e, e.bundle(), e.stateChange(), e.body(), now);
        }

        private void onDelivery(ReceiverScheduler.Delivery d) {
            calls.add(d);
        }

        void offer(Lane lane, long sequence) {
            scheduler.offer(new Envelope(lane, Envelope.Kind.GAME, sequence, new long[] {0, 0, 0}, 0, null,
                    Envelope.Bundle.NONE, false), 0);
        }
    }
}
