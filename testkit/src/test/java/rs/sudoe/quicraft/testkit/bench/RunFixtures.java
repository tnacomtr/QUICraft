// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import java.util.Map;

/** Deterministic result fixtures for schema tests. No value here is a benchmark measurement. */
final class RunFixtures {
    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private RunFixtures() {}

    static RunResult tcp(double joinMs, double chunkLoadMs, double rttMs) {
        return run(TransportVersion.TCP, true, BenchmarkSchema.VERSION, 1, COMMIT,
                "", "", 0, "", true, joinMs, chunkLoadMs, rttMs);
    }

    static RunResult quic2(double joinMs, double chunkLoadMs, double rttMs) {
        return run(TransportVersion.QUIC_2, true, BenchmarkSchema.VERSION, 1, COMMIT,
                "", "", 0, TransportVersion.QUIC_2.alpn(), true, joinMs, chunkLoadMs, rttMs);
    }

    static RunResult quic3(double joinMs, double chunkLoadMs, double rttMs) {
        return run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3, COMMIT,
                "protocol-775", DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, joinMs, chunkLoadMs, rttMs);
    }

    static RunResult run(TransportVersion version, boolean ok, int schemaVersion, int streamCount,
            String buildCommit, String routingTableId, String routingTableDigest, int routingTableSchema,
            String negotiatedAlpn, boolean negotiatedTransportVerified, double joinMs, double chunkLoadMs,
            double rttMs) {
        return new RunResult(0, false, ok, new double[] {1, 2, 3, 4}, joinMs, chunkLoadMs, 1,
                rttMs, rttMs, 1, 0, false, null, Map.of(), null, false,
                schemaVersion, version, streamCount, buildCommit, routingTableId, routingTableDigest,
                routingTableSchema, negotiatedAlpn, negotiatedTransportVerified);
    }
}
