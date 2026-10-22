// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.FailureCache;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
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
