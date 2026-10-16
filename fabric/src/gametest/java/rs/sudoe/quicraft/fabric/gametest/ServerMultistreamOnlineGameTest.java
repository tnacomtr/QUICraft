// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * The same end-to-end {@code /3} join as {@link ServerMultistreamGameTest}, with the server in online
 * mode (docs/protocol.md 12.4 to 12.13): the raw client does vanilla's RSA and AES/CFB8 login against
 * a mock session server, the binding exchange is keyed with Minecraft's shared secret (mode 1), and
 * Minecraft's cipher is switched off at the boundary of the final switch packets. No offline test
 * flag: a server that authenticates needs none.
 */
public class ServerMultistreamOnlineGameTest implements FabricClientGameTest {
    private static final String PROFILE = "{\"id\":\"00000000000000000000000000000007\",\"name\":\"RawV3\","
            + "\"properties\":[]}";
    private static final String[] HOST_PROPERTIES = {"minecraft.api.session.host", "minecraft.api.services.host",
            "minecraft.api.profiles.host", "minecraft.api.auth.host"};

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
        Path settings = FabricLoader.getInstance().getConfigDir().resolve("quicraft").resolve("server.properties");
        byte[] before = Files.exists(settings) ? Files.readAllBytes(settings) : null;
        Files.createDirectories(settings.getParent());
        Files.write(settings, "multistream=true\n".getBytes(StandardCharsets.UTF_8));
        // The mock session server: every hasJoined succeeds. The server reads the host properties when
        // it starts, so they are set before it does.
        HttpServer sessions = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        sessions.createContext("/", exchange -> {
            byte[] body = PROFILE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        sessions.start();
        String[] previous = new String[HOST_PROPERTIES.length];
        for (int i = 0; i < HOST_PROPERTIES.length; i++) {
            previous[i] = System.getProperty(HOST_PROPERTIES[i]);
            System.setProperty(HOST_PROPERTIES[i], "http://127.0.0.1:" + sessions.getAddress().getPort());
        }
        System.clearProperty("quicraft.multistream.offlineTest");
        try {
            Properties p = new Properties();
            p.setProperty("server-port", Integer.toString(GameTests.freePort()));
            p.setProperty("online-mode", "true");
            p.setProperty("level-type", "minecraft:flat");
            p.setProperty("generate-structures", "false");
            p.setProperty("spawn-protection", "0");
            p.setProperty("view-distance", "4");
            p.setProperty("simulation-distance", "4");
            try (TestDedicatedServerContext server = context.worldBuilder().createServer(p)) {
                Advertisement ad = server.computeOnServer(s -> ServerQuic.advertisement());
                check(ad != null && "quicraft/3".equals(ad.alpn()), "the server advertises quicraft/3: " + ad);
                RawV3Client.Result result;
                try (RawV3Client.Join join = RawV3Client.start(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), ad.port()), ad.fingerprint(),
                        MultistreamSwitch.tableFor(775), 5, true)) {
                    for (int tick = 0; tick < 1200 && !join.done.isDone(); tick++) {
                        context.waitTick();
                    }
                    result = join.result;
                }
                QuicraftFabricTestLog.info("online /3 join: " + result);
                check(result.failure == null, "the raw join ran without error: " + result);
                check(result.events.contains("login encryption on"), "Minecraft's login encryption ran: " + result);
                check(result.activated, "binding activated the lanes: " + result);
                check(result.finishedConfiguration, "the configuration stage finished over records: " + result);
                check(result.playPackets >= 5, "the server sent play packets over the lanes: " + result);
                context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
            }
        } finally {
            for (int i = 0; i < HOST_PROPERTIES.length; i++) {
                if (previous[i] == null) {
                    System.clearProperty(HOST_PROPERTIES[i]);
                } else {
                    System.setProperty(HOST_PROPERTIES[i], previous[i]);
                }
            }
            sessions.stop(0);
            if (before != null) {
                Files.write(settings, before);
            } else {
                Files.deleteIfExists(settings);
            }
        }
    }
}
