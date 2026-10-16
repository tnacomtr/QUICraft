// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.DecodeContext;

/** Tells payload codecs which connection is decoding (see {@link DecodeContext}). */
@Mixin(PacketDecoder.class)
abstract class PacketDecoderContextMixin {
    @Inject(method = "decode", at = @At("HEAD"))
    private void quicraft$context(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci) {
        try {
            DecodeContext.enter(ctx.channel());
        } catch (Throwable t) {
            Hooks.failed("decode context", t);
        }
    }
}
