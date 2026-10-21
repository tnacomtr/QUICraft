// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.time.StallClock;

/**
 * Receiver-side ordering policy for the N+3 streams of one direction (protocol.md 13.10, 13.12,
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
 * <p>Admission ({@link #offer}) reads only the envelope. The packet route and the injected
 * {@link Validator} run later, in {@link #validateEligible}, once a head is ready: a blocked or
 * future-epoch record is never handed to the body validator (13.10, 13.18). Enqueue requires that step.
 *
 * <p>Protocol violations are returned as {@link ReceiveClose} values with code 0x11, never thrown.
 * Refusals (a stream already holds its head) are returned as {@link Admission#busy()}. Nothing here
 * closes a connection by itself; the caller acts on the returned close.
 *
 * <p>The blocked-head timers of a receiver run in one of two modes and never mix them. A receiver built
 * with a {@link LongSupplier} is boundary-driven: intervals arrive through {@link #stallStarted(long)} and
 * {@link #stallEnded(long)}. A receiver built with a {@link StallClock} is cumulative: every timer reads
 * that one source, so an observation taken late excludes exactly what a timely one would have, and the
 * boundary hooks throw {@link IllegalStateException} before reading any clock or touching any state.
 *
 * <p>Not thread-safe; the receive path that owns the connection drives it.
 */
public final class StreamReceiver {
    /** Record kind values (13.4): END is CONTROL-only; ACK_JOIN and BATCH_JOIN are chunk-only. */
    private static final int KIND_END = RecordCodec.KIND_END;
    private static final int KIND_BUNDLE_OPEN = RecordCodec.KIND_BUNDLE_OPEN;
    private static final int KIND_ACK_JOIN = 2;
    private static final int KIND_BATCH_JOIN = 3;
    /** Largest epoch value on the wire: minimal signed-31-bit VarInt (13.4, 13.18). */
    private static final long MAX_EPOCH = 2147483647L;
    private static final int SEQUENCE_BITS = 40;

    /** Injected checks the policy cannot make alone: shard placement and fence validation (13.10, 13.18). */
    public interface Validator {
        /** Records an applied route, including the closing sequence of an atomic bundle. */
        default void applied(int stream, long opening, long closing, ReceiveRoute route) { }

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
        /** Total wire size of the unit, prefixes included; credited toward renewal when it commits (13.10). */
        final long wireBytes;
        final BlockedHeadTimer timer;
        /** Route from the body, known only once the head is eligible and validated (null before). */
        ReceiveRoute route;
        /** True once {@link StreamReceiver#validateEligible} accepted the head; the validator ran once. */
        boolean validated;
        boolean enqueued;
        /** Enqueue order of this head, from {@link StreamReceiver#enqueueCount}; diagnostics only. */
        long enqueueSerial;
        /** Sticky for the current wait: some prerequisite on the chain was withheld locally (13.12). */
        boolean chainWasWithheld;

        Unit(Record record, long closing, long wireBytes, BlockedHeadTimer timer) {
            this.record = record;
            this.closing = closing;
            this.wireBytes = wireBytes;
            this.timer = timer;
        }
    }

    private final Direction direction;
    private final int streams;
    private final long[] admitted;
    private final long[] enqueued;
    private final long[] applied;
    private final Unit[] head;
    /** Scratch: one observation per waiting head for the current step, indexed by stream (no per-commit allocation). */
    private final BlockedHeadTimer.Observation[] readings;
    private final ReceiveSlots slots;
    /** True when the connection's read pump owns the slot charge (see the pump-slot constructor). */
    private final boolean slotsHeldByPump;
    private final Validator validator;
    /** The boundary transport clock; null in cumulative mode. */
    private final LongSupplier clock;
    /** The cumulative stall source of the executor incarnation; null in boundary mode. */
    private final StallClock source;
    private final Set<Long> withheld = new HashSet<Long>();
    private long epoch;
    private boolean transitionPending;
    private boolean stalled;
    /** First 0x11 found by a re-check after an apply; sticky, every later offer returns it (13.18). */
    private ReceiveClose violation;
    /**
     * A blocked-head expiry reached before a commit, sticky: a later commit cannot rescue it (13.10). Captured
     * from the pre-commit state in {@link #applied}, returned by {@link #expiredWait} until the connection closes.
     */
    private ReceiveClose lapsedWait;
    /** Count of enqueues on this receiver; stamps each head's {@link Unit#enqueueSerial} (diagnostics only). */
    private long enqueueCount;
    /**
     * The cumulative sample taken by the latest expiry check of the current step, reused by the apply that follows so one
     * operation takes one reading (13.10). Cleared at the start of every {@link #applied} call.
     */
    private StallClock.Sample lastCheckSample;
    /** Transport nanos of the last successful apply on any stream, valid only when {@link #appliedOnce} (diagnostics only). */
    private long lastAppliedNanos;
    private boolean appliedOnce;
    /** Stream of the commit that caused the last renewal, -1 when none (diagnostics only). */
    private int lastRenewalStream = -1;
    /** Opening sequence of the commit that caused the last renewal (diagnostics only). */
    private long lastRenewalSequence;

