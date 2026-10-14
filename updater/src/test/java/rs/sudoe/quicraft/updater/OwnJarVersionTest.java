// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** build.json lookup in a QUICraft jar: exactly one entry, capped, parsed by DocumentParser. */
class OwnJarVersionTest {

    @TempDir
    Path tmp;

    @Test
    void readsVersionFromSingleBuildEntry() throws Exception {
        Path jar = JarFixtures.jar(tmp, "quicraft.jar", null, JarFixtures.BUILD_JSON_V1);

        assertEquals(UpdateVersion.parse("1.2.3"), OwnJarVersion.read(jar));
    }

    @Test
    void missingBuildEntryIsRefused() throws Exception {
        Path jar = JarFixtures.jar(tmp, "quicraft.jar", null, null);

        assertThrows(UpdateRejectedException.class, () -> OwnJarVersion.read(jar));
    }

    /**
     * ZipOutputStream refuses duplicate names, so two entries of equal length are written and their
     * names are rewritten to the same text in the raw bytes (the local and central headers both).
     */
    @Test
    void duplicatedBuildEntryIsRefused() throws Exception {
        Path jar = tmp.resolve("dup.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            JarFixtures.put(zip, "META-INF/quicraft/buildAjson", JarFixtures.BUILD_JSON_V1);
            JarFixtures.put(zip, "META-INF/quicraft/buildBjson", JarFixtures.BUILD_JSON_V1);
        }
        byte[] raw = Files.readAllBytes(jar);
        raw = replaceAll(raw, "buildAjson", "build.json");
        raw = replaceAll(raw, "buildBjson", "build.json");
        Files.write(jar, raw);

        UpdateRejectedException e = assertThrows(UpdateRejectedException.class, () -> OwnJarVersion.read(jar));
        assertEquals("build metadata missing or duplicated", e.getMessage());
    }

    @Test
    void oversizedBuildEntryIsRefused() throws Exception {
        String padding = " ".repeat(OwnJarVersion.MAX_BYTES);
        Path jar = JarFixtures.jar(tmp, "quicraft.jar", null, JarFixtures.BUILD_JSON_V1 + padding);

        UpdateRejectedException e = assertThrows(UpdateRejectedException.class,
                () -> OwnJarVersion.read(jar));
        assertEquals("build metadata too large", e.getMessage());
    }

    /** parseBuild enforces a 1 KiB cap; a valid document padded to 1025 bytes is refused. */
    @Test
    void buildEntryOverOneKibibyteIsRefusedByParser() throws Exception {
        String padded = JarFixtures.BUILD_JSON_V1
                + " ".repeat(1025 - JarFixtures.BUILD_JSON_V1.length());
        assertEquals(1025, padded.getBytes(StandardCharsets.UTF_8).length);
        Path jar = JarFixtures.jar(tmp, "quicraft.jar", null, padded);

        assertThrows(UpdateRejectedException.class, () -> OwnJarVersion.read(jar));
    }

    @Test
    void buildEntryAtOneKibibyteIsAccepted() throws Exception {
        String padded = JarFixtures.BUILD_JSON_V1
                + " ".repeat(1024 - JarFixtures.BUILD_JSON_V1.length());
        Path jar = JarFixtures.jar(tmp, "quicraft.jar", null, padded);

        assertEquals(UpdateVersion.parse("1.2.3"), OwnJarVersion.read(jar));
    }

    @Test
    void unparsableBuildEntryIsRefused() throws Exception {
        Path jar = JarFixtures.jar(tmp, "quicraft.jar", null, "{not json");

        assertThrows(UpdateRejectedException.class, () -> OwnJarVersion.read(jar));
    }

    @Test
    void notAZipIsAnIoException() throws Exception {
        Path file = tmp.resolve("plain.jar");
        Files.write(file, "garbage".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> OwnJarVersion.read(file));
    }

    private static byte[] replaceAll(byte[] data, String from, String to) {
        byte[] needle = from.getBytes(StandardCharsets.US_ASCII);
        byte[] repl = to.getBytes(StandardCharsets.US_ASCII);
        if (needle.length != repl.length) {
            throw new IllegalArgumentException("replacement must keep the length");
        }
        byte[] out = data.clone();
        for (int i = 0; i + needle.length <= out.length; i++) {
            if (Arrays.equals(out, i, i + needle.length, needle, 0, needle.length)) {
                System.arraycopy(repl, 0, out, i, repl.length);
            }
        }
        return out;
    }
}
