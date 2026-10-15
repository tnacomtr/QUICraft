// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * The TLS exporter of QUICraft's patched native (natives/patches/netty/0004, docs/protocol.md
 * §12.7) over a real connection: both ends of one connection must derive the same bytes for the
 * same label, and different bytes for another label or another connection. Gradle sets
 * {@code quicraft.test.expectKeyingMaterialExport}: true on a host whose native is QUICraft's
 * build, false in {@code testUpstreamNative} (patched classes, upstream Netty's native), where the
 * capability probe must be false and every export test is skipped. Both runs are required to pass.
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
        final QuicByteStream client;
        final QuicByteStream serverStream;

        Connection() throws Exception {
            CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
            server = Loopback.echoServer(identity, accepted);
            client = QuicClient.connect(server.localAddress(), identity.fingerprint(), TransportConfig.DEFAULT)
                    .get(5, TimeUnit.SECONDS);
            // One echoed round trip, so the server has processed the client's Finished: the exporter
            // is only available once the handshake is complete on this end too.
            Loopback.Collector collector = new Loopback.Collector();
            client.setListener(collector);
            client.setAutoRead(true);
            byte[] ping = new byte[] {42};
            client.write(ByteBuffer.wrap(ping));
            client.flush();
            assertArrayEquals(ping, collector.await(ping.length, 5, TimeUnit.SECONDS), "no echo");
            serverStream = accepted.get(5, TimeUnit.SECONDS);
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
        }
    }
}
