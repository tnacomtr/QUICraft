// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.ServerBindingPayload;
import rs.sudoe.quicraft.fabric.net.FallbackPayload;
import rs.sudoe.quicraft.fabric.server.ServerQuic;
import rs.sudoe.quicraft.fabric.server.V3Join;

/**
 * The client's fallback report (docs/protocol.md §6): logged, rate-limited, never acted on. And the
 * {@code quicraft:binding} payloads of 12.8, which this takes before any other mod or plugin sees
 * them (an early priority puts this ahead of theirs).
 */
@Mixin(value = ServerCommonPacketListenerImpl.class, priority = 100)
abstract class ServerCommonPacketListenerImplMixin {
    @Shadow
    @Final
    protected Connection connection;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void quicraft$fallbackReport(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (packet.payload() instanceof ServerBindingPayload binding) {
            // 12.8: never delivered to anything else, on any connection. Without a join (TCP, /2)
            // it is inert.
            ci.cancel();
            try {
                Hooks.enter("binding payload");
                V3Join join = V3Join.of(connection);
                if (join != null) {
                    join.onPayload(binding.data());
                }
            } catch (Throwable t) {
                Hooks.failed("binding payload", t);
                V3Join.failOf(connection, "binding payload: " + t, t);
            }
            return;
        }
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
