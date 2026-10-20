// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;
import rs.sudoe.quicraft.fabric.net.BindingDecodeContext;

/** Scopes shared payload codec selection to exactly one decoder invocation. */
@Mixin(PacketDecoder.class)
abstract class PacketDecoderContextMixin {
    @WrapMethod(method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V")
    private void quicraft$context(ChannelHandlerContext ctx, ByteBuf input, List<Object> out,
            Operation<Void> original) {
        Channel previous = BindingDecodeContext.current();
        try {
            BindingDecodeContext.enter(ctx.channel());
            original.call(ctx, input, out);
        } finally {
            BindingDecodeContext.enter(previous);
        }
    }
}
