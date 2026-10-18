// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.spongepowered.asm.mixin.Mixin;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;

/**
 * U3-W3a producer scope for the frozen login/ready boundary (runA section 0):
 * {@code PrepareSpawnTask$Ready.spawn(Connection,CommonListenerCookie):ServerPlayer}. This runs
 * inside the spawn boundary when vanilla chooses the ready stage, so the envelopes it produces name
 * both the enclosing login_spawn scope and this inner login_ready scope.
 *
 * <p>The hook is fail safe. Opening the frame happens before the vanilla call and is guarded; the
 * close happens in {@code finally}. A project failure leaves the vanilla method's result and its
 * exactly-once invocation untouched, so TCP login behaves as if this mixin were absent.
 */
@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Ready")
abstract class CausalReadyMixin {
    private static final String SPAWN =
            "spawn(Lnet/minecraft/network/Connection;Lnet/minecraft/server/network/CommonListenerCookie;)"
                    + "Lnet/minecraft/server/level/ServerPlayer;";

    @WrapMethod(method = SPAWN)
    private ServerPlayer quicraft$loginReadyProducer(Connection connection, CommonListenerCookie cookie,
            Operation<ServerPlayer> original) {
        CausalSendContext.ProducerScope scope = null;
        try {
            Hooks.enter("causal login ready");
            scope = CausalSendContext.openProducerScope(
                    ((ConnectionAccessor) connection).quicraft$channel(), "login_ready");
        } catch (Throwable t) {
            Hooks.failed("causal login ready", t);
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
                Hooks.failed("causal login ready exit", t);
            }
        }
    }
}
