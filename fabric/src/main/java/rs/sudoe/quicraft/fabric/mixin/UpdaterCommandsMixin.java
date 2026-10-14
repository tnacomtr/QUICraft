// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.mixin;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.update.UpdaterCommands;

/** Dedicated server only: adds {@code /quicraft update} once the command dispatcher is built. */
@Mixin(Commands.class)
abstract class UpdaterCommandsMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void quicraft$registerUpdateCommand(Commands.CommandSelection selection, CommandBuildContext context,
            CallbackInfo ci) {
        try {
            Hooks.enter("updater commands");
            if (selection == Commands.CommandSelection.DEDICATED) {
                UpdaterCommands.register(((Commands) (Object) this).getDispatcher());
            }
        } catch (Throwable t) {
            Hooks.failed("updater commands", t);
        }
    }
}
