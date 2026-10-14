// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import rs.sudoe.quicraft.updater.HealthSignal;
import rs.sudoe.quicraft.updater.LiveJarDiscovery;
import rs.sudoe.quicraft.updater.UpdaterLog;
import rs.sudoe.quicraft.updater.UpdaterPlatform;

/**
 * The Velocity side of the updater (docs/updater.md 12.14): where the update directory and config file
 * are, how the running plugin jar is found, how health is judged, and how logs and admin notices are
 * routed. It holds no updater logic.
 *
 * <p>{@link #health()} is PROVISIONAL. Blocker 9 (docs/updater.md 10) is unresolved, so the verdict is only
 * "QUICraft's init did not fail, and the proxy finished its ProxyInitializeEvent handling". It does not
 * look at player joins.
 */
final class VelocityUpdaterPlatform implements UpdaterPlatform {
    static final String PLATFORM = "velocity";
    static final String CONFIG_FILE = "updater.properties";
    static final String UPDATE_DIR = "update";

    private static final Pattern MAJOR_MINOR = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.|-|$).*");

    private final String gameTarget;
    private final Logger logger;
    private final Path dataDirectory;
    private final Path updateDir;
    private final boolean updateDirExisted;
    private final VelocityLiveJarDiscovery discovery;
    private final VelocityUpdateNotifier notifier;
    private final CompletableFuture<Boolean> initVerdict = new CompletableFuture<>();

    /**
     * @param gameTarget the proxy's {@code major.minor}, from {@link #targetOf}
     * @param dataDirectory the plugin's data directory, {@code plugins/quicraft/} in production
     * @param pluginSource Velocity's reported source of this plugin
     * @param pluginsDir the proxy's plugin directory; Velocity's scan root
     * @param notifier the admin notice router
     */
    VelocityUpdaterPlatform(String gameTarget, Logger logger, Path dataDirectory,
            Supplier<Optional<Path>> pluginSource, Path pluginsDir, VelocityUpdateNotifier notifier) {
        this.gameTarget = gameTarget;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.updateDir = dataDirectory.resolve(UPDATE_DIR);
        // Read here, before the updater's own init can create the directory.
        this.updateDirExisted = existedBeforeStart(updateDir);
        this.discovery = new VelocityLiveJarDiscovery(pluginSource, pluginsDir);
        this.notifier = notifier;
    }

    /**
     * Anything at the path counts as "existed": a directory, a plain file, or a symbolic link (even a
     * dangling one), checked without following links. Any failure to check counts as "existed" too, so
     * the updater never treats an unreadable path as a fresh install.
     */
    private static boolean existedBeforeStart(Path dir) {
        try {
            return Files.exists(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * The proxy's {@code major.minor} from its version string, for example {@code 4.2} for
     * {@code 4.2.0-30}. Empty when the string has no such form; the updater then refuses to start,
     * because a target it cannot match would never offer an update.
     */
    static String targetOf(String proxyVersion) {
        if (proxyVersion == null) {
            return "";
        }
        Matcher m = MAJOR_MINOR.matcher(proxyVersion.trim());
        return m.matches() ? m.group(1) + "." + m.group(2) : "";
    }

    /**
     * Called by QuicraftVelocity once its own init has finished, with whether QUIC init failed. Completes
     * the health verdict that the updater's boot-pending check waits for.
     */
    void completeInit(boolean quicInitOk) {
        initVerdict.complete(quicInitOk);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String gameTarget() {
        return gameTarget;
    }

    @Override
    public Path updateDir() {
        return updateDir;
    }

    @Override
    public Path configFile() {
        return dataDirectory.resolve(CONFIG_FILE);
    }

    @Override
    public boolean updateDirExistedBeforeStart() {
        return updateDirExisted;
    }

    @Override
    public LiveJarDiscovery discovery() {
        return discovery;
    }

    @Override
    public HealthSignal health() {
        return timeout -> {
            CompletableFuture<Boolean> verdict = new CompletableFuture<>();
            initVerdict.thenAccept(verdict::complete);
            return verdict.completeOnTimeout(Boolean.FALSE, timeout.toMillis(), TimeUnit.MILLISECONDS);
        };
    }

    @Override
    public UpdaterLog log() {
        return new UpdaterLog() {
            @Override
            public void debug(String message) {
                logger.debug(message);
            }

            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }
        };
    }

    /**
     * Delivers the notice to the online admins only. The facade already writes the console/log line for
     * every notice, so nothing is logged here (docs/updater.md 6; {@link UpdaterPlatform#notifyAdmins}).
     */
    @Override
    public void notifyAdmins(String message) {
        notifier.announce(message);
    }
}
