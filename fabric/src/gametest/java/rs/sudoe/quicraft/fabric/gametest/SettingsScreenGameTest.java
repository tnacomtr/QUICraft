// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.client.QuicraftSettingsScreen;

/**
 * The settings screen: reached from the multiplayer screen's button; the transport button
 * cycles auto, tcp-only, quic-only and the choice is saved.
 */
public class SettingsScreenGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        QuicraftClient client = QuicraftClient.get();
        client.setMode(Mode.AUTO);
        context.setScreen(() -> new JoinMultiplayerScreen(new TitleScreen()));
        context.waitForScreen(JoinMultiplayerScreen.class);
        context.clickScreenButton("quicraft.settings.button");
        context.waitForScreen(QuicraftSettingsScreen.class);
        context.takeScreenshot("quicraft-settings");
        context.clickScreenButton("quicraft.settings.transport");
        check(client.mode() == Mode.TCP_ONLY, "auto -> tcp-only, got " + client.mode());
        context.clickScreenButton("quicraft.settings.transport");
        check(client.mode() == Mode.QUIC_ONLY, "tcp-only -> quic-only, got " + client.mode());
        context.clickScreenButton("quicraft.settings.transport");
        check(client.mode() == Mode.AUTO, "quic-only -> auto, got " + client.mode());
        context.clickScreenButton("quicraft.settings.forget_failures");
        context.clickScreenButton("gui.done");
        context.waitForScreen(JoinMultiplayerScreen.class);
        context.setScreen(() -> null);
    }
}
