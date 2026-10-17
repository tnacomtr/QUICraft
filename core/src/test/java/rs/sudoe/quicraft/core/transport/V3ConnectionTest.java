// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LaneSession;
import rs.sudoe.quicraft.core.multistream.PreambleV2;
import rs.sudoe.quicraft.core.multistream.SessionContext;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * The {@code /3} connection path of docs/protocol.md 13.1-13.3, 12.11-12.12 and the 0-RTT rules
 * of 12.18 (user decision 2026-10-16): dial, accept, the three lanes, the ALPN partition and the
 * failure modes. Needs QUICraft's patched native, so the whole class skips on an upstream one.
 */
class V3ConnectionTest {
    /** Everything defaults to CONTROL, which is all a one-record round trip needs (13.6). */
    static final RoutingTable TABLE = RoutingTableLoader.parse(tableJson());

    private static String tableJson() {
        String limit = "{\"maxServerboundRecord\":32768,\"maxServerboundRealtimeRecord\":510,"
                + "\"maxDecodedFootprint\":1048576,\"ordinaryReadAllowance\":5619,"
                + "\"decodeFootprintBound\":\"primitive.raw_copies.v1\"}";
        StringBuilder limits = new StringBuilder();
        for (String state : new String[] {"handshaking", "status", "login", "configuration", "play"}) {
            if (limits.length() != 0) limits.append(',');
            limits.append('"').append(state).append("\":").append(limit);
        }
        return "{\"schema\":2,\"protocol\":4242,\"default\":\"global\",\"extractors\":{},"
                + "\"serverboundLimits\":{" + limits + "},\"routes\":[]}";
    }

    /** Two VarInt-framed packets, a handshake and a Login Start ({@link rs.sudoe.quicraft.core.transport.FlightRecorder}). */
    static final byte[] LOGIN_FLIGHT = {3, 0, 1, 2, 2, 0, 7};

    static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    @AfterEach
    void reset() {
        Faults.set();
        QuicServer.confirmMillis = 10_000;
        QuicServer.v3StreamsForTests = -1;
        QuicClient.setGraceFloorForTests(0);
        QuicClient.setBeforeConnectCompletionForTests(null);
        QuicClient.forgetSessions();
    }

    private static void requireV3() {
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
    }

    // --- The tests.

