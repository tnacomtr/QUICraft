// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Runs the game's own compression on one frame (13.4). Only ever cast to from the adapter. */
@Mixin(CompressionEncoder.class)
public interface CompressionEncoderAccessor {
    @Invoker("encode")
    void quicraft$encode(ChannelHandlerContext ctx, ByteBuf uncompressed, ByteBuf out) throws Exception;
}
