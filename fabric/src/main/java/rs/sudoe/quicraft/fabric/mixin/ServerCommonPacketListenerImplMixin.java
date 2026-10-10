// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.net.FallbackPayload;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/** The client's fallback report (docs/protocol.md §6): logged, rate-limited, never acted on. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerImplMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void quicraft$fallbackReport(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            Hooks.enter("fallback report");
            if (packet.payload() instanceof FallbackPayload report) {
                ServerQuic.onFallbackReport(report.data());
            }
        } catch (Throwable t) {
            Hooks.failed("fallback report", t);
        }
    }
}
