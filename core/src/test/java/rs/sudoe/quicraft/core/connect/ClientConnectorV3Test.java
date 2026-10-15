// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.connect.ClientConnector.Outcome;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.connect.TransportSelection.PinState;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
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
    static final class FakePlatform implements ClientConnector.Platform<String> {
        CompletableFuture<Optional<Advertisement>> status =
                CompletableFuture.completedFuture(Optional.<Advertisement>empty());
        CompletableFuture<QuicByteStream> quic =
                CompletableFuture.completedFuture(new ClientConnectorTest.FakeStream());
        CompletableFuture<String> tcp = CompletableFuture.completedFuture("tcp");
        final AtomicInteger v2Dials = new AtomicInteger();
        final AtomicInteger v3Dials = new AtomicInteger();
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
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final FailureCache failures = FailureCache.load(null, clock::get);
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
                    new ClientConnector.V3Support(pins, rebinds, worker, clock::get, protocolNumber,
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
        f.pins.recordPin(HOST, LOGICAL_PORT, f.fingerprint.toString());
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
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
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
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
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
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
        FakePlatform p = new FakePlatform();
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp(), "12.16: fresh TCP, never /2 and never 0x10");
        assertEquals(0, p.v3Dials.get(), "no /3 connection is created at all");
        assertEquals(0, p.v2Dials.get());
        assertNull(outcome.v3Plan());
        assertFalse(f.failures.allows(f.address(), QUIC_PORT, f.fingerprint),
                "§6's failure-cache entry is recorded");
        // And the re-bind backoff is up: a second attempt is refused.
        assertNull(f.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, f.clock.get()),
                "12.16's re-bind backoff is recorded too");
    }

    @Test
    void aChangedPinWithNoAdvertisedDigestUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3(Collections.<Integer, String>emptyMap());
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
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
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
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
            f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
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
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
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
            f.pins.recordPin("server" + i + ".example", 25565, fingerprint('e').toString());
        }
        assertEquals(PinStore.RecordResult.RECORDED,
                f.pins.recordPin(HOST, LOGICAL_PORT, f.fingerprint.toString()));
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
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.get()));

        // What the race records on a QUIC handshake win must not clear it (12.14).
        f.failures.recordSuccess(f.address(), QUIC_PORT, f.fingerprint);
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.get()));

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
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
        f.failures.recordActivationFailure(f.address(), QUIC_PORT, f.fingerprint);
        // A blocked activation would keep /3 off, so clear it first: this test is about the end of
        // a successful attempt.
        f.failures.clearActivationFailure(f.address(), QUIC_PORT, f.fingerprint);

        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        plan.authenticatedLogin(true);
        assertTrue(plan.activated().get(FIVE_SECONDS, TimeUnit.SECONDS));

        assertEquals(f.fingerprint.toString(), f.pins.pin(HOST, LOGICAL_PORT).get().fingerprint(),
                "12.15: an authenticated activation records the pin");
        assertFalse(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.get()));
        // The attempt is over and its state is cleared, so a fresh one is admitted.
        assertNotNull(f.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, f.clock.get()));
    }

    @Test
    void activationInOfflineModeWritesNoPin() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        // 12.19: offline mode never creates or updates an authenticated pin, and the default is
        // "not authenticated".
        assertTrue(plan.activated().get(FIVE_SECONDS, TimeUnit.SECONDS));
        assertFalse(f.pins.pin(HOST, LOGICAL_PORT).isPresent());
    }

    @Test
    void aFailedPinWriteClosesTheConnectionAndLeavesThePinAlone() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        plan.authenticatedLogin(true);

        Faults.set(Faults.PIN_WRITE);
        assertFalse(plan.activated().get(FIVE_SECONDS, TimeUnit.SECONDS),
                "12.15: a failed pin write must be reported so the caller closes with 0x10");
        Faults.set();
        assertEquals(fingerprint('d').toString(), f.pins.pin(HOST, LOGICAL_PORT).get().fingerprint(),
                "the old pin stays until a passing check replaces it");
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.get()),
                "a write failure after activation is itself an activation failure");
    }

    @Test
    void failureRecordsTheRightKindOfEntryAndRetriesForEveryKindBeforeActivation() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
        V3Plan changed = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        assertTrue(changed.failed(false, V3Plan.REBIND_FAILED),
                "the user's fail-safe decision: a failed re-bind continues over fresh TCP");
        assertEquals("QUIC multi-stream failed (" + V3Plan.REBIND_FAILED + "), fell back to TCP",
                changed.fallbackMessage());
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.get()),
                "12.14: the failure is recorded as an activation failure");
        assertTrue(f.failures.allows(f.address(), QUIC_PORT, f.fingerprint),
                "and it is not also a handshake entry");

        Fixture g = Fixture.empty();
        g.advertiseV3();
        V3Plan ordinary = g.connect(g.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        assertNull(ordinary.attempt(), "an ordinary pin takes no re-bind attempt");
        assertTrue(ordinary.failed(false, V3Plan.BINDING_FAILED),
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
    void aWorkerThatRefusesTasksStillYieldsATcpOutcome() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
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
    void aFaultInTheDialOfAChangedPinEndsTheAttemptAndUsesTcp() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
        FakePlatform p = new FakePlatform();
        Faults.set(Faults.V3_DIAL);
        Outcome<String> outcome = f.connect(f.connector(true, true), p, Mode.AUTO);

        assertEquals("tcp", outcome.tcp(), "12.16: a failed re-bind continues over fresh TCP");
        assertEquals("QUIC multi-stream failed (handshake failed), fell back to TCP",
                outcome.fallbackMessage());
        assertFalse(f.failures.allows(f.address(), QUIC_PORT, f.fingerprint),
                "§6's entry is recorded");
        assertNull(f.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, f.clock.get()),
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
            f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
            V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
            assertTrue(plan.failed(false, reason), reason + " must retry over fresh TCP");
            assertEquals("QUIC multi-stream failed (" + reason + "), fell back to TCP",
                    plan.fallbackMessage());
            assertEquals(reason, plan.fallbackReason());
            assertTrue(plan.isFailed());
            assertFalse(plan.isActivated());
            assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.get()),
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

        assertTrue(plan.failed(false));
        assertEquals("QUIC multi-stream failed (internal error), fell back to TCP", plan.fallbackMessage());
        // Reporting twice keeps the first answer and the first reason.
        assertTrue(plan.failed(false, "something else"));
        assertEquals(V3Plan.INTERNAL_ERROR, plan.fallbackReason());
    }

    @Test
    void aFailureAfterActivationDoesNotRetryInTheSameAttempt() throws Exception {
        Fixture f = Fixture.empty();
        f.advertiseV3();
        f.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString());
        V3Plan plan = f.connect(f.connector(true, true), new FakePlatform(), Mode.AUTO).v3Plan();
        plan.authenticatedLogin(true);
        assertTrue(plan.activated().get(FIVE_SECONDS, TimeUnit.SECONDS));
        assertTrue(plan.isActivated());

        assertFalse(plan.failed(true, "lane ordering failure"),
                "after activation there is no same-attempt retry; the next join uses TCP");
        assertFalse(plan.isActivated());
        assertEquals("QUIC multi-stream failed (lane ordering failure), fell back to TCP",
                plan.fallbackMessage());
        assertTrue(f.failures.isActivationBlocked(f.address(), QUIC_PORT, f.fingerprint, f.clock.get()),
                "so the next join goes straight to TCP during the backoff");

        // A second report changes nothing, activated() cannot follow a failure, and the pin stays.
        assertFalse(plan.failed(true, "later still"));
        assertFalse(plan.activated().join().booleanValue());
        assertEquals(f.fingerprint.toString(), f.pins.pin(HOST, LOGICAL_PORT).get().fingerprint());
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
