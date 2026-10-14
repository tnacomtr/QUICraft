// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.updater.BuildInfo;
import rs.sudoe.quicraft.updater.DocumentParser;
import rs.sudoe.quicraft.updater.UpdateDocuments;
import rs.sudoe.quicraft.updater.UpdateRejectedException;
import rs.sudoe.quicraft.updater.UpdateVersion;

/**
 * The shaded Velocity jar carries the updater's build metadata. Gradle sets the jar and the
 * expected version (see velocity/build.gradle.kts). The build.json must name this platform and the
 * project version exactly; it parses through DocumentParser.parseBuild whenever the version is in
 * the UpdateVersion grammar (a SNAPSHOT build is rejected by design, see checkReleaseTrust).
 */
class UpdaterJarMetadataTest {

    private static final String BUILD_ENTRY = "META-INF/quicraft/build.json";
    private static final String TRUST_ENTRY = "META-INF/quicraft/updater-trust.json";

    private static String version() {
        String version = System.getProperty("quicraft.expectedVersion");
        assertNotNull(version, "quicraft.expectedVersion is set by the Gradle test task");
        return version;
    }

    private static byte[] entry(String name) throws IOException {
        String jar = System.getProperty("quicraft.shadowJar");
        assertNotNull(jar, "quicraft.shadowJar is set by the Gradle test task");
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull(entry, name + " is missing from " + jar);
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static boolean inUpdateVersionGrammar(String version) {
        try {
            UpdateVersion.parse(version);
            return true;
        } catch (UpdateRejectedException e) {
            return false;
        }
    }

    @Test
    void buildJsonNamesVelocityAndTheProjectVersion() throws Exception {
        String version = version();
        byte[] bytes = entry(BUILD_ENTRY);
        assertEquals(
                "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"velocity\",\"version\":\""
                        + version + "\"}",
                new String(bytes, UTF_8));
        if (inUpdateVersionGrammar(version)) {
            BuildInfo info = DocumentParser.parseBuild(bytes);
            assertEquals("velocity", info.platform());
            assertEquals(version, info.version().toString());
        } else {
            // Dev builds (for example 0.1.0-SNAPSHOT) are not updatable: the parser refuses them.
            assertThrows(UpdateRejectedException.class, () -> DocumentParser.parseBuild(bytes));
        }
    }

    @Test
    void trustFileInTheJarParsesAsAValidTrustFile() throws Exception {
        UpdateDocuments.Trust trust = DocumentParser.parseTrust(entry(TRUST_ENTRY));
        assertFalse(trust.keys().isEmpty());
    }
}
