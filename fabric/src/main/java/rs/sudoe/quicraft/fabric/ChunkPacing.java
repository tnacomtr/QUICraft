// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Chunk-batch pacing on {@code /3} connections, one instance per client packet listener.
 *
 * <p>Vanilla's client times a chunk batch from {@code chunk_batch_start} to {@code chunk_batch_finished}, and the
 * server sends that many chunks per tick. On {@code /3} the start record rides the control stream and arrives well
 * before the chunk records on the chunk streams, so the measured time per chunk is inflated and the rate stalls at
 * a few chunks per tick (157 chunks on a clean link: 2.5 s against TCP's 0.86 s, benchmarks.md). The client
 * therefore restarts the batch timer when the batch's first chunk arrives, so it measures what TCP measures. On by
 * default; {@code -Dquicraft.v3.batchTimerFromFirstChunk=false} keeps vanilla timing.
 *
 * <p>Busy timer (prototype, {@code -Dquicraft.v3.batchTimerBusy=true}, default off): instead of the wall time from
 * the batch's first chunk, the batch interval is the network-thread busy time the connection spent decoding and
 * firing its packets since the start of the decode of that first chunk's delivery
 * ({@link rs.sudoe.quicraft.bridge.GameLanes#busyMarkForCurrentDelivery()}). Transport
 * gating between units is then not counted. Empty batches and {@code /2} or TCP keep vanilla timing.
 *
 * <p>Only the network-thread pass of a chunk counts: vanilla's chunk handler runs once on the network thread and
 * again on the main thread, which lags, so a main-thread replay of an older batch's chunk must not consume the next
 * batch's restart.
 *
 * <p>A server-side floor on the chunks per tick was measured and rejected: under 75 ms delay and 2% loss it made
 * the server burst past the link and 3 of 6 sessions closed on the blocked-head timer (0x12).
 */
public final class ChunkPacing {
    /** Client: restart the batch timer when the first chunk arrives. */
    public static final boolean BATCH_TIMER_FROM_FIRST_CHUNK = enabled("quicraft.v3.batchTimerFromFirstChunk");
    /** Client: time the batch by the connection's network-thread busy time instead of wall time. */
    public static final boolean BATCH_TIMER_BUSY = Boolean.getBoolean("quicraft.v3.batchTimerBusy");

    private final boolean enabled;
    private final boolean busy;
    private volatile boolean awaitingFirstChunk;
    /** Busy-time counter reading when this batch's first chunk arrived; valid while {@link #marked}. */
    private long busyMark;
    private boolean marked;
    private boolean busyActivationLogged;

    public ChunkPacing() {
        this(BATCH_TIMER_FROM_FIRST_CHUNK, BATCH_TIMER_BUSY);
    }

    ChunkPacing(boolean enabled) {
        this(enabled, false);
    }

    ChunkPacing(boolean enabled, boolean busy) {
        this.enabled = enabled;
        this.busy = busy;
    }

    /** chunk_batch_start was handled: the next network-thread chunk restarts the timer. */
    public void batchStarted() {
        if (enabled || busy) {
            awaitingFirstChunk = true;
        }
    }

    /**
     * A level chunk is being handled. Once per batch, on the first network-thread chunk of a {@code /3} connection:
     * records the busy counter when the busy timer is on, and restarts the wall timer through {@code restart} when
     * the first-chunk timer is on. A throw clears the pending restart and propagates.
     */
    public void chunk(boolean networkThread, BooleanSupplier v3, Runnable restart) {
        chunk(networkThread, v3, restart, () -> -1L);
    }

    /** As {@link #chunk(boolean, BooleanSupplier, Runnable)}, with the connection's busy counter (negative: unknown). */
    public void chunk(boolean networkThread, BooleanSupplier v3, Runnable restart, LongSupplier busyNanos) {
        if (!networkThread || !awaitingFirstChunk) {
            return;
        }
        awaitingFirstChunk = false;
        if (!v3.getAsBoolean()) {
            return;
        }
        if (busy) {
            long mark = readBusy(busyNanos);
            marked = mark >= 0;
            busyMark = mark;
        }
        if (enabled) {
            restart.run();
        }
    }

    /** chunk_batch_finished was handled: an empty batch keeps vanilla timing. */
    public void batchFinished() {
        awaitingFirstChunk = false;
        marked = false;
    }

    /**
     * chunk_batch_finished was handled. Returns the busy nanoseconds this batch spent since its first chunk, or
     * {@code -1} when the busy timer does not apply (off, no chunk in this batch, or the counter is unavailable). The
     * busy counter is only read when a first chunk was marked.
     */
    public long batchFinished(LongSupplier busyNanos) {
        awaitingFirstChunk = false;
        boolean wasMarked = marked;
        marked = false;
        if (!busy || !wasMarked) {
            return -1L;
        }
        long now = readBusy(busyNanos);
        if (now < 0 || now < busyMark) {
            return -1L;
        }
        return now - busyMark;
    }

    /**
     * Reads a busy counter for the batch timer. A throwing supplier counts as unavailable (-1), so the batch keeps
     * vanilla timing and the wall restart still runs: a broken busy hook never changes the wall timer.
     */
    private static long readBusy(LongSupplier busyNanos) {
        try {
            return busyNanos.getAsLong();
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** One-shot per listener: true the first time it is asked while the busy timer is on (for the INFO log line). */
    public boolean takeBusyActivation() {
        if (!busy || busyActivationLogged) {
            return false;
        }
        busyActivationLogged = true;
        return true;
    }

    private static boolean enabled(String name) {
        try {
            return !"false".equalsIgnoreCase(System.getProperty(name, "true"));
        } catch (Throwable t) {
            return false;
        }
    }
}
