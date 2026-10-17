// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;
import rs.sudoe.quicraft.core.routing.Lane;

/**
 * The /3 compact sender (docs/protocol.md 13.4, 13.9, 13.15, 13.19, 13.20). It binds the sender
 * building blocks into one object per connection direction: a {@link StreamSequencer} assigns
 * every record at the ordered submission point, {@link RecordCodec} encodes it, a
 * {@link SendScheduler} chooses slices, {@link AckJoinTracker} and {@link BatchJoinTracker} hold
 * joins, and a separate {@link OutboundPool} charges the bytes until QUIC releases them.
 *
 * <p>Ordering: every {@code submit*} call is made in the game's logical send order and assigns its
 * stream position at that call. Pending ACK_JOINs live outside the sequenced FIFOs; they take a
 * sequence only when {@link #takeAck} or {@link #expireAcks} resolves them.
 *
 * <p>Charges: a record's bytes are reserved when it is sequenced. A record is offered to the
 * scheduler only once the pool has granted its reservation (granted in reservation order), and
 * its charge is released only when the transport reports the last slice released through
 * {@link #released(long)} or {@link #failed(long)}. Each charge is released exactly once. Closing
 * the driver releases every outstanding charge and drops every queued reservation.
 *
 * <p>Producer overflow closes with 0x12 (resource). A bundle that breaks a wire limit closes
 * locally with 0x11 and is never split. The clock is a monotonic nanosecond source.
 *
 * <p>Not thread-safe beyond its own monitor; the caller is the connection's single send path.
 */
public final class SessionSendDriver {
    /** Close code for a local ordering or wire-limit violation (13.12). */
    public static final int CLOSE_ORDERING = MultistreamProtocolException.CLOSE_ORDERING;
    /** Close code for producer overflow or a local resource close (13.12). */
    public static final int CLOSE_RESOURCE = Protocol.CLOSE_RESOURCE;

    private static final int CONTROL = LogicalStream.CONTROL;
    private static final int REALTIME = LogicalStream.REALTIME;
    private static final int FIRST_CHUNK = 2;

    /** One slice to write now. Report its completion with {@link #released(long)} or {@link #failed(long)}. */
    public static final class Write {
        private final long token;
        private final int stream;
        private final byte[] bytes;
        private final boolean last;

        Write(long token, int stream, byte[] bytes, boolean last) {
            this.token = token;
            this.stream = stream;
            this.bytes = bytes;
            this.last = last;
        }

        /** Token to report when QUIC releases this slice. */
        public long token() {
            return token;
        }

        /** Logical stream index this slice belongs to. */
        public int stream() {
            return stream;
        }

        /** A copy of the slice bytes, at most {@link SendScheduler#QUANTUM_BYTES}. */
        public byte[] bytes() {
            return bytes.clone();
        }

        /** Whether this slice completes its record. */
        public boolean last() {
            return last;
        }
    }

    /** One outbound charge: a sequenced record, or a pending ACK_JOIN that will become one. */
    private static final class Charge {
        final long bytes;
        final long end;
        Record record;
        byte[] encoded;
        boolean continues;
        long lastToken = -1;
        boolean released;

        Charge(long bytes, long end) {
            this.bytes = bytes;
            this.end = end;
        }
    }

    /** A pending ACK_JOIN: its payload and its charge, which transfers to the record when sequenced. */
    private static final class PendingAck {
        final byte[] payload;
        final Charge charge;
        final long createdNanos;

        PendingAck(byte[] payload, Charge charge, long createdNanos) {
            this.payload = payload;
            this.charge = charge;
            this.createdNanos = createdNanos;
        }
    }

    private final SessionContext context;
    private final ServerProfile profile;
    private final OutboundPool pool;
    private final PressureRanking ranking;
    private final Object owner;
    private final Object ip;
    private final Object account;
    private final LongSupplier clock;
    private final StreamSequencer sequencer;
    private final SendScheduler scheduler;
    private final AckJoinTracker acks = new AckJoinTracker();
    private final BatchJoinTracker batches = new BatchJoinTracker();
    private final Map<Long, PendingAck> pendingAcks = new HashMap<Long, PendingAck>();
    private final ArrayDeque<Charge> toOffer = new ArrayDeque<Charge>();
    private final ArrayDeque<Charge> orphans = new ArrayDeque<Charge>();
    private final IdentityHashMap<Record, Charge> byRecord = new IdentityHashMap<Record, Charge>();
    private final Map<Long, Charge> tokens = new HashMap<Long, Charge>();
    private long reservedTotal;
    private long releasedTotal;
    private long lastEnd;
    private boolean refused;
    private long nextToken = 1;
    private int queuedRecords;
    private long queuedBytes;
    private boolean closed;
    private int closeCode;

