// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import rs.sudoe.quicraft.core.routing.Direction;

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
 *       releases dependencies, frees the stream's slot and, for a transition record, advances the
 *       receive epoch by exactly one (13.18).</li>
 * </ul>
 *
 * <p>A head's blocked-head timer runs only while the head is dependency-blocked (13.10): it starts
 * when a head is admitted blocked, and stops once the head becomes ready or reaches Q. Locally
 * withheld prerequisites are recorded per (stream, sequence) and only matter for the expiring head's
 * own prerequisite chain.
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
    /** Largest epoch value on the wire: minimal signed-31-bit VarInt (13.4, 13.18). */
    private static final long MAX_EPOCH = 2147483647L;
    private static final int SEQUENCE_BITS = 40;

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

        static Admission of(ReceiveClose close) {
            return new Admission(Kind.VIOLATION, close);
        }
    }

    /** One admitted head unit and its per-stream state. */
    private static final class Unit {
        /** The opening record; its sequence identifies the unit and its dependencies gate it. */
        final Record record;
        /** Sequence of the last record of the unit; equals the opener's for a single record. */
        final long closing;
        final ReceiveRoute route;
        final BlockedHeadTimer timer;
        boolean enqueued;
        /** Sticky for the current wait: some prerequisite on the chain was withheld locally (13.12). */
        boolean chainWasWithheld;

        Unit(Record record, long closing, ReceiveRoute route, BlockedHeadTimer timer) {
            this.record = record;
            this.closing = closing;
            this.route = route;
            this.timer = timer;
        }
    }

    private final Direction direction;
    private final int streams;
    private final long[] admitted;
    private final long[] enqueued;
    private final long[] applied;
    private final Unit[] head;
    private final ReceiveSlots slots;
    private final Validator validator;
    private final LongSupplier clock;
    private final Set<Long> withheld = new HashSet<Long>();
    private long epoch;
    private boolean transitionPending;
    private boolean stalled;
    /** First 0x11 found by a re-check after an apply; sticky, every later offer returns it (13.18). */
    private ReceiveClose violation;

    /**
     * Clientbound receiver, kept for existing callers.
     *
     * @param chunks negotiated chunk count N, 1..6
     * @param validator injected shard, fence and join checks
     * @param clock monotonic nanosecond transport clock
     */
    public StreamReceiver(int chunks, Validator validator, LongSupplier clock) {
        this(chunks, Direction.CLIENTBOUND, validator, clock);
    }

    /**
     * @param chunks negotiated chunk count N, 1..6
     * @param direction sending direction this receiver serves; serverbound allows epoch 0 only (13.18)
     * @param validator injected shard, fence and join checks
     * @param clock monotonic nanosecond transport clock
     */
    public StreamReceiver(int chunks, Direction direction, Validator validator, LongSupplier clock) {
        LogicalStream.validateShardCount(chunks);
        if (direction == null || validator == null || clock == null) {
            throw new IllegalArgumentException("direction, validator and clock are required");
        }
        this.direction = direction;
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
     * Offers one single-record head unit for admission (13.10). Shorthand for
     * {@link #offer(Record, long, ReceiveRoute, long, int)} with closing sequence equal to the record's own.
     *
     * @param record decoded envelope; its body stays opaque here
     * @param route the route the integration decoded from the packet id
     * @param wireBytes total wire size of the unit, prefixes included, charged to the stream slot
     * @param recordCount must be 1 for a single record
     * @return ADMITTED, BUSY, or VIOLATION with a 0x11 close
     */
    public Admission offer(Record record, ReceiveRoute route, long wireBytes, int recordCount) {
        if (record == null) {
            throw new IllegalArgumentException("record and route are required");
        }
        return offer(record, record.sequence(), route, wireBytes, recordCount);
    }

    /**
     * Offers one scheduling unit for admission (13.10, 13.15). A unit is one record or one complete
     * bundle: its members occupy consecutive sequences from the opening record's sequence through
     * {@code closingSequence}, and only the opener carries external dependencies. Admission advances
     * the stream's frontier to the closing sequence, so the next unit must open at closing + 1.
     *
     * @param record the opening record; its body stays opaque here
     * @param closingSequence sequence of the last record of the unit, equal to the opener's for one record
     * @param route the route the integration decoded from the packet id
     * @param wireBytes total wire size of the unit, prefixes included, charged to the stream slot
     * @param recordCount records in the unit, delimiters included; must equal closing - opening + 1
     * @return ADMITTED, BUSY, or VIOLATION with a 0x11 close
     */
    public Admission offer(Record record, long closingSequence, ReceiveRoute route, long wireBytes,
            int recordCount) {
        if (record == null || route == null) {
            throw new IllegalArgumentException("record and route are required");
        }
        if (violation != null) {
            return Admission.of(violation);
        }
        int stream = record.streamIndex();
        if (stream < 0 || stream >= streams) {
            return Admission.violation("stream index " + stream + " outside 0.." + (streams - 1));
        }
        if (direction == Direction.SERVERBOUND && stream >= 2) {
            return Admission.violation("serverbound record on CHUNK stream " + stream + " (13.3)");
        }
        if (record.sequence() != admitted[stream] + 1) {
            return Admission.violation("skipped or duplicate sequence on stream " + stream);
        }
        if (closingSequence < record.sequence() || closingSequence - record.sequence() + 1 != recordCount) {
            return Admission.violation("unit closes at " + closingSequence + " but holds " + recordCount
                    + " records from " + record.sequence() + " on stream " + stream);
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
        Admission pathCheck = checkTransitionPath(record);
        if (pathCheck != null) {
            return pathCheck;
        }
        if (!validator.valid(stream, record, route)) {
            return Admission.violation("shard, fence or join validation failed on stream " + stream);
        }
        if (closesCycle(record)) {
            return Admission.violation("provable dependency cycle through stream " + stream);
        }
        if (wireBytes > ReceiveSlots.BYTES_PER_STREAM || recordCount > ReceiveSlots.RECORDS_PER_STREAM
                || recordCount < 0 || wireBytes < 0) {
            return Admission.violation("unit exceeds the stream slot on stream " + stream);
        }
        ReceiveClose heldViolation = heldPathViolation(transitionPending || route.epochChange());
        if (heldViolation != null) {
            return Admission.of(heldViolation);
        }
        if (head[stream] != null) {
            return Admission.busy();
        }
        if (!slots.admitHead(stream, wireBytes, recordCount)) {
            return Admission.busy();
        }
        Unit unit = new Unit(record, closingSequence, route, new BlockedHeadTimer(clock, true));
        head[stream] = unit;
        admitted[stream] = closingSequence;
        if (route.epochChange()) {
            transitionPending = true;
        }
        if (!ready(unit)) {
            unit.timer.start();
            if (stalled) {
                unit.timer.stallStarted();
            }
        }
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
     * Records that the head unit opening at {@code sequence} was handed to the game executor (Q). It must
     * be ready. Q advances to the unit's closing sequence, so a bundle is enqueued as a whole (13.15).
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
        enqueued[stream] = unit.closing;
    }

    /**
     * Records that the game's serial handler applied the enqueued head unit opening at {@code sequence} (D).
     * Releases the stream's slot, commits D to the unit's closing sequence and, for a transition record,
     * advances the epoch by exactly one (13.18).
     */
    public void applied(int stream, long sequence) {
        Unit unit = currentHead(stream, sequence);
        if (!unit.enqueued) {
            throw new IllegalStateException("applied before enqueued on stream " + stream);
        }
        rememberWithheldChains();
        head[stream] = null;
        applied[stream] = unit.closing;
        slots.releaseHead(stream);
        withheld.removeIf(key -> (key >>> SEQUENCE_BITS) == stream && (key & SEQUENCE_MASK) <= unit.closing);
        if (unit.route.epochChange()) {
            epoch = epoch + 1;
            transitionPending = false;
        }
        stopTimersOfReadyHeads();
        if (violation == null) {
            violation = heldPathViolation(transitionPending);
        }
    }

    /**
     * Returns the 0x11 found when an apply left a held epoch+1 head without a path to its transition,
     * or null. Stays set until the connection closes; the caller must act on it (13.18).
     */
    public ReceiveClose pendingViolation() {
        return violation;
    }

    /**
     * Checks every held head that is dependency-blocked and not yet at Q whose timer has expired
     * (13.10, 13.12). Returns the 0x12 close to send, or null. A ready head never expires.
     */
    public ReceiveClose expiredWait() {
        for (int stream = 0; stream < streams; stream++) {
            Unit unit = head[stream];
            if (unit != null && !unit.enqueued && !ready(unit) && unit.timer.expired()) {
                boolean wasWithheld = unit.chainWasWithheld || chainWithheld(unit.record);
                return ReceiveClose.blockedExpiry(unit.timer.stallConsumed(), wasWithheld);
            }
        }
        return null;
    }

    /**
     * Records that a prerequisite record was withheld locally (node pool, read grant or decode refusal).
     * It blocks the 0x12 phrase only for an expiring head whose prerequisite chain includes it (13.12).
     */
    public void markLocallyWithheld(int stream, long sequence) {
        checkStream(stream);
        withheld.add(key(stream, sequence));
    }

    /**
     * Forwards a recorded game-executor tick stall (LaneGame). Heads admitted while it continues start
     * excluded too, until {@link #stallEnded()} (13.10).
     */
    public void stallStarted() {
        stalled = true;
        for (Unit unit : head) {
            if (unit != null) {
                unit.timer.stallStarted();
            }
        }
    }

    /** Forwards the end of a recorded game-executor tick stall (LaneGame). */
    public void stallEnded() {
        stalled = false;
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

    /** Returns the current receive epoch of this direction, starting at 0 (13.18). */
    public long epoch() {
        return epoch;
    }

    /** Reserves decoded workspace before allocation; false means fail closed (13.12). */
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

    /**
     * Epoch rules (13.18). A transition record carries the OLD epoch and advances it on apply; it may
     * not overlap another pending transition. Other records may be at the current epoch or at exactly
     * one ahead (early arrival, opaque until the transition applies); anything further is future.
     */
    private Admission checkEpoch(Record record, ReceiveRoute route) {
        long recordEpoch = record.epoch();
        if (recordEpoch < 0 || recordEpoch > MAX_EPOCH) {
            return Admission.violation("epoch " + recordEpoch + " outside the signed-31-bit range");
        }
        if (direction == Direction.SERVERBOUND && (recordEpoch != 0 || route.epochChange())) {
            return Admission.violation("serverbound records must stay at epoch 0 (13.18)");
        }
        if (recordEpoch < epoch) {
            return Admission.violation("stale epoch " + recordEpoch + " < " + epoch);
        }
        if (route.epochChange()) {
            if (recordEpoch != epoch) {
                return Admission.violation("transition must carry the old epoch " + epoch);
            }
            if (transitionPending) {
                return Admission.violation("second transition before the first applied");
            }
            if (epoch == MAX_EPOCH) {
                return Admission.violation("epoch exhausted");
            }
            return null;
        }
        if (recordEpoch > epoch + 1) {
            return Admission.violation("future epoch " + recordEpoch);
        }
        return null;
    }

    /**
     * An epoch+1 record is only admissible while it can reach its transition: either the transition is
     * already admitted and unapplied, or one of its own dependencies is still unapplied and may lead to
     * it. Runs after dependency validation, so dependency indices are in range.
     */
    private Admission checkTransitionPath(Record record) {
        if (record.epoch() != epoch + 1 || transitionPending || !prerequisitesApplied(record)) {
            return null;
        }
        return Admission.violation("epoch " + record.epoch() + " without a transition path");
    }

    private boolean prerequisitesApplied(Record record) {
        for (Record.Dependency dependency : record.dependencies()) {
            if (Long.compareUnsigned(dependency.sequence(), applied[dependency.streamIndex()]) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-runs the epoch+1 path rule for every held head (13.18). Prerequisites only change on apply and a
     * transition only leaves on apply, so this runs after each apply and before each admission.
     */
    private ReceiveClose heldPathViolation(boolean transitionReachable) {
        if (transitionReachable) {
            return null;
        }
        for (Unit unit : head) {
            Record held = unit == null ? null : unit.record;
            if (held != null && !unit.enqueued && held.epoch() == epoch + 1 && prerequisitesApplied(held)) {
                return ReceiveClose.peerViolation("epoch " + held.epoch() + " without a transition path");
            }
        }
        return null;
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
        boolean joinKind = record.kind() == KIND_ACK_JOIN || record.kind() == KIND_BATCH_JOIN;
        if ((route.join() || joinKind) && stream != 2) {
            return Admission.violation("join record outside CHUNK[0]");
        }
        if (joinKind != route.join()) {
            return Admission.violation("join kind and join route disagree");
        }
        if (record.kind() == KIND_END && !control) {
            return Admission.violation("END record outside CONTROL");
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
            if (direction == Direction.SERVERBOUND && index >= 2) {
                return Admission.violation("serverbound dependency on CHUNK stream " + index + " (13.3)");
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
     * Provable-cycle check (13.10): a cycle exists iff the candidate's wait chain reaches its own stream
     * at or beyond the candidate's sequence. Absent records are not proof of impossibility.
     */
    private boolean closesCycle(Record candidate) {
        return chainReach(candidate)[candidate.streamIndex()] >= candidate.sequence();
    }

    /**
     * Returns, for each stream, the highest sequence that the wait chain of {@code root} depends on.
     * Dependencies and the implicit own-stream chain below them are followed through held heads.
     */
    private long[] chainReach(Record root) {
        long[] reach = new long[streams];
        boolean[] expanded = new boolean[streams];
        Deque<Record> work = new ArrayDeque<Record>();
        work.add(root);
        while (!work.isEmpty()) {
            Record node = work.poll();
            for (Record.Dependency dependency : node.dependencies()) {
                int t = dependency.streamIndex();
                long q = dependency.sequence();
                if (q <= applied[t] || q <= reach[t]) {
                    continue;
                }
                reach[t] = q;
                if (head[t] != null && !expanded[t]) {
                    expanded[t] = true;
                    work.add(head[t].record);
                }
            }
        }
        return reach;
    }

    /**
     * Reports whether any prerequisite on the root's chain was withheld locally: the root itself, or a
     * withheld (stream, sequence) that the chain depends on and that is not yet applied.
     */
    private boolean chainWithheld(Record root) {
        if (withheld.contains(key(root.streamIndex(), root.sequence()))) {
            return true;
        }
        long[] reach = chainReach(root);
        for (Long entry : withheld) {
            int t = (int) (entry >>> SEQUENCE_BITS);
            long x = entry & SEQUENCE_MASK;
            if (x > applied[t] && x <= reach[t]) {
                return true;
            }
        }
        return false;
    }

    /** A head is ready when the transition it needs has executed and every dependency is at or below D. */
    private boolean ready(Unit unit) {
        Record record = unit.record;
        if (record.epoch() > epoch) {
            return false;
        }
        for (Record.Dependency dependency : record.dependencies()) {
            if (Long.compareUnsigned(dependency.sequence(), applied[dependency.streamIndex()]) > 0) {
                return false;
            }
        }
        return true;
    }

    /** Stops the blocked-head timer of every held head that has become ready (13.10). */
    private void stopTimersOfReadyHeads() {
        for (Unit unit : head) {
            if (unit != null && !unit.enqueued && ready(unit)) {
                unit.timer.stop();
                unit.chainWasWithheld = false;
            }
        }
    }

    /** Captures withholding history for every waiting head before an apply clears markers (13.12). */
    private void rememberWithheldChains() {
        for (Unit unit : head) {
            if (unit != null && !unit.enqueued && !unit.chainWasWithheld && chainWithheld(unit.record)) {
                unit.chainWasWithheld = true;
            }
        }
    }

    private static long key(int stream, long sequence) {
        return ((long) stream << SEQUENCE_BITS) | sequence;
    }

    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

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
