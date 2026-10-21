// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.ChunkPacing;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.V3Hooks;

/**
 * {@code /3} only (default on, {@code -Dquicraft.v3.batchTimerFromFirstChunk=false} turns it off): the chunk batch
 * timer restarts when the batch's first chunk arrives instead of at chunk_batch_start, because on {@code /3} the
 * start record outruns the chunk streams (see {@link ChunkPacing}). Like vanilla's own timer it runs on the network
 * thread: the chunk handler's HEAD runs there before {@code ensureRunningOnSameThread} hands the packet to the main
 * thread, and the main-thread replay is skipped. An empty batch keeps vanilla timing. Any throw continues as vanilla.
 */
@Mixin(ClientPacketListener.class)
abstract class ChunkBatchTimerMixin {
    @Shadow
    @Final
    private ChunkBatchSizeCalculator chunkBatchSizeCalculator;

    @Unique
    private final ChunkPacing quicraft$pacing = new ChunkPacing();

    @Inject(method = "handleChunkBatchStart", at = @At("TAIL"))
    private void quicraft$batchStart(ClientboundChunkBatchStartPacket packet, CallbackInfo ci) {
        try {
            quicraft$pacing.batchStarted();
        } catch (Throwable t) {
            Hooks.failed("v3 batch timer", t);
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void quicraft$firstChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        try {
            quicraft$pacing.chunk(!Minecraft.getInstance().isSameThread(),
                    () -> V3Hooks.isV3(((ClientPacketListener) (Object) this).getConnection()),
                    () -> {
                        Hooks.enter("v3 batch timer");
                        chunkBatchSizeCalculator.onBatchStart();
                    });
        } catch (Throwable t) {
            Hooks.failed("v3 batch timer", t);
        }
    }

    @Inject(method = "handleChunkBatchFinished", at = @At("HEAD"))
    private void quicraft$batchFinished(ClientboundChunkBatchFinishedPacket packet, CallbackInfo ci) {
        try {
            quicraft$pacing.batchFinished();
        } catch (Throwable t) {
            Hooks.failed("v3 batch timer", t);
        }
    }
}
