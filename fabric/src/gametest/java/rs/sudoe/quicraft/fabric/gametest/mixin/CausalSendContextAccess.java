// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest.mixin;

import java.util.Deque;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;

@Mixin(value = CausalSendContext.class, remap = false)
public interface CausalSendContextAccess {
    @Accessor(value = "frames", remap = false)
    Map<Thread, Deque<CausalSendContext.Frame>> quicraft$frames();
}