    /**
     * Clientbound receiver, kept for existing callers (legacy boundary mode).
     *
     * @param chunks negotiated chunk count N, 1..5
     * @param validator injected shard, fence and join checks
     * @param clock monotonic nanosecond transport clock
     */
    public StreamReceiver(int chunks, Validator validator, LongSupplier clock) {
        this(chunks, Direction.CLIENTBOUND, validator, clock);
    }

    /**
     * Legacy boundary-mode receiver: its timers are driven by {@link #stallStarted(long)} and
     * {@link #stallEnded(long)} notifications on {@code clock}.
     *
     * @param chunks negotiated chunk count N, 1..5
     * @param direction sending direction this receiver serves; serverbound allows epoch 0 only (13.18)
     * @param validator injected shard, fence and join checks
     * @param clock monotonic nanosecond transport clock
     */
    public StreamReceiver(int chunks, Direction direction, Validator validator, LongSupplier clock) {
        this(chunks, direction, new ReceiveSlots(chunks), false, validator, clock, null);
    }

    /**
     * Cumulative receiver: every blocked-head timer reads {@code source}, so an observation taken late
     * excludes exactly what a timely one would have (13.10). {@code source} is required and is not
     * replaced for the life of the receiver.
     *
     * @param chunks negotiated chunk count N, 1..5
     * @param direction sending direction this receiver serves; serverbound allows epoch 0 only (13.18)
     * @param validator injected shard, fence and join checks
     * @param source the stall accounting source of the executor incarnation
     */
    public StreamReceiver(int chunks, Direction direction, Validator validator, StallClock source) {
        this(chunks, direction, new ReceiveSlots(chunks), false, validator, null, requireSource(source));
    }

    /**
     * Receiver whose stream slots are charged by the connection's {@link ClientReadPump}, kept for existing
     * callers (legacy boundary mode). The pump admits each head unit at read time; {@link #offer} then only
     * checks that the stream holds exactly that unit's bytes and records, and never charges again. The owner
     * releases the slot once, by committing the pump unit after {@link #applied}; this receiver does not
     * release it.
     *
     * @param chunks negotiated chunk count N, 1..5, equal to the pump's
     * @param slots the pump's clientbound slots, the single owner of the head charge
     * @param validator injected shard, fence and join checks
     * @param clock monotonic nanosecond transport clock
     */
    public StreamReceiver(int chunks, ReceiveSlots slots, Validator validator, LongSupplier clock) {
        this(chunks, Direction.CLIENTBOUND, slots, true, validator, clock, null);
    }

    /**
     * Cumulative pump-slot receiver: as {@link #StreamReceiver(int, ReceiveSlots, Validator, LongSupplier)},
     * but every blocked-head timer reads {@code source} (13.10). {@code source} is required.
     *
     * @param chunks negotiated chunk count N, 1..5, equal to the pump's
     * @param slots the pump's clientbound slots, the single owner of the head charge
     * @param validator injected shard, fence and join checks
     * @param source the stall accounting source of the executor incarnation
     */
    public StreamReceiver(int chunks, ReceiveSlots slots, Validator validator, StallClock source) {
        this(chunks, Direction.CLIENTBOUND, slots, true, validator, null, requireSource(source));
    }

    private StreamReceiver(int chunks, Direction direction, ReceiveSlots slots, boolean slotsHeldByPump,
                           Validator validator, LongSupplier clock, StallClock source) {
        LogicalStream.validateShardCount(chunks);
        if (direction == null || slots == null || validator == null) {
            throw new IllegalArgumentException("direction, slots and validator are required");
        }
        if ((clock == null) == (source == null)) {
            throw new IllegalArgumentException("exactly one of the boundary clock and the stall source is required");
        }
        this.direction = direction;
        streams = chunks + 3;
        admitted = new long[streams];
        enqueued = new long[streams];
        applied = new long[streams];
        head = new Unit[streams];
        readings = new BlockedHeadTimer.Observation[streams];
        this.slots = slots;
        this.slotsHeldByPump = slotsHeldByPump;
        this.validator = validator;
        this.clock = clock;
        this.source = source;
    }

