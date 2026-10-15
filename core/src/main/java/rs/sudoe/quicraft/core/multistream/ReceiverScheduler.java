// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import rs.sudoe.quicraft.core.routing.Lane;

/** Pure serial ordering core. All calls and callbacks belong on the owner's game executor.
 * Failures are terminal: the owner must close, not resume using this instance.
 */
public final class ReceiverScheduler {
    public static final int MAX_RECORDS = 1024;
    public static final int MAX_AUXILIARY_RECORDS = 960;
    public static final long SHARED_BYTES = 16L * 1024 * 1024;
    public static final long CONTROL_RESERVE_BYTES = 1024 * 1024;
    public static final long TIMEOUT_NANOS = 10000000000L;

    /** Handoff, not a commit: decode/validate the whole unit before invoking game logic,
     * debit retained output via retainDecoded(), then acknowledge only after application.
     * The owner applies stricter game-specific packet/bundle limits and closes on failure.
     */
    public interface Handler { void deliver(Delivery delivery); }
    /** One record, or a whole bundle. Acknowledge the final sequence, never a member. */
    public static final class Delivery {
        private final List<Envelope> records;
        private Delivery(List<Envelope> records) {
            this.records = Collections.unmodifiableList(records);
        }
        public List<Envelope> records() { return records; }
        public Lane lane() { return records.get(0).lane(); }
        public long sequence() { return records.get(records.size() - 1).sequence(); }
    }
    /** Identity token for a first-byte reservation; never reuse after release/complete. */
    public static final class Partial {
        private final Lane lane;
        private final long started;
        private long bytes;
        private Partial(Lane lane, long bytes, long now) {
            this.lane = lane; this.bytes = bytes; this.started = now;
        }
    }
    private static final class Entry {
        final Envelope envelope;
        long bytes;
        int uncompressed;
        long headSince;
        boolean head;
        Entry(Envelope envelope, long bytes, int uncompressed) {
            this.envelope = envelope; this.bytes = bytes; this.uncompressed = uncompressed;
        }
    }
    private final Handler handler;
    private final List<ArrayDeque<Entry>> queues = new ArrayList<ArrayDeque<Entry>>();
    private final Map<Partial, Boolean> partials = new IdentityHashMap<Partial, Boolean>();
    private final long[] received = new long[3];
    private final long[] delivered = new long[3];
    private int count, auxiliaryCount;
    private long bytes, auxiliaryBytes;
    private Delivery outstanding;
    private long outstandingSince;
    private long pumpNow;
    private boolean pumping, ended, controlEnded;
    private boolean receivingBundle;
    private long bundleStarted;
    private int bundleMembers;
    private long bundleUncompressed;

