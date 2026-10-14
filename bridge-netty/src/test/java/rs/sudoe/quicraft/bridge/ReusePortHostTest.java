// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * The game's own UDP sockets for a hosted QUIC listener (docs/protocol.md §11): one per worker
 * loop with SO_REUSEPORT, so a node's QUIC work spreads over several event loops, and the
 * fallbacks that keep the listener working with one socket wherever several are impossible.
 */
// NioEventLoopGroup: deprecated in 4.2, the only choice in 4.1. "try": closed by try only.
@SuppressWarnings({"deprecation", "try"})
class ReusePortHostTest {
    private static ServerIdentity identity;
    private EventLoopGroup workers;

    @BeforeAll
    static void setUpClass() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    @BeforeEach
    void setUp() {
        workers = new NioEventLoopGroup(2);
    }

    @AfterEach
    void tearDown() {
        workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /** Several sockets on one port need Linux and a JDK with SO_REUSEPORT (docs/protocol.md §11). */
    private static boolean reusePortHere() {
        return System.getProperty("os.name", "").startsWith("Linux")
                && GameHost.reusePortOption(NioDatagramChannel.class) != null;
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    @Test
    void socketCountReadsAutoAndTheRange() {
        assertEquals(QuicServer.defaultSocketCount(), GameHost.socketCount("auto"));
        assertEquals(QuicServer.defaultSocketCount(), GameHost.socketCount("AUTO"));
        assertEquals(QuicServer.defaultSocketCount(), GameHost.socketCount(""));
        assertEquals(QuicServer.defaultSocketCount(), GameHost.socketCount(null));
        assertEquals(1, GameHost.socketCount("1"));
        assertEquals(64, GameHost.socketCount("64"));
        assertEquals(8, GameHost.socketCount(" 8 "));
        for (String invalid : new String[] {"0", "65", "-3", "many", "2.5"}) {
            assertEquals(QuicServer.defaultSocketCount(), GameHost.socketCount(invalid),
                    () -> "udp-sockets=" + invalid);
        }
    }

    @Test
    void reusePortOptionFindsTheNioOption() {
        ChannelOption<Boolean> option = GameHost.reusePortOption(NioDatagramChannel.class);
        assumeTrue(System.getProperty("os.name", "").startsWith("Linux"), "SO_REUSEPORT is Linux only");
        assertNotNull(option, "the JDK has StandardSocketOptions.SO_REUSEPORT");
        NioDatagramChannel probe = new NioDatagramChannel();
        try {
            assertTrue(probe.config().setOption(option, Boolean.TRUE),
                    "the option applies to a NIO datagram channel");
        } finally {
            probe.unsafe().closeForcibly();
        }
    }

    @Test
    void reusePortOptionIsNullWhereTheTransportHasNone() {
        // A datagram channel class whose package holds no matching ChannelOption: io.netty.channel
        // .socket.ChannelOption does not exist. Such a transport gets one socket, never a failure.
        assertNull(GameHost.reusePortOption(DatagramChannel.class));
        assertNull(GameHost.reusePortOption(io.netty.channel.local.LocalChannel.class));
    }

    @Test
    void oneSocketPerLoopWithReusePortServesQuicOnItsSocketsLoop() throws Exception {
        assumeTrue(reusePortHere(), "several sockets need Linux and SO_REUSEPORT");
        try (GameHost.HostedSockets bound = GameHost.bindHosted(workers, 2, loopback(), NioDatagramChannel::new)) {
            assertEquals(2, bound.size(), "one UDP socket per distinct worker loop");
            assertNotSame(bound.loops().get(0), bound.loops().get(1), "a distinct loop per socket");
            assertEquals(bound.hostSockets().get(0).localAddress().getPort(),
                    bound.hostSockets().get(1).localAddress().getPort(), "both sockets on the same port");
            AtomicReference<EventLoop> accepting = new AtomicReference<>();
            AtomicReference<Thread> acceptThread = new AtomicReference<>();
            AtomicReference<Thread> readThread = new AtomicReference<>();
            InetSocketAddress address = bound.hostSockets().get(0).localAddress();
            try (QuicServer server = QuicServer.bind(bound.hostLoops(), bound.hostSockets(), identity,
                    TransportConfig.DEFAULT, stream -> {
                        acceptThread.set(Thread.currentThread());
                        accepting.set(bound.acceptingLoop());
                        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
                        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                readThread.compareAndSet(null, Thread.currentThread());
                                ctx.write(msg);
                            }

                            @Override
                            public void channelReadComplete(ChannelHandlerContext ctx) {
                                ctx.flush();
                            }
                        });
                        accepting.get().register(channel);
                    })) {
                QuicByteStream client = QuicClient.connect(address, identity.fingerprint(), TransportConfig.DEFAULT)
                        .get(5, TimeUnit.SECONDS);
                client.write(ByteBuffer.wrap(new byte[] {1}));
                client.flush();
                assertTrue(waitFor(acceptThread), "the acceptor never ran on a game loop");
                EventLoop loop = accepting.get();
                assertTrue(bound.loops().contains(loop), "the stream was accepted on one of the sockets' loops");
                assertSame(workers, loop.parent(), "and on the platform's own worker group");
                assertTrue(loop.inEventLoop(acceptThread.get()),
                        "acceptingLoop() names the accepting socket's loop, where the accept runs");
                assertSame(acceptThread.get(), readThread.get(), "QUIC, the socket and the pipeline share a thread");
                client.close();
            }
        }
    }

    @Test
    void oneSocketWhenTheGroupHasOneLoop() throws Exception {
        EventLoopGroup single = new NioEventLoopGroup(1);
        try (GameHost.HostedSockets bound = GameHost.bindHosted(single, 8, loopback(), NioDatagramChannel::new)) {
            assertEquals(1, bound.size(), "one socket per loop the group has");
        } finally {
            single.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void oneWorkingSocketWhenALaterSocketCannotBeBound() throws Exception {
        assumeTrue(reusePortHere(), "several sockets need Linux and SO_REUSEPORT");
        AtomicInteger created = new AtomicInteger();
        ChannelFactory<DatagramChannel> datagrams = () -> {
            if (created.incrementAndGet() > 1) {
                throw new IllegalStateException("no more sockets");
            }
            return new NioDatagramChannel();
        };
        try (GameHost.HostedSockets bound = GameHost.bindHosted(workers, 2, loopback(), datagrams)) {
            assertEquals(1, bound.size(), "the first socket stays bound when a later one fails");
            assertEquals(2, created.get(), "the bind stopped at the failed second socket instead of retrying");
            assertAddressIsUsable(bound);
        }
    }

    @Test
    void aPortHeldWithoutReusePortStillFailsTheBind() throws Exception {
        assumeTrue(System.getProperty("os.name", "").startsWith("Linux"), "SO_REUSEPORT is Linux only");
        // SO_REUSEPORT shares a port only with SO_REUSEPORT. A socket that holds it without the
        // option (the query listener, another server) still blocks QUIC, so the query collision
        // stays a config decision (docs/protocol.md §2) and a failed bind stays a failure.
        java.net.DatagramSocket holder = new java.net.DatagramSocket(loopback());
        InetSocketAddress address = (InetSocketAddress) holder.getLocalSocketAddress();
        try {
            assertThrows(Exception.class, () -> GameHost.bindHosted(workers, 1, address, NioDatagramChannel::new));
            assertThrows(Exception.class, () -> GameHost.bindHosted(workers, 2, address, NioDatagramChannel::new));
        } finally {
            holder.close();
        }
        // The failed binds left nothing behind: the port is free again.
        try (GameHost.HostedSockets bound = GameHost.bindHosted(workers, 2, address, NioDatagramChannel::new)) {
            assertTrue(bound.size() >= 1, "the port is free after the failed binds");
        }
    }

    /** A QUIC client reaches the listener over these sockets, so the address really is served. */
    private static void assertAddressIsUsable(GameHost.HostedSockets bound) throws Exception {
        InetSocketAddress address = bound.hostSockets().get(0).localAddress();
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = QuicServer.bind(bound.hostLoops(), bound.hostSockets(), identity,
                TransportConfig.DEFAULT, accepted::complete)) {
            QuicByteStream client = QuicClient.connect(address, identity.fingerprint(), TransportConfig.DEFAULT)
                    .get(5, TimeUnit.SECONDS);
            client.write(ByteBuffer.wrap(new byte[] {1}));
            client.flush();
            assertNotNull(accepted.get(5, TimeUnit.SECONDS), "the listener served a QUIC connection");
            client.close();
        }
    }

    private static boolean waitFor(AtomicReference<?> reference) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (reference.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return reference.get() != null;
    }
}
