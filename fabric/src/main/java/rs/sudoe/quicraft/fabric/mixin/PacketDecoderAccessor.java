// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The inbound protocol the game's decoder currently runs; the /3 codec decodes with the same one. */
@Mixin(PacketDecoder.class)
public interface PacketDecoderAccessor {
    @Accessor("protocolInfo")
    ProtocolInfo<?> quicraft$protocolInfo();
}
