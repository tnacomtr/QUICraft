// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.binding.BindingTestSessions;
import rs.sudoe.quicraft.core.connect.ClientConnector.Outcome;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.connect.TransportSelection.PinState;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
import rs.sudoe.quicraft.core.pin.PinKey;
import rs.sudoe.quicraft.core.pin.PinStore;
import rs.sudoe.quicraft.core.pin.RebindState;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/**
 * The {@code /3} pre-connect selection and its pin, re-bind and fallback bookkeeping, end to end
 * through {@link ClientConnector} (docs/protocol.md 12.3, 12.15, 12.16, 13.1, and the user's
 * fail-safe decision). A {@code /3} listener speaks {@code /3} only, so a client that cannot do
 * {@code /3} joins over TCP; {@code /2} is only ever selected for a {@code quicraft/2}
 * advertisement. The routing table comes from the fixtures in {@code core/src/test/resources}.
 */
class ClientConnectorV3Test {

    /**
     * 12.15's expiry calls take the keys of the attempts that are running. These tests run at most
     * one attempt and never need another server's pin protected, so the set is empty.
     */
    private static final Set<PinKey> NO_ACTIVE_KEYS = Collections.emptySet();
    /** The protocol number of the valid routing fixture. */
    private static final int PROTOCOL = 12345;
    private static final int QUIC_PORT = 25570;
    private static final InetSocketAddress SERVER =
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
    private static final String HOST = "mc.example.com";
    private static final int LOGICAL_PORT = 25565;
    private static final long FIVE_SECONDS = 5;

    private static ScheduledExecutorService scheduler;
    private static String localDigest;

    @BeforeAll
    static void setUp() throws Exception {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        localDigest = "sha256:" + RoutingRegistry.forProtocol(PROTOCOL).get().digestHex();
    }

    @AfterAll
    static void tearDown() {
        Faults.set();
        scheduler.shutdownNow();
    }

    @AfterEach
    void noFaults() {
        Faults.set();
    }

    /** A platform whose QUIC and TCP answers the test sets, recording which dial was used. */
    static class FakePlatform implements ClientConnector.Platform<String> {
        CompletableFuture<Optional<Advertisement>> status =
                CompletableFuture.completedFuture(Optional.<Advertisement>empty());
        CompletableFuture<QuicByteStream> quic =
                CompletableFuture.completedFuture(new ClientConnectorTest.FakeStream());
        CompletableFuture<String> tcp = CompletableFuture.completedFuture("tcp");
        final AtomicInteger v2Dials = new AtomicInteger();
        final AtomicInteger v3Dials = new AtomicInteger();
        final AtomicInteger tcpAttempts = new AtomicInteger();
        volatile TransportSelection.Transport dialAlpn;
        volatile boolean bypassResumption;

        @Override
        public CompletableFuture<Optional<Advertisement>> queryStatus(InetSocketAddress server) {
            return status;
        }

        @Override
        public CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target,
                Fingerprint fingerprint, EarlyFlight early) {
            v2Dials.incrementAndGet();
            dialAlpn = TransportSelection.Transport.V2;
            return quic;
        }

