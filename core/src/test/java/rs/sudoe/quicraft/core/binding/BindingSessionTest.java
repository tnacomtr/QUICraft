// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.binding.BindingStateMachine.Role;

/**
 * The binding session of docs/protocol.md 12.4 to 12.14: the golden five-message exchange between
 * two sessions, every failure that must close {@code /3} with {@code 0x10}, the deadlines and the
 * quiescence queue of 12.10.
 */
class BindingSessionTest {
    private static final byte[] S = BindingTestData.hex(BindingTestData.S_HEX);
    private static final byte[] E = BindingTestData.hex(BindingTestData.E_HEX);
    private static final byte[] F = BindingTestData.hex(BindingTestData.F_HEX);
    private static final byte[] R = BindingTestData.hex(BindingTestData.R_HEX);
    private static final byte[] ZEROS = new byte[32];
    /** The instant both sessions are told they entered configuration. */
    private static final long ENTERED = 1_000_000_000L;

    private static final long OFFER_WAIT = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long DEADLINE = BindingSession.ACTIVATION_DEADLINE_NANOS;

    @AfterEach
    void noFaults() {
        Faults.set();
    }

    /** The adapter's side: it records what the session asked for and queues payloads to the peer. */
    static final class FakeAdapter implements BindingDriver.Callbacks {
        final List<String> events = new ArrayList<String>();
        final List<byte[]> sent = new ArrayList<byte[]>();
        final Deque<byte[]> inbound = new ArrayDeque<byte[]>();
        BindingSession session;
        FakeAdapter peer;
        int failCode = -1;
        String failReason;

        @Override
        public void send(byte[] payload) {
            events.add("send:" + kindOf(payload));
            sent.add(payload.clone());
            if (peer != null) {
                peer.inbound.add(payload);
            }
        }

        @Override
        public void activateOutbound() {
            events.add("outbound");
        }

        @Override
        public void activateInbound() {
            events.add("inbound");
        }

        @Override
        public void fail(int code, String reason) {
            if (failCode < 0) {
                failCode = code;
                failReason = reason;
                events.add("fail:" + code);
            }
        }

        boolean failed() {
            return failCode >= 0;
        }
    }

    /** A session and its adapter, with a controllable nanosecond clock. */
    static final class Endpoint {
        final AtomicLong clock = new AtomicLong(ENTERED);
        final FakeAdapter adapter = new FakeAdapter();
        final BindingSession session;

        Endpoint(Role role, ExporterSource exporter, byte[] sharedSecret, int mode, int protocol,
                byte[] fingerprint, byte[] routing, long offerWaitNanos) {
            this.session = new BindingSession(role, exporter, sharedSecret, mode, protocol, fingerprint,
                    routing, clock::get, offerWaitNanos, adapter);
            adapter.session = this.session;
        }

        void tick(long nowNanos) {
            clock.set(nowNanos);
            session.tick(nowNanos);
        }

        void receive(byte[] payload) {
            session.onPayload(payload);
        }
    }

    private static Endpoint client() {
        return client(BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE, OFFER_WAIT);
    }

    private static Endpoint client(ExporterSource exporter, byte[] sharedSecret, int mode, long offerWait) {
        return new Endpoint(Role.CLIENT, exporter, sharedSecret, mode, BindingTestData.PROTOCOL_NUMBER,
                F, R, offerWait);
    }

    private static Endpoint server() {
        return server(BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE);
    }

    private static Endpoint server(ExporterSource exporter, byte[] sharedSecret, int mode) {
        return new Endpoint(Role.SERVER, exporter, sharedSecret, mode, BindingTestData.PROTOCOL_NUMBER,
                F, R, 0);
    }

    /** Wires the two adapters to each other and delivers every queued payload, in order. */
    private static void link(Endpoint a, Endpoint b) {
        a.adapter.peer = b.adapter;
        b.adapter.peer = a.adapter;
    }