    /**
     * Creates the sender for one connection direction and registers the connection with the ranking.
     *
     * @param pool the outbound pool, separate from the inbound pool
     * @param ranking the outbound pressure ranking, separate from the inbound ranking
     * @param owner the charge owner for this connection in both pool and ranking
     * @param ip the connection's source address for the per-IP cap
     * @param account the connection's account, or null
     * @param clock a monotonic nanosecond clock
     */
    public SessionSendDriver(SessionContext context, ServerProfile profile, OutboundPool pool,
            PressureRanking ranking, Object owner, InetAddress ip, String account, LongSupplier clock) {
        if (context == null || profile == null || pool == null || ranking == null || owner == null
                || clock == null) {
            throw new IllegalArgumentException("send driver");
        }
        this.context = context;
        this.profile = profile;
        this.pool = pool;
        this.ranking = ranking;
        this.owner = owner;
        this.ip = ip;
        this.account = account;
        this.clock = clock;
        this.sequencer = new StreamSequencer(context.shardCount());
        this.scheduler = new SendScheduler(profile);
        ranking.connect(owner, ip, account, clock.getAsLong());
    }

    /** The negotiated session shape. */
    public SessionContext context() {
        return context;
    }

    /** Whether the driver has closed; a closed driver assigns nothing and writes nothing. */
    public synchronized boolean isClosed() {
        return closed;
    }

    /** The close code that ended the driver (0x11 or 0x12), or 0 while open or after a plain close. */
    public synchronized int closeCode() {
        return closeCode;
    }

    /** Records sequenced but not yet fully written. */
    public synchronized int queuedRecords() {
        return queuedRecords;
    }

    /** Assigns an ordinary GAME record and returns its handle. Chunk shards are hashed with the current epoch. */
    public synchronized SentPacket submit(OutboundPacket packet, StreamSequencer.Fence fence, int chunkX, int chunkZ,
            Record.Dependency... semantic) {
        SentPacket handle = new SentPacket();
        if (closed) {
            return handle;
        }
        byte[] frame = packet.frame();
        if (overflows(1, frame.length)) {
            closeWith(CLOSE_RESOURCE);
            return handle;
        }
        int stream = streamFor(fence, chunkX, chunkZ);
        Record record = sequencer.assign(stream, fence, frame, false, semantic);
        handle.assign(laneOf(stream), stream, record.sequence());
        enqueueRecord(record, false);
        return handle;
    }

    /** Assigns an ordinary GAME record whose predecessors are existing handles. */
    public synchronized SentPacket submitAfter(OutboundPacket packet, StreamSequencer.Fence fence, int chunkX,
            int chunkZ, SentPacket... after) {
        return submit(packet, fence, chunkX, chunkZ, dependenciesOf(after));
    }

    /**
     * Assigns a level-creating epoch transition (13.18). Pending ACK_JOINs settle first, in the old
     * epoch, and any active batch is discarded.
     */
    public synchronized SentPacket submitEpochTransition(OutboundPacket packet) {
        SentPacket handle = new SentPacket();
        if (closed) {
            return handle;
        }
        if (overflows(1, packet.frame().length)) {
            closeWith(CLOSE_RESOURCE);
            return handle;
        }
        settleAcksLocked();
        batches.abort();
        Record record = sequencer.assignEpochTransition(packet.frame());
        handle.assign(Lane.CONTROL, CONTROL, record.sequence());
        enqueueRecord(record, false);
        return handle;
    }

    /**
     * Registers a pending ACK_JOIN (13.19). Its charge is taken now and carried over to the record when
     * the ack is sequenced. Returns false, storing nothing, when the tracker's entry or byte limit would
     * be exceeded; the caller then pauses its producer.
     */
    public synchronized boolean createAck(long id, byte[] payload, int chargedBytes) {
        if (closed || payload == null) {
            return false;
        }
        long now = clock.getAsLong();
        if (!acks.create(id, chargedBytes, now)) {
            return false;
        }
        pendingAcks.put(id, new PendingAck(payload, reserve(chargedBytes), now));
        return true;
    }

