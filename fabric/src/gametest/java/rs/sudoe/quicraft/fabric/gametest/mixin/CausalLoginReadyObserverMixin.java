// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rs.sudoe.quicraft.fabric.gametest.CausalLoginProbe;

@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Ready")
abstract class CausalLoginReadyObserverMixin {
    private static final String TARGET = "spawn(Lnet/minecraft/network/Connection;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/level/ServerPlayer;";
    @Inject(method = TARGET, at = @At("HEAD"))
    private void quicraftTest$head(Connection c, CommonListenerCookie cookie, CallbackInfoReturnable<ServerPlayer> ci) { CausalLoginProbe.observe("login_ready", true, c); }
    @Inject(method = TARGET, at = @At("RETURN"))
    private void quicraftTest$return(Connection c, CommonListenerCookie cookie, CallbackInfoReturnable<ServerPlayer> ci) { CausalLoginProbe.observe("login_ready", false, c); }
}
