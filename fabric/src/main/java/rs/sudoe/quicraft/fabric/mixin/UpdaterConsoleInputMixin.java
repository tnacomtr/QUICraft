// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.update.ConsoleInputFlag;

/**
 * Marks real console input. The dedicated server drains its queued console commands in this method and runs
 * every one of them with the server as its command source, and a datapack function runs with the server as
 * its source too. The source alone therefore cannot prove console input (docs/updater.md section 3, 12.8,
 * {@code reset-state}); this mark can.
 *
 * <p>Marked is the exact {@code CommandSourceStack} the drain hands to {@code performPrefixedCommand}, for as
 * long as that one call runs, and nothing else. A mark on the thread would be wrong: the server thread runs
 * unrelated queued work while a drained command is still running (a console {@code /reload} waits on the task
 * queue), and on an exceptional exit the drain's return never happens. Wrapping the call marks the source for
 * exactly the command it belongs to, keeps the mark while that command runs, and restores the previous mark on
 * every exit, exception or not.
 *
 * <p>Our own bookkeeping fails safe: a failure is logged once and the original call still runs exactly once,
 * with no mark. A target this mixin cannot find leaves the mark unset, so the console stays unproven and
 * {@code reset-state} is refused: fail closed.
 */
@Mixin(DedicatedServer.class)
abstract class UpdaterConsoleInputMixin {
    @WrapOperation(method = "handleConsoleInputs", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;performPrefixedCommand(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V"))
    private void quicraft$markConsoleInput(Commands commands, CommandSourceStack source, String command,
            Operation<Void> original) {
        Object previous = null;
        boolean marked = false;
        try {
            Hooks.enter("updater console input");
            previous = ConsoleInputFlag.mark(source);
            marked = true;
        } catch (Throwable t) {
            // Fail closed: nothing was marked, so nothing is restored below.
            Hooks.failed("updater console input", t);
        }
        try {
            original.call(commands, source, command);
        } finally {
            if (marked) {
                try {
                    ConsoleInputFlag.restore(previous);
                } catch (Throwable t) {
                    Hooks.failed("updater console input", t);
                }
            }
        }
    }
}
