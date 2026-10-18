// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

/**
 * Metrics from one join. Times are milliseconds; {@code -1} means the phase never completed.
 *
 * <p>The first components keep the Phase 0 result shape and meaning. Provenance components follow
 * them so an old consumer can keep reading the metric fields by name. A failed run still records
 * the transport it attempted; its negotiated fields stay empty and
 * {@code negotiatedTransportVerified} stays false.
 *
 * @param phasesMs time from connect start until: login hello (encryption request, or login
 *     finished in offline mode), login finished, first configuration packet, play login
 * @param joinMs connect start until the play-state login packet arrives
 * @param chunkLoadMs play login until the last chunk before a quiet period
 * @param rttMedianMs median play RTT (ping request sent right after a movement packet, until pong)
 * @param disconnected the connection closed before the bench ended it
 * @param net kernel counter deltas over the run (UDP drops, TCP retransmits), see NetStats
 * @param quic the client's QUIC connection stats at the end of the run; null over TCP
 * @param zeroRtt the join sent its first flight as 0-RTT data (transport quic-0rtt)
 * @param schemaVersion benchmark result schema, see {@link BenchmarkSchema#VERSION}
 * @param transportVersion transport actually used, never inferred from the result label
 * @param streamCount application streams in use when the join completed; 1 for TCP and {@code /2}
 * @param buildCommit Git commit of the build under test, 7 to 40 lowercase hex characters, or
 *     {@link BenchmarkSchema#UNKNOWN_COMMIT}; a {@code -dirty} suffix is recorded when the tree was
 *     modified
 * @param routingTableId routing resource identity for {@code /3}; empty for TCP and {@code /2}
 * @param routingTableDigest SHA-256 of the exact routing resource bytes, lowercase hex; empty otherwise
 * @param routingTableSchema routing resource schema; 0 for TCP and {@code /2}, 2 for {@code /3}
 * @param negotiatedAlpn ALPN enforced by a completed QUIC connection path; empty for TCP and failures
 * @param negotiatedTransportVerified true only when that path established the transport version
 */
public record RunResult(
        int run,
        boolean warmup,
        boolean ok,
        double[] phasesMs,
        double joinMs,
        double chunkLoadMs,
        int chunks,
        double rttMedianMs,
        double rttP95Ms,
        int rttSamples,
        int rttLost,
        boolean disconnected,
        String error,
        java.util.Map<String, Long> net,
        java.util.Map<String, Long> quic,
        boolean zeroRtt,
        int schemaVersion,
        TransportVersion transportVersion,
        int streamCount,
        String buildCommit,
        String routingTableId,
        String routingTableDigest,
        int routingTableSchema,
        String negotiatedAlpn,
        boolean negotiatedTransportVerified) {}
