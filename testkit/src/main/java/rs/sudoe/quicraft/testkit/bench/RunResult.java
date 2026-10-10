// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

/**
 * Metrics from one join. Times are milliseconds; {@code -1} means the phase never completed.
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
        boolean zeroRtt) {}