    private static void pump(Endpoint a, Endpoint b) {
        for (int guard = 0; guard < 64; guard++) {
            if (!a.adapter.inbound.isEmpty()) {
                a.session.onPayload(a.adapter.inbound.poll());
                continue;
            }
            if (!b.adapter.inbound.isEmpty()) {
                b.session.onPayload(b.adapter.inbound.poll());
                continue;
            }
            return;
        }
        throw new AssertionError("the exchange did not settle");
    }

    private static String kindOf(byte[] payload) {
        try {
            return BindingMessage.decode(payload).kind().name();
        } catch (BindingFormatException e) {
            return "?";
        }
    }

    /** The five messages of 12.9, in order, as each side saw them. */
    @Test
    void goldenFiveMessageExchangeActivatesBothEndsInTheDocumentedOrder() {
        Endpoint client = client();
        Endpoint server = server();
        link(client, server);

        server.session.onConfigurationEntered(ENTERED);
        assertEquals(Arrays.asList("send:OFFER"), server.adapter.events,
                "the server speaks first, with OFFER and nothing else");
        assertEquals(1, client.adapter.inbound.size(), "OFFER is on its way to the client");

        client.session.onConfigurationEntered(ENTERED);
        pump(client, server);

        assertEquals(Arrays.asList("send:OFFER", "send:SERVER_PROOF", "send:SERVER_SWITCH",
                "inbound", "outbound"), server.adapter.events,
                "12.11/12.12: SERVER_SWITCH is queued, then the server's inbound and outbound "
                        + "ciphers go off");
        assertEquals(Arrays.asList("send:CLIENT_PROOF", "send:CLIENT_SWITCH", "outbound", "inbound"),
                client.adapter.events,
                "12.11: the client's outbound cipher goes off right after CLIENT_SWITCH, and its "
                        + "inbound one after SERVER_SWITCH verified");
        assertTrue(client.session.isActivated());
        assertTrue(server.session.isActivated());
        assertTrue(client.session.isSettled());
        assertTrue(server.session.isSettled());
        assertFalse(client.adapter.failed(), "no failure on the happy path");
        assertFalse(server.adapter.failed());

        // Both sides agreed on the same tags, which is why each accepted the other's messages:
        // two messages from the client and three from the server.
        assertEquals(2, client.adapter.sent.size());
        assertEquals(3, server.adapter.sent.size());
        assertArrayEquals(ZEROS, Arrays.copyOfRange(server.adapter.sent.get(0), 71, 103),
                "OFFER's tag is all zeros");
    }

    @Test
    void offlineModeRunsTheSameExchangeWithZeroTags() {
        Endpoint client = client(BindingTestData.exporter(E), null, BindingTestData.MODE_OFFLINE, OFFER_WAIT);
        Endpoint server = server(BindingTestData.exporter(E), null, BindingTestData.MODE_OFFLINE);
        link(client, server);
        client.session.onConfigurationEntered(ENTERED);
        server.session.onConfigurationEntered(ENTERED);
        pump(client, server);
        assertTrue(client.session.isActivated());
        assertTrue(server.session.isActivated());
        for (byte[] payload : server.adapter.sent) {
            assertArrayEquals(ZEROS, Arrays.copyOfRange(payload, 71, 103), "12.19: mode 0 tags are zeros");
        }
    }

    @Test
    void aTamperedOrMismatchedOfferClosesWith0x10() throws Exception {
        // A tag that is not zeros.
        byte[] nonZeroTag = BindingMessage.offer(BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R).encode();
        nonZeroTag[102] = 1;
        assertBindingFailure(nonZeroTag);

        // A different fingerprint F'.
        byte[] otherF = new byte[32];
        Arrays.fill(otherF, (byte) 7);
        assertBindingFailure(BindingMessage.offer(BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, otherF, R).encode());

        // A different routing digest R'.
        byte[] otherR = new byte[32];
        Arrays.fill(otherR, (byte) 9);
        assertBindingFailure(BindingMessage.offer(BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, otherR).encode());

        // Another mode: 12.19 forbids mode 0 for an online connection.
        assertBindingFailure(BindingMessage.offer(BindingTestData.MODE_OFFLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R).encode());

        // Another protocol number.
        assertBindingFailure(BindingMessage.offer(BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER + 1, F, R).encode());
    }

