// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link OwnJarResources#read}: exactly one entry each, a size cap, and no other source. */
class OwnJarResourcesTest {

    private static final String BUILD = UpdaterFacadeFixtures.buildJson(UpdaterFacadeFixtures.PLATFORM,
            UpdaterFacadeFixtures.RUNNING);
    private static final String TRUST = "{\"trust\":true}";

    @TempDir
    Path tmp;

    /** Writes a jar with the given (name, bytes) entries in order. */
    private Path jar(String name, Object... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        Path p = tmp.resolve(name);
        Files.write(p, bytes.toByteArray());
        return p;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsBothEntriesOfAGoodJar() throws Exception {
        Path p = jar("good.jar", ArtifactVerifier.BUILD_ENTRY, utf8(BUILD), ArtifactVerifier.TRUST_ENTRY,
                utf8(TRUST));
        OwnJarResources.Resources r = OwnJarResources.read(p);
        assertArrayEquals(utf8(BUILD), r.buildJson());
        assertArrayEquals(utf8(TRUST), r.trustJson());
    }

    @Test
    void aMissingEntryIsRefused() throws Exception {
        Path p = jar("no-trust.jar", ArtifactVerifier.BUILD_ENTRY, utf8(BUILD));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class, () -> OwnJarResources.read(p));
        assertEquals(OwnJarResources.MISSING, e.getMessage());
    }

    @Test
    void aDuplicatedEntryIsRefused() throws Exception {
        Path p = tmp.resolve("dup.jar");
        Files.write(p, UpdaterFacadeFixtures.jarWithDuplicateBuild(BUILD, TRUST));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class, () -> OwnJarResources.read(p));
        assertEquals(OwnJarResources.DUPLICATE, e.getMessage());
    }

    @Test
    void anOversizedEntryIsRefused() throws Exception {
        byte[] big = new byte[OwnJarResources.MAX_ENTRY_BYTES + 1];
        Path p = jar("big.jar", ArtifactVerifier.BUILD_ENTRY, big, ArtifactVerifier.TRUST_ENTRY, utf8(TRUST));
        UpdateRejectedException e = assertThrows(UpdateRejectedException.class, () -> OwnJarResources.read(p));
        assertEquals(OwnJarResources.TOO_LARGE, e.getMessage());
    }

    @Test
    void anEntryAtTheCapIsAccepted() throws Exception {
        byte[] atCap = new byte[OwnJarResources.MAX_ENTRY_BYTES];
        Path p = jar("cap.jar", ArtifactVerifier.BUILD_ENTRY, utf8(BUILD), ArtifactVerifier.TRUST_ENTRY, atCap);
        assertEquals(OwnJarResources.MAX_ENTRY_BYTES, OwnJarResources.read(p).trustJson().length);
    }
}
