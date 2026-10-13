// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;

/**
 * Finds the running server's own jar (docs/updater.md 12.14). Platform implementations come in
 * Wave 4. Any failure means the updater is disabled for this start.
 */
public interface LiveJarDiscovery {

    /**
     * @return the live jar with its real path and SHA-512
     * @throws IOException when the jar cannot be read
     * @throws UpdateRejectedException when the jar is not acceptable (for example a link or an
     *     unknown location)
     */
    LiveJar discover() throws IOException, UpdateRejectedException;
}
