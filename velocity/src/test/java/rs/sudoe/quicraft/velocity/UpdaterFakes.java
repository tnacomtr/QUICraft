// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.ProxyVersion;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.slf4j.Logger;
import rs.sudoe.quicraft.updater.HealthSignal;
import rs.sudoe.quicraft.updater.LiveJarDiscovery;
import rs.sudoe.quicraft.updater.UpdateRejectedException;
import rs.sudoe.quicraft.updater.UpdaterLog;
import rs.sudoe.quicraft.updater.UpdaterPlatform;
import rs.sudoe.quicraft.updater.UpdaterRuntime;

/**
 * Small fakes for the Velocity interfaces the updater adapter uses. Dynamic proxies answer every method
 * of the interface: {@code sendMessage} records the text, {@code hasPermission} checks a set, and the
 * rest return a fixed value. No Velocity proxy is booted.
 */
final class UpdaterFakes {
    private UpdaterFakes() {
    }

    /** A fake source that records every line it is sent. */
    static final class Recorder {
        final List<String> lines = new ArrayList<>();
    }

    static CommandSource console(Recorder out) {
        return (CommandSource) proxy(ConsoleCommandSource.class, out, Set.of(), null);
    }

    static CommandSource player(Recorder out, Set<String> permissions) {
        return (CommandSource) proxy(Player.class, out, permissions, UUID.randomUUID());
    }

    /** A fake player, for notice delivery; the recorder gets the notices it receives. */
    static Player notifiedPlayer(Recorder out, Set<String> permissions, UUID id) {
        return (Player) proxy(Player.class, out, permissions, id);
    }

    /** A fake player whose permission decision is arbitrary, for checks that must not run under a lock. */
    static Player notifiedPlayer(Recorder out, Predicate<String> permission, UUID id) {
        return (Player) proxy(Player.class, out, permission, id);
    }

    static SimpleCommand.Invocation invocation(CommandSource source, String... args) {
        return (SimpleCommand.Invocation) Proxy.newProxyInstance(
                UpdaterFakes.class.getClassLoader(),
                new Class<?>[] {SimpleCommand.Invocation.class},
                (self, method, a) -> switch (method.getName()) {
                    case "source" -> source;
                    case "arguments" -> args.clone();
                    case "alias" -> "quicraft";
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * A fake proxy for the plugin's init. {@code getVersion()} answers with another dynamic proxy (its
     * {@code getVersion()} gives null, which the adapter maps to an empty game target); everything else
     * answers with a default, so a failure in the init path surfaces as a real exception.
     */
    static ProxyServer proxyServer() {
        return (ProxyServer) Proxy.newProxyInstance(UpdaterFakes.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class},
                (self, method, args) -> {
                    if ("getVersion".equals(method.getName())) {
                        return new ProxyVersion("Velocity", "PaperMC", "4.2.0-30");
                    }
                    Class<?> type = method.getReturnType();
                    if (type.isInterface()) {
                        return Proxy.newProxyInstance(UpdaterFakes.class.getClassLoader(),
                                new Class<?>[] {type}, (nested, nestedMethod, nestedArgs) ->
                                        defaultValue(nestedMethod.getReturnType()));
                    }
                    return defaultValue(type);
                });
    }

    /** A logger that records only the fact that a level was called. */
    static Logger recordingLogger(List<String> lines) {
        return (Logger) Proxy.newProxyInstance(UpdaterFakes.class.getClassLoader(),
                new Class<?>[] {Logger.class},
                (self, method, args) -> {
                    switch (method.getName()) {
                        case "trace", "debug", "info", "warn", "error" -> lines.add(method.getName());
                        default -> {
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** Records every dispatch, so the command grammar can be checked without a live updater. */
    static final class RecordingDispatcher implements UpdateDispatcher {
        final List<String[]> calls = new ArrayList<>();
        final List<Boolean> consoles = new ArrayList<>();

        @Override
        public UpdaterRuntime.Reply dispatch(String[] args, boolean console, Consumer<String> out) {
            calls.add(args.clone());
            consoles.add(console);
            return UpdaterRuntime.Reply.DONE;
        }
    }

    private static Object proxy(Class<?> type, Recorder out, Set<String> permissions, UUID id) {
        return proxy(type, out, permissions::contains, id);
    }

    private static Object proxy(Class<?> type, Recorder out, Predicate<String> permissions, UUID id) {
        return Proxy.newProxyInstance(UpdaterFakes.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> {
                    switch (method.getName()) {
                        case "sendMessage":
                            if (args != null && args.length == 1 && args[0] instanceof Component c) {
                                out.lines.add(text(c));
                            }
                            return null;
                        case "hasPermission":
                            return permissions.test((String) args[0]);
                        case "getUniqueId":
                            return id;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    /** A runtime whose discovery refuses, so it starts disabled and answers with the reason. */
    static UpdaterRuntime disabledRuntime(Path dir) {
        UpdaterPlatform platform = new UpdaterPlatform() {
            @Override
            public String platform() {
                return "velocity";
            }

            @Override
            public String gameTarget() {
                return "4.2";
            }

            @Override
            public Path updateDir() {
                return dir.resolve("update");
            }

            @Override
            public Path configFile() {
                return dir.resolve("updater.properties");
            }

            @Override
            public boolean updateDirExistedBeforeStart() {
                return false;
            }

            @Override
            public LiveJarDiscovery discovery() {
                return () -> {
                    throw new UpdateRejectedException("no jar");
                };
            }

            @Override
            public HealthSignal health() {
                return timeout -> CompletableFuture.completedFuture(true);
            }

            @Override
            public UpdaterLog log() {
                return new UpdaterLog() {
                    @Override
                    public void debug(String message) {
                    }

                    @Override
                    public void info(String message) {
                    }

                    @Override
                    public void warn(String message) {
                    }
                };
            }

            @Override
            public void notifyAdmins(String message) {
            }
        };
        return UpdaterRuntime.start(platform);
    }

    private static String text(Component c) {
        if (c instanceof TextComponent t) {
            return t.content();
        }
        throw new IllegalArgumentException("only plain text components are used in these tests");
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }
}
