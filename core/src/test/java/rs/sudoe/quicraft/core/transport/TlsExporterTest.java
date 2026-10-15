// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * The TLS exporter of QUICraft's patched native (natives/patches/netty/0004, docs/protocol.md
 * §12.7) over a real connection: both ends of one connection must derive the same bytes for the
 * same label, and different bytes for another label or another connection. Every export runs on
 * the stream the game actually holds, a {@link ClientStream} or a {@link ServerStream}, never the
 * Netty stream underneath it. Gradle sets {@code quicraft.test.expectKeyingMaterialExport}: true on
 * a host whose native is QUICraft's build, false in {@code testUpstreamNative} (patched classes,
 * upstream Netty's native), where the capability probe must be false and every export test is
 * skipped. Both runs are required to pass.
 */
class TlsExporterTest {
    private static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    private static boolean expected() {
        String value = System.getProperty("quicraft.test.expectKeyingMaterialExport");
        assertNotNull(value, "run through Gradle, which says whether this host has a patched native");
        return Boolean.parseBoolean(value);
    }

    /** One loopback QUIC connection, both ends already past the handshake. */
    private static final class Connection implements AutoCloseable {
        final QuicServer server;
        final ClientStream client;
        final ServerStream serverStream;

        Connection() throws Exception {
            CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
            server = Loopback.echoServer(identity, accepted);
            QuicByteStream connected = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            // One echoed round trip, so the server has processed the client's Finished: the exporter
            // is only available once the handshake is complete on this end too.
            client = (ClientStream) connected;
            Loopback.Collector collector = new Loopback.Collector();
            client.setListener(collector);
            client.setAutoRead(true);
            byte[] ping = new byte[] {42};
            client.write(ByteBuffer.wrap(ping));
            client.flush();
            assertArrayEquals(ping, collector.await(ping.length, 5, TimeUnit.SECONDS), "no echo");
            serverStream = (ServerStream) accepted.get(5, TimeUnit.SECONDS);
            // What the game holds is never the Netty stream the exporter used to demand.
            assertTrue(client instanceof ClientStream, "the client stream is not a ClientStream");
            assertTrue(serverStream instanceof ServerStream, "the server stream is not a ServerStream");
        }

        @Override
        public void close() {
            client.close();
            server.close();
        }
    }

