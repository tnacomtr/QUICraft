// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import rs.sudoe.quicraft.updater.DevelopmentBuildException;
import rs.sudoe.quicraft.updater.LiveJar;
import rs.sudoe.quicraft.updater.LiveJarDiscovery;
import rs.sudoe.quicraft.updater.LiveJarValidator;
import rs.sudoe.quicraft.updater.OwnJarVersion;
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
    private static final String ID = "id";
    private static final String PROVIDES = "provides";
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
     * Reads the identity of a plugin jar from its {@code velocity-plugin.json}, reading only that one
     * entry, capped at {@link #MAX_DESCRIPTOR_BYTES}. The descriptor is parsed with Gson in Velocity's
     * own lenient mode, so an unknown field or a long string (Velocity's {@code description} has no
     * limit) does not refuse the jar. A jar without the descriptor is not a plugin and gives empty.
     *
     * <p>A jar that provides {@code quicraft} through its {@code provides} aliases counts as a
     * duplicate, so this returns {@link #OWN_ID} for it, like it does for a jar whose {@code id} is
     * {@code quicraft}: Velocity would load both and refuse the duplicate. A jar whose central directory
     * holds more than one {@link #DESCRIPTOR} entry throws, because {@link ZipFile} and Velocity's
     * {@code JarInputStream} may disagree on which entry counts, so the identity read here need not be
     * the identity Velocity loads. Any read failure, oversize entry or unparsable descriptor throws, so
     * the validator refuses: a jar that cannot be inspected never counts as "not a duplicate".
     */
    static Optional<String> idOf(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(DESCRIPTOR);
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            requireSingleDescriptor(zip);
            JsonObject root = parseDescriptor(readCap(zip, entry));
            String id = stringField(root, ID);
            if (OWN_ID.equals(id) || providesOwnId(root)) {
                return Optional.of(OWN_ID);
            }
            return id == null ? Optional.empty() : Optional.of(id);
        }
    }

    /**
     * Refuses a jar holding more than one {@link #DESCRIPTOR} entry. Which of them a reader counts is
     * reader-specific, so a jar with several descriptors has no single identity the updater could judge
     * against Velocity's choice.
     */
    private static void requireSingleDescriptor(ZipFile zip) throws IOException {
        long descriptors = zip.stream().filter(e -> DESCRIPTOR.equals(e.getName())).count();
        if (descriptors > 1) {
            throw new IOException("plugin descriptor appears more than once");
        }
    }

    /** The descriptor's decompressed bytes, capped like the entry-size limit. */
    private static byte[] readCap(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            if (bytes.length > MAX_DESCRIPTOR_BYTES) {
                throw new IOException("plugin descriptor is larger than the limit");
            }
            return bytes;
        }
    }

    /** Parses the descriptor the way Velocity does: Gson, lenient, from its UTF-8 bytes. */
    private static JsonObject parseDescriptor(byte[] bytes) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new IOException("plugin descriptor is not readable by the updater", e);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("plugin descriptor root is not an object");
        }
        return parsed.getAsJsonObject();
    }

    private static String stringField(JsonObject root, String name) {
        JsonElement field = root.get(name);
        return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()
                ? field.getAsString()
                : null;
    }

    private static boolean providesOwnId(JsonObject root) {
        JsonElement provides = root.get(PROVIDES);
        if (provides == null || !provides.isJsonArray()) {
            return false;
        }
        for (JsonElement entry : provides.getAsJsonArray()) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()
                    && OWN_ID.equals(entry.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The validator calls this only for the live jar; any read failure refuses it (the validator wraps
     * the {@link IllegalStateException}). A {@link DevelopmentBuildException} is unchecked and is
     * rethrown unchanged, so the facade names the development build instead of a generic refusal.
     */
    static rs.sudoe.quicraft.updater.UpdateVersion versionOf(Path jar) {
        try {
            return OwnJarVersion.read(jar);
        } catch (DevelopmentBuildException e) {
            throw e;
        } catch (IOException | UpdateRejectedException e) {
            throw new IllegalStateException("cannot read the live jar version", e);
        }
    }
}
