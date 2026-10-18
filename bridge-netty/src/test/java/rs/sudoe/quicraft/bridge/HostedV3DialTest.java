// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.netty.channel.ReflectiveChannelFactory;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.HostDatagramSocket;
import rs.sudoe.quicraft.core.transport.HostLoop;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.core.transport.V3Listener;

/**
 * The hosted {@code /3} dial: {@link GameHost#connectV3} puts the connection on the game's event
 * loop, so the TLS exporter (which must run on the connection's loop, 12.7) and the binding
 * session's deadlines run on the one thread the game's pipeline uses. Needs QUICraft's patched
 * native, so it is skipped on an upstream one.
 */
// NioEventLoopGroup: deprecated in 4.2, the only choice in 4.1.
@SuppressWarnings("deprecation")
class HostedV3DialTest {
    private static final int PROTOCOL = 4242;
    private static final RoutingTable TABLE = RoutingTableLoader.parse(
            "{\"schema\":1,\"protocol\":4242,\"default\":\"control\",\"routes\":[]}");
    /**
     * A valid schema-2 table for the session test (13.7): no routes, so every packet is
     * CONTROL/global, with the limits every state must declare.
     */
    private static final RoutingTable TABLE_V2 = RoutingTableLoader.parse(tableV2Json());
    private static ServerIdentity identity;
    private EventLoopGroup serverGroup;
    private EventLoop serverLoop;
    private EventLoopGroup clientGroup;
    private EventLoop clientLoop;

    private static String tableV2Json() {
        String limit = "{\"maxServerboundRecord\":32768,\"maxServerboundRealtimeRecord\":510,"
                + "\"maxDecodedFootprint\":1048576,\"ordinaryReadAllowance\":5619,"
                + "\"decodeFootprintBound\":\"primitive.raw_copies.v1\"}";
        StringBuilder limits = new StringBuilder();
        for (String state : new String[] {"handshaking", "status", "login", "configuration", "play"}) {
            if (limits.length() != 0) {
                limits.append(',');
            }
            limits.append('"').append(state).append("\":").append(limit);
        }
        return "{\"schema\":2,\"protocol\":4242,\"default\":\"global\",\"extractors\":{},"
                + "\"serverboundLimits\":{" + limits + "},\"routes\":[]}";
    }

