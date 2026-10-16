// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import javax.crypto.Cipher;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.client.V3Hooks;

/**
 * Where vanilla starts encryption: a {@code /3} join swaps the decryptor it just installed for the
 * boundary-aware one right here, so the cipher position still matches the server's (12.13).
 */
@Mixin(Connection.class)
abstract class ConnectionEncryptionMixin {
    @Inject(method = "setEncryptionKey", at = @At("TAIL"))
    private void quicraft$boundary(Cipher decryptCipher, Cipher encryptCipher, CallbackInfo ci) {
        V3Hooks.encryptionStarted((Connection) (Object) this, decryptCipher);
    }
}
