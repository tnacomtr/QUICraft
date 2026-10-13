// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The live server jar as found by a {@link LiveJarDiscovery}: the resolved real path, the directory
 * it was scanned from, its size, SHA-512 (computed by discovery) and the version read from disk.
 */
public record LiveJar(Path realPath, Path realScanDirectory, long size, String sha512,
        UpdateVersion onDiskVersion) {

    /** Largest integer a JSON reader can hold exactly (2^53 - 1), like the other sizes in the updater. */
    static final long MAX_SIZE = JournalLive.MAX_SIZE;

    public LiveJar {
        Objects.requireNonNull(realPath, "realPath");
        Objects.requireNonNull(realScanDirectory, "realScanDirectory");
        Objects.requireNonNull(onDiskVersion, "onDiskVersion");
        if (!realPath.isAbsolute() || !realScanDirectory.isAbsolute()) {
            throw new IllegalArgumentException("live jar paths must be absolute");
        }
        if (size <= 0 || size > MAX_SIZE) {
            throw new IllegalArgumentException("invalid size");
        }
        SharedChecks.requireLowerHex(sha512, 128, "sha512");
    }
}
