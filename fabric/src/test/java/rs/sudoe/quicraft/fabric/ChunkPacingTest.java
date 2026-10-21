// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkPacingTest {
    @Test
    void timerRestartsOnlyForEnabledV3FirstChunk() {
        assertTrue(ChunkPacing.restartTimer(true, true, true));
        assertFalse(ChunkPacing.restartTimer(false, true, true));
        assertFalse(ChunkPacing.restartTimer(true, false, true));
        assertFalse(ChunkPacing.restartTimer(true, true, false));
    }

    @Test
    void theFirstChunkTimerIsOnUnlessTurnedOff() {
        boolean off = "false".equalsIgnoreCase(System.getProperty("quicraft.v3.batchTimerFromFirstChunk"));
        assertTrue(ChunkPacing.BATCH_TIMER_FROM_FIRST_CHUNK != off);
    }
}
