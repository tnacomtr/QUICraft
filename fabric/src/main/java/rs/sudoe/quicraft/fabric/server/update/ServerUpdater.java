// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import com.mojang.brigadier.context.CommandContext;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.commands.CommandSourceStack;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.QuicraftFabric;
import rs.sudoe.quicraft.fabric.mixin.CommandSourceStackAccessor;
import rs.sudoe.quicraft.updater.UpdaterRuntime;

/**
 * The dedicated server's updater hooks (docs/updater.md section 3, 6, 7). Every entry point catches
 * everything, so a failure here never reaches the server's startup, stop or join path.
 */
public final class ServerUpdater {
    private static final AdminNotices<UUID> NOTICES = new AdminNotices<>();
    private static volatile @Nullable MinecraftServer server;
    private static volatile @Nullable UpdaterRuntime runtime;
    private static volatile boolean commandsRegistered;

    private ServerUpdater() {
    }

    /** Hook: the dedicated server finished its init. Starts the updater; never throws. */
    public static void start(DedicatedServer minecraft) {
        try {
            Hooks.enter("updater start");
            if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER || runtime != null) {
                return;
            }
            server = minecraft;
            runtime = UpdaterRuntime.start(new FabricUpdaterPlatform());
            if (!commandsRegistered) {
                QuicraftFabric.LOG.warn("QUICraft: the update command was not registered; the updater is "
                        + "running without its command");
            }
        } catch (Throwable t) {
            Hooks.failed("updater start", t);
        }
    }

    /** Hook: the dedicated server stops. Shuts the updater down; never throws. */
    public static void stop() {
        try {
            Hooks.enter("updater stop");
            UpdaterRuntime r = runtime;
            if (r != null) {
                r.shutdown();
            }
        } catch (Throwable t) {
            Hooks.failed("updater stop", t);
        }
    }

    /** Hook: a player was placed. Gives a pending notice to an admin who has not seen it; never throws. */
    public static void join(ServerPlayer player) {
        try {
            Hooks.enter("updater join");
            if (!isAdmin(player)) {
                return;
            }
            NOTICES.take(player.getUUID()).ifPresent(message -> player.sendSystemMessage(Component.literal(message)));
        } catch (Throwable t) {
            Hooks.failed("updater join", t);
        }
    }

    /** Hook: the update command was registered on the dedicated server's dispatcher. */
    static void markCommandsRegistered() {
        commandsRegistered = true;
    }

    /**
     * Runs one command line for {@code source}. Answers go back on the server thread. Never throws.
     *
     * @param words the words after {@code /quicraft update}
     */
    public static int command(CommandContext<CommandSourceStack> context, String[] words) {
        CommandSourceStack source = context.getSource();
        try {
            Hooks.enter("updater command");
            UpdaterRuntime rt = runtime;
            MinecraftServer s = server;
            if (rt == null || s == null) {
                reply(source, "updater is not running on this server");
                return 1;
            }
            rt.dispatch(words, isConsole(source), line -> onServerThread(s, () -> reply(source, line)));
        } catch (Throwable t) {
            Hooks.failed("updater command", t);
            reply(source, "updater command failed; see the server log");
        }
        return 1;
    }

    /** Called by the platform from any thread: queues the notice to the server thread, then sends it. */
    static void notifyAdmins(String message) {
        MinecraftServer s = server;
        if (s == null) {
            return;
        }
        onServerThread(s, () -> {
            try {
                NOTICES.replace(message);
                for (ServerPlayer player : s.getPlayerList().getPlayers()) {
                    if (isAdmin(player)) {
                        NOTICES.take(player.getUUID()).ifPresent(m -> player.sendSystemMessage(Component.literal(m)));
                    }
                }
            } catch (Throwable t) {
                Hooks.failed("updater notice", t);
            }
        });
    }

    /** The server console, as opposed to RCON, a command block or a player. */
    static boolean isConsole(CommandSourceStack source) {
        return ((CommandSourceStackAccessor) (Object) source).quicraft$source() instanceof MinecraftServer;
    }

    static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    static boolean isAdmin(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    private static void reply(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void onServerThread(MinecraftServer s, Runnable task) {
        if (s.isSameThread()) {
            task.run();
        } else {
            s.execute(task);
        }
    }
}
