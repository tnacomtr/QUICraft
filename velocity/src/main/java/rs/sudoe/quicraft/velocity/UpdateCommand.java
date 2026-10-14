// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import rs.sudoe.quicraft.updater.UpdaterRuntime;

/**
 * {@code /quicraft update ...} (docs/updater.md 3). Permission: the console always; a player needs
 * {@link #PERMISSION}; {@code reset-state} is the console only. The permission is checked here and again
 * by the updater's own dispatch for console-only commands, so a refusal never depends on one check.
 *
 * <p>The updater's answer lines may arrive on its worker thread; the sources accept them there.
 */
final class UpdateCommand implements SimpleCommand {
    static final String PERMISSION = "quicraft.admin.update";
    static final String USAGE = "usage: /quicraft update check [-s] | update | cancel | reset-state";
    static final String DENIED = "you do not have permission to run this command";
    static final String CONSOLE_ONLY = "reset-state is available only from the server console";
    static final String NOT_RUNNING = "the QUICraft updater is not running; see the server log";
    static final String FAILED = "the QUICraft update command failed; see the server log";

    private static final String ROOT = "update";
    private static final String CHECK = "check";
    private static final String CANCEL = "cancel";
    private static final String RESET = "reset-state";
    private static final Set<String> SUBCOMMANDS = Set.of(CHECK, CANCEL, RESET);

    private final Supplier<UpdaterRuntime> runtime;
    private final Logger logger;

    /**
     * @param runtime the running updater, or null when it did not start
     * @param logger the plugin logger, for failures that must not reach the caller
     */
    UpdateCommand(Supplier<UpdaterRuntime> runtime, Logger logger) {
        this.runtime = runtime;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        try {
            run(source, invocation.arguments());
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            logger.warn("QUICraft: the update command failed", t);
            say(source, FAILED);
        }
    }

    private void run(CommandSource source, String[] args) {
        if (args.length == 0 || !ROOT.equalsIgnoreCase(args[0])) {
            say(source, USAGE);
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (rest.length > 0 && !SUBCOMMANDS.contains(rest[0].toLowerCase(Locale.ROOT))) {
            say(source, USAGE);
            return;
        }
        boolean console = source instanceof ConsoleCommandSource;
        if (!console && !source.hasPermission(PERMISSION)) {
            say(source, DENIED);
            return;
        }
        if (!console && rest.length > 0 && RESET.equals(rest[0])) {
            say(source, CONSOLE_ONLY);
            return;
        }
        UpdaterRuntime r = runtime.get();
        if (r == null) {
            say(source, NOT_RUNNING);
            return;
        }
        r.dispatch(rest, console, line -> say(source, line));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        boolean console = invocation.source() instanceof ConsoleCommandSource;
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0];
            if (ROOT.startsWith(typed.toLowerCase(Locale.ROOT))) {
                out.add(ROOT);
            }
        } else if (args.length == 2 && ROOT.equalsIgnoreCase(args[0])) {
            for (String word : List.of(CHECK, CANCEL, RESET)) {
                if (word.equals(RESET) && !console) {
                    continue;
                }
                if (word.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(word);
                }
            }
        }
        return out;
    }

    private static void say(CommandSource source, String line) {
        source.sendMessage(Component.text(line));
    }
}