        @Override
        public CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target,
                Fingerprint fingerprint, EarlyFlight early, TransportSelection.Transport alpn,
                boolean bypassResumption) {
            v3Dials.incrementAndGet();
            dialAlpn = alpn;
            this.bypassResumption = bypassResumption;
            return quic;
        }

        @Override
        public ConnectionRace.Tcp<String> tcp() {
            return new ConnectionRace.Tcp<String>() {
                @Override
                public CompletableFuture<String> connect() {
                    tcpAttempts.incrementAndGet();
                    return tcp;
                }

                @Override
                public void close(String connection) {}
            };
        }

        @Override
        public ScheduledExecutorService scheduler() {
            return scheduler;
        }
    }

    /** The pin store, re-bind state and failure cache a test drives, with a controllable clock. */
    static final class Fixture {
        /** The clock the connector, the failure cache and the re-bind state all share. */
        LongSupplier clock = System::currentTimeMillis;
        final FailureCache failures = FailureCache.load(null, clock);
        final PinStore pins;
        final RebindState rebinds;
        final AdvertisementCache advertisements = new AdvertisementCache();
        final Fingerprint fingerprint = fingerprint('c');
        /** Runs every task inline, so a test observes the worker's state calls synchronously. */
        Executor worker = new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };

        Fixture(PinStore pins, RebindState rebinds) {
            this.pins = pins;
            this.rebinds = rebinds;
        }

        static Fixture empty() {
            return new Fixture(PinStore.load(null), RebindState.load(null));
        }

        ClientConnector connector(boolean multistreamEnabled, boolean exporterCapable) {
            return connector(PROTOCOL, multistreamEnabled, exporterCapable);
        }

        ClientConnector connector(int protocolNumber, boolean multistreamEnabled,
                boolean exporterCapable) {
            return new ClientConnector(advertisements, failures,
                    new ClientConnector.V3Support(pins, rebinds, worker, clock, protocolNumber,
                            multistreamEnabled, exporterCapable));
        }

        /** Puts the advertisement in the cache, so no status query is needed. */
        Advertisement advertiseV3(Map<Integer, String> routing) {
            Advertisement ad = Advertisement.of(Protocol.ALPN_V3, QUIC_PORT, fingerprint, routing);
            advertisements.record(SERVER, Optional.of(ad));
            return ad;
        }

        Advertisement advertiseV3() {
            return advertiseV3(Collections.singletonMap(Integer.valueOf(PROTOCOL), localDigest));
        }

        Advertisement advertiseV2() {
            Advertisement ad = Advertisement.v1(QUIC_PORT, fingerprint);
            advertisements.record(SERVER, Optional.of(ad));
            return ad;
        }

        Outcome<String> connect(ClientConnector connector, FakePlatform platform, Mode mode)
                throws Exception {
            return connect(connector, platform, mode, HOST);
        }

        Outcome<String> connect(ClientConnector connector, FakePlatform platform, Mode mode, String host)
                throws Exception {
            return connector.connect(SERVER, host, LOGICAL_PORT, mode, null, platform)
                    .get(FIVE_SECONDS, TimeUnit.SECONDS);
        }

        /** The connector's result future, for the tests that cancel or inspect an unfinished join. */
        CompletableFuture<Outcome<String>> join(ClientConnector connector, FakePlatform platform) {
            return connector.connect(SERVER, HOST, LOGICAL_PORT, Mode.AUTO, null, platform);
        }

        /** The address and port §6 keys a {@code /3} failure by: the resolved IP and the QUIC port. */
        InetAddress address() {
            return SERVER.getAddress();
        }
    }

    private static Fingerprint fingerprint(char digit) {
        StringBuilder text = new StringBuilder("sha256:");
        for (int i = 0; i < 64; i++) {
            text.append(digit);
        }
        return Fingerprint.parse(text.toString());
    }

    /** An online-authenticated client session that completed the exchange (12.7, 12.15). */
    private static BindingSession onlineSession() {
        return BindingTestSessions.activatedClient(BindingStateMachine.MODE_ONLINE);
    }

    /** An offline-mode client session that completed the exchange (12.19). */
    private static BindingSession offlineSession() {
        return BindingTestSessions.activatedClient(BindingStateMachine.MODE_OFFLINE);
    }

    @Test
    void anOrdinaryPinSelectsV3WithTheAdvertisedFingerprintAndTheLocalDigest() throws Exception {
        Fixture f = Fixture.empty();
        Advertisement ad = f.advertiseV3();
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertNotNull(outcome.quic(), "13.1: /3 is selected and dialled");
        assertNull(outcome.tcp());
        assertNull(outcome.fallback());
        assertEquals(1, p.v3Dials.get());
        assertEquals(0, p.v2Dials.get(), "a /3 selection never dials /2");
        assertEquals(TransportSelection.Transport.V3, p.dialAlpn);
        assertFalse(p.bypassResumption, "only a changed pin bypasses resumption (12.16)");

        V3Plan plan = outcome.v3Plan();
        assertNotNull(plan);
        assertEquals(PinState.NONE, plan.pinState());
        assertNull(plan.attempt(), "an ordinary pin takes no re-bind attempt");
        assertEquals(ad.fingerprint(), plan.fingerprint());
        assertArrayEquals(RoutingRegistry.forProtocol(PROTOCOL).get().digest(), plan.routingDigest());
        assertEquals(32, plan.fingerprintBytes().length, "F as raw bytes, for the binding session");
        assertEquals(f.fingerprint.toString(), "sha256:" + hex(plan.fingerprintBytes()),
                "and it is the advertised fingerprint, not a rehash of it");
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(String.format("%02x", Integer.valueOf(b & 0xFF)));
        }
        return text.toString();
    }

    @Test
    void aSamePinSelectsV3WithoutAnAttempt() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, f.fingerprint.toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertNotNull(outcome.quic());
        assertEquals(PinState.SAME, outcome.v3Plan().pinState());
        assertNull(outcome.v3Plan().attempt());
        assertFalse(p.bypassResumption);
    }

    @Test
    void aChangedPinTakesOneRebindAttemptWithAFullHandshake() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertNotNull(outcome.quic());
        V3Plan plan = outcome.v3Plan();
        assertEquals(PinState.CHANGED, plan.pinState());
        assertNotNull(plan.attempt(), "12.16: one automatic re-bind attempt");
        assertTrue(p.bypassResumption, "12.16: full handshake, no early data");
        assertEquals(1, p.v3Dials.get());
        assertEquals("mc.example.com", plan.attempt().host());
        assertEquals(LOGICAL_PORT, plan.attempt().port());
    }

    @Test
    void aChangedPinInsideItsBackoffUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        ClientConnector connector = f.connector(true, true);
        Outcome<String> first = f.connect(connector, new FakePlatform(), Mode.AUTO);
        // The attempt ends in failure: 12.16's window is now up.
        assertTrue(f.rebinds.endAttempt(first.v3Plan().attempt(), false));

        FakePlatform second = new FakePlatform();
        Outcome<String> outcome = f.connect(connector, second, Mode.AUTO);
        assertEquals("tcp", outcome.tcp(), "at most one automatic attempt per window");
        assertNull(outcome.quic());
        assertEquals(0, second.v3Dials.get());
        assertEquals(0, second.v2Dials.get(), "12.16: a changed pin never falls back to /2");
        assertNull(outcome.v3Plan());
    }

    @Test
    void aChangedPinWithAMismatchedDigestUsesTcpAndRecordsBothBackoffs() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3(Collections.singletonMap(Integer.valueOf(PROTOCOL), "sha256:" + repeat('a')));
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp(), "12.16: fresh TCP, never /2 and never 0x10");
        assertEquals(0, p.v3Dials.get(), "no /3 connection is created at all");
        assertEquals(0, p.v2Dials.get());
        assertNull(outcome.v3Plan());
        assertFalse(f.failures.allows(f.address(), QUIC_PORT, f.fingerprint),
                "§6's failure-cache entry is recorded");
        // And the re-bind backoff is up: a second attempt is refused.
        assertNull(f.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, f.clock.getAsLong()),
                "12.16's re-bind backoff is recorded too");
    }

    @Test
    void aChangedPinWithNoAdvertisedDigestUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3(Collections.<Integer, String>emptyMap());
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        assertEquals("tcp", f.connect(f.connector(true, true), p, Mode.AUTO).tcp());
        assertEquals(0, p.v3Dials.get());
        assertEquals(0, p.v2Dials.get());
    }

    /** The open question, spec-literal: a changed pin with a /2-only advertisement is fresh TCP. */
    @Test
    void aChangedPinWithOnlyV2AdvertisedUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV2();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp());
        assertEquals(0, p.v2Dials.get(), "12.16 never trusts F' over /2");
        assertEquals(0, p.v3Dials.get());
        assertFalse(f.failures.allows(f.address(), QUIC_PORT, f.fingerprint));
    }

    @Test
    void aChangedPinWithoutTheExporterOrTheFlagUsesTcp() throws Exception {
        for (boolean[] capabilities : new boolean[][] {{false, true}, {true, false}, {false, false}}) {
            Fixture f = Fixture.empty();
            f.advertiseV3();
            f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
            FakePlatform p = new FakePlatform();
            Outcome<String> outcome = f.connect(
                    f.connector(capabilities[0], capabilities[1]), p, Mode.AUTO);
            assertEquals("tcp", outcome.tcp(), "a missing /3 prerequisite means fresh TCP");
            assertEquals(0, p.v2Dials.get());
            assertEquals(0, p.v3Dials.get());
        }
    }

    @Test
    void aChangedPinInQuicOnlyModeFails() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        // A missing prerequisite plus quic-only is FAIL: the caller shows the failure (12.16).
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> f.connect(f.connector(true, false), p, Mode.QUIC_ONLY));
        assertEquals(0, p.v2Dials.get());
        assertEquals(0, p.v3Dials.get());
    }

    @Test
    void anOrdinaryPinWithAMissingPrerequisiteUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3(Collections.singletonMap(Integer.valueOf(PROTOCOL), "sha256:" + repeat('a')));
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp(), "13.1: a /3 listener speaks /3 only, so this is TCP");
        assertEquals(0, p.v2Dials.get(), "a /3 listener is never offered /2");
        assertEquals(0, p.v3Dials.get());
        assertNull(outcome.v3Plan(), "nothing failed: the client simply cannot do /3 here");
        assertNull(outcome.fallbackMessage());
    }

    @Test
    void anOrdinaryPinWithoutALocalRoutingTableUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3(Collections.singletonMap(Integer.valueOf(4242), "sha256:" + repeat('a')));
        FakePlatform p = new FakePlatform();
        // 4242 has no resource on the test classpath, so the client has no table for it.
        Outcome<String> outcome = f.connect(f.connector(4242, true, true), p, Mode.AUTO);
        assertEquals("tcp", outcome.tcp());
        assertEquals(0, p.v2Dials.get());
        assertNull(outcome.v3Plan());
    }

    @Test
    void theV2AndFailExitsHoldNoAttempt() throws Exception {
        // The /2 exit: a changed pin never selects /2, so nothing can be admitted for it.
        Fixture v2 = Fixture.empty();
        v2.advertiseV2();
        v2.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        assertEquals("tcp", v2.connect(v2.connector(true, true), new FakePlatform(), Mode.AUTO).tcp());
        assertTrue(v2.rebinds.activeKeys().isEmpty(), "the /2 exit holds no attempt");
        assertNull(v2.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, v2.clock.getAsLong()),
                "12.16: a changed pin with a /2-only advertisement only records its backoff");

        // The FAIL exit: quic-only with a missing /3 prerequisite, for a changed pin.
        Fixture fail = Fixture.empty();
        fail.advertiseV3();
        fail.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        assertThrows(ExecutionException.class,
                () -> fail.connect(fail.connector(true, false), new FakePlatform(), Mode.QUIC_ONLY));
        assertTrue(fail.rebinds.activeKeys().isEmpty(), "the FAIL exit holds no attempt");
    }

    @Test
    void aV2AdvertisementKeepsTheShippedPath() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV2();
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertNotNull(outcome.quic());
        assertEquals(1, p.v2Dials.get());
        assertEquals(0, p.v3Dials.get());
        assertNull(outcome.v3Plan());
    }

    @Test
    void aCorruptPinStoreUsesTcpForAnOrdinaryName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pins-v2.json");
        Files.write(file, "{not a pin store".getBytes(StandardCharsets.UTF_8));
        PinStore pins = PinStore.load(file);
        assertFalse(pins.isUsable());
        Fixture f = new Fixture(pins, RebindState.load(null));
        f.advertiseV3();
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp(), "12.15: a corrupt store uses TCP for a /3 listener");
        assertEquals(0, p.v2Dials.get(), "a /3 listener is never offered /2");
        assertEquals(0, p.v3Dials.get());
        assertNull(outcome.v3Plan());

        // A /2 listener is unaffected by the store: /2 involves no pin at all.
        Fixture g = new Fixture(PinStore.load(file), RebindState.load(null));
        g.advertiseV2();
        FakePlatform v2 = new FakePlatform();
        Outcome<String> shipped = g.connect(g.connector(true, true), v2, Mode.AUTO);
        assertNotNull(shipped.quic());
        assertEquals(1, v2.v2Dials.get());
    }

    @Test
    void aFullPinStoreUsesTcpForANewNameAndStillUsesV3ForAKnownOne() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        for (int i = 0; i < PinStore.MAX_PINS - 1; i++) {
            f.pins.recordPin("server" + i + ".example", 25565, fingerprint('e').toString(), NO_ACTIVE_KEYS);
        }
        assertEquals(PinStore.RecordResult.RECORDED,
                f.pins.recordPin(HOST, LOGICAL_PORT, f.fingerprint.toString(), NO_ACTIVE_KEYS));
        assertEquals(PinStore.MAX_PINS, f.pins.size(), "the store is now full");

        // A name the store already knows keeps using /3: only new names are restricted.
        FakePlatform known = new FakePlatform();
        Outcome<String> knownOutcome = f.connect(f.connector(true, true), known, Mode.AUTO);
        assertNotNull(knownOutcome.quic());
        assertEquals(1, known.v3Dials.get());
        assertEquals(PinState.SAME, knownOutcome.v3Plan().pinState());

        FakePlatform unknown = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), unknown, Mode.AUTO,
                "other.example.com");
        assertEquals("tcp", outcome.tcp(), "12.16: a full store uses TCP for new names, not /2");
        assertEquals(0, unknown.v2Dials.get());
        assertEquals(0, unknown.v3Dials.get());
        assertNull(outcome.v3Plan());
    }

    @Test
    void anActivationFailureIsNotClearedByAHandshakeSuccessAndUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.failures.recordActivationFailure(f.address(), QUIC_PORT, f.fingerprint);
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.getAsLong()));

        // What the race records on a QUIC handshake win must not clear it (12.14).
        f.failures.recordSuccess(f.address(), QUIC_PORT, f.fingerprint);
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.getAsLong()));

        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);
        assertEquals("tcp", outcome.tcp(), "§6: a /3 activation failure costs at most one TCP join");
        assertEquals(0, p.v3Dials.get());
        assertEquals(0, p.v2Dials.get());
    }

    @Test
    void activationRecordsThePinClearsTheFailureAndEndsTheAttempt() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        f.failures.recordActivationFailure(f.address(), QUIC_PORT, f.fingerprint);
        // A blocked activation would keep /3 off, so clear it first: this test is about the end of
        // a successful attempt.
        f.failures.clearActivationFailure(f.address(), QUIC_PORT, f.fingerprint);

        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        assertTrue(plan.activated(onlineSession()).get(FIVE_SECONDS, TimeUnit.SECONDS));

        assertEquals(f.fingerprint.toString(), f.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).get().fingerprint(),
                "12.15: an authenticated activation records the pin");
        assertFalse(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.getAsLong()));
        // The attempt is over and its state is cleared, so a fresh one is admitted.
        assertNotNull(f.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, f.clock.getAsLong()));
    }

    @Test
    void activationInOfflineModeWritesNoPin() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        // 12.19: offline mode never creates or updates an authenticated pin.
        assertTrue(plan.activated(offlineSession()).get(FIVE_SECONDS, TimeUnit.SECONDS));
        assertFalse(f.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).isPresent());
    }

    @Test
    void aFailedPinWriteClosesTheConnectionAndLeavesThePinAlone() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();

        Faults.set(Faults.PIN_WRITE);
        assertFalse(plan.activated(onlineSession()).get(FIVE_SECONDS, TimeUnit.SECONDS),
                "12.15: a failed pin write must be reported so the caller closes with 0x10");
        Faults.set();
        assertEquals(fingerprint('d').toString(), f.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).get().fingerprint(),
                "the old pin stays until a passing check replaces it");
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.getAsLong()),
                "a write failure after activation is itself an activation failure");
        // 12.15 with 12.14: the adapter still gets the retry decision for the failure it just saw.
        assertTrue(plan.failed(V3Plan.Phase.JOINING, V3Plan.PIN_WRITE_FAILED));
        assertEquals("QUIC multi-stream failed (" + V3Plan.PIN_WRITE_FAILED + "), fell back to TCP",
                plan.fallbackMessage());
    }

    @Test
    void failureRecordsTheRightKindOfEntryAndRetriesForEveryKindBeforeActivation() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        V3Plan changed = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        assertTrue(changed.failed(V3Plan.Phase.JOINING, V3Plan.REBIND_FAILED),
                "the user's fail-safe decision: a failed re-bind continues over fresh TCP");
        assertEquals("QUIC multi-stream failed (" + V3Plan.REBIND_FAILED + "), fell back to TCP",
                changed.fallbackMessage());
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.getAsLong()),
                "12.14: the failure is recorded as an activation failure");
        assertTrue(f.failures.allows(f.address(), QUIC_PORT, f.fingerprint),
                "and it is not also a handshake entry");

        Fixture g = Fixture.empty();
        g.advertiseV3();
        V3Plan ordinary = g.connect(g.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        assertNull(ordinary.attempt(), "an ordinary pin takes no re-bind attempt");
        assertTrue(ordinary.failed(V3Plan.Phase.JOINING, V3Plan.BINDING_FAILED),
                "and it still gets the same-attempt TCP retry before activation");
    }

    @Test
    void aFaultInThePreconnectStepFallsBackToTcpWithAMessage() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        FakePlatform p = new FakePlatform();
        Faults.set(Faults.V3_SELECT);
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);
        assertEquals("tcp", outcome.tcp(), "13.1: a /3 listener is never offered /2");
        assertEquals(0, p.v2Dials.get());
        assertEquals(0, p.v3Dials.get());
        assertEquals("QUIC multi-stream failed (internal error), fell back to TCP",
                outcome.fallbackMessage());
        assertNull(outcome.v3Plan(), "no /3 connection exists, so there is no plan to drive");

        // A /2 advertisement, or none, keeps the shipped decision: /2 or TCP.
        Fixture g = Fixture.empty();
        g.advertiseV2();
        FakePlatform v2 = new FakePlatform();
        Outcome<String> shipped = g.connect(g.connector(true, true), v2, Mode.AUTO);
        assertNotNull(shipped.quic());
        assertEquals(1, v2.v2Dials.get());

        Fixture h = Fixture.empty();
        FakePlatform none = new FakePlatform();
        Outcome<String> tcp = h.connect(h.connector(true, true), none, Mode.AUTO);
        assertEquals("tcp", tcp.tcp());
        assertEquals(0, none.v3Dials.get());
        assertEquals(0, none.v2Dials.get());
    }

    @Test
    void aFaultInTheDialFallsBackToTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        FakePlatform p = new FakePlatform();
        Faults.set(Faults.V3_DIAL);
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);
        assertEquals("tcp", outcome.tcp(), "§5: a failed /3 handshake races and falls back");
        assertEquals("QUIC multi-stream failed (handshake failed), fell back to TCP",
                outcome.fallbackMessage());
    }

    /** Queues every task until the test releases it, so a test can act while the worker is busy. */
    static final class QueuedExecutor implements Executor {
        private final List<Runnable> queued = new ArrayList<Runnable>();

        @Override
        public synchronized void execute(Runnable command) {
            queued.add(command);
        }

        synchronized void runAll() {
            for (int guard = 0; guard < 64 && !queued.isEmpty(); guard++) {
                queued.remove(0).run();
            }
        }
    }

    /** Runs the first task inline and refuses every later one, like a worker that is shutting down. */
    static final class RejectAfterOneTask implements Executor {
        private int tasks;

        @Override
        public void execute(Runnable command) {
            if (tasks++ == 0) {
                command.run();
                return;
            }
            throw new RejectedExecutionException("the worker is shutting down");
        }
    }

    @Test
    void anExpiredPinIsFirstContactAgain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pins-v2.json");
        Clock bound = Clock.systemUTC();
        PinStore.load(file, bound).recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(),
                NO_ACTIVE_KEYS);
        // 12.15: a pin expires 30 days after its last binding, so a store 31 days later no longer
        // knows this name.
        PinStore pins = PinStore.load(file, Clock.offset(bound, Duration.ofDays(31)));

        RebindState rebinds = RebindState.load(null);
        RebindState.Attempt stale = rebinds.tryBeginAttempt(HOST, LOGICAL_PORT,
                System.currentTimeMillis());
        rebinds.endAttempt(stale, false); // a backoff window and its entry exist for that server
        assertEquals(1, rebinds.size());

        Fixture f = new Fixture(pins, rebinds);
        f.advertiseV3();
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertNotNull(outcome.quic(), "an expired pin is not a changed one: /3 is selected normally");
        V3Plan plan = outcome.v3Plan();
        assertEquals(PinState.NONE, plan.pinState(), "the name is first contact again");
        assertNull(plan.attempt(), "12.16: so there is no re-bind attempt");
        assertFalse(p.bypassResumption, "and no bypassed resumption either");
        assertFalse(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.getAsLong()),
                "no failure and no backoff are recorded for it");
        assertFalse(f.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).isPresent(),
                "and the expired pin is gone from the store");
        assertEquals(0, rebinds.size(), "12.15: its re-bind state is forgotten too");
        assertNotNull(rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, System.currentTimeMillis()),
                "so its stale backoff window cannot hold the next attempt back");
    }

    @Test
    void quicOnlyNeverFallsBackToTcpOnTheV3Path() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        FakePlatform p = new FakePlatform();
        IOException failure = new IOException("the /3 dial failed");
        p.quic = new CompletableFuture<QuicByteStream>();
        p.quic.completeExceptionally(failure);

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> f.connect(f.connector(true, true), p, Mode.QUIC_ONLY));
        assertSame(failure, thrown.getCause(), "quic-only shows the /3 error, as the /2 path does");
        assertEquals(1, p.v3Dials.get());
        assertEquals(0, p.tcpAttempts.get(), "and it never opens TCP");
    }

    @Test
    void aBackedOffServerCostsNoHeadStart() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        // §6 recorded a failure for this exact address, port and fingerprint.
        f.failures.recordFailure(f.address(), QUIC_PORT, f.fingerprint);
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp(), "§6: one head start per backoff window, then TCP");
        assertEquals(0, p.v3Dials.get());
        assertEquals(0, p.v2Dials.get());
        assertNull(outcome.v3Plan());
    }

    @Test
    void aBackedOffServerStillDialsInQuicOnlyMode() throws Exception {
        // §4's debug setting ignores §6's backoff, exactly as the shipped decision does.
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.failures.recordFailure(f.address(), QUIC_PORT, f.fingerprint);
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.QUIC_ONLY);

        assertNotNull(outcome.quic());
        assertEquals(1, p.v3Dials.get());
    }

    @Test
    void aBackoffInQuicOnlyModeFailsInsteadOfSilentlyUsingTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.failures.recordActivationFailure(f.address(), QUIC_PORT, f.fingerprint);
        FakePlatform p = new FakePlatform();
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> f.connect(f.connector(true, true), p, Mode.QUIC_ONLY));
        assertEquals(0, p.v3Dials.get());
        assertEquals(0, p.v2Dials.get());
    }

    @Test
    void anActiveResourceWindowSendsAutoToTcpAndFailsQuicOnlyWithoutADial() throws Exception {
        // 12.14: a locally detected resource abort started the fixed 60-second window for this server.
        Fixture f = Fixture.empty();
        f.advertiseV3();
        assertTrue(f.rebinds.startResourceWindow(HOST, LOGICAL_PORT));

        FakePlatform auto = new FakePlatform();
        assertEquals("tcp", f.connect(f.connector(true, true), auto, Mode.AUTO).tcp(),
                "inside the window, AUTO joins over TCP without another QUIC login");
        assertEquals(0, auto.v3Dials.get());

        FakePlatform quicOnly = new FakePlatform();
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> f.connect(f.connector(true, true), quicOnly, Mode.QUIC_ONLY),
                "quic-only has no TCP fallback, so the window fails it");
        assertEquals(0, quicOnly.v3Dials.get(), "and it does not dial /3 inside the window");
        assertEquals(0, quicOnly.v2Dials.get());
        assertEquals(0, quicOnly.tcpAttempts.get());
    }

    @Test
    void aFullPinStoreInQuicOnlyModeFailsInsteadOfSilentlyUsingTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        for (int i = 0; i < PinStore.MAX_PINS; i++) {
            f.pins.recordPin("server" + i + ".example", 25565, fingerprint('e').toString(), NO_ACTIVE_KEYS);
        }
        FakePlatform p = new FakePlatform();
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> f.connect(f.connector(true, true), p, Mode.QUIC_ONLY));
        assertEquals(0, p.v3Dials.get());
        assertEquals(0, p.v2Dials.get());
    }

    @Test
    void aWorkerThatRefusesTasksStillYieldsATcpOutcome() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        // The pre-connect step needs the first task; the failure bookkeeping is the second.
        f.worker = new RejectAfterOneTask();
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<QuicByteStream>();
        p.quic.completeExceptionally(new IOException("the dial failed"));
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp(),
                "a worker that refuses the bookkeeping task must never stop the TCP join");
        assertEquals("QUIC multi-stream failed (handshake failed), fell back to TCP",
                outcome.fallbackMessage());
    }

    @Test
    void aBlackHoledRebindDialFallsBackToTcpAfterTheHeadStart() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<QuicByteStream>(); // a black hole: the dial never completes
        long start = System.nanoTime();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals("tcp", outcome.tcp(),
                "§5: the re-bind never makes the player wait for a full QUIC timeout");
        assertTrue(waited < ClientConnector.HEAD_START_MILLIS + 2000, "waited " + waited + " ms");
        assertEquals(FallbackReason.TIMEOUT_OR_LOST_RACE, outcome.fallback());
        assertEquals("QUIC multi-stream failed (handshake timed out), fell back to TCP",
                outcome.fallbackMessage());
        assertFalse(f.failures.allows(f.address(), QUIC_PORT, f.fingerprint), "§6's entry is recorded");
        assertNull(f.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, f.clock.getAsLong()),
                "and 12.16's re-bind window is up");
    }

    @Test
    void aFailedPreconnectStepNeverAdmitsARebind(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rebind-v1.bin");
        Fixture f = new Fixture(PinStore.load(null), RebindState.load(file));
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        Faults.set(Faults.V3_SELECT);
        Outcome<String> outcome = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO);
        Faults.set();

        assertEquals("tcp", outcome.tcp(), "a failed pre-connect step still joins over TCP");
        assertEquals(0, f.rebinds.activeKeys().size(), "and it holds no attempt");
        assertFalse(Files.exists(file),
                "nothing is admitted or written before the step that can fail");
    }

    private static String stateFile(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    void cancellingTheJoinEndsAnAdmittedRebind(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rebind-v1.bin");
        Fixture f = new Fixture(PinStore.load(null), RebindState.load(file));
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<QuicByteStream>(); // still dialling when the player gives up
        CompletableFuture<Outcome<String>> join = f.join(f.connector(true, true), p);

        assertTrue(join.cancel(false));
        // RebindState.load(null) keeps everything in memory, so this fixture writes a real file to
        // observe the attempt: tryBeginAttempt only pre-stores the window (failures stays 0), while
        // endAttempt(false) counts one failure.
        assertTrue(f.rebinds.activeKeys().isEmpty(),
                "an abandoned join must not leave the attempt active");
        String state = stateFile(file);
        assertTrue(state.contains("\"failures\":1"),
                "an abandoned join must not leave the server's re-bind state held: " + state);
    }


    @Test
    void cancellingWhileThePreparationRunsStillEndsTheAttemptItAdmits() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        QueuedExecutor gate = new QueuedExecutor();
        f.worker = gate;
        final AtomicInteger clockReads = new AtomicInteger();
        f.clock = () -> {
            clockReads.incrementAndGet();
            return System.currentTimeMillis();
        };
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<QuicByteStream>();

        CompletableFuture<Outcome<String>> join = f.join(f.connector(true, true), p);
        assertTrue(join.cancel(false));
        gate.runAll();

        assertTrue(clockReads.get() > 0,
                "the preparation must run to completion, cancelled join or not");
        assertTrue(f.rebinds.activeKeys().isEmpty(),
                "and the attempt it admitted must be ended, never left held");
        assertEquals(0, p.v3Dials.get(), "nothing may dial for an abandoned join");
        assertEquals(0, p.tcpAttempts.get());
    }

    @Test
    void cancellingWhileThePreparationSupplierIsStillRunningEndsTheAttemptItAdmits(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("rebind-v1.bin");
        Fixture f = new Fixture(PinStore.load(null), RebindState.load(file));
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        // Every worker task on its own thread, so the supplier really is in flight while the join
        // is cancelled: prepare0 reads the clock first, and that read is what the latch holds.
        f.worker = command -> {
            Thread thread = new Thread(command, "v3-preparation");
            thread.setDaemon(true);
            thread.start();
        };
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger clockReads = new AtomicInteger();
        f.clock = () -> {
            if (clockReads.incrementAndGet() == 1) {
                started.countDown();
                try {
                    release.await(FIVE_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return System.currentTimeMillis();
        };
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<QuicByteStream>();

        CompletableFuture<Outcome<String>> join = f.join(f.connector(true, true), p);
        try {
            assertTrue(started.await(FIVE_SECONDS, TimeUnit.SECONDS),
                    "the preparation supplier must be running, not merely queued");
            assertTrue(join.cancel(false));
        } finally {
            release.countDown();
        }

        // The supplier runs to completion on its thread, and the branch that sees the cancelled
        // join must end the attempt it admitted.
        String state = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FIVE_SECONDS);
        while (System.nanoTime() < deadline) {
            state = Files.exists(file) ? stateFile(file) : "";
            if (state.contains("\"failures\":1")) {
                break;
            }
            Thread.sleep(10);
        }
        assertTrue(state.contains("\"failures\":1"),
                "a join cancelled while the preparation ran must not leave the server's re-bind"
                        + " state held: " + state);
        assertTrue(f.rebinds.activeKeys().isEmpty(),
                "and the attempt it admitted must be ended, never left held");
        assertEquals(0, p.v3Dials.get(), "nothing may dial for an abandoned join");
        assertEquals(0, p.tcpAttempts.get());
    }

    @Test
    void quicOnlyNeverRetriesOverTcpAfterABindingFailure() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.QUIC_ONLY);

        assertNotNull(outcome.quic(), "quic-only dials /3");
        assertFalse(outcome.v3Plan().failed(V3Plan.Phase.JOINING, V3Plan.BINDING_FAILED),
                "§4: quic-only has no TCP fallback, so a binding failure never authorises one");
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint,
                f.clock.getAsLong()), "the failure is still recorded");
        assertTrue(f.rebinds.activeKeys().isEmpty(), "and the attempt is released");
    }

    @Test
    void aPlatformThatThrowsWhileSettingUpEndsAnAdmittedRebind() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform broken = new FakePlatform() {
            @Override
            public ConnectionRace.Tcp<String> tcp() {
                throw new IllegalStateException("the platform's TCP setup is broken");
            }
        };
        assertThrows(ExecutionException.class,
                () -> f.connect(f.connector(true, true), broken, Mode.AUTO));
        assertTrue(f.rebinds.activeKeys().isEmpty(),
                "a synchronous setup failure must not leave the re-bind attempt active");
    }

    @Test
    void aFailedRaceEndsAnAdmittedRebind() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<QuicByteStream>();
        p.quic.completeExceptionally(new IOException("no QUIC"));
        p.tcp = new CompletableFuture<String>();
        p.tcp.completeExceptionally(new ConnectException("no TCP"));

        assertThrows(ExecutionException.class,
                () -> f.connect(f.connector(true, true), p, Mode.AUTO));
        assertEquals(0, f.rebinds.activeKeys().size(),
                "a race that fails outright must not leave the re-bind attempt active");
    }


    @Test
    void aTcpAnswerForAChangedPinNeverAdmitsAnAttempt() throws Exception {
        // The activation backoff, the full store and §6's handshake backoff all answer TCP, and with
        // the attempt admitted only when the plan really dials /3 none of them can leak one.
        Fixture activation = Fixture.empty();
        activation.advertiseV3();
        activation.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        activation.failures.recordActivationFailure(activation.address(), QUIC_PORT,
                activation.fingerprint);
        assertEquals("tcp",
                activation.connect(activation.connector(true, true), new FakePlatform(), Mode.AUTO).tcp());
        assertEquals(0, activation.rebinds.activeKeys().size());
        assertEquals(0, activation.rebinds.size(), "and nothing is written for it either");

        Fixture backoff = Fixture.empty();
        backoff.advertiseV3();
        backoff.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        backoff.failures.recordFailure(backoff.address(), QUIC_PORT, backoff.fingerprint);
        assertEquals("tcp",
                backoff.connect(backoff.connector(true, true), new FakePlatform(), Mode.AUTO).tcp());
        assertEquals(0, backoff.rebinds.activeKeys().size());
        assertEquals(0, backoff.rebinds.size());
    }

    @Test
    void aFaultInTheDialOfAChangedPinEndsTheAttemptAndUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        FakePlatform p = new FakePlatform();
        Faults.set(Faults.V3_DIAL);
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp(), "12.16: a failed re-bind continues over fresh TCP");
        assertEquals("QUIC multi-stream failed (handshake failed), fell back to TCP",
                outcome.fallbackMessage());
        assertFalse(f.failures.allows(f.address(), QUIC_PORT, f.fingerprint),
                "§6's entry is recorded");
        assertNull(f.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, f.clock.getAsLong()),
                "and the re-bind backoff is up");
    }

    /** The user's fail-safe decision: every failure kind before activation retries over fresh TCP. */
    @Test
    void everyFailureKindBeforeActivationRetriesOverFreshTcpAndTellsThePlayer() throws Exception {
        String[] reasons = {
                V3Plan.BINDING_FAILED,
                V3Plan.REBIND_FAILED,
                V3Plan.HANDSHAKE_TIMED_OUT,
                V3Plan.INTERNAL_ERROR,
                "exporter error",
                "activation deadline",
                "quiescence queue overflow",
                "the ALPN was not offered",
        };
        for (String reason : reasons) {
            Fixture f = Fixture.empty();
            f.advertiseV3();
            f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
            V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
            assertTrue(plan.failed(V3Plan.Phase.JOINING, reason), reason + " must retry over fresh TCP");
            assertEquals("QUIC multi-stream failed (" + reason + "), fell back to TCP",
                    plan.fallbackMessage());
            assertEquals(reason, plan.fallbackReason());
            assertTrue(plan.isFailed());
            assertFalse(plan.isActivated());
            assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.getAsLong()),
                    reason + " must record the activation failure so later joins use TCP");
        }
    }

    @Test
    void theDefaultFailureReasonIsTheInternalErrorOne() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        assertNull(plan.fallbackMessage(), "nothing failed yet");
        assertNull(plan.fallbackReason());
        assertFalse(plan.isFailed());

        assertTrue(plan.failed(V3Plan.Phase.JOINING));
        assertEquals("QUIC multi-stream failed (internal error), fell back to TCP", plan.fallbackMessage());
        // A second report keeps the first cause and never starts a second TCP join.
        assertFalse(plan.failed(V3Plan.Phase.JOINING, "something else"));
        assertEquals(V3Plan.INTERNAL_ERROR, plan.fallbackReason());
    }

    @Test
    void aFailureAfterActivationDoesNotRetryInTheSameAttempt() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        assertTrue(plan.activated(onlineSession()).get(FIVE_SECONDS, TimeUnit.SECONDS));
        assertTrue(plan.isActivated());

        assertFalse(plan.failed(V3Plan.Phase.PLAY, "lane ordering failure"),
                "after activation there is no same-attempt retry; the next join uses TCP");
        assertFalse(plan.isActivated());
        assertEquals("QUIC multi-stream failed (lane ordering failure), fell back to TCP",
                plan.fallbackMessage());
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.getAsLong()),
                "so the next join goes straight to TCP during the backoff");

        // A second report changes nothing, activated() cannot follow a failure, and the pin stays.
        assertFalse(plan.failed(V3Plan.Phase.JOINING, "later still"));
        assertFalse(plan.isActivated());
        assertEquals(f.fingerprint.toString(), f.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).get().fingerprint());
    }

    @Test
    void aFailedQuicHandshakeCarriesThePlayerFacingMessage() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<QuicByteStream>();
        p.quic.completeExceptionally(new javax.net.ssl.SSLHandshakeException("no"));
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp());
        assertEquals(FallbackReason.HANDSHAKE_FAILURE, outcome.fallback());
        assertEquals("QUIC multi-stream failed (handshake failed), fell back to TCP",
                outcome.fallbackMessage());
    }

    @Test
    void aQuicHandshakeThatLosesTheRaceCarriesTheTimeoutMessage() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<QuicByteStream>(); // never completes
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp());
        assertEquals(FallbackReason.TIMEOUT_OR_LOST_RACE, outcome.fallback());
        assertEquals("QUIC multi-stream failed (handshake timed out), fell back to TCP",
                outcome.fallbackMessage());
    }

    @Test
    void theFiveArgumentDialDelegatesForV2AndRefusesV3() {
        final QuicByteStream stream = new ClientConnectorTest.FakeStream();
        ClientConnector.Platform<String> minimal = new ClientConnector.Platform<String>() {
            @Override
            public CompletableFuture<Optional<Advertisement>> queryStatus(InetSocketAddress server) {
                return CompletableFuture.completedFuture(Optional.<Advertisement>empty());
            }

            @Override
            public CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target,
                    Fingerprint fingerprint, EarlyFlight early) {
                return CompletableFuture.completedFuture(stream);
            }

            @Override
            public ConnectionRace.Tcp<String> tcp() {
                return new ConnectionRace.Tcp<String>() {
                    @Override
                    public CompletableFuture<String> connect() {
                        return CompletableFuture.completedFuture("tcp");
                    }

                    @Override
                    public void close(String connection) {}
                };
            }

            @Override
            public ScheduledExecutorService scheduler() {
                return scheduler;
            }
        };

        assertEquals(stream, minimal.connectQuic(SERVER, fingerprint('c'), null,
                TransportSelection.Transport.V2, false).join(), "V2 delegates to the shipped dial");
        assertThrows(UnsupportedOperationException.class, () -> minimal.connectQuic(SERVER,
                fingerprint('c'), null, TransportSelection.Transport.V3, true));
    }

    @Test
    void aConnectorWithoutV3SupportKeepsTheShippedPath() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        FakePlatform p = new FakePlatform();
        ClientConnector plain = new ClientConnector(f.advertisements, f.failures);
        Outcome<String> outcome = plain.connect(SERVER, HOST, LOGICAL_PORT, Mode.AUTO, null, p)
                .get(FIVE_SECONDS, TimeUnit.SECONDS);
        assertNotNull(outcome.quic());
        assertEquals(1, p.v2Dials.get(), "null V3Support means /3 is never selected");
        assertNull(outcome.v3Plan());
    }

    @Test
    void v3SupportRejectsLocalMisuse() {
        PinStore pins = PinStore.load(null);
        RebindState rebinds = RebindState.load(null);
        Executor worker = Runnable::run;
        assertThrows(IllegalArgumentException.class, () -> new ClientConnector.V3Support(
                null, rebinds, worker, System::currentTimeMillis, PROTOCOL, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ClientConnector.V3Support(
                pins, null, worker, System::currentTimeMillis, PROTOCOL, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ClientConnector.V3Support(
                pins, rebinds, null, System::currentTimeMillis, PROTOCOL, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ClientConnector.V3Support(
                pins, rebinds, worker, null, PROTOCOL, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ClientConnector.V3Support(
                pins, rebinds, worker, System::currentTimeMillis, -1, true, true));
    }

    private static String repeat(char c) {
        StringBuilder text = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            text.append(c);
        }
        return text.toString();
    }
}
