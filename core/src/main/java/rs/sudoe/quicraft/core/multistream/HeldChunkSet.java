// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The sender's per-session view of which chunk positions the client holds (docs/protocol.md 13.21), used to
 * decide whether a chunk install (45) may skip the {@code ack} dependency.
 *
 * <p>A position is HELD from its install record until its forget record. A first install, for a position
 * that is neither held nor among the last {@link #RING_CAPACITY} forgotten positions, cannot have a pending
 * block prediction on the client, so it need not wait for the last ACK-stream record. Every other install
 * (a replacement, or a re-install shortly after a forget) must wait. The ring covers the forget case because
 * a forget does not clear the client's prediction map.
 *
 * <p>Fail safe: above {@link #HELD_CAPACITY} held positions the set stops tracking and every install needs
 * the ack dependency, until {@link #clear()} (epoch change). {@link #markUnknown()} does the same for a
 * chunk lifecycle record the sender could not track, such as one inside a bundle.
 *
 * <p>Not thread-safe; the send driver calls it under its monitor.
 */
public final class HeldChunkSet {
    /** Largest number of held positions tracked (13.21). */
    public static final int HELD_CAPACITY = 4096;
    /** Number of most recently forgotten positions remembered (13.21). */
    public static final int RING_CAPACITY = 1024;

    private final Set<Long> held = new LinkedHashSet<Long>();
    private final Set<Long> forgotten = new LinkedHashSet<Long>();
    private boolean untrusted;

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    /**
     * Records an install of the chunk and returns true when it must carry the {@code ack} dependency.
     */
    public boolean install(int chunkX, int chunkZ) {
        long key = key(chunkX, chunkZ);
        boolean needsAck = untrusted || held.contains(key) || forgotten.contains(key);
        if (!untrusted && !held.contains(key)) {
            if (held.size() >= HELD_CAPACITY) {
                untrusted = true;
                held.clear();
                forgotten.clear();
                return true;
            }
            held.add(key);
        }
        return needsAck;
    }

    /** Records a forget of the chunk. A forget always carries the {@code ack} dependency. */
    public void forget(int chunkX, int chunkZ) {
        if (untrusted) {
            return;
        }
        long key = key(chunkX, chunkZ);
        held.remove(key);
        forgotten.remove(key);
        forgotten.add(key);
        if (forgotten.size() > RING_CAPACITY) {
            Iterator<Long> eldest = forgotten.iterator();
            eldest.next();
            eldest.remove();
        }
    }

    /** From now on every install needs the ack dependency, until {@link #clear()}. */
    public void markUnknown() {
        untrusted = true;
        held.clear();
        forgotten.clear();
    }

    /**
     * Drops everything on an epoch change (respawn, dimension change, configuration): the client discards
     * all its chunks there, so no prediction can outlive it.
     */
    public void clear() {
        untrusted = false;
        held.clear();
        forgotten.clear();
    }

    /** Number of held positions, for tests. */
    int heldCount() {
        return held.size();
    }

    /** Number of remembered forgotten positions, for tests. */
    int forgottenCount() {
        return forgotten.size();
    }

    /** True once the set stopped tracking (overflow or unknown lifecycle record). */
    boolean untrusted() {
        return untrusted;
    }
}