    /** The export of one end, run on that end's thread as the accessor requires. */
    private static byte[] export(QuicByteStream stream, String label, byte[] context) throws Exception {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        stream.execute(() -> {
            try {
                result.complete(TlsExporter.export(stream, label, context, TlsExporter.BINDING_LENGTH));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(5, TimeUnit.SECONDS);
    }

    @Test
    void exporterIsAvailableExactlyWithThePatchedNative() {
        assertEquals(expected(), QuicSupport.hasKeyingMaterialExport());
    }

    @Test
    void bothEndsDeriveTheSameValue() throws Exception {
        assumeTrue(expected(), "needs the patched native");
        try (Connection connection = new Connection()) {
            byte[] fromClient = export(connection.client, TlsExporter.BINDING_LABEL, null);
            byte[] fromServer = export(connection.serverStream, TlsExporter.BINDING_LABEL, null);
            assertNotNull(fromClient, "the client end derived no exporter value");
            assertNotNull(fromServer, "the server end derived no exporter value");
            assertEquals(TlsExporter.BINDING_LENGTH, fromClient.length);
            assertEquals(TlsExporter.BINDING_LENGTH, fromServer.length);
            assertArrayEquals(fromClient, fromServer, "the two ends derived different values");
            // §12.7 supplies the context explicitly: the empty context and none are one call, and
            // must therefore give one value.
            assertArrayEquals(fromClient, export(connection.client, TlsExporter.BINDING_LABEL, new byte[0]),
                    "the empty context and no context differ");
        }
    }

    @Test
    void otherLabelsAndConnectionsDeriveDifferentValues() throws Exception {
        assumeTrue(expected(), "needs the patched native");
        try (Connection first = new Connection(); Connection second = new Connection()) {
            byte[] value = export(first.client, TlsExporter.BINDING_LABEL, null);
            byte[] otherLabel = export(first.client, TlsExporter.BINDING_LABEL + ".other", null);
            byte[] otherConnection = export(second.client, TlsExporter.BINDING_LABEL, null);
            assertNotNull(value);
            assertNotNull(otherLabel);
            assertNotNull(otherConnection);
            assertFalse(Arrays.equals(value, otherLabel), "another label derived the same value");
            assertFalse(Arrays.equals(value, otherConnection), "another connection derived the same value");
        }
    }

    @Test
    void exportOffTheStreamLoopIsRejected() throws Exception {
        assumeTrue(expected(), "needs the patched native");
        try (Connection connection = new Connection()) {
            assertThrows(IllegalStateException.class, () -> TlsExporter.bindingValue(connection.client),
                    "the exporter must be restricted to the stream's event loop");
            assertThrows(IllegalStateException.class, () -> TlsExporter.bindingValue(connection.serverStream),
                    "the exporter must be restricted to the stream's event loop");
        }
    }

    /**
     * The input guards of {@link TlsExporter#export}: a null stream and a stream that is not a
     * QUICraft one. No native and no connection needed, so this also runs with an upstream native.
     */
    @Test
    void exportRejectsNullAndForeignStreams() {
        assertThrows(NullPointerException.class, () -> TlsExporter.bindingValue(null));

        QuicByteStream foreign = new ForeignStream();
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TlsExporter.bindingValue(foreign),
                "a stream that is not a QUICraft one must be rejected");
        assertTrue(rejected.getMessage().contains("not a QUIC connection"),
                () -> "unexpected message: " + rejected.getMessage());
    }

    /**
     * The §12.7 "before the handshake" answer: on a connection the server passed to the game early
     * (0-RTT data, docs/protocol.md §8) the handshake is still open, so the exporter is null and a
     * caller must treat that as an error, never as an empty value. The server's acceptor already
     * runs on the stream's own event loop, so the export there is a legal call.
     */
    @Test
    void exportIsNullOnAStreamPassedBeforeItsHandshake() throws Exception {
        assumeTrue(expected(), "needs the patched native");
        ServerIdentity serverIdentity = ServerIdentity.generate();
        try (EarlyObserver server = new EarlyObserver(serverIdentity)) {
            // A first join without 0-RTT: passed on after the handshake, and it leaves the early
            // token and session ticket the second one needs to send its first flight early.
            EarlyDataTest.Join first = EarlyDataTest.Join.run(server.address(), serverIdentity, "inputs",
                    EarlyDataTest.FLIGHT, null);
            first.closeAfterTicket(null);

            EarlyDataTest.Join second = EarlyDataTest.Join.run(server.address(), serverIdentity, "inputs",
                    EarlyDataTest.FLIGHT, null);
            assertTrue(second.early.sent(), () -> "the rejoin sent no 0-RTT data: " + QuicClient.lastEarlyOutcome());
            second.stream.close();

            EarlyObserver.Seen early = server.awaitEarly(5, TimeUnit.SECONDS);
            assertTrue(server.server.earlyReleases() > 0, "the server never released a stream early");
            assertNull(early.value, "the exporter must be null before the handshake completes");

            // The first join's stream, passed on after its handshake, did export a value.
            EarlyObserver.Seen confirmed = server.confirmed(5, TimeUnit.SECONDS);
            assertNotNull(confirmed.value, "a stream past its handshake must export a value");
            assertEquals(TlsExporter.BINDING_LENGTH, confirmed.value.length);
        }
    }

    /** A {@link QuicByteStream} that is not a QUICraft stream: the accessor's guard must reject it. */
    private static final class ForeignStream implements QuicByteStream {
        @Override
        public InetSocketAddress remoteAddress() {
            return null;
        }

        @Override
        public InetSocketAddress localAddress() {
            return null;
        }

        @Override
        public void setListener(Listener listener) {}

        @Override
        public void write(ByteBuffer data) {}

        @Override
        public void flush() {}

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public void setAutoRead(boolean autoRead) {}

        @Override
        public void read() {}

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {}

        @Override
        public CompletableFuture<java.util.Map<String, Long>> connectionStats() {
            return CompletableFuture.completedFuture(java.util.Collections.emptyMap());
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public boolean inEventLoop() {
            return true;
        }
    }

    /**
     * A QUIC listener whose acceptor records the exporter value of every stream it passes on and
     * whether the connection's handshake was still open then, which is what an early release means
     * (docs/protocol.md §8). The acceptor runs on the stream's event loop, so the export is legal.
     */
    private static final class EarlyObserver implements AutoCloseable {
        static final class Seen {
            final boolean beforeHandshake;
            final byte[] value;

            Seen(boolean beforeHandshake, byte[] value) {
                this.beforeHandshake = beforeHandshake;
                this.value = value;
            }
        }

        final QuicServer server;
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        private final CountDownLatch earlySeen = new CountDownLatch(1);
        private final CountDownLatch confirmedSeen = new CountDownLatch(1);

        EarlyObserver(ServerIdentity identity) throws Exception {
            // The acceptor runs only after bind() returns, so the listener is published by then.
            QuicServer[] bound = new QuicServer[1];
            server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT, stream -> {
                boolean beforeHandshake = bound[0].earlyReleases() > 0;
                byte[] value = TlsExporter.bindingValue(stream);
                seen.add(new Seen(beforeHandshake, value));
                if (beforeHandshake) {
                    earlySeen.countDown();
                } else {
                    confirmedSeen.countDown();
                }
                stream.setListener(new QuicByteStream.Listener() {
                    @Override
                    public void onData(ByteBuffer data) {
                        stream.write(data);
                        stream.flush();
                    }

                    @Override
                    public void onWritabilityChanged(boolean writable) {}

                    @Override
                    public void onClosed(Throwable cause) {}
                });
                stream.setAutoRead(true);
            });
            bound[0] = server;
        }

        InetSocketAddress address() {
            return server.localAddress();
        }

        Seen awaitEarly(long timeout, TimeUnit unit) throws InterruptedException {
            assertTrue(earlySeen.await(timeout, unit),
                    "the server never passed a stream on before its handshake");
            for (Seen s : seen) {
                if (s.beforeHandshake) {
                    return s;
                }
            }
            throw new AssertionError("no pre-handshake stream recorded");
        }

        Seen confirmed(long timeout, TimeUnit unit) throws InterruptedException {
            assertTrue(confirmedSeen.await(timeout, unit),
                    "the server never passed a stream on after its handshake");
            for (Seen s : seen) {
                if (!s.beforeHandshake) {
                    return s;
                }
            }
            throw new AssertionError("no confirmed stream recorded");
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
