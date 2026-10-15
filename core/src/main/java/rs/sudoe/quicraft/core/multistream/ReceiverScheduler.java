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

    /** Decodes only an eligible record, reserving output before allocation and reporting decoded(). */
    public interface Decoder {
        /**
         * Receives a dependency-eligible opaque record, never a blocked record.
         *
         * <p>Call reserveDecoded before allocating output, then decoded after parsing and
         * classification. The identity of {@code envelope} is the decode token. Callbacks may
         * complete inline or later on the same executor. {@code insideBundle} is true only for
         * CONTROL records following a decoded OPEN and preceding its decoded CLOSE.</p>
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
        private final long started;
        private long bytes;
        private Partial(Lane lane, long bytes, long now) {
            this.lane = lane;
            this.bytes = bytes;
            this.started = now;
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
    private long decodingSince;
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

    /** Re-raises an ordering failure as is; any other exception is fatal and latches. */
    private OrderingException escape(RuntimeException ex) {
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
     * the per-connection budget, instead of starting bytes it could never charge. The probe
     * reserves and immediately releases, so a concurrent connection may win the race and the
     * later real reservation then follows the fatal {@code 0x11} path; never another connection's
     * data.
     */
    public boolean nodeCreditAvailable(long additionalBytes) {
        if (additionalBytes <= 0) {
            return true;
        }
        if (!node.tryReserve(additionalBytes)) {
            return false;
        }
        node.release(additionalBytes);
        return true;
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
        enter();
        try {
            checkDeadlines(now);
            if (ended || lane == null || size <= 0 || !laneOpen(lane)) {
                throw new OrderingException("invalid partial");
            }
            budget(lane, size, 1);
            debitNode(size);
            Partial p = new Partial(lane, size, now);
            partials.put(p, Boolean.TRUE);
            account(lane, size, 1);
            return p;
        } catch (RuntimeException ex) {
            throw escape(ex);
        }
    }

    /** Debits more bytes before retaining another fragment; the first-byte timer is unchanged. */
    public void growPartial(Partial p, long additionalBytes, long now) {
        enter();
        try {
            checkDeadlines(now);
            requirePartial(p);
            if (!laneOpen(p.lane)) {
                throw new OrderingException("partial beyond END");
            }
            budget(p.lane, additionalBytes, 0);
            debitNode(additionalBytes);
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
            if (retainedBytes > p.bytes) {
                budget(p.lane, retainedBytes - p.bytes, 0);
                // The node budget takes the growth before the partial's charge is returned, so the
                // transfer can never look free to another connection.
                debitNode(retainedBytes - p.bytes);
            }
            partials.remove(p);
            account(p.lane, -p.bytes, -1);
            creditNode(p.bytes);
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
                    decoding = next.decode;
                    decodingSince = pumpNow;
                    try {
                        decoder.decode(decoding.envelope,
                                receivingBundle && decoding.envelope.lane() == Lane.CONTROL, pumpNow);
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
            debitNode(additionalRetainedBytes);
            decoding.bytes += additionalRetainedBytes;
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
            decoding = null;
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
            if (decoding != null && now - decodingSince >= TIMEOUT_NANOS) {
                throw new OrderingException("uncompleted decode timeout");
            }
            if (receivingBundle && now - bundleStarted >= TIMEOUT_NANOS) {
                throw new OrderingException("missing bundle close timeout");
            }
            if (outstanding != null && now - outstandingSince >= TIMEOUT_NANOS) {
                throw new OrderingException("unapplied handler timeout");
            }
            for (Partial p : partials.keySet()) {
                if (now - p.started >= TIMEOUT_NANOS) {
                    throw new OrderingException("incomplete record timeout");
                }
            }
            for (ArrayDeque<Entry> q : queues) {
                Entry head = q.peek();
                if (head != null && now - head.headSince >= TIMEOUT_NANOS
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
