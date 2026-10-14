// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.update.ConsoleInputFlag;

/**
 * Marks real console input. The dedicated server drains its queued console commands in this method and
 * runs every one of them with the server as its command source, and a datapack function runs with the
 * server as its source too. The source alone therefore cannot prove console input (docs/updater.md
 * section 3, 12.8, {@code reset-state}); this mark can.
 *
 * <p>The mark lasts for the whole drain and is cleared on the way out, whether or not a drained command
 * threw, and both hooks fail safe. A target this mixin cannot find leaves the mark unset, so the console
 * stays unproven and {@code reset-state} is refused: fail closed.
 */
@Mixin(DedicatedServer.class)
abstract class UpdaterConsoleInputMixin {
    @Inject(method = "handleConsoleInputs", at = @At("HEAD"))
    private void quicraft$enterConsoleInput(CallbackInfo ci) {
        try {
            Hooks.enter("updater console input");
            ConsoleInputFlag.enter();
        } catch (Throwable t) {
            // Fail closed: never leave a half-set mark behind.
            ConsoleInputFlag.exit();
            Hooks.failed("updater console input", t);
        }
    }

    @Inject(method = "handleConsoleInputs", at = @At("RETURN"))
    private void quicraft$exitConsoleInput(CallbackInfo ci) {
        try {
            Hooks.enter("updater console input");
        } catch (Throwable t) {
            Hooks.failed("updater console input", t);
        } finally {
            try {
                ConsoleInputFlag.exit();
            } catch (Throwable t) {
                Hooks.failed("updater console input", t);
            }
        }
    }
}
