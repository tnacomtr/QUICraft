// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import rs.sudoe.quicraft.core.routing.Lane;

/**
 * The transport-independent ordering core for one receiving direction (13.10-13.15).
 *
 * <p>The owner first admits opaque wire envelopes. Only a dependency-eligible record is handed
 * to {@link Decoder}; that decoder reserves allocation bytes and then reports classification via
 * {@link #decoded}. CONTROL bundle members may use provisional CONTROL watermarks for decoding,
 * but no provisional watermark releases an auxiliary record or advances committed delivery.</p>
 *
 * <p>Only a completely decoded record or validated bundle reaches {@link Handler}. The owner
 * applies it serially and calls {@link #applied}; only that acknowledgement commits watermarks
 * and releases retained budgets. Decoding subsequent records waits for that acknowledgement,
 * preserving compression and protocol-state transitions. Game-specific stricter limits and
 * validation of routing/packet contents remain the adapter's responsibility.</p>
 *
 * <p>Node budget (13.12): every byte this scheduler retains is also reserved against a shared
 * {@link ReceiveBudget}, in addition to the per-connection ceilings above. A refused node
 * reservation is never satisfied by evicting someone else's data: when the bytes have not
 * arrived yet the owner withholds the credit that would carry them (see
 * {@link #nodeCreditAvailable(long)}), and when they already arrived and can never fit, that one
 * connection fails with {@code 0x11}. {@link #releaseAll()} gives every charge back when the
 * connection closes.</p>
 *
 * <p>Pump priority: CONTROL is examined first, then REALTIME, then WORLD, and one decode or
 * delivery is outstanding at a time. A continuously busy CONTROL lane can therefore starve an
 * auxiliary lane; such starvation is bounded only by the 10 s blocked-head timer.</p>
 *
 * <p>Adapter contract: {@link #offer}, {@link #complete}, {@link #decoded} and {@link #applied}
 * may call {@link Decoder#decode} and {@link Handler#deliver} inline on the caller's thread. An
 * asynchronous decoder must call {@link #reserveDecoded} and {@link #decoded} back on that same
 * single thread or executor. All calls use one monotonic {@code now} base, in nanoseconds.</p>
 *
 * <p>Not thread-safe: confine the instance, its callbacks and the transport glue to one thread
 * (the channel event loop). Any {@link OrderingException} requires closing the whole connection
 * with close code 0x11. If a {@link Decoder} or {@link Handler} callback throws any
 * RuntimeException, the scheduler latches as failed: the exception is rethrown (wrapped in an
 * {@link OrderingException} with the cause unless it already is one) and every later call throws
 * {@link OrderingException} "scheduler failed". Do not resume using this instance after failure.</p>
 */
public final class ReceiverScheduler {
    /** Combined pending record ceiling, including partials and staged/decoded records. */
    public static final int MAX_RECORDS = 1024;
    /** Auxiliary ceiling, leaving 64 record slots exclusively for CONTROL. */
    public static final int MAX_AUXILIARY_RECORDS = 960;
    /** Shared receive capacity, with auxiliary bytes limited to at most this amount. */
    public static final long SHARED_BYTES = 16L * 1024 * 1024;
    /** Additional CONTROL-only capacity; the aggregate ceiling is 17 MiB. */
    public static final long CONTROL_RESERVE_BYTES = 1024 * 1024;
    /** Deadline duration for blocked heads, partials, decoding and application. */
    public static final long TIMEOUT_NANOS = 10000000000L;

    /**
     * Signals that the shared node budget could not carry an allocation right now. The owner must
     * withhold and retry later (13.12): it is not a protocol failure, so nothing latches and the
     * decode deadline keeps running from the first attempt.
     */
    static final class RetryLater extends RuntimeException {
        private static final long serialVersionUID = 1L;
        RetryLater() {
            super("node receive budget cannot carry this allocation yet (13.12)");
        }
    }

