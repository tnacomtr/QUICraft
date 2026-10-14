// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.fabric.QuicraftFabric;
import rs.sudoe.quicraft.updater.HealthSignal;
import rs.sudoe.quicraft.updater.LiveJarDiscovery;
import rs.sudoe.quicraft.updater.UpdaterLog;
import rs.sudoe.quicraft.updater.UpdaterPlatform;

/**
 * The Fabric dedicated server's side of the updater (docs/updater.md 12.14). Created once per start, before
 * anything creates the update directory. Adapter only: the facade does the checks.
 */
public final class FabricUpdaterPlatform implements UpdaterPlatform {
    private static final String GAME_MOD = "minecraft";

    private final FabricJarDiscovery discovery = new FabricJarDiscovery();
    private final Path updateDir;
    private final boolean updateDirExisted;
    /** Derived once, in the constructor, because the facade asks for the platform more than once. */
    private final @Nullable String platform;

    public FabricUpdaterPlatform() {
        this.updateDir = QuicraftFabric.configDir().resolve("update");
        // Read before the facade's first step creates anything under config/quicraft.
        this.updateDirExisted = Files.exists(updateDir, LinkOption.NOFOLLOW_LINKS);
        this.platform = FabricPlatformNames.platformOrNull(gameVersion());
    }

    /**
     * The platform this jar was built for, taken from the loaded Minecraft version alone. It never comes
     * from the discovered jar: the facade compares that jar's own build metadata against this value. A game
     * version this project ships no jar for throws, so the facade disables the updater.
     */
    @Override
    public String platform() {
        String cached = platform;
        if (cached == null) {
            throw new IllegalStateException(
                    "no QUICraft platform for the loaded Minecraft version " + gameVersion());
        }
        return cached;
    }

    /** @return the version the loader reports for {@code minecraft} */
    private static String gameVersion() {
        return FabricLoader.getInstance().getModContainer(GAME_MOD)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElseThrow(() -> new IllegalStateException("the game version is not known to the loader"));
    }

    @Override
    public String gameTarget() {
        return gameVersion();
    }

    @Override
    public Path updateDir() {
        return updateDir;
    }

    @Override
    public Path configFile() {
        return QuicraftFabric.configDir().resolve("updater.properties");
    }

    @Override
    public boolean updateDirExistedBeforeStart() {
        return updateDirExisted;
    }

    @Override
    public LiveJarDiscovery discovery() {
        return discovery;
    }

    /**
     * The alpha's settled health criterion (docs/updater.md section 10, blocker 9): the dedicated server
     * finished starting. This platform is only reached after the server's init returned true, so the
     * verdict is "healthy" at once. A crash or a failed start after this point is not detected, and the
     * backup of the previous generation is kept until this verdict is recorded (section 5).
     */
    @Override
    public HealthSignal health() {
        return timeout -> CompletableFuture.completedFuture(Boolean.TRUE);
    }

    @Override
    public UpdaterLog log() {
        return LOG;
    }

    /** The facade writes the log line for every notice, so this delivers to the admins only. */
    @Override
    public void notifyAdmins(String message) {
        ServerUpdater.notifyAdmins(message);
    }

    private static final UpdaterLog LOG = new UpdaterLog() {
        @Override
        public void debug(String message) {
            QuicraftFabric.LOG.debug("QUICraft updater: {}", message);
        }

        @Override
        public void info(String message) {
            QuicraftFabric.LOG.info("QUICraft updater: {}", message);
        }

        @Override
        public void warn(String message) {
            QuicraftFabric.LOG.warn("QUICraft updater: {}", message);
        }
    };
}
