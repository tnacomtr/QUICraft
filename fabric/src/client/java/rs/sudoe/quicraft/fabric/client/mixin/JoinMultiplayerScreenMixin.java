// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftSettingsScreen;

/** A small "QUICraft" button in the multiplayer screen's top right corner opens the settings. */
@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin extends Screen {
    private JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void quicraft$settingsButton(CallbackInfo ci) {
        try {
            Hooks.enter("settings button");
            Screen self = this;
            addRenderableWidget(Button.builder(Component.translatable("quicraft.settings.button"),
                    button -> minecraft.setScreen(new QuicraftSettingsScreen(self)))
                    .bounds(width - 86, 6, 80, 20).build());
        } catch (Throwable t) {
            Hooks.failed("settings button", t);
        }
    }
}
