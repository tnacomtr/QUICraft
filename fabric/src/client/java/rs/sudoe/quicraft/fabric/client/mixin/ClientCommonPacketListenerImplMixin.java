// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.EarlyJoins;
import rs.sudoe.quicraft.fabric.client.V3Hooks;

/**
 * The configuration and play listeners' disconnect: a {@code /3} attempt that failed before its
 * lanes were active (docs/protocol.md 12.14) starts the same join again over TCP once vanilla has
 * cleaned up the failed one, and {@code quic-only} shows why instead of "end of stream".
 */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ClientCommonPacketListenerImplMixin {
    @Shadow
    @Final
    protected Connection connection;

    @ModifyVariable(method = "onDisconnect", at = @At("HEAD"), argsOnly = true)
    private DisconnectionDetails quicraft$details(DisconnectionDetails details) {
        return V3Hooks.disconnectDetails(connection, details);
    }

    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void quicraft$retryOverTcp(DisconnectionDetails details, CallbackInfo ci) {
        try {
            Hooks.enter("v3 retry");
            EarlyJoins.retryOverTcp(connection);
        } catch (Throwable t) {
            Hooks.failed("v3 retry", t);
        }
    }
}
