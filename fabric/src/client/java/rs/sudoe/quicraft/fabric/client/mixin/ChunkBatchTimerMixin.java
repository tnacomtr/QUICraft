// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.ChunkPacing;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.QuicraftFabric;
import rs.sudoe.quicraft.fabric.client.V3Hooks;

/**
 * {@code /3} only (default on, {@code -Dquicraft.v3.batchTimerFromFirstChunk=false} turns it off): the chunk batch
 * timer restarts when the batch's first chunk arrives instead of at chunk_batch_start, because on {@code /3} the
 * start record outruns the chunk streams (see {@link ChunkPacing}). Like vanilla's own timer it runs on the network
 * thread: the chunk handler's HEAD runs there before {@code ensureRunningOnSameThread} hands the packet to the main
 * thread, and the main-thread replay is skipped. An empty batch keeps vanilla timing. Any throw continues as vanilla.
 *
 * <p>By default (turn off with {@code -Dquicraft.v3.batchTimerBusy=false}) the batch interval is instead the
 * connection's network-thread busy time since the batch's first chunk: at chunk_batch_finished the calculator's
 * start time is moved to {@code now - busy}, so {@code onBatchFinished} measures busy time.
 */
@Mixin(ClientPacketListener.class)
abstract class ChunkBatchTimerMixin {
    /** {@code -Dquicraft.v3.batchdiag=true}: one INFO line per timed batch (size, wall ms, busy ms). */
    @Unique
    private static final boolean BATCH_DIAG = Boolean.getBoolean("quicraft.v3.batchdiag");

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
            Connection connection = ((ClientPacketListener) (Object) this).getConnection();
            quicraft$pacing.chunk(!Minecraft.getInstance().isSameThread(),
                    () -> V3Hooks.isV3(connection),
                    () -> {
                        Hooks.enter("v3 batch timer");
                        chunkBatchSizeCalculator.onBatchStart();
                    },
                    () -> V3Hooks.busyMark(connection));
        } catch (Throwable t) {
            Hooks.failed("v3 batch timer", t);
        }
    }

    @Inject(method = "handleChunkBatchFinished", at = @At("HEAD"))
    private void quicraft$batchFinished(ClientboundChunkBatchFinishedPacket packet, CallbackInfo ci) {
        try {
            Connection connection = ((ClientPacketListener) (Object) this).getConnection();
            long busy = quicraft$pacing.batchFinished(() -> V3Hooks.busyNanos(connection));
            if (busy < 0) {
                return;
            }
            Hooks.enter("v3 batch timer");
            ChunkBatchSizeCalculatorAccessor calculator = (ChunkBatchSizeCalculatorAccessor) (Object) chunkBatchSizeCalculator;
            long now = Util.getNanos();
            if (BATCH_DIAG) {
                QuicraftFabric.LOG.info("QUICraft v3 batch: size={} wall={}ms busy={}ms", packet.batchSize(),
                        (now - calculator.quicraft$chunkBatchStartTime()) / 1_000_000.0, busy / 1_000_000.0);
            }
            calculator.quicraft$setChunkBatchStartTime(now - busy);
            if (quicraft$pacing.takeBusyActivation()) {
                QuicraftFabric.LOG.info("QUICraft: /3 chunk batch timer uses network-thread busy time "
                        + "(-Dquicraft.v3.batchTimerBusy=true)");
            }
        } catch (Throwable t) {
            Hooks.failed("v3 batch timer", t);
        }
    }
}
