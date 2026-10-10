// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;

/** The tcp-only setting joins an advertising server over TCP and says so in F3. */
public class TcpOnlyGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        QuicraftClient.get().setMode(Mode.TCP_ONLY);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            try (var connection = server.connect()) {
                GameTests.waitForChunks(connection);
                check(!GameTests.connectedOverQuic(context), "tcp-only joined over TCP");
                check(GameTests.debugServerLines(context).contains("QUICraft: TCP (tcp-only)"), "F3 says TCP");
            }
        } finally {
            QuicraftClient.get().setMode(Mode.AUTO);
        }
    }
}
