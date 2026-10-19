// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.gametest.CausalLoginProbe;

@Mixin(PlayerList.class)
abstract class CausalLoginPlaceObserverMixin {
    private static final String TARGET = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V";
    @Inject(method = TARGET, at = @At("HEAD"))
    private void quicraftTest$head(Connection c, ServerPlayer p, CommonListenerCookie cookie, CallbackInfo ci) { CausalLoginProbe.observe("login", true, c); }
    @Inject(method = TARGET, at = @At("RETURN"))
    private void quicraftTest$return(Connection c, ServerPlayer p, CommonListenerCookie cookie, CallbackInfo ci) { CausalLoginProbe.observe("login", false, c); }
}
