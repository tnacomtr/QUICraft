// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import java.security.PrivateKey;
import javax.crypto.SecretKey;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.CryptException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.V3Join;

/**
 * A {@code quicraft/3} connection needs Minecraft's shared secret to bind the login to the QUIC
 * connection (docs/protocol.md 12.6). The game computes it in {@code handleKey}; this hands a copy to
 * the connection's join, if it has one, and returns the very key the game asked for. Every other
 * connection is untouched.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
abstract class ServerLoginPacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Redirect(method = "handleKey", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/login/ServerboundKeyPacket;getSecretKey(Ljava/security/PrivateKey;)Ljavax/crypto/SecretKey;"))
    private SecretKey quicraft$secret(ServerboundKeyPacket packet, PrivateKey key) throws CryptException {
        SecretKey secret = packet.getSecretKey(key);
        try {
            Hooks.enter("binding secret");
            V3Join join = V3Join.of(connection);
            if (join != null) {
                join.onSecret(secret.getEncoded());
            }
        } catch (Throwable t) {
            Hooks.failed("binding secret", t);
            // A /3 join without its secret closes with 0x10 when binding starts; TCP never gets here.
        }
        return secret;
    }
}
