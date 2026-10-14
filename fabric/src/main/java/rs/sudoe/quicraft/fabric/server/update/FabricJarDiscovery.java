// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import rs.sudoe.quicraft.updater.LiveJar;
import rs.sudoe.quicraft.updater.LiveJarDiscovery;
import rs.sudoe.quicraft.updater.LiveJarValidator;
import rs.sudoe.quicraft.updater.OwnJarVersion;
import rs.sudoe.quicraft.updater.UpdateRejectedException;
import rs.sudoe.quicraft.updater.UpdateVersion;

/**
 * Finds QUICraft's own jar through the loader (docs/updater.md 12.14, Fabric). Anything that is not a single
 * plain jar path is refused: nested jars, unknown origins and development directories.
 */
final class FabricJarDiscovery implements LiveJarDiscovery {
    static final String OWN_ID = "quicraft";
    private static final String MODS_PROPERTY = "fabric.modsFolder";

    @Override
    public LiveJar discover() throws IOException, UpdateRejectedException {
        ModContainer mod = FabricLoader.getInstance().getModContainer(OWN_ID)
                .orElseThrow(() -> new UpdateRejectedException("QUICraft is not known to the loader"));
        ModOrigin origin = mod.getOrigin();
        if (origin.getKind() != ModOrigin.Kind.PATH) {
            throw new UpdateRejectedException("QUICraft is not loaded from a plain jar path");
        }
        List<Path> paths = origin.getPaths();
        if (paths.size() != 1) {
            throw new UpdateRejectedException("QUICraft is loaded from more than one path");
        }
        return LiveJarValidator.validate(paths.get(0), modsDirectory(), FabricJarRules::loaderWouldLoad,
                FabricJarRules::idOf, OWN_ID, FabricJarDiscovery::versionOf);
    }

    /** The loader's mods directory: {@code fabric.modsFolder} when set, else {@code <gameDir>/mods}. */
    static Path modsDirectory() {
        String configured = System.getProperty(MODS_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        return FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath();
    }

    /**
     * The validator takes a function that cannot throw checked exceptions. A development build keeps its own
     * unchecked {@code DevelopmentBuildException}, which the validator rethrows unchanged so the facade names
     * that reason; any other unusable metadata becomes a runtime failure, which the validator turns into its
     * generic "cannot read the live jar version" refusal. An unreadable jar is refused the same way.
     */
    private static UpdateVersion versionOf(Path jar) {
        try {
            return OwnJarVersion.read(jar);
        } catch (UpdateRejectedException e) {
            throw new IllegalStateException("the live jar's build metadata is unusable", e);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the live jar version", e);
        }
    }
}
