// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.connect.ClientConnector.Plan;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/** The client's connect path with a fake platform: decisions, status query, race, caches. */
class ClientConnectorTest {
    private static ScheduledExecutorService scheduler;
    private static Advertisement ad;
    private static final InetSocketAddress SERVER = new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);

    @BeforeAll
    static void setUp() throws Exception {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        ad = Advertisement.v1(25570, ServerIdentity.generate().fingerprint());
    }

    @AfterAll
    static void tearDown() {
        scheduler.shutdownNow();
    }

    /** A platform whose answers the test sets. */
    static class FakePlatform implements ClientConnector.Platform<String> {
        CompletableFuture<Optional<Advertisement>> status = CompletableFuture.completedFuture(Optional.of(ad));
        CompletableFuture<QuicByteStream> quic = CompletableFuture.completedFuture(new FakeStream());
        CompletableFuture<String> tcp = CompletableFuture.completedFuture("tcp");
        final AtomicInteger statusQueries = new AtomicInteger();
        final AtomicInteger quicAttempts = new AtomicInteger();
        final AtomicInteger tcpAttempts = new AtomicInteger();
        final List<String> tcpClosed = new CopyOnWriteArrayList<>();
        InetSocketAddress quicTarget;

        @Override
        public CompletableFuture<Optional<Advertisement>> queryStatus(InetSocketAddress server) {
            statusQueries.incrementAndGet();
            return status;
        }

        @Override
        public CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target, Fingerprint fingerprint) {
            quicAttempts.incrementAndGet();
            quicTarget = target;
            return quic;
        }

        @Override
        public ConnectionRace.Tcp<String> tcp() {
            return new ConnectionRace.Tcp<String>() {
                @Override
                public CompletableFuture<String> connect() {
                    tcpAttempts.incrementAndGet();
                    return tcp;
                }

                @Override
                public void close(String connection) {
                    tcpClosed.add(connection);
                }
            };
        }

        @Override
        public ScheduledExecutorService scheduler() {
            return scheduler;
        }
    }

    static final class FakeStream implements QuicByteStream {
        volatile boolean closed;

        @Override public InetSocketAddress remoteAddress() { return SERVER; }
        @Override public InetSocketAddress localAddress() { return SERVER; }
        @Override public void setListener(Listener listener) {}
        @Override public void write(ByteBuffer data) {}
        @Override public void flush() {}
        @Override public boolean isWritable() { return true; }
        @Override public void setAutoRead(boolean autoRead) {}
        @Override public void read() {}
        @Override public boolean isOpen() { return !closed; }
        @Override public void close() { closed = true; }
        @Override public CompletableFuture<java.util.Map<String, Long>> connectionStats() {
            return CompletableFuture.completedFuture(java.util.Collections.<String, Long>emptyMap());
        }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public boolean inEventLoop() { return true; }
    }

    private static ClientConnector connector(AdvertisementCache ads, FailureCache failures) {
        return new ClientConnector(ads, failures, () -> true, 300);
    }

    @Test
    void planIsVanillaTcpWheneverQuicCannotPlayAPart() {
        AdvertisementCache ads = new AdvertisementCache();
        FailureCache failures = FailureCache.load(null);
        assertEquals(Plan.VANILLA_TCP, connector(ads, failures).plan(SERVER, Mode.TCP_ONLY));
        assertEquals(Plan.VANILLA_TCP, new ClientConnector(ads, failures, () -> false, 300).plan(SERVER, Mode.AUTO),
                "no native");
        assertEquals(Plan.CONNECT, connector(ads, failures).plan(SERVER, Mode.AUTO), "no advertisement: query");
        ads.record(SERVER, Optional.of(ad));
        assertEquals(Plan.CONNECT, connector(ads, failures).plan(SERVER, Mode.AUTO));
        failures.recordFailure(SERVER.getAddress(), ad.port(), ad.fingerprint());
        assertEquals(Plan.VANILLA_TCP, connector(ads, failures).plan(SERVER, Mode.AUTO), "backing off");
        assertEquals(Plan.CONNECT, connector(ads, failures).plan(SERVER, Mode.QUIC_ONLY));
    }

    @Test
    void directConnectQueriesStatusThenUsesQuicAtTheAdvertisedPort() throws Exception {
        AdvertisementCache ads = new AdvertisementCache();
        FakePlatform p = new FakePlatform();
        ClientConnector.Outcome<String> o = connector(ads, FailureCache.load(null)).connect(SERVER, Mode.AUTO, p)
                .get(5, TimeUnit.SECONDS);
        assertTrue(o.quic() != null);
        assertNull(o.fallback());
        assertEquals(1, p.statusQueries.get());
        assertEquals(new InetSocketAddress(SERVER.getAddress(), 25570), p.quicTarget);
        assertEquals(Optional.of(ad), ads.fresh(SERVER), "the query's advertisement is cached");
        assertEquals(0, p.tcpAttempts.get());
    }

    @Test
    void aCachedAdvertisementSkipsTheStatusQuery() throws Exception {
        AdvertisementCache ads = new AdvertisementCache();
        ads.record(SERVER, Optional.of(ad));
        FakePlatform p = new FakePlatform();
        connector(ads, FailureCache.load(null)).connect(SERVER, Mode.AUTO, p).get(5, TimeUnit.SECONDS);
        assertEquals(0, p.statusQueries.get());
        assertEquals(1, p.quicAttempts.get());
    }

    @Test
    void noAdvertisementMeansTcpWithoutAnyQuicAttempt() throws Exception {
        FakePlatform p = new FakePlatform();
        p.status = CompletableFuture.completedFuture(Optional.<Advertisement>empty());
        ClientConnector.Outcome<String> o = connector(new AdvertisementCache(), FailureCache.load(null))
                .connect(SERVER, Mode.AUTO, p).get(5, TimeUnit.SECONDS);
        assertEquals("tcp", o.tcp());
        assertNull(o.fallback(), "QUIC wasn't tried: nothing to report");
        assertEquals(0, p.quicAttempts.get());
    }

    @Test
    void aFailedOrSilentStatusQueryMeansTcp() throws Exception {
        FakePlatform failing = new FakePlatform();
        failing.status = new CompletableFuture<>();
        failing.status.completeExceptionally(new ConnectException("refused"));
        assertEquals("tcp", connector(new AdvertisementCache(), FailureCache.load(null))
                .connect(SERVER, Mode.AUTO, failing).get(5, TimeUnit.SECONDS).tcp());

        FakePlatform silent = new FakePlatform();
        silent.status = new CompletableFuture<>();
        long start = System.nanoTime();
        assertEquals("tcp", connector(new AdvertisementCache(), FailureCache.load(null))
                .connect(SERVER, Mode.AUTO, silent).get(5, TimeUnit.SECONDS).tcp());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 250, "waited for the timeout");
        assertTrue(silent.status.isCancelled(), "the timed-out query is abandoned");
    }

    @Test
    void aQuicFailureFallsBackRecordsTheFailureAndReportsTheReason() throws Exception {
        AdvertisementCache ads = new AdvertisementCache();
        ads.record(SERVER, Optional.of(ad));
        FailureCache failures = FailureCache.load(null);
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<>();
        p.quic.completeExceptionally(new javax.net.ssl.SSLHandshakeException("bad certificate"));
        ClientConnector c = connector(ads, failures);
        ClientConnector.Outcome<String> o = c.connect(SERVER, Mode.AUTO, p).get(5, TimeUnit.SECONDS);
        assertEquals("tcp", o.tcp());
        assertEquals(FallbackReason.HANDSHAKE_FAILURE, o.fallback());
        assertEquals(Plan.VANILLA_TCP, c.plan(SERVER, Mode.AUTO), "the failure cache backs off now");
    }

    @Test
    void aQuicSuccessClearsTheFailureCache() throws Exception {
        AdvertisementCache ads = new AdvertisementCache();
        ads.record(SERVER, Optional.of(ad));
        FailureCache failures = FailureCache.load(null);
        failures.recordFailure(SERVER.getAddress(), ad.port(), ad.fingerprint());
        // QUIC_ONLY ignores the backoff; a success must clear it for AUTO too.
        FakePlatform p = new FakePlatform();
        ClientConnector c = connector(ads, failures);
        assertTrue(c.connect(SERVER, Mode.AUTO, p).get(5, TimeUnit.SECONDS).tcp() != null, "backing off: TCP");
        failures.recordSuccess(SERVER.getAddress(), ad.port(), ad.fingerprint());
        assertTrue(c.connect(SERVER, Mode.AUTO, p).get(5, TimeUnit.SECONDS).quic() != null);
    }

    @Test
    void quicOnlyNeverFallsBack() throws Exception {
        FakePlatform none = new FakePlatform();
        none.status = CompletableFuture.completedFuture(Optional.<Advertisement>empty());
        ExecutionException e = assertThrows(ExecutionException.class, () -> connector(new AdvertisementCache(),
                FailureCache.load(null)).connect(SERVER, Mode.QUIC_ONLY, none).get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof IOException);
        assertEquals(0, none.tcpAttempts.get());

        FakePlatform failing = new FakePlatform();
        failing.quic = new CompletableFuture<>();
        IOException quicError = new IOException("handshake");
        failing.quic.completeExceptionally(quicError);
        ExecutionException e2 = assertThrows(ExecutionException.class, () -> connector(new AdvertisementCache(),
                FailureCache.load(null)).connect(SERVER, Mode.QUIC_ONLY, failing).get(5, TimeUnit.SECONDS));
        assertSame(quicError, e2.getCause());
        assertEquals(0, failing.tcpAttempts.get());
    }

    @Test
    void cancellingDuringTheRaceClosesWhateverConnectsLater() throws Exception {
        AdvertisementCache ads = new AdvertisementCache();
        ads.record(SERVER, Optional.of(ad));
        FakePlatform p = new FakePlatform();
        p.quic = new CompletableFuture<>();
        p.tcp = new CompletableFuture<>();
        CompletableFuture<ClientConnector.Outcome<String>> attempt = connector(ads, FailureCache.load(null))
                .connect(SERVER, Mode.AUTO, p);
        Thread.sleep(ClientConnector.HEAD_START_MILLIS + 100); // TCP has started too
        assertEquals(1, p.tcpAttempts.get());
        assertTrue(attempt.cancel(false));
        assertTrue(p.quic.isCancelled(), "the QUIC attempt is cancelled");
        p.tcp.complete("late");
        assertEquals(java.util.Collections.singletonList("late"), p.tcpClosed);
    }

    @Test
    void tcpFailuresAreMarkedSoThePlatformDoesNotRetry() throws Exception {
        ConnectException refused = new ConnectException("Connection refused");
        FakePlatform none = new FakePlatform();
        none.status = CompletableFuture.completedFuture(Optional.<Advertisement>empty());
        none.tcp = new CompletableFuture<>();
        none.tcp.completeExceptionally(refused);
        ExecutionException e = assertThrows(ExecutionException.class, () -> connector(new AdvertisementCache(),
                FailureCache.load(null)).connect(SERVER, Mode.AUTO, none).get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof ClientConnector.TcpConnectException);
        assertSame(refused, e.getCause().getCause());
        assertEquals("Connection refused", e.getCause().getMessage());

        AdvertisementCache ads = new AdvertisementCache();
        ads.record(SERVER, Optional.of(ad));
        FakePlatform both = new FakePlatform();
        both.quic = new CompletableFuture<>();
        both.quic.completeExceptionally(new IOException("quic down"));
        both.tcp = new CompletableFuture<>();
        both.tcp.completeExceptionally(refused);
        ExecutionException e2 = assertThrows(ExecutionException.class, () -> connector(ads, FailureCache.load(null))
                .connect(SERVER, Mode.AUTO, both).get(5, TimeUnit.SECONDS));
        assertTrue(e2.getCause() instanceof ClientConnector.TcpConnectException);
        assertSame(refused, e2.getCause().getCause());
    }

    @Test
    void aPlatformBugIsNotATcpFailure() throws Exception {
        AdvertisementCache ads = new AdvertisementCache();
        ads.record(SERVER, Optional.of(ad));
        FakePlatform broken = new FakePlatform() {
            @Override
            public ConnectionRace.Tcp<String> tcp() {
                throw new IllegalStateException("bug");
            }
        };
        ExecutionException e = assertThrows(ExecutionException.class, () -> connector(ads, FailureCache.load(null))
                .connect(SERVER, Mode.AUTO, broken).get(5, TimeUnit.SECONDS));
        assertFalse(e.getCause() instanceof ClientConnector.TcpConnectException, "the platform falls back to vanilla");
    }
}
