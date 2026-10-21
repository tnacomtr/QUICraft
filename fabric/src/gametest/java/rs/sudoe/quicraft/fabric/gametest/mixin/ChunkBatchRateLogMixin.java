// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest.mixin;

import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test only: {@code -Dquicraft.test.batchRateLog=true} logs every finished chunk batch with the client's desired
 * chunks per tick afterwards, on every transport (TCP, /2, /3), so pacing can be compared across arms.
 */
@Mixin(ClientPacketListener.class)
abstract class ChunkBatchRateLogMixin {
    @Unique
    private static final boolean ENABLED = Boolean.getBoolean("quicraft.test.batchRateLog");
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("QUICraft gametest");

    @Shadow
    @Final
    private ChunkBatchSizeCalculator chunkBatchSizeCalculator;

    @Inject(method = "handleChunkBatchFinished", at = @At("TAIL"))
    private void quicraftTest$logRate(ClientboundChunkBatchFinishedPacket packet, CallbackInfo ci) {
        // One line per batch on purpose: opt-in, and the analysis needs every batch of a join (a few hundred).
        if (ENABLED) {
            try {
                LOGGER.info("QUICraft test batch: size={} desired={}", packet.batchSize(),
                        chunkBatchSizeCalculator.getDesiredChunksPerTick());
            } catch (Throwable ignored) {
                // Diagnostics never reach vanilla's handler.
            }
        }
    }
}
