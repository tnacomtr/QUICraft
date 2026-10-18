// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.function.LongSupplier;

/**
 * The game executor's completion record for one side (docs/protocol.md 13.10), the input to executor-stall
 * detection. One instance per game executor: {@link #SERVER} for the dedicated or integrated server thread,
 * {@link #CLIENT} for the client's game thread. A connection watches the executor that consumes its own game
 * tasks, so a server connection never reads the client's record and the reverse.
 *
 * <p><b>Liveness.</b> The executor is stalled for a connection only while work for that connection is
 * pending on it: a unit delivered to the game and not yet applied. An idle executor never stalls. The stall
 * clock starts at the later of the executor's last completion and the moment the pending work arrived, so an
 * executor that has been idle for minutes does not look stalled the instant work reaches it. A stall is
 * recorded once that clock reaches {@link #THRESHOLD_NANOS} (13.10's "at least 1 second").
 *
 * <p><b>Clock discipline.</b> Everything is in one monotonic clock domain (the transport clock). A stall is
 * timestamped at its qualifying instant, {@code max(lastCompletion, pendingSince) + THRESHOLD}, and ends at the
 * completion that ended it, not at the instant a watchdog observed either. The first qualifying second is
 * therefore not excluded: the executor is only known to be stalled from the threshold on. A late watchdog thus
 * neither extends nor shortens a recorded stall.
 *
 * <p><b>Executor replacement.</b> {@link #replaceExecutor()} starts a new generation with a fresh completion
 * record. Sessions compare generations on every sync and end any stall recorded against the old executor.
 *
 * <p><b>Detector.</b> Only a platform that installed a real completion detector ({@link #markInstalled()}) may
 * offer multi-stream. A platform without one must not offer it: without a detector, executor time would count
 * toward the close clocks again (13.10). The core does not decide that; the adapter checks {@link #installed()}
 * before it admits a session.
 *
 * <p>Thread-safe: completions arrive from the game thread, sessions read on their event loop.
 */
public final class ExecutorStallWatch {
    /** 13.10: a recorded stall means the game executor completes no task for at least 1 second. */
    public static final long THRESHOLD_NANOS = 1_000_000_000L;

    /** The dedicated or integrated server's game executor. */
    public static final ExecutorStallWatch SERVER = new ExecutorStallWatch(System::nanoTime);
    /** The client's game executor. */
    public static final ExecutorStallWatch CLIENT = new ExecutorStallWatch(System::nanoTime);

    private final LongSupplier clock;
    private volatile long lastCompletion;
    private volatile long generation;
    private volatile boolean installed;

    /** @param clock the monotonic transport clock the sessions use */
    public ExecutorStallWatch(LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock");
        }
        this.clock = clock;
        this.lastCompletion = clock.getAsLong();
    }

    /** Records that the executor completed a task now (the adapter's completion hook). */
    public void markCompletion() {
        markCompletion(clock.getAsLong());
    }

    /** Records a completion at an explicit time. The record never moves backwards. */
    public void markCompletion(long atNanos) {
        if (atNanos > lastCompletion) {
            lastCompletion = atNanos;
        }
    }

    /** The time of the executor's last recorded completion. */
    public long lastCompletion() {
        return lastCompletion;
    }

    /**
     * The qualifying instant of the stall that pending work has run into, or -1 when there is no stall at
     * {@code now}: none pending ({@code pendingSince < 0}), or the threshold not yet reached.
     */
    public long stalledSince(long now, long pendingSince) {
        if (pendingSince < 0) {
            return -1;
        }
        long since = Math.max(lastCompletion, pendingSince) + THRESHOLD_NANOS;
        return now >= since ? since : -1;
    }

    /** The executor generation; increases on every {@link #replaceExecutor()}. */
    public long generation() {
        return generation;
    }

    /** The game executor was replaced (a new server or client game): a fresh completion record, a new generation. */
    public void replaceExecutor() {
        generation = generation + 1;
        lastCompletion = clock.getAsLong();
    }

    /** Marks a real completion detector as installed on this side. */
    public void markInstalled() {
        installed = true;
    }

    /** Whether a completion detector is installed; a /3 session may be admitted only when it is. */
    public boolean installed() {
        return installed;
    }
}
