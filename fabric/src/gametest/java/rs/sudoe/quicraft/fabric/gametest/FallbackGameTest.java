// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * Fallback with a real client (docs/protocol.md §5, §6): an advertisement whose UDP port is
 * dead, and one with the wrong fingerprint. Each join still succeeds over TCP, the server gets
 * the fallback report, and the failure cache then skips QUIC.
 */
public class FallbackGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        QuicraftClient.get().setMode(Mode.AUTO);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            Advertisement real = server.computeOnServer(s -> ServerQuic.advertisement());
            check(real != null, "the server advertises QUIC");
            int port = server.computeOnServer(s -> s.getPort());

            // UDP to a port where nothing listens.
            int dead = GameTests.freePort();
            joinOverTcpWith(context, server, port, Advertisement.v1(dead, real.fingerprint()), "dead UDP port");

            // The right port, but another server's certificate.
            Advertisement wrongFingerprint;
            try {
                wrongFingerprint = Advertisement.v1(real.port(), ServerIdentity.generate().fingerprint());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            joinOverTcpWith(context, server, port, wrongFingerprint, "fingerprint mismatch");
        }
    }

    private static void joinOverTcpWith(ClientGameTestContext context, TestDedicatedServerContext server, int port,
            Advertisement fake, String what) {
        record(port, fake);
        int reportsBefore = server.computeOnServer(s -> ServerQuic.fallbackReportsReceived());
        long start = System.nanoTime();
        try (var connection = server.connect()) {
            GameTests.waitForChunks(connection);
            check(!GameTests.connectedOverQuic(context), what + ": joined over TCP");
            context.waitTicks(10);
            int reports = server.computeOnServer(s -> ServerQuic.fallbackReportsReceived());
            check(reports == reportsBefore + 1, what + ": the server got the fallback report (" + reportsBefore
                    + " -> " + reports + ")");
        }
        QuicraftFabricTestLog.info(what + ": TCP join took " + (System.nanoTime() - start) / 1_000_000 + " ms");
        // Backing off now: the next join goes straight to vanilla TCP.
        check(QuicraftClient.get().connector().plan(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                Mode.AUTO) == rs.sudoe.quicraft.core.connect.ClientConnector.Plan.VANILLA_TCP
                || QuicraftClient.get().connector().plan(new InetSocketAddress("::1", port), Mode.AUTO)
                        == rs.sudoe.quicraft.core.connect.ClientConnector.Plan.VANILLA_TCP,
                what + ": the failure cache backs off");
    }

    /** Plants the advertisement for both loopback addresses "localhost" may resolve to. */
    private static void record(int port, Advertisement ad) {
        QuicraftClient.get().connector().advertisements()
                .record(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), Optional.of(ad));
        QuicraftClient.get().connector().advertisements().record(new InetSocketAddress("::1", port), Optional.of(ad));
    }
}