    @BeforeAll
    static void setUpClass() throws Exception {
        assumeTrue(QuicSupport.isAvailable() && QuicSupport.hasMaxFlowControlWindows()
                        && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
        identity = ServerIdentity.generate();
    }

    @BeforeEach
    void setUp() {
        serverGroup = new NioEventLoopGroup(1);
        serverLoop = serverGroup.next();
        clientGroup = new NioEventLoopGroup(1);
        clientLoop = clientGroup.next();
    }

    @AfterEach
    void tearDown() {
        serverGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void theDialedConnectionRunsOnTheGameLoopAndExportsThereToo() throws Exception {
        CompletableFuture<V3Connection> accepted = new CompletableFuture<>();
        try (QuicServer server = QuicServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                identity, TransportConfig.DEFAULT, stream -> accepted.complete((V3Connection) stream),
                new V3Listener() {
                    @Override
                    public RoutingTable tableFor(int protocolNumber) {
                        return protocolNumber == PROTOCOL ? TABLE : null;
                    }
                })) {
            V3Connection client = GameHost.connectV3(clientLoop,
                    new ReflectiveChannelFactory<>(NioDatagramChannel.class), server.localAddress(),
                    identity.fingerprint(), TABLE, TransportConfig.DEFAULT, null, false)
                    .get(10, TimeUnit.SECONDS);
            assertNotNull(accepted.get(10, TimeUnit.SECONDS));
            assertEquals("quicraft/3", client.alpn());

            // The exporter must be answered on the connection's loop: that is the game's loop here.
            AtomicReference<Thread> onLoop = new AtomicReference<>();
            AtomicReference<Object> exported = new AtomicReference<>();
            clientLoop.submit(() -> {
                onLoop.set(Thread.currentThread());
                exported.set(client.exporterValue());
            }).get(10, TimeUnit.SECONDS);
            assertTrue(clientLoop.inEventLoop(onLoop.get()), "the exporter ran on the game's loop");
            assertEquals(32, ((byte[]) exported.get()).length);
            client.close();
        }
    }

    /**
     * The N+2 session's periodic tick must run on the hosted loop (13.12). Activation schedules it
     * through {@code HostedEventLoop.scheduleAtFixedRate}; before that primitive existed the
     * schedule threw, the connection closed {@code 0x2}, and a real {@code /3} join could never
     * leave the configuration stage.
     */
    @Test
    void aSessionActivatedOnHostedLoopsRunsItsTicker() throws Exception {
        DatagramChannel serverUdp = udp(serverLoop);
        CompletableFuture<V3Connection> accepted = new CompletableFuture<>();
        try (QuicServer server = QuicServer.bind(GameHost.loop(serverLoop), GameHost.socket(serverUdp), identity,
                TransportConfig.DEFAULT, stream -> accepted.complete((V3Connection) stream),
                new V3Listener() {
                    @Override
                    public RoutingTable tableFor(int protocolNumber) {
                        return protocolNumber == PROTOCOL ? TABLE_V2 : null;
                    }
                })) {
            V3Connection client = GameHost.connectV3(clientLoop,
                    new ReflectiveChannelFactory<>(NioDatagramChannel.class), server.localAddress(),
                    identity.fingerprint(), TABLE_V2, TransportConfig.DEFAULT, null, false)
                    .get(10, TimeUnit.SECONDS);
            V3Connection serverSide = accepted.get(10, TimeUnit.SECONDS);
            await(serverSide::handshakeComplete, 10_000);
            await(() -> negotiated(serverSide), 10_000);
            await(() -> negotiated(client), 10_000);
            StubGame clientGame = new StubGame();
            StubGame serverGame = new StubGame();
            client.activate(clientGame, false, null);
            serverSide.activate(serverGame, PROTOCOL, false, null);
            await(() -> client.session() != null && serverSide.session() != null, 5_000);
            // Several ticks of the session's periodic task: it used to throw here and close 0x2.
            Thread.sleep(300);
            assertTrue(client.isOpen(), "the client /3 connection stayed open: " + clientGame);
            assertTrue(serverSide.isOpen(), "the server /3 connection stayed open: " + serverGame);
            assertFalse(clientGame.closed, "the client game saw a close");
            assertFalse(serverGame.closed, "the server game saw a close");
            client.end();
            serverSide.end();
        }
    }

    private static DatagramChannel udp(EventLoop loop) throws InterruptedException {
        DatagramChannel channel = new NioDatagramChannel();
        loop.register(channel).sync();
        channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).sync();
        return channel;
    }

    private static boolean negotiated(V3Connection connection) {
        try {
            connection.sessionContext();
            return true;
        } catch (IllegalStateException notYet) {
            return false;
        }
    }

