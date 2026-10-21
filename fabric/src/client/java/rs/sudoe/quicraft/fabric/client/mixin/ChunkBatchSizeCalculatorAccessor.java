// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads and sets the batch start time of vanilla's chunk batch size calculator, so a {@code /3} connection can
 * time a batch by its network-thread busy time (see {@code ChunkPacing}). Only ever cast to from the adapter.
 */
@Mixin(ChunkBatchSizeCalculator.class)
public interface ChunkBatchSizeCalculatorAccessor {
    /** {@code Util.getNanos()} at the batch start, the clock {@code onBatchFinished} subtracts from. */
    @Accessor("chunkBatchStartTime")
    long quicraft$chunkBatchStartTime();

    @Accessor("chunkBatchStartTime")
    void quicraft$setChunkBatchStartTime(long nanos);
}
