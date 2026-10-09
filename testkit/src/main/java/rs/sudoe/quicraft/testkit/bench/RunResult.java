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
        String error) {}