    private static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition not met within " + millis + " ms");
            }
            Thread.sleep(10);
        }
    }

    /** Records a close; no record is expected in this test. */
    private static final class StubGame implements LaneGame {
        volatile boolean closed;

        @Override
        public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle, Reservation reservation) {
            throw new UnsupportedOperationException("no records in this test");
        }

        @Override
        public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            throw new UnsupportedOperationException("no records in this test");
        }

        @Override
        public void onWritabilityChanged(boolean writable) { }

        @Override
        public void onClosed(int code, boolean byPeer, Throwable cause) {
            closed = true;
        }

        @Override
        public String toString() {
            return closed ? "closed" : "open";
        }
    }

    @Test
    void synchronousHostedV3SetupFailureClosesTheSuppliedSocket() {
        TrackingSocket socket = new TrackingSocket();
        assertThrows(NullPointerException.class, () -> QuicClient.connectV3(new HostLoop() {
            @Override
            public boolean inEventLoop(Thread thread) {
                return false;
            }

            @Override
            public void execute(Runnable task) {
            }

            @Override
            public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
                return () -> true;
            }
        }, socket, new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565), identity.fingerprint(), null,
                TransportConfig.DEFAULT, null, false));
        assertEquals(1, socket.closes.get(), "the supplied hosted socket is closed exactly once when synchronous validation fails");
    }

    @Test
    void cancelledHostedV3DialClosesTheSuppliedSocketOnce() throws Exception {
        // Nothing is written to this socket, so the handshake stays pending until the cancel.
        TrackingSocket socket = new TrackingSocket();
        CompletableFuture<V3Connection> dial = QuicClient.connectV3(GameHost.loop(clientLoop), socket,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565), identity.fingerprint(), TABLE,
                TransportConfig.DEFAULT, null, false);
        // Registration on the loop hands the socket to the channel; the cancel then closes both ways.
        socket.started.get(10, TimeUnit.SECONDS);
        assertTrue(dial.cancel(true));
        drainClientLoop();
        assertEquals(1, socket.closes.get(), "a cancelled hosted /3 dial closes its socket exactly once");
    }

    @Test
    void failedHostedV3DialClosesTheSuppliedSocketOnce() throws Exception {
        try (QuicServer server = QuicServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                identity, TransportConfig.DEFAULT, stream -> { },
                new V3Listener() {
                    @Override
                    public RoutingTable tableFor(int protocolNumber) {
                        return protocolNumber == PROTOCOL ? TABLE : null;
                    }
                })) {
            DatagramChannel channel = new NioDatagramChannel();
            clientLoop.register(channel).sync();
            channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).sync();
            TrackingSocket socket = new TrackingSocket(GameHost.socket(channel));
            // A different identity's fingerprint: the server's certificate fails the pin.
            CompletableFuture<V3Connection> dial = QuicClient.connectV3(GameHost.loop(clientLoop), socket,
                    server.localAddress(), ServerIdentity.generate().fingerprint(), TABLE,
                    TransportConfig.DEFAULT, null, false);
            assertThrows(ExecutionException.class, () -> dial.get(10, TimeUnit.SECONDS));
            drainClientLoop();
            assertEquals(1, socket.closes.get(), "a failed hosted /3 dial closes its socket exactly once");
        }
    }

    /** Returns once the game loop has run everything queued before this call, twice over. */
    private void drainClientLoop() throws Exception {
        clientLoop.submit(() -> { }).get(10, TimeUnit.SECONDS);
        clientLoop.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    /** Counts every close that reaches it; optionally forwards to a real socket. */
    private static final class TrackingSocket implements HostDatagramSocket {
        final AtomicInteger closes = new AtomicInteger();
        final CompletableFuture<Void> started = new CompletableFuture<>();
        private final HostDatagramSocket delegate;

        TrackingSocket() {
            this(null);
        }

        TrackingSocket(HostDatagramSocket delegate) {
            this.delegate = delegate;
        }

        @Override
        public InetSocketAddress localAddress() {
            return delegate != null ? delegate.localAddress()
                    : new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        }

        @Override
        public void start(Receiver receiver) {
            started.complete(null);
            if (delegate != null) {
                delegate.start(receiver);
            }
        }

        @Override
        public void write(ByteBuffer data, InetSocketAddress recipient) {
            if (delegate != null) {
                delegate.write(data, recipient);
            }
        }

        @Override
        public void flush() {
            if (delegate != null) {
                delegate.flush();
            }
        }

        @Override
        public boolean isWritable() {
            return delegate == null || delegate.isWritable();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (delegate != null) {
                delegate.close();
            }
        }
    }
}
