// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import com.mojang.blaze3d.platform.InputConstants;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * A modded client joins a modded dedicated server over QUIC, plays a scripted session without
 * desync, shows the transport in F3, and the server list ping still works and caches the
 * advertisement. Then a second join uses the cached advertisement (no status query).
 */
public class QuicJoinGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        QuicraftClient.get().setMode(Mode.AUTO);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            Advertisement ad = server.computeOnServer(s -> ServerQuic.advertisement());
            check(ad != null, "the server advertises QUIC");
            int port = server.computeOnServer(s -> s.getPort());
            check(ad.port() == port, "QUIC on the game port number: " + ad.port() + " vs " + port);

            // Direct connect, nothing cached: status query, then QUIC.
            try (var connection = server.connect()) {
                GameTests.waitForChunks(connection);
                check(GameTests.connectedOverQuic(context), "joined over QUIC");
                List<String> lines = GameTests.debugServerLines(context);
                check(lines.contains("QUICraft: QUIC (auto)"), "F3 shows the transport: " + lines);
                PlaySession.run(context, server);
                // After the session, so the join toasts are gone: for people to look at.
                context.getInput().pressKey(InputConstants.KEY_F3);
                context.waitTicks(5);
                context.takeScreenshot("quicraft-quic-f3");
                context.getInput().pressKey(InputConstants.KEY_F3);
            }

            // The server list ping works and refreshes the advertisement cache.
            check(GameTests.serverListPing(context, GameTests.address(server)), "server list ping");
            boolean cached = QuicraftClient.get().connector().advertisements()
                    .fresh(new InetSocketAddress(InetAddress.getLoopbackAddress(), port)).isPresent()
                    || QuicraftClient.get().connector().advertisements()
                            .fresh(new InetSocketAddress("::1", port)).isPresent();
            check(cached, "the ping cached the advertisement");

            try (var connection = server.connect()) {
                GameTests.waitForChunks(connection);
                check(GameTests.connectedOverQuic(context), "rejoined over QUIC");
            }
        }
    }
}
