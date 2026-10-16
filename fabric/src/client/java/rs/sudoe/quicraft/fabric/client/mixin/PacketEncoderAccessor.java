// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The outbound twin of {@link PacketDecoderAccessor}. Only ever cast to from the adapter. */
@Mixin(PacketEncoder.class)
public interface PacketEncoderAccessor {
    @Accessor("protocolInfo")
    ProtocolInfo<?> quicraft$protocolInfo();

    @Invoker("encode")
    void quicraft$encode(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out) throws Exception;
}
