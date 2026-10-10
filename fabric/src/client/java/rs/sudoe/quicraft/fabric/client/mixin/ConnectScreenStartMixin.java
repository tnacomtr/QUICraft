// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.EarlyJoins;

/** Notes the join the connect screen starts: 0-RTT needs its inputs, a TCP retry its request. */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenStartMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void quicraft$starting(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data,
            boolean quickPlay, TransferState transferState, CallbackInfo ci) {
        try {
            Hooks.enter("connect request");
            EarlyJoins.starting(parent, address, data, quickPlay, transferState);
        } catch (Throwable t) {
            Hooks.failed("connect request", t);
        }
    }
}
