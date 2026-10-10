// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * CLAUDE.md fault injection: with every QUICraft hook throwing, on the client and the server, the
 * server list ping and a TCP join with a full play session still work.
 */
public class FaultInjectionGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        QuicraftClient.get().setMode(Mode.AUTO);
        Hooks.setFaultInjection(true);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            check(server.computeOnServer(s -> ServerQuic.advertisement()) == null, "nothing advertised");
            check(GameTests.serverListPing(context, GameTests.address(server)), "server list ping");
            try (var connection = server.connect()) {
                GameTests.waitForChunks(connection);
                check(!GameTests.connectedOverQuic(context), "joined over TCP");
                PlaySession.run(context, server, connection);
            }
        } finally {
            Hooks.setFaultInjection(false);
        }
    }
}