    @Test
    void wrongLengthVersionAndKindCloseWith0x10() throws Exception {
        byte[] offer = BindingMessage.offer(BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R).encode();

        byte[] shortPayload = Arrays.copyOf(offer, 102);
        assertBindingFailure(shortPayload);

        byte[] trailing = Arrays.copyOf(offer, 104);
        assertBindingFailure(trailing);

        byte[] badVersion = offer.clone();
        badVersion[0] = 3;
        assertBindingFailure(badVersion);

        byte[] unknownKind = offer.clone();
        unknownKind[1] = 5;
        assertBindingFailure(unknownKind);
    }

    @Test
    void reflectionWrongDirectionDuplicatesAndWrongOrderCloseWith0x10() throws Exception {
        // A client-bound kind the client must never accept from itself: CLIENT_PROOF is server-bound.
        Endpoint endpoint = client();
        endpoint.session.onConfigurationEntered(ENTERED);
        assertBindingFailure(endpoint, BindingMessage.of(BindingKind.CLIENT_PROOF,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R, ZEROS).encode());

        // SERVER_SWITCH before OFFER is the wrong step of the fixed order (12.9).
        Endpoint early = client();
        early.session.onConfigurationEntered(ENTERED);
        assertBindingFailure(early, BindingMessage.of(BindingKind.SERVER_SWITCH,
                BindingTestData.MODE_ONLINE, BindingTestData.PROTOCOL_NUMBER, F, R, ZEROS).encode());

        // A duplicate OFFER after a valid one (12.9).
        Endpoint duplicated = client();
        duplicated.session.onConfigurationEntered(ENTERED);
        byte[] offer = BindingMessage.offer(BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R).encode();
        duplicated.session.onPayload(offer);
        assertFalse(duplicated.adapter.failed(), "the first OFFER is fine");
        assertBindingFailure(duplicated, offer);

        // OFFER sent to a server is the wrong direction (12.9).
        Endpoint server = server();
        server.session.onConfigurationEntered(ENTERED);
        assertBindingFailure(server, offer);
    }

    @Test
    void anUnsolicitedPayloadBeforeConfigurationClosesWith0x10() throws Exception {
        Endpoint endpoint = client();
        assertBindingFailure(endpoint, BindingMessage.offer(BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R).encode());
    }

    /** Every one of the cases above must end the connection with {@code 0x10} and nothing else. */
    private static void assertBindingFailure(byte[] payload) {
        Endpoint endpoint = client();
        endpoint.session.onConfigurationEntered(ENTERED);
        assertBindingFailure(endpoint, payload);
    }

