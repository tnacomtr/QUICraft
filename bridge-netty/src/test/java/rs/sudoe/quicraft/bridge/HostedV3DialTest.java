// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.netty.channel.ReflectiveChannelFactory;
import rs.sudoe.quicraft.core.QuicSupport;
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
    private static ServerIdentity identity;
    private EventLoopGroup clientGroup;
    private EventLoop clientLoop;

    @BeforeAll
    static void setUpClass() throws Exception {
        assumeTrue(QuicSupport.isAvailable() && QuicSupport.hasMaxFlowControlWindows()
                        && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
        identity = ServerIdentity.generate();
    }

    @BeforeEach
    void setUp() {
        clientGroup = new NioEventLoopGroup(1);
        clientLoop = clientGroup.next();
    }

    @AfterEach
    void tearDown() {
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
        assertTrue(socket.closed, "the supplied hosted socket is released when synchronous validation fails");
    }

    private static final class TrackingSocket implements HostDatagramSocket {
        volatile boolean closed;

        @Override
        public InetSocketAddress localAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        }

        @Override
        public void start(Receiver receiver) {
        }

        @Override
        public void write(java.nio.ByteBuffer data, InetSocketAddress recipient) {
        }

        @Override
        public void flush() {
        }

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
