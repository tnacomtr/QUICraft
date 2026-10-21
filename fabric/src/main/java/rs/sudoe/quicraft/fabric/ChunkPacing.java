// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

/**
 * Chunk-batch pacing decisions for {@code /3} connections (both opt-in, off by default).
 *
 * <p>Vanilla's client times a chunk batch from {@code chunk_batch_start} to
 * {@code chunk_batch_finished} and the server sends that many chunks per tick. On {@code /3} the
 * start record rides the control lane and arrives well before the chunk lanes' records, so the
 * measured time per chunk is inflated and the rate stalls at a few chunks per tick. The
 * pure decisions live here so they can be tested without the game.</p>
 */
public final class ChunkPacing {
    /** Client: restart the batch timer when the first chunk arrives (-Dquicraft.v3.batchTimerFromFirstChunk). */
    public static final boolean BATCH_TIMER_FROM_FIRST_CHUNK = flag("quicraft.v3.batchTimerFromFirstChunk");
    /** Server: floor for the stored chunks per tick, 0 = off (-Dquicraft.v3.minChunksPerTick). */
    public static final int MIN_CHUNKS_PER_TICK = floor("quicraft.v3.minChunksPerTick");
    /** Vanilla's cap (PlayerChunkSender.MAX_CHUNKS_PER_TICK). */
    static final float VANILLA_MAX = 64.0F;

    private ChunkPacing() {}

    /** Whether the batch timer restarts now: enabled, a {@code /3} connection, first chunk of a batch. */
    public static boolean restartTimer(boolean enabled, boolean v3, boolean awaitingFirstChunk) {
        return enabled && v3 && awaitingFirstChunk;
    }

    /**
     * The rate the server receives, raised to {@code floor} (never above vanilla's 64) on a
     * {@code /3} connection. NaN, other connections, and floor 0 leave the value untouched, so
     * vanilla's own handling applies.
     */
    public static float clampRate(float desired, int floor, boolean v3) {
        if (!v3 || floor <= 0 || Float.isNaN(desired)) {
            return desired;
        }
        float lifted = Math.min(floor, VANILLA_MAX);
        return desired < lifted ? lifted : desired;
    }

    private static boolean flag(String name) {
        try {
            return Boolean.getBoolean(name);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int floor(String name) {
        try {
            return Math.max(0, Integer.getInteger(name, 0));
        } catch (Throwable t) {
            return 0;
        }
    }
}
