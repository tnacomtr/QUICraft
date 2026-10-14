// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import rs.sudoe.quicraft.fabric.QuicraftFabric;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.HealthSignal;
import rs.sudoe.quicraft.updater.LiveJar;
import rs.sudoe.quicraft.updater.LiveJarDiscovery;
import rs.sudoe.quicraft.updater.OwnJarResources;
import rs.sudoe.quicraft.updater.UpdateRejectedException;
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

    public FabricUpdaterPlatform() {
        this.updateDir = QuicraftFabric.configDir().resolve("update");
        // Read before the facade's first step creates anything under config/quicraft.
        this.updateDirExisted = Files.exists(updateDir, LinkOption.NOFOLLOW_LINKS);
    }

    /** The platform name comes from the discovered jar's own build metadata, never from this class. */
    @Override
    public String platform() {
        LiveJar live = discovery.found();
        if (live == null) {
            throw new IllegalStateException("the live jar has not been discovered");
        }
        try {
            return DocumentParser.parseBuild(OwnJarResources.read(live.realPath()).buildJson()).platform();
        } catch (IOException | UpdateRejectedException e) {
            throw new IllegalStateException("the live jar's build metadata cannot be read", e);
        }
    }

    @Override
    public String gameTarget() {
        return FabricLoader.getInstance().getModContainer(GAME_MOD)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElseThrow(() -> new IllegalStateException("the game version is not known to the loader"));
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
     * PROVISIONAL (blocker 9 is open): this runs only after the dedicated server's init returned true, so the
     * verdict is "healthy" at once. It does not yet watch the first ticks or a crash after start.
     */
    @Override
    public HealthSignal health() {
        return timeout -> CompletableFuture.completedFuture(Boolean.TRUE);
    }

    @Override
    public UpdaterLog log() {
        return LOG;
    }

    @Override
    public void notifyAdmins(String message) {
        LOG.info(message);
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
