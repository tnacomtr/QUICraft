// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tracks one clientbound chunk batch and produces its BATCH_JOIN dependencies (docs/protocol.md
 * 13.19). chunk_batch_start is a CONTROL/view gate, so every install in the batch waits for it
 * through V. The finish record depends on the start and on the per-stream maximum of every
 * install, not only on the last stream. The adapter must reject nested or unmatched markers and
 * must not mix epochs in one batch; this class enforces both.
 *
 * <p>Not thread-safe beyond its own monitor. Call order at the logical send point: {@link #begin},
 * one {@link #chunk} per install, {@link #finish}, then {@link #recordJoin} with the assigned
 * BATCH_JOIN sequence. The next chunk_batch_start takes {@link #previousJoinDependency()} as a
 * semantic predecessor (13.19).
 */
public final class BatchJoinTracker {
    /** Logical index of CHUNK[0], the stream every join lives on (13.19). */
    private static final int CHUNK0 = 2;

    private boolean active;
    private long activeId;
    private long startSequence;
    private long batchEpoch;
    private final Map<Integer, Long> installs = new TreeMap<Integer, Long>();
    private long lastJoinSequence;

    /**
     * Begins a batch after the CONTROL/view-gate start record has been assigned (13.19).
     *
     * @throws IllegalArgumentException for nesting or invalid arguments
     */
    public synchronized void begin(long id, long startSequence, long epoch) {
        if (id <= 0 || startSequence <= 0 || epoch < 0) {
            throw new IllegalArgumentException("batch");
        }
        if (active) {
            throw new IllegalArgumentException("nested batch");
        }
        active = true;
        activeId = id;
        this.startSequence = startSequence;
        batchEpoch = epoch;
        installs.clear();
    }

    /**
     * Records one assigned chunk install of the active batch (13.19). Its stream must be a chunk
     * stream and its epoch must equal the batch epoch.
     */
    public synchronized void chunk(long id, int logicalStream, long sequence, long epoch) {
        require(id, epoch);
        if (logicalStream < CHUNK0 || sequence <= 0) {
            throw new IllegalArgumentException("batch chunk");
        }
        Long previous = installs.get(logicalStream);
        if (previous == null || previous < sequence) {
            installs.put(logicalStream, sequence);
        }
    }

    /**
     * Ends the active batch and returns its BATCH_JOIN dependencies: the start on CONTROL, then the
     * per-stream maximum of every install (13.19). An empty batch depends on its start only.
     *
     * <p>An epoch mismatch on the active batch clears that batch before it throws, so a later
     * {@link #begin} is not rejected as nested.
     *
     * @throws IllegalArgumentException for an unmatched marker or an epoch mismatch
     */
    public synchronized Record.Dependency[] finish(long id, long epoch) {
        if (active && activeId == id && epoch != batchEpoch) {
            clearActive();
        }
        require(id, epoch);
        List<Record.Dependency> dependencies = new ArrayList<Record.Dependency>();
        dependencies.add(new Record.Dependency(LogicalStream.CONTROL, startSequence));
        for (Map.Entry<Integer, Long> install : installs.entrySet()) {
            dependencies.add(new Record.Dependency(install.getKey(), install.getValue()));
        }
        clearActive();
        return dependencies.toArray(new Record.Dependency[dependencies.size()]);
    }

    /**
     * Discards the active batch and its recorded installs at a protocol-state, epoch or disconnect
     * transition (13.19). {@link #lastJoinSequence} is kept, so the next chunk_batch_start still
     * depends on the last BATCH_JOIN that was sequenced. Does nothing when no batch is active.
     */
    public synchronized void abort() {
        clearActive();
    }

    private void clearActive() {
        active = false;
        activeId = 0;
        installs.clear();
        startSequence = 0;
        batchEpoch = 0;
    }

    /**
     * Records the assigned BATCH_JOIN sequence on CHUNK[0] so the next chunk_batch_start can depend
     * on it, even across empty batches (13.19).
     */
    public synchronized void recordJoin(long joinSequence) {
        if (joinSequence <= 0 || joinSequence < lastJoinSequence) {
            throw new IllegalArgumentException("join sequence");
        }
        lastJoinSequence = joinSequence;
    }

    /**
     * The explicit dependency that the next chunk_batch_start carries on CHUNK[0] (13.19), or null
     * before any BATCH_JOIN exists.
     */
    public synchronized Record.Dependency previousJoinDependency() {
        if (lastJoinSequence == 0) {
            return null;
        }
        return new Record.Dependency(CHUNK0, lastJoinSequence);
    }

    /** Whether a batch is active, so adapters can reject nested or unmatched markers (13.19). */
    public synchronized boolean active() {
        return active;
    }

    private void require(long id, long epoch) {
        if (!active || activeId != id) {
            throw new IllegalArgumentException("unknown batch");
        }
        if (epoch != batchEpoch) {
            throw new IllegalArgumentException("batch epoch");
        }
    }
}
