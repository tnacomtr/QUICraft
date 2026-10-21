// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

/**
 * Chunk-batch pacing on {@code /3} connections.
 *
 * <p>Vanilla's client times a chunk batch from {@code chunk_batch_start} to {@code chunk_batch_finished}, and the
 * server sends that many chunks per tick. On {@code /3} the start record rides the control stream and arrives well
 * before the chunk records on the chunk streams, so the measured time per chunk is inflated and the rate stalls at
 * a few chunks per tick (157 chunks on a clean link: 2.5 s against TCP's 0.86 s, benchmarks.md). The client
 * therefore restarts the batch timer when the batch's first chunk arrives, so it measures what TCP measures. On by
 * default; {@code -Dquicraft.v3.batchTimerFromFirstChunk=false} keeps vanilla timing.
 *
 * <p>A server-side floor on the chunks per tick was measured and rejected: under 75 ms delay and 2% loss it made
 * the server burst past the link and 3 of 6 sessions closed on the blocked-head timer (0x12).
 */
public final class ChunkPacing {
    /** Client: restart the batch timer when the first chunk arrives. */
    public static final boolean BATCH_TIMER_FROM_FIRST_CHUNK = enabled("quicraft.v3.batchTimerFromFirstChunk");

    private ChunkPacing() {}

    /** Whether the batch timer restarts now: enabled, a {@code /3} connection, first chunk of a batch. */
    public static boolean restartTimer(boolean enabled, boolean v3, boolean awaitingFirstChunk) {
        return enabled && v3 && awaitingFirstChunk;
    }

    private static boolean enabled(String name) {
        try {
            return !"false".equalsIgnoreCase(System.getProperty(name, "true"));
        } catch (Throwable t) {
            return false;
        }
    }
}
