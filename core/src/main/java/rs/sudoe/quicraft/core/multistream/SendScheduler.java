// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import rs.sudoe.quicraft.core.profile.ServerProfile;

/**
 * Dependency-aware, bounded write policy that runs before record bytes enter QUIC (docs/protocol.md
 * 13.20). It is local policy, not wire format: it never changes stream order, dependency
 * construction or bundle atomicity.
 *
 * <p>Rules:
 * <ul>
 * <li>Each logical stream is a FIFO. Only the head of a stream can be written, so a record is never
 *     sent before its own predecessor and bytes on one stream are never reordered.</li>
 * <li>A head is ready when every explicit dependency has been fully written (its last slice
 *     selected).</li>
 * <li>Records are written in slices of at most {@link #QUANTUM_BYTES}. Between two slices of one
 *     record, other streams may be selected, so a large record never blocks a REALTIME record for
 *     more than one quantum. Inside a bundle run, stream 0 stays open (13.15).</li>
 * <li>Urgent records (REALTIME, and ACK_JOIN or BATCH_JOIN) whose prerequisites are unsent promote
 *     the earliest ready prerequisite chain instead of filling QUIC with dependents.</li>
 * <li>Each ready, writable CHUNK counts the selections that pass it over. Its count resets only when
 *     that CHUNK is picked. A CHUNK at {@link #MAX_CHUNK_SKIPS} is taken before promotion and before
 *     round-robin, so chunks progress under any REALTIME, CONTROL or promoted load of other shards.</li>
 * <li>Fair selection: each class (REALTIME, CHUNK, CONTROL) has a virtual time advanced by the
 *     slice bytes divided by its profile weight. The lowest one wins, so each runnable class gets a
 *     share proportional to its weight. Ties go REALTIME, then CHUNK, then CONTROL. REALTIME is also
 *     limited to {@link #MAX_REALTIME_BURST} consecutive picks while another class is ready.</li>
 * <li>Chunk streams are served round-robin among shards that are ready and writable.</li>
 * <li>A ready CONTROL head that has waited {@link #AGE_LIMIT} selections since it became the CONTROL
 *     head is taken regardless of weights, so small CONTROL traffic keeps progressing under sustained
 *     load. The age never starts at offer time, so a deep CONTROL backlog cannot make every head
 *     "aged" and starve REALTIME.</li>
 * <li>A record offered with {@code bundleContinues} keeps stream 0 open until the closing record is
 *     fully written: the next selection must be the following bundle record.</li>
 * <li>While a bundle is open, the skip, burst and aging bounds are suspended: the whole bundle is one
 *     CONTROL scheduling unit (13.15), so nothing else is selected until its closing record is
 *     written.</li>
 * </ul>
 *
 * <p>Thread-safe through its monitor. Weights, burst and aging constants are benchmark policy
 * (13.20), not negotiated data.
 */
public final class SendScheduler {
    /** Write quantum: no transport write is accounted above 64 KiB (13.20). */
    public static final int QUANTUM_BYTES = 64 * 1024;
    /** Largest encoded record including its outer length prefix (13.4). */
    public static final int MAX_RECORD_BYTES = 2_097_212;
    /** Consecutive REALTIME selections allowed while another class is ready (13.20). */
    public static final int MAX_REALTIME_BURST = 8;
    /** Selections a ready CONTROL head may wait before it is taken regardless of weight (13.20). */
    public static final int AGE_LIMIT = 32;
    /**
     * Selections that may pass over a ready, writable CHUNK before it is taken. Each CHUNK keeps its
     * own count. With one starved shard, a ready, writable chunk is selected by the ninth selection
     * after it was last picked, whatever REALTIME, CONTROL or promoted load runs. With several shards
     * starved at once the bound is {@code MAX_CHUNK_SKIPS + N - 1} selections, where N is the number
     * of CHUNK shards (13.20).
     */
    public static final int MAX_CHUNK_SKIPS = 8;

