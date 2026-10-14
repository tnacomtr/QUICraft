// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the two metadata entries of the running server's own jar: {@code build.json} and the updater
 * trust file. The jar is opened as a {@link ZipFile} at the path that discovery returned. A classloader
 * {@code getResource} is never used here: Knot and Velocity's loaders can return another mod's copy,
 * which would replace the trust anchor.
 *
 * <p>Each entry must appear exactly once. A duplicate name is refused, and so is an entry larger than
 * {@link #MAX_ENTRY_BYTES}.
 */
public final class OwnJarResources {

    /** The cap for each entry. */
    static final int MAX_ENTRY_BYTES = 64 * 1024;

    static final String MISSING = "required metadata entry is missing";
    static final String DUPLICATE = "metadata entry appears more than once";
    static final String TOO_LARGE = "metadata entry is larger than the limit";

    /**
     * The two entries, raw.
     *
     * @param buildJson the bytes of {@code META-INF/quicraft/build.json}
     * @param trustJson the bytes of {@code META-INF/quicraft/updater-trust.json}
     */
    public record Resources(byte[] buildJson, byte[] trustJson) {
        public Resources {
            Objects.requireNonNull(buildJson, "buildJson");
            Objects.requireNonNull(trustJson, "trustJson");
        }
    }

    private OwnJarResources() {
    }

    /**
     * Reads both entries from {@code jar}.
     *
     * @param jar the discovered live jar, a real path
     * @return the raw entries
     * @throws IOException when the jar cannot be opened or read
     * @throws UpdateRejectedException when an entry is missing, duplicated or too large
     */
    public static Resources read(Path jar) throws IOException, UpdateRejectedException {
        Objects.requireNonNull(jar, "jar");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            byte[] build = single(zip, ArtifactVerifier.BUILD_ENTRY);
            byte[] trust = single(zip, ArtifactVerifier.TRUST_ENTRY);
            return new Resources(build, trust);
        }
    }

    private static byte[] single(ZipFile zip, String name) throws IOException, UpdateRejectedException {
        ZipEntry found = null;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (name.equals(e.getName())) {
                if (found != null) {
                    throw new UpdateRejectedException(DUPLICATE);
                }
                found = e;
            }
        }
        if (found == null) {
            throw new UpdateRejectedException(MISSING);
        }
        if (found.getSize() > MAX_ENTRY_BYTES) {
            throw new UpdateRejectedException(TOO_LARGE);
        }
        try (InputStream in = zip.getInputStream(found)) {
            byte[] bytes = in.readNBytes(MAX_ENTRY_BYTES + 1);
            if (bytes.length > MAX_ENTRY_BYTES) {
                throw new UpdateRejectedException(TOO_LARGE);
            }
            return bytes;
        }
    }
}
