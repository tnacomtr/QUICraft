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
 * <p>Not thread-safe. Every call and callback belongs on the owner's game executor. Time is a
 * caller-supplied monotonic nanosecond value. Any {@link OrderingException} requires closing the
 * whole connection; do not resume using this instance after failure.</p>
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
    private boolean ended;
    /** The admitted END, kept so auxiliary records it depends on can still arrive. */
    private Envelope endAdmitted;
    private boolean receivingBundle;
    private long bundleStarted;
    private int bundleMembers;
    private long bundleUncompressed;

    /** Creates an empty receiving direction with separate decoding and application callbacks. */
    public ReceiverScheduler(Decoder decoder, Handler handler) {
        if (decoder == null || handler == null) {
            throw new IllegalArgumentException("decoder/handler");
        }
        this.decoder = decoder;
        this.handler = handler;
        for (int i = 0; i < 3; i++) {
            queues.add(new ArrayDeque<Entry>());
        }
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

    /** Reserves a record slot and positive retained byte count at the first received byte. */
    public Partial reservePartial(Lane lane, long size, long now) {
        checkDeadlines(now);
        if (ended || lane == null || size <= 0 || !laneOpen(lane)) {
            throw new OrderingException("invalid partial");
        }
        budget(lane, size, 1);
        Partial p = new Partial(lane, size, now);
        partials.put(p, Boolean.TRUE);
        account(lane, size, 1);
        return p;
    }
    /** Debits more bytes before retaining another fragment; the first-byte timer is unchanged. */
    public void growPartial(Partial p, long additionalBytes, long now) {
        checkDeadlines(now);
        requirePartial(p);
        if (!laneOpen(p.lane)) {
            throw new OrderingException("partial beyond END");
        }
        budget(p.lane, additionalBytes, 0);
        p.bytes += additionalBytes;
        account(p.lane, additionalBytes, 0);
    }

    /** Releases a parser reservation whose bytes have been discarded by the owner. */
    public void releasePartial(Partial p, long now) {
        checkDeadlines(now);
        requirePartial(p);
        partials.remove(p);
        account(p.lane, -p.bytes, -1);
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
        checkDeadlines(now);
        validate(e, retainedBytes);
        budget(e.lane(), retainedBytes, 1);
        enqueue(e, retainedBytes, now);
    }
    /** Transfers an existing reservation without double-counting its record slot. */
    public void complete(Partial p, Envelope e, long retainedBytes, long now) {
        checkDeadlines(now);
        requirePartial(p);
        if (p.lane != e.lane()) {
            throw new OrderingException("partial lane mismatch");
        }
        validate(e, retainedBytes);
        if (retainedBytes > p.bytes) {
            budget(p.lane, retainedBytes - p.bytes, 0);
        }
        partials.remove(p);
        account(p.lane, -p.bytes, -1);
        enqueue(e, retainedBytes, now);
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
    private Candidate eligible(ArrayDeque<Entry> q) {
        if (q.isEmpty()) {
            return null;
        }
        Candidate candidate = new Candidate();
        List<Envelope> unit = new ArrayList<Envelope>();
        long provisional = delivered[0];
        boolean bundle = false;
        for (Entry entry : q) {
            if (!ready(entry.envelope, provisional)) {
                return null;
            }
            if (entry.decoded == null) {
                candidate.decode = entry;
                return candidate;
            }
            Envelope e = entry.decoded;
            if (bundle && e.kind() == Envelope.Kind.END) {
                throw new OrderingException("END inside bundle");
            }
            if (unit.isEmpty()) {
                bundle = e.bundle() == Envelope.Bundle.OPEN;
            }
            unit.add(e);
            if (!bundle || e.bundle() == Envelope.Bundle.CLOSE) {
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
                    decoder.decode(decoding.envelope,
                            receivingBundle && decoding.envelope.lane() == Lane.CONTROL, pumpNow);
                } else {
                    outstanding = next.delivery;
                    outstandingSince = pumpNow;
                    handler.deliver(outstanding);
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
        decoding.bytes += additionalRetainedBytes;
        decoding.uncompressed = totalUncompressedBytes;
        decoding.decodeReserved = true;
        account(envelope.lane(), additionalRetainedBytes, 0);
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
    }
    /** Commits the single outstanding unit after application, then retries eligible heads. */
    public void applied(Lane lane, long sequence, long now) {
        checkDeadlines(now);
        if (outstanding == null || lane != outstanding.lane() || sequence != outstanding.sequence()) {
            throw new OrderingException("unexpected handler acknowledgement");
        }
        for (Envelope e : outstanding.records()) {
            Entry entry = queues.get(lane.ordinal()).remove();
            account(lane, -entry.bytes, -1);
            if (e.kind() == Envelope.Kind.END) {
                ended = true;
            }
        }
        delivered[lane.ordinal()] = sequence;
        outstanding = null;
        markHeads(now);
        pump(now);
    }

    /** Checks all resource/ordering deadlines without advancing or releasing any watermark. */
    public void checkDeadlines(long now) {
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
            if (head != null && eligible(q) == null
                    && (outstanding == null || outstanding.lane() != head.envelope.lane())
                    && (decoding == null || decoding.envelope.lane() != head.envelope.lane())
                    && now - head.headSince >= TIMEOUT_NANOS) {
                throw new OrderingException("blocked head/bundle timeout");
            }
        }
    }
}