    private static final int CONTROL = LogicalStream.CONTROL;
    private static final int REALTIME = LogicalStream.REALTIME;
    private static final int FIRST_CHUNK = 2;
    private static final int CLASS_REALTIME = 0;
    private static final int CLASS_CHUNK = 1;
    private static final int CLASS_CONTROL = 2;

    /** The ACK stream (13.21), scheduled in the CONTROL class; -1 when the session has none. */
    private final int ackStream;
    private boolean ackServedLast;
    private final int realtimeWeight;
    private final int chunkWeight;
    private final int controlWeight;
    private final int queuePacketLimit;
    private final long queueByteLimit;
    private final TreeMap<Integer, ArrayDeque<Item>> queues = new TreeMap<Integer, ArrayDeque<Item>>();
    private final TreeMap<Integer, Long> offered = new TreeMap<Integer, Long>();
    private final TreeMap<Integer, Long> sent = new TreeMap<Integer, Long>();
    private final long[] classVirtualTime = new long[3];
    private long virtualTime;
    private long selections;
    private int realtimeRun;
    /** Per CHUNK stream: selections passed over while ready and writable, reset when picked (13.20). */
    private final TreeMap<Integer, Integer> chunkSkips = new TreeMap<Integer, Integer>();
    private int lastChunk = 1;
    private boolean bundleOpen;
    private int queuedRecords;
    private long queuedBytes;

    /** One write slice of a selected record and its accounting (13.20). */
    public static final class Selection {
        private final Record record;
        private final int bytes;
        private final int offset;
        private final int length;
        private final boolean last;

        private Selection(Record record, int bytes, int offset, int length, boolean last) {
            this.record = record;
            this.bytes = bytes;
            this.offset = offset;
            this.length = length;
            this.last = last;
        }

        /** The selected record, whose stream order is unchanged. */
        public Record record() {
            return record;
        }

        /** The record's full encoded byte count. */
        public int bytes() {
            return bytes;
        }

        /** Byte offset of this slice within the record. */
        public int offset() {
            return offset;
        }

        /** Bytes of this slice, at most {@link #QUANTUM_BYTES}. */
        public int length() {
            return length;
        }

        /** Whether this slice completes the record. */
        public boolean last() {
            return last;
        }

        /** The byte count charged to this transport write, equal to {@link #length()}. */
        public int quantumBytes() {
            return length;
        }
    }

    /**
     * Reads weights and queue limits from the connection's immutable profile (13.20).
     *
     * @throws IllegalArgumentException if the profile is null
     */
    public SendScheduler(ServerProfile profile) {
        this(profile, -1);
    }

    /**
     * As {@link #SendScheduler(ServerProfile)} for a session with an ACK stream (13.21). The ACK stream takes
     * part in the CONTROL class, taking turns with stream 0 when both are ready; it is never a CHUNK stream and
     * never REALTIME.
     *
     * @param ackStream the ACK stream's logical index N+2, or -1 for none
     */
    public SendScheduler(ServerProfile profile, int ackStream) {
        if (profile == null) {
            throw new IllegalArgumentException("profile");
        }
        this.realtimeWeight = positive(profile.realtimeWeight);
        this.chunkWeight = positive(profile.chunkWeight);
        this.controlWeight = positive(profile.controlWeight);
        this.queuePacketLimit = profile.queuePacketLimit;
        this.queueByteLimit = profile.queueByteLimit;
        this.ackStream = ackStream;
    }

    private boolean isChunk(int stream) {
        return stream >= FIRST_CHUNK && stream != ackStream;
    }

