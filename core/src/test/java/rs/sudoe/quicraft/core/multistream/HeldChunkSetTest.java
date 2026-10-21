// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The first-install exemption bookkeeping of 13.21. */
class HeldChunkSetTest {
    @Test
    void aFirstInstallSkipsTheAckDependency() {
        HeldChunkSet set = new HeldChunkSet();
        assertFalse(set.install(3, -7), "never held, never forgotten");
        assertFalse(set.install(4, -7));
        assertEquals(2, set.heldCount());
    }

    @Test
    void aReplacementOfAHeldChunkNeedsTheAck() {
        HeldChunkSet set = new HeldChunkSet();
        assertFalse(set.install(1, 1));
        assertTrue(set.install(1, 1));
        assertTrue(set.install(1, 1), "still held");
        assertEquals(1, set.heldCount());
    }

    @Test
    void anInstallAfterAForgetInTheRingNeedsTheAck() {
        HeldChunkSet set = new HeldChunkSet();
        set.install(5, 5);
        set.forget(5, 5);
        assertEquals(0, set.heldCount());
        assertEquals(1, set.forgottenCount());
        assertTrue(set.install(5, 5), "a forget does not clear the client's prediction map");
    }

    @Test
    void aForgetOfAnUnknownChunkIsRemembered() {
        HeldChunkSet set = new HeldChunkSet();
        set.forget(9, 9);
        assertTrue(set.install(9, 9));
    }

    @Test
    void theRingRemembersTheLast1024ForgottenPositionsOnly() {
        HeldChunkSet set = new HeldChunkSet();
        for (int i = 0; i < HeldChunkSet.RING_CAPACITY + 1; i++) {
            set.forget(i, 0);
        }
        assertEquals(HeldChunkSet.RING_CAPACITY, set.forgottenCount());
        assertFalse(set.install(0, 0), "the oldest forgotten position fell out of the ring");
        assertTrue(set.install(1, 0), "position 1 is still in the ring");
        assertTrue(set.install(HeldChunkSet.RING_CAPACITY, 0), "the newest is in the ring");
    }

    @Test
    void forgettingTheSamePositionAgainRefreshesItInTheRing() {
        HeldChunkSet set = new HeldChunkSet();
        set.forget(0, 0);
        for (int i = 1; i < HeldChunkSet.RING_CAPACITY; i++) {
            set.forget(i, 0);
        }
        set.forget(0, 0);
        set.forget(HeldChunkSet.RING_CAPACITY, 0);
        assertTrue(set.install(0, 0), "refreshed, so it outlived position 1");
        assertEquals(HeldChunkSet.RING_CAPACITY, set.forgottenCount());
    }

    @Test
    void heldSetOverflowFailsSafeSoEveryInstallNeedsTheAck() {
        HeldChunkSet set = new HeldChunkSet();
        for (int i = 0; i < HeldChunkSet.HELD_CAPACITY; i++) {
            assertFalse(set.install(i, 0), "first install " + i);
        }
        assertEquals(HeldChunkSet.HELD_CAPACITY, set.heldCount());
        assertTrue(set.install(HeldChunkSet.HELD_CAPACITY, 0), "above the cap the set no longer tracks");
        assertTrue(set.untrusted());
        assertTrue(set.install(-1, -1), "a position never seen still needs the ack once the set is untrusted");
        set.forget(0, 0);
        assertTrue(set.install(12345, 6789), "forgets cannot make an untrusted set trusted");
    }

    @Test
    void anUnknownLifecycleRecordFailsSafeUntilTheEpochChanges() {
        HeldChunkSet set = new HeldChunkSet();
        set.install(1, 1);
        set.markUnknown();
        assertTrue(set.install(2, 2));
        assertTrue(set.untrusted());
    }

    @Test
    void anEpochChangeClearsTheHeldSetTheRingAndTheUntrustedFlag() {
        HeldChunkSet set = new HeldChunkSet();
        set.install(1, 1);
        set.forget(2, 2);
        set.markUnknown();
        set.clear();
        assertFalse(set.untrusted());
        assertEquals(0, set.heldCount());
        assertEquals(0, set.forgottenCount());
        assertFalse(set.install(1, 1), "the client dropped every chunk at the epoch change");
        assertFalse(set.install(2, 2), "and the ring is gone with it");
    }

