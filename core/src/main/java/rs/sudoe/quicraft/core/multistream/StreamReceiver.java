// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.List;

/** Minimal record admission and dependency ordering policy for /3 receivers (13.10, 13.18). */
public final class StreamReceiver {
    /** Record-specific route and validation, evaluated before game execution. */
    public interface Validator {
        /** Validates the decoded record, including its shard and join semantics (13.18-13.19). */
        boolean validate(Record record);
    }

    private final long[] admitted;
    private final long[] enqueued;
    private final long[] applied;
    private final long[] epochs;
    private final List<Record>[] queues;
    private final Validator validator;
    private final ReceiveSlots slots;

    /** Creates N+2 independent receive lanes; records are admitted in contiguous sequence order. */
    @SuppressWarnings("unchecked")
    public StreamReceiver(int chunks, Validator validator) {
        LogicalStream.validateShardCount(chunks);
        if (validator == null) throw new IllegalArgumentException("validator");
        int size = chunks + 2;
        admitted = new long[size]; enqueued = new long[size]; applied = new long[size]; epochs = new long[size];
        queues = (List<Record>[]) new List<?>[size];
        for (int i = 0; i < size; i++) queues[i] = new ArrayList<Record>();
        this.validator = validator; slots = new ReceiveSlots(chunks);
    }

    /** Admits a decoded record after sequence, epoch, self-dependency, and injected route checks (13.10). */
    public boolean offer(Record record, long retainedBytes) {
        if (record == null || record.streamIndex() >= queues.length) return false;
        int stream = record.streamIndex();
        if (record.sequence() != admitted[stream] + 1 || record.epoch() != epochs[stream]
                || !validator.validate(record) || cyclic(record)) return false;
        if (!slots.reserve(stream, retainedBytes)) return false;
        queues[stream].add(record); admitted[stream] = record.sequence();
        return true;
    }

    /** Returns the first eligible head, prioritizing REALTIME over CONTROL (13.10). */
    public Record next() {
        int[] priority = {LogicalStream.REALTIME, LogicalStream.CONTROL};
        for (int stream : priority) {
            Record record = head(stream);
            if (record != null && ready(record)) return record;
        }
        for (int stream = 2; stream < queues.length; stream++) {
            Record record = head(stream);
            if (record != null && ready(record)) return record;
        }
        return null;
    }

    /** Marks FIFO enqueue for application timeout accounting only; dependencies continue to use D (13.10). */
    public void enqueued(int stream, long sequence) {
        checkHead(stream, sequence);
        enqueued[stream] = sequence;
    }

    /** Commits D only after the serial game handler has completed (13.10). */
    public void applied(int stream, long sequence, long retainedBytes) {
        checkHead(stream, sequence);
        if (enqueued[stream] != sequence) throw new IllegalStateException("not enqueued");
        queues[stream].remove(0); applied[stream] = sequence;
        slots.release(stream, retainedBytes);
    }

    /** Returns a defensive copy of D watermarks (13.10). */
    public long[] appliedWatermarks() { return applied.clone(); }
    /** Returns a defensive copy of Q watermarks (13.10). */
    public long[] enqueuedWatermarks() { return enqueued.clone(); }
    /** Reserves the shared single execution workspace before decode or execution allocation (13.12). */
    public boolean reserveWorkspace(long bytes) { return slots.reserveWorkspace(bytes); }
    /** Releases shared execution workspace after the unit completes (13.12). */
    public void releaseWorkspace(long bytes) { slots.releaseWorkspace(bytes); }

    private Record head(int stream) { return queues[stream].isEmpty() ? null : queues[stream].get(0); }
    private boolean ready(Record record) {
        for (Record.Dependency dependency : record.dependencies()) {
            if (dependency.streamIndex() >= applied.length || dependency.streamIndex() == record.streamIndex()
                    || Long.compareUnsigned(dependency.sequence(), applied[dependency.streamIndex()]) > 0) return false;
        }
        return true;
    }
    private boolean cyclic(Record record) {
        // A direct forward edge to a stream that already depends on this record is provable immediately.
        for (Record.Dependency dependency : record.dependencies()) {
            if (dependency.streamIndex() >= queues.length) return true;
            Record other = head(dependency.streamIndex());
            if (other != null) for (Record.Dependency back : other.dependencies())
                if (back.streamIndex() == record.streamIndex() && back.sequence() >= record.sequence()) return true;
        }
        return false;
    }
    private void checkHead(int stream, long sequence) {
        if (stream < 0 || stream >= queues.length || head(stream) == null || head(stream).sequence() != sequence)
            throw new IllegalStateException("not current stream head");
    }
}
