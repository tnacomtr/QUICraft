// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets a {@code /3} connection run the game's own packet decoder on one frame at a time
 * (docs/protocol.md 13.4): the handler stays in the pipeline untouched, so the current protocol and
 * the terminal-packet pipeline swap are exactly vanilla's. Only ever cast to from the adapter.
 */
@Mixin(PacketDecoder.class)
public interface PacketDecoderAccessor {
    @Accessor("protocolInfo")
    ProtocolInfo<?> quicraft$protocolInfo();

    @Invoker("decode")
    void quicraft$decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;
}
