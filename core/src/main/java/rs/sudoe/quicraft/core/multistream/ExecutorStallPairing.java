// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import rs.sudoe.quicraft.core.resource.PressureRanking;

/**
 * The listener-wide pairing of executor stalls into the inbound pressure ranking (docs/protocol.md 13.12).
 *
 * <p>Several sessions can be in a stall on the same executor at once, but a {@link PressureRanking} has no
 * nesting counter: its {@code stallStart} ignores a second start and its first {@code stallEnd} closes the
 * stall for everyone. This class keeps the count instead. The ranking hears one start when the first session
 * enters a stall and one end when the last session leaves it, so the ranking's exclusion covers exactly the
 * time any session was stalled.
 *
 * <p>Discipline: {@link #enter} and {@link #exit} must balance. An {@link #exit} with nothing entered is a
 * bookkeeping bug and throws, rather than silently ending another session's stall. A session that closes while
 * stalled must {@link #exit} (its release does), so a missing end cannot leave the ranking excluding time
 * forever.
 *
 * <p>Thread-safe: called from sessions' event loops, which can be more than one.
 */
public final class ExecutorStallPairing {
    private final PressureRanking ranking;
    private int depth;

    /** @param ranking the inbound ranking whose stall exclusion this pairs */
    public ExecutorStallPairing(PressureRanking ranking) {
        if (ranking == null) {
            throw new IllegalArgumentException("ranking");
        }
        this.ranking = ranking;
    }

    /** One session entered a stall at {@code atNanos}; the first entry starts the ranking's exclusion. */
    public synchronized void enter(long atNanos) {
        if (depth == 0) {
            ranking.stallStart(atNanos);
        }
        depth++;
    }

    /**
     * One session left a stall at {@code atNanos}; the last exit ends the ranking's exclusion.
     *
     * @throws IllegalStateException when no session is in a stall
     */
    public synchronized void exit(long atNanos) {
        if (depth == 0) {
            throw new IllegalStateException("executor stall exit without a matching enter");
        }
        depth--;
        if (depth == 0) {
            ranking.stallEnd(atNanos);
        }
    }

    /** The number of sessions currently in a stall (0 when none). */
    public synchronized int depth() {
        return depth;
    }
}
