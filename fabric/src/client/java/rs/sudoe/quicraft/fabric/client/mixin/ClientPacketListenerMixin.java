// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.client.PlayNotices;
import rs.sudoe.quicraft.fabric.client.V3Hooks;

/**
 * The join reached play: the chat exists, so what the multi-stream attempt has to say goes there,
 * and the {@code /3} join marks write their one line here.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Shadow
    @Final
    protected Connection connection;

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void quicraft$joined(ClientboundLoginPacket packet, CallbackInfo ci) {
        PlayNotices.joined();
        V3Hooks.playLogin(connection);
    }
}
