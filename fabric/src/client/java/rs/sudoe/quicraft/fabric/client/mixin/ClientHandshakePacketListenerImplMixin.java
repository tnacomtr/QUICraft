// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import javax.crypto.SecretKey;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.EarlyJoins;
import rs.sudoe.quicraft.fabric.client.FallbackReports;
import rs.sudoe.quicraft.fabric.client.V3Hooks;

@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class ClientHandshakePacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    /** After a 0-RTT first-flight mismatch: the same join again over TCP, not the disconnect screen. */
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void quicraft$retryOverTcp(CallbackInfo ci) {
        try {
            Hooks.enter("early retry");
            if (EarlyJoins.retryOverTcp(connection)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            Hooks.failed("early retry", t);
        }
    }

    @Inject(method = "handleLoginFinished", at = @At("TAIL"))
    private void quicraft$reportFallback(CallbackInfo ci) {
        FallbackReports.loginFinished(connection);
        V3Hooks.configurationStarted(connection); // a no-op unless this is a quicraft/3 join
    }

    /** /3 needs an authenticated login (12.7): Hello without authentication ends the attempt. */
    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void quicraft$hello(ClientboundHelloPacket packet, CallbackInfo ci) {
        if (!V3Hooks.hello(connection, packet.shouldAuthenticate())) {
            ci.cancel();
        }
    }

    /** The shared secret S that binding is keyed from (12.4, 12.6). */
    @WrapOperation(method = "handleHello", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Crypt;generateSecretKey()Ljavax/crypto/SecretKey;"))
    private SecretKey quicraft$secret(Operation<SecretKey> original) {
        SecretKey key = original.call();
        V3Hooks.secret(connection, key);
        return key;
    }

    /** Whether the session-server join succeeded: the LAN case logs a warning and encrypts anyway. */
    @Inject(method = "authenticateServer", at = @At("RETURN"))
    private void quicraft$authenticated(String digest, CallbackInfoReturnable<Component> cir) {
        V3Hooks.authenticated(connection, cir.getReturnValue());
    }

    /** Login Finished without authenticated encryption ends the /3 attempt (12.7). */
    @Inject(method = "handleLoginFinished", at = @At("HEAD"), cancellable = true)
    private void quicraft$loginFinished(CallbackInfo ci) {
        if (!V3Hooks.loginFinished(connection)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "onDisconnect", at = @At("HEAD"), argsOnly = true)
    private DisconnectionDetails quicraft$details(DisconnectionDetails details) {
        return V3Hooks.disconnectDetails(connection, details);
    }
}
