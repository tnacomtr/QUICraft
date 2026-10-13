// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RevalidationTest {

    private static final String SHA = "ef".repeat(64);
    private static final String OTHER = "12".repeat(64);
    private static final String PLATFORM = "fabric-26x";

    private static ArtifactIdentity staged() throws Exception {
        return new ArtifactIdentity(PLATFORM, UpdateVersion.parse("0.4.0"), 4096L, SHA);
    }

    private static UpdateDocuments.ReleaseEntry entry(String platform, String version, long size,
            String sha) throws Exception {
        return new UpdateDocuments.ReleaseEntry(platform, UpdateVersion.parse(version),
                List.of("1.21.11"), "quicraft-fabric-26x-0.4.0.jar", size, sha, "v0.4.0");
    }

    @Test
    void identicalEntryMatches() throws Exception {
        assertEquals(RevalidationDecision.MATCH,
                Revalidation.compare(staged(), entry(PLATFORM, "0.4.0", 4096L, SHA), PLATFORM));
    }

    @Test
    void differentVersionSizeOrHashRequiresRestart() throws Exception {
        assertEquals(RevalidationDecision.RESTART_REQUIRED,
                Revalidation.compare(staged(), entry(PLATFORM, "0.4.1", 4096L, SHA), PLATFORM));
        assertEquals(RevalidationDecision.RESTART_REQUIRED,
                Revalidation.compare(staged(), entry(PLATFORM, "0.4.0", 4097L, SHA), PLATFORM));
        assertEquals(RevalidationDecision.RESTART_REQUIRED,
                Revalidation.compare(staged(), entry(PLATFORM, "0.4.0", 4096L, OTHER), PLATFORM));
    }

    @Test
    void missingEntryRejects() throws Exception {
        assertEquals(RevalidationDecision.REJECT,
                Revalidation.compare(staged(), null, PLATFORM));
    }

    @Test
    void platformMismatchRejects() throws Exception {
        assertEquals(RevalidationDecision.REJECT,
                Revalidation.compare(staged(), entry("velocity", "0.4.0", 4096L, SHA), PLATFORM));
        assertEquals(RevalidationDecision.REJECT,
                Revalidation.compare(staged(), entry(PLATFORM, "0.4.0", 4096L, SHA), "velocity"));
    }
}