    /**
     * A decode request the node budget can never carry, whatever other connections release. It is a
     * local resource failure, never peer misconduct: the owner closes with its internal-error code
     * (0x2), not 0x11, and it is not wrapped into an {@link OrderingException}.
     */
    static final class LocalResourceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        LocalResourceException(String message) {
            super(message);
        }
    }

    /** Decodes only an eligible record, reserving output before allocation and reporting decoded(). */
    public interface Decoder {
        /**
         * Receives a dependency-eligible opaque record, never a blocked record.
         *
         * <p>Call reserveDecoded before allocating output, then decoded after parsing and
         * classification. The identity of {@code envelope} is the decode token. Callbacks may
         * complete inline or later on the same executor. {@code insideBundle} is true only for
         * CONTROL records following a decoded OPEN and preceding its decoded CLOSE.</p>
         *
         * <p>{@link #reserveDecoded} may refuse ({@link RetryLater}) when the shared node budget
         * cannot carry an allocation yet. A decode that is refused that way owns the cleanup of
         * everything it allocated for this attempt: it must release it before it lets the refusal
         * propagate, because the entry stays undecoded and the scheduler reruns the decoder from
         * its start. A decoder that keeps what it allocated leaks it and charges the same output
         * again on every attempt.</p>
         */
        void decode(Envelope envelope, boolean insideBundle, long now);
    }

    /** Applies one already-decoded record or an entire validated bundle, then calls applied(). */
    public interface Handler {
        /** Accepts one decoded scheduling unit; returning does not acknowledge its application. */
        void deliver(Delivery delivery);
    }

    /** One record, or a whole bundle. Acknowledge the final sequence, never a member. */
    public static final class Delivery {
        private final List<Envelope> records;
        private Delivery(List<Envelope> records) {
            this.records = Collections.unmodifiableList(records);
        }
        /** Returns the immutable unit, including both delimiters for a bundle. */
        public List<Envelope> records() {
            return records;
        }

        /** Distinguishes an atomic bundle from an ordinary single-record delivery. */
        public boolean isBundle() {
            return records.get(0).bundle() == Envelope.Bundle.OPEN;
        }

        /** Returns the lane to acknowledge after the unit has been applied. */
        public Lane lane() {
            return records.get(0).lane();
        }

        /** Returns the final sequence to acknowledge, never an intermediate bundle member. */
        public long sequence() {
            return records.get(records.size() - 1).sequence();
        }
    }

    /** Identity token for a first-byte reservation; never reuse after release/complete. */
    public static final class Partial {
        private final Lane lane;
        private long started;
        private long bytes;
        private Partial(Lane lane, long bytes, long now) {
            this.lane = lane;
            this.bytes = bytes;
            this.started = now;
        }
    }

    /**
     * Transport-local withholding is not evidence of a stalled peer. Event-loop only. A lane is
     * marked here only while it is actually retrying a refused parser allocation (the owner
     * recomputes that on every pass). Decode blocks live on their queue entries so simultaneous
     * refusals on different lanes remain independently visible.
     */
    private final long[] receiveBlockedSince = {-1, -1, -1};
    private long bundlePausedSince = -1;

    void receiveBlocked(Lane lane, boolean blocked, long now) {
        enter();
        int index = lane.ordinal();
        long since = receiveBlockedSince[index];
        if (blocked == (since >= 0)) {
            return;
        }
        if (blocked) {
            receiveBlockedSince[index] = now;
        } else {
            for (Partial p : partials.keySet()) {
                if (p.lane == lane) {
                    p.started += now - Math.max(p.started, since);
                }
            }
            receiveBlockedSince[index] = -1;
        }
        updatePauses(now);
    }

    /** True while a decode waits for node space; the owner's cap on local waits counts it. */
    boolean decodeBlocked() {
        return anyDecodeBlocked();
    }

    private boolean anyDecodeBlocked() {
        for (ArrayDeque<Entry> q : queues) {
            for (Entry entry : q) {
                if (entry.nodeBlockedSince >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean decodeBlocked(Lane lane, long upTo) {
        for (Entry entry : queues.get(lane.ordinal())) {
            if (entry.nodeBlockedSince >= 0
                    && Long.compareUnsigned(entry.envelope.sequence(), upTo) <= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code lane} is withheld locally in a way that delays the record with sequence
     * {@code upTo} (unsigned) on it: the lane is retrying a refused parser allocation and has not yet
     * received that record, or the decode that cannot allocate is that lane's record at or before it.
     */
    private boolean laneLocallyBlocked(Lane lane, long upTo) {
        int i = lane.ordinal();
        if (receiveBlockedSince[i] >= 0 && Long.compareUnsigned(received[i], upTo) < 0) {
            return true;
        }
        return decodeBlocked(lane, upTo);
    }

    /**
     * Whether the head of {@code q} is waiting only on something a locally withheld lane has to
     * deliver: a missing predecessor on a lane that is blocked, or the rest of an open bundle on a
     * blocked CONTROL lane. Mirrors {@link #blocked}; a head that waits on a peer's silence is never
     * paused, and neither is one that waits on a lane that is merely idle.
     */
    private boolean headPaused(ArrayDeque<Entry> q) {
        long provisional = delivered[0];
        boolean bundle = false;
        boolean first = true;
        for (Entry entry : q) {
            Envelope env = entry.envelope;
            boolean missing = false;
            for (Lane lane : Lane.values()) {
                long watermark = lane == Lane.CONTROL ? provisional : delivered[lane.ordinal()];
                long dependency = env.dependency(lane);
                if (Long.compareUnsigned(dependency, watermark) > 0) {
                    missing = true;
                    if (lane != env.lane() && laneLocallyBlocked(lane, dependency)) {
                        return true;
                    }
                }
            }
            if (missing || entry.decoded == null) {
                return false;
            }
            Envelope e = entry.decoded;
            if (first) {
                first = false;
                bundle = e.bundle() == Envelope.Bundle.OPEN;
            }
            if (!bundle || e.bundle() == Envelope.Bundle.CLOSE) {
                return false;
            }
            provisional = e.sequence();
        }
        return bundle && laneLocallyBlocked(Lane.CONTROL, -1L);
    }

    private void setPaused(Entry head, boolean paused, long now) {
        if (paused && head.pausedSince < 0) {
            head.pausedSince = now;
        } else if (!paused && head.pausedSince >= 0) {
            head.headSince += now - Math.max(head.headSince, head.pausedSince);
            head.pausedSince = -1;
        }
    }

    /**
     * Recomputes, from the current state, which blocked heads and which open bundle are paused by a
     * local wait, and moves their start times forward by the time they spent paused. Nothing is
     * remembered between calls except when each pause began, so a pause ends the moment the lane or
     * decode it depended on stops being blocked.
     */
    private void updatePauses(long now) {
        boolean anyBlocker = anyDecodeBlocked();
        for (long since : receiveBlockedSince) {
            anyBlocker |= since >= 0;
        }
        for (ArrayDeque<Entry> q : queues) {
            Entry head = q.peek();
            if (head != null) {
                setPaused(head, anyBlocker && headPaused(q), now);
            }
        }
        boolean bundlePaused = anyBlocker && receivingBundle && laneLocallyBlocked(Lane.CONTROL, -1L);
        if (bundlePaused && bundlePausedSince < 0) {
            bundlePausedSince = now;
        } else if (!bundlePaused && bundlePausedSince >= 0) {
            bundleStarted += now - Math.max(bundleStarted, bundlePausedSince);
            bundlePausedSince = -1;
        }
    }

    private static final class Entry {
        final Envelope envelope;
        Envelope decoded;
        long bytes;
        int uncompressed;
        boolean decodeReserved;
        long headSince;
        boolean head;
        /** First attempt of this entry's current decode, or -1 while it has none (13.12). */
        long decodeSince = -1;
        long nodeBlockedSince = -1;
        /** When a local wait started pausing this entry's blocked-head timer, or -1. */
        long pausedSince = -1;
        /** Bytes the current decode attempt reserved, released if that attempt is refused. */
        long decodeReservedBytes;
        /** What the refused attempt needed from the node; the decode is not rerun before it is free. */
        long retryNeed;
        /** The refused request exceeds the node's capacity: no wait can ever carry it. */
        boolean impossible;
        Entry(Envelope envelope, long bytes) {
            this.envelope = envelope;
            this.bytes = bytes;
            if (envelope.kind() == Envelope.Kind.END) {
                decoded = envelope;
            }
        }
    }

    private static final class Candidate {
        Entry decode;
        Delivery delivery;
    }

    private final Decoder decoder;
    private final Handler handler;
    private final ReceiveBudget node;
    private final List<ArrayDeque<Entry>> queues = new ArrayList<ArrayDeque<Entry>>();
    private final Map<Partial, Boolean> partials = new IdentityHashMap<Partial, Boolean>();
    private final long[] received = new long[3];
    private final long[] delivered = new long[3];
    private int count;
    private int auxiliaryCount;
    private long bytes;
    private long auxiliaryBytes;
    private Delivery outstanding;
    private Entry decoding;
    private long outstandingSince;
    private long pumpNow;
    private boolean pumping;
    private boolean failed;
    private boolean ended;
    /** The admitted END, kept so auxiliary records it depends on can still arrive. */
    private Envelope endAdmitted;
    private boolean receivingBundle;
    private long bundleStarted;
    private int bundleMembers;
    private long bundleUncompressed;

    /** Creates an empty receiving direction with separate decoding and application callbacks. */
    public ReceiverScheduler(Decoder decoder, Handler handler) {
        this(decoder, handler, ReceiveBudget.unlimited());
    }

    /**
     * As {@link #ReceiverScheduler(Decoder, Handler)} with the node budget of 13.12 every retained
     * byte is additionally reserved against; {@link ReceiveBudget#unlimited()} for an owner that
     * enforces no node budget.
     */
    public ReceiverScheduler(Decoder decoder, Handler handler, ReceiveBudget node) {
        if (decoder == null || handler == null) {
            throw new IllegalArgumentException("decoder/handler");
        }
        this.decoder = decoder;
        this.handler = handler;
        this.node = node == null ? ReceiveBudget.unlimited() : node;
        for (int i = 0; i < 3; i++) {
            queues.add(new ArrayDeque<Entry>());
        }
    }

    private void enter() {
        if (failed) {
            throw new OrderingException("scheduler failed");
        }
    }

    /**
     * Re-raises an ordering failure as is; a node-budget refusal passes through so the owner can
     * withhold and retry; any other exception is fatal and latches.
     */
    private OrderingException escape(RuntimeException ex) {
        if (ex instanceof RetryLater) {
            throw (RetryLater) ex;
        }
        if (ex instanceof LocalResourceException) {
            failed = true;
            throw (LocalResourceException) ex;
        }
        if (ex instanceof OrderingException) {
            return (OrderingException) ex;
        }
        return fatal(ex);
    }

    private OrderingException fatal(RuntimeException ex) {
        failed = true;
        if (ex instanceof OrderingException) {
            return (OrderingException) ex;
        }
        return new OrderingException("unexpected failure: " + ex, ex);
    }

    /** Returns a snapshot of the committed contiguous watermarks in lane order. */
    public long[] delivered() {
        return delivered.clone();
    }

    /** Returns all charged slots, including partial, staged and outstanding records. */
    public int pendingRecords() {
        return count;
    }

    /** Returns all charged wire/parser/decoded bytes awaiting application or release. */
    public long pendingBytes() {
        return bytes;
    }

    /** True only after END has been applied; the owner then closes normally. */
    public boolean ended() {
        return ended;
    }

    // The 64 CONTROL slots and the 1 MiB reserve follow 13.12's literal "at most 960 auxiliary
    // records": auxiliary records are capped at 960 regardless of how many CONTROL records are
    // pending, so CONTROL always keeps 64 slots and 1 MiB that auxiliary lanes cannot use.
    /** Query only. Transport must separately debit outstanding granted credit before granting more. */
    public boolean auxiliaryCreditAvailable(long additionalBytes, int additionalRecords) {
        return additionalBytes >= 0 && additionalRecords >= 0
                && additionalRecords <= MAX_RECORDS - count
                && additionalRecords <= MAX_AUXILIARY_RECORDS - auxiliaryCount
                && additionalBytes <= SHARED_BYTES - auxiliaryBytes
                && additionalBytes <= SHARED_BYTES + CONTROL_RESERVE_BYTES - bytes;
    }

    private void budget(Lane lane, long size, int records) {
        if (size < 0 || records < 0 || records > MAX_RECORDS - count
                || size > SHARED_BYTES + CONTROL_RESERVE_BYTES - bytes
                || (lane != Lane.CONTROL && !auxiliaryCreditAvailable(size, records))) {
            throw new OrderingException("receive budget exceeded");
        }
    }

    private void account(Lane lane, long size, int records) {
        bytes += size;
        count += records;
        if (lane != Lane.CONTROL) {
            auxiliaryBytes += size;
            auxiliaryCount += records;
        }
    }

    /**
     * Reserves retained bytes against the shared node budget (13.12), before the per-connection
     * accounting moves. A refusal is fatal for this connection only: the bytes have already
     * arrived or are already granted and cannot be evicted, so {@code 0x11} closes it while every
     * other connection keeps its reservation.
     */
    private void debitNode(long size) {
        if (size > 0 && !node.tryReserve(size)) {
            throw new OrderingException("node receive budget exhausted (13.12)");
        }
    }

    /** Gives retained bytes back to the node budget once they are gone. */
    private void creditNode(long size) {
        if (size > 0) {
            node.release(size);
        }
    }

    /**
     * Query only, 13.12 node budget: true when the node could still carry {@code additionalBytes}.
     * The owner withholds QUIC receive credit ("the lane stops reading") exactly as it does for
     * the per-connection budget, instead of starting bytes it could never charge. It reads
     * {@link ReceiveBudget#available()}, so it reserves nothing and can never make another
     * connection's real reservation fail; a real reservation that a concurrent connection wins
     * the race for follows the fatal {@code 0x11} path, never another connection's data.
     */
    public boolean nodeCreditAvailable(long additionalBytes) {
        return additionalBytes <= 0 || node.available() >= additionalBytes;
    }

    /**
     * Releases every retained charge back to the node budget and drops all state. The owner calls
     * this exactly once when the connection closes: nothing this connection held may stay charged
     * to a budget other connections share. Not usable afterwards.
     */
    public void releaseAll() {
        long released = 0;
        for (Partial p : partials.keySet()) {
            released += p.bytes;
        }
        for (ArrayDeque<Entry> q : queues) {
            for (Entry e : q) {
                released += e.bytes;
            }
        }
        partials.clear();
        for (ArrayDeque<Entry> q : queues) {
            q.clear();
        }
        bytes = 0;
        count = 0;
        auxiliaryBytes = 0;
        auxiliaryCount = 0;
        decoding = null;
        outstanding = null;
        ended = true;
        creditNode(released);
    }

    /** Reserves a record slot and positive retained byte count at the first received byte. */
    public Partial reservePartial(Lane lane, long size, long now) {
        return reservePartial(lane, size, 0, now, now);
    }

    /**
     * As {@link #reservePartial(Lane, long, long)} with {@code prepaidBytes} already reserved
     * against the node budget (a record grant) and the record's first byte seen at
     * {@code startedAt}, so the incomplete-record timer runs from that byte.
     */
    public Partial reservePartial(Lane lane, long size, long prepaidBytes, long startedAt, long now) {
        enter();
        try {
            checkDeadlines(now);
            if (ended || lane == null || size <= 0 || prepaidBytes < 0 || prepaidBytes > size
                    || !laneOpen(lane)) {
                throw new OrderingException("invalid partial");
            }
            budget(lane, size, 1);
            debitNode(size - prepaidBytes);
            Partial p = new Partial(lane, size, startedAt);
            partials.put(p, Boolean.TRUE);
            account(lane, size, 1);
            return p;
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /** Debits more bytes before retaining another fragment; the first-byte timer is unchanged. */
    public void growPartial(Partial p, long additionalBytes, long now) {
        growPartial(p, additionalBytes, 0, now);
    }

    /** As {@link #growPartial(Partial, long, long)} with part of the growth already reserved. */
    public void growPartial(Partial p, long additionalBytes, long prepaidBytes, long now) {
        enter();
        try {
            checkDeadlines(now);
            requirePartial(p);
            if (!laneOpen(p.lane)) {
                throw new OrderingException("partial beyond END");
            }
            if (prepaidBytes < 0 || prepaidBytes > additionalBytes) {
                throw new OrderingException("invalid prepaid growth");
            }
            budget(p.lane, additionalBytes, 0);
            debitNode(additionalBytes - prepaidBytes);
            p.bytes += additionalBytes;
            account(p.lane, additionalBytes, 0);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /** Releases a parser reservation whose bytes have been discarded by the owner. */
    public void releasePartial(Partial p, long now) {
        enter();
        try {
            checkDeadlines(now);
            requirePartial(p);
            partials.remove(p);
            account(p.lane, -p.bytes, -1);
            creditNode(p.bytes);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    private void requirePartial(Partial p) {
        if (!partials.containsKey(p)) {
            throw new OrderingException("unknown partial");
        }
    }

    /** Admits a complete opaque envelope, charging its minimally encoded wire size. */
    public void offer(Envelope e, long now) {
        offer(e, e.wireBytes(), now);
    }

    /**
     * As {@link #offer(Envelope, long, long)} with {@code prepaidBytes} already reserved against
     * the node budget by the caller, so an adapter that can withhold never turns a node refusal
     * into a protocol failure.
     */
    public void offer(Envelope e, long retainedBytes, long prepaidBytes, long now) {
        enter();
        try {
            checkDeadlines(now);
            validate(e, retainedBytes);
            if (prepaidBytes < 0 || prepaidBytes > retainedBytes) {
                throw new OrderingException("invalid prepaid admission");
            }
            budget(e.lane(), retainedBytes, 1);
            debitNode(retainedBytes - prepaidBytes);
            enqueue(e, retainedBytes, now);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /** Admits opaque wire bytes only; no bundle classification or decompression is needed. */
    public void offer(Envelope e, long retainedBytes, long now) {
        enter();
        try {
            checkDeadlines(now);
            validate(e, retainedBytes);
            budget(e.lane(), retainedBytes, 1);
            debitNode(retainedBytes);
            enqueue(e, retainedBytes, now);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /** Transfers an existing reservation without double-counting its record slot. */
    public void complete(Partial p, Envelope e, long retainedBytes, long now) {
        enter();
        try {
            checkDeadlines(now);
            requirePartial(p);
            if (p.lane != e.lane()) {
                throw new OrderingException("partial lane mismatch");
            }
            validate(e, retainedBytes);
            // 13.12: the entry carries exactly its own charge. The partial's reservation moves to
            // the entry (plus any growth, minus any surplus the parser had charged), so the entry
            // is charged for as long as it is queued and applied() credits the same amount back.
            if (retainedBytes > p.bytes) {
                budget(p.lane, retainedBytes - p.bytes, 0);
                // The node budget takes the growth before the entry is queued, so the transfer can
                // never look free to another connection.
                debitNode(retainedBytes - p.bytes);
            } else if (retainedBytes < p.bytes) {
                creditNode(p.bytes - retainedBytes);
            }
            partials.remove(p);
            account(p.lane, -p.bytes, -1);
            enqueue(e, retainedBytes, now);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /**
     * Before END every lane is open. After END is admitted, CONTROL is closed and an auxiliary lane
     * stays open only while records up to END's dependency on it are still outstanding: END rides
     * CONTROL and may overtake them (13.14).
     */
    private boolean laneOpen(Lane lane) {
        if (endAdmitted == null) {
            return true;
        }
        return lane != Lane.CONTROL
                && Long.compareUnsigned(received[lane.ordinal()], endAdmitted.dependency(lane)) < 0;
    }

    private void validate(Envelope e, long retained) {
        int i = e.lane().ordinal();
        if (ended || received[i] == -1L
                || e.sequence() != received[i] + 1
                || e.dependency(e.lane()) != e.sequence() - 1) {
            throw new OrderingException("sequence/own dependency violation");
        }
        if (endAdmitted != null && !laneOpen(e.lane())) {
            throw new OrderingException("record beyond END");
        }
        if (e.kind() == Envelope.Kind.END) {
            for (Lane lane : Lane.values()) {
                if (lane != Lane.CONTROL
                        && Long.compareUnsigned(received[lane.ordinal()], e.dependency(lane)) > 0) {
                    throw new OrderingException("record already beyond END");
                }
            }
        }
        if (retained < e.wireBytes()) {
            throw new OrderingException("retained wire size");
        }
    }

    private void enqueue(Envelope e, long retained, long now) {
        received[e.lane().ordinal()] = e.sequence();
        if (e.kind() == Envelope.Kind.END) {
            endAdmitted = e;
        }
        account(e.lane(), retained, 1);
        queues.get(e.lane().ordinal()).add(new Entry(e, retained));
        markHeads(now);
        pump(now);
    }

    private void markHeads(long now) {
        for (ArrayDeque<Entry> q : queues) {
            Entry e = q.peek();
            if (e != null && !e.head) {
                e.head = true;
                e.headSince = now;
            }
        }
    }

    private boolean ready(Envelope e, long provisionalControl) {
        for (Lane lane : Lane.values()) {
            long watermark = lane == Lane.CONTROL ? provisionalControl : delivered[lane.ordinal()];
            if (Long.compareUnsigned(e.dependency(lane), watermark) > 0) {
                return false;
            }
        }
        return true;
    }

    /** Mirror of {@link #eligible} that builds no candidate or list: true when the queue has nothing to act on. */
    private boolean blocked(ArrayDeque<Entry> q) {
        long provisional = delivered[0];
        boolean bundle = false;
        boolean first = true;
        for (Entry entry : q) {
            if (!ready(entry.envelope, provisional)) {
                return true;
            }
            if (entry.decoded == null) {
                return false;
            }
            Envelope e = entry.decoded;
            if (bundle && e.kind() == Envelope.Kind.END) {
                throw new OrderingException("END inside bundle");
            }
            if (first) {
                first = false;
                bundle = e.bundle() == Envelope.Bundle.OPEN;
            }
            if (!bundle || e.bundle() == Envelope.Bundle.CLOSE) {
                return false;
            }
            provisional = e.sequence();
        }
        return true;
    }

    private Candidate eligible(ArrayDeque<Entry> q) {
        if (q.isEmpty()) {
            return null;
        }
        Candidate candidate;
        List<Envelope> unit = null;
        long provisional = delivered[0];
        boolean bundle = false;
        for (Entry entry : q) {
            if (!ready(entry.envelope, provisional)) {
                return null;
            }
            if (entry.decoded == null) {
                candidate = new Candidate();
                candidate.decode = entry;
                return candidate;
            }
            Envelope e = entry.decoded;
            if (bundle && e.kind() == Envelope.Kind.END) {
                throw new OrderingException("END inside bundle");
            }
            if (unit == null) {
                unit = new ArrayList<Envelope>();
                bundle = e.bundle() == Envelope.Bundle.OPEN;
            }
            unit.add(e);
            if (!bundle || e.bundle() == Envelope.Bundle.CLOSE) {
                candidate = new Candidate();
                candidate.delivery = new Delivery(unit);
                return candidate;
            }
            provisional = e.sequence();
        }
        return null;
    }

    /**
     * Gives back everything the current decode attempt reserved and resets the entry's decode
     * state, so the next attempt starts from the entry's own wire charge again (13.12). The entry
     * stays queued and undecoded; only what this attempt added goes back.
     */
    private void releaseDecodeAttempt(Entry entry) {
        long reserved = entry.decodeReservedBytes;
        if (reserved > 0) {
            entry.bytes -= reserved;
            account(entry.envelope.lane(), -reserved, 0);
            creditNode(reserved);
        }
        entry.decodeReservedBytes = 0;
        entry.decodeReserved = false;
        entry.uncompressed = 0;
    }

    private void pump(long now) {
        // An inline applied() may advance time while the non-recursive drain is active.
        pumpNow = now;
        if (pumping || ended) {
            return;
        }
        pumping = true;
        try {
            while (outstanding == null && decoding == null && !ended) {
                Candidate next = null;
                for (ArrayDeque<Entry> q : queues) {
                    next = eligible(q);
                    if (next != null) {
                        break;
                    }
                }
                if (next == null) {
                    break;
                }
                if (next.decode != null) {
                    Entry candidate = next.decode;
                    if (candidate.nodeBlockedSince >= 0 && node.available() < candidate.retryNeed) {
                        // 13.12: the refused attempt needed more than the node has free right now.
                        // Rerunning the game's decode could only be refused again, so it waits (still
                        // paused, still counted by the owner's cap) until that much is free.
                        return;
                    }
                    decoding = candidate;
                    if (decoding.nodeBlockedSince >= 0) {
                        decoding.decodeSince += pumpNow - Math.max(decoding.decodeSince, decoding.nodeBlockedSince);
                        decoding.nodeBlockedSince = -1;
                        updatePauses(pumpNow);
                    }
                    if (decoding.decodeSince < 0) {
                        // This entry's own first attempt. A retry of the same entry keeps it, so the
                        // 13.12 decode deadline runs from the first attempt; a later entry starts
                        // its own, and a successful decode clears it.
                        decoding.decodeSince = pumpNow;
                    }
                    try {
                        decoder.decode(decoding.envelope,
                                receivingBundle && decoding.envelope.lane() == Lane.CONTROL, pumpNow);
                    } catch (RetryLater retry) {
                        // 13.12: the node budget refused this attempt's allocation. Give this
                        // attempt's own reservations back before the entry waits: the decoder is
                        // rerun from its start, so keeping them would charge the same output again on
                        // every retry and drain the budget.
                        Entry refused = decoding;
                        releaseDecodeAttempt(refused);
                        decoding = null;
                        if (refused.impossible) {
                            throw new LocalResourceException("a decode needs " + refused.retryNeed
                                    + " bytes but the node budget holds at most " + node.capacity()
                                    + ": it can never be carried (13.12)");
                        }
                        refused.nodeBlockedSince = pumpNow;
                        updatePauses(pumpNow);
                        return;
                    } catch (RuntimeException ex) {
                        throw fatal(ex);
                    }
                } else {
                    outstanding = next.delivery;
                    outstandingSince = pumpNow;
                    try {
                        handler.deliver(outstanding);
                    } catch (RuntimeException ex) {
                        throw fatal(ex);
                    }
                }
            }
        } finally {
            pumping = false;
        }
    }

    /**
     * Debits output before allocation, only for the current eligible decode request.
     *
     * <p>All retained bytes stay charged until applied(), including staged bundle members.
     * Each call adds {@code additionalRetainedBytes} but replaces the packet's total uncompressed
     * size estimate. Count decompression buffers and decoded objects alongside retained wire
     * bytes. A zero-byte debit is valid for classification that makes no extra allocation.</p>
     */
    public void reserveDecoded(Envelope envelope, long additionalRetainedBytes,
                              int totalUncompressedBytes, long now) {
        enter();
        try {
            checkDeadlines(now);
            if (decoding == null || decoding.envelope != envelope
                    || totalUncompressedBytes < 0 || totalUncompressedBytes > 8 * 1024 * 1024) {
                throw new OrderingException("ineligible/oversized decode");
            }
            if (receivingBundle && envelope.lane() == Lane.CONTROL
                    && bundleUncompressed + totalUncompressedBytes > 8L * 1024 * 1024) {
                throw new OrderingException("decoded bundle bounds");
            }
            budget(envelope.lane(), additionalRetainedBytes, 0);
            if (additionalRetainedBytes > 0 && !node.tryReserve(additionalRetainedBytes)) {
                // 13.12: the bytes are about to be allocated, not yet received, so the owner can
                // wait for node space instead of failing the connection. The refusal is rethrown as
                // the retry the game already passes through; what it needed is kept so the decode is
                // not rerun before that much is free, and a need above the node's whole capacity is
                // flagged so the scheduler fails it locally once the game has let the throw out.
                decoding.retryNeed = decoding.decodeReservedBytes + additionalRetainedBytes;
                decoding.impossible = decoding.bytes + additionalRetainedBytes > node.capacity();
                throw new RetryLater();
            }
            decoding.bytes += additionalRetainedBytes;
            decoding.decodeReservedBytes += additionalRetainedBytes;
            decoding.uncompressed = totalUncompressedBytes;
            decoding.decodeReserved = true;
            account(envelope.lane(), additionalRetainedBytes, 0);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /**
     * Reports classification only after eligible, budgeted decoding has completed.
     *
     * <p>State/compression/activation changes are invalid in a bundle. NONE denotes an ordinary
     * packet outside a bundle; the adapter reports MEMBER for ordinary packets inside one. The
     * supplied decoded body replaces the opaque body in the eventual delivery. A decoded OPEN
     * starts the missing-close deadline: identifying it before eligibility would require the
     * speculative packet decoding forbidden by 13.16.</p>
     */
    public void decoded(Envelope envelope, Envelope.Bundle classification,
                        boolean changesStateCompressionOrActivation, Object body, long now) {
        enter();
        try {
            checkDeadlines(now);
            if (decoding == null || decoding.envelope != envelope || !decoding.decodeReserved
                    || classification == null) {
                throw new OrderingException("unexpected decode result");
            }
            boolean control = envelope.lane() == Lane.CONTROL;
            if (!control && (classification != Envelope.Bundle.NONE || changesStateCompressionOrActivation)) {
                throw new OrderingException("wrong lane for bundle/state change");
            }
            if (control) {
                if (receivingBundle) {
                    if (changesStateCompressionOrActivation || (classification != Envelope.Bundle.MEMBER
                            && classification != Envelope.Bundle.CLOSE)) {
                        throw new OrderingException("invalid member or nested bundle");
                    }
                    if (classification == Envelope.Bundle.MEMBER
                            && bundleMembers == SenderSequencer.MAX_BUNDLE_MEMBERS) {
                        throw new OrderingException("bundle member count");
                    }
                    bundleUncompressed += decoding.uncompressed;
                    if (classification == Envelope.Bundle.MEMBER) {
                        bundleMembers++;
                    } else {
                        receivingBundle = false;
                    }
                } else if (classification == Envelope.Bundle.OPEN) {
                    if (changesStateCompressionOrActivation) {
                        throw new OrderingException("state-changing delimiter");
                    }
                    receivingBundle = true;
                    bundleStarted = now;
                    bundleMembers = 0;
                    bundleUncompressed = decoding.uncompressed;
                } else if (classification != Envelope.Bundle.NONE) {
                    throw new OrderingException("unexpected bundle member/delimiter");
                }
            }
            decoding.decoded = new Envelope(envelope.lane(), envelope.kind(), envelope.sequence(),
                    envelope.dependencies(), envelope.bodyLength(), body, classification,
                    changesStateCompressionOrActivation);
            // The decode is done: its deadline and the attempt's reservation tally both reset, so
            // they cannot reach a later entry.
            decoding.decodeSince = -1;
            decoding.decodeReservedBytes = 0;
            decoding = null;
            pump(now);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /**
     * Retries the head that a node-budget refusal left undecoded (13.12). The owner calls this from
     * its tick while such a refusal is pending; nothing else about the queue state changes.
     */
    public void retry(long now) {
        enter();
        try {
            pump(now);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /** Commits the single outstanding unit after application, then retries eligible heads. */
    public void applied(Lane lane, long sequence, long now) {
        enter();
        try {
            checkDeadlines(now);
            if (outstanding == null || lane != outstanding.lane() || sequence != outstanding.sequence()) {
                throw new OrderingException("unexpected handler acknowledgement");
            }
            for (Envelope e : outstanding.records()) {
                Entry entry = queues.get(lane.ordinal()).remove();
                account(lane, -entry.bytes, -1);
                creditNode(entry.bytes);
                if (e.kind() == Envelope.Kind.END) {
                    ended = true;
                }
            }
            delivered[lane.ordinal()] = sequence;
            outstanding = null;
            markHeads(now);
            pump(now);
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /** Checks all resource/ordering deadlines without advancing or releasing any watermark. */
    public void checkDeadlines(long now) {
        enter();
        try {
            updatePauses(now);
            // Local allocation refusal pauses the decode timer, not application/handler timeouts.
            for (ArrayDeque<Entry> q : queues) {
                for (Entry entry : q) {
                    if (entry.nodeBlockedSince < 0 && entry.decodeSince >= 0
                            && now - entry.decodeSince >= TIMEOUT_NANOS) {
                        throw new OrderingException("uncompleted decode timeout");
                    }
                }
            }
            if (bundlePausedSince < 0 && receivingBundle && now - bundleStarted >= TIMEOUT_NANOS) {
                throw new OrderingException("missing bundle close timeout");
            }
            if (outstanding != null && now - outstandingSince >= TIMEOUT_NANOS) {
                throw new OrderingException("unapplied handler timeout");
            }
            for (Partial p : partials.keySet()) {
                if (receiveBlockedSince[p.lane.ordinal()] < 0 && now - p.started >= TIMEOUT_NANOS) {
                    throw new OrderingException("incomplete record timeout");
                }
            }
            for (ArrayDeque<Entry> q : queues) {
                Entry head = q.peek();
                if (head != null && head.pausedSince < 0 && now - head.headSince >= TIMEOUT_NANOS
                        && (outstanding == null || outstanding.lane() != head.envelope.lane())
                        && (decoding == null || decoding.envelope.lane() != head.envelope.lane())
                        && blocked(q)) {
                    throw new OrderingException("blocked head/bundle timeout");
                }
            }
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }
}