    public ReceiverScheduler(Handler handler) {
        if (handler == null) throw new IllegalArgumentException("handler");
        this.handler = handler;
        for (int i = 0; i < 3; i++) queues.add(new ArrayDeque<Entry>());
    }
    public long[] delivered() { return delivered.clone(); }
    public int pendingRecords() { return count; }
    public long pendingBytes() { return bytes; }
    public boolean ended() { return ended; }
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
                || (lane != Lane.CONTROL && !auxiliaryCreditAvailable(size, records)))
            throw new OrderingException("receive budget exceeded");
    }
    private void account(Lane lane, long size, int records) {
        bytes += size; count += records;
        if (lane != Lane.CONTROL) { auxiliaryBytes += size; auxiliaryCount += records; }
    }
    public Partial reservePartial(Lane lane, long size, long now) {
        checkDeadlines(now);
        if (ended || lane == null || size <= 0) throw new OrderingException("invalid partial");
        budget(lane, size, 1);
        Partial p = new Partial(lane, size, now);
        partials.put(p, Boolean.TRUE); account(lane, size, 1);
        return p;
    }
    public void growPartial(Partial p, long additionalBytes, long now) {
        checkDeadlines(now); requirePartial(p); budget(p.lane, additionalBytes, 0);
        p.bytes += additionalBytes; account(p.lane, additionalBytes, 0);
    }
    public void releasePartial(Partial p, long now) {
        checkDeadlines(now); requirePartial(p);
        partials.remove(p); account(p.lane, -p.bytes, -1);
    }
    private void requirePartial(Partial p) {
        if (!partials.containsKey(p)) throw new OrderingException("unknown partial");
    }
    public void offer(Envelope e, long now) {
        offer(e, e.wireBytes(), e.bodyLength(), now);
    }
    /** retainedBytes includes wire/parser buffers plus retained decoded output, not just body bytes.
     * uncompressedBytes is supplied by the boundary-aware bundle decoder (no speculative decoding).
     */
    public void offer(Envelope e, long retainedBytes, int uncompressedBytes, long now) {
        checkDeadlines(now);
        validate(e, retainedBytes, uncompressedBytes);
        budget(e.lane(), retainedBytes, 1);
        enqueue(e, retainedBytes, uncompressedBytes, now);
    }
    /** Transfers an existing reservation without double-counting its record slot. */
    public void complete(Partial p, Envelope e, long retainedBytes, int uncompressedBytes, long now) {
        checkDeadlines(now); requirePartial(p);
        if (p.lane != e.lane()) throw new OrderingException("partial lane mismatch");
        validate(e, retainedBytes, uncompressedBytes);
        if (retainedBytes > p.bytes) budget(p.lane, retainedBytes - p.bytes, 0);
        partials.remove(p); account(p.lane, -p.bytes, -1);
        enqueue(e, retainedBytes, uncompressedBytes, now);
    }
    private void validate(Envelope e, long retained, int uncompressed) {
        int i = e.lane().ordinal();
        if (ended || (i == 0 && controlEnded) || received[i] == -1L
                || e.sequence() != received[i] + 1
                || e.dependency(e.lane()) != e.sequence() - 1)
            throw new OrderingException("sequence/own dependency violation");
        if (retained < e.wireBytes() || uncompressed < 0 || uncompressed > 8 * 1024 * 1024)
            throw new OrderingException("retained/decompressed size");
        if (i == 0) {
            Envelope.Bundle b = e.bundle();
            if ((!receivingBundle && (b == Envelope.Bundle.MEMBER || b == Envelope.Bundle.CLOSE))
                    || (receivingBundle && b != Envelope.Bundle.MEMBER && b != Envelope.Bundle.CLOSE))
                throw new OrderingException("bundle delimiter order");
            if ((b == Envelope.Bundle.MEMBER && bundleMembers == SenderSequencer.MAX_BUNDLE_MEMBERS)
                    || ((b == Envelope.Bundle.MEMBER || b == Envelope.Bundle.CLOSE)
                    && bundleUncompressed + uncompressed > 8L * 1024 * 1024))
                throw new OrderingException("bundle bounds");
        }
    }
    private void enqueue(Envelope e, long retained, int uncompressed, long now) {
        received[e.lane().ordinal()] = e.sequence();
        if (e.kind() == Envelope.Kind.END) controlEnded = true;
        if (e.bundle() == Envelope.Bundle.OPEN) {
            receivingBundle = true; bundleStarted = now; bundleMembers = 0;
            bundleUncompressed = uncompressed;
        } else if (e.bundle() == Envelope.Bundle.MEMBER) {
            bundleMembers++; bundleUncompressed += uncompressed;
        } else if (e.bundle() == Envelope.Bundle.CLOSE) receivingBundle = false;
        account(e.lane(), retained, 1);
        queues.get(e.lane().ordinal()).add(new Entry(e, retained, uncompressed));
        markHeads(now); pump(now);
    }
    private void markHeads(long now) {
        for (ArrayDeque<Entry> q : queues) {
            Entry e = q.peek();
            if (e != null && !e.head) { e.head = true; e.headSince = now; }
        }
    }
    private boolean ready(Envelope e, long provisionalControl) {
        for (Lane lane : Lane.values()) {
            long watermark = lane == Lane.CONTROL ? provisionalControl : delivered[lane.ordinal()];
            if (Long.compareUnsigned(e.dependency(lane), watermark) > 0) return false;
        }
        return true;
    }
    private Delivery eligible(ArrayDeque<Entry> q) {
        if (q.isEmpty()) return null;
        List<Envelope> unit = new ArrayList<Envelope>();
        long provisional = delivered[0];
        boolean bundle = q.peek().envelope.bundle() == Envelope.Bundle.OPEN;
        for (Entry entry : q) {
            Envelope e = entry.envelope;
            if (!ready(e, provisional)) return null;
            unit.add(e);
            if (!bundle || e.bundle() == Envelope.Bundle.CLOSE) return new Delivery(unit);
            provisional = e.sequence();
        }
        return null;
    }
    private void pump(long now) {
        // An inline applied() may advance time while the non-recursive drain is active.
        pumpNow = now;
        if (pumping || ended) return;
        pumping = true;
        try {
            while (outstanding == null && !ended) {
                Delivery next = null;
                for (ArrayDeque<Entry> q : queues) {
                    next = eligible(q);
                    if (next != null) break;
                }
                if (next == null) break;
                outstanding = next; outstandingSince = pumpNow;
                handler.deliver(next);
                if (outstanding != null) break;
            }
        } finally { pumping = false; }
    }
    /** Debit before allocating decoding output, only for an eligible outstanding record.
     * additionalRetainedBytes counts allocations still retained alongside the original bytes;
     * totalUncompressedBytes replaces the earlier size estimate, and obeys the packet/bundle cap.
     * All debits are released on applied(), never on callback return.
     */
    public void retainDecoded(Envelope envelope, long additionalRetainedBytes,
                              int totalUncompressedBytes, long now) {
        checkDeadlines(now);
        if (outstanding == null || !outstanding.records().contains(envelope)
                || totalUncompressedBytes < 0 || totalUncompressedBytes > 8 * 1024 * 1024)
            throw new OrderingException("ineligible/oversized decode");
        Entry target = null;
        long bundleSize = 0;
        boolean bundle = outstanding.records().size() > 1;
        for (Entry entry : queues.get(envelope.lane().ordinal())) {
            if (!outstanding.records().contains(entry.envelope)) break;
            if (entry.envelope == envelope) target = entry;
            if (bundle)
                bundleSize += entry.envelope == envelope ? totalUncompressedBytes : entry.uncompressed;
        }
        if (target == null || (bundle && bundleSize > 8L * 1024 * 1024))
            throw new OrderingException("decoded bundle bounds");
        budget(envelope.lane(), additionalRetainedBytes, 0);
        target.bytes += additionalRetainedBytes; target.uncompressed = totalUncompressedBytes;
        account(envelope.lane(), additionalRetainedBytes, 0);
    }
    public void applied(Lane lane, long sequence, long now) {
        checkDeadlines(now);
        if (outstanding == null || lane != outstanding.lane() || sequence != outstanding.sequence())
            throw new OrderingException("unexpected handler acknowledgement");
        for (Envelope e : outstanding.records()) {
            Entry entry = queues.get(lane.ordinal()).remove();
            account(lane, -entry.bytes, -1);
            if (e.kind() == Envelope.Kind.END) ended = true;
        }
        delivered[lane.ordinal()] = sequence;
        outstanding = null; markHeads(now); pump(now);
    }
    public void checkDeadlines(long now) {
        if (receivingBundle && now - bundleStarted >= TIMEOUT_NANOS)
            throw new OrderingException("missing bundle close timeout");
        if (outstanding != null && now - outstandingSince >= TIMEOUT_NANOS)
            throw new OrderingException("unapplied handler timeout");
        for (Partial p : partials.keySet())
            if (now - p.started >= TIMEOUT_NANOS) throw new OrderingException("incomplete record timeout");
        for (ArrayDeque<Entry> q : queues) {
            Entry head = q.peek();
            if (head != null && eligible(q) == null
                    && (outstanding == null || outstanding.lane() != head.envelope.lane())
                    && now - head.headSince >= TIMEOUT_NANOS)
                throw new OrderingException("blocked head/bundle timeout");
        }
    }
}
