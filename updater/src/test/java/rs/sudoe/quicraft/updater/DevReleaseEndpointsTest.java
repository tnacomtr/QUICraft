// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The endpoint layout of {@link DevReleaseEndpoints}. */
class DevReleaseEndpointsTest {

    private static final ReleaseHost HOST = new ReleaseHost("modrinth", URI.create("https://localhost:9001"));

    @Test
    void documentsAndSignaturesAreUnderTheOrigin() {
        DevReleaseEndpoints e = new DevReleaseEndpoints();
        assertEquals(URI.create("https://localhost:9001/revocations.json"), e.revocations(HOST));
        assertEquals(URI.create("https://localhost:9001/revocations.json.sig"), e.revocationsSignature(HOST));
        assertEquals(URI.create("https://localhost:9001/manifest.json"), e.manifest(HOST));
        assertEquals(URI.create("https://localhost:9001/manifest.json.sig"), e.manifestSignature(HOST));
    }

    @Test
    void artifactIsNamedByItsSha512() throws Exception {
        String sha = "ab".repeat(64);
        UpdateDocuments.ReleaseEntry entry = new UpdateDocuments.ReleaseEntry("fabric-26x",
                UpdateVersion.parse("0.4.0"), List.of("26.1"), "quicraft.jar", 1, sha, "v0.4.0");
        assertEquals(URI.create("https://localhost:9001/artifact/" + sha + ".jar"),
                new DevReleaseEndpoints().artifact(HOST, entry));
    }
}
