// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client.mixin;

import net.minecraft.client.gui.components.debug.DebugEntryTps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.client.DebugLine;

/** F3: the transport line goes right under the server line (shown by default). */
@Mixin(DebugEntryTps.class)
abstract class DebugEntryTpsMixin {
    @Inject(method = "display", at = @At("TAIL"))
    private void quicraft$transport(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk,
            LevelChunk serverChunk, CallbackInfo ci) {
        DebugLine.add(displayer::addLine);
    }
}
