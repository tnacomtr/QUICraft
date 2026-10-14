// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /quicraft update [check [-s] | cancel | reset-state]} (docs/updater.md section 3). OP level 3 for the
 * whole command; {@code reset-state} is console only. The words are passed to the facade as they are typed.
 */
public final class UpdaterCommands {
    private UpdaterCommands() {
    }

    /** Registers the command on the dedicated server's dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> update = Commands.literal("update")
                // Bare "/quicraft update": downloads, verifies and stages the new jar (docs/updater.md section 3).
                .executes(c -> ServerUpdater.command(c, new String[0]))
                .then(Commands.literal("check")
                        .executes(c -> ServerUpdater.command(c, new String[] {"check"}))
                        .then(Commands.literal("-s")
                                .executes(c -> ServerUpdater.command(c, new String[] {"check", "-s"}))))
                .then(Commands.literal("cancel")
                        .executes(c -> ServerUpdater.command(c, new String[] {"cancel"})))
                .then(Commands.literal("reset-state")
                        .requires(ServerUpdater::isConsole)
                        .executes(c -> ServerUpdater.command(c, new String[] {"reset-state"})));
        dispatcher.register(Commands.literal("quicraft")
                .requires(ServerUpdater::isAdmin)
                .then(update));
        ServerUpdater.markCommandsRegistered();
    }
}
