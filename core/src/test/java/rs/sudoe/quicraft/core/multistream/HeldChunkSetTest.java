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
}
