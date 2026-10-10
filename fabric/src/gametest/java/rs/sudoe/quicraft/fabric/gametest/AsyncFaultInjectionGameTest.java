// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;

/**
 * Fault injection inside the client's asynchronous connect path, after the connect screen got
 * its future: each stage throwing on its own must still end in a TCP join (CLAUDE.md: a QUICraft
 * bug never stops a player from joining over TCP).
 */
public class AsyncFaultInjectionGameTest implements FabricClientGameTest {
    private static final String[][] STAGES = {
        {"status query"},             // no advertisement learned: TCP through the connector
        {"quic connect"},             // QUIC attempt fails: TCP wins the race
        {"connect outcome"},          // the winner can't be used: vanilla's own connect
        {"quic connect", "connect tcp winner"}, // TCP won, then installing it fails: vanilla
    };

    @Override
    public void runTest(ClientGameTestContext context) {
        QuicraftClient client = QuicraftClient.get();
        client.setMode(Mode.AUTO);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            int port = server.computeOnServer(s -> s.getPort());
            for (String[] stage : STAGES) {
                // Fresh state each time: nothing cached, nothing backed off.
                for (InetSocketAddress key : new InetSocketAddress[] {
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        new InetSocketAddress("::1", port)}) {
                    client.connector().advertisements().record(key, Optional.empty());
                }
                client.connector().forgetFailures();
                Hooks.setFaultInjection(stage);
                try (var connection = server.connect()) {
                    GameTests.waitForChunks(connection);
                    check(!GameTests.connectedOverQuic(context), Arrays.toString(stage) + ": joined over TCP");
                } finally {
                    Hooks.setFaultInjection(false);
                }
                QuicraftFabricTestLog.info(Arrays.toString(stage) + " throwing: joined over TCP");
            }
            client.connector().forgetFailures();
        } finally {
            Hooks.setFaultInjection(false);
        }
    }
}
