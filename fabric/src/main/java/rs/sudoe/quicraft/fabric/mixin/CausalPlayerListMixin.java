// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;

/**
 * U3-W3a producer scope for the frozen login boundary (runA section 0):
 * {@code PlayerList.placeNewPlayer(Connection,ServerPlayer,CommonListenerCookie):V}. Every envelope
 * created on the server thread while the join packets are produced snapshots the frame's refs; the
 * frame is closed on the same thread whether the vanilla method returns or throws.
 *
 * <p>This unit does not add the respawn or alive-replacement hook; those remain for the lifecycle
 * wave. The hook is fail safe: opening is guarded, closing is in {@code finally}, and the vanilla
 * method runs exactly once, so TCP login behaves as if this mixin were absent.
 */
@Mixin(PlayerList.class)
abstract class CausalPlayerListMixin {
    private static final String PLACE_NEW_PLAYER =
            "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lnet/minecraft/server/network/CommonListenerCookie;)V";

    @WrapMethod(method = PLACE_NEW_PLAYER)
    private void quicraft$placeNewPlayerProducer(Connection connection, ServerPlayer player,
            CommonListenerCookie cookie, Operation<Void> original) {
        CausalSendContext.ProducerScope scope = null;
        try {
            Hooks.enter("causal place new player");
            scope = CausalSendContext.openProducerScope(
                    ((ConnectionAccessor) connection).quicraft$channel(), "login");
        } catch (Throwable t) {
            Hooks.failed("causal place new player", t);
            scope = null;
        }
        try {
            original.call(connection, player, cookie);
        } finally {
            try {
                if (scope != null) {
                    scope.close();
                }
            } catch (Throwable t) {
                Hooks.failed("causal place new player exit", t);
            }
        }
    }
}
