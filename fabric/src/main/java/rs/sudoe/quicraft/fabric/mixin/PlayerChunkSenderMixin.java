// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.ChunkPause;

/**
 * Producer pause for chunk batches (docs/protocol.md 13.20). While a {@code /3} connection's outbound
 * queue is under pressure, {@code sendNextChunks} returns before it touches the batch quota or starts
 * a batch, so the pending chunks wait and go out after the pressure clears. Nothing is dropped.
 *
 * <p>Fail safe: the cancel happens only when {@link ChunkPause#paused} says so. Every other case,
 * including TCP, {@code /2}, a missing connection and any throw in project code, runs vanilla.
 */
@Mixin(PlayerChunkSender.class)
abstract class PlayerChunkSenderMixin {
    @Inject(method = "sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"),
            cancellable = true)
    private void quicraft$pauseUnderOutboundPressure(ServerPlayer player, CallbackInfo ci) {
        try {
            Hooks.enter("chunk pause");
            Channel channel = gameChannel(player);
            if (ChunkPause.paused(channel)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            Hooks.failed("chunk pause", t);
        }
    }

    private static Channel gameChannel(ServerPlayer player) {
        Connection connection = ((ServerCommonPacketListenerImplAccessor) player.connection).quicraft$connection();
        return connection == null ? null : ((ConnectionAccessor) connection).quicraft$channel();
    }
}
