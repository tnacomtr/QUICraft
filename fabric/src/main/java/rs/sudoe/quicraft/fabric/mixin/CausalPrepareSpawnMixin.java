// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import org.spongepowered.asm.mixin.Mixin;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;

/**
 * U3-W3a producer scope for the frozen login/spawn boundary (runA section 0):
 * {@code PrepareSpawnTask.spawnPlayer(Connection,CommonListenerCookie):ServerPlayer}. Every envelope
 * created on the server thread while this boundary runs snapshots the frame's refs; the frame is
 * closed on the same thread whether the vanilla method returns or throws.
 *
 * <p>The hook is fail safe. Opening the frame happens before the vanilla call and is guarded; the
 * close happens in {@code finally}. A project failure leaves the vanilla method's result and its
 * exactly-once invocation untouched, so TCP login behaves as if this mixin were absent.
 */
@Mixin(PrepareSpawnTask.class)
abstract class CausalPrepareSpawnMixin {
    private static final String SPAWN_PLAYER =
            "spawnPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/network/CommonListenerCookie;)"
                    + "Lnet/minecraft/server/level/ServerPlayer;";

    @WrapMethod(method = SPAWN_PLAYER)
    private ServerPlayer quicraft$loginSpawnProducer(Connection connection, CommonListenerCookie cookie,
            Operation<ServerPlayer> original) {
        CausalSendContext.ProducerScope scope = null;
        try {
            Hooks.enter("causal login spawn");
            scope = CausalSendContext.openProducerScope(
                    ((ConnectionAccessor) connection).quicraft$channel(), "login_spawn");
        } catch (Throwable t) {
            Hooks.failed("causal login spawn", t);
            scope = null;
        }
        try {
            return original.call(connection, cookie);
        } finally {
            try {
                if (scope != null) {
                    scope.close();
                }
            } catch (Throwable t) {
                Hooks.failed("causal login spawn exit", t);
            }
        }
    }
}
