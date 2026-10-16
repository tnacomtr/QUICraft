// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The outbound protocol the game's encoder currently runs: the state a /3 record is sent in. */
@Mixin(PacketEncoder.class)
public interface PacketEncoderAccessor {
    @Accessor("protocolInfo")
    ProtocolInfo<?> quicraft$protocolInfo();
}
