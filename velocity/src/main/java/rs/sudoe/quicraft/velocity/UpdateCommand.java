// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * {@code /quicraft update ...} (docs/updater.md 3). Permission: the console always; a player needs
 * {@link #PERMISSION}; {@code reset-state} is the console only. The permission is checked here and again
 * by the updater's own dispatch for console-only commands, so a refusal never depends on one check.
 *
 * <p>The grammar is the updater's: everything after the {@code update} word is passed on, and an empty
 * array means "download, verify and stage". The five accepted forms are exactly {@code []},
 * {@code [check]}, {@code [check, -s]}, {@code [cancel]} and {@code [reset-state]}; anything else,
 * including another {@code update}, prints the fixed usage line. The subcommand is lower-cased once and
 * that one value is used for the allow check, the console-only check and the dispatch.
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
    private static final String SILENT = "-s";
    private static final Set<String> SUBCOMMANDS = Set.of(CHECK, CANCEL, RESET);

    private final Supplier<UpdateDispatcher> dispatcher;
    private final Logger logger;

    /**
     * @param dispatcher the running updater's dispatch, or null when it did not start
     * @param logger the plugin logger, for failures that must not reach the caller
     */
    UpdateCommand(Supplier<UpdateDispatcher> dispatcher, Logger logger) {
        this.dispatcher = dispatcher;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        try {
            run(invocation, source, invocation.arguments());
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            logger.warn("QUICraft: the update command failed", t);
            say(source, FAILED);
        }
    }

    /** The console, or a player holding {@value #PERMISSION}. */
    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource source = invocation.source();
        return source instanceof ConsoleCommandSource || source.hasPermission(PERMISSION);
    }

    private void run(Invocation invocation, CommandSource source, String[] args) {
        if (!hasPermission(invocation)) {
            say(source, DENIED);
            return;
        }
        if (args.length == 0 || !ROOT.equalsIgnoreCase(args[0])) {
            say(source, USAGE);
            return;
        }
        String[] rest = normalise(Arrays.copyOfRange(args, 1, args.length));
        if (!accepted(rest)) {
            say(source, USAGE);
            return;
        }
        boolean console = source instanceof ConsoleCommandSource;
        if (!console && rest.length == 1 && RESET.equals(rest[0])) {
            say(source, CONSOLE_ONLY);
            return;
        }
        UpdateDispatcher target = dispatcher.get();
        if (target == null) {
            say(source, NOT_RUNNING);
            return;
        }
        target.dispatch(rest, console, line -> say(source, line));
    }

    /** Lower-cases the subcommand once, so the allow check, the console-only check and the dispatch agree. */
    private static String[] normalise(String[] rest) {
        if (rest.length == 0) {
            return rest;
        }
        String[] normalised = rest.clone();
        normalised[0] = normalised[0].toLowerCase(Locale.ROOT);
        return normalised;
    }

    /** The five forms of docs/updater.md 3, with the words after {@code update} already lower-cased. */
    private static boolean accepted(String[] rest) {
        if (rest.length == 0) {
            return true;
        }
        if (rest.length == 1) {
            return SUBCOMMANDS.contains(rest[0]);
        }
        return rest.length == 2 && CHECK.equals(rest[0]) && SILENT.equals(rest[1]);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!hasPermission(invocation)) {
            return List.of();
        }
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
