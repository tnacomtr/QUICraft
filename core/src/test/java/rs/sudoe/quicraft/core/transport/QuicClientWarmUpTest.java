// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.core.connect.ConnectDecision;
import rs.sudoe.quicraft.core.connect.FailureCache;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/** The warm-up only moves initialisation off the join: it never throws and changes nothing a dial does. */
class QuicClientWarmUpTest {

    @TempDir
    Path dir;

    @Test
    void warmUpNeverThrowsAndIsRepeatable() {
        assertDoesNotThrow(QuicClient::warmUp);
        assertDoesNotThrow(QuicClient::warmUp);
    }

    @Test
    void warmUpLeavesNoExtraNonDaemonThreadOrOpenUdpSocket() throws Exception {
        assumeTrue(QuicSupport.isAvailable(), "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs the patched native (12.2, 12.3, 13.13)");
        Path fds = Paths.get("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "needs /proc/self/fd");
        QuicClient.resetWarmUpForTests();
        Thread.sleep(300); // an earlier test's socket may still be closing
        Set<String> threadsBefore = nonDaemonThreads();
        Set<String> socketsBefore = openSockets(fds);
        QuicClient.warmUp();
        // Netty's global executor thread is non-daemon and exits after about 1 s idle: wait it out
        // (bounded), then only threads that are still there and were not there before count.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Set<String> added = newThreads(threadsBefore);
        while (!added.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            added = newThreads(threadsBefore);
        }
        assertTrue(added.isEmpty(), "non-daemon threads left by warm-up: " + added);
        Thread.sleep(500); // the client's UDP socket closes 50 ms after its connection
        Set<String> socketsAdded = openSockets(fds);
        socketsAdded.removeAll(socketsBefore); // sockets that closed meanwhile are not a leak
        assertTrue(socketsAdded.isEmpty(), "UDP/TCP sockets left open by warm-up: " + socketsAdded);
    }

    @Test
    void aDialAfterTheWarmUpStillConnectsAndCachesNothingNew() throws Exception {
        assumeTrue(QuicSupport.isAvailable(), "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs the patched native (12.2, 12.3, 13.13)");
        QuicClient.resetWarmUpForTests();
        QuicClient.warmUp();
        FailureCache failures = FailureCache.load(dir.resolve("quic-failures.properties"));
        AdvertisementCache adverts = new AdvertisementCache();
        ServerIdentity identity = ServerIdentity.generate();
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                stream -> { }, protocol -> V3ConnectionTest.TABLE)) {
            InetSocketAddress target = server.localAddress();
            assertTrue(failures.allows(target.getAddress(), target.getPort(), identity.fingerprint()));
            assertFalse(adverts.fresh(target).isPresent());
            V3Connection connection = QuicClient.connectV3(target, identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertNotNull(connection);
            connection.close();
            assertTrue(failures.allows(target.getAddress(), target.getPort(), identity.fingerprint()));
            assertFalse(adverts.fresh(target).isPresent());
        } finally {
            QuicClient.forgetSessions();
        }
    }

    @Test
    void warmUpLeavesTheSeededEarlyCacheUntouched() throws Exception {
        assumeWarmable();
        QuicClient.resetWarmUpForTests();
        TransportConfig warm = QuicClient.warmConfigForTests();
        assertFalse(warm.earlyData, "the warm listener or dial would issue or accept early data");
        assertFalse(warm.sessionResumption, "a warm handshake could read or store a session ticket");
        EarlyCache cache = EarlyCache.INSTANCE;
        List<Object> sentinel = EarlyCache.server(new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565),
                Fingerprint.of(new byte[] {0x5e, 0x4e, 0x71}), Protocol.ALPN_V3);
        cache.putToken(sentinel, new byte[] {1, 2, 3});
        int tokensBefore = cache.tokenCount();
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();

        QuicClient.warmUp();