    /**
     * Enqueues one sequenced record and its encoded byte count (13.20). Records of each stream must
     * be offered in sequence order without gaps, which the sequencer guarantees.
     *
     * @param bundleContinues true for every bundle record except the closing one (13.15)
     * @throws IllegalArgumentException for an invalid byte count, an out-of-order offer, or a bundle
     *         flag on a non-CONTROL record
     */
    public synchronized void offer(Record record, int bytes, boolean bundleContinues) {
        if (record == null || bytes < 0 || bytes > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("item");
        }
        int stream = record.streamIndex();
        if (bundleContinues && stream != CONTROL) {
            throw new IllegalArgumentException("bundle stream");
        }
        long previous = offeredOf(stream);
        if (record.sequence() != previous + 1) {
            throw new IllegalArgumentException("out-of-order offer");
        }
        offered.put(stream, record.sequence());
        ArrayDeque<Item> queue = queues.computeIfAbsent(stream, key -> new ArrayDeque<Item>());
        Item item = new Item(record, bytes, bundleContinues, selections);
        if (queue.isEmpty()) {
            item.headSince = selections;
        }
        queue.addLast(item);
        queuedRecords++;
        queuedBytes += bytes;
    }

    /** Enqueues a record that is not part of a bundle (13.20). */
    public synchronized void offer(Record record, int bytes) {
        offer(record, bytes, false);
    }

    /**
     * Selects the next write slice, or returns null when nothing is ready and writable.
     *
     * @param writable reports whether a logical stream can accept bytes now, including connection
     *                 level writability; unwritable streams are never selected (13.20)
     */
    public synchronized Selection pollSelection(IntPredicate writable) {
        if (writable == null) {
            throw new IllegalArgumentException("writable");
        }
        if (bundleOpen) {
            Item next = front(CONTROL);
            if (next == null || !usable(next, writable)) {
                return null;
            }
            return select(next);
        }
        Item starved = starvedChunk(writable);
        if (starved != null) {
            return pick(starved, writable);
        }
        Item aged = agedControl(writable);
        if (aged != null) {
            return pick(aged, writable);
        }
        Item chosen = promotedHead(writable);
        if (chosen == null) {
            chosen = choose(writable, chunkCandidate(writable));
        }
        return chosen == null ? null : pick(chosen, writable);
    }

    /**
     * Charges one skip to every other ready, writable CHUNK before {@code item} is selected, then
     * selects {@code item}. Skips are counted per shard, so a promoted pick of one shard cannot reset
     * the count of another (13.20).
     */
    private Selection pick(Item item, IntPredicate writable) {
        int picked = item.record.streamIndex();
        for (Map.Entry<Integer, ArrayDeque<Item>> entry : queues.tailMap(FIRST_CHUNK, true).entrySet()) {
            int stream = entry.getKey();
            Item head = entry.getValue().peekFirst();
            if (isChunk(stream) && stream != picked && head != null && usable(head, writable)) {
                chunkSkips.put(stream, skipsOf(stream) + 1);
            }
        }
        return select(item);
    }

    /**
     * The ready, writable CHUNK that has been passed over {@link #MAX_CHUNK_SKIPS} times and has
     * been passed over the most, or null. Equal counts go to the shard next in round-robin order
     * (13.20).
     */
    private Item starvedChunk(IntPredicate writable) {
        Item best = null;
        int bestSkips = MAX_CHUNK_SKIPS - 1;
        for (int stream : chunkStreamsInRoundRobinOrder()) {
            int skips = skipsOf(stream);
            Item head = front(stream);
            if (skips > bestSkips && head != null && usable(head, writable)) {
                best = head;
                bestSkips = skips;
            }
        }
        return best;
    }

    /**
     * Earliest ready prerequisite chain of a blocked urgent head, or null. A promoted pick is charged
     * like any other pick in {@link #select}, and it cannot pass a ready CHUNK forever: the
     * {@link #MAX_CHUNK_SKIPS} rule in {@link #pollSelection} applies first (13.20).
     */
    private Item promotedHead(IntPredicate writable) {
        for (Map.Entry<Integer, ArrayDeque<Item>> entry : queues.entrySet()) {
            Item head = entry.getValue().peekFirst();
            if (head == null || !isUrgent(head) || ready(head)) {
                continue;
            }
            Item promoted = promote(head, new HashSet<Long>(), writable);
            if (promoted != null) {
                return promoted;
            }
        }
        return null;
    }