    private static void assertBindingFailure(Endpoint endpoint, byte[] payload) {
        endpoint.session.onPayload(payload);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, endpoint.adapter.failCode,
                "12.14: every binding failure closes the connection with 0x10");
        assertFalse(endpoint.session.isActivated());
        assertTrue(endpoint.session.isSettled());
    }

    @Test
    void theClientFailsWhenNoOfferArrivesWithinItsOfferWait() {
        Endpoint endpoint = client();
        endpoint.session.onConfigurationEntered(ENTERED);

        endpoint.tick(ENTERED + OFFER_WAIT - 1);
        assertFalse(endpoint.adapter.failed(), "still inside the offer wait");
        endpoint.tick(ENTERED + OFFER_WAIT);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, endpoint.adapter.failCode);
    }

    @Test
    void theTotalDeadlineEndsTheExchangeWithoutAnOfferWait() {
        // 12.14: a missing OFFER also expires the ten second deadline.
        Endpoint endpoint = client(BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE, 0);
        endpoint.session.onConfigurationEntered(ENTERED);
        endpoint.tick(ENTERED + DEADLINE - 1);
        assertFalse(endpoint.adapter.failed());
        endpoint.tick(ENTERED + DEADLINE);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, endpoint.adapter.failCode);
    }

    @Test
    void theServerIsHeldToTheSameTenSecondDeadline() {
        Endpoint endpoint = server();
        endpoint.session.onConfigurationEntered(ENTERED);
        endpoint.tick(ENTERED + DEADLINE - 1);
        assertFalse(endpoint.adapter.failed());
        endpoint.tick(ENTERED + DEADLINE);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, endpoint.adapter.failCode);
    }

    @Test
    void aTicksClockIsTheDeadlineAuthorityAndActivationStopsTheTimers() {
        // A caller that ticks with a stale reading cannot extend the deadline.
        Endpoint stale = client(BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE, 0);
        stale.session.onConfigurationEntered(ENTERED);
        stale.clock.set(ENTERED + DEADLINE);
        stale.session.tick(ENTERED);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, stale.adapter.failCode);

        // Before the exchange starts there is nothing to time.
        Endpoint early = client();
        early.tick(ENTERED + DEADLINE * 2);
        assertFalse(early.adapter.failed());

        // After activation the timers are done.
        Endpoint client = client();
        Endpoint server = server();
        link(client, server);
        client.session.onConfigurationEntered(ENTERED);
        server.session.onConfigurationEntered(ENTERED);
        pump(client, server);
        client.tick(ENTERED + DEADLINE * 2);
        server.tick(ENTERED + DEADLINE * 2);
        assertFalse(client.adapter.failed());
        assertFalse(server.adapter.failed());
    }

    @Test
    void quiescenceQueuesUpToItsBoundsAndOverflowClosesWith0x10() {
        Endpoint server = server();
        server.session.onConfigurationEntered(ENTERED);
        assertTrue(server.session.isQuiescent(), "12.10: the server queues ordinary packets from OFFER on");
        for (int i = 0; i < BindingSession.MAX_QUEUED_PACKETS; i++) {
            assertTrue(server.session.queueOrdinary("packet-" + i, 1), "packet " + i + " fits");
        }
        assertFalse(server.session.queueOrdinary("one-too-many", 1), "the 257th packet overflows");
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, server.adapter.failCode);
        assertTrue(server.adapter.failReason.contains("overflow"));
    }

    @Test
    void theEightMebibyteBoundIsEnforcedToo() {
        Endpoint server = server();
        server.session.onConfigurationEntered(ENTERED);
        assertTrue(server.session.queueOrdinary("big", (int) BindingSession.MAX_QUEUED_BYTES),
                "exactly 8 MiB is still allowed");
        assertFalse(server.session.queueOrdinary("one-more", 1));
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, server.adapter.failCode);
    }

    @Test
    void onlyTheQuiescenceWindowQueuesAndReleaseHandsPacketsBackInOrder() {
        Endpoint client = client();
        // Before the client has accepted an OFFER nothing is paused: the caller sends normally.
        assertFalse(client.session.queueOrdinary("early", 1));

        Endpoint server = server();
        server.session.onConfigurationEntered(ENTERED);
        assertTrue(server.session.queueOrdinary("a", 1));
        assertTrue(server.session.queueOrdinary("b", 2));
        assertEquals(Arrays.asList("a", "b"), server.session.releaseQueued());
        assertEquals(Arrays.asList(), server.session.releaseQueued(), "releasing twice gives nothing");

        // After activation, ordinary packets are records: not queued, and not a failure either.
        Endpoint client2 = client();
        Endpoint server2 = server();
        link(client2, server2);
        client2.session.onConfigurationEntered(ENTERED);
        server2.session.onConfigurationEntered(ENTERED);
        pump(client2, server2);
        assertTrue(server2.session.isActivated());
        assertFalse(server2.session.isQuiescent());
        assertFalse(server2.session.queueOrdinary("late", 1));
        assertFalse(server2.adapter.failed());
    }

    @Test
    void aNativeExporterErrorClosesWith0x10() {
        ExporterSource noNative = () -> {
            throw new UnsatisfiedLinkError("no netty_quiche42 in java.library.path");
        };
        Endpoint endpoint = client(noNative, S, BindingTestData.MODE_ONLINE, OFFER_WAIT);
        endpoint.session.onConfigurationEntered(ENTERED);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, endpoint.adapter.failCode);
        assertTrue(endpoint.adapter.failReason.contains("EXPORTER_ERROR"), endpoint.adapter.failReason);
        assertFalse(endpoint.session.isActivated());

        Endpoint shortValue = client(BindingTestData.exporter(new byte[16]), S,
                BindingTestData.MODE_ONLINE, OFFER_WAIT);
        shortValue.session.onConfigurationEntered(ENTERED);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, shortValue.adapter.failCode);
    }

    @Test
    void everyFaultStageOfTheExchangeClosesWith0x10() throws Exception {
        Faults.set(Faults.BINDING_EXPORTER);
        Endpoint exporter = client();
        exporter.session.onConfigurationEntered(ENTERED);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, exporter.adapter.failCode);
        Faults.set();

        Faults.set(Faults.BINDING_STEP);
        Endpoint step = client();
        step.session.onConfigurationEntered(ENTERED);
        step.session.onPayload(BindingMessage.offer(BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R).encode());
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, step.adapter.failCode);
        Faults.set();

        Faults.set(Faults.LANE_ACTIVATE);
        Endpoint client = client();
        Endpoint server = server();
        link(client, server);
        client.session.onConfigurationEntered(ENTERED);
        server.session.onConfigurationEntered(ENTERED);
        // OFFER, CLIENT_PROOF, SERVER_PROOF, then the client's CLIENT_SWITCH hits the fault.
        pump(client, server);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, client.adapter.failCode);
        assertFalse(client.session.isActivated());
    }

    @Test
    void constructorRejectsLocalMisuse() {
        assertThrows(IllegalArgumentException.class, () -> new BindingSession(Role.CLIENT,
                BindingTestData.exporter(E), S, 2, BindingTestData.PROTOCOL_NUMBER, F, R,
                System::nanoTime, OFFER_WAIT, new FakeAdapter()));
        assertThrows(IllegalArgumentException.class, () -> new BindingSession(Role.CLIENT,
                BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE, -1, F, R,
                System::nanoTime, OFFER_WAIT, new FakeAdapter()));
        assertThrows(IllegalArgumentException.class, () -> new BindingSession(Role.CLIENT,
                BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, new byte[31], R, System::nanoTime, OFFER_WAIT,
                new FakeAdapter()));
        assertThrows(IllegalArgumentException.class, () -> new BindingSession(Role.CLIENT,
                BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, new byte[31], System::nanoTime, OFFER_WAIT,
                new FakeAdapter()));
        assertThrows(IllegalArgumentException.class, () -> new BindingSession(Role.CLIENT,
                BindingTestData.exporter(E), null, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R, System::nanoTime, OFFER_WAIT,
                new FakeAdapter()));
        assertThrows(IllegalArgumentException.class, () -> new BindingSession(Role.CLIENT,
                BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R, System::nanoTime, -1, new FakeAdapter()));
        assertThrows(NullPointerException.class, () -> new BindingSession(Role.CLIENT,
                BindingTestData.exporter(E), S, BindingTestData.MODE_ONLINE,
                BindingTestData.PROTOCOL_NUMBER, F, R, System::nanoTime, OFFER_WAIT, null));
    }

    @Test
    void queueOrdinaryRejectsLocalMisuse() {
        Endpoint server = server();
        server.session.onConfigurationEntered(ENTERED);
        assertThrows(IllegalArgumentException.class, () -> server.session.queueOrdinary(null, 1));
        assertThrows(IllegalArgumentException.class, () -> server.session.queueOrdinary("packet", -1));
    }

    @Test
    void aFailureReasonCarriesNoSecretMaterial() {
        Endpoint endpoint = client();
        endpoint.session.onConfigurationEntered(ENTERED);
        endpoint.session.onPayload(new byte[103]);
        assertEquals(Protocol.CLOSE_BINDING_FAILURE, endpoint.adapter.failCode);
        String reason = endpoint.adapter.failReason;
        assertFalse(reason.contains(BindingTestData.hex(S)), "no shared secret");
        assertFalse(reason.contains(BindingTestData.hex(E)), "no exporter value");
        assertFalse(reason.contains(BindingTestData.hex(F)), "no fingerprint bytes");
        assertFalse(reason.contains(BindingTestData.hex(R)), "no routing digest bytes");
    }
}
