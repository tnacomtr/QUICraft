// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;

/**
 * quic-only (debugging): joins over QUIC when QUIC works, and when it doesn't, ends on the
 * disconnected screen with QUIC's error instead of falling back, and never hangs.
 */
public class QuicOnlyGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        QuicraftClient client = QuicraftClient.get();
        client.setMode(Mode.QUIC_ONLY);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            try (var connection = server.connect()) {
                GameTests.waitForChunks(connection);
                check(GameTests.connectedOverQuic(context), "quic-only joined over QUIC");
            }

            int port = server.computeOnServer(s -> s.getPort());
            Advertisement real = server.computeOnServer(s -> rs.sudoe.quicraft.fabric.server.ServerQuic.advertisement());
            Advertisement dead = Advertisement.v1(GameTests.freePort(), real.fingerprint());
            for (InetSocketAddress key : new InetSocketAddress[] {
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), new InetSocketAddress("::1", port)}) {
                client.connector().advertisements().record(key, Optional.of(dead));
            }
            String address = GameTests.address(server);
            context.runOnClient(c -> ConnectScreen.startConnecting(new TitleScreen(), c,
                    ServerAddress.parseString(address), new ServerData("quic-only", address, ServerData.Type.OTHER),
                    false, null));
            // QUIC's own connect timeout is 10 s; the screen must come back after it.
            context.waitFor(c -> c.screen instanceof DisconnectedScreen, 20 * 20);
            check(!GameTests.connectedOverQuic(context), "no connection");
            context.setScreen(TitleScreen::new);
        } finally {
            client.setMode(Mode.AUTO);
        }
    }
}