    /** Records queued and not yet fully selected (13.20). */
    public synchronized int size() {
        return queuedRecords;
    }

    /** Encoded bytes queued and not yet selected, for outbound pool accounting (13.20). */
    public synchronized long queuedBytes() {
        return queuedBytes;
    }

    /** Whether the profile's queue limits are reached, so the producer should back off. */
    public synchronized boolean queueFull() {
        return queuedRecords >= queuePacketLimit || queuedBytes >= queueByteLimit;
    }

    /**
     * The ready, writable CONTROL head that has waited {@link #AGE_LIMIT} selections since it became
     * the head, or null. It is checked before prerequisite promotion, so a sustained promoted load
     * cannot pass it (13.20).
     */
    private Item agedControl(IntPredicate writable) {
        Item control = usableFront(CONTROL, writable);
        if (control != null && selections - control.headSince >= AGE_LIMIT) {
            return control;
        }
        Item ack = ackStream < 0 ? null : usableFront(ackStream, writable);
        return ack != null && selections - ack.headSince >= AGE_LIMIT ? ack : null;
    }

    /** The CONTROL-class candidate: stream 0 or the ACK stream, alternating when both are ready (13.21). */
    private Item controlClassFront(IntPredicate writable) {
        Item control = usableFront(CONTROL, writable);
        Item ack = ackStream < 0 ? null : usableFront(ackStream, writable);
        if (control == null || ack == null) {
            return control != null ? control : ack;
        }
        return ackServedLast ? control : ack;
    }

