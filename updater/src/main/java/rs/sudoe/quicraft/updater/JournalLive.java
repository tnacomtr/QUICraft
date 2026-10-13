// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/** The live jar as recorded in the journal: absolute real path, size, SHA-512 and version. */
public record JournalLive(String path, long size, String sha512, UpdateVersion version) {

    static final int MAX_PATH_CHARS = 4096;
    /** Largest integer a JSON reader can hold exactly (2^53 - 1), like the other integers in the updater. */
    static final long MAX_SIZE = 9007199254740991L;

    public JournalLive {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(version, "version");
        if (path.isEmpty() || path.length() > MAX_PATH_CHARS || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid path");
        }
        try {
            if (!Path.of(path).isAbsolute()) {
                throw new IllegalArgumentException("path is not absolute");
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid path", e);
        }
        if (size < 0 || size > MAX_SIZE) {
            throw new IllegalArgumentException("invalid size");
        }
        SharedChecks.requireLowerHex(sha512, 128, "sha512");
    }
}
