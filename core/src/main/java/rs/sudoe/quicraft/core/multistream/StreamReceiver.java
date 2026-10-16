// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;


/**
 * Receiver-side ordering policy for the N+2 streams of one direction (protocol.md 13.10, 13.12,
 * 13.18, 13.19). It holds at most one head unit per stream and decides which head may be enqueued
 * to the game executor. It never decodes game bodies and never runs game code.
 *
 * <p>Two watermarks per stream:
 * <ul>
 *   <li>{@code Q} (enqueued): set when a validated, ready head is handed to the executor. It only
 *       drives timeout accounting; it never releases a dependency, frees a slot or changes epochs.</li>
 *   <li>{@code D} (applied): set after the game's serial handler has applied the unit. Only D
 *       releases dependencies, frees the stream's slot and advances the epoch at a boundary.</li>
 * </ul>
 *
 * <p>Protocol violations are returned as {@link ReceiveClose} values with code 0x11, never thrown.
 * Refusals (a stream already holds its head) are returned as {@link Admission#busy()}. Nothing here
 * closes a connection by itself; the caller acts on the returned close.
 *
 * <p>Not thread-safe; the receive path that owns the connection drives it.
 */
public final class StreamReceiver {
    /** Record kind values (13.4): END is CONTROL-only; ACK_JOIN and BATCH_JOIN are chunk-only. */
    private static final int KIND_END = RecordCodec.KIND_END;
    private static final int KIND_ACK_JOIN = 2;
    private static final int KIND_BATCH_JOIN = 3;

    /** Injected checks the policy cannot make alone: shard placement and fence validation (13.10, 13.18). */
    public interface Validator {
        /** Returns true when the record's declared route and position are valid for its stream and epoch. */
        boolean valid(int stream, Record record, ReceiveRoute route);
    }

    /** Outcome of {@link #offer}. */
    public static final class Admission {
        /** Result categories. */
        public enum Kind {
            /** The unit is admitted and its slot is reserved. */
            ADMITTED,
            /** The stream already holds its head; stop reading until it commits (refusal, not an error). */
            BUSY,
            /** The protocol was violated; close with {@link #close()}. */
            VIOLATION
        }

        private static final Admission ADMITTED_RESULT = new Admission(Kind.ADMITTED, null);
        private static final Admission BUSY_RESULT = new Admission(Kind.BUSY, null);

        private final Kind kind;
        private final ReceiveClose close;

        private Admission(Kind kind, ReceiveClose close) {
            this.kind = kind;
            this.close = close;
        }

        /** Returns the outcome category. */
        public Kind kind() {
            return kind;
        }

        /** Returns the close for a violation, or null otherwise. */
        public ReceiveClose close() {
            return close;
        }

        static Admission admitted() {
            return ADMITTED_RESULT;
        }

        static Admission busy() {
            return BUSY_RESULT;
        }

        static Admission violation(String detail) {
            return new Admission(Kind.VIOLATION, ReceiveClose.peerViolation(detail));
        }
    }

    /** One admitted head unit and its per-stream state. */
    private static final class Unit {
        final Record record;
        final ReceiveRoute route;
        final BlockedHeadTimer timer;
        boolean enqueued;

        Unit(Record record, ReceiveRoute route, BlockedHeadTimer timer) {
            this.record = record;
            this.route = route;
            this.timer = timer;
        }
    }

    private final int streams;
    private final long[] admitted;
    private final long[] enqueued;
    private final long[] applied;
    private final Unit[] head;
    private final ReceiveSlots slots;
    private final Validator validator;
    private final LongSupplier clock;
    private long epoch;
    private long boundaryEpoch;
    private boolean boundaryPending;
    private boolean locallyWithheld;

