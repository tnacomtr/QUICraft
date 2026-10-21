// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

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
 * Opt-in ({@code -Dquicraft.v3.batchTimerFromFirstChunk=true}), {@code /3} only: the chunk batch
 * timer restarts when the batch's first chunk arrives instead of at chunk_batch_start, because on
 * {@code /3} the start record outruns the chunk lanes (see {@link ChunkPacing}). An empty batch
 * keeps vanilla timing. Any throw continues as vanilla.
 */
@Mixin(ClientPacketListener.class)
abstract class ChunkBatchTimerMixin {
    @Shadow
    @Final
    private ChunkBatchSizeCalculator chunkBatchSizeCalculator;

    @Unique
    private volatile boolean quicraft$awaitingFirstChunk;

    @Inject(method = "handleChunkBatchStart", at = @At("TAIL"))
    private void quicraft$batchStart(ClientboundChunkBatchStartPacket packet, CallbackInfo ci) {
        if (ChunkPacing.BATCH_TIMER_FROM_FIRST_CHUNK) {
            quicraft$awaitingFirstChunk = true;
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void quicraft$firstChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!ChunkPacing.BATCH_TIMER_FROM_FIRST_CHUNK || !quicraft$awaitingFirstChunk) {
            return;
        }
        try {
            Hooks.enter("v3 batch timer");
            boolean v3 = V3Hooks.isV3(((ClientPacketListener) (Object) this).getConnection());
            if (ChunkPacing.restartTimer(true, v3, true)) {
                chunkBatchSizeCalculator.onBatchStart();
            }
            quicraft$awaitingFirstChunk = false;
        } catch (Throwable t) {
            quicraft$awaitingFirstChunk = false;
            Hooks.failed("v3 batch timer", t);
        }
    }

    @Inject(method = "handleChunkBatchFinished", at = @At("HEAD"))
    private void quicraft$batchFinished(ClientboundChunkBatchFinishedPacket packet, CallbackInfo ci) {
        quicraft$awaitingFirstChunk = false;
    }
}
