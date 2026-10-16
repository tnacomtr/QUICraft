// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.CompressionDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The negotiated inbound compression, which a GAME record's body repeats (13.4). */
@Mixin(CompressionDecoder.class)
public interface CompressionDecoderAccessor {
    @Accessor("threshold")
    int quicraft$threshold();

    @Accessor("validateDecompressed")
    boolean quicraft$validateDecompressed();
}
