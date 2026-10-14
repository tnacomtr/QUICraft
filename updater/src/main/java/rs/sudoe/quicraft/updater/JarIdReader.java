// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the plugin or mod id of a jar (docs/updater.md 12.14). The loader adapter supplies it:
 * {@code velocity-plugin.json} {@code id} or {@code fabric.mod.json} {@code id}.
 */
@FunctionalInterface
public interface JarIdReader {

    /**
     * @return the id, or empty when the jar is not a plugin or mod or its descriptor is missing or
     *     unparsable
     * @throws IOException when the jar cannot be read; the validator treats that as a refusal
     */
    Optional<String> idOf(Path jar) throws IOException;
}
