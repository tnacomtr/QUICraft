// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.DecodeContext;
import rs.sudoe.quicraft.fabric.net.BindingPayload;

/**
 * Unknown custom payloads lose their bytes; {@code quicraft:binding} keeps them (both directions).
 * Only the codec changes. Whether a binding payload does anything is decided by the pipeline
 * handler of a {@code /3} connection, so on TCP and on {@code /2} the payload is inert (12.8).
 */
@Mixin(DiscardedPayload.class)
abstract class BindingCodecMixin {
    @Inject(method = "codec", at = @At("RETURN"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends FriendlyByteBuf> void quicraft$bindingCodec(Identifier id, int maxPayloadSize,
            CallbackInfoReturnable<StreamCodec<T, DiscardedPayload>> cir) {
        try {
            Hooks.enter("binding codec");
            if (BindingPayload.TYPE.id().equals(id)) {
                StreamCodec<T, DiscardedPayload> vanilla = cir.getReturnValue();
                StreamCodec<T, BindingPayload> binding = BindingPayload.codec();
                // Vanilla's own codec unless a /3 exchange is waiting for the payload: then over-long
                // bytes must not fail a join that would otherwise have discarded them (12.8).
                cir.setReturnValue((StreamCodec) new StreamCodec<T, Object>() {
                    @Override
                    public Object decode(T buf) {
                        return DecodeContext.bindingExpected() ? binding.decode(buf) : vanilla.decode(buf);
                    }

                    @Override
                    public void encode(T buf, Object payload) {
                        if (payload instanceof BindingPayload) {
                            binding.encode(buf, (BindingPayload) payload);
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
