// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.CompressionDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Runs the game's own decompression on one frame (13.4). Only ever cast to from the adapter. */
@Mixin(CompressionDecoder.class)
public interface CompressionDecoderAccessor {
    @Invoker("decode")
    void quicraft$decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;
}
