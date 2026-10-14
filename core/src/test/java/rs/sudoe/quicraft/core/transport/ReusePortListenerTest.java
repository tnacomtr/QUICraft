// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * One listener on several SO_REUSEPORT UDP sockets, each on its own event loop (docs/protocol.md
 * §11): connections spread over the sockets, every limit stays node-wide, and closing releases all
 * of them.
 *
 * <p>Only Linux is tested: that is where the kernel's 4-tuple hash balances unicast UDP over the
 * sockets, and Windows has no SO_REUSEPORT at all ({@code QuicServer.defaultSocketCount}). The
 * tests that need several real sockets skip themselves elsewhere; the single-socket binds every
 * platform uses are covered by {@code QuicLoopbackTest} and are unchanged.
 */
class ReusePortListenerTest {
    private static final int SOCKETS = 4;
    private static final int CLIENTS = 64;
    /** Threads of a listener's own group; a client's are named quicraft-client-. */
    private static final String SERVER_THREAD_PREFIX = "quicraft-server-";
    private static final long CONNECT_TIMEOUT_SECONDS = 10;

    private static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    /** These tests bind one address several times, which only Linux balances between sockets. */
    private static void assumeSeveralSockets() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Linux"), "SO_REUSEPORT balancing is Linux only");
        assumeTrue(TestHost.reusePortAvailable(), "SO_REUSEPORT is unavailable on this JDK");
    }

    /** Connection limits raised: several of these tests open many connections from one IP at once. */
    private static TransportConfig limits(int maxOpen) {
        return TransportConfig.builder()
                .maxOpen(maxOpen)
                .maxOpenPerIp(maxOpen)
                .globalAttemptsPerSecond(100_000)
                .perIpAttemptsPerSecond(10_000)
                .retryThresholdPerSecond(100_000)
                .maxTrackedIps(1_000_000)
                .ingressInitialsPerSecond(100_000)
                .ingressInitialsPerIpPerSecond(100_000)
                .build();
    }

    @Test
    void connectionsSpreadOverSeveralSockets() throws Exception {
        assumeSeveralSockets();
        TransportConfig config = limits(100_000);
        List<QuicByteStream> streams = new ArrayList<>();
        QuicServer server = QuicServer.bind(Loopback.anyLocal(), SOCKETS, identity, config,
                Loopback.echo(new CompletableFuture<>()));
        try {
            assertEquals(SOCKETS, server.socketCount(), "every requested socket was bound");
            for (int i = 0; i < CLIENTS; i++) {
                QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(), config)
                        .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                streams.add(stream);
                Loopback.Collector collector = new Loopback.Collector();
                stream.setListener(collector);
                stream.setAutoRead(true);
                byte[] payload = ("client " + i).getBytes(StandardCharsets.UTF_8);
                stream.write(ByteBuffer.wrap(payload));
                stream.flush();
                assertArrayEquals(payload, collector.await(payload.length, 5, TimeUnit.SECONDS),
                        "client " + i + " got its echo");
            }
            int[] accepted = server.acceptedConnectionsPerSocket();
            System.out.println(CLIENTS + " connections accepted per socket: " + Arrays.toString(accepted));
            assertEquals(CLIENTS, sum(accepted), "every connection was accepted by exactly one socket");
            assertTrue(socketsUsed(accepted) > 1,
                    "the kernel put every connection on one socket: " + Arrays.toString(accepted));
        } finally {
            for (QuicByteStream stream : streams) {
                stream.close();
            }
            server.close();
        }
    }

    @Test
    void theOpenConnectionLimitStaysNodeWide() throws Exception {
        assumeSeveralSockets();
        TransportConfig config = limits(8);
        List<QuicByteStream> open = new ArrayList<>();
        List<QuicByteStream> served = java.util.Collections.synchronizedList(new ArrayList<>());
        QuicServer server = QuicServer.bind(Loopback.anyLocal(), SOCKETS, identity, config, stream -> {
            served.add(stream);
            stream.setListener(new Loopback.Collector()); // open, and reading nothing
        });
        try {
            for (int i = 0; i < 8; i++) {
                QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(), config)
                        .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                open.add(stream);
            }
            // The server hands a stream over only once its handshake completed, which confirms its
            // lease; after that no connection of the eight is still pending.
            awaitServers(served, 8);
            assertEquals(0, server.pendingHandshakes(), "every lease was confirmed");
            long retriesBefore = server.retriesSent();

            // Over the limit: a Retry at most, then silence, whichever of the four sockets the
            // kernel hands this attempt to. Exactly as with a single socket.
            long start = System.nanoTime();
            CompletableFuture<QuicByteStream> ninth = QuicClient.connect(server.localAddress(),
                    identity.fingerprint(), config);
            assertThrows(TimeoutException.class, () -> ninth.get(2, TimeUnit.SECONDS),
                    "the 9th connection must be refused although the listener has four sockets");
            System.out.printf("over the node-wide limit: no connection after %d ms, Retry packets %d%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), server.retriesSent() - retriesBefore);
            ninth.cancel(true);
            assertTrue(server.retriesSent() > retriesBefore, "the refused attempt is answered with a Retry");

            int[] accepted = server.acceptedConnectionsPerSocket();
            assertEquals(8, sum(accepted), "the limit counts every socket together: " + Arrays.toString(accepted));
        } finally {
            for (QuicByteStream stream : open) {
                stream.close();
            }
            server.close();
        }
    }

    @Test
    void closeReleasesEverySocket() throws Exception {
        assumeSeveralSockets();
        TransportConfig config = limits(1000);
        QuicServer server = QuicServer.bind(Loopback.anyLocal(), SOCKETS, identity, config,
                Loopback.echo(new CompletableFuture<>()));
        InetSocketAddress address = server.localAddress();
        try {
            QuicByteStream stream = QuicClient.connect(address, identity.fingerprint(), config)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stream.close();
        } finally {
            server.close();
        }

        // Every socket gave the port back: a single-socket listener takes it and serves.
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer again = Loopback.echoServer(address, identity, config, accepted)) {
            assertEquals(1, again.socketCount());
            QuicByteStream stream = QuicClient.connect(again.localAddress(), identity.fingerprint(), config)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            byte[] payload = "after the close".getBytes(StandardCharsets.UTF_8);
            stream.write(ByteBuffer.wrap(payload));
            stream.flush();
            assertArrayEquals(payload, collector.await(payload.length, 5, TimeUnit.SECONDS));
            stream.close();
        }
    }

    @Test
    void aFailedBindLeavesNoSocketOrThreadBehind() throws Exception {
        assumeSeveralSockets();
        TransportConfig config = limits(1000);
        // A plain socket holds the port without SO_REUSEPORT, so the first (and every later)
        // SO_REUSEPORT bind of this listener fails and the whole bind must unwind.
        DatagramSocket holder = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        InetSocketAddress address = (InetSocketAddress) holder.getLocalSocketAddress();
        try {
            Exception failure = assertThrows(Exception.class, () -> QuicServer.bind(address, SOCKETS, identity,
                    config, Loopback.echo(new CompletableFuture<>())));
            assertTrue(failure instanceof SocketException, () -> "unexpected failure: " + failure);
        } finally {
            holder.close();
        }
        TestHost.awaitNoThreads(SERVER_THREAD_PREFIX, 5_000);
        assertFalse(TestHost.hasThread(SERVER_THREAD_PREFIX), "a failed bind leaked a quicraft-server thread");

        // Nothing is left bound either: the same port takes a full multi-socket listener now.
        try (QuicServer server = QuicServer.bind(address, SOCKETS, identity, config,
                Loopback.echo(new CompletableFuture<>()))) {
            assertEquals(SOCKETS, server.socketCount());
        }
        TestHost.awaitNoThreads(SERVER_THREAD_PREFIX, 5_000);
        assertFalse(TestHost.hasThread(SERVER_THREAD_PREFIX), "close() leaked a quicraft-server thread");
    }

    @Test
    void hostedSocketsShareOneListener() throws Exception {
        assumeSeveralSockets();
        TransportConfig config = limits(100_000);
        TestHost.Loop first = new TestHost.Loop("quicraft-test-host-1");
        TestHost.Loop second = new TestHost.Loop("quicraft-test-host-2");
        TestHost.Socket socketOne = null;
        TestHost.Socket socketTwo = null;
        QuicServer server = null;
        try {
            socketOne = new TestHost.Socket(first, Loopback.anyLocal());
            // The platform binds each of its own sockets with SO_REUSEPORT to the same address.
            socketTwo = new TestHost.Socket(second, socketOne.localAddress());
            server = QuicServer.bind(Arrays.asList((HostLoop) first, second),
                    Arrays.asList((HostDatagramSocket) socketOne, socketTwo), identity, config,
                    Loopback.echo(new CompletableFuture<>()));
            assertEquals(2, server.socketCount());
            for (int i = 0; i < 32; i++) {
                QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(), config)
                        .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                Loopback.Collector collector = new Loopback.Collector();
                stream.setListener(collector);
                stream.setAutoRead(true);
                byte[] payload = ("hosted " + i).getBytes(StandardCharsets.UTF_8);
                stream.write(ByteBuffer.wrap(payload));
                stream.flush();
                assertArrayEquals(payload, collector.await(payload.length, 5, TimeUnit.SECONDS));
                stream.close();
            }
            int[] accepted = server.acceptedConnectionsPerSocket();
            System.out.println("hosted connections accepted per socket: " + Arrays.toString(accepted));
            assertEquals(32, sum(accepted), "every connection was accepted by exactly one host socket");
            assertTrue(socketsUsed(accepted) > 1, "one host socket served everything: " + Arrays.toString(accepted));
        } finally {
            if (server != null) {
                server.close();
            } else {
                if (socketOne != null) {
                    socketOne.close();
                }
                if (socketTwo != null) {
                    socketTwo.close();
                }
            }
            first.shutdown();
            second.shutdown();
        }
    }

    @Test
    void aHostedBindFailureClosesTheSocketsItBound() throws Exception {
        assumeTrue(QuicSupport.isAvailable(), "QUIC native unavailable");
        TestHost.Loop first = new TestHost.Loop("quicraft-test-host-3");
        TestHost.Loop second = new TestHost.Loop("quicraft-test-host-4");
        TestHost.Socket socketOne = new TestHost.Socket(first, Loopback.anyLocal());
        TestHost.Socket socketTwo = new TestHost.Socket(second, socketOne.localAddress());
        // The second socket cannot start: the bind must close the first one, which is registered.
        socketTwo.failOnStart(true);
        try {
            assertThrows(Exception.class, () -> QuicServer.bind(
                    Arrays.asList((HostLoop) first, second),
                    Arrays.asList((HostDatagramSocket) socketOne, socketTwo), identity, TransportConfig.DEFAULT,
                    Loopback.echo(new CompletableFuture<>())));
            assertTrue(socketOne.isClosed(), "the socket bound before the failure must be closed");
            assertTrue(socketTwo.isClosed(), "the socket that failed must be closed too");
        } finally {
            socketOne.close();
            socketTwo.close();
            first.shutdown();
            second.shutdown();
        }
    }

    @Test
    void everyConnectionIdTheListenerIssuesNamesItsSocket() throws Exception {
        assumeSeveralSockets();
        TransportConfig config = limits(1000);
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), SOCKETS, identity, config,
                Loopback.echo(accepted));
                Relay relay = new Relay(server.localAddress())) {
            QuicByteStream stream = QuicClient.connect(relay.address(), identity.fingerprint(), config)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int index = socketIndexOf(relay.firstFromServer.get(5, TimeUnit.SECONDS));
            int[] perSocket = server.acceptedConnectionsPerSocket();
            assertTrue(index >= 0 && index < SOCKETS, "the connection id named socket " + index);
            // Netty's dispatcher routes a datagram by these two bytes, so a connection id that
            // named another socket would send the client's packets to a codec that doesn't know it.
            assertEquals(1, perSocket[index],
                    "the connection id must name the socket that accepted the connection: "
                            + Arrays.toString(perSocket));
            stream.close();
        }
    }

    /**
     * The socket index the listener encoded into the source connection id of its first datagram to
     * the client. A long header is {@code byte0, version(4), dcidLength, dcid, scidLength, scid}
     * (RFC 9000 §17.2); Netty's generator puts the index in the first two bytes of every id.
     */
    private static int socketIndexOf(byte[] datagram) {
        int dcidLength = datagram[5] & 0xFF;
        int scidLength = datagram[6 + dcidLength] & 0xFF;
        assertTrue(scidLength >= 2, "the listener's connection ids carry the index in two bytes");
        int scid = 7 + dcidLength;
        return ((datagram[scid] & 0xFF) << 8) | (datagram[scid + 1] & 0xFF);
    }

    /** A UDP relay that records the first datagram the listener sends. */
    private static final class Relay implements AutoCloseable {
        private final DatagramSocket socket;
        private final InetSocketAddress server;
        private final CompletableFuture<byte[]> firstFromServer = new CompletableFuture<>();
        private volatile InetSocketAddress client;
        private final Thread pump;

        Relay(InetSocketAddress server) throws SocketException {
            this.server = server;
            this.socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            this.pump = new Thread(this::pump, "reuse-port-relay");
            pump.setDaemon(true);
            pump.start();
        }

        InetSocketAddress address() {
            return (InetSocketAddress) socket.getLocalSocketAddress();
        }

        private void pump() {
            byte[] buffer = new byte[65536];
            while (!socket.isClosed()) {
                java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (java.io.IOException closed) {
                    return;
                }
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                boolean fromServer = server.equals(packet.getSocketAddress());
                if (fromServer) {
                    firstFromServer.complete(data);
                } else {
                    client = (InetSocketAddress) packet.getSocketAddress();
                }
                InetSocketAddress to = fromServer ? client : server;
                if (to == null) {
                    continue;
                }
                try {
                    socket.send(new java.net.DatagramPacket(data, data.length, to));
                } catch (java.io.IOException ignored) {
                    // closing
                }
            }
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    /** Waits until the server handed over {@code count} streams, which happens after their handshake. */
    private static void awaitServers(List<QuicByteStream> served, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECT_TIMEOUT_SECONDS);
        while (served.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(count, served.size(), "the server never handed over every stream");
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private static int socketsUsed(int[] accepted) {
        int used = 0;
        for (int value : accepted) {
            if (value > 0) {
                used++;
            }
        }
        return used;
    }
}
