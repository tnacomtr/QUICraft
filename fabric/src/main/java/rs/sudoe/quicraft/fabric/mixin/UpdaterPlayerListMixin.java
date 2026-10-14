// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.update.ServerUpdater;

/** A player was placed: an admin who has not seen the latest updater notice gets it. */
@Mixin(PlayerList.class)
abstract class UpdaterPlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void quicraft$noticeUpdate(Connection connection, ServerPlayer player, CommonListenerCookie cookie,
            CallbackInfo ci) {
        try {
            Hooks.enter("updater join");
            ServerUpdater.join(player);
        } catch (Throwable t) {
            Hooks.failed("updater join", t);
        }
    }
}
