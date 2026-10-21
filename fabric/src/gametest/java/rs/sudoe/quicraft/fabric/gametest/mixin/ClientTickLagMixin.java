// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test only: a slow client main thread. {@code -Dquicraft.test.clientTickLagMs=N} sleeps N ms at the start of
 * every client tick; {@code -Dquicraft.test.clientStallMs=M} with {@code -Dquicraft.test.clientStallEveryTicks=K}
 * adds an M ms stall every K ticks. All default to 0 (off).
 */
@Mixin(Minecraft.class)
abstract class ClientTickLagMixin {
    @Unique
    private static final int LAG_MS = Integer.getInteger("quicraft.test.clientTickLagMs", 0);
    @Unique
    private static final int STALL_MS = Integer.getInteger("quicraft.test.clientStallMs", 0);
    @Unique
    private static final int STALL_EVERY = Integer.getInteger("quicraft.test.clientStallEveryTicks", 0);
    @Unique
    private long quicraftTest$ticks;

    @Inject(method = "tick", at = @At("HEAD"))
    private void quicraftTest$lag(CallbackInfo ci) {
        long sleep = LAG_MS;
        if (STALL_MS > 0 && STALL_EVERY > 0 && ++quicraftTest$ticks % STALL_EVERY == 0) {
            sleep += STALL_MS;
        }
        if (sleep <= 0) {
            return;
        }
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
