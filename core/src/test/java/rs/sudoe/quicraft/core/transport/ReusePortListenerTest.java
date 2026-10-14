// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
    /** How many connections each rebinding test moves to a new local port, one rebind per connection. */
    private static final int REBINDS = 8;
    /** Source ports whose 4-tuple hash is measured before the rebind, to aim at another socket. */
    private static final int CALIBRATIONS = 8;
    /** The echo has to come back this fast; quiche retransmits a lost datagram well inside it. */
    private static final long ECHO_TIMEOUT_SECONDS = 5;

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

    /**
     * A short-header datagram that stops before the whole connection id, one byte {@code 40},
     * {@code 40 00}, or a header without the full 20-byte id, must never reach Netty's dispatcher:
     * its parser throws for one ({@code QuicHeaderParser.sliceCid}), and no handler on a datagram
     * pipeline catches that, so the pipeline's tail logs a stack trace and the (direct, pooled)
     * buffer leaks, once per datagram, for anything a remote sender cares to send to a listener with
     * two or more sockets. The bound in front of the dispatcher drops and counts them instead, and
     * the backstop swallows whatever else a datagram handler throws. The listener must be otherwise
     * unaffected: a real connection still completes and echoes afterwards.
     */
    @Test
    void aTruncatedShortHeaderIsDroppedAndTheListenerKeepsServing() throws Exception {
        assumeSeveralSockets();
        TransportConfig config = limits(1000);
        Logger root = Logger.getLogger("");
        WarningCollector warnings = new WarningCollector();
        root.addHandler(warnings);
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), SOCKETS, identity, config,
                Loopback.echo(new CompletableFuture<>()));
                DatagramSocket sender = new DatagramSocket(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            byte[] oneByte = {0x40};
            byte[] twoBytes = {0x40, 0x00};
            // 20 bytes: the header byte plus 19, one short of the whole 20-byte connection id.
            byte[] headerWithoutTheWholeId = new byte[Codecs.LOCAL_CONNECTION_ID_LENGTH];
            headerWithoutTheWholeId[0] = 0x40;
            byte[][] malformed = {oneByte, twoBytes, headerWithoutTheWholeId};
            int sent = 0;
            for (int round = 0; round < 8; round++) {
                for (byte[] bytes : malformed) {
                    sender.send(new DatagramPacket(bytes, bytes.length, server.localAddress()));
                    sent++;
                }
            }

            assertTrue(awaitMalformed(server, sent, 5), "every datagram sent must be dropped and counted: "
                    + Arrays.toString(server.droppedMalformedPerSocket()));
            final int total = sent;
            int[] dropped = server.droppedMalformedPerSocket();
            assertAll("every truncated short header is dropped on the socket that read it, and logged nowhere",
                    () -> assertEquals(SOCKETS, dropped.length, "one malformed-drop counter per socket"),
                    () -> assertEquals(total, sum(dropped), "dropped: " + Arrays.toString(dropped)),
                    () -> assertEquals(0, sum(server.droppedForwardsPerSocket()), "none of them is a forward drop"),
                    () -> assertTrue(warnings.records.isEmpty(), "a log record at WARNING or above means "
                            + "something reached a pipeline tail: " + warnings.records));

            // The listener is untouched otherwise: a real connection still completes and echoes.
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(), config)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            byte[] payload = "after the malformed datagrams".getBytes(StandardCharsets.UTF_8);
            stream.write(ByteBuffer.wrap(payload));
            stream.flush();
            assertArrayEquals(payload, collector.await(payload.length, 5, TimeUnit.SECONDS),
                    "a connection after the flood got its echo");
            stream.close();
        } finally {
            root.removeHandler(warnings);
        }
    }

    /** Waits until {@code expected} datagrams are counted as malformed across the listener's sockets. */
    private static boolean awaitMalformed(QuicServer server, int expected, long seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (sum(server.droppedMalformedPerSocket()) < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return sum(server.droppedMalformedPerSocket()) == expected;
    }

    /** Collects every record at WARNING or above, from any logger, so a test can assert there was none. */
    private static final class WarningCollector extends Handler {
        final List<String> records = java.util.Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record.getLevel() + " " + record.getLoggerName() + ": " + record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
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
     * A NAT rebind with today's single-socket listener: the client's datagrams reach the listener
     * from a new source port mid-connection, as they do when a home NAT picks a new external port.
     * There is no second socket that could forward anything here, so this is the baseline: does the
     * QUIC layer accept the peer-address change at all, with the transport parameters we send?
     * ({@code Codecs.apply} leaves Netty's active-migration option at its default, off.)
     *
     * <p>It does, once per connection. A second rebind is silently dropped, which is quiche's path
     * bookkeeping rather than a listener bug: a new 4-tuple needs a new path, and quiche's path map
     * holds at most {@code active_connection_id_limit} paths (2, the RFC 9000 default, and what
     * Netty's codec leaves it at), of which the old path stays non-removable because it keeps the
     * connection's destination connection id. That is pinned below, so a quiche change surfaces
     * here instead of silently in production.
     */
    @Test
    void aRebindKeepsTheConnectionAliveOnOneSocket() throws Exception {
        TransportConfig config = limits(1000);
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), 1, identity, config,
                Loopback.echo(new CompletableFuture<>()));
                RebindRelay relay = new RebindRelay(server.localAddress(), 0)) {
            assertEquals(1, server.socketCount(), "the baseline is the single-socket listener every platform uses");
            QuicByteStream stream = QuicClient.connect(relay.address(), identity.fingerprint(), config)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            int received = echoRoundTrip(stream, collector, 0, "before the rebind");

            relay.rebind(0);
            System.out.println("single socket, rebind 1: the listener now sees the client at port "
                    + relay.upstreamPort());
            received = echoRoundTrip(stream, collector, received, "after the first rebind");
            assertTrue(stream.isOpen(), "the connection is still open after the rebind");

            relay.rebind(0);
            byte[] second = "after the second rebind".getBytes(StandardCharsets.UTF_8);
            stream.write(ByteBuffer.wrap(second));
            stream.flush();
            int afterSecond = collector.await(received + second.length, 3, TimeUnit.SECONDS).length;
            System.out.println("single socket, rebind 2 (port " + relay.upstreamPort() + "): "
                    + (afterSecond - received) + " of " + second.length + " bytes came back");
            assertEquals(received, afterSecond,
                    "quiche accepted a second peer address change on one connection: with the limits we send it "
                            + "allows exactly one. Update this test if a dependency upgrade changed that");
            assertTrue(stream.isOpen(), "a dropped rebind leaves the connection open, not closed");
            stream.close();
        }
    }

    /**
     * The same rebind with {@link #SOCKETS} SO_REUSEPORT sockets. For each round the kernel's
     * 4-tuple hash is measured first, one probe connection per candidate source port: the socket
     * that counts the probe is the socket the kernel gives every datagram from that port. The
     * connection is then made from a measured port, so the socket that accepts it is predicted
     * before the connect and checked against the counters after it, and it is rebound onto a port
     * the hash gives to a different socket. Those datagrams are read by a socket whose codec knows
     * no such connection: only {@link io.netty.handler.codec.quic.QuicCodecDispatcher}
     * (QuicServer.SocketDispatcher) can deliver them, by the connection id the listener issued. The
     * echo proves they arrived; the counters prove no second connection was made for them.
     *
     * <p>Several connections with one rebind each rather than several rebinds on one connection:
     * quiche accepts only one peer-address change per connection, see
     * {@link #aRebindKeepsTheConnectionAliveOnOneSocket()}.
     */
    @Test
    void aRebindOntoAnotherSocketKeepsTheConnectionAlive() throws Exception {
        assumeSeveralSockets();
        TransportConfig config = limits(1000);
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), SOCKETS, identity, config,
                Loopback.echo(new CompletableFuture<>()))) {
            assertEquals(SOCKETS, server.socketCount());
            Map<Integer, Integer> socketOfPort = calibrateSourcePorts(server, config);
            // The mapping is a property of the 4-tuple, not a one-off: the same port measured again,
            // after the probes and their sockets are gone, still gets the same socket.
            int firstPort = socketOfPort.keySet().iterator().next();
            assertEquals(socketOfPort.get(firstPort).intValue(), socketForSourcePort(server, config, firstPort),
                    "the kernel must give the same source port to the same socket every time: " + socketOfPort);

            int proven = 0;
            Map<String, Integer> pairs = new LinkedHashMap<>();
            for (int round = 1; round <= REBINDS; round++) {
                try (RebindRelay relay = new RebindRelay(server.localAddress(), 0)) {
                    // Speak from a measured port, so the accepting socket is known in advance. The
                    // rotation makes the rounds use different measured ports, hence different
                    // owning and receiving sockets.
                    int from = rebindToCalibratedPort(relay, socketOfPort, -1, round * 3);
                    int[] beforeConnect = server.acceptedConnectionsPerSocket();
                    QuicByteStream stream = QuicClient.connect(relay.address(), identity.fingerprint(), config)
                            .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    int[] afterConnect = server.acceptedConnectionsPerSocket();
                    int owner = theOnlySocketThatGained(beforeConnect, afterConnect);
                    assertEquals(socketOfPort.get(from).intValue(), owner,
                            "the measured hash must predict the socket that accepts the connection from port " + from
                                    + ": " + socketOfPort);
                    Loopback.Collector collector = new Loopback.Collector();
                    stream.setListener(collector);
                    stream.setAutoRead(true);
                    int received = echoRoundTrip(stream, collector, 0, "round " + round + " before the rebind");

                    int port = rebindToCalibratedPort(relay, socketOfPort, owner, round * 3 + 1);
                    int receiver = socketOfPort.get(port);
                    System.out.println("round " + round + ": the connection lives on socket " + owner
                            + " (its packets arrive from port " + from + "); after the rebind they arrive from port "
                            + port + ", which the kernel's 4-tuple hash gives to socket " + receiver);
                    received = echoRoundTrip(stream, collector, received, "round " + round + " after the rebind");
                    assertArrayEquals(afterConnect, server.acceptedConnectionsPerSocket(),
                            "the rebind must reach the existing connection, not create one: "
                                    + Arrays.toString(server.acceptedConnectionsPerSocket()));
                    pairs.put("owner " + owner, receiver);
                    proven++;
                    stream.close();
                }
            }

            assertEquals(REBINDS, proven, "every round rebound onto a socket that did not own the connection");
            int[] dropped = server.droppedForwardsPerSocket();
            assertEquals(SOCKETS, dropped.length, "one forward-drop counter per socket");
            assertEquals(0, sum(dropped),
                    "every rebound datagram was forwarded to the owning socket, not dropped: "
                            + Arrays.toString(dropped));
            System.out.println("NAT rebind over " + SOCKETS + " sockets: " + proven + " connections each accepted on"
                    + " the socket their first port predicted and kept echoing after a rebind onto another socket,"
                    + " owner -> receiving socket per round " + pairs + ". Kernel source-port hash: " + socketOfPort);
        }
    }

    /**
     * Picks source ports and measures, for each, the socket the kernel's SO_REUSEPORT hash gives its
     * traffic to. The hash is a function of the 4-tuple only (both ends on loopback, one listener
     * address), so it stays valid for the rebind that later sends a connection's packets from that
     * port. Ports outside the ephemeral range are used, so the kernel does not hand them out in
     * between; a busy one is skipped.
     */
    private static Map<Integer, Integer> calibrateSourcePorts(QuicServer server, TransportConfig config)
            throws Exception {
        Map<Integer, Integer> socketOfPort = new LinkedHashMap<>();
        int port = 20_000 + (int) (System.nanoTime() % 8_000L);
        for (int tries = 0; socketOfPort.size() < CALIBRATIONS && tries < 200; tries++, port++) {
            try {
                socketOfPort.put(port, socketForSourcePort(server, config, port));
            } catch (BindException busy) {
                // something else holds this port; the next one will do
            }
        }
        assertEquals(CALIBRATIONS, socketOfPort.size(),
                "could not calibrate " + CALIBRATIONS + " source ports: " + socketOfPort);
        return socketOfPort;
    }

    /**
     * The socket the kernel's hash gives {@code sourcePort}'s traffic to: a QUIC connection is
     * opened through a relay whose socket facing the listener is bound to exactly that port, and
     * the socket whose accepted-connection count grows is the one the kernel picked for it.
     */
    private static int socketForSourcePort(QuicServer server, TransportConfig config, int sourcePort)
            throws Exception {
        int[] before = server.acceptedConnectionsPerSocket();
        try (RebindRelay probe = new RebindRelay(server.localAddress(), sourcePort)) {
            assertEquals(sourcePort, probe.upstreamPort(), "the probe must speak from the measured port");
            QuicByteStream stream = QuicClient.connect(probe.address(), identity.fingerprint(), config)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stream.close();
        }
        return theOnlySocketThatGained(before, server.acceptedConnectionsPerSocket());
    }

    /**
     * Moves the relay onto the first calibrated port that is free and whose measured socket is not
     * {@code owner} ({@code -1}: any), starting {@code rotation} entries into the calibrated ports
     * so the rounds do not all pick the same one, and returns it. A port something else took
     * meanwhile is skipped; the relay keeps working from its previous port if none is left.
     */
    private static int rebindToCalibratedPort(RebindRelay relay, Map<Integer, Integer> socketOfPort, int owner,
            int rotation) throws SocketException {
        List<Integer> ports = new ArrayList<>(socketOfPort.keySet());
        for (int i = 0; i < ports.size(); i++) {
            int port = ports.get((i + rotation) % ports.size());
            if (socketOfPort.get(port) == owner) {
                continue;
            }
            try {
                relay.rebind(port);
                assertEquals(port, relay.upstreamPort());
                return port;
            } catch (BindException taken) {
                // moved on since the calibration; the next candidate will do
            }
        }
        throw new AssertionError("no free calibrated source port maps to a socket other than " + owner + ": "
                + socketOfPort);
    }

    /** The one socket whose accepted-connection count grew by one from {@code before} to {@code after}. */
    private static int theOnlySocketThatGained(int[] before, int[] after) {
        int gained = -1;
        for (int i = 0; i < after.length; i++) {
            int delta = after[i] - before[i];
            assertTrue(delta == 0 || delta == 1,
                    "socket " + i + " accepted " + delta + " connections: " + Arrays.toString(before) + " -> "
                            + Arrays.toString(after));
            if (delta == 1) {
                assertTrue(gained < 0, "two sockets accepted a connection: " + Arrays.toString(before) + " -> "
                        + Arrays.toString(after));
                gained = i;
            }
        }
        assertTrue(gained >= 0, "no socket accepted the connection: " + Arrays.toString(before) + " -> "
                + Arrays.toString(after));
        return gained;
    }

    /**
     * Writes {@code text} and requires its echo: the collector must hold {@code received} bytes plus
     * the text within {@link #ECHO_TIMEOUT_SECONDS}, with the text as the tail. Returns the new
     * total, so the next round trip awaits the bytes after it.
     */
    private static int echoRoundTrip(QuicByteStream stream, Loopback.Collector collector, int received, String text)
            throws InterruptedException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        long start = System.nanoTime();
        stream.write(ByteBuffer.wrap(payload));
        stream.flush();
        int expected = received + payload.length;
        byte[] all = collector.await(expected, ECHO_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(expected, all.length, "\"" + text + "\" was not echoed within " + ECHO_TIMEOUT_SECONDS
                + " s: " + (all.length - received) + " of " + payload.length + " bytes came back in " + millis + " ms");
        assertArrayEquals(payload, Arrays.copyOfRange(all, received, expected));
        System.out.println("\"" + text + "\" echoed in " + millis + " ms");
        return expected;
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

    /**
     * A UDP relay that stands in for a NAT. The client talks to one socket, which never changes;
     * the socket facing the listener can be moved to a new local port mid-connection, which is
     * exactly what the listener sees when a NAT rebinds: the peer's address changes while both
     * directions keep flowing. A datagram in flight through the switch can be lost, because the
     * socket it was read from is gone; QUIC retransmits it.
     */
    private static final class RebindRelay implements AutoCloseable {
        private final InetSocketAddress server;
        /** Faces the client and never changes, so the client never sees the rebind. */
        private final DatagramSocket facing;
        /** Faces the listener; rebound to a new local port by {@link #rebind(int)}. */
        private volatile DatagramSocket upstream;
        /** The client's address, learned from its first datagram. */
        private volatile InetSocketAddress client;

        RebindRelay(InetSocketAddress server, int upstreamPort) throws SocketException {
            this.server = server;
            this.facing = new DatagramSocket(loopback(0));
            this.upstream = new DatagramSocket(loopback(upstreamPort));
            startUpstreamPump();
            Thread toServer = new Thread(this::pumpToServer, "reuse-port-relay-to-server");
            toServer.setDaemon(true);
            toServer.start();
        }

        InetSocketAddress address() {
            return (InetSocketAddress) facing.getLocalSocketAddress();
        }

        /** The local port the listener currently sees this relay at. */
        int upstreamPort() {
            return upstream.getLocalPort();
        }

        /**
         * Moves the socket facing the listener to {@code port}, or to any free port when 0, which
         * is never the one it had. Datagrams written from now on go out from the new port; the
         * first one carrying data is what the listener reads as the peer-address change.
         */
        void rebind(int port) throws SocketException {
            synchronized (this) {
                DatagramSocket previous = upstream;
                DatagramSocket next = null;
                for (int attempt = 0; attempt < 8 && next == null; attempt++) {
                    DatagramSocket candidate = new DatagramSocket(loopback(port));
                    if (port != 0 || candidate.getLocalPort() != previous.getLocalPort()) {
                        next = candidate;
                    } else {
                        candidate.close();
                    }
                }
                if (next == null) {
                    throw new SocketException("the kernel kept handing back the same local port");
                }
                upstream = next;
                startUpstreamPump();
                previous.close();
            }
        }

        /** Reads the client's datagrams and sends them on from the current upstream socket. */
        private void pumpToServer() {
            byte[] buffer = new byte[65536];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    facing.receive(packet);
                } catch (java.io.IOException closed) {
                    return;
                }
                client = (InetSocketAddress) packet.getSocketAddress();
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                synchronized (this) {
                    try {
                        upstream.send(new DatagramPacket(data, data.length, server));
                    } catch (java.io.IOException ignored) {
                        // the socket was just rebound; QUIC retransmits
                    }
                }
            }
        }

        /** Reads the listener's datagrams off one upstream socket and sends them to the client. */
        private void startUpstreamPump() {
            DatagramSocket reads = upstream;
            Thread pump = new Thread(() -> pumpFromServer(reads), "reuse-port-relay-from-server");
            pump.setDaemon(true);
            pump.start();
        }

        private void pumpFromServer(DatagramSocket reads) {
            byte[] buffer = new byte[65536];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    reads.receive(packet);
                } catch (java.io.IOException closed) {
                    return;
                }
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                synchronized (this) {
                    if (reads != upstream) {
                        continue; // rebound meanwhile: this socket's reading ends
                    }
                    InetSocketAddress to = client;
                    if (to == null) {
                        continue; // nothing from the client yet
                    }
                    try {
                        facing.send(new DatagramPacket(data, data.length, to));
                    } catch (java.io.IOException ignored) {
                        // closing
                    }
                }
            }
        }

        private static InetSocketAddress loopback(int port) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }

        @Override
        public void close() {
            facing.close();
            upstream.close();
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
