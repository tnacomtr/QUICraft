// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.CipherBase;
import net.minecraft.network.CipherDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CipherDecoder.class)
public interface CipherDecoderAccessor {
    @Accessor("cipher")
    CipherBase quicraft$cipherBase();
}
