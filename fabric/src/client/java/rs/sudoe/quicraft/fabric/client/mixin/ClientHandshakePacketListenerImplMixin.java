// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.EarlyJoins;
import rs.sudoe.quicraft.fabric.client.FallbackReports;

@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class ClientHandshakePacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    /** After a 0-RTT first-flight mismatch: the same join again over TCP, not the disconnect screen. */
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void quicraft$retryOverTcp(CallbackInfo ci) {
        try {
            Hooks.enter("early retry");
            if (EarlyJoins.retryOverTcp(connection)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            Hooks.failed("early retry", t);
        }
    }

    @Inject(method = "handleLoginFinished", at = @At("TAIL"))
    private void quicraft$reportFallback(CallbackInfo ci) {
        FallbackReports.loginFinished(connection);
    }
}
