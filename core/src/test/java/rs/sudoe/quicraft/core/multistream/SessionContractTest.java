// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.binding.BindingFailure;
import rs.sudoe.quicraft.core.binding.BindingMachineResult;
import rs.sudoe.quicraft.core.binding.BindingResult;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.binding.ExporterSource;
import rs.sudoe.quicraft.core.routing.Lane;

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
        for (int n = 1; n <= 5; n++) {
            // Client offers 1..5; the server's configured maximum and preference both equal n.
            SessionContext selected = SessionContext.select(parse(PreambleV2.encode(null, 1, 5)), n, n);
            assertEquals(n, selected.shardCount(), "N=" + n);
            assertEquals(n + 3, selected.logicalStreamCount());
            assertEquals(n + 2, selected.ackIndex());
            assertEquals(selected, SessionContext.of(n));
            assertEquals(n, selected.shardCount(), "the selection never changes after it is made");
            // The server's reply names N in both fields, and the client accepts it inside its offer.
            assertEquals(selected, SessionContext.accept(n, n, 1, 5));
        }
        // A narrow offer clamps the preference into the intersection, never outside it.
        assertEquals(4, SessionContext.select(parse(PreambleV2.encode(null, 4, 5)), 5, 3).shardCount());
        assertEquals(4, SessionContext.select(parse(PreambleV2.encode(null, 1, 4)), 5, 5).shardCount());
    }

    @Test
    void malformedRangesCloseWithProtocolViolation() {
        // Length 4, version 2, no token, then min/max that the parser must refuse (13.2).
        assertEquals(0x1, closeCodeOf(new byte[] {4, 2, 0, 0, 5}), "min 0");
        assertEquals(0x1, closeCodeOf(new byte[] {4, 2, 0, 1, 6}), "max 6 is above the cap of 5");
        assertEquals(0x1, closeCodeOf(new byte[] {4, 2, 0, 5, 3}), "min above max");
        assertEquals(0x1, closeCodeOf(new byte[] {4, 3, 0, 1, 5}), "unknown version");
        // The client side refuses a reply that is not one shard count.
        assertEquals(0x1, reply(2, 3));
        assertEquals(0x1, reply(0, 0));
        assertEquals(0x1, reply(6, 6));
    }

    @Test
    void emptyIntersectionOrSelectionOutsideTheOfferCloseWithNoCommonShards() throws Exception {
        // Offer 4..5 against a configured maximum of 3 leaves no common shard count.
        MultistreamProtocolException none = assertThrows(MultistreamProtocolException.class,
                () -> SessionContext.select(parse(PreambleV2.encode(null, 4, 5)), 3, 3));
        assertEquals(0x10, none.closeCode());
        // A reply naming 4 when the client offered only 1..3 is an invalid selection.
        assertEquals(0x10, reply(4, 4, 1, 3));
    }

    @Test
    void invalidSharedCountIsLocalMisuse() {
        assertThrows(IllegalArgumentException.class, () -> SessionContext.of(0));
        assertThrows(IllegalArgumentException.class, () -> SessionContext.of(6));
    }

    private static int reply(int min, int max) {
        return reply(min, max, 1, 5);
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
        // The ACK stream is logical index N+2 = 4, QUIC id 1+4N = 9 (13.3).
        assertEquals(9L, two.quicIdOf(4));
        assertEquals(4, two.logicalIndexOf(9L));
        assertEquals(4, two.ackIndex());
        assertThrows(IllegalArgumentException.class, () -> two.quicIdOf(5));
        assertThrows(IllegalArgumentException.class, () -> two.logicalIndexOf(13L));
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

    /** The N-taking constructor carries the negotiated N into the binding (12.7, 13.2). */
    @Test
    void bindingSessionCarriesTheSelectedN() throws Exception {
        ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        try {
            Recorder serverSide = new Recorder();
            BindingSession server = bindingSession(BindingStateMachine.Role.SERVER, 3, serverSide, timers);
            Recorder matching = new Recorder();
            BindingSession client = bindingSession(BindingStateMachine.Role.CLIENT, 3, matching, timers);
            client.onConfigurationEntered(0L);
            server.onConfigurationEntered(0L);
            assertFalse(serverSide.sent.isEmpty(), "the server opened the binding with its OFFER");
            client.onPayload(serverSide.sent.get(0));
            assertNull(matching.failReason, "the matching N is accepted");
            assertFalse(matching.sent.isEmpty(), "the client answers the OFFER with its CLIENT_PROOF");

            Recorder mismatched = new Recorder();
            BindingSession otherN = bindingSession(BindingStateMachine.Role.CLIENT, 2, mismatched, timers);
            otherN.onConfigurationEntered(0L);
            otherN.onPayload(serverSide.sent.get(0));
            assertEquals(Protocol.CLOSE_BINDING_FAILURE, mismatched.failCode);
            assertTrue(mismatched.failReason.contains("WRONG_SHARD_COUNT"), mismatched.failReason);
            assertTrue(mismatched.sent.isEmpty(), "a mismatched N sends nothing back");
            client.close();
            server.close();
            otherN.close();
        } finally {
            timers.shutdownNow();
        }
    }

    private static BindingSession bindingSession(BindingStateMachine.Role role, int shardCount,
                                                 BindingDriver.Callbacks callbacks,
                                                 ScheduledExecutorService timers) {
        return new BindingSession(role, () -> EXPORTER.clone(), SECRET.clone(), ONLINE, PROTOCOL, shardCount,
                FINGERPRINT.clone(), ROUTING.clone(), () -> 0L, BindingSession.ACTIVATION_DEADLINE_NANOS,
                callbacks, timers, () -> true);
    }

    /** Records the binding's outbound payloads and its failure, nothing else. */
    static final class Recorder implements BindingDriver.Callbacks {
        final List<byte[]> sent = new ArrayList<byte[]>();
        volatile int failCode;
        volatile String failReason;

        @Override
        public void send(byte[] payload) {
            sent.add(payload);
        }

        @Override
        public void activateOutbound() { }

        @Override
        public void activateInbound() { }

        @Override
        public void fail(int code, String reason) {
            failCode = code;
            failReason = reason;
        }
    }

    private static BindingStateMachine machine(BindingMachineResult result) {
        assertNotNull(result.machine(), "expected a machine but got " + result);
        return result.machine();
    }

    // --- Additive API (13.10).

    @Test
    void additiveLaneGameCallbacksRemainDefault() throws Exception {
        for (String name : new String[] {"onExecutorTickStallStart", "onExecutorTickStallEnd"}) {
            assertDefaultMethod(LaneGame.class, name);
        }
        assertDefaultMethod(LaneGame.class, "onDecodedUncompressed", Lane.class, int.class, long.class, byte[].class);
        assertDefaultMethod(LaneGame.class, "onEnqueued", Lane.class, int.class, long.class);
        assertDefaultMethod(LaneGame.class, "onApplicationComplete", Lane.class, int.class, long.class);
    }

    private static void assertDefaultMethod(Class<?> type, String name, Class<?>... params) throws Exception {
        Method method = type.getMethod(name, params);
        assertTrue(method.isDefault(), name + " must be an additive default method");
    }

    /**
     * 13.4: with compression on, a GAME frame starts with a Data Length VarInt. Reading a bundle
     * member's id must skip it; without the session's compression flag the stored length (0) would
     * be taken for the id.
     */
    @Test
    void aStoredCompressionFrameReadsItsBundleMemberId() {
        byte[] stored = storedFrame(42);
        assertEquals(42, NStreamSession.packetIdOf(stored, true));
        assertEquals(0, NStreamSession.packetIdOf(stored, false),
                "without compression the stored Data Length is read as the packet id");
    }

    /** {@code VarInt(inner length) || VarInt(0) || id || body} (13.4, stored). */
    private static byte[] storedFrame(int packetId) {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        VarInt.write(packet, packetId);
        packet.write('x');
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        VarInt.write(frame, packet.size() + 1);
        VarInt.write(frame, 0);
        frame.write(packet.toByteArray(), 0, packet.size());
        return frame.toByteArray();
    }
}
