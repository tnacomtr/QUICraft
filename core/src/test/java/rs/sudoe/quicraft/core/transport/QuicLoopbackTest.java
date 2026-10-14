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
    void echoesBytesWhenEveryConnectionGetsARetry() throws Exception {
        TransportConfig always = TransportConfig.builder().retryMode(TransportConfig.RetryMode.ALWAYS).build();
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(Loopback.anyLocal(), identity, always, accepted)) {
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            byte[] hello = "hello after a retry".getBytes(StandardCharsets.UTF_8);
            stream.write(ByteBuffer.wrap(hello));
            stream.flush();
            assertArrayEquals(hello, collector.await(hello.length, 5, TimeUnit.SECONDS));
            assertTrue(server.retriesSent() >= 1, "the connection went through a Retry");
            stream.close();
        }
    }

    @Test
    void aConnectionOverThePerIpLimitGetsNoConnectionAndTheSlotFreesOnClose() throws Exception {
        TransportConfig onePerIp = TransportConfig.builder().maxOpenPerIp(1).build();
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(Loopback.anyLocal(), identity, onePerIp, accepted)) {
            QuicByteStream first = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            accepted.get(5, TimeUnit.SECONDS);
            long retriesBefore = server.retriesSent();

            // Over the limit: a Retry at most, then silence. No handshake completes, so the client
            // can't mistake the attempt for a won race; it falls back to TCP after its head start.
            long start = System.nanoTime();
            CompletableFuture<QuicByteStream> second = QuicClient.connect(server.localAddress(),
                    identity.fingerprint(), TransportConfig.DEFAULT);
            assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(2, TimeUnit.SECONDS));
            System.out.printf("over the limit: no connection after %d ms, Retry packets %d%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), server.retriesSent() - retriesBefore);
            assertTrue(server.retriesSent() > retriesBefore, "the refused attempt is answered with a Retry");
            second.cancel(true);

            first.close();
            long closed = System.nanoTime();
            long deadline = closed + TimeUnit.SECONDS.toNanos(10);
            QuicByteStream third = null;
            while (third == null && System.nanoTime() < deadline) {
                CompletableFuture<QuicByteStream> attempt = QuicClient.connect(server.localAddress(),
                        identity.fingerprint(), TransportConfig.DEFAULT);
                try {
                    // Longer than the client's first PTO (~1 s): a retransmitted Initial can still
                    // be admitted, and a cancelled half-open attempt would hold the slot.
                    third = attempt.get(3, TimeUnit.SECONDS);
                } catch (ExecutionException | java.util.concurrent.TimeoutException stillHeld) {
                    attempt.cancel(true); // else a late success would hold the slot
                    Thread.sleep(50);
                }
            }
            assertTrue(third != null, "the lease must be released when the first connection closes");
            System.out.printf("a new connection succeeded %d ms after the first closed%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closed));
            third.close();
        }
    }

    @Test
    void aHandshakeThatNeverCompletesIsClosedAndReleasesItsSlot() throws Exception {
        TransportConfig onePerIp = TransportConfig.builder().maxOpenPerIp(1).build();
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        QuicServer.handshakeMillis = 2_000;
        try (QuicServer server = Loopback.echoServer(Loopback.anyLocal(), identity, onePerIp, accepted);
                DelayRelay relay = new DelayRelay(server.localAddress(), 0)) {
            // A half-open handshake: the server's flight never reaches the client, and once the
            // server has the connection nothing more comes from the client, not even its close.
            relay.dropFromServer(true);
            long created = System.nanoTime();
            CompletableFuture<QuicByteStream> halfOpen = QuicClient.connect(relay.address(), identity.fingerprint(),
                    TransportConfig.DEFAULT);
            Thread.sleep(200);
            relay.dropFromClient(true);
            halfOpen.cancel(true);

            CompletableFuture<QuicByteStream> blocked = QuicClient.connect(server.localAddress(),
                    identity.fingerprint(), TransportConfig.DEFAULT);
            assertThrows(java.util.concurrent.TimeoutException.class, () -> blocked.get(1, TimeUnit.SECONDS),
                    "the half-open handshake holds the only slot");
            blocked.cancel(true);

            QuicByteStream next = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (next == null && System.nanoTime() < deadline) {
                CompletableFuture<QuicByteStream> attempt = QuicClient.connect(server.localAddress(),
                        identity.fingerprint(), TransportConfig.DEFAULT);
                try {
                    next = attempt.get(3, TimeUnit.SECONDS); // longer than the client's first PTO
                } catch (ExecutionException | java.util.concurrent.TimeoutException stillHeld) {
                    attempt.cancel(true);
                }
            }
            long freedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - created);
            System.out.printf("slot free again %d ms after the half-open connection started (deadline %d ms)%n",
                    freedMillis, QuicServer.handshakeMillis);
            assertTrue(next != null && freedMillis < QuicServer.handshakeMillis + 2_000,
                    "the handshake deadline closes the half-open connection and frees its slot, took " + freedMillis);
            next.close();
        } finally {
            QuicServer.handshakeMillis = 10_000;
        }
    }

    /** Writes {@code text} and waits for the echo, {@code before} bytes having come back already. */
    private static void echo(QuicByteStream stream, Loopback.Collector collector, int before, String text)
            throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        stream.write(ByteBuffer.wrap(bytes));
        stream.flush();
        byte[] all = collector.await(before + bytes.length, 5, TimeUnit.SECONDS);
        assertArrayEquals(bytes, java.util.Arrays.copyOfRange(all, before, all.length));
    }

    @Test
    void aCompletedHandshakeOutlivesTheHandshakeDeadline() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        QuicServer.handshakeMillis = 300;
        try (QuicServer server = Loopback.echoServer(identity, accepted)) {
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);
            echo(stream, collector, 0, "before the deadline");
            Thread.sleep(800);
            echo(stream, collector, "before the deadline".length(), "after the deadline");
            assertEquals(1, collector.closed.getCount(), "the connection is still open");
            assertEquals(0, server.pendingHandshakes(), "its lease was confirmed");
            stream.close();
        } finally {
            QuicServer.handshakeMillis = 10_000;
        }
    }

    @Test
    void aFailedHandshakeDeadlineStillConfirmsAndReleasesTheLease() throws Exception {
        TransportConfig onePerIp = TransportConfig.builder().maxOpenPerIp(1).build();
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        rs.sudoe.quicraft.core.Faults.set(rs.sudoe.quicraft.core.Faults.HANDSHAKE_DEADLINE);
        try (QuicServer server = Loopback.echoServer(Loopback.anyLocal(), identity, onePerIp, accepted)) {
            QuicByteStream first = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            first.setListener(collector);
            first.setAutoRead(true);
            echo(first, collector, 0, "no deadline, still admitted");
            assertEquals(0, server.pendingHandshakes(), "the handshake still confirms the lease");
            first.close();

            QuicByteStream next = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (next == null && System.nanoTime() < deadline) {
                CompletableFuture<QuicByteStream> attempt = QuicClient.connect(server.localAddress(),
                        identity.fingerprint(), TransportConfig.DEFAULT);
                try {
                    next = attempt.get(3, TimeUnit.SECONDS); // longer than the client's first PTO
                } catch (ExecutionException | java.util.concurrent.TimeoutException stillHeld) {
                    attempt.cancel(true);
                }
            }
            assertTrue(next != null, "closing still releases the lease");
            next.close();
        } finally {
            rs.sudoe.quicraft.core.Faults.set();
        }
    }

    @Test
    void echoesBytesWithACustomInitialCongestionWindow() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = Loopback.echoServer(identity, accepted)) {
            TransportConfig config = TransportConfig.builder().initialCongestionWindowPackets(32).build();
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    config).get(5, TimeUnit.SECONDS);
            Loopback.Collector collector = new Loopback.Collector();
            stream.setListener(collector);
            stream.setAutoRead(true);

            byte[] hello = "hello with a bigger initial window".getBytes(StandardCharsets.UTF_8);
            stream.write(ByteBuffer.wrap(hello));
            stream.flush();
            assertArrayEquals(hello, collector.await(hello.length, 5, TimeUnit.SECONDS));
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
    void aPeerThatDoesNotReadMakesTheStreamUnwritableUntilItDoes() throws Exception {
        CompletableFuture<QuicByteStream> accepted = new CompletableFuture<>();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                accepted::complete)) {
            QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                    TransportConfig.DEFAULT).get(5, TimeUnit.SECONDS);
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> writableAgain =
                    new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
            stream.setListener(new QuicByteStream.Listener() {
                @Override
                public void onData(ByteBuffer data) {}

                @Override
                public void onWritabilityChanged(boolean writable) {
                    if (writable) {
                        writableAgain.get().countDown();
                    }
                }

                @Override
                public void onClosed(Throwable cause) {}
            });
            // Keep writing whenever the stream is writable. A single moment of unwritability proves
            // nothing: quiche accepts more as its congestion window grows, so the in-flight bytes
            // drain again (and the stream is writable again) until the peer's flow-control window is
            // full. "written" also counts bytes still queued in the writer (up to HIGH_WATER), so the
            // test judges saturation by what the transport accepted ("taken"): written - inFlight, since a
            // write completes (and leaves inFlight) once quiche took it. Quiche never accepts past
            // the peer's stream window, which also covers this stream's few preamble bytes. A pause
            // counts only when the taken bytes are within the slack of that window and neither
            // taken nor in flight changed for the whole plateau while the stream was unwritable.
            // The slack is one chunk (a partly accepted write stays in flight) plus room for the
            // preamble. Earlier stalls keep retrying until the deadline.
            byte[] chunk = new byte[16384];
            NettyQuicByteStream raw = ((PreambleStream) stream).raw;
            long window = TransportConfig.DEFAULT.initialMaxStreamData;
            long slack = chunk.length + 4096;
            long written = 0;
            long limit = 64L << 20;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            long lastTaken = -1;
            long lastInFlight = -1;
            long steadySince = System.nanoTime();
            long taken = 0;
            long inFlight = 0;
            boolean saturated = false;
            while (written < limit && System.nanoTime() < deadline) {
                if (stream.isWritable()) {
                    stream.write(ByteBuffer.wrap(chunk));
                    stream.flush();
                    written += chunk.length;
                    lastTaken = -1;
                } else {
                    // a stale inFlight can only understate taken, which is the safe side.
                    inFlight = raw.inFlightForTest();
                    taken = written - inFlight;
                    long now = System.nanoTime();
                    if (taken != lastTaken || inFlight != lastInFlight) {
                        lastTaken = taken;
                        lastInFlight = inFlight;
                        steadySince = now;
                    } else if (taken >= window - slack && now - steadySince >= TimeUnit.MILLISECONDS.toNanos(1000)) {
                        saturated = true;
                        break;
                    }
                    Thread.sleep(5);
                }
            }
            System.out.println("aPeerThatDoesNotRead: at saturation written=" + written + " inFlight=" + inFlight
                    + " accepted=" + taken);
            assertTrue(saturated, "never saturated, wrote " + written + ", taken " + taken + " of the " + window
                    + " byte peer window");
            assertFalse(stream.isWritable(), "a stalled peer must make the stream unwritable");
            assertTrue(taken <= window, "quiche accepted past the peer's window: " + taken);
            assertTrue(inFlight <= 2 * NettyQuicByteStream.HIGH_WATER + chunk.length,
                    "in flight must stay bounded, was " + inFlight);

            // Arm a fresh observation now that saturation is established, then let the peer read.
            java.util.concurrent.CountDownLatch recovered = new java.util.concurrent.CountDownLatch(1);
            writableAgain.set(recovered);
            java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();
            QuicByteStream serverSide = accepted.get(5, TimeUnit.SECONDS);
            serverSide.setListener(new QuicByteStream.Listener() {
                @Override
                public void onData(ByteBuffer data) {
                    received.addAndGet(data.remaining());
                }

                @Override
                public void onWritabilityChanged(boolean writable) {}

                @Override
                public void onClosed(Throwable cause) {}
            });
            long receivedBeforeReads = received.get();
            serverSide.setAutoRead(true);
            assertTrue(recovered.await(10, TimeUnit.SECONDS), "writable again once the peer reads");
            long writableDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!stream.isWritable() && System.nanoTime() < writableDeadline) {
                Thread.sleep(5);
            }
            assertTrue(stream.isWritable(), "writable after the peer drained the stream");
            // Everything written before the stall must arrive once the peer reads.
            long readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (received.get() < written && System.nanoTime() < readDeadline) {
                Thread.sleep(10);
            }
            assertTrue(received.get() > receivedBeforeReads, "the server received nothing after reads resumed");
            assertEquals(written, received.get(), "every written byte must reach the server once it reads");
            stream.close();
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
