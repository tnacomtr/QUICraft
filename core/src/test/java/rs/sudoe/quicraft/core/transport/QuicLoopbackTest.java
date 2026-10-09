// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager.FingerprintMismatchException;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

class QuicLoopbackTest {
    private static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        identity = ServerIdentity.generate();
    }

    @Test
    void echoesBytesAndReportsThePeersUdpAddress() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(identity, accepted)) {
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);

            byte[] hello = "hello over quic".getBytes(StandardCharsets.UTF_8);
            stream.write(ByteBuffer.wrap(hello));
            stream.flush();
            assertArrayEquals(hello, collector.await(hello.length, 5, TimeUnit.SECONDS));

            QuicByteStream serverSide = accepted.get(5, TimeUnit.SECONDS);
            InetSocketAddress peer = serverSide.remoteAddress();
            assertEquals(InetAddress.getLoopbackAddress(), peer.getAddress());
            assertEquals(stream.localAddress().getPort(), peer.getPort());
            stream.close();
        }
    }

    @Test
    void movesFourMebibytesEachWayWithoutStalling() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(identity, accepted)) {
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);

            byte[] payload = new byte[4 << 20];
            new Random(42).nextBytes(payload);
            for (int off = 0; off < payload.length; off += 16384) {
                stream.write(ByteBuffer.wrap(payload, off, Math.min(16384, payload.length - off)));
            }
            stream.flush();
            assertArrayEquals(payload, collector.await(payload.length, 20, TimeUnit.SECONDS));
            stream.close();
        }
    }

    @Test
    void wrongFingerprintFailsFastWithAMismatch() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(identity, accepted)) {
            Fingerprint other = ServerIdentity.generate().fingerprint();
            long start = System.nanoTime();
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> QuicClient.connect(server.localAddress(), other, TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(hasCause(e, FingerprintMismatchException.class), () -> "unexpected failure: " + e.getCause());
            assertTrue(millis < 1000, "mismatch took " + millis + " ms");
            assertFalse(accepted.isDone(), "server must never accept a stream from a rejected handshake");
        }
    }

    @Test
    void alpnMismatchFailsTheHandshake() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        EventLoopGroup group = Codecs.newGroup("test-alpn", 1);
        try (QuicServer server = Loopback.echoServer(identity, accepted)) {
            ChannelHandler codec = Codecs.apply(new QuicClientCodecBuilder(), TransportConfig.DEFAULT, false)
                    .sslContext(QuicSslContextBuilder.forClient()
                            .trustManager(new rs.sudoe.quicraft.core.tls.FingerprintTrustManager(identity.fingerprint()))
                            .endpointIdentificationAlgorithm(null)
                            .applicationProtocols("something-else/1").build())
                    .build();
            Channel udp = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec)
                    .bind(0).sync().channel();
            // Fails, but only at the connect timeout: see QuicServer.Connection (Netty 4.2.19 limit).
            io.netty.util.concurrent.Future<QuicChannel> f = QuicChannel.newBootstrap(udp)
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                    .handler(new ChannelInboundHandlerAdapter()).remoteAddress(server.localAddress()).connect()
                    .await();
            assertFalse(f.isSuccess(), "handshake with a foreign ALPN must fail");
            assertFalse(accepted.isDone(), "server must never accept a stream without a common ALPN");
            udp.close().sync();
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Documents why every limit in docs/protocol.md §9 is set: with quiche's defaults (no
     * initial_max_* parameters), the client can't even open a stream.
     */
    @Test
    void quicheDefaultsAllowNoStreams() throws Exception {
        EventLoopGroup group = Codecs.newGroup("test-defaults", 1);
        try {
            ChannelHandler serverCodec = new QuicServerCodecBuilder()
                    .sslContext(QuicSslContextBuilder.forServer(identity.privateKey(), null, identity.certificate())
                            .applicationProtocols(Protocol.ALPN).build())
                    .tokenHandler(NoRetryTokenHandler.INSTANCE)
                    .handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .build();
            Channel serverUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(serverCodec)
                    .bind(Loopback.anyLocal()).sync().channel();
            ChannelHandler clientCodec = new QuicClientCodecBuilder()
                    .sslContext(QuicSslContextBuilder.forClient()
                            .trustManager(new rs.sudoe.quicraft.core.tls.FingerprintTrustManager(identity.fingerprint()))
                            .endpointIdentificationAlgorithm(null)
                            .applicationProtocols(Protocol.ALPN).build())
                    .build();
            Channel clientUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(clientCodec)
                    .bind(0).sync().channel();
            QuicChannel connection = QuicChannel.newBootstrap(clientUdp).handler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(serverUdp.localAddress()).connect().get(5, TimeUnit.SECONDS);
            io.netty.util.concurrent.Future<QuicStreamChannel> stream = connection
                    .createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter()).await();
            assertFalse(stream.isSuccess(), "with quiche's default limits no bidirectional stream can open");
            connection.close(true, 0, Unpooled.EMPTY_BUFFER).sync();
            clientUdp.close().sync();
            serverUdp.close().sync();
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void closingTheClientClosesTheServerStream() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        Loopback.Collector serverCollector = new Loopback.Collector();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT, s -> {
            s.setListener(serverCollector);
            s.setAutoRead(true);
            accepted.complete(s);
        })) {
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            stream.write(ByteBuffer.wrap(new byte[] {1}));
            stream.flush();
            accepted.get(5, TimeUnit.SECONDS);
            stream.close();
            assertTrue(serverCollector.closed.await(5, TimeUnit.SECONDS), "server stream did not see the close");
        }
    }

    @Test
    void cancellingAConnectAttemptLeavesNothingOpen() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(identity, accepted)) {
            CompletableFuture<QuicByteStream> attempt = QuicClient.connect(server.localAddress(),
                    identity.fingerprint(), TransportConfig.DEFAULT);
            attempt.cancel(false);
            assertTrue(attempt.isCancelled());
            // A stream that would have completed after the cancel must be closed, not leaked.
            Thread.sleep(500);
            if (accepted.isDone()) {
                QuicByteStream serverSide = accepted.get();
                Loopback.Collector c = new Loopback.Collector();
                serverSide.setListener(c);
                assertTrue(c.closed.await(5, TimeUnit.SECONDS) || !serverSide.isOpen(),
                        "a cancelled attempt's connection must be closed");
            }
        }
    }

    @Test
    void insecureTokenHandlerIsNeverUsedInMainSources() throws IOException {
        Path main = Paths.get("src/main");
        assertTrue(Files.isDirectory(main), "run from the core project directory");
        AtomicBoolean found = new AtomicBoolean();
        try (Stream<Path> files = Files.walk(main)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                try {
                    if (new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains("InsecureQuicTokenHandler")) {
                        found.set(true);
                        fail("InsecureQuicTokenHandler referenced in " + f);
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        assertFalse(found.get());
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
            for (Throwable s : c.getSuppressed()) {
                if (hasCause(s, type)) {
                    return true;
                }
            }
        }
        return false;
    }

    @AfterAll
    static void tearDown() {}
}
