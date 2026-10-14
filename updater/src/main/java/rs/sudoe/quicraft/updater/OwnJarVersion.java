// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the version of a QUICraft jar from its {@code META-INF/quicraft/build.json}. The entry must
 * be present exactly once and within {@link #MAX_BYTES}; its content goes through
 * {@link DocumentParser#parseBuild}.
 */
public final class OwnJarVersion {
    /** Cap on the raw entry, checked while reading; the parser enforces its own, smaller cap. */
    static final int MAX_BYTES = 64 * 1024;

    private OwnJarVersion() {
    }

    /**
     * @param jar a jar file
     * @return the version recorded in the jar's build metadata
     * @throws IOException when the file is not a readable zip
     * @throws UpdateRejectedException when the metadata is missing, duplicated, oversized or invalid
     */
    public static UpdateVersion read(Path jar) throws IOException, UpdateRejectedException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            requireSingleEntry(zip);
            ZipEntry entry = zip.getEntry(ArtifactVerifier.BUILD_ENTRY);
            if (entry == null || entry.isDirectory()) {
                throw new UpdateRejectedException("build metadata entry missing");
            }
            return DocumentParser.parseBuild(readCapped(zip, entry)).version();
        }
    }

    private static void requireSingleEntry(ZipFile zip) throws UpdateRejectedException {
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            if (ArtifactVerifier.BUILD_ENTRY.equals(entries.nextElement().getName())) {
                count++;
            }
        }
        if (count != 1) {
            throw new UpdateRejectedException("build metadata missing or duplicated");
        }
    }

    private static byte[] readCapped(ZipFile zip, ZipEntry entry)
            throws IOException, UpdateRejectedException {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new UpdateRejectedException("build metadata too large");
            }
            return bytes;
        }
    }
}
