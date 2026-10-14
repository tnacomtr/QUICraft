// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Fabric loader's rules for a mods directory entry and for a mod id (docs/updater.md 12.14), as the
 * {@code ScanFilter} and {@code JarIdReader} the validator takes.
 */
final class FabricJarRules {
    /** The cap on the descriptor, counted while reading (docs/updater.md 12.14, 64 KiB). */
    static final int DESCRIPTOR_MAX_BYTES = 64 * 1024;
    private static final String DESCRIPTOR = "fabric.mod.json";

    private FabricJarRules() {
    }

    /** Regular file (links followed, as the loader does), not hidden, name ends {@code .jar}. */
    static boolean loaderWouldLoad(Path entry) {
        Path name = entry.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString();
        if (text.startsWith(".") || !text.endsWith(".jar") || !Files.isRegularFile(entry)) {
            return false;
        }
        return !hidden(entry);
    }

    /**
     * The loader's hidden test. When the platform cannot answer, the entry counts as one the loader would
     * load: the conservative choice, because a duplicate scan must not miss a second QUICraft jar.
     */
    private static boolean hidden(Path entry) {
        try {
            return Files.isHidden(entry);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Reads the id that {@code jar}'s {@code fabric.mod.json} answers to: one entry, bounded. A jar with no
     * descriptor is "not a mod" (empty), and so is a descriptor that is an object with nothing the scan
     * recognizes. An unreadable archive, an oversized descriptor, or a descriptor that is present but cannot
     * be parsed throws, so the validator refuses a jar it cannot inspect. A mod that only declares the
     * QUICraft id in its {@code provides} counts as QUICraft too, so the duplicate scan sees a second entry
     * the loader would load.
     */
    static Optional<String> idOf(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(DESCRIPTOR);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readNBytes(DESCRIPTOR_MAX_BYTES + 1);
            }
            if (bytes.length > DESCRIPTOR_MAX_BYTES) {
                throw new IOException("fabric.mod.json is larger than the limit");
            }
            return FabricModDescriptor.idOf(bytes, FabricJarDiscovery.OWN_ID);
        }
    }
}