        assertEquals(roundTripsBefore + 1, QuicClient.warmRoundTripsForTests(),
                "the warm-up's loopback round trip did not complete");
        settleEarlyCache(cache, tokensBefore);
        assertTrue(cache.hasToken(sentinel), "the warm-up removed or evicted the seeded early token");
        assertEquals(tokensBefore, cache.tokenCount(), "the warm-up stored early tokens in the shared cache");
    }

    @Test
    void warmUpPublishesNoEarlyOutcome() {
        assumeWarmable();
        QuicClient.resetWarmUpForTests();
        QuicClient.setLastEarlyOutcomeForTests("seeded by the test");
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();

        QuicClient.warmUp();

        assertEquals(roundTripsBefore + 1, QuicClient.warmRoundTripsForTests(),
                "the warm-up's loopback round trip did not complete");
        assertEquals("seeded by the test", QuicClient.lastEarlyOutcome(),
                "the warm-up published a global early-data outcome");
    }

    /**
     * The warm-up reaches neither cache: {@link QuicClient} holds no reference to them; the
     * client's wiring owns them and hands them to {@link ClientConnector}. So seed them through
     * the real production connector and assert through its public API that the instances a
     * subsequent real dial uses are untouched. The shared static state the warm-up does hold
     * (early tokens) is covered above.
     */
    @Test
    void warmUpLeavesTheProductionFailureAndAdvertisementCachesUntouched() throws Exception {
        assumeWarmable();
        QuicClient.resetWarmUpForTests();
        AdvertisementCache adverts = new AdvertisementCache();
        FailureCache failures = FailureCache.load(dir.resolve("warm-failures.properties"));
        ClientConnector connector = new ClientConnector(adverts, failures);
        InetAddress address = InetAddress.getLoopbackAddress();
        int port = 25565;
        Fingerprint fingerprint = Fingerprint.of(new byte[] {0x6a});
        InetSocketAddress server = new InetSocketAddress(address, port);
        Advertisement advertised = Advertisement.v1(port, fingerprint);
        adverts.record(server, Optional.of(advertised));
        failures.recordFailure(address, advertised.port(), advertised.fingerprint());
        assertEquals(ClientConnector.Plan.VANILLA_TCP, connector.plan(server, ConnectDecision.Mode.AUTO),
                "the seeded advertisement plus the seeded failure did not back off");
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();

        QuicClient.warmUp();

        assertEquals(roundTripsBefore + 1, QuicClient.warmRoundTripsForTests(),
                "the warm-up's loopback round trip did not complete");
        assertEquals(ClientConnector.Plan.VANILLA_TCP, connector.plan(server, ConnectDecision.Mode.AUTO),
                "the warm-up cleared the production failure cache");
        assertEquals(Optional.of(advertised), connector.advertisements().fresh(server),
                "the warm-up changed the production advertisement cache");
    }

    @Test
    void aWarmRoundTripThatCannotFinishIsBoundedAndLeavesNoSocket() throws Exception {
        assumeWarmable();
        Path fds = Paths.get("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "needs /proc/self/fd");
        QuicClient.resetWarmUpForTests();
        Thread.sleep(300); // an earlier test's socket may still be closing
        Set<String> socketsBefore = openSockets(fds);
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();
        QuicClient.setWarmTimeoutMillisForTests(100);
        QuicClient.warmTargetForTests = aClosedLoopbackPort();
        try {
            long start = System.nanoTime();
            assertDoesNotThrow(QuicClient::warmUp);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis < 2000, "a warm-up bounded to 100 ms took " + elapsedMillis + " ms");
            assertEquals(roundTripsBefore, QuicClient.warmRoundTripsForTests(),
                    "a warm-up dialed to a port that never answers still completed");
        } finally {
            QuicClient.setWarmTimeoutMillisForTests(0);
            QuicClient.warmTargetForTests = null;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Set<String> added = socketsAdded(fds, socketsBefore);
        while (!added.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
            added = socketsAdded(fds, socketsBefore);
        }
        assertTrue(added.isEmpty(), "sockets left open by a warm-up that could not finish: " + added);
    }

    @Test
    void aStalledIdentityGenerationIsBoundedAndLeavesNoNonDaemonThread() throws Exception {
        assumeWarmable();
        QuicClient.resetWarmUpForTests();
        Thread.sleep(300); // an earlier test's socket may still be closing
        Set<String> threadsBefore = nonDaemonThreads();
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();
        QuicClient.warmIdentityDelayForTests = () -> {
            try {
                Thread.sleep(5000); // identity generation outlives the warm-up's 2 s deadline
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            long start = System.nanoTime();
            assertDoesNotThrow(QuicClient::warmUp);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis < 3000,
                    "a warm-up whose identity generation stalled took " + elapsedMillis + " ms");
            assertEquals(roundTripsBefore, QuicClient.warmRoundTripsForTests(),
                    "a warm-up whose identity generation stalled still completed a round trip");
        } finally {
            QuicClient.warmIdentityDelayForTests = null;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Set<String> added = newThreads(threadsBefore);
        while (!added.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            added = newThreads(threadsBefore);
        }
        assertTrue(added.isEmpty(), "non-daemon threads left by a warm-up whose identity stalled: " + added);
    }

    @Test
    void aStalledBindIsBoundedAndItsLateListenerIsClosed() throws Exception {
        assumeWarmable();
        Path fds = Paths.get("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "needs /proc/self/fd");
        QuicClient.resetWarmUpForTests();
        Thread.sleep(300); // an earlier test's socket may still be closing
        Set<String> socketsBefore = openSockets(fds);
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();
        QuicServer.warmBindDelayMillisForTests = 3000;
        try {
            long start = System.nanoTime();
            assertDoesNotThrow(QuicClient::warmUp);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis < 3000, "a warm-up whose bind stalled took " + elapsedMillis + " ms");
            assertEquals(roundTripsBefore, QuicClient.warmRoundTripsForTests(),
                    "a warm-up whose bind stalled still completed a round trip");
        } finally {
            QuicServer.warmBindDelayMillisForTests = 0;
        }
        // The bind completes about 1 s after warmUp returned; the late listener must close then.
        awaitNoNewSockets(fds, socketsBefore, 6);
    }

    @Test
    void aStalledTlsStartupIsBoundedAndItsLateListenerIsDisposed() throws Exception {
        assumeWarmable();
        Path fds = Paths.get("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "needs /proc/self/fd");
        QuicClient.resetWarmUpForTests();
        Thread.sleep(300); // an earlier test's socket may still be closing
        Set<String> socketsBefore = openSockets(fds);
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();
        long disposalsBefore = QuicServer.warmLateDisposalsForTests();
        CountDownLatch startupReached = new CountDownLatch(1);
        AtomicBoolean startupFinished = new AtomicBoolean();
        QuicServer.warmStartupBlockForTests = () -> {
            startupReached.countDown();
            try {
                Thread.sleep(3000); // the TLS context and bind outlive the warm-up's 2 s deadline
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                startupFinished.set(true);
            }
        };
        try {
            long start = System.nanoTime();
            assertDoesNotThrow(QuicClient::warmUp);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMillis < 3000,
                    "a warm-up whose TLS startup stalled took " + elapsedMillis + " ms");
            assertTrue(startupReached.await(1, TimeUnit.SECONDS), "the warm TLS startup was never reached");
            assertEquals(roundTripsBefore, QuicClient.warmRoundTripsForTests(),
                    "a warm-up whose TLS startup stalled still completed a round trip");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (!startupFinished.get() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(startupFinished.get(), "the stalled TLS startup never finished");
            while (QuicServer.warmLateDisposalsForTests() <= disposalsBefore && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(QuicServer.warmLateDisposalsForTests() > disposalsBefore,
                    "the TLS startup that completed after the deadline was not disposed of");
        } finally {
            QuicServer.warmStartupBlockForTests = null;
        }
        awaitNoNewSockets(fds, socketsBefore, 6);
    }

    @Test
    void aLateHandshakeCompletionAfterTheDeadlineLeavesNoSocket() throws Exception {
        assumeWarmable();
        Path fds = Paths.get("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "needs /proc/self/fd");
        QuicClient.resetWarmUpForTests();
        Thread.sleep(300); // an earlier test's socket may still be closing
        Set<String> socketsBefore = openSockets(fds);
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();
        // The hook runs only once the handshake has completed and the dial is about to return; the
        // latch proves this test reached that stage instead of spending the deadline before the
        // dial. The 1500 ms deadline leaves identity and the TLS startup ample room, and the hook
        // then holds the completion well past it.
        CountDownLatch handshakeReached = new CountDownLatch(1);
        CountDownLatch releaseHandshake = new CountDownLatch(1);
        QuicClient.setWarmTimeoutMillisForTests(1500);
        QuicClient.setBeforeConnectCompletionForTests(() -> {
            handshakeReached.countDown();
            try {
                releaseHandshake.await(10, TimeUnit.SECONDS); // completes well after the deadline
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertDoesNotThrow(QuicClient::warmUp);
            assertTrue(handshakeReached.await(2, TimeUnit.SECONDS),
                    "the warm dial never reached the handshake completion; identity/startup consumed the deadline");
            assertEquals(roundTripsBefore, QuicClient.warmRoundTripsForTests(),
                    "a warm round trip that completed after the deadline was still counted");
        } finally {
            releaseHandshake.countDown();
            QuicClient.setWarmTimeoutMillisForTests(0);
            QuicClient.setBeforeConnectCompletionForTests(null);
        }
        awaitNoNewSockets(fds, socketsBefore, 6);
    }

    @Test
    void aConcurrentRealJoinSucceedsAndKeepsItsOwnEarlyOutcome() throws Exception {
        assumeWarmable();
        QuicClient.resetWarmUpForTests();
        EarlyCache cache = EarlyCache.INSTANCE;
        List<Object> sentinel = EarlyCache.server(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565),
                Fingerprint.of(new byte[] {0x7c, 0x11}), Protocol.ALPN_V3);
        cache.putToken(sentinel, new byte[] {1, 2, 3});
        QuicClient.setLastEarlyOutcomeForTests("seeded by the test");
        ServerIdentity identity = ServerIdentity.generate();
        Thread warm = new Thread(QuicClient::warmUp, "quicraft-warmup-test");
        warm.setDaemon(true);
        try (QuicServer server = QuicServer.bind(Loopback.anyLocal(), identity, TransportConfig.DEFAULT,
                stream -> { }, protocol -> V3ConnectionTest.TABLE)) {
            InetSocketAddress target = server.localAddress();
            warm.start();
            V3Connection connection = QuicClient.connectV3(target, identity.fingerprint(),
                    V3ConnectionTest.TABLE, null).get(10, TimeUnit.SECONDS);
            assertNotNull(connection, "a real dial beside the warm-up did not connect");
            List<Object> real = EarlyCache.server(target, identity.fingerprint(), Protocol.ALPN_V3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!cache.hasToken(real) && System.nanoTime() < deadline) {
                Thread.sleep(50); // the server's preamble and its /3 token arrive after connect
            }
            connection.close();
            warm.join(5000);
            assertFalse(warm.isAlive(), "the concurrent warm-up did not finish");
            assertEquals("not requested", QuicClient.lastEarlyOutcome(),
                    "the real dial's early-data outcome is not the one published");
            assertTrue(cache.hasToken(real), "the real /3 dial recorded no early token");
            assertTrue(cache.hasToken(sentinel), "the warm-up removed the seeded early token");
        } finally {
            QuicClient.forgetSessions();
            warm.join(1000);
        }
    }

    @Test
    void theWarmListenerCreatesNoServerJoinDiag() {
        assumeWarmable();
        QuicClient.resetWarmUpForTests();
        long joinDiagsBefore = QuicServer.serverJoinDiagsForTests();
        long roundTripsBefore = QuicClient.warmRoundTripsForTests();

        QuicClient.warmUp();

        assertEquals(roundTripsBefore + 1, QuicClient.warmRoundTripsForTests(),
                "the warm-up's loopback round trip did not complete");
        assertEquals(joinDiagsBefore, QuicServer.serverJoinDiagsForTests(),
                "the warm listener created a server join-diagnostics instance");
    }

    private static void assumeWarmable() {
        assumeTrue(QuicSupport.isAvailable(), "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
        assumeTrue(QuicSupport.hasMaxFlowControlWindows() && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs the patched native (12.2, 12.3, 13.13)");
    }

    /** Gives an early-token write that would arrive on the event loop a bounded moment to show. */
    private static void settleEarlyCache(EarlyCache cache, int tokensBefore) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < deadline && cache.tokenCount() == tokensBefore) {
            Thread.sleep(50);
        }
    }

    /** A loopback UDP port nothing listens on: the warm dial's handshake can never complete there. */
    private static InetSocketAddress aClosedLoopbackPort() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket socket = new DatagramSocket(0, loopback)) {
            return new InetSocketAddress(loopback, socket.getLocalPort());
        }
    }

    private static Set<String> socketsAdded(Path fds, Set<String> before) throws IOException {
        Set<String> added = openSockets(fds);
        added.removeAll(before);
        return added;
    }

    /** Waits, bounded, until the process holds no socket descriptor it did not hold before. */
    private static void awaitNoNewSockets(Path fds, Set<String> before, long timeoutSeconds)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Set<String> added = socketsAdded(fds, before);
        while (!added.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
            added = socketsAdded(fds, before);
        }
        assertTrue(added.isEmpty(), "sockets left open by the warm-up: " + added);
    }

    private static Set<String> newThreads(Set<String> before) {
        Set<String> added = nonDaemonThreads();
        added.removeAll(before);
        return added;
    }

    private static Set<String> nonDaemonThreads() {
        Set<String> names = new TreeSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isDaemon()) {
                names.add(thread.getName() + "#" + thread.getId());
            }
        }
        return names;
    }

    /** The socket descriptors this process holds, by their kernel identity ("socket:[inode]"). */
    private static Set<String> openSockets(Path fds) throws IOException {
        Set<String> sockets = new TreeSet<>();
        try (DirectoryStream<Path> links = Files.newDirectoryStream(fds)) {
            for (Path link : links) {
                try {
                    String target = Files.readSymbolicLink(link).toString();
                    if (target.startsWith("socket:")) {
                        sockets.add(target);
                    }
                } catch (IOException closedMeanwhile) {
                    // the descriptor closed between listing and reading it
                }
            }
        }
        return sockets;
    }
}
