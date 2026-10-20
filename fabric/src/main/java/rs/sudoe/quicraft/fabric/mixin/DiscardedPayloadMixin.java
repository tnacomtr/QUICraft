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
import rs.sudoe.quicraft.fabric.net.BindingDecodeContext;
import rs.sudoe.quicraft.fabric.net.BindingPayload;
import rs.sudoe.quicraft.fabric.net.FallbackPayload;

/** Unknown custom payloads lose their bytes; quicraft:fallback and quicraft:binding keep them (both directions). */
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

    @Inject(method = "codec", at = @At("RETURN"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends FriendlyByteBuf> void quicraft$bindingCodec(Identifier id, int maxPayloadSize,
            CallbackInfoReturnable<StreamCodec<T, DiscardedPayload>> cir) {
        try {
            Hooks.enter("binding codec");
            if (BindingPayload.TYPE.id().equals(id)) {
                StreamCodec<T, DiscardedPayload> vanilla = cir.getReturnValue();
                StreamCodec<T, BindingPayload> binding = BindingPayload.codec();
                cir.setReturnValue((StreamCodec) new StreamCodec<T, Object>() {
                    @Override
                    public Object decode(T buf) {
                        return BindingDecodeContext.bindingExpected() ? binding.decode(buf) : vanilla.decode(buf);
                    }

                    @Override
                    public void encode(T buf, Object payload) {
                        if (payload instanceof BindingPayload shared) {
                            binding.encode(buf, shared);
                        } else {
                            vanilla.encode(buf, (DiscardedPayload) payload);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Hooks.failed("binding codec", t);
        }
    }
}
