// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkPacingTest {
    private final AtomicInteger restarts = new AtomicInteger();

    private void chunk(ChunkPacing pacing, boolean networkThread, boolean v3) {
        pacing.chunk(networkThread, () -> v3, restarts::incrementAndGet);
    }

    @Test
    void onlyTheFirstChunkOfABatchRestartsTheTimer() {
        ChunkPacing pacing = new ChunkPacing(true);
        pacing.batchStarted();
        chunk(pacing, true, true);
        chunk(pacing, true, true);
        pacing.batchFinished();
        pacing.batchStarted();
        chunk(pacing, true, true);
        assertEquals(2, restarts.get());
    }

    @Test
    void aMainThreadReplayOfAnOlderChunkDoesNotConsumeTheRestart() {
        ChunkPacing pacing = new ChunkPacing(true);
        pacing.batchStarted();
        chunk(pacing, false, true);
        assertEquals(0, restarts.get());
        chunk(pacing, true, true);
        assertEquals(1, restarts.get());
    }

    @Test
    void finishClearsAnEmptyBatch() {
        ChunkPacing pacing = new ChunkPacing(true);
        pacing.batchStarted();
        pacing.batchFinished();
        chunk(pacing, true, true);
        assertEquals(0, restarts.get());
        pacing.batchStarted();
        chunk(pacing, true, true);
        assertEquals(1, restarts.get());
    }

    @Test
    void chunksOutsideABatchAndNonV3ConnectionsKeepVanillaTiming() {
        ChunkPacing pacing = new ChunkPacing(true);
        chunk(pacing, true, true);
        pacing.batchStarted();
        chunk(pacing, true, false);
        chunk(pacing, true, true);
        assertEquals(0, restarts.get());
    }

    @Test
    void turnedOffNeverRestarts() {
        ChunkPacing pacing = new ChunkPacing(false);
        pacing.batchStarted();
        chunk(pacing, true, true);
        assertEquals(0, restarts.get());
    }

    @Test
    void aThrowingRestartClearsThePendingRestart() {
        ChunkPacing pacing = new ChunkPacing(true);
        pacing.batchStarted();
        assertThrows(IllegalStateException.class, () -> pacing.chunk(true, () -> true, () -> {
            throw new IllegalStateException("boom");
        }));
        chunk(pacing, true, true);
        assertEquals(0, restarts.get());
    }
}
