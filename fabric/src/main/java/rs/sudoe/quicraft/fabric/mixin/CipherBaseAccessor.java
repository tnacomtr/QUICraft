// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import javax.crypto.Cipher;
import net.minecraft.network.CipherBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The AES/CFB8 cipher inside the game's cipher handlers, which the /3 cutover continues (12.13). */
@Mixin(CipherBase.class)
public interface CipherBaseAccessor {
    @Accessor("cipher")
    Cipher quicraft$cipher();
}