    /** Records an already assigned covered update in a pending ACK_JOIN (13.19). */
    public synchronized void coverAssigned(long ackId, SentPacket covered) {
        acks.coverAssigned(ackId, covered.logicalStream(), covered.sequence());
    }

    /** Adds a covered update that is not assigned yet (13.19). */
    public synchronized boolean coverPending(long ackId, long updateToken) {
        return acks.coverPending(ackId, updateToken);
    }

    /** Resolves a pending covered update with its assigned handle (13.19). */
    public synchronized void assignedPending(long updateToken, SentPacket covered) {
        acks.assignedPending(updateToken, covered.logicalStream(), covered.sequence());
    }

    /** Removes a suppressed covered update from coverage (13.19). */
    public synchronized void suppressPending(long updateToken) {
        acks.suppressPending(updateToken);
    }

    /** Marks an ACK_JOIN as lacking exact coverage, so it uses the safe superset (13.19). */
    public synchronized void markInexact(long ackId) {
        acks.markInexact(ackId);
    }

    /**
     * Sequences a pending ACK_JOIN if its coverage is complete (13.19). Returns null while it is still
     * unresolved, or after it was dropped at its deadline.
     */
    public synchronized SentPacket takeAck(long ackId) {
        PendingAck pending = pendingAcks.get(ackId);
        if (pending == null || closed) {
            return null;
        }
        long now = clock.getAsLong();
        AckJoinTracker.Ready ready = acks.take(ackId, now, sequencer.assigned()[CONTROL],
                sequencer.safeSupersetDependencies());
        if (ready == null) {
            if (expiredAt(pending, now)) {
                dropAck(ackId, pending);
            }
            return null;
        }
        return sequenceAck(ackId, pending, ready);
    }

