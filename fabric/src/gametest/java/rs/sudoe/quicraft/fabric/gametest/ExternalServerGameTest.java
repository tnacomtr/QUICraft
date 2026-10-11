// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.client.Screens;
import rs.sudoe.quicraft.fabric.client.Transports;

/**
 * The real client against a server outside the test (opt-in, {@code -Pquicraft.externalServer=
 * host:port}), e.g. the testkit's Velocity: testkit/run-real-client.sh. A first join over QUIC,
 * a few seconds in the world, a disconnect, then a rejoin that must go over QUIC with 0-RTT
 * (docs/protocol.md §8) and stay in the world. Whether the server acted on the 0-RTT data is in
 * its own log (Velocity: {@code -Dquicraft.logStats}, earlyReleases).
 */
public class ExternalServerGameTest implements FabricClientGameTest {
    private static final int IN_WORLD_TICKS = 20 * 5;

    @Override
    public void runTest(ClientGameTestContext context) {
        String address = System.getProperty("quicraft.gametest.externalServer", "");
        if (address.isEmpty()) {
            return;
        }
        Hooks.setFaultInjection(false);
        Faults.set();
        QuicClient.forgetSessions();
        QuicraftClient client = QuicraftClient.get();
        client.setMode(Mode.AUTO);
        client.connector().forgetFailures();

        join(context, address, false, "external first join");
        join(context, address, true, "external rejoin");
        context.setScreen(TitleScreen::new); // the harness expects every test to end there
    }

    private static void join(ClientGameTestContext context, String address, boolean zeroRtt, String what) {
        long start = System.nanoTime();
        context.runOnClient(c -> ConnectScreen.startConnecting(new TitleScreen(), c,
                ServerAddress.parseString(address), new ServerData("external", address, ServerData.Type.OTHER),
                false, null));
        for (int t = 0; !context.computeOnClient(c -> c.level != null && c.player != null && Screens.current(c) == null); t++) {
            if (t > 20 * 60) {
                String state = context.computeOnClient(c -> "screen " + (Screens.current(c) == null ? "none"
                        : Screens.current(c).getClass().getName()) + ", connection " + (c.getConnection() == null
                        ? "none" : c.getConnection().getConnection().getPacketListener()));
                throw new AssertionError(what + ": not in the world after 60 s (" + state + ")");
            }
            context.waitTick();
        }
        long inWorld = (System.nanoTime() - start) / 1_000_000;
        check(GameTests.connectedOverQuic(context), what + ": over QUIC");
        boolean used = context.computeOnClient(c -> c.getConnection() != null
                && Transports.usedZeroRtt(c.getConnection().getConnection()));
        check(used == zeroRtt, what + ": 0-RTT " + (zeroRtt ? "used" : "not used"));
        context.waitTicks(IN_WORLD_TICKS);
        check(context.computeOnClient(c -> c.getConnection() != null && c.level != null && c.player != null),
                what + ": still in the world after " + IN_WORLD_TICKS / 20 + " s");
        // As the pause menu's Disconnect button: closes the connection, then the world.
        context.runOnClient(c -> c.disconnectFromWorld(Component.literal("external test done")));
        context.waitFor(c -> c.level == null, 20 * 10);
        QuicraftFabricTestLog.info(what + ": in the world after " + inWorld + " ms, over QUIC"
                + (zeroRtt ? " with 0-RTT" : ""));
        context.waitTicks(20); // the server's close finishes before the next join
    }
}
