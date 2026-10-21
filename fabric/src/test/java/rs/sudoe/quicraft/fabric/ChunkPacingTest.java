// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void floorLiftsLowRatesOnV3Only() {
        assertEquals(16f, ChunkPacing.clampRate(3f, 16, true));
        assertEquals(40f, ChunkPacing.clampRate(40f, 16, true));
        assertEquals(3f, ChunkPacing.clampRate(3f, 16, false));
        assertEquals(3f, ChunkPacing.clampRate(3f, 0, true));
    }

    @Test
    void floorNeverExceedsVanillaCapAndKeepsNaN() {
        assertEquals(64f, ChunkPacing.clampRate(1f, 500, true));
        assertTrue(Float.isNaN(ChunkPacing.clampRate(Float.NaN, 16, true)));
    }
}
