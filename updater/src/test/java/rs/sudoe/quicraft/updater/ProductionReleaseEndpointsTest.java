// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The endpoint layout of {@link ProductionReleaseEndpoints} (docs/updater.md 12.15). */
class ProductionReleaseEndpointsTest {

    private static final ReleaseHost GITHUB = new ReleaseHost("github", URI.create("https://github.com"));
    private static final ReleaseHost SUDOE = new ReleaseHost("sudoe", URI.create("https://git.sudoe.rs"));
    private static final ReleaseHost MODRINTH = new ReleaseHost("modrinth", URI.create("https://modrinth.example"));

    private final ProductionReleaseEndpoints endpoints = new ProductionReleaseEndpoints();

    private static UpdateDocuments.ReleaseEntry entry(String tag, String file) throws Exception {
        return new UpdateDocuments.ReleaseEntry("fabric-26x", UpdateVersion.parse("0.1.0"), List.of("26.1"),
                file, 1L, "ab".repeat(64), tag);
    }

    @Test
    void githubMetadataIsTheUpdaterMetadataReleaseOfItsRepository() {
        assertEquals(URI.create("https://github.com/tnacomtr/QUICraft/releases/download/updater-metadata/"
                + "release.v1.json"), endpoints.manifest(GITHUB));
        assertEquals(URI.create("https://github.com/tnacomtr/QUICraft/releases/download/updater-metadata/"
                + "release.v1.json.sig"), endpoints.manifestSignature(GITHUB));
        assertEquals(URI.create("https://github.com/tnacomtr/QUICraft/releases/download/updater-metadata/"
                + "revocations.v1.json"), endpoints.revocations(GITHUB));
        assertEquals(URI.create("https://github.com/tnacomtr/QUICraft/releases/download/updater-metadata/"
                + "revocations.v1.json.sig"), endpoints.revocationsSignature(GITHUB));
    }

    @Test
    void sudoeMetadataIsTheUpdaterMetadataReleaseOfItsRepository() {
        assertEquals(URI.create("https://git.sudoe.rs/tna/QUICraft/releases/download/updater-metadata/"
                + "release.v1.json"), endpoints.manifest(SUDOE));
        assertEquals(URI.create("https://git.sudoe.rs/tna/QUICraft/releases/download/updater-metadata/"
                + "revocations.v1.json.sig"), endpoints.revocationsSignature(SUDOE));
    }

    @Test
    void anArtifactIsUnderItsReleaseTagOnBothHosts() throws Exception {
        assertEquals(URI.create("https://github.com/tnacomtr/QUICraft/releases/download/v0.1.0-alpha.1/"
                + "quicraft-fabric-26x.jar"),
                endpoints.artifact(GITHUB, entry("v0.1.0-alpha.1", "quicraft-fabric-26x.jar")));
        assertEquals(URI.create("https://git.sudoe.rs/tna/QUICraft/releases/download/v0.1.0-alpha.1/"
                + "quicraft-fabric-26x.jar"),
                endpoints.artifact(SUDOE, entry("v0.1.0-alpha.1", "quicraft-fabric-26x.jar")));
    }

    @Test
    void aTagThatIsNotAVersionTagIsRejected() {
        for (String tag : List.of("", "v", "0.1.0", "v0.1.0+1", "v0.1.0/../x", "v".repeat(42),
                "v0.1.0 1")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> endpoints.artifact(GITHUB, entry(tag, "quicraft.jar")), () -> "tag " + tag);
            assertEquals(ProductionReleaseEndpoints.INVALID_TAG, e.getMessage());
        }
    }

    @Test
    void aFileThatIsNotAPlainJarNameIsRejected() {
        for (String file : List.of("", "quicraft", "quicraft.zip", "quicraft.jar/", "../quicraft.jar",
                "a/b.jar", "quicraft .jar", "a".repeat(126) + ".jar")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> endpoints.artifact(GITHUB, entry("v1", file)), () -> "file " + file);
            assertEquals(ProductionReleaseEndpoints.INVALID_FILE, e.getMessage());
        }
    }

    @Test
    void aHostKeyWithoutAProductionRepositoryIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> endpoints.manifest(MODRINTH));
        assertEquals(ProductionReleaseEndpoints.UNKNOWN_HOST, e.getMessage());
        IllegalArgumentException artifact = assertThrows(IllegalArgumentException.class,
                () -> endpoints.artifact(MODRINTH, entry("v1", "quicraft.jar")));
        assertEquals(ProductionReleaseEndpoints.UNKNOWN_HOST, artifact.getMessage());
    }

    @Test
    void everyMetadataUriIsUnderTheRepositoriesUpdaterMetadataTag() {
        for (URI uri : List.of(endpoints.manifest(GITHUB), endpoints.manifestSignature(GITHUB),
                endpoints.revocations(GITHUB), endpoints.revocationsSignature(GITHUB))) {
            assertTrue(uri.getPath().startsWith("/tnacomtr/QUICraft/releases/download/"
                    + ProductionReleaseEndpoints.METADATA_TAG + "/"), () -> "path " + uri.getPath());
            assertFalse(uri.getPath().contains(".."), () -> "path " + uri.getPath());
        }
    }
}