    /**
     * @param chunks negotiated chunk count N, 1..6
     * @param validator injected shard, fence and join checks
     * @param clock monotonic nanosecond transport clock
     */
    public StreamReceiver(int chunks, Validator validator, LongSupplier clock) {
        LogicalStream.validateShardCount(chunks);
        if (validator == null || clock == null) {
            throw new IllegalArgumentException("validator and clock are required");
        }
        streams = chunks + 2;
        admitted = new long[streams];
        enqueued = new long[streams];
        applied = new long[streams];
        head = new Unit[streams];
        slots = new ReceiveSlots(chunks);
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Offers one fully parsed head unit (one record or one complete bundle) for admission (13.10).
     *
     * @param record decoded envelope; its body stays opaque here
     * @param route the route the integration decoded from the packet id
     * @param wireBytes total wire size of the unit, prefixes included, charged to the stream slot
     * @param recordCount records in the unit, including delimiters and partials
     * @return ADMITTED, BUSY, or VIOLATION with a 0x11 close
     */
    public Admission offer(Record record, ReceiveRoute route, long wireBytes, int recordCount) {
        if (record == null || route == null) {
            throw new IllegalArgumentException("record and route are required");
        }
        int stream = record.streamIndex();
        if (stream < 0 || stream >= streams) {
            return Admission.violation("stream index " + stream + " outside 0.." + (streams - 1));
        }
        if (record.sequence() != admitted[stream] + 1) {
            return Admission.violation("skipped or duplicate sequence on stream " + stream);
        }
        Admission epochCheck = checkEpoch(record, route);
        if (epochCheck != null) {
            return epochCheck;
        }
        Admission laneCheck = checkRoute(stream, record, route);
        if (laneCheck != null) {
            return laneCheck;
        }
        Admission depCheck = checkDependencies(stream, record);
        if (depCheck != null) {
            return depCheck;
        }
        if (!validator.valid(stream, record, route)) {
            return Admission.violation("shard, fence or join validation failed on stream " + stream);
        }
        if (closesCycle(record)) {
            return Admission.violation("provable dependency cycle through stream " + stream);
        }
        if (head[stream] != null) {
            return Admission.busy();
        }
        if (wireBytes > ReceiveSlots.BYTES_PER_STREAM || recordCount > ReceiveSlots.RECORDS_PER_STREAM
                || recordCount < 0 || wireBytes < 0) {
            return Admission.violation("unit exceeds the stream slot on stream " + stream);
        }
        if (!slots.admitHead(stream, wireBytes, recordCount)) {
            return Admission.busy();
        }
        BlockedHeadTimer timer = new BlockedHeadTimer(clock, true);
        Unit unit = new Unit(record, route, timer);
        head[stream] = unit;
        admitted[stream] = record.sequence();
        if (route.epochChange()) {
            boundaryPending = true;
            boundaryEpoch = record.epoch();
        }
        locallyWithheld = false;
        timer.start();
        return Admission.admitted();
    }

    /**
     * Returns the stream whose head should be enqueued next, or -1 if none is ready. REALTIME is
     * preferred, then CONTROL, then chunk streams in index order (13.10, REALTIME-first).
     */
    public int nextToEnqueue() {
        int[] order = new int[streams];
        order[0] = LogicalStream.REALTIME;
        order[1] = LogicalStream.CONTROL;
        for (int i = 2; i < streams; i++) {
            order[i] = i;
        }
        for (int stream : order) {
            Unit unit = head[stream];
            if (unit != null && !unit.enqueued && ready(unit)) {
                return stream;
            }
        }
        return -1;
    }

    /** Returns the record held as the head of a stream, or null. */
    public Record head(int stream) {
        checkStream(stream);
        return head[stream] == null ? null : head[stream].record;
    }

    /**
     * Records that the head with this sequence was handed to the game executor (Q). It must be ready.
     * Stops its blocked-head timer: after Q no transport application timeout applies (13.10).
     */
    public void enqueued(int stream, long sequence) {
        Unit unit = currentHead(stream, sequence);
        if (unit.enqueued) {
            throw new IllegalStateException("already enqueued on stream " + stream);
        }
        if (!ready(unit)) {
            throw new IllegalStateException("enqueued before its prerequisites were applied");
        }
        unit.enqueued = true;
        unit.timer.stop();
        enqueued[stream] = sequence;
    }

    /**
     * Records that the game's serial handler applied the enqueued head (D). Releases the stream's slot,
     * commits D and, for an epoch-boundary record, opens the new epoch (13.18).
     */
    public void applied(int stream, long sequence) {
        Unit unit = currentHead(stream, sequence);
        if (!unit.enqueued) {
            throw new IllegalStateException("applied before enqueued on stream " + stream);
        }
        head[stream] = null;
        applied[stream] = sequence;
        slots.releaseHead(stream);
        if (unit.route.epochChange()) {
            epoch = unit.record.epoch();
            boundaryPending = false;
        }
    }

    /**
     * Checks every held head whose blocked-head timer has expired (13.10, 13.12). Returns the 0x12
     * close to send, or null. A head expires only before its Q commit.
     */
    public ReceiveClose expiredWait() {
        for (int stream = 0; stream < streams; stream++) {
            Unit unit = head[stream];
            if (unit != null && !unit.enqueued && unit.timer.expired()) {
                return ReceiveClose.blockedExpiry(unit.timer.stallConsumed(), locallyWithheld);
            }
        }
        return null;
    }

    /**
     * Records that a prerequisite was withheld locally (node pool, read grant or decode refusal). It
     * blocks the 0x12 phrase for the current wait (13.12).
     */
    public void markLocallyWithheld() {
        locallyWithheld = true;
    }

    /** Forwards a recorded game-executor tick stall to every running blocked-head timer (LaneGame). */
    public void stallStarted() {
        for (Unit unit : head) {
            if (unit != null) {
                unit.timer.stallStarted();
            }
        }
    }

    /** Forwards the end of a recorded game-executor tick stall (LaneGame). */
    public void stallEnded() {
        for (Unit unit : head) {
            if (unit != null) {
                unit.timer.stallEnded();
            }
        }
    }

    /** Returns D, the applied watermark of a stream. */
    public long appliedWatermark(int stream) {
        checkStream(stream);
        return applied[stream];
    }

    /** Returns Q, the enqueued watermark of a stream. Used for timeout accounting only. */
    public long enqueuedWatermark(int stream) {
        checkStream(stream);
        return enqueued[stream];
    }

    /** Returns the current epoch of this direction, starting at 0 (13.18). */
    public long epoch() {
        return epoch;
    }

    /** Returns the epoch-boundary record slot, reserving decoded workspace for execution (13.12). */
    public boolean reserveWorkspace(long bytes) {
        return slots.reserveWorkspace(bytes);
    }

    /** Returns execution workspace after the unit has been applied. */
    public void releaseWorkspace(long bytes) {
        slots.releaseWorkspace(bytes);
    }

    /** Exposes the slot accounting for the integration and for tests. */
    public ReceiveSlots slots() {
        return slots;
    }

    private Admission checkEpoch(Record record, ReceiveRoute route) {
        long recordEpoch = record.epoch();
        if (recordEpoch < epoch) {
            return Admission.violation("stale epoch " + recordEpoch + " < " + epoch);
        }
        if (recordEpoch == epoch) {
            return route.epochChange() ? Admission.violation("epoch boundary does not advance the epoch") : null;
        }
        if (recordEpoch != epoch + 1) {
            return Admission.violation("future epoch " + recordEpoch);
        }
        if (route.epochChange()) {
            return boundaryPending ? Admission.violation("second epoch boundary before the first applied") : null;
        }
        return boundaryPending && boundaryEpoch == recordEpoch ? null
                : Admission.violation("future epoch " + recordEpoch + " without a boundary");
    }

    private Admission checkRoute(int stream, Record record, ReceiveRoute route) {
        boolean control = stream == LogicalStream.CONTROL;
        boolean realtime = stream == LogicalStream.REALTIME;
        switch (route.lane()) {
            case CONTROL:
                if (!control) {
                    return Admission.violation("CONTROL route on stream " + stream);
                }
                break;
            case REALTIME:
                if (!realtime) {
                    return Admission.violation("REALTIME route on stream " + stream);
                }
                break;
            default:
                if (control || realtime) {
                    return Admission.violation("CHUNK route on stream " + stream);
                }
                break;
        }
        if (route.join() && stream != 2) {
            return Admission.violation("join record outside CHUNK[0]");
        }
        if (record.kind() == KIND_END && !control) {
            return Admission.violation("END record outside CONTROL");
        }
        if ((record.kind() == KIND_ACK_JOIN || record.kind() == KIND_BATCH_JOIN) && stream < 2) {
            return Admission.violation("join kind outside chunk streams");
        }
        return null;
    }

    private Admission checkDependencies(int stream, Record record) {
        List<Record.Dependency> deps = record.dependencies();
        if (deps.size() > streams - 1) {
            return Admission.violation("dependency list longer than N+1");
        }
        int previous = -1;
        for (Record.Dependency dependency : deps) {
            int index = dependency.streamIndex();
            if (index < 0 || index >= streams) {
                return Admission.violation("dependency index " + index + " out of range");
            }
            if (index == stream) {
                return Admission.violation("same-stream dependency entry on stream " + stream);
            }
            if (index <= previous) {
                return Admission.violation("dependency entries not strictly increasing");
            }
            if (dependency.sequence() <= 0) {
                return Admission.violation("non-positive dependency sequence");
            }
            previous = index;
        }
        return null;
    }

    /**
     * Provable-cycle check (13.10). Starting from the candidate's explicit dependencies, follows the
     * chain of waits: an unresolved dependency on stream t at sequence q waits on every earlier record
     * of t, and on t's held head when it is reached. A cycle exists iff the chain reaches the
     * candidate's own stream at or beyond the candidate's sequence. Absent records are not proof of
     * impossibility and never close the connection here.
     */
    private boolean closesCycle(Record candidate) {
        long[] reach = new long[streams];
        boolean[] expanded = new boolean[streams];
        Deque<Record> work = new ArrayDeque<Record>();
        work.add(candidate);
        while (!work.isEmpty()) {
            Record node = work.poll();
            for (Record.Dependency dependency : node.dependencies()) {
                int t = dependency.streamIndex();
                long q = dependency.sequence();
                if (q <= applied[t] || q <= reach[t]) {
                    continue;
                }
                reach[t] = q;
                if (t == candidate.streamIndex() && q >= candidate.sequence()) {
                    return true;
                }
                if (head[t] != null && !expanded[t]) {
                    expanded[t] = true;
                    work.add(head[t].record);
                }
            }
        }
        return false;
    }

    /** A head is ready when every dependency is at or below D and the epoch has opened (13.10, 13.18). */
    private boolean ready(Unit unit) {
        Record record = unit.record;
        if (record.epoch() > epoch && !unit.route.epochChange()) {
            return false;
        }
        for (Record.Dependency dependency : record.dependencies()) {
            if (Long.compareUnsigned(dependency.sequence(), applied[dependency.streamIndex()]) > 0) {
                return false;
            }
        }
        return true;
    }

    private Unit currentHead(int stream, long sequence) {
        checkStream(stream);
        Unit unit = head[stream];
        if (unit == null || unit.record.sequence() != sequence) {
            throw new IllegalStateException("not the current head of stream " + stream);
        }
        return unit;
    }

    private void checkStream(int stream) {
        if (stream < 0 || stream >= streams) {
            throw new IllegalArgumentException("stream " + stream);
        }
    }
}
