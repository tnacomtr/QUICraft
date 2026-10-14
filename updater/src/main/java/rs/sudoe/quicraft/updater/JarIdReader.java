// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the plugin or mod id of a jar (docs/updater.md 12.14). The loader adapter supplies it:
 * {@code velocity-plugin.json} {@code id} or {@code fabric.mod.json} {@code id}.
 *
 * <p>Mandatory limits for every implementation:
 * <ul>
 *   <li>The descriptor is read with a cap of 64 KiB of decompressed bytes. The entry's declared
 *       size is not trusted; the reader counts the bytes it actually reads.</li>
 *   <li>No full-archive scan: the reader opens the central directory and reads only the one
 *       descriptor entry.</li>
 *   <li>Fail closed: any entry over a limit, or any read failure, throws {@link IOException}.
 *       {@link LiveJarValidator} refuses the live jar on that exception, so a jar that cannot be
 *       inspected can never count as "not a duplicate".</li>
 * </ul>
 */
@FunctionalInterface
public interface JarIdReader {

    /**
     * @return the id, or empty when the jar is not a plugin or mod or its descriptor is missing or
     *     unparsable
     * @throws IOException when the jar cannot be read, or a limit above is exceeded
     */
    Optional<String> idOf(Path jar) throws IOException;
}
