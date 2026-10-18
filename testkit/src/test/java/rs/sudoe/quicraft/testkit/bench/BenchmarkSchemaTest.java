// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

/** Shape checks for the benchmark provenance schema. Fixtures are synthetic, not measurements. */
class BenchmarkSchemaTest {
    @Test
    void schemaConstantsMatchTheReviewedRoutingSchema() {
        assertEquals(1, BenchmarkSchema.VERSION);
        assertEquals(2, BenchmarkSchema.ROUTING_SCHEMA_V3);
    }

    @Test
    void buildCommitAcceptsOnlyCleanLowercaseGitShas() {
        assertTrue(BenchmarkSchema.isBuildCommit(RunFixtures.COMMIT));
        assertTrue(BenchmarkSchema.isBuildCommit("0123456"));
        assertFalse(BenchmarkSchema.isBuildCommit("unknown"));
        assertFalse(BenchmarkSchema.isBuildCommit("0123456-dirty"));
        assertFalse(BenchmarkSchema.isBuildCommit("012345"));
        assertFalse(BenchmarkSchema.isBuildCommit("0123456789ABCDEF0123456789abcdef01234567"));
        assertFalse(BenchmarkSchema.isBuildCommit(null));
    }

    @Test
    void routingDigestAcceptsOnlySixtyFourLowercaseHexCharacters() {
        assertTrue(BenchmarkSchema.isRoutingDigest(RunFixtures.DIGEST));
        assertFalse(BenchmarkSchema.isRoutingDigest("sha256:" + RunFixtures.DIGEST));
        assertFalse(BenchmarkSchema.isRoutingDigest(RunFixtures.DIGEST.substring(1)));
        assertFalse(BenchmarkSchema.isRoutingDigest(RunFixtures.DIGEST.toUpperCase()));
        assertFalse(BenchmarkSchema.isRoutingDigest(null));
    }

    @Test
    void routingTableIdentityRejectsBlankAndPlaceholders() {
        assertTrue(BenchmarkSchema.isRoutingTableId("protocol-775"));
        assertFalse(BenchmarkSchema.isRoutingTableId(""));
        assertFalse(BenchmarkSchema.isRoutingTableId("   "));
        assertFalse(BenchmarkSchema.isRoutingTableId("unknown"));
        assertFalse(BenchmarkSchema.isRoutingTableId("NONE"));
        assertFalse(BenchmarkSchema.isRoutingTableId(null));
    }

    @Test
    void provenanceRoundTripsThroughTheResultJson() {
        RunResult run = RunFixtures.quic3(10, 20, 30);
        String json = new Gson().toJson(run);
        RunResult parsed = new Gson().fromJson(json, RunResult.class);

        assertEquals(BenchmarkSchema.VERSION, parsed.schemaVersion());
        assertEquals(TransportVersion.QUIC_3, parsed.transportVersion());
        assertEquals(3, parsed.streamCount());
        assertEquals(RunFixtures.COMMIT, parsed.buildCommit());
        assertEquals("protocol-775", parsed.routingTableId());
        assertEquals(RunFixtures.DIGEST, parsed.routingTableDigest());
        assertEquals(BenchmarkSchema.ROUTING_SCHEMA_V3, parsed.routingTableSchema());
        assertEquals(TransportVersion.QUIC_3.alpn(), parsed.negotiatedAlpn());
        assertTrue(parsed.negotiatedTransportVerified());
    }
}
