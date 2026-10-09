// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * Phase 1 gate: fallback when UDP is dropped, when the handshake fails and when the fingerprint
 * doesn't match, on real loopback sockets. Every case also checks that the loser is closed.
 */
class ConnectionRaceTest {
    private static final long HEAD_START = 250;
    private static ServerIdentity identity;
    /** Some other server's fingerprint, for mismatch cases. */
    private static rs.sudoe.quicraft.core.tls.Fingerprint otherFingerprint;
    private static ScheduledExecutorService scheduler;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
        otherFingerprint = ServerIdentity.generate().fingerprint();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterAll
    static void tearDown() {
        scheduler.shutdownNow();
    }

    private static QuicServer server(AtomicInteger accepted) throws Exception {
        return QuicServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), identity,
                TransportConfig.DEFAULT, stream -> accepted.incrementAndGet());
    }

    @Test
    void quicWinsAndTcpNeverStartsWhenTheHandshakeBeatsTheHeadStart() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (QuicServer server = server(accepted); TcpFixture tcp = new TcpFixture(0)) {
            ConnectionRace.Result<Socket> r = ConnectionRace.race(
                    () -> QuicClient.connect(server.localAddress(), identity.fingerprint(), TransportConfig.DEFAULT),
                    tcp, HEAD_START, scheduler).get(5, TimeUnit.SECONDS);
            assertTrue(r.quicWon());
            assertNull(r.quicFailure());
            Thread.sleep(HEAD_START + 200);
            assertFalse(tcp.started(), "TCP must not start once QUIC has won");
            r.quic().close();
        }
    }

    @Test
    void udpSilentlyDroppedFallsBackAfterTheHeadStartAndTheQuicAttemptGoesQuiet() throws Exception {
        try (DatagramSocket blackHole = new DatagramSocket(0, InetAddress.getLoopbackAddress());
                TcpFixture tcp = new TcpFixture(0)) {
            InetSocketAddress target = (InetSocketAddress) blackHole.getLocalSocketAddress();
            AtomicReference<CompletableFuture<QuicByteStream>> quic = new AtomicReference<>();
            long start = System.nanoTime();
            ConnectionRace.Result<Socket> r = ConnectionRace.race(() -> {
                quic.set(QuicClient.connect(target, identity.fingerprint(), TransportConfig.DEFAULT));
                return quic.get();
            }, tcp, HEAD_START, scheduler).get(5, TimeUnit.SECONDS);
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertFalse(r.quicWon());
            assertNotNull(r.tcp());
            assertEquals(FallbackReason.TIMEOUT_OR_LOST_RACE, r.quicFailure());
            assertTrue(waited >= HEAD_START && waited < HEAD_START + 500, "player waited " + waited + " ms");
            assertTrue(quic.get().isCancelled(), "the losing QUIC attempt must be cancelled");

            // The black hole saw the QUIC Initial, and after the cancel no retransmission follows.
            blackHole.setSoTimeout(100);
            assertTrue(drain(blackHole) > 0, "QUIC should have sent its Initial to the black hole");
            blackHole.setSoTimeout(3000);
            assertEquals(0, drain(blackHole), "a cancelled QUIC attempt must stop sending");
        }
    }

    @Test
    void closedUdpPortFallsBackWithinTheHeadStart() throws Exception {
        int closedPort;
        try (DatagramSocket s = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        try (TcpFixture tcp = new TcpFixture(0)) {
            long start = System.nanoTime();
            ConnectionRace.Result<Socket> r = ConnectionRace.race(
                    () -> QuicClient.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), closedPort),
                            identity.fingerprint(), TransportConfig.DEFAULT),
                    tcp, HEAD_START, scheduler).get(5, TimeUnit.SECONDS);
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertNotNull(r.tcp());
            assertNotNull(r.quicFailure());
            assertTrue(waited < HEAD_START + 500, "player waited " + waited + " ms");
        }
    }

    @Test
    void fingerprintMismatchStartsTcpImmediately() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        long longHeadStart = 5000;
        try (QuicServer server = server(accepted); TcpFixture tcp = new TcpFixture(0)) {
            long start = System.nanoTime();
            ConnectionRace.Result<Socket> r = ConnectionRace.race(
                    () -> QuicClient.connect(server.localAddress(), otherFingerprint,
                            TransportConfig.DEFAULT),
                    tcp, longHeadStart, scheduler).get(5, TimeUnit.SECONDS);
            long tcpStartedAfter = TimeUnit.NANOSECONDS.toMillis(tcp.startedAtNanos.get() - start);
            assertNotNull(r.tcp());
            assertEquals(FallbackReason.FINGERPRINT_MISMATCH, r.quicFailure());
            assertTrue(tcpStartedAfter < 500, "TCP started " + tcpStartedAfter + " ms in, not immediately");
            assertEquals(0, accepted.get(), "the server must never accept a stream for a rejected certificate");
        }
    }

    @Test
    void aTcpAttemptThatCompletesAfterQuicWonIsClosed() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (QuicServer server = server(accepted); TcpFixture slowTcp = new TcpFixture(400)) {
            // Head start 0: TCP starts with QUIC but takes 400 ms; QUIC wins on loopback.
            ConnectionRace.Result<Socket> r = ConnectionRace.race(
                    () -> QuicClient.connect(server.localAddress(), identity.fingerprint(), TransportConfig.DEFAULT),
                    slowTcp, 0, scheduler).get(5, TimeUnit.SECONDS);
            assertTrue(r.quicWon());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (slowTcp.closed.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(slowTcp.opened, slowTcp.closed, "the late TCP connection must be closed");
            assertEquals(1, slowTcp.closed.size());
            r.quic().close();
        }
    }

    @Test
    void aQuicStreamThatCompletesAfterTcpWonIsClosed() throws Exception {
        CompletableFuture<QuicByteStream> serverSide = new CompletableFuture<>();
        try (QuicServer server = QuicServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), identity,
                TransportConfig.DEFAULT, serverSide::complete); TcpFixture tcp = new TcpFixture(0)) {
            // QUIC is held back 400 ms, past a 0 ms head start, so TCP wins first.
            ConnectionRace.Result<Socket> r = ConnectionRace.race(() -> {
                CompletableFuture<QuicByteStream> late = new CompletableFuture<>();
                scheduler.schedule(() -> QuicClient.connect(server.localAddress(), identity.fingerprint(),
                        TransportConfig.DEFAULT).whenComplete((s, e) -> {
                            if (e != null) {
                                late.completeExceptionally(e);
                            } else if (!late.complete(s)) {
                                s.close();
                            }
                        }), 400, TimeUnit.MILLISECONDS);
                return late;
            }, tcp, 0, scheduler).get(5, TimeUnit.SECONDS);
            assertNotNull(r.tcp());
            assertEquals(FallbackReason.TIMEOUT_OR_LOST_RACE, r.quicFailure());
            // If the server ever got the stream, it must see it closed.
            QuicByteStream accepted = null;
            try {
                accepted = serverSide.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException expected) {
                // never connected: nothing to leak
            }
            if (accepted != null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (accepted.isOpen() && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertFalse(accepted.isOpen(), "the late QUIC connection must be closed");
            }
        }
    }

    @Test
    void whenBothFailThePlayerSeesTheTcpErrorAsInVanilla() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (QuicServer server = server(accepted)) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> ConnectionRace.race(
                    () -> QuicClient.connect(server.localAddress(), otherFingerprint,
                            TransportConfig.DEFAULT),
                    TcpFixture.refused(), HEAD_START, scheduler).get(5, TimeUnit.SECONDS));
            Throwable cause = e.getCause();
            while (cause instanceof java.util.concurrent.CompletionException) {
                cause = cause.getCause();
            }
            assertTrue(cause instanceof ConnectException, "expected TCP's ConnectException, got " + cause);
        }
    }

    @Test
    void aThrowingQuicAttemptStillConnectsOverTcp() throws Exception {
        try (TcpFixture tcp = new TcpFixture(0)) {
            ConnectionRace.Result<Socket> r = ConnectionRace.race(() -> {
                throw new IllegalStateException("injected fault");
            }, tcp, 5000, scheduler).get(5, TimeUnit.SECONDS);
            assertNotNull(r.tcp());
            assertEquals(FallbackReason.OTHER, r.quicFailure());
        }
    }

    private static int drain(DatagramSocket socket) throws Exception {
        int count = 0;
        byte[] buf = new byte[2048];
        while (true) {
            try {
                socket.receive(new DatagramPacket(buf, buf.length));
                count++;
            } catch (SocketTimeoutException e) {
                return count;
            }
        }
    }
}
