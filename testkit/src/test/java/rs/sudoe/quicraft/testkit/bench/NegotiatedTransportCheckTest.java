// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The fail-closed {@code /3} assertion. Fixtures are synthetic, not measurements. */
class NegotiatedTransportCheckTest {
    @Test
    void completeNegotiatedEvidenceVerifies() {
        NegotiatedTransportCheck.Result result = NegotiatedTransportCheck.check(
                TransportVersion.QUIC_3, RunFixtures.quic3(10, 20, 30));

        assertEquals(NegotiatedTransportCheck.Verdict.VERIFIED, result.verdict());
        assertTrue(result.verified());
        assertTrue(result.detail().contains("schema=2"));
        assertTrue(result.detail().contains("alpn=quicraft/3"));
    }

    @Test
    void quic2NeverSatisfiesAQuic3AssertionEvenWithQuic3LookingFields() {
        RunResult disguised = RunFixtures.run(TransportVersion.QUIC_2, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, 10, 20, 30);

        NegotiatedTransportCheck.Result result =
                NegotiatedTransportCheck.check(TransportVersion.QUIC_3, disguised);

        assertEquals(NegotiatedTransportCheck.Verdict.FAIL_CLOSED, result.verdict());
        assertFalse(result.verified());
        assertTrue(result.detail().contains("QUIC_2"));
    }

    @Test
    void missingNegotiationVerificationFailsClosed() {
        RunResult run = RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), false, 10, 20, 30);

        assertEquals(NegotiatedTransportCheck.Verdict.FAIL_CLOSED,
                NegotiatedTransportCheck.check(TransportVersion.QUIC_3, run).verdict());
    }

    @Test
    void wrongAlpnFailsClosed() {
        RunResult run = RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_2.alpn(), true, 10, 20, 30);

        assertEquals(NegotiatedTransportCheck.Verdict.FAIL_CLOSED,
                NegotiatedTransportCheck.check(TransportVersion.QUIC_3, run).verdict());
    }

    @Test
    void missingOrWrongRoutingEvidenceFailsClosed() {
        assertFailed(RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, 10, 20, 30));
        assertFailed(RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "unknown", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, 10, 20, 30));
        assertFailed(RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "protocol-775", "", BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, 10, 20, 30));
        assertFailed(RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, 1,
                TransportVersion.QUIC_3.alpn(), true, 10, 20, 30));
    }

    @Test
    void unknownCommitZeroStreamsWrongSchemaOrFailedRunFailsClosed() {
        assertFailed(RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                BenchmarkSchema.UNKNOWN_COMMIT, "protocol-775", RunFixtures.DIGEST,
                BenchmarkSchema.ROUTING_SCHEMA_V3, TransportVersion.QUIC_3.alpn(), true, 10, 20, 30));
        assertFailed(RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 0,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, 10, 20, 30));
        assertFailed(RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION + 1, 3,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, 10, 20, 30));
        assertFailed(RunFixtures.run(TransportVersion.QUIC_3, false, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, 10, 20, 30));
    }

    @Test
    void onlyQuic3HasAFailClosedAssertion() {
        assertEquals(NegotiatedTransportCheck.Verdict.NOT_APPLICABLE,
                NegotiatedTransportCheck.check(TransportVersion.TCP, RunFixtures.tcp(1, 2, 3)).verdict());
        assertEquals(NegotiatedTransportCheck.Verdict.NOT_APPLICABLE,
                NegotiatedTransportCheck.check(TransportVersion.QUIC_2, RunFixtures.quic2(1, 2, 3)).verdict());
        assertEquals(NegotiatedTransportCheck.Verdict.FAIL_CLOSED,
                NegotiatedTransportCheck.check(TransportVersion.QUIC_3, null).verdict());
    }

    private static void assertFailed(RunResult run) {
        assertEquals(NegotiatedTransportCheck.Verdict.FAIL_CLOSED,
                NegotiatedTransportCheck.check(TransportVersion.QUIC_3, run).verdict());
    }
}
