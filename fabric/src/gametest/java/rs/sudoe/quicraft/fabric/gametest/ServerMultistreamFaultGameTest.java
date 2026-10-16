// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * CLAUDE.md fault injection for the {@code /3} server hooks: with project code forced to throw in
 * each of them in turn, a {@code /3} join is closed (docs/protocol.md 12.14, so a real client would
 * retry over TCP) instead of hanging or half-switching, and a vanilla TCP join and the server list
 * ping on the same server still work.
 */
public class ServerMultistreamFaultGameTest implements FabricClientGameTest {
    /** Every hook the {@code /3} join adds to the server (its enter() names), offline-profile ones only. */
    private static final String[] HOOKS = {"binding configuration", "binding start", "binding payload",
        "binding inbound", "binding outbound"};

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!QuicSupport.hasKeyingMaterialExport() || !QuicSupport.hasMaxFlowControlWindows()) {
            return;
        }
        try {
            run(context);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void run(ClientGameTestContext context) throws Exception {
        Hooks.setFaultInjection(false);
        QuicraftClient.get().setMode(Mode.TCP_ONLY);
        Path settings = FabricLoader.getInstance().getConfigDir().resolve("quicraft").resolve("server.properties");
        byte[] before = Files.exists(settings) ? Files.readAllBytes(settings) : null;
        Files.createDirectories(settings.getParent());
        Files.write(settings, "multistream=true\n".getBytes(StandardCharsets.UTF_8));
        System.setProperty("quicraft.multistream.offlineTest", "true");
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            Advertisement ad = server.computeOnServer(s -> ServerQuic.advertisement());
            check(ad != null && "quicraft/3".equals(ad.alpn()), "the server advertises quicraft/3: " + ad);
            InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), ad.port());
            for (String hook : HOOKS) {
                Hooks.setFaultInjection(hook);
                RawV3Client.Result result;
                try (RawV3Client.Join join = RawV3Client.start(address, ad.fingerprint(),
                        MultistreamSwitch.tableFor(775), 5, false, true)) {
                    for (int tick = 0; tick < 1200 && !join.done.isDone(); tick++) {
                        context.waitTick();
                    }
                    result = join.result;
                } finally {
                    Hooks.setFaultInjection(false);
                }
                QuicraftFabricTestLog.info(hook + " throwing, /3 join: " + result);
                check(result.closed, hook + ": the failed /3 join was closed, not left hanging: " + result);
                check(!result.finishedConfiguration && result.playPackets == 0,
                        hook + ": no half-switched play session: " + result);
            }
            // The same server, every project hook throwing: vanilla TCP and the ping are unaffected.
            Hooks.setFaultInjection(true);
            try {
                check(GameTests.serverListPing(context, GameTests.address(server)), "server list ping");
                try (var connection = server.connect()) {
                    GameTests.waitForChunks(connection);
                    check(!GameTests.connectedOverQuic(context), "joined over TCP");
                }
            } finally {
                Hooks.setFaultInjection(false);
            }
            context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        } finally {
            System.clearProperty("quicraft.multistream.offlineTest");
            QuicraftClient.get().setMode(Mode.AUTO);
            if (before != null) {
                Files.write(settings, before);
            } else {
                Files.deleteIfExists(settings);
            }
        }
    }
}
