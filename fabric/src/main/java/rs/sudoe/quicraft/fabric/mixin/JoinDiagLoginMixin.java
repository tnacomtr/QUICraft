// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rs.sudoe.quicraft.fabric.server.V3Join;

/**
 * The {@code /3} join reached play on the server: the spawn succeeded, so its join marks write their
 * one line here. A TCP join or a join without the diagnostics switch has no marks and nothing
 * happens; never throws.
 */
@Mixin(PrepareSpawnTask.class)
abstract class JoinDiagLoginMixin {
    private static final String SPAWN_PLAYER =
            "spawnPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/network/CommonListenerCookie;)"
                    + "Lnet/minecraft/server/level/ServerPlayer;";

    @Inject(method = SPAWN_PLAYER, at = @At("RETURN"))
    private void quicraft$playLogin(Connection connection, CommonListenerCookie cookie,
            CallbackInfoReturnable<ServerPlayer> cir) {
        try {
            if (cir.getReturnValue() == null) {
                return;
            }
            V3Join join = V3Join.of(connection);
            if (join != null) {
                join.playLogin();
            }
        } catch (Throwable ignored) {
            // Diagnostics never reach the join.
        }
    }
}