    /** Settles every pending ACK_JOIN at its deadline (13.19). Complete ones are sequenced; others are dropped. */
    public synchronized void expireAcks() {
        if (closed) {
            return;
        }
        long now = clock.getAsLong();
        List<AckJoinTracker.Ready> ready = acks.expire(now, sequencer.assigned()[CONTROL],
                sequencer.safeSupersetDependencies());
        for (AckJoinTracker.Ready item : ready) {
            sequenceAckById(item);
        }
        for (Map.Entry<Long, PendingAck> entry : new ArrayList<Map.Entry<Long, PendingAck>>(pendingAcks.entrySet())) {
            if (expiredAt(entry.getValue(), now)) {
                dropAck(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Starts a batch: its CONTROL/view-gate start waits for the previous BATCH_JOIN (13.19). */
    public synchronized SentPacket beginBatch(long batchId, OutboundPacket start) {
        Record.Dependency previous = batches.previousJoinDependency();
        SentPacket handle = submit(start, StreamSequencer.Fence.VIEW, 0, 0,
                previous == null ? new Record.Dependency[0] : new Record.Dependency[] {previous});
        if (handle.isAssigned() && !closed) {
            batches.begin(batchId, handle.sequence(), sequencer.epoch());
        }
        return handle;
    }

    /** Assigns one install of the active batch on its hashed CHUNK stream (13.19). */
    public synchronized SentPacket batchInstall(long batchId, OutboundPacket install, int chunkX, int chunkZ) {
        SentPacket handle = submit(install, StreamSequencer.Fence.CHUNK, chunkX, chunkZ, new Record.Dependency[0]);
        if (handle.isAssigned() && !closed) {
            batches.chunk(batchId, handle.logicalStream(), handle.sequence(), sequencer.epoch());
        }
        return handle;
    }

    /** Ends the active batch with its BATCH_JOIN on CHUNK[0] (13.19). */
    public synchronized SentPacket finishBatch(long batchId, OutboundPacket join) {
        SentPacket handle = new SentPacket();
        if (closed) {
            return handle;
        }
        Record.Dependency[] deps = batches.finish(batchId, sequencer.epoch());
        if (overflows(1, join.frame().length)) {
            closeWith(CLOSE_RESOURCE);
            return handle;
        }
        Record record = sequencer.assignJoin(3, join.frame(), deps);
        batches.recordJoin(record.sequence());
        handle.assign(Lane.WORLD, FIRST_CHUNK, record.sequence());
        enqueueRecord(record, false);
        return handle;
    }

    /**
     * Assigns one bundle as one CONTROL run (13.15): the opening delimiter as a kind 4 record, every
     * member, then the closing delimiter. A bundle over 126 members or over B closes 0x11 locally and
     * assigns nothing.
     *
     * @return the handles of the unit in stream order, or an empty list when nothing was assigned
     */
    public synchronized List<SentPacket> submitBundle(OutboundPacket opening, List<OutboundPacket> members,
            StreamSequencer.Fence[] scopes, OutboundPacket closing, Record.Dependency... external) {
        List<SentPacket> result = new ArrayList<SentPacket>();
        if (closed) {
            return result;
        }
        int units = members.size() + 2;
        long bytes = opening.frame().length + closing.frame().length;
        List<byte[]> frames = new ArrayList<byte[]>(members.size());
        for (OutboundPacket member : members) {
            frames.add(member.frame());
            bytes += member.frame().length;
        }
        if (overflows(units, bytes)) {
            closeWith(CLOSE_RESOURCE);
            return result;
        }
        List<Record> records;
        try {
            records = sequencer.assignBundle(opening.frame(), frames, scopes, closing.frame(), external);
        } catch (IllegalArgumentException refused) {
            closeWith(CLOSE_ORDERING);
            return result;
        }
        for (int i = 0; i < records.size(); i++) {
            Record record = records.get(i);
            result.add(handleOf(record));
            enqueueRecord(record, i < records.size() - 1);
        }
        return result;
    }

    /**
     * Selects the next slice to write, or returns null when nothing is granted, ready and writable.
     * The caller writes the slice, then reports its outcome with {@link #released(long)} or
     * {@link #failed(long)}.
     *
     * @param writable reports whether a logical stream can accept bytes now, connection level included
     */
    public synchronized Write poll(IntPredicate writable) {
        if (closed || writable == null) {
            return null;
        }
        if (checkShed()) {
            return null;
        }
        drainGrants();
        SendScheduler.Selection selection = scheduler.pollSelection(writable);
        if (selection == null) {
            return null;
        }
        Charge charge = byRecord.get(selection.record());
        if (charge == null) {
            throw new IllegalStateException("selected record has no charge");
        }
        byte[] slice = Arrays.copyOfRange(charge.encoded, selection.offset(),
                selection.offset() + selection.length());
        long token = nextToken++;
        tokens.put(token, charge);
        if (selection.last()) {
            charge.lastToken = token;
            byRecord.remove(selection.record());
            queuedRecords--;
            queuedBytes -= charge.encoded.length;
        }
        return new Write(token, selection.record().streamIndex(), slice, selection.last());
    }

    /** The transport released the send buffer of this slice; its record's charge is released once (13.12). */
    public synchronized void released(long token) {
        settle(token);
    }

    /** The write of this slice failed; the charge is released once, as for a release (13.12). */
    public synchronized void failed(long token) {
        settle(token);
    }

    /**
     * Closes the driver: every outstanding charge is released exactly once, queued reservations are
     * dropped, pending ACK_JOINs are discarded and the connection leaves the ranking. Later calls do
     * nothing.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        pool.cancel(owner);
        for (Charge charge : tokens.values()) {
            charge.released = true;
        }
        for (Charge charge : byRecord.values()) {
            charge.released = true;
        }
        tokens.clear();
        byRecord.clear();
        toOffer.clear();
        orphans.clear();
        pendingAcks.clear();
        ranking.disconnect(owner, clock.getAsLong());
    }

    private void closeWith(int code) {
        closeCode = code;
        close();
    }

    private void settle(long token) {
        Charge charge = tokens.remove(token);
        if (charge == null || charge.released || token != charge.lastToken) {
            return;
        }
        charge.released = true;
        pool.release(owner, charge.bytes);
        releasedTotal += charge.bytes;
        long now = clock.getAsLong();
        ranking.progress(owner, charge.bytes, now);
        ranking.setRetained(owner, pool.reserved(owner), now);
        drainGrants();
    }

    private void enqueueRecord(Record record, boolean continues) {
        byte[] encoded = RecordCodec.encodeRecord(record);
        Charge charge = reserve(encoded.length);
        charge.record = record;
        charge.encoded = encoded;
        charge.continues = continues;
        byRecord.put(record, charge);
        toOffer.addLast(charge);
        queuedRecords++;
        queuedBytes += encoded.length;
    }

    private Charge reserve(long bytes) {
        reservedTotal += bytes;
        Charge charge = new Charge(bytes, reservedTotal);
        lastEnd = reservedTotal;
        boolean granted = pool.reserve(owner, ip, account, bytes);
        if (!granted && !refused) {
            refused = true;
            ranking.beginRefusedWait(owner, clock.getAsLong());
        }
        return charge;
    }

    /** Moves granted reservations forward: releases orphans, offers sequenced records in order. */
    private void drainGrants() {
        long granted = releasedTotal + pool.reserved(owner);
        while (!orphans.isEmpty() && orphans.peekFirst().end <= granted) {
            Charge orphan = orphans.pollFirst();
            orphan.released = true;
            pool.release(owner, orphan.bytes);
            releasedTotal += orphan.bytes;
        }
        while (!toOffer.isEmpty() && toOffer.peekFirst().end <= granted) {
            Charge charge = toOffer.pollFirst();
            scheduler.offer(charge.record, charge.encoded.length, charge.continues);
        }
        if (refused && granted >= lastEnd) {
            refused = false;
            ranking.endRefusedWait(owner);
        }
        ranking.setRetained(owner, pool.reserved(owner), clock.getAsLong());
    }

    private boolean checkShed() {
        if (pool.drainShedOwners().contains(owner)) {
            closeWith(CLOSE_RESOURCE);
            return true;
        }
        return false;
    }

    private boolean overflows(int records, long bytes) {
        return queuedRecords + records > profile.queuePacketLimit || queuedBytes + bytes > profile.queueByteLimit;
    }

    private void settleAcksLocked() {
        List<AckJoinTracker.Ready> ready = acks.settleForTransition(sequencer.assigned()[CONTROL],
                sequencer.safeSupersetDependencies());
        for (AckJoinTracker.Ready item : ready) {
            sequenceAckById(item);
        }
        for (Map.Entry<Long, PendingAck> entry : new ArrayList<Map.Entry<Long, PendingAck>>(pendingAcks.entrySet())) {
            dropAck(entry.getKey(), entry.getValue());
        }
    }

    private void sequenceAckById(AckJoinTracker.Ready ready) {
        PendingAck pending = pendingAcks.get(ready.id());
        if (pending != null) {
            sequenceAck(ready.id(), pending, ready);
        }
    }

    private SentPacket sequenceAck(long ackId, PendingAck pending, AckJoinTracker.Ready ready) {
        pendingAcks.remove(ackId);
        Record record = sequencer.assignJoin(2, pending.payload, ready.dependencies());
        SentPacket handle = handleOf(record);
        byte[] encoded = RecordCodec.encodeRecord(record);
        // The ack's charge transfers to the record: no second reservation (13.19).
        Charge charge = pending.charge;
        charge.record = record;
        charge.encoded = encoded;
        charge.continues = false;
        byRecord.put(record, charge);
        toOffer.addLast(charge);
        queuedRecords++;
        queuedBytes += encoded.length;
        return handle;
    }

    /** Drops a pending ACK_JOIN the tracker already removed; its charge is released once the pool grants it. */
    private void dropAck(long ackId, PendingAck pending) {
        pendingAcks.remove(ackId);
        orphans.addLast(pending.charge);
    }

    private boolean expiredAt(PendingAck pending, long now) {
        return now - pending.createdNanos >= AckJoinTracker.DEADLINE_NANOS;
    }

    private int streamFor(StreamSequencer.Fence fence, int chunkX, int chunkZ) {
        switch (fence) {
            case REALTIME:
                return REALTIME;
            case CHUNK:
                return FIRST_CHUNK + ShardHash.shardOf(chunkX, chunkZ, sequencer.epoch(), context.shardCount());
            default:
                return CONTROL;
        }
    }

    private static Lane laneOf(int stream) {
        if (stream == CONTROL) {
            return Lane.CONTROL;
        }
        return stream == REALTIME ? Lane.REALTIME : Lane.WORLD;
    }

    private SentPacket handleOf(Record record) {
        SentPacket handle = new SentPacket();
        handle.assign(laneOf(record.streamIndex()), record.streamIndex(), record.sequence());
        return handle;
    }

    private static Record.Dependency[] dependenciesOf(SentPacket... after) {
        List<Record.Dependency> result = new ArrayList<Record.Dependency>();
        for (SentPacket handle : after) {
            if (handle != null && handle.isAssigned()) {
                result.add(new Record.Dependency(handle.logicalStream(), handle.sequence()));
            }
        }
        return result.toArray(new Record.Dependency[result.size()]);
    }
}