    private Item choose(IntPredicate writable, Item chunk) {
        Item control = controlClassFront(writable);
        Item realtime = usableFront(REALTIME, writable);
        boolean others = chunk != null || control != null;
        List<Item> candidates = new ArrayList<Item>(3);
        List<Integer> classes = new ArrayList<Integer>(3);
        // Order is the tie-break: REALTIME, then CHUNK, then CONTROL (13.20).
        if (realtime != null && (!others || realtimeRun < MAX_REALTIME_BURST)) {
            candidates.add(realtime);
            classes.add(CLASS_REALTIME);
        }
        if (chunk != null) {
            candidates.add(chunk);
            classes.add(CLASS_CHUNK);
        }
        if (control != null) {
            candidates.add(control);
            classes.add(CLASS_CONTROL);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int best = 0;
        for (int i = 1; i < candidates.size(); i++) {
            if (effectiveTime(classes.get(i)) < effectiveTime(classes.get(best))) {
                best = i;
            }
        }
        return candidates.get(best);
    }

    /** Next ready CHUNK[i] after the previously served shard, cyclically (13.20). */
    private Item chunkCandidate(IntPredicate writable) {
        for (int stream : chunkStreamsInRoundRobinOrder()) {
            Item head = front(stream);
            if (head != null && usable(head, writable)) {
                return head;
            }
        }
        return null;
    }

    /** Chunk streams after the last served shard, then the rest, each in ascending order. */
    private List<Integer> chunkStreamsInRoundRobinOrder() {
        List<Integer> order = new ArrayList<Integer>();
        for (Integer stream : queues.tailMap(lastChunk, false).keySet()) {
            if (isChunk(stream)) {
                order.add(stream);
            }
        }
        for (Integer stream : queues.headMap(lastChunk, true).keySet()) {
            if (isChunk(stream)) {
                order.add(stream);
            }
        }
        return order;
    }

    /**
     * Earliest ready, writable prerequisite chain that unblocks {@code item}, or null. Depth-first
     * through unsent explicit dependencies; each visited head is examined once.
     */
    private Item promote(Item item, Set<Long> seen, IntPredicate writable) {
        for (Record.Dependency dependency : item.record.dependencies()) {
            int stream = dependency.streamIndex();
            if (sentOf(stream) >= dependency.sequence()) {
                continue;
            }
            Item head = front(stream);
            if (head == null || !seen.add(key(head))) {
                continue;
            }
            if (usable(head, writable)) {
                return head;
            }
            Item deeper = promote(head, seen, writable);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    /** Selects the next slice of the head record on the item's stream. */
    private Selection select(Item item) {
        int stream = item.record.streamIndex();
        int offset = item.offset;
        int length = Math.min(item.bytes - offset, QUANTUM_BYTES);
        boolean last = offset + length == item.bytes;
        item.offset = offset + length;
        queuedBytes -= length;
        if (last) {
            ArrayDeque<Item> queue = queues.get(stream);
            queue.pollFirst();
            queuedRecords--;
            sent.put(stream, item.record.sequence());
            if (!queue.isEmpty()) {
                // The next record becomes the head now; its age starts at this selection (13.20).
                queue.peekFirst().headSince = selections + 1;
            }
        }
        int cls = classOf(stream);
        if (cls == CLASS_CONTROL) {
            ackServedLast = stream == ackStream;
        }
        long base = Math.max(classVirtualTime[cls], virtualTime);
        virtualTime = base;
        classVirtualTime[cls] = base + (long) Math.max(1, length) * 1024L / weightOf(cls);
        realtimeRun = cls == CLASS_REALTIME ? realtimeRun + 1 : 0;
        if (cls == CLASS_CHUNK) {
            lastChunk = stream;
            chunkSkips.remove(stream);
        }
        if (stream == CONTROL) {
            if (offset == 0 && item.bundleContinues) {
                bundleOpen = true;
            } else if (last) {
                bundleOpen = item.bundleContinues;
            }
        }
        selections++;
        return new Selection(item.record, item.bytes, offset, length, last);
    }

    private long effectiveTime(int cls) {
        return Math.max(classVirtualTime[cls], virtualTime);
    }

    private Item usableFront(int stream, IntPredicate writable) {
        Item head = front(stream);
        return head != null && usable(head, writable) ? head : null;
    }

    private boolean usable(Item item, IntPredicate writable) {
        return ready(item) && writable.test(item.record.streamIndex());
    }

    private boolean ready(Item item) {
        for (Record.Dependency dependency : item.record.dependencies()) {
            if (sentOf(dependency.streamIndex()) < dependency.sequence()) {
                return false;
            }
        }
        return true;
    }

    private boolean isUrgent(Item head) {
        return head.record.streamIndex() == REALTIME || head.record.kind() != 0;
    }

    private Item front(int stream) {
        ArrayDeque<Item> queue = queues.get(stream);
        return queue == null ? null : queue.peekFirst();
    }

    private long sentOf(int stream) {
        Long value = sent.get(stream);
        return value == null ? 0 : value;
    }

    private int skipsOf(int stream) {
        Integer value = chunkSkips.get(stream);
        return value == null ? 0 : value;
    }

    private long offeredOf(int stream) {
        Long value = offered.get(stream);
        return value == null ? 0 : value;
    }

    private static long key(Item head) {
        return (long) head.record.streamIndex() * 4_294_967_296L + head.record.sequence();
    }

    private int classOf(int stream) {
        if (stream == CONTROL || stream == ackStream) {
            return CLASS_CONTROL;
        }
        return stream == REALTIME ? CLASS_REALTIME : CLASS_CHUNK;
    }

    private long weightOf(int cls) {
        if (cls == CLASS_CONTROL) {
            return controlWeight;
        }
        return cls == CLASS_REALTIME ? realtimeWeight : chunkWeight;
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }

    private static final class Item {
        final Record record;
        final int bytes;
        final boolean bundleContinues;
        /** Selection count when this record became its stream's head (13.20 aging). */
        long headSince;
        int offset;

        Item(Record record, int bytes, boolean bundleContinues, long headSince) {
            this.record = record;
            this.bytes = bytes;
            this.bundleContinues = bundleContinues;
            this.headSince = headSince;
        }
    }
}
