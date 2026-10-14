// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Test jars and loader stand-ins for {@link LiveJarValidator} and {@link OwnJarVersion}. */
final class JarFixtures {

    static final String OWN_ID = "quicraft";
    static final String ID_ENTRY = "test-id.txt";
    static final String BUILD_JSON_V1 =
            "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"velocity\",\"version\":\"1.2.3\"}";

    private JarFixtures() {
    }

    /** A jar with an optional id entry and an optional build.json entry. */
    static Path jar(Path dir, String name, String id, String buildJson) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            if (id != null) {
                put(zip, ID_ENTRY, id);
            }
            if (buildJson != null) {
                put(zip, ArtifactVerifier.BUILD_ENTRY, buildJson);
            }
            put(zip, "payload.bin", "x");
        }
        return file;
    }

    static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Reads the id entry of a test jar; a file that is not a zip is an IOException. */
    static Optional<String> testIds(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(ID_ENTRY);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /** A loader-style filter: a regular file whose name ends with .jar (no hidden-file rule). */
    static boolean velocityLike(Path entry) {
        return Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar");
    }

    /** A sparse regular file of the given length (one byte written at the end). */
    static Path sparse(Path file, long length) throws IOException {
        Files.createDirectories(file.getParent());
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ch.position(length - 1);
            ch.write(ByteBuffer.wrap(new byte[] {0}));
        }
        return file;
    }
}
