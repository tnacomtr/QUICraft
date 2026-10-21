// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static long busyAt(long nanos) {
        return nanos;
    }

    @Test
    void busyDeltaIsTheCounterSinceTheFirstChunkOfTheBatch() {
        ChunkPacing pacing = new ChunkPacing(false, true);
        pacing.batchStarted();
        pacing.chunk(true, () -> true, restarts::incrementAndGet, () -> busyAt(100));
        pacing.chunk(true, () -> true, restarts::incrementAndGet, () -> busyAt(180));
        assertEquals(350 - 100, pacing.batchFinished(() -> busyAt(350)));
        assertEquals(0, restarts.get());
    }

    @Test
    void busyTimerOffReturnsNoDeltaAndNeverReadsTheCounter() {
        ChunkPacing pacing = new ChunkPacing(true, false);
        pacing.batchStarted();
        pacing.chunk(true, () -> true, restarts::incrementAndGet, () -> busyAt(100));
        assertEquals(-1, pacing.batchFinished(() -> {
            throw new AssertionError("the counter must not be read with the busy timer off");
        }));
        assertEquals(1, restarts.get());
    }

    @Test
    void busyOnlyDoesNotRestartTheWallTimer() {
        ChunkPacing pacing = new ChunkPacing(false, true);
        pacing.batchStarted();
        pacing.chunk(true, () -> true, restarts::incrementAndGet, () -> busyAt(10));
        assertEquals(0, restarts.get());
        assertEquals(40, pacing.batchFinished(() -> busyAt(50)));
    }

    @Test
    void tcpBatchHasNoBusyDelta() {
        ChunkPacing pacing = new ChunkPacing(false, true);
        pacing.batchStarted();
        pacing.chunk(true, () -> false, restarts::incrementAndGet, () -> busyAt(100));
        assertEquals(-1, pacing.batchFinished(() -> busyAt(900)));
    }

    @Test
    void emptyBatchHasNoBusyDeltaAndNeverReadsTheCounter() {
        ChunkPacing pacing = new ChunkPacing(false, true);
        pacing.batchStarted();
        assertEquals(-1, pacing.batchFinished(() -> {
            throw new AssertionError("an empty batch must keep vanilla timing");
        }));
        pacing.batchStarted();
        pacing.chunk(true, () -> true, restarts::incrementAndGet, () -> busyAt(7));
        assertEquals(10, pacing.batchFinished(() -> busyAt(17)));
    }

    @Test
    void mainThreadReplayDoesNotMoveTheBusyMark() {
        ChunkPacing pacing = new ChunkPacing(false, true);
        pacing.batchStarted();
        pacing.chunk(false, () -> true, restarts::incrementAndGet, () -> busyAt(1));
        pacing.chunk(true, () -> true, restarts::incrementAndGet, () -> busyAt(200));
        assertEquals(60, pacing.batchFinished(() -> busyAt(260)));
    }

    @Test
    void anUnavailableCounterGivesNoDelta() {
        ChunkPacing pacing = new ChunkPacing(false, true);
        pacing.batchStarted();
        pacing.chunk(true, () -> true, restarts::incrementAndGet, () -> -1L);
        assertEquals(-1, pacing.batchFinished(() -> busyAt(500)));
    }

    @Test
    void theBusyActivationIsLoggedOnce() {
        ChunkPacing pacing = new ChunkPacing(false, true);
        assertTrue(pacing.takeBusyActivation());
        assertFalse(pacing.takeBusyActivation());
        assertFalse(new ChunkPacing(false, false).takeBusyActivation());
    }
}
