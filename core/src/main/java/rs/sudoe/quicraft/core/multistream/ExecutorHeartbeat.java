// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Owner-driven heartbeat that reserves, submits and completes {@link ExecutorProgressWatch} probes.
 *
 * <p>The listener or client adapter owns exactly one heartbeat per game executor and calls
 * {@link #tick()} on its own cadence: 250 ms by design (docs/protocol.md 13.10), the same heartbeat
 * for every session, never a per-session ticker. This class starts no scheduler. A tick reserves one
 * probe under the watch's monitor, then submits it to the executor with the monitor released; at
 * most one probe is outstanding, so ticks while a probe is pending submit nothing. The submitted
 * task first asks {@code onExecutor} whether it is running on the expected executor: a false or
 * throwing answer, or a rejected submission, invalidates the watch, because a probe that did not
 * demonstrably run on the executor proves nothing. A task that does run there completes the
 * reserved token, which is what marks readiness.
 *
 * <p>Exceptions from the executor submission and from the identity check are caught and turned into
 * invalidation: the heartbeat runs on the owner's path and fails safe, never propagating. A
 * rejected or mis-identified probe leaves the snapshot not ready ({@code valid && proven}).
 *
 * <p>{@link #close()} is idempotent, invalidates the watch, and makes every later tick and every
 * queued probe callback ineffective: an invalid watch is never revived, so a completion that runs
 * after close can never prove readiness. A close racing a reservation or a callback is safe for the
 * same reason: whichever order they interleave in, the invalid flag wins and the watch stays not
 * ready.
 *
 * <p>Thread-safe: the owner calls {@link #tick()} and {@link #close()}, the executor runs probe tasks.
 */
public final class ExecutorHeartbeat {
    private final ExecutorProgressWatch watch;
    private final Executor executor;
    private final BooleanSupplier onExecutor;
    private volatile boolean closed;

    /**
     * @param watch the watch of the executor incarnation this heartbeat probes
     * @param executor the game executor the probe must run on
     * @param onExecutor identity check evaluated inside the probe task; true only on the expected executor
     */
    public ExecutorHeartbeat(ExecutorProgressWatch watch, Executor executor, BooleanSupplier onExecutor) {
        if (watch == null) {
            throw new IllegalArgumentException("watch");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor");
        }
        if (onExecutor == null) {
            throw new IllegalArgumentException("onExecutor");
        }
        this.watch = watch;
        this.executor = executor;
        this.onExecutor = onExecutor;
    }

    /** One owner tick: reserve at most one probe and submit it, or do nothing while one is pending. */
    public void tick() {
        if (closed) {
            return;
        }
        long token = watch.probeSubmitted();
        if (token == 0) {
            return;
        }
        try {
            executor.execute(() -> probe(token));
        } catch (RuntimeException rejected) {
            watch.invalidate();
        }
    }

    /**
     * Runs on the executor thread: the probe may only complete when the identity check places it on
     * the expected executor; a false or throwing check invalidates the watch instead.
     */
    private void probe(long token) {
        boolean onExpected;
        try {
            onExpected = onExecutor.getAsBoolean();
        } catch (Throwable identityFailure) {
            watch.invalidate();
            return;
        }
        if (!onExpected) {
            watch.invalidate();
            return;
        }
        watch.probeCompleted(token);
    }

    /**
     * Stops the heartbeat idempotently and invalidates the watch, so queued probe callbacks cannot
     * prove readiness afterwards. Safe to call more than once.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        watch.invalidate();
    }
}
