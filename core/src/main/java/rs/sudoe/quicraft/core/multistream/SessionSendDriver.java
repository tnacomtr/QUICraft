// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
 * <p>Charges and grant order (invariant): a record's bytes are reserved when it is sequenced. Pool
 * tickets are requested strictly in assignment order across all streams. The driver walks its queue
 * of sequenced records in the order {@link StreamSequencer} assigned them, requests the next record
 * only after every earlier record was granted, and stops at the first record that is not granted.
 * So at most one ungranted ticket is outstanding for this owner, and a later record never takes bytes
 * ahead of an earlier one. Every prerequisite is assigned before its dependents, so by induction every
 * granted record's prerequisites are granted before it, and every granted record can be written and
 * released. A bundle run (13.15) is one charge of its total size, requested by its opener and offered
 * as a whole once granted, so the scheduler never holds an open bundle whose bytes are still ungranted,
 * and no earlier granted record waits behind it. A record is offered to the scheduler once granted.
 * The one exception is a pending ACK_JOIN,
 * whose charge is taken at creation, out of assignment order (13.19). Such charges are bounded by the
 * ACK headroom (see the package constructor), so they never keep a record from fitting once the
 * earlier granted records release. A record's charge is released once every slice it wrote has been
 * reported through {@link #released(long)} or {@link #failed(long)}, after the record has been fully
 * written, in any completion order. Each charge is released exactly once. Closing the driver releases
 * every outstanding charge and drops every queued reservation.
 *
 * <p>REALTIME slice (13.12). Each play connection charges a fixed slice of {@code S} bytes (default
 * {@link #DEFAULT_REALTIME_SLICE_BYTES}) from the pool once, as its first ticket, and holds it until close. A REALTIME
 * record whose wire size is at most S is granted from that slice locally, in assignment order, with at most one
 * waiting record, and it never touches the pool ledger. A larger REALTIME record takes the main queue at its global
 * position, and is then written on the REALTIME stream in REALTIME order. No record is offered before the slice
 * is granted (ACK charges may be granted earlier, within their headroom).
 *
 * <p>Deadlock argument. Each queue grants in assignment order. Each queue's budget holds the largest record it
 * accepts: the slice holds at least S, and the main budget holds {@code maxRecordBytes + ackByteLimit}. Every
 * prerequisite is assigned before its dependents. Take the globally earliest unreleased record X. Its prerequisites
 * are already released. The bytes its queue holds belong only to records earlier in that queue, which are released
 * too, so X fits, is granted, written and released. The slice is a per-connection playing reservation, charged once
 * and released exactly once at close.
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

    /** The REALTIME outbound slice per play connection, 2 KiB (13.12 provisional default). */
    public static final long DEFAULT_REALTIME_SLICE_BYTES = 2048;

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

    /**
     * Bound on a record's encoded size beyond its payload: header, length prefix and bundle varints (13.4).
     * Used only to estimate a pending ACK_JOIN's queue count before it is sequenced.
     */
    private static final int RECORD_OVERHEAD = RecordCodec.COMPACT_MAX_HEADER + 3 + 8;

    /** One outbound charge: a sequenced record, or a pending ACK_JOIN that will become one. */
    private static final class Charge {
        final long bytes;
        Record record;
        byte[] encoded;
        boolean continues;
        long ticket;
        boolean requested;
        boolean granted;
        boolean finished;
        int outstanding;
        boolean released;
        /** True for a pending ACK_JOIN's charge, which counts against the ACK headroom until released. */
        boolean ack;
        /**
         * The bundle run this record belongs to, or null when the charge is its own unit. Members after the first
         * point at the first member, which holds the pool charge for the whole run (13.15).
         */
        Charge head;
        /** On a run's head or a single charge: members not yet released. The pool charge is released at zero. */
        int unreleased = 1;
        /** Bytes reported to the ranking as progress when this record is released. */
        long progress;
        /** True for a REALTIME record granted from the REALTIME slice rather than the pool (13.12). */
        boolean local;
        /** True for a REALTIME record larger than the slice, which takes the main queue (13.12). */
        boolean viaMain;

        Charge(long bytes) {
            this.bytes = bytes;
            this.progress = bytes;
        }

        /** The charge that owns the pool bytes: the run's head, or this charge. */
        Charge group() {
            return head == null ? this : head;
        }
    }

    /**
     * A pending ACK_JOIN: its payload and its charge, which transfers to the record when sequenced.
     * Its queue count is {@code queuedBytes}, the estimate counted from creation until it is sequenced.
     */
    private static final class PendingAck {
        final byte[] payload;
        final Charge charge;
        final long createdNanos;
        final long queuedBytes;

        PendingAck(byte[] payload, Charge charge, long createdNanos, long queuedBytes) {
            this.payload = payload;
            this.charge = charge;
            this.createdNanos = createdNanos;
            this.queuedBytes = queuedBytes;
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
    /** Every REALTIME record in assignment order; the REALTIME stream is offered only in this order. */
    private final ArrayDeque<Charge> realtimeOrder = new ArrayDeque<Charge>();
    /** The per-connection REALTIME slice S: one pool charge taken first and held until close. */
    private final long realtimeSliceBytes;
    private final Charge slice;
    /** Bytes of local REALTIME records granted from the slice and not yet released. */
    private long sliceHeld;
    private final ArrayDeque<Charge> orphans = new ArrayDeque<Charge>();
    private final IdentityHashMap<Record, Charge> byRecord = new IdentityHashMap<Record, Charge>();
    private final Map<Long, Charge> tokens = new HashMap<Long, Charge>();
    /** The ACK headroom: bytes and entries of ACK charges created and not yet released (13.19). */
    private final long ackByteLimit;
    /** The largest record wire size, and so the largest bundle run, the connection can write (13.12). */
    private final long maxRecordBytes;
    private long ackBytesHeld;
    private int ackEntriesHeld;
    private int pendingGrants;
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
     * @throws IllegalArgumentException when the connection cap cannot hold the headroom (see the package constructor)
     */
    public SessionSendDriver(SessionContext context, ServerProfile profile, OutboundPool pool,
            PressureRanking ranking, Object owner, InetAddress ip, String account, LongSupplier clock) {
        this(context, profile, pool, ranking, owner, ip, account, clock, ReceiveSlots.BYTES_PER_STREAM,
                AckJoinTracker.MAX_BYTES);
    }

    /**
     * The sender with explicit ACK headroom. Production uses the wire maximum of one record and the 64 KiB ACK
     * bound; tests pass smaller values so that small connection caps can reproduce both deadlock traces.
     *
     * <p>Headroom (13.19): a pending ACK_JOIN is charged at creation, before the record it covers, and it keeps
     * its bytes until the transport releases it. The bytes of all such ACKs, pending or sequenced but unreleased,
     * are bounded by {@code ackByteLimit}, and {@link #createAck} backpressures at that limit. The smallest cap that
     * applies to the connection (owner, IP, account or node, see {@link OutboundPool#smallestCap}) must hold one
     * maximum record beside every ACK byte, so {@code maxRecordBytes + ackByteLimit <= smallest cap}. A bundle run
     * is at most {@link StreamSequencer#MAX_BUNDLE_ENCODED_BYTES}, which production sets as {@code maxRecordBytes}.
     * Then a record the ACKs wait on always fits beside them once earlier granted records release, and no ACK
     * can deadlock the connection. A cap below that sum throws.
     *
     * @param maxRecordBytes the largest record wire size the connection can write
     * @param ackByteLimit the most ACK bytes precharged at once, from creation until transport release
     */
    SessionSendDriver(SessionContext context, ServerProfile profile, OutboundPool pool,
            PressureRanking ranking, Object owner, InetAddress ip, String account, LongSupplier clock,
            long maxRecordBytes, long ackByteLimit) {
        this(context, profile, pool, ranking, owner, ip, account, clock, maxRecordBytes, ackByteLimit,
                DEFAULT_REALTIME_SLICE_BYTES);
    }

    /**
     * The sender with explicit headroom and REALTIME slice. The slice (13.12) is charged once, first, and the check
     * {@code maxRecordBytes + ackByteLimit + realtimeSliceBytes <= smallest cap} holds the slice beside one maximum
     * record and every ACK byte.
     *
     * @param realtimeSliceBytes the REALTIME slice S, at least the largest REALTIME record that the slice serves
     */
    SessionSendDriver(SessionContext context, ServerProfile profile, OutboundPool pool,
            PressureRanking ranking, Object owner, InetAddress ip, String account, LongSupplier clock,
            long maxRecordBytes, long ackByteLimit, long realtimeSliceBytes) {
        this(context, profile, pool, ranking, owner, ip, account, clock, maxRecordBytes, ackByteLimit,
                realtimeSliceBytes, false);
    }

    /**
     * The production sender for either direction. A serverbound sender (the client's) accepts the interaction
     * flag and refuses chunk routes and epoch transitions (13.9, 13.18); a clientbound one is the server's.
     */
    public SessionSendDriver(SessionContext context, ServerProfile profile, OutboundPool pool,
            PressureRanking ranking, Object owner, InetAddress ip, String account, LongSupplier clock,
            boolean serverbound) {
        this(context, profile, pool, ranking, owner, ip, account, clock, ReceiveSlots.BYTES_PER_STREAM,
                AckJoinTracker.MAX_BYTES, DEFAULT_REALTIME_SLICE_BYTES, serverbound);
    }

    SessionSendDriver(SessionContext context, ServerProfile profile, OutboundPool pool,
            PressureRanking ranking, Object owner, InetAddress ip, String account, LongSupplier clock,
            long maxRecordBytes, long ackByteLimit, long realtimeSliceBytes, boolean serverbound) {
        if (context == null || profile == null || pool == null || ranking == null || owner == null
                || clock == null) {
            throw new IllegalArgumentException("send driver");
        }
        // The headroom must fit under the smallest cap that applies to this connection: owner, IP, account or node.
        if (maxRecordBytes <= 0 || ackByteLimit <= 0 || realtimeSliceBytes <= 0
                || maxRecordBytes + ackByteLimit + realtimeSliceBytes > pool.smallestCap(account)) {
            throw new IllegalArgumentException("smallest cap cannot hold a maximum record beside pending ACK bytes");
        }
        this.realtimeSliceBytes = realtimeSliceBytes;
        this.slice = new Charge(realtimeSliceBytes);
        this.ackByteLimit = ackByteLimit;
        this.maxRecordBytes = maxRecordBytes;
        this.context = context;
        this.profile = profile;
        this.pool = pool;
        this.ranking = ranking;
        this.owner = owner;
        this.ip = ip;
        this.account = account;
        this.clock = clock;
        this.sequencer = new StreamSequencer(context.shardCount(), serverbound);
        this.scheduler = new SendScheduler(profile);
        ranking.connect(owner, ip, account, clock.getAsLong());
        // The slice is the first ticket of this owner, requested before any record (13.12).
        request(slice);
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

    /**
     * True while a pool request of this owner waits for bytes. The pool grants it when another owner
     * releases, but the driver only learns of the grant on its next {@link #poll}, so the session that owns
     * the driver polls again after other owners release (13.12).
     */
    synchronized boolean awaitingGrant() {
        return !closed && pendingGrants > 0;
    }

    /** Assigns an ordinary GAME record and returns its handle. Chunk shards are hashed with the current epoch. */
    public synchronized SentPacket submit(OutboundPacket packet, StreamSequencer.Fence fence, int chunkX, int chunkZ,
            Record.Dependency... semantic) {
        return submit(packet, fence, false, chunkX, chunkZ, semantic);
    }

    /**
     * As {@link #submit(OutboundPacket, StreamSequencer.Fence, int, int, Record.Dependency...)}, with the
     * serverbound interaction flag (13.9). The flag is valid only for an ORDINARY_CONTROL route on CONTROL;
     * the sequencer refuses it otherwise.
     */
    public synchronized SentPacket submit(OutboundPacket packet, StreamSequencer.Fence fence, boolean interaction,
            int chunkX, int chunkZ, Record.Dependency... semantic) {
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
        Record record = sequencer.assign(stream, fence, frame, interaction, semantic);
        if (!enqueueRecord(record, false)) {
            return handle;
        }
        handle.assign(laneOf(stream), stream, record.sequence());
        return handle;
    }

    /**
     * Assigns the fenced END (13.14): a CONTROL global fence after every record already assigned. Pending
     * ACK_JOINs settle first, as for an epoch transition, since nothing may follow END. Returns an unassigned
     * handle when closed.
     */
    public synchronized SentPacket submitEnd() {
        SentPacket handle = new SentPacket();
        if (closed) {
            return handle;
        }
        settleAcksLocked();
        batches.abort();
        Record record = sequencer.assignEnd();
        if (!enqueueRecord(record, false)) {
            return handle;
        }
        handle.assign(Lane.CONTROL, CONTROL, record.sequence());
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
        if (!enqueueRecord(record, false)) {
            return handle;
        }
        handle.assign(Lane.CONTROL, CONTROL, record.sequence());
        return handle;
    }

    /**
     * Registers a pending ACK_JOIN (13.19). Its charge is taken now and carried over to the record when
     * the ack is sequenced, and it counts against the queue limits from now on. Returns false, storing
     * nothing, when the tracker's entry or byte limit would be exceeded; the caller then pauses its producer.
     * A queue limit or a charge no cap can admit closes the driver with 0x12 instead.
     */
    public synchronized boolean createAck(long id, byte[] payload, int chargedBytes) {
        if (closed || payload == null) {
            return false;
        }
        long now = clock.getAsLong();
        long queued = payload.length + RECORD_OVERHEAD;
        if (overflows(1, queued) || !pool.admissible(account, chargedBytes)) {
            closeWith(CLOSE_RESOURCE);
            return false;
        }
        // Granted orphans (dropped ACKs) give their headroom back first. Headroom backpressure (13.19): the
        // producer pauses, nothing is stored, and no charge is taken.
        pump();
        if (ackEntriesHeld >= AckJoinTracker.MAX_ENTRIES || ackBytesHeld + chargedBytes > ackByteLimit) {
            return false;
        }
        if (!acks.create(id, chargedBytes, now)) {
            return false;
        }
        ackEntriesHeld++;
        ackBytesHeld += chargedBytes;
        queuedRecords++;
        queuedBytes += queued;
        Charge charge = new Charge(chargedBytes);
        charge.ack = true;
        request(charge);
        pendingAcks.put(id, new PendingAck(payload, charge, now, queued));
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
        if (!enqueueRecord(record, false)) {
            return handle;
        }
        batches.recordJoin(record.sequence());
        handle.assign(Lane.WORLD, FIRST_CHUNK, record.sequence());
        return handle;
    }

    /**
     * Assigns one bundle as one CONTROL run (13.15): the opening delimiter as a kind 4 record, every
     * member, then the closing delimiter. A bundle over 126 members or over B closes 0x11 locally and
     * assigns nothing.
     *
     * @return the handles of the unit in stream order, or an empty list when the driver refused the unit and
     *         closed (0x11 for a bundle over its limits, 0x12 for a member no cap can admit); nothing is written
     *         after such a close
     * @throws IllegalArgumentException when the run's total wire bytes exceed {@code maxRecordBytes}: it could never be
     *         granted, so it is refused before anything is queued, and the sequencer and queues are unchanged
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
        // The exact unit total is computed without assigning anything, so a refused run leaves the sequencer unchanged.
        long unitBytes;
        try {
            unitBytes = sequencer.bundleWireBytes(opening.frame(), frames, scopes, closing.frame(), external);
        } catch (IllegalArgumentException refused) {
            closeWith(CLOSE_ORDERING);
            return result;
        }
        // A run no cap can admit closes 0x12 as before; a run that fits the caps but not one maximum record is refused.
        if (!pool.admissible(account, unitBytes)) {
            closeWith(CLOSE_RESOURCE);
            return result;
        }
        if (unitBytes > maxRecordBytes) {
            throw new IllegalArgumentException("bundle run larger than maxRecordBytes");
        }
        List<Record> records;
        try {
            records = sequencer.assignBundle(opening.frame(), frames, scopes, closing.frame(), external);
        } catch (IllegalArgumentException refused) {
            closeWith(CLOSE_ORDERING);
            return result;
        }
        // The run is one charge of its total size, so admissibility is checked on that total before anything is queued.
        List<byte[]> encodings = new ArrayList<byte[]>(records.size());
        long runBytes = 0;
        for (Record record : records) {
            byte[] encoded = RecordCodec.encodeRecord(record);
            runBytes += encoded.length;
            encodings.add(encoded);
        }
        if (!pool.admissible(account, runBytes)) {
            closeWith(CLOSE_RESOURCE);
            return result;
        }
        enqueueRun(records, encodings);
        for (Record record : records) {
            result.add(handleOf(record));
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
        pump();
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
        charge.outstanding++;
        if (selection.last()) {
            charge.finished = true;
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
        // Releases every owner charge exactly once, the REALTIME slice included (it is the owner's first charge).
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
        realtimeOrder.clear();
        orphans.clear();
        pendingAcks.clear();
        ranking.disconnect(owner, clock.getAsLong());
    }

    private void closeWith(int code) {
        closeCode = code;
        close();
    }

    /**
     * Settles one slice. The record's charge is released once it was fully written and its last
     * outstanding slice has completed, whatever the completion order.
     */
    private void settle(long token) {
        Charge charge = tokens.remove(token);
        if (charge == null) {
            return;
        }
        charge.outstanding--;
        if (!charge.finished || charge.outstanding > 0 || charge.released) {
            return;
        }
        charge.released = true;
        releaseCharge(charge);
        long now = clock.getAsLong();
        ranking.progress(owner, charge.progress, now);
        ranking.setRetained(owner, pool.reserved(owner), now);
        pump();
    }

    /**
     * Releases one record's share of a charge. The pool bytes of a bundle run are released once, when its last
     * member is released. An ACK charge also leaves the ACK headroom (13.19).
     */
    private void releaseCharge(Charge charge) {
        if (charge.local) {
            sliceHeld -= charge.bytes;
            return;
        }
        Charge group = charge.group();
        if (--group.unreleased == 0) {
            pool.release(owner, group.bytes);
        }
        if (charge.ack) {
            ackBytesHeld -= charge.bytes;
            ackEntriesHeld--;
        }
    }

    /**
     * Encodes and queues a freshly sequenced record. A record no cap can ever admit closes the driver with
     * 0x12 instead, and returns false: nothing is written after the close, so its sequence number is never
     * visible to the peer.
     */
    private boolean enqueueRecord(Record record, boolean continues) {
        byte[] encoded = RecordCodec.encodeRecord(record);
        if (!pool.admissible(account, encoded.length)) {
            closeWith(CLOSE_RESOURCE);
            return false;
        }
        enqueueEncoded(record, encoded, continues);
        return true;
    }

    private void enqueueEncoded(Record record, byte[] encoded, boolean continues) {
        Charge charge = new Charge(encoded.length);
        charge.record = record;
        charge.encoded = encoded;
        charge.continues = continues;
        byRecord.put(record, charge);
        if (record.streamIndex() == REALTIME) {
            // Every REALTIME record keeps its stream position; one above the slice also takes the main queue (13.12).
            charge.viaMain = encoded.length > realtimeSliceBytes;
            realtimeOrder.addLast(charge);
            if (charge.viaMain) {
                toOffer.addLast(charge);
            }
        } else {
            toOffer.addLast(charge);
        }
        queuedRecords++;
        queuedBytes += encoded.length;
        pump();
    }

    /**
     * Queues a bundle run as one unit (13.15). The first member carries the pool charge for the whole run, so the
     * run is requested and granted once. Its members are contiguous in the queue, so {@link #pump()} offers all of
     * them together, and no part of the run is offered while the run waits for bytes.
     */
    private void enqueueRun(List<Record> records, List<byte[]> encodings) {
        long runBytes = 0;
        for (byte[] encoded : encodings) {
            runBytes += encoded.length;
        }
        Charge first = null;
        for (int i = 0; i < records.size(); i++) {
            Charge charge = new Charge(first == null ? runBytes : 0);
            charge.record = records.get(i);
            charge.encoded = encodings.get(i);
            charge.continues = i < records.size() - 1;
            charge.progress = charge.encoded.length;
            if (first == null) {
                first = charge;
            } else {
                charge.head = first;
                first.unreleased++;
            }
            byRecord.put(charge.record, charge);
            toOffer.addLast(charge);
            queuedRecords++;
            queuedBytes += charge.encoded.length;
        }
        pump();
    }

    /** Asks the pool for this charge. A refused request is granted later from the pool's queue. */
    private void request(Charge charge) {
        charge.requested = true;
        // The slice is protected: a connection holding only its slice is never a shed victim (13.12).
        charge.ticket = charge == slice ? pool.reserveProtectedTicket(owner, ip, account, charge.bytes)
                : pool.reserveTicket(owner, ip, account, charge.bytes);
        if (pool.takeGrant(charge.ticket)) {
            charge.granted = true;
            return;
        }
        if (pendingGrants == 0) {
            ranking.beginRefusedWait(owner, clock.getAsLong());
        }
        pendingGrants++;
    }

    /** True once the pool has charged this request; the grant is consumed by the first true answer. */
    private boolean granted(Charge charge) {
        if (charge.granted) {
            return true;
        }
        if (!charge.requested || !pool.takeGrant(charge.ticket)) {
            return false;
        }
        charge.granted = true;
        pendingGrants--;
        if (pendingGrants == 0) {
            ranking.endRefusedWait(owner);
        }
        return true;
    }

    /**
     * Moves charges forward. Orphans are released once granted. Sequenced records are requested and offered in
     * global assignment order (see the class javadoc): the walk stops at the first record that is not granted,
     * so no later record, on any stream, is requested or offered before it. A pending ACK_JOIN's charge was
     * requested at creation, so it is only waited on here, at its assignment position.
     */
    private void pump() {
        for (Iterator<Charge> it = orphans.iterator(); it.hasNext();) {
            Charge orphan = it.next();
            if (granted(orphan)) {
                it.remove();
                orphan.released = true;
                releaseCharge(orphan);
            }
        }
        // No record is offered before the REALTIME slice: it is the owner's first ticket (13.12).
        if (granted(slice)) {
            pumpMain();
            pumpRealtime();
        }
        ranking.setRetained(owner, pool.reserved(owner), clock.getAsLong());
    }

    /**
     * Grants and offers the main queue in assignment order. An oversize REALTIME record is granted here, at its
     * position, and is offered by {@link #pumpRealtime()} in REALTIME order.
     */
    private void pumpMain() {
        for (Iterator<Charge> it = toOffer.iterator(); it.hasNext();) {
            Charge charge = it.next();
            // A bundle run's later members are queued right behind its head, which is removed only once granted,
            // so reaching a member means its run is already granted.
            if (charge.head == null) {
                if (!charge.requested) {
                    request(charge);
                }
                if (!granted(charge)) {
                    break;
                }
            }
            it.remove();
            if (!charge.viaMain) {
                scheduler.offer(charge.record, charge.encoded.length, charge.continues);
            }
        }
    }

    /**
     * Offers REALTIME records in REALTIME order. A record of at most the slice is granted from the slice when it fits
     * the bytes that earlier REALTIME releases freed; it never waits for the main queue or the ledger. An oversize one
     * is offered once the main queue has granted it. The walk stops at the first record that cannot go yet, so at
     * most one REALTIME record waits, and only for REALTIME stream order or slice bytes.
     */
    private void pumpRealtime() {
        for (Iterator<Charge> it = realtimeOrder.iterator(); it.hasNext();) {
            Charge charge = it.next();
            if (charge.viaMain) {
                if (!charge.granted) {
                    break;
                }
            } else {
                if (sliceHeld + charge.bytes > realtimeSliceBytes) {
                    break;
                }
                sliceHeld += charge.bytes;
                charge.local = true;
                charge.granted = true;
            }
            it.remove();
            scheduler.offer(charge.record, charge.encoded.length, charge.continues);
        }
    }

    /** Takes this owner's shed notice, if any. Other owners' notices stay in the pool for their drivers. */
    private boolean checkShed() {
        if (pool.takeShed(owner)) {
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
        queuedBytes += encoded.length - pending.queuedBytes;
        return handle;
    }

    /** Drops a pending ACK_JOIN the tracker already removed; its charge is released once the pool grants it. */
    private void dropAck(long ackId, PendingAck pending) {
        pendingAcks.remove(ackId);
        orphans.addLast(pending.charge);
        queuedRecords--;
        queuedBytes -= pending.queuedBytes;
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
