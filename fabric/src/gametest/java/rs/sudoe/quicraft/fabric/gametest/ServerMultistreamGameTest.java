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
import net.minecraft.SharedConstants;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * The dedicated server's {@code /3} join end to end, without the client adapter (docs/protocol.md
 * 12 and 13): a raw {@code quicraft/3} client logs in, runs the binding exchange in the
 * configuration stage, switches to the three lanes and receives the player's play packets. The
 * server runs offline with the explicit test profile of 12.19. Skipped on a native that cannot do
 * {@code /3}.
 */
public class ServerMultistreamGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!QuicSupport.hasKeyingMaterialExport() || !QuicSupport.hasMaxFlowControlWindows()
                || MultistreamSwitch.tableFor(SharedConstants.getProtocolVersion()) == null) {
            // No /3 on this native, or no routing resource for this game version (13.1): TCP only.
            return;
        }
        Hooks.setFaultInjection(false);
        try {
            run(context);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void run(ClientGameTestContext context) throws Exception {
        Path settings = FabricLoader.getInstance().getConfigDir().resolve("quicraft").resolve("server.properties");
        byte[] before = Files.exists(settings) ? Files.readAllBytes(settings) : null;
        Files.createDirectories(settings.getParent());
        Files.write(settings, "multistream=true\n".getBytes(StandardCharsets.UTF_8));
        System.setProperty("quicraft.multistream.offlineTest", "true");
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            Advertisement ad = server.computeOnServer(s -> ServerQuic.advertisement());
            check(ad != null && "quicraft/3".equals(ad.alpn()), "the server advertises quicraft/3: " + ad);
            RawV3Client.Result result;
            try (RawV3Client.Join join = RawV3Client.start(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    ad.port()), ad.fingerprint(), MultistreamSwitch.tableFor(SharedConstants.getProtocolVersion()), 5, false)) {
                // The harness steps the game's ticks only while the test waits for them.
                for (int tick = 0; tick < 1200 && !join.done.isDone(); tick++) {
                    context.waitTick();
                }
                result = join.result;
            }
            QuicraftFabricTestLog.info("offline /3 join: " + result);
            check(result.failure == null, "the raw join ran without error: " + result);
            check(result.activated, "binding activated the lanes: " + result);
            check(result.finishedConfiguration, "the configuration stage finished over records: " + result);
            check(result.playPackets >= 5, "the server sent play packets over the lanes: " + result);
            // The world builder leaves the client on its create-world screen when no game client joins.
            context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        } finally {
            System.clearProperty("quicraft.multistream.offlineTest");
            if (before != null) {
                Files.write(settings, before);
            } else {
                Files.deleteIfExists(settings);
            }
        }
    }
}