    @Test
    void nothingIsAllocatedUntilTheFirstInstallOrForget() {
        HeldChunkSet set = new HeldChunkSet();
        assertEquals(0, set.footprintWords());
        set.install(1, 1);
        assertEquals(64, set.heldTableLength(), "the held table starts small");
        assertEquals(0, set.ringLength(), "the ring waits for the first forget");
        set.forget(1, 1);
        assertEquals(HeldChunkSet.RING_CAPACITY, set.ringLength());
    }

    @Test
    void aTravellingPlayersFootprintStaysSmall() {
        HeldChunkSet set = new HeldChunkSet();
        // A 21x21 view: 441 held chunks, then a full ring of forgets.
        for (int x = 0; x < 21; x++) {
            for (int z = 0; z < 21; z++) {
                set.install(x, z);
            }
        }
        for (int i = 0; i < 2 * HeldChunkSet.RING_CAPACITY; i++) {
            set.forget(100 + i, 100);
        }
        assertEquals(441, set.heldCount());
        assertTrue(set.heldTableLength() <= 1024, "held table words: " + set.heldTableLength());
        assertTrue(set.footprintWords() <= 1024 + 256, "total words: " + set.footprintWords());
    }

    @Test
    void theTableNeverExceedsTwiceTheHeldCapacityAndClearFreesIt() {
        HeldChunkSet set = new HeldChunkSet();
        for (int i = 0; i < HeldChunkSet.HELD_CAPACITY; i++) {
            set.install(i, 7);
        }
        assertTrue(set.heldTableLength() <= 2 * HeldChunkSet.HELD_CAPACITY);
        set.clear();
        assertEquals(0, set.footprintWords());
        set.install(1, 2);
        set.markUnknown();
        assertEquals(0, set.footprintWords(), "an untrusted set holds no arrays");
    }

    @Test
    void positionsWithZeroAndNegativeAndExtremeCoordinatesWork() {
        HeldChunkSet set = new HeldChunkSet();
        int[][] positions = {{0, 0}, {-1, -1}, {Integer.MIN_VALUE, 0}, {0, Integer.MIN_VALUE},
                {Integer.MAX_VALUE, Integer.MAX_VALUE}, {0, 1}, {1, 0}};
        for (int[] p : positions) {
            assertFalse(set.install(p[0], p[1]), p[0] + "," + p[1]);
        }
        for (int[] p : positions) {
            assertTrue(set.install(p[0], p[1]), "held " + p[0] + "," + p[1]);
        }
        set.forget(0, 0);
        assertEquals(positions.length - 1, set.heldCount());
        assertTrue(set.install(0, 0), "in the ring after the forget");
    }

    /** Randomized comparison against a HashSet and a bounded LinkedHashSet, including deletes inside clusters. */
    @Test
    void behavesLikeAReferenceModelUnderRandomOperations() {
        java.util.Random random = new java.util.Random(0x5EEDL);
        for (int round = 0; round < 40; round++) {
            HeldChunkSet set = new HeldChunkSet();
            java.util.Set<Long> held = new java.util.HashSet<Long>();
            java.util.LinkedHashSet<Long> ring = new java.util.LinkedHashSet<Long>();
            int space = 4 + random.nextInt(round < 20 ? 40 : 400);
            for (int step = 0; step < 6000; step++) {
                int x = random.nextInt(space) - space / 3;
                int z = random.nextInt(3) - 1;
                long k = ((long) x << 32) | (z & 0xffffffffL);
                int op = random.nextInt(10);
                if (op == 0 && step % 997 == 0) {
                    set.clear();
                    held.clear();
                    ring.clear();
                } else if (op < 6) {
                    boolean expected = held.contains(k) || ring.contains(k);
                    held.add(k);
                    assertEquals(expected, set.install(x, z), "round " + round + " step " + step);
                } else {
                    held.remove(k);
                    ring.remove(k);
                    ring.add(k);
                    if (ring.size() > HeldChunkSet.RING_CAPACITY) {
                        java.util.Iterator<Long> eldest = ring.iterator();
                        eldest.next();
                        eldest.remove();
                    }
                    set.forget(x, z);
                }
                assertEquals(held.size(), set.heldCount(), "round " + round + " step " + step);
                assertEquals(ring.size(), set.forgottenCount(), "round " + round + " step " + step);
            }
        }
    }
}