    @Test
    void productionClientAcceptsAllNegotiatedChunkStreams() throws Exception {
        requireV3();
        try (Pair pair = new Pair(connection -> { })) {
            assertTrue(await(pair.serverSide::handshakeComplete, 5_000), "the server's handshake");
            activate(pair.serverSide, pair.serverGame, 4242);
            activate(pair.client, pair.clientGame, -1);
            assertTrue(pair.client.session() instanceof rs.sudoe.quicraft.core.multistream.NStreamSession);
            int n = pair.client.sessionContext().shardCount();
            assertTrue(n > 1, "exercise the old id-1-only admission defect");
            java.lang.reflect.Method channel = rs.sudoe.quicraft.core.multistream.NStreamSession.class
                    .getDeclaredMethod("channelOf", int.class);
            channel.setAccessible(true);
            assertTrue(await(() -> {
                CompletableFuture<Boolean> ready = new CompletableFuture<>();
                pair.client.execute(() -> {
                    try {
                        for (int i = 0; i < n; i++) {
                            io.netty.handler.codec.quic.QuicStreamChannel stream =
                                    (io.netty.handler.codec.quic.QuicStreamChannel)
                                            channel.invoke(pair.client.session(), i + 2);
                            if (stream == null || stream.streamId() != 1 + 4L * i) {
                                ready.complete(false);
                                return;
                            }
                        }
                        ready.complete(true);
                    } catch (Exception e) {
                        ready.completeExceptionally(e);
                    }
                });
                try {
                    return ready.get(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, 5_000), "every negotiated CHUNK was admitted through QuicClient");
            pair.serverSide.session().send(ProtocolState.PLAY, 0, frame(0, "still-open"));
            assertTrue(await(() -> pair.clientGame.count() == 1, 5_000));
        }
    }

    @Test
    void aThreeDialAndListenerConnectExportEqualValuesAndRoundTripARecord() throws Exception {
        requireV3();
        try (Pair pair = new Pair(connection -> { })) {
            assertTrue(await(pair.serverSide::handshakeComplete, 5_000), "the server's handshake");
            assertTrue(pair.client.handshakeComplete(), "the client's handshake");
            activate(pair.serverSide, pair.serverGame, 4242);
            activate(pair.client, pair.clientGame, -1);

            pair.serverGame.echo = true;
            pair.client.session().send(ProtocolState.PLAY, 0, frame(0, "hello"));
            assertTrue(await(() -> pair.clientGame.count() >= 1, 5_000), "the echoed record");

            assertArrayEquals(exporter(pair.client), exporter(pair.serverSide),
                    "both ends of one connection derive the same 12.7 exporter value");
            long rtt = rtt(pair.client);
            assertTrue(rtt >= -1, "smoothedRttMillis never throws");
        }
    }

    @Test
    void activatedConnectionRejectsGameWritesButPreActivationWriteStillWorks() throws Exception {
        requireV3();
        try (Pair pair = new Pair(connection -> { })) {
            Loopback.Collector login = new Loopback.Collector();
            pair.serverSide.setListener(login);
            pair.serverSide.setAutoRead(true);
            pair.client.write(ByteBuffer.wrap(LOGIN_FLIGHT));
            assertArrayEquals(LOGIN_FLIGHT, login.await(LOGIN_FLIGHT.length, 5, TimeUnit.SECONDS),
                    "activation must follow the login flight, not race it");
            activate(pair.serverSide, pair.serverGame, 4242);
            activate(pair.client, pair.clientGame, -1);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> pair.client.write(ByteBuffer.wrap(LOGIN_FLIGHT)));
            assertTrue(failure.getMessage().contains("session"));
        }
    }

    @Test
    void aTwoClientNeverHandshakesWithAThreeListener() throws Exception {
        requireV3();
        try (QuicServer server = bindServer(connection -> { })) {
            CompletableFuture<QuicByteStream> attempt = QuicClient.connect(server.localAddress(),
                    identity.fingerprint(), TransportConfig.DEFAULT);
            assertThrows(ExecutionException.class, () -> attempt.get(20, TimeUnit.SECONDS),
                    "the ALPN mismatch must fail the handshake");
        }
    }

    @Test
    void aThreeClientNeverHandshakesWithATwoListener() throws Exception {
        requireV3();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                stream -> { })) {
            CompletableFuture<V3Connection> attempt = QuicClient.connectV3(server.localAddress(),
                    identity.fingerprint(), TABLE, null);
            assertThrows(ExecutionException.class, () -> attempt.get(20, TimeUnit.SECONDS),
                    "the ALPN mismatch must fail the handshake");
        }
    }

