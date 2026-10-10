// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager.FingerprintMismatchException;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/** Client session resumption (docs/protocol.md §8): rejoins skip the certificate exchange. */
class SessionResumptionTest {
    @BeforeAll
    static void native_() {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
    }

    @Test
    void rejoinsResumeWithoutACertificateCheck() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        try (QuicServer server = Loopback.echoServer(identity, new CompletableFuture<>())) {
            echo(server.localAddress(), identity, TransportConfig.DEFAULT);
            assertEquals(1, checks(identity, TransportConfig.DEFAULT));
            for (int i = 0; i < 3; i++) {
                echo(server.localAddress(), identity, TransportConfig.DEFAULT);
            }
            assertEquals(1, checks(identity, TransportConfig.DEFAULT), "rejoins must resume");
        }
    }

    @Test
    void withoutResumptionEveryConnectChecksTheCertificate() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        TransportConfig fresh = TransportConfig.builder().sessionResumption(false).build();
        try (QuicServer server = Loopback.echoServer(identity, new CompletableFuture<>())) {
            echo(server.localAddress(), identity, fresh);
            echo(server.localAddress(), identity, fresh);
            assertEquals(0, checks(identity, TransportConfig.DEFAULT), "no shared context used");
        }
    }

    @Test
    void restartedServerGetsAFullHandshake() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        InetSocketAddress address;
        try (QuicServer server = Loopback.echoServer(identity, new CompletableFuture<>())) {
            address = server.localAddress();
            echo(address, identity, TransportConfig.DEFAULT);
        }
        // Same identity and port, new ticket keys: the ticket is refused, the handshake falls back
        // to a full one with the certificate, which still matches.
        try (QuicServer server = Loopback.echoServer(address, identity, new CompletableFuture<>())) {
            echo(server.localAddress(), identity, TransportConfig.DEFAULT);
            assertEquals(2, checks(identity, TransportConfig.DEFAULT));
        }
    }

    @Test
    void ticketOfferedToAnotherServerStillFailsOnTheFingerprint() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        ServerIdentity impostor = ServerIdentity.generate();
        InetSocketAddress address;
        try (QuicServer server = Loopback.echoServer(identity, new CompletableFuture<>())) {
            address = server.localAddress();
            echo(address, identity, TransportConfig.DEFAULT);
        }
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(address, impostor, accepted)) {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> QuicClient.connect(server.localAddress(), identity.fingerprint(), TransportConfig.DEFAULT)
                            .get(5, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof FingerprintMismatchException, () -> "unexpected: " + e.getCause());
            assertFalse(accepted.isDone(), "the impostor must never get the stream");
        }
    }

    @Test
    void aMismatchDoesNotStickToTheSharedContext() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        ServerIdentity other = ServerIdentity.generate();
        try (QuicServer good = Loopback.echoServer(identity, new CompletableFuture<>());
                QuicServer wrong = Loopback.echoServer(other, new CompletableFuture<>())) {
            echo(good.localAddress(), identity, TransportConfig.DEFAULT);
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> QuicClient.connect(wrong.localAddress(), identity.fingerprint(), TransportConfig.DEFAULT)
                            .get(5, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof FingerprintMismatchException, () -> "unexpected: " + e.getCause());
            echo(good.localAddress(), identity, TransportConfig.DEFAULT);
        }
    }

    private static int checks(ServerIdentity identity, TransportConfig config) {
        return QuicClient.certificateChecks(identity.fingerprint(), config);
    }

    private static void echo(InetSocketAddress server, ServerIdentity identity, TransportConfig config)
            throws Exception {
        QuicByteStream stream = QuicClient.connect(server, identity.fingerprint(), config).get(5, TimeUnit.SECONDS);
        try {
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            byte[] hello = "hello again".getBytes(StandardCharsets.UTF_8);
            stream.write(ByteBuffer.wrap(hello));
            stream.flush();
            assertArrayEquals(hello, collector.await(hello.length, 5, TimeUnit.SECONDS));
        } finally {
            stream.close();
        }
    }
}
