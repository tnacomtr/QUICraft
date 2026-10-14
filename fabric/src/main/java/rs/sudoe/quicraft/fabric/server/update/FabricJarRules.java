// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        return !text.startsWith(".") && text.endsWith(".jar") && Files.isRegularFile(entry);
    }

    /**
     * Reads the {@code id} of {@code jar}'s {@code fabric.mod.json}: one entry, bounded. A missing or unparsable
     * descriptor is "not a mod" (empty); an unreadable archive or an oversized descriptor throws.
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
            return idFrom(bytes);
        }
    }

    private static Optional<String> idFrom(byte[] bytes) {
        try {
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return Optional.empty();
            }
            JsonElement id = root.getAsJsonObject().get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                return Optional.empty();
            }
            return Optional.of(id.getAsString());
        } catch (JsonParseException | IllegalStateException e) {
            return Optional.empty();
        }
    }
}