    @Test
    void cancellingAfterServerAcceptsV3SendsPeerVisibleClose() throws Exception {
        requireV3();
        CountDownLatch completionPaused = new CountDownLatch(1);
        CountDownLatch releaseCompletion = new CountDownLatch(1);
        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<V3Connection> serverConnection = new AtomicReference<>();
        Loopback.Collector collector = new Loopback.Collector();
        QuicClient.setBeforeConnectCompletionForTests(() -> awaitCompletionRelease(completionPaused,
                releaseCompletion));
        try (QuicServer server = bindServer(connection -> {
            connection.setListener(collector);
            connection.setAutoRead(true);
            serverConnection.set(connection);
            accepted.countDown();
        })) {
            CompletableFuture<V3Connection> attempt = QuicClient.connectV3(server.localAddress(),
                    identity.fingerprint(), TABLE, null);
            assertTrue(completionPaused.await(5, TimeUnit.SECONDS), "client completion hook reached");
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "server accepted the connection");
            assertTrue(attempt.cancel(false), "the /3 connect future is pending after server acceptance");
            releaseCompletion.countDown();
            assertTrue(collector.closed.await(2, TimeUnit.SECONDS),
                    "the accepted /3 connection must see CONNECTION_CLOSE before its deadline");
            assertFalse(serverConnection.get().isOpen(), "server connection is closed");
        } finally {
            releaseCompletion.countDown();
            QuicClient.setBeforeConnectCompletionForTests(null);
        }
    }

    private static void awaitCompletionRelease(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release connect completion");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting to release connect completion", e);
        }
    }

    @Test
    void aClientStreamFourBeforeTheServersActivationIsFatal() throws Exception {
        requireV3();
        try (Pair pair = new Pair(connection -> { })) { // the server never activates
            CompletableFuture<Integer> close = watchClose(channelOf(pair.client));
            QuicStreamChannel four = channelOf(pair.client)
                    .createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(4, four.streamId());
            four.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {9}));
            assertEquals(0x11, close.get(10, TimeUnit.SECONDS).intValue(),
                    "13.3: stream 4 before the server's activation is fatal 0x11");
        }
    }

    @Test
    void aThirdClientStreamIsFatal() throws Exception {
        requireV3();
        QuicServer.v3StreamsForTests = 3; // otherwise the two-stream limit hides the branch (13.13)
        try (Pair pair = new Pair(connection -> { })) {
            activate(pair.serverSide, pair.serverGame, 4242);
            activate(pair.client, pair.clientGame, -1);
            QuicStreamChannel third = channelOf(pair.client)
                    .createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(8, third.streamId());
            third.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {9}));
            assertEquals(0x11, pair.clientGame.closed.get(10, TimeUnit.SECONDS).intValue(),
                    "13.3: any stream other than 0 and 4 is fatal 0x11");
        }
    }

    @Test
    void aTwoTokenIsNeverOfferedOnAThreeDial() throws Exception {
        requireV3();
        InetSocketAddress address;
        try (QuicServer two = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                Loopback.echo(new CompletableFuture<QuicByteStream>()))) {
            address = two.localAddress();
            EarlyFlight early = new EarlyFlight("inputs");
            QuicByteStream stream = QuicClient.connect(address, identity.fingerprint(), TransportConfig.DEFAULT,
                    early).get(10, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            stream.write(ByteBuffer.wrap(LOGIN_FLIGHT));
            stream.flush();
            assertArrayEquals(LOGIN_FLIGHT, collector.await(LOGIN_FLIGHT.length, 5, TimeUnit.SECONDS));
            stream.close();

            assertTrue(EarlyCache.INSTANCE.hasToken(EarlyCache.server(address, identity.fingerprint())),
                    "the /2 join must have a /2 early token");
            assertFalse(EarlyCache.INSTANCE.hasToken(
                            EarlyCache.server(address, identity.fingerprint(), Protocol.ALPN_V3)),
                    "12.17: /2 tokens live in the /2 partition only");
            assertNotEquals(EarlyCache.server(address, identity.fingerprint()),
                    EarlyCache.server(address, identity.fingerprint(), Protocol.ALPN_V3));
        }

        try (QuicServer three = bindServer(connection -> { })) {
            primeV3Join(three.localAddress());
            InetSocketAddress threeAddress = three.localAddress();
            assertTrue(EarlyCache.INSTANCE.hasToken(
                            EarlyCache.server(threeAddress, identity.fingerprint(), Protocol.ALPN_V3)),
                    "the /3 join must have a /3 early token");
            assertFalse(EarlyCache.INSTANCE.hasToken(EarlyCache.server(threeAddress, identity.fingerprint())),
                    "12.17: /3 tokens live in the /3 partition only");
        }
    }

    @Test
    void aResumedThreeDialSendsZeroRttBeforeTheHandshakeCompletes() throws Exception {
        requireV3();
        List<Boolean> handshakeAtAccept = Collections.synchronizedList(new ArrayList<Boolean>());
        AtomicReference<V3Connection> serverSide = new AtomicReference<V3Connection>();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        boolean[] dataBeforeHandshake = new boolean[1];
        try (QuicServer server = bindServer(connection -> {
            handshakeAtAccept.add(connection.handshakeComplete());
            serverSide.set(connection);
            connection.setListener(new QuicByteStream.Listener() {
                @Override
                public void onData(ByteBuffer data) {
                    if (!connection.handshakeComplete()) {
                        dataBeforeHandshake[0] = true;
                    }
                    byte[] chunk = new byte[data.remaining()];
                    data.duplicate().get(chunk);
                    synchronized (received) {
                        received.write(chunk, 0, chunk.length);
                    }
                    connection.write(data);
                    connection.flush();
                }

                @Override
                public void onWritabilityChanged(boolean writable) { }

                @Override
                public void onClosed(Throwable cause) { }
            });
            connection.setAutoRead(true);
        }); DelayRelay relay = new DelayRelay(server.localAddress(), 30)) {
            // Prime a /3 ticket, early token and recorded flight for the same address.
            primeV3Join(relay.address());
            int before = handshakeAtAccept.size();

            EarlyFlight early = new EarlyFlight("inputs");
            V3Connection resumed = QuicClient.connectV3(relay.address(), identity.fingerprint(), TABLE, early)
                    .get(10, TimeUnit.SECONDS);
            assertTrue(early.sent(), () -> "no 0-RTT: " + QuicClient.lastEarlyOutcome());
            resumed.write(ByteBuffer.wrap(LOGIN_FLIGHT));
            resumed.flush();

            assertTrue(await(() -> receivedSize(received) >= LOGIN_FLIGHT.length, 5_000),
                    "the early Login Start must reach the server");
            assertTrue(await(() -> handshakeAtAccept.size() > before, 5_000), "the server accepted it early");
            assertFalse(handshakeAtAccept.get(handshakeAtAccept.size() - 1),
                    "12.18/12.7: the server sees the early bytes before its own handshake completes");
            assertTrue(dataBeforeHandshake[0], "the early Login Start arrived before handshake completion");
            assertTrue(await(() -> serverSide.get().handshakeComplete(), 5_000),
                    "and the handshake completes afterwards");
            assertTrue(await(resumed::handshakeComplete, 5_000), "the client's handshake completes too");
            resumed.close();
        }
    }

    @Test
    void aBypassResumptionDialIsAFullHandshakeAndKeepsTheStoredTicket() throws Exception {
        requireV3();
        try (QuicServer server = bindServer(connection -> { })) {
            InetSocketAddress address = server.localAddress();
            primeV3Join(address);
            assertTrue(QuicClient.hasSessionTicket(address, identity.fingerprint()),
                    "the priming join must have a resumable /3 ticket");

            // bypassResumption: a full handshake, so no early data even though the material is there.
            EarlyFlight early = new EarlyFlight("inputs");
            V3Connection connection = QuicClient.connectV3(address, identity.fingerprint(), TABLE, early, true)
                    .get(10, TimeUnit.SECONDS);
            assertFalse(early.sent(), "12.16's re-bind is a full handshake, never 0-RTT");
            assertTrue(QuicClient.hasSessionTicket(address, identity.fingerprint()),
                    "12.16: a bypassing dial leaves the stored ticket untouched");
            connection.close();
        }
    }

    @Test
    void anUnknownEarlyTokenWaitsForTheHandshake() throws Exception {
        requireV3();
        // Keep the client from abandoning the 0-RTT connection before the server's handshake ends,
        // so the wait path (not the §8 retry) is what this test observes.
        QuicClient.setGraceFloorForTests(2_000);
        List<Boolean> handshakeAtAccept = Collections.synchronizedList(new ArrayList<Boolean>());
        try (QuicServer server = bindServer(connection -> handshakeAtAccept.add(connection.handshakeComplete()));
                DelayRelay relay = new DelayRelay(server.localAddress(), 30)) {
            InetSocketAddress address = relay.address();
            primeV3Join(address);

            // Replace the token the priming join earned with one the server never issued.
            EarlyCache.INSTANCE.putToken(EarlyCache.server(address, identity.fingerprint(), Protocol.ALPN_V3),
                    new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
            EarlyFlight early = new EarlyFlight("inputs");
            V3Connection stream = QuicClient.connectV3(address, identity.fingerprint(), TABLE, early)
                    .get(10, TimeUnit.SECONDS);
            assertTrue(early.sent(), () -> "no 0-RTT: " + QuicClient.lastEarlyOutcome());
            stream.close();

            assertTrue(await(() -> handshakeAtAccept.size() >= 2, 5_000), "the stream is delivered");
            assertTrue(handshakeAtAccept.get(handshakeAtAccept.size() - 1),
                    "an unknown token must wait for the handshake (12.18, docs/protocol.md §8)");
        }
    }

    @Test
    void enablingThreeKeepsTheCallersConfiguration() throws Exception {
        requireV3();
        TransportConfig custom = TransportConfig.builder()
                .maxOpen(7)
                .maxOpenPerIp(3)
                .maxTrackedIps(100)
                .globalAttemptsPerSecond(11)
                .perIpAttemptsPerSecond(5)
                .retryThresholdPerSecond(4)
                .ingressInitialsPerSecond(13)
                .ingressInitialsPerIpPerSecond(6)
                .retryMode(TransportConfig.RetryMode.NEVER)
                .maxIdleTimeout(5, TimeUnit.SECONDS)
                .congestionControl(TransportConfig.CongestionControl.RENO)
                .initialCongestionWindowPackets(12)
                .earlyData(false)
                .sessionResumption(false)
                .build();

        // The /3 copy preserves every value and only turns the multistream limits on.
        TransportConfig copy = custom.withMultistream();
        assertTrue(copy.isMultistream());
        assertFalse(custom.isMultistream());
        assertEquals(custom.maxIdleTimeoutMillis, copy.maxIdleTimeoutMillis);
        assertEquals(custom.initialMaxData, copy.initialMaxData);
        assertEquals(custom.initialMaxStreamData, copy.initialMaxStreamData);
        assertEquals(custom.congestionControl, copy.congestionControl);
        assertEquals(custom.initialCongestionWindowPackets, copy.initialCongestionWindowPackets);
        assertEquals(custom.earlyData, copy.earlyData);
        assertEquals(custom.sessionResumption, copy.sessionResumption);
        assertEquals(custom.relaxedLossThreshold, copy.relaxedLossThreshold);
        assertEquals(custom.globalAttemptsPerSecond, copy.globalAttemptsPerSecond);
        assertEquals(custom.perIpAttemptsPerSecond, copy.perIpAttemptsPerSecond);
        assertEquals(custom.retryThresholdPerSecond, copy.retryThresholdPerSecond);
        assertEquals(custom.maxOpen, copy.maxOpen);
        assertEquals(custom.maxOpenPerIp, copy.maxOpenPerIp);
        assertEquals(custom.maxTrackedIps, copy.maxTrackedIps);
        assertEquals(custom.ingressInitialsPerSecond, copy.ingressInitialsPerSecond);
        assertEquals(custom.ingressInitialsPerIpPerSecond, copy.ingressInitialsPerIpPerSecond);
        assertEquals(custom.retryMode, copy.retryMode);

        // A /3 listener keeps it too.
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, custom, stream -> { },
                protocol -> TABLE)) {
            TransportConfig effective = server.effectiveConfigForTests();
            assertTrue(effective.isMultistream());
            assertEquals(7, effective.maxOpen());
            assertEquals(3, effective.maxOpenPerIp());
            assertEquals(100, effective.maxTrackedIps());
            assertEquals(TransportConfig.RetryMode.NEVER, effective.retryMode());
            assertEquals(5000, effective.maxIdleTimeoutMillis);
            assertEquals(TransportConfig.CongestionControl.RENO, effective.congestionControl());
            assertFalse(effective.earlyData);
            assertFalse(effective.sessionResumption);
        }

        // And a /3 dial: earlyData=false means no 0-RTT even with the material cached.
        try (QuicServer server = bindServer(connection -> { })) {
            InetSocketAddress address = server.localAddress();
            primeV3Join(address);
            EarlyFlight early = new EarlyFlight("inputs");
            V3Connection connection = QuicClient.connectV3(address, identity.fingerprint(), TABLE, custom,
                    early, false).get(10, TimeUnit.SECONDS);
            assertFalse(early.sent(), "the dial's own earlyData=false must be honoured");
            connection.close();
        }
    }

    @Test
    void aUsedEarlyTokenWaitsForTheHandshake() throws Exception {
        requireV3();
        // Keep the client from abandoning the replay's 0-RTT connection before the server's handshake
        // ends, so the wait path is what this test observes.
        QuicClient.setGraceFloorForTests(2_000);
        List<Boolean> handshakeAtAccept = Collections.synchronizedList(new ArrayList<Boolean>());
        try (QuicServer server = bindServer(connection -> handshakeAtAccept.add(connection.handshakeComplete()));
                DelayRelay relay = new DelayRelay(server.localAddress(), 30)) {
            InetSocketAddress address = relay.address();
            primeV3Join(address);
            List<Object> key = EarlyCache.server(address, identity.fingerprint(), Protocol.ALPN_V3);
            byte[] token = EarlyCache.INSTANCE.takeToken(key);
            assertNotNull(token, "the priming join must have stored a /3 early token");

            // First use: a 0-RTT dial redeems it, so the server delivers the stream early.
            EarlyCache.INSTANCE.putToken(key, token);
            EarlyFlight first = new EarlyFlight("inputs");
            V3Connection firstConnection = QuicClient.connectV3(address, identity.fingerprint(), TABLE, first)
                    .get(10, TimeUnit.SECONDS);
            assertTrue(first.sent(), () -> "no 0-RTT: " + QuicClient.lastEarlyOutcome());
            assertTrue(await(() -> handshakeAtAccept.size() >= 2, 5_000), "the first use is accepted");
            assertFalse(handshakeAtAccept.get(handshakeAtAccept.size() - 1), "a redeemed token delivers early");
            firstConnection.close();

            // Replay of the same token: the server already consumed it, so this stream waits.
            int before = handshakeAtAccept.size();
            EarlyCache.INSTANCE.putToken(key, token);
            EarlyFlight replay = new EarlyFlight("inputs");
            V3Connection replayed = QuicClient.connectV3(address, identity.fingerprint(), TABLE, replay)
                    .get(10, TimeUnit.SECONDS);
            assertTrue(replay.sent(), () -> "no 0-RTT: " + QuicClient.lastEarlyOutcome());
            assertTrue(await(() -> handshakeAtAccept.size() > before, 5_000), "the replay is delivered");
            assertTrue(handshakeAtAccept.get(handshakeAtAccept.size() - 1),
                    "a used token must wait for the handshake (docs/protocol.md §8)");
            replayed.close();
        }
    }

    @Test
    void aServerRefusesToActivateBeforeItsOwnHandshakeCompletes() throws Exception {
        requireV3();
        List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
        AtomicReference<V3Connection> early = new AtomicReference<V3Connection>();
        Logger logger = Logger.getLogger("QUICraft");
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        try (QuicServer server = bindServer(connection -> {
            if (connection.handshakeComplete()) {
                return; // the priming join (and any retry) is delivered after the handshake
            }
            early.compareAndSet(null, connection);
            connection.detachForActivation(leftover -> { });
            connection.activate(new Game(), 4242, null); // too early on this 0-RTT connection (12.7)
        }); DelayRelay relay = new DelayRelay(server.localAddress(), 30)) {
            primeV3Join(relay.address());
            EarlyFlight earlyFlight = new EarlyFlight("inputs");
            QuicClient.connectV3(relay.address(), identity.fingerprint(), TABLE, earlyFlight)
                    .get(10, TimeUnit.SECONDS);
            assertTrue(earlyFlight.sent(), () -> "no 0-RTT: " + QuicClient.lastEarlyOutcome());
            assertNotNull(early.get(), "the resumed dial must be delivered early, before the handshake");
            assertTrue(await(() -> !early.get().isOpen(), 5_000),
                    "12.7: activation before the server's own handshake closes the connection");
        } finally {
            logger.removeHandler(handler);
        }

        assertNull(early.get().session(), "no session may be created before the server's handshake (12.7)");
        boolean warned = false;
        for (String line : warnings) {
            if (line != null && line.contains("handshake is not complete")) {
                warned = true;
            }
        }
        assertTrue(warned, "the refusal is the 12.7 handshake guard, which fails closed with 0x10");
    }

    // --- Helpers.

    /** A /3 listener with the test callback, on a fresh loopback port. */
    @Test
    void aThreeDialAndListenerAgreeOnTheSelectedShardCount() throws Exception {
        requireV3();
        try (Pair pair = new Pair(connection -> { })) {
            assertTrue(await(pair.serverSide::handshakeComplete, 5_000), "the server's handshake");
            // 13.2: the client offers 1..6, the server clamps its preference (3) into that offer.
            assertTrue(await(() -> negotiated(pair.serverSide) != null, 5_000), "the server's selection");
            assertTrue(await(() -> negotiated(pair.client) != null, 5_000), "the client's selection");
            assertEquals(TransportConfig.MULTISTREAM_PREFERRED_SHARDS, negotiated(pair.serverSide).shardCount());
            assertEquals(negotiated(pair.serverSide), negotiated(pair.client),
                    "both ends hold the same immutable N");
        }
    }

    /** The negotiated context, or null before the preamble exchange has finished. */
    private static SessionContext negotiated(V3Connection connection) {
        try {
            return connection.sessionContext();
        } catch (IllegalStateException notYet) {
            return null;
        }
    }

    private static QuicServer bindServer(Consumer<V3Connection> onAccepted) throws Exception {
        return QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT, stream -> {
            V3Connection connection = (V3Connection) stream;
            onAccepted.accept(connection);
        }, protocol -> TABLE);
    }

    /** One /3 listener and one /3 dial to it, both sides already accepted. */
    static final class Pair implements AutoCloseable {
        final QuicServer server;
        final V3Connection client;
        final V3Connection serverSide;
        final Game clientGame = new Game();
        final Game serverGame = new Game();

        Pair(Consumer<V3Connection> onAccepted) throws Exception {
            CompletableFuture<V3Connection> accepted = new CompletableFuture<V3Connection>();
            server = bindServer(connection -> {
                onAccepted.accept(connection);
                accepted.complete(connection);
            });
            client = QuicClient.connectV3(server.localAddress(), identity.fingerprint(), TABLE, null)
                    .get(10, TimeUnit.SECONDS);
            serverSide = accepted.get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            server.close();
        }
    }

    /** Activation on the connection's loop, then the game's session reference. */
    static void activate(V3Connection connection, Game game, int protocol) throws Exception {
        connection.detachForActivation(leftover -> { });
        if (protocol >= 0) {
            connection.activate(game, protocol, null);
        } else {
            connection.activate(game, null);
        }
        assertTrue(await(() -> connection.session() != null, 5_000), "activation");
        CountDownLatch onLoop = new CountDownLatch(1);
        connection.execute(onLoop::countDown);
        assertTrue(onLoop.await(5, TimeUnit.SECONDS), "the activation task ran");
        game.session = connection.session();
    }

    /** A game that records deliveries, acknowledges them itself, and can echo them back. */
    static final class Game implements LaneGame {
        final List<Object> delivered = Collections.synchronizedList(new ArrayList<Object>());
        final CompletableFuture<Integer> closed = new CompletableFuture<Integer>();
        volatile rs.sudoe.quicraft.core.multistream.MultistreamSession session;
        volatile boolean echo;

        @Override
        public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle,
                              Reservation reservation) {
            int prefix = 0;
            while ((frame[prefix] & 0x80) != 0) {
                prefix++;
            }
            prefix++;
            int id = frame[prefix] & 0xff;
            reservation.reserve(0, frame.length);
            return new Decoded(new Packet(ProtocolState.PLAY, id, frame), ProtocolState.PLAY, id,
                    Envelope.Bundle.NONE, false);
        }

        @Override
        public Decoded decodeInflated(Lane lane, long sequence, byte[] content, boolean insideBundle,
                                      Reservation reservation) {
            byte[] frame = new byte[content.length + 1];
            frame[0] = (byte) content.length;
            System.arraycopy(content, 0, frame, 1, content.length);
            return decode(lane, sequence, frame, insideBundle, reservation);
        }

        @Override
        public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            delivered.addAll(packets);
            if (echo) {
                for (Object packet : packets) {
                    Packet p = (Packet) packet;
                    session.send(p.state, p.id, p.frame);
                }
            }
            session.applied(lane, sequence);
        }

        @Override
        public void onWritabilityChanged(boolean writable) { }

        @Override
        public void onClosed(int code, boolean byPeer, Throwable cause) {
            closed.complete(code);
        }

        int count() {
            return delivered.size();
        }
    }

    /** One decoded test packet. */
    static final class Packet {
        final ProtocolState state;
        final int id;
        final byte[] frame;

        Packet(ProtocolState state, int id, byte[] frame) {
            this.state = state;
            this.id = id;
            this.frame = frame;
        }
    }

    static byte[] frame(int packetId, String payload) {
        byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int inner = 1 + bytes.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value = inner;
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        out.write(packetId);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }

    /** One /3 join that writes the login flight, so the next dial has a token and a flight. */
    static void primeV3Join(InetSocketAddress address) throws Exception {
        EarlyFlight early = new EarlyFlight("inputs");
        V3Connection connection = QuicClient.connectV3(address, identity.fingerprint(), TABLE, early)
                .get(10, TimeUnit.SECONDS);
        connection.write(ByteBuffer.wrap(LOGIN_FLIGHT));
        connection.flush();
        Thread.sleep(300); // let the server's preamble (and its token) arrive
        connection.close();
        Thread.sleep(100);
    }

    /** The Netty channel under a connection, for a test that opens a stream by hand. */
    static QuicChannel channelOf(V3Connection connection) {
        return ((V3ConnectionImpl) connection).rawStream().connection();
    }

    static CompletableFuture<Integer> watchClose(QuicChannel channel) {
        CompletableFuture<Integer> close = new CompletableFuture<Integer>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof QuicConnectionCloseEvent) {
                    QuicConnectionCloseEvent event = (QuicConnectionCloseEvent) evt;
                    close.complete(event.isApplicationClose() ? event.error() : -1);
                }
                ctx.fireUserEventTriggered(evt);
            }
        });
        return close;
    }

    static byte[] exporter(V3Connection connection) throws Exception {
        CompletableFuture<byte[]> value = new CompletableFuture<byte[]>();
        connection.execute(() -> {
            try {
                value.complete(connection.exporterValue());
            } catch (Throwable t) {
                value.completeExceptionally(t);
            }
        });
        return value.get(5, TimeUnit.SECONDS);
    }

    static long rtt(V3Connection connection) throws Exception {
        CompletableFuture<Long> value = new CompletableFuture<Long>();
        connection.execute(() -> {
            try {
                value.complete(connection.smoothedRttMillis());
            } catch (Throwable t) {
                value.completeExceptionally(t);
            }
        });
        return value.get(5, TimeUnit.SECONDS);
    }

    static int receivedSize(ByteArrayOutputStream bytes) {
        synchronized (bytes) {
            return bytes.size();
        }
    }

    static boolean await(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }
}
