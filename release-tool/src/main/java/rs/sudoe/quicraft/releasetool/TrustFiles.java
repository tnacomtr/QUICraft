// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.nio.file.Path;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.UpdateDocuments;
import rs.sudoe.quicraft.updater.UpdateRejectedException;

/**
 * Reads and parses the owner's public trust file (docs/updater.md 12.2).
 *
 * <p>The default is the owner-managed file the release guard also uses; a release run always passes
 * {@code --trust} explicitly. The file holds public keys only, and the tool never reads a private
 * key.
 */
final class TrustFiles {

    /** Default {@code --trust} path, relative to the repository root. */
    static final String DEFAULT_PATH = "updater/release-trust/updater-trust.json";

    private TrustFiles() {
    }

    /** The default trust path as a path. */
    static Path defaultPath() {
        return Path.of(DEFAULT_PATH);
    }

    /**
     * Reads the trust file, enforcing the 8192-byte cap.
     *
     * @throws ToolException if it cannot be read or is too large
     */
    static byte[] readBytes(Path file) throws ToolException {
        return ToolFiles.readCapped(file, Limits.TRUST_MAX_BYTES);
    }

    /**
     * Parses trust bytes with the updater's own parser.
     *
     * @throws ToolException if the updater rejects the file
     */
    static UpdateDocuments.Trust parse(byte[] bytes) throws ToolException {
        try {
            return DocumentParser.parseTrust(bytes);
        } catch (UpdateRejectedException e) {
            throw new ToolException("trust file rejected by the updater parser: " + e.getMessage(), e);
        }
    }

    /**
     * Reads and parses a trust file in one step.
     *
     * @throws ToolException if it cannot be read or the updater rejects it
     */
    static UpdateDocuments.Trust load(Path file) throws ToolException {
        return parse(readBytes(file));
    }
}
