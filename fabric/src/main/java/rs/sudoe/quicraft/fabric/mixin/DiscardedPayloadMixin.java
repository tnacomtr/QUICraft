// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.net.FallbackPayload;

/** Unknown custom payloads lose their bytes; quicraft:fallback keeps them (both directions). */
@Mixin(DiscardedPayload.class)
abstract class DiscardedPayloadMixin {
    @Inject(method = "codec", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends FriendlyByteBuf> void quicraft$fallbackCodec(Identifier id, int maxPayloadSize,
            CallbackInfoReturnable<StreamCodec<T, DiscardedPayload>> cir) {
        try {
            Hooks.enter("fallback codec");
            if (FallbackPayload.TYPE.id().equals(id)) {
                cir.setReturnValue((StreamCodec) FallbackPayload.<T>codec(maxPayloadSize));
            }
        } catch (Throwable t) {
            Hooks.failed("fallback codec", t);
        }
    }
}
