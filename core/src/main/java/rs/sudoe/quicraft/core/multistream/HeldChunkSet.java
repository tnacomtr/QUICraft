// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/**
 * The sender's per-session view of which chunk positions the client holds (docs/protocol.md 13.21), used to
 * decide whether a chunk install (45) may skip the {@code ack} dependency.
 *
 * <p>A position is HELD from its install record until its forget record. A first install, for a position
 * that is neither held nor among the last {@link #RING_CAPACITY} forgotten positions, cannot have a pending
 * block prediction on the client, so it need not wait for the last ACK-stream record. Every other install
 * (a replacement, or a re-install shortly after a forget) must wait. The ring covers the forget case because
 * a forget does not clear the client's prediction map; a forget-then-reinstall race needs the reinstall within
 * about one round trip, so a short ring is enough.
 *
 * <p>Fail safe: above {@link #HELD_CAPACITY} held positions the set stops tracking and every install needs
 * the ack dependency, until {@link #clear()} (epoch change). {@link #markUnknown()} does the same for a
 * chunk lifecycle record the sender could not track, such as one inside a bundle.
 *
 * <p>Memory (scale target of 50,000 connections per node): primitive arrays only, allocated on the first install
 * or forget and released by {@link #clear()}, {@link #markUnknown()} and overflow. The held set is an
 * open-addressing {@code long[]} table at load factor at most 1/2 that doubles from 64 to 8192 entries; the ring
 * is a {@code long[256]}.
 *
 * <p>Not thread-safe; the send driver calls it under its monitor.
 */
public final class HeldChunkSet {
    /** Largest number of held positions tracked (13.21). */
    public static final int HELD_CAPACITY = 4096;
    /** Number of most recently forgotten positions remembered (13.21). */
    public static final int RING_CAPACITY = 256;

    private static final int INITIAL_TABLE = 64;
    /** The empty slot marker; a position whose key is 0 is tracked by {@link #hasZero} instead. */
    private static final long EMPTY = 0L;

    /** Open-addressing table (linear probing, backward-shift delete), or null before the first install. */
    private long[] table;
    private int heldSize;
    private boolean hasZero;
    /** Circular buffer of forgotten keys, oldest first from {@link #ringHead}, or null before the first forget. */
    private long[] ring;
    private int ringHead;
    private int ringSize;
    private boolean untrusted;

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    private static int hash(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32) & mask;
    }

    /**
     * Records an install of the chunk and returns true when it must carry the {@code ack} dependency.
     */
    public boolean install(int chunkX, int chunkZ) {
        long key = key(chunkX, chunkZ);
        boolean held = containsHeld(key);
        boolean needsAck = untrusted || held || inRing(key);
        if (!untrusted && !held) {
            if (heldSize >= HELD_CAPACITY) {
                markUnknown();
                return true;
            }
            addHeld(key);
        }
        return needsAck;
    }

    /** Records a forget of the chunk. A forget always carries the {@code ack} dependency. */
    public void forget(int chunkX, int chunkZ) {
        if (untrusted) {
            return;
        }
        long key = key(chunkX, chunkZ);
        removeHeld(key);
        if (ring == null) {
            ring = new long[RING_CAPACITY];
        }
        int at = ringIndexOf(key);
        if (at >= 0) {
            // Refresh recency: close the gap, then append at the tail.
            for (int i = at; i + 1 < ringSize; i++) {
                ring[(ringHead + i) % RING_CAPACITY] = ring[(ringHead + i + 1) % RING_CAPACITY];
            }
            ringSize--;
        }
        if (ringSize == RING_CAPACITY) {
            ringHead = (ringHead + 1) % RING_CAPACITY;
            ringSize--;
        }
        ring[(ringHead + ringSize) % RING_CAPACITY] = key;
        ringSize++;
    }

    /** From now on every install needs the ack dependency, until {@link #clear()}. */
    public void markUnknown() {
        release();
        untrusted = true;
    }

    /**
     * Drops everything on an epoch change (respawn, dimension change, configuration): the client discards
     * all its chunks there, so no prediction can outlive it.
     */
    public void clear() {
        release();
        untrusted = false;
    }

    private void release() {
        table = null;
        ring = null;
        heldSize = 0;
        hasZero = false;
        ringHead = 0;
        ringSize = 0;
    }

    // --- Held set.

    private boolean containsHeld(long key) {
        if (key == EMPTY) {
            return hasZero;
        }
        if (table == null) {
            return false;
        }
        int mask = table.length - 1;
        for (int i = hash(key, mask); table[i] != EMPTY; i = (i + 1) & mask) {
            if (table[i] == key) {
                return true;
            }
        }
        return false;
    }

    private void addHeld(long key) {
        if (key == EMPTY) {
            hasZero = true;
            heldSize++;
            return;
        }
        if (table == null) {
            table = new long[INITIAL_TABLE];
        } else if ((heldSize + 1) * 2 > table.length) {
            grow();
        }
        insert(table, key);
        heldSize++;
    }

    private static void insert(long[] into, long key) {
        int mask = into.length - 1;
        int i = hash(key, mask);
        while (into[i] != EMPTY) {
            i = (i + 1) & mask;
        }
        into[i] = key;
    }

    private void grow() {
        long[] bigger = new long[table.length * 2];
        for (long key : table) {
            if (key != EMPTY) {
                insert(bigger, key);
            }
        }
        table = bigger;
    }

    private void removeHeld(long key) {
        if (key == EMPTY) {
            if (hasZero) {
                hasZero = false;
                heldSize--;
            }
            return;
        }
        if (table == null) {
            return;
        }
        int mask = table.length - 1;
        int i = hash(key, mask);
        while (table[i] != EMPTY && table[i] != key) {
            i = (i + 1) & mask;
        }
        if (table[i] == EMPTY) {
            return;
        }
        heldSize--;
        // Backward-shift delete: pull later members of the cluster into the hole when their home allows it.
        int hole = i;
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            long moved = table[j];
            if (moved == EMPTY) {
                break;
            }
            int home = hash(moved, mask);
            boolean stays = hole <= j ? (hole < home && home <= j) : (hole < home || home <= j);
            if (!stays) {
                table[hole] = moved;
                hole = j;
            }
        }
        table[hole] = EMPTY;
    }

    // --- Ring.

    private int ringIndexOf(long key) {
        for (int i = 0; i < ringSize; i++) {
            if (ring[(ringHead + i) % RING_CAPACITY] == key) {
                return i;
            }
        }
        return -1;
    }

    private boolean inRing(long key) {
        return ring != null && ringIndexOf(key) >= 0;
    }

    // --- Diagnostics for tests.

    /** Number of held positions. */
    int heldCount() {
        return heldSize;
    }

    /** Number of remembered forgotten positions. */
    int forgottenCount() {
        return ringSize;
    }

    /** True once the set stopped tracking (overflow or unknown lifecycle record). */
    boolean untrusted() {
        return untrusted;
    }

    /** Length of the held table, 0 before the first install. */
    int heldTableLength() {
        return table == null ? 0 : table.length;
    }

    /** Length of the ring array, 0 before the first forget. */
    int ringLength() {
        return ring == null ? 0 : ring.length;
    }

    /** Primitive words allocated by this instance. */
    int footprintWords() {
        return heldTableLength() + ringLength();
    }
}