    /** Rejects a null cumulative source before a receiver exists. */
    private static StallClock requireSource(StallClock source) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        return source;
    }

    /**
     * Envelope admission of one single-record unit (13.10). Shorthand for
     * {@link #offer(Record, long, long, int)} with closing sequence equal to the record's own.
     *
     * @param record decoded envelope; its body stays opaque here
     * @param wireBytes total wire size of the unit, prefixes included, charged to the stream slot
     * @param recordCount must be 1 for a single record
     * @return ADMITTED, BUSY, or VIOLATION with a 0x11 close
     */
    public Admission offer(Record record, long wireBytes, int recordCount) {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        return offer(record, record.sequence(), wireBytes, recordCount);
    }

    /**
     * Envelope admission of one scheduling unit (13.10, 13.15). A unit is one record or one complete
     * bundle: its members occupy consecutive sequences from the opening record's sequence through
     * {@code closingSequence}, and only the opener carries external dependencies. Admission advances
     * the stream's frontier to the closing sequence, so the next unit must open at closing + 1.
     *
     * <p>Admission reads only the envelope: stream, sequence, epoch, dependencies, kind and sizes. It never
     * reads the body and never runs the {@link Validator}, because the packet route is unknown until the
     * head is eligible. The route, shard and fence checks run later in {@link #validateEligible}.
     *
     * @param record the opening record; its body stays opaque here
     * @param closingSequence sequence of the last record of the unit, equal to the opener's for one record
     * @param wireBytes total wire size of the unit, prefixes included, charged to the stream slot
     * @param recordCount records in the unit, delimiters included; must equal closing - opening + 1
     * @return ADMITTED, BUSY, or VIOLATION with a 0x11 close
     */
    public Admission offer(Record record, long closingSequence, long wireBytes, int recordCount) {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        if (violation != null) {
            return Admission.of(violation);
        }
        int stream = record.streamIndex();
        if (stream < 0 || stream >= streams) {
            return Admission.violation("stream index " + stream + " outside 0.." + (streams - 1));
        }
        if (stream == ackIndex()) {
            // 13.21: one small GAME record at a time; never a bundle, join, END or other kind.
            if (record.kind() != RecordCodec.KIND_GAME) {
                return Admission.violation("record kind " + record.kind() + " on the ACK stream (13.21)");
            }
            if (wireBytes > ReceiveSlots.ACK_RECORD_MAX_BYTES) {
                return Admission.violation("ACK stream record of " + wireBytes + " wire bytes (13.21)");
            }
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
        if (closingSequence != record.sequence()
                && (stream != LogicalStream.CONTROL || direction == Direction.SERVERBOUND)) {
            return Admission.violation("multi-record unit outside clientbound CONTROL (13.15) on stream " + stream);
        }
        Admission epochCheck = checkEpoch(record);
        if (epochCheck != null) {
            return epochCheck;
        }
        if ((record.kind() == KIND_ACK_JOIN || record.kind() == KIND_BATCH_JOIN) && stream != 2) {
            return Admission.violation("join record outside CHUNK[0]");
        }
        if (record.kind() == KIND_END && stream != LogicalStream.CONTROL) {
            return Admission.violation("END record outside CONTROL");
        }
        if (record.kind() == KIND_BUNDLE_OPEN) {
            if (stream != LogicalStream.CONTROL || direction == Direction.SERVERBOUND) {
                return Admission.violation("bundle open outside clientbound CONTROL (13.15) on stream " + stream);
            }
            if (recordCount < 2) return Admission.violation("bundle open with fewer than two records (13.15)");
        }
        Admission depCheck = checkDependencies(stream, record);
        if (depCheck != null) {
            return depCheck;
        }
        Admission pathCheck = checkTransitionPath(record);
        if (pathCheck != null) {
            return pathCheck;
        }
        if (closesCycle(record)) {
            return Admission.violation("provable dependency cycle through stream " + stream);
        }
        if (wireBytes > ReceiveSlots.BYTES_PER_STREAM || recordCount > ReceiveSlots.RECORDS_PER_STREAM
                || recordCount < 0 || wireBytes < 0) {
            return Admission.violation("unit exceeds the stream slot on stream " + stream);
        }
        ReceiveClose heldViolation = heldPathViolation(transitionReachable());
        if (heldViolation != null) {
            return Admission.of(heldViolation);
        }
        if (head[stream] != null) {
            return Admission.busy();
        }
        if (slotsHeldByPump) {
            if (!slots.holdsHead(stream) || slots.heldBytes(stream) != wireBytes
                    || slots.heldRecords(stream) != recordCount) {
                return Admission.violation("pump slot on stream " + stream + " does not match the unit");
            }
        }
        if (source != null) {
            return admitCumulative(stream, record, closingSequence, wireBytes, recordCount);
        }
        // Legacy boundary mode keeps its original ordering: a refused own slot returns BUSY before any
        // clock read or state change, and the timer starts only after the head is published.
        if (!slotsHeldByPump && !slots.admitHead(stream, wireBytes, recordCount)) {
            return Admission.busy();
        }
        Unit unit = new Unit(record, closingSequence, wireBytes, new BlockedHeadTimer(clock, true));
        head[stream] = unit;
        admitted[stream] = closingSequence;
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
     * preferred, then CONTROL, then chunk streams in index order (13.10, REALTIME-first). The returned head
     * still needs {@link #validateEligible} before {@link #enqueued}.
     */
    public int nextToEnqueue() {
        if (violation != null) {
            return -1;
        }
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

    /**
     * Body validation of the eligible head of a stream (13.6, 13.10, 13.18). Call it once the head is
     * returned by {@link #nextToEnqueue()}, supplying the route the integration decoded from the packet id.
     * Runs the route, join, transition, shard and fence checks, and runs the {@link Validator} exactly once.
     * Later calls for the same head return null without re-running anything.
     *
     * @return null when the head is valid and may be enqueued, or a 0x11 close; a close is also sticky
     */
    public ReceiveClose validateEligible(int stream, ReceiveRoute route) {
        checkStream(stream);
        if (route == null) {
            throw new IllegalArgumentException("route is required");
        }
        if (violation != null) {
            return violation;
        }
        Unit unit = head[stream];
        if (unit == null || unit.enqueued) {
            throw new IllegalStateException("no unenqueued head on stream " + stream);
        }
        if (!ready(unit)) {
            throw new IllegalStateException("validated before its prerequisites were applied");
        }
        if (unit.validated) {
            return null;
        }
        ReceiveClose close = validateBody(stream, unit.record, route);
        if (close == null) {
            close = checkTransition(unit.record, route);
        }
        if (close != null) {
            violation = close;
            return close;
        }
        unit.route = route;
        unit.validated = true;
        if (route.epochChange()) {
            transitionPending = true;
        }
        ReceiveClose held = heldPathViolation(transitionReachable());
        if (held != null) {
            violation = held;
        }
        return held;
    }

    /**
     * Terminal cleanup: drops every retained head unit (and its record) and stops its timer. Releases no slot:
     * the owner of each slot charge releases it (the pump, or the driver's own cleanup in owned-slot mode).
     */
    public void discard() {
        for (int stream = 0; stream < head.length; stream++) {
            if (head[stream] != null) {
                head[stream].timer.stop();
                head[stream] = null;
            }
        }
        withheld.clear();
    }

    /** Returns the record held as the head of a stream, or null. */
    public Record head(int stream) {
        checkStream(stream);
        return head[stream] == null ? null : head[stream].record;
    }

    /** Read-only diagnostic; wire dependencies do not identify the sender's fence/join semantic type. */
    ReceiveTrace.WaitReason traceWaitReason() {
        for (Unit unit : head) {
            if (unit == null || unit.enqueued) continue;
            if (unit.record.epoch() > epoch) return ReceiveTrace.WaitReason.EPOCH_TRANSITION;
            for (Record.Dependency dependency : unit.record.dependencies()) {
                if (Long.compareUnsigned(dependency.sequence(), applied[dependency.streamIndex()]) <= 0) continue;
                if (unit.record.kind() == RecordCodec.KIND_BUNDLE_OPEN) return ReceiveTrace.WaitReason.BUNDLE_SEQUENCE;
                if (dependency.streamIndex() == 0) return ReceiveTrace.WaitReason.CONTROL_SEQUENCE;
                if (dependency.streamIndex() == 1) return ReceiveTrace.WaitReason.REALTIME_SEQUENCE;
                return ReceiveTrace.WaitReason.CHUNK_SEQUENCE_FENCE_OR_JOIN_UNKNOWN;
            }
        }
        return null;
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
        if (!unit.validated) {
            throw new IllegalStateException("enqueued before validateEligible on stream " + stream);
        }
        if (!ready(unit)) {
            throw new IllegalStateException("enqueued before its prerequisites were applied");
        }
        unit.enqueued = true;
        unit.enqueueSerial = ++enqueueCount;
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
        lastCheckSample = null;
        if (!settleWaitsBeforeCommit(stream, unit)) {
            // A lapsed wait is terminal (13.10): this commit changes no state, makes no callback and raises no
            // violation of its own; pendingViolation() now reports the captured close.
            return;
        }
        rememberWithheldChains();
        head[stream] = null;
        applied[stream] = unit.closing;
        noteApplied();
        validator.applied(stream, sequence, unit.closing, unit.route);
        if (!slotsHeldByPump) {
            slots.releaseHead(stream);
        }
        withheld.removeIf(key -> (key >>> SEQUENCE_BITS) == stream && (key & SEQUENCE_MASK) <= unit.closing);
        if (unit.route.epochChange()) {
            epoch = epoch + 1;
            transitionPending = false;
            if (violation == null) {
                violation = staleHeadViolation();
            }
        }
        stopTimersOfReadyHeads();
        if (violation == null) {
            violation = heldPathViolation(transitionReachable());
        }
    }

    /**
     * After the epoch advanced, any retained head still below the new epoch (enqueued or not) was admitted
     * before the fence and would execute after it, which 13.18 forbids. The applied transition's own unit is
     * already cleared. O(streams), no allocation unless it fails.
     */
    private ReceiveClose staleHeadViolation() {
        for (Unit unit : head) {
            if (unit != null && unit.record.epoch() < epoch) {
                return ReceiveClose.peerViolation("epoch " + unit.record.epoch()
                        + " record retained across the epoch " + epoch + " fence (13.18)");
            }
        }
        return null;
    }

    /**
     * Returns the close the caller must act on, or null: a lapsed blocked-head wait captured before a commit
     * (13.10, sticky, 0x12) takes precedence over any post-commit 0x11, so the expiry closes first. Otherwise the
     * 0x11 found when an apply left a held epoch+1 head without a path to its transition (13.18). Both stay set
     * until the connection closes.
     */
    public ReceiveClose pendingViolation() {
        return lapsedWait != null ? lapsedWait : violation;
    }

    /**
     * Checks every held head that is dependency-blocked and not yet at Q (13.10, 13.12). Returns the 0x12 close
     * to send, or null. A ready head never expires. A no-progress expiry closes with the phrase rules of
     * {@link ReceiveClose#blockedExpiry}; with no such expiry, an absolute-retention expiry closes generic 0x12
     * with no phrase. A lapsed wait captured by {@link #applied} is returned first.
     */
    public ReceiveClose expiredWait() {
        return lapsedWait != null ? lapsedWait : expiredClose(false);
    }

    /**
     * The close of the first expired wait in the current state, or null. A no-progress expiry takes precedence
     * over an absolute-retention expiry on any other head (13.12, "Deadline precedence"). Takes one observation
     * per waiting head into {@link #readings}; a cumulative receiver reads its source once for the whole check.
     *
     * @param lapsed true when the check runs in settlement, so the close is captured before the commit (diagnostics)
     */
    private ReceiveClose expiredClose(boolean lapsed) {
        ReceiveClose retention = null;
        StallClock.Sample sample = null;
        for (int stream = 0; stream < streams; stream++) {
            readings[stream] = null;
            Unit unit = head[stream];
            if (unit == null || unit.enqueued || ready(unit)) {
                continue;
            }
            if (source != null && sample == null) {
                sample = source.sample();
                lastCheckSample = sample;
            }
            BlockedHeadTimer.Observation now = source == null ? unit.timer.observe() : unit.timer.observe(sample);
            readings[stream] = now;
            if (unit.timer.expired(now)) {
                boolean wasWithheld = unit.chainWasWithheld || chainWithheld(unit.record);
                return ReceiveClose.blockedExpiry(now != null && now.stallConsumed(), wasWithheld,
                        describeExpiry("no-progress", lapsed, stream, unit, now, sample));
            }
            if (retention == null && unit.timer.absoluteExpired(now)) {
                retention = ReceiveClose.retentionExpiry(describeExpiry("absolute", lapsed, stream, unit, now, sample));
            }
        }
        return retention;
    }

    /**
     * Builds the local-only diagnostic of an expired blocked head. Runs only when the wait closes the connection, so
     * the string is never built on the normal path. Lists the head, every unresolved dependency with its applied,
     * queued and admitted watermarks and whether the receiver holds its prerequisite unit (held, ready, enq, seq),
     * then the timer's readings: transport time since the wait started, active time since the last renewal, the stall
     * time consumed and the credit toward the next renewal. Ends with the receiver's global state: the executing head,
     * the executor queue depth, the time since any apply and the source of the last renewal.
     *
     * @param sample the cumulative sample of this check, or null in boundary mode
     */
    private String describeExpiry(String kind, boolean lapsed, int stream, Unit unit,
                                  BlockedHeadTimer.Observation now, StallClock.Sample sample) {
        StringBuilder text = new StringBuilder(256);
        text.append("expiry=").append(kind).append(" lapsed=").append(lapsed ? "yes" : "no")
                .append(" head=").append(stream).append(':').append(unit.record.sequence())
                .append(" unresolved=[");
        boolean first = true;
        for (Record.Dependency dependency : unit.record.dependencies()) {
            int required = dependency.streamIndex();
            if (Long.compareUnsigned(dependency.sequence(), applied[required]) <= 0) {
                continue;
            }
            if (!first) {
                text.append("; ");
            }
            first = false;
            text.append("stream=").append(required).append(" need=").append(dependency.sequence())
                    .append(" applied=").append(applied[required])
                    .append(" queued=").append(enqueued[required])
                    .append(" admitted=").append(admitted[required]);
            appendHeldState(text, required);
        }
        text.append("] blocked-ms=").append(now.elapsedNanos() / 1_000_000L)
                .append(" since-renewal-ms=")
                .append((now.activeNanos() - unit.timer.renewedActiveNanos()) / 1_000_000L)
                .append(" stall-consumed-ms=").append(now.exclusionNanos() / 1_000_000L)
                .append(" credit-bytes=").append(unit.timer.creditBytes())
                .append('/').append(BlockedHeadTimer.RENEWAL_BYTES);
        text.append(" executing=").append(executingHead())
                .append(" executor-queued-units=").append(queuedUnits())
                .append(" ms-since-last-apply-any-stream=");
        if (appliedOnce) {
            text.append((diagnosticNanos(sample) - lastAppliedNanos) / 1_000_000L);
        } else {
            text.append("none");
        }
        text.append(" last-renewal-source=");
        if (lastRenewalStream < 0) {
            text.append("none");
        } else {
            text.append(lastRenewalStream).append(':').append(lastRenewalSequence);
        }
        return text.toString();
    }

    /**
     * Appends whether the receiver holds the unit at applied+1 on a prerequisite stream: held (a unit is there at
     * that sequence), ready (it is dependency-ready), enq (it is enqueued) and its record sequence, or none.
     */
    private void appendHeldState(StringBuilder text, int stream) {
        Unit held = head[stream];
        boolean holds = held != null && held.record.sequence() == applied[stream] + 1;
        text.append(" held=").append(holds ? "yes" : "no");
        text.append(" ready=").append(holds && ready(held) ? "yes" : "no");
        text.append(" enq=").append(holds && held.enqueued ? "yes" : "no");
        text.append(" seq=").append(holds ? String.valueOf(held.record.sequence()) : "none");
    }

    /** The oldest enqueued, unapplied head ("stream:seq"), which the serial executor runs first; "none" if none. */
    private String executingHead() {
        int oldest = -1;
        for (int stream = 0; stream < streams; stream++) {
            Unit unit = head[stream];
            if (unit != null && unit.enqueued
                    && (oldest < 0 || unit.enqueueSerial < head[oldest].enqueueSerial)) {
                oldest = stream;
            }
        }
        return oldest < 0 ? "none" : oldest + ":" + head[oldest].record.sequence();
    }

    /** Number of heads enqueued to the executor and not yet applied. */
    private int queuedUnits() {
        int queued = 0;
        for (Unit unit : head) {
            if (unit != null && unit.enqueued) {
                queued++;
            }
        }
        return queued;
    }

    /** Transport reading for diagnostics: the boundary clock, or the sample taken for the current check. */
    private long diagnosticNanos(StallClock.Sample sample) {
        return source == null ? clock.getAsLong() : sample.nowNanos();
    }

    /**
     * Records the transport time of an apply (O(1)). A cumulative apply reuses the reading of its own settlement when that
     * step took one, so an apply takes at most one reading of the source; a source that is no longer valid records nothing,
     * so a dead incarnation never fails a commit for a diagnostic.
     */
    private void noteApplied() {
        if (source == null) {
            lastAppliedNanos = clock.getAsLong();
            appliedOnce = true;
            return;
        }
        StallClock.Sample sample = lastCheckSample != null ? lastCheckSample : source.sample();
        lastCheckSample = null;
        if (sample.valid()) {
            lastAppliedNanos = sample.nowNanos();
            appliedOnce = true;
        }
    }

    /**
     * Runs before a commit changes any state (13.10) and returns false when the commit must not proceed. The expiry
     * decision and the renewal of every waiting head use one reading per head taken in this step, so an expiry
     * reached at the renewal observation is never renewed. An expiry in the pre-commit state is captured as a
     * sticky, terminal close that this commit cannot rescue. An unavailable source during settlement is the same:
     * a generic 0x12 with no state changed. Otherwise each waiting head is credited with this commit when it
     * qualifies, and renews at once on its own direct-dependency watermark or when the credit reaches
     * {@link BlockedHeadTimer#RENEWAL_BYTES}.
     */
    private boolean settleWaitsBeforeCommit(int stream, Unit committing) {
        if (lapsedWait != null) {
            return false;
        }
        try {
            ReceiveClose expired = expiredClose(true);
            if (expired != null) {
                lapsedWait = expired;
                return false;
            }
        } catch (StallClock.UnavailableException e) {
            lapsedWait = ReceiveClose.resourceWait("stall source unavailable during settlement");
            return false;
        }
        for (int waitingStream = 0; waitingStream < streams; waitingStream++) {
            Unit waiting = head[waitingStream];
            if (waiting != null && !waiting.enqueued && !ready(waiting)) {
                creditQualifyingCommit(waiting, readings[waitingStream], stream, committing);
            }
        }
        return true;
    }

    /**
     * Credits a commit to one waiting head when it qualifies (13.10): the stream is an unresolved prerequisite of
     * the head's pre-commit chain (transitive included) and the unit opens at or below that prerequisite's required
     * sequence. The head renews at once (a) when this commit reaches the head's OWN direct dependency entry on the
     * stream, or (b) when its qualifying credit reaches 64 KiB. A commit on a transitive-only prerequisite earns
     * byte credit (b) and never arm (a), so tiny records chained through an intermediate head cannot keep renewing.
     * Raw arrivals, Q, ACKs, unrelated commits and changes of prerequisite earn nothing. O(streams).
     */
    private void creditQualifyingCommit(Unit waiting, BlockedHeadTimer.Observation now, int stream, Unit committing) {
        long required = chainReach(waiting.record)[stream];
        if (required <= applied[stream] || committing.record.sequence() > required) {
            return;
        }
        waiting.timer.addCredit(committing.wireBytes);
        if (reachesDirectDependency(waiting.record, stream, committing.closing) || waiting.timer.creditReached()) {
            waiting.timer.renew(now);
            lastRenewalStream = committing.record.streamIndex();
            lastRenewalSequence = committing.record.sequence();
        }
    }

    /**
     * Reports whether a commit ending at {@code closing} reaches the record's own direct dependency entry on
     * {@code stream} while that entry is still unresolved (13.10, arm a). A record has at most one entry per stream.
     */
    private boolean reachesDirectDependency(Record record, int stream, long closing) {
        for (Record.Dependency dependency : record.dependencies()) {
            if (dependency.streamIndex() == stream) {
                return dependency.sequence() > applied[stream] && closing >= dependency.sequence();
            }
        }
        return false;
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
     * Forwards a recorded game-executor tick stall (LaneGame). Each held, running timer reads its own
     * clock; a receiver with no heads reads no clock at all. Heads admitted while it continues start
     * excluded too, until {@link #stallEnded()} (13.10). A cumulative receiver derives stall time from
     * its source instead: it rejects every boundary hook before reading a clock or changing any state.
     */
    public void stallStarted() {
        requireBoundaryMode();
        stalled = true;
        for (Unit unit : head) {
            if (unit != null) {
                unit.timer.stallStarted();
            }
        }
    }

    /** As {@link #stallStarted()}, timestamped at {@code atNanos} (13.10; see {@link BlockedHeadTimer#stallStarted(long)}). */
    public void stallStarted(long atNanos) {
        requireBoundaryMode();
        stalled = true;
        for (Unit unit : head) {
            if (unit != null) {
                unit.timer.stallStarted(atNanos);
            }
        }
    }

    /** Forwards the end of a recorded game-executor tick stall (LaneGame), one read per held timer. */
    public void stallEnded() {
        requireBoundaryMode();
        stalled = false;
        for (Unit unit : head) {
            if (unit != null) {
                unit.timer.stallEnded();
            }
        }
    }

    /** As {@link #stallEnded()}, timestamped at {@code atNanos}. */
    public void stallEnded(long atNanos) {
        requireBoundaryMode();
        stalled = false;
        for (Unit unit : head) {
            if (unit != null) {
                unit.timer.stallEnded(atNanos);
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
     * Envelope epoch rules (13.18), checked at admission: range, serverbound epoch 0, stale and future.
     * Other records may be at the current epoch or at exactly one ahead (early arrival, opaque until the
     * transition applies); anything further is future. Whether a record is a transition, and so must carry
     * the old epoch, is known only from its route and is checked in {@link #checkTransition}.
     */
    private Admission checkEpoch(Record record) {
        long recordEpoch = record.epoch();
        if (recordEpoch < 0 || recordEpoch > MAX_EPOCH) {
            return Admission.violation("epoch " + recordEpoch + " outside the signed-31-bit range");
        }
        if (direction == Direction.SERVERBOUND && recordEpoch != 0) {
            return Admission.violation("serverbound records must stay at epoch 0 (13.18)");
        }
        if (recordEpoch < epoch) {
            return Admission.violation("stale epoch " + recordEpoch + " < " + epoch);
        }
        if (recordEpoch > epoch + 1) {
            return Admission.violation("future epoch " + recordEpoch);
        }
        return null;
    }

    /**
     * An epoch+1 record is only admissible while it can reach its transition: either a transition for the
     * current epoch is reachable (see {@link #transitionReachable()}), or one of its own dependencies is
     * still unapplied and may lead to it. Runs after dependency validation, so dependency indices are in range.
     */
    private Admission checkTransitionPath(Record record) {
        if (record.epoch() != epoch + 1 || transitionReachable() || !prerequisitesApplied(record)) {
            return null;
        }
        return Admission.violation("epoch " + record.epoch() + " without a transition path");
    }

    /**
     * Returns true when a transition for the current epoch is pending (validated, not yet applied) or can
     * still be admitted: an unvalidated clientbound CONTROL head at the current epoch might turn out to be one
     * (13.18). Transitions are CONTROL-only and never serverbound, so no other head counts.
     */
    private boolean transitionReachable() {
        if (transitionPending) {
            return true;
        }
        if (direction == Direction.SERVERBOUND) {
            return false;
        }
        Unit control = head[LogicalStream.CONTROL];
        return control != null && !control.validated && control.record.epoch() == epoch;
    }

    /**
     * Transition rules for a validated head whose route marks an epoch change (13.18): the transition is
     * CONTROL-only, never serverbound, carries the OLD epoch, may not overlap another pending transition and
     * may not exhaust the epoch range.
     */
    private ReceiveClose checkTransition(Record record, ReceiveRoute route) {
        if (!route.epochChange()) {
            return null;
        }
        if (direction == Direction.SERVERBOUND) {
            return ReceiveClose.peerViolation("serverbound transition route (13.18)");
        }
        if (record.streamIndex() != LogicalStream.CONTROL) {
            return ReceiveClose.peerViolation("transition route outside CONTROL on stream " + record.streamIndex());
        }
        if (record.epoch() != epoch) {
            return ReceiveClose.peerViolation("transition must carry the old epoch " + epoch);
        }
        if (transitionPending) {
            return ReceiveClose.peerViolation("second transition before the first applied");
        }
        if (epoch == MAX_EPOCH) {
            return ReceiveClose.peerViolation("epoch exhausted");
        }
        return null;
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

    /**
     * Body validation of an eligible head (13.6, 13.10, 13.18): the packet route must match the stream's lane,
     * join routes live on CHUNK[0], and the injected {@link Validator} must accept shard and fence. Runs only
     * from {@link #validateEligible}, never at admission.
     */
    private ReceiveClose validateBody(int stream, Record record, ReceiveRoute route) {
        ReceiveClose laneClose = checkRoute(stream, route);
        if (laneClose != null) {
            return laneClose;
        }
        ReceiveClose joinClose = checkJoinKind(record, route);
        if (joinClose != null) {
            return joinClose;
        }
        if (!validator.valid(stream, record, route)) {
            return ReceiveClose.peerViolation("shard, fence or join validation failed on stream " + stream);
        }
        return null;
    }

    /** The ACK stream's logical index, N+2 (13.21). */
    private int ackIndex() {
        return streams - 1;
    }

    private ReceiveClose checkRoute(int stream, ReceiveRoute route) {
        boolean control = stream == LogicalStream.CONTROL;
        boolean realtime = stream == LogicalStream.REALTIME;
        boolean ack = stream == ackIndex();
        switch (route.lane()) {
            case ACK:
                if (!ack) {
                    return ReceiveClose.peerViolation("ACK route on stream " + stream);
                }
                break;
            case CONTROL:
                if (!control) {
                    return ReceiveClose.peerViolation("CONTROL route on stream " + stream);
                }
                break;
            case REALTIME:
                if (!realtime) {
                    return ReceiveClose.peerViolation("REALTIME route on stream " + stream);
                }
                break;
            default:
                if (control || realtime || ack) {
                    return ReceiveClose.peerViolation("CHUNK route on stream " + stream);
                }
                break;
        }
        if (route.join() && stream != 2) {
            return ReceiveClose.peerViolation("join record outside CHUNK[0]");
        }
        return null;
    }

    /** Join kind and join route must agree; checked once the route is known (13.19). */
    private ReceiveClose checkJoinKind(Record record, ReceiveRoute route) {
        boolean joinKind = record.kind() == KIND_ACK_JOIN || record.kind() == KIND_BATCH_JOIN;
        if (joinKind != route.join()) {
            return ReceiveClose.peerViolation("join kind and join route disagree");
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

    /**
     * Cumulative admission: the local unit is prepared and its timer started only when a prerequisite
     * wait actually begins, before the head is published and, in owned-slot mode, before the stream's
     * slot is acquired. A start that cannot trust its source throws with no receiver state changed; a
     * refused own slot discards the prepared timer and publishes nothing.
     */
    private Admission admitCumulative(int stream, Record record, long closingSequence, long wireBytes,
                                      int recordCount) {
        Unit unit = new Unit(record, closingSequence, wireBytes, new BlockedHeadTimer(source, true));
        if (!ready(unit)) {
            unit.timer.start();
        }
        if (!slotsHeldByPump && !slots.admitHead(stream, wireBytes, recordCount)) {
            unit.timer.stop();
            return Admission.busy();
        }
        head[stream] = unit;
        admitted[stream] = closingSequence;
        return Admission.admitted();
    }

    /** Rejects a boundary stall notification in cumulative mode before any clock read or state change. */
    private void requireBoundaryMode() {
        if (source != null) {
            throw new IllegalStateException("a cumulative receiver has no boundary stall notifications");
        }
    }

    private void checkStream(int stream) {
        if (stream < 0 || stream >= streams) {
            throw new IllegalArgumentException("stream " + stream);
        }
    }
}
