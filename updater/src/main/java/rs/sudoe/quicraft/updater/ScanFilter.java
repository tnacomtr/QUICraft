// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.file.Path;

/**
 * The loader's own directory filter (docs/updater.md 12.14): true when the loader would load the
 * entry as a plugin or mod. The adapter encodes the filter of its loader.
 */
@FunctionalInterface
public interface ScanFilter {

    /** @param entry a direct child of the scan directory */
    boolean loaderWouldLoad(Path entry);
}
