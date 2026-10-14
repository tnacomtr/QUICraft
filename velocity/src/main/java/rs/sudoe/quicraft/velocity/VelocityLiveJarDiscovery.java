// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import rs.sudoe.quicraft.updater.LiveJar;
import rs.sudoe.quicraft.updater.LiveJarDiscovery;
import rs.sudoe.quicraft.updater.LiveJarValidator;
import rs.sudoe.quicraft.updater.OwnJarVersion;
import rs.sudoe.quicraft.updater.StrictJson;
import rs.sudoe.quicraft.updater.UpdateRejectedException;

/**
 * Finds the running plugin jar for the Velocity adapter (docs/updater.md 12.14).
 *
 * <p>The reported source comes from Velocity's {@code PluginDescription.getSource()}. Its parent is not
 * trusted: the scan directory is always {@code plugins/} relative to the proxy's working directory, as
 * Velocity itself scans it (12.14: one level, {@code File.isFile()} and a {@code .jar} name, no
 * hidden-file filter). A {@code .quicraft-x.jar} in that directory is therefore counted as a QUICraft jar
 * and refuses the update, because Velocity would load it too. A {@code .jar.bak} is ignored, because
 * Velocity does not load it. The validator then refuses a symlinked jar, a jar outside the scan
 * directory and a second jar carrying the {@code quicraft} id.
 */
final class VelocityLiveJarDiscovery implements LiveJarDiscovery {
    static final String OWN_ID = "quicraft";
    static final String DESCRIPTOR = "velocity-plugin.json";
    /** Cap on the descriptor's decompressed bytes; the entry's declared size is not trusted. */
    static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final int MAX_STRING_CHARS = 512;
    private static final String JAR_SUFFIX = ".jar";

    private final Supplier<Optional<Path>> source;
    private final Path scanDirectory;

    /**
     * @param source Velocity's reported source of this plugin; empty when Velocity does not know it
     * @param scanDirectory the proxy's plugin directory, {@code Path.of("plugins")} in production
     */
    VelocityLiveJarDiscovery(Supplier<Optional<Path>> source, Path scanDirectory) {
        this.source = source;
        this.scanDirectory = scanDirectory;
    }

    @Override
    public LiveJar discover() throws IOException, UpdateRejectedException {
        Optional<Path> reported;
        try {
            reported = source.get();
        } catch (RuntimeException e) {
            throw new UpdateRejectedException("plugin source unknown", e);
        }
        if (reported == null || reported.isEmpty()) {
            throw new UpdateRejectedException("plugin source unknown");
        }
        return LiveJarValidator.validate(reported.get(), scanDirectory.toAbsolutePath(),
                VelocityLiveJarDiscovery::loaderWouldLoad, VelocityLiveJarDiscovery::idOf, OWN_ID,
                VelocityLiveJarDiscovery::versionOf);
    }

    /** Velocity's plugin filter: a regular file (links followed, like {@code File.isFile}) named *.jar. */
    static boolean loaderWouldLoad(Path entry) {
        Path name = entry.getFileName();
        return name != null && name.toString().endsWith(JAR_SUFFIX) && Files.isRegularFile(entry);
    }

    /**
     * Reads the {@code id} of a plugin jar from its {@code velocity-plugin.json}, reading only that one
     * entry, capped at {@link #MAX_DESCRIPTOR_BYTES}. A jar without the descriptor is not a plugin and
     * gives empty. Any read failure, oversize entry or unparsable descriptor throws, so the validator
     * refuses: a jar that cannot be inspected never counts as "not a duplicate".
     */
    static Optional<String> idOf(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(DESCRIPTOR);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            }
            if (bytes.length > MAX_DESCRIPTOR_BYTES) {
                throw new IOException("plugin descriptor is larger than the limit");
            }
            Map<String, Object> root;
            try {
                root = StrictJson.parse(bytes, MAX_DESCRIPTOR_BYTES, MAX_STRING_CHARS);
            } catch (UpdateRejectedException e) {
                throw new IOException("plugin descriptor is not readable by the updater", e);
            }
            Object id = root.get("id");
            return id instanceof String s ? Optional.of(s) : Optional.empty();
        }
    }

    /** The validator calls this only for the live jar; any failure refuses it. */
    static rs.sudoe.quicraft.updater.UpdateVersion versionOf(Path jar) {
        try {
            return OwnJarVersion.read(jar);
        } catch (IOException | UpdateRejectedException e) {
            throw new IllegalStateException("cannot read the live jar version", e);
        }
    }
}
