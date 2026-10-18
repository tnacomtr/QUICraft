// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.resource.InboundExcessPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;
import rs.sudoe.quicraft.core.resource.ResourcePools;
import rs.sudoe.quicraft.core.routing.RouteV2;

/**
 * Bounded serverbound read pump for one N+2 session (protocol.md 13.12).
 *
 * <p>The pump reads the CONTROL and REALTIME streams one record at a time. Before a record's body is
 * read, its bounded prefix is staged in a fixed 74-byte slice of the protected area, inspected and its
 * whole footprint is charged: REALTIME inside its protected 2 KiB slice, CONTROL inside the protected
 * allowance or else from the refusable inbound excess pool. A declaration that cannot be admitted is
 * held. The decoder keeps its staged prefix and the pump retries the same record, consuming no further
 * bytes, while the other stream keeps progressing.
 *
 * <p>Bytes are read straight into the decoder's own staging array, so the admitted footprint is the
 * only memory a record occupies: the record frame (the wire record, header included) and its decoded
 * packet bytes. A completed record is an opaque unit. Compressed records are not inflated by
 * {@link #step}; the execution driver calls {@link #inflate(Unit)} once the unit is eligible to run.
 *
 * <p>Each stream holds at most one unit. A unit's charges are returned by {@link Unit#close}, which the
 * caller invokes only after the game has executed the record.
 *
 * <p>Inbound pressure ranking (13.12). With a ranking, the pump registers the connection at construction,
 * reports every byte read from the peer as progress, keeps the ranking's retained bytes equal to the
 * connection's excess-pool charge after every change it makes, runs a refused wait while a CONTROL record
 * waits for excess, and unregisters the connection in {@link #cancel()}. The inbound excess pool picks its
 * shed victims by that ranking. A grant the pool makes on another connection's release is reported when this
 * pump next polls it.
 *
 * <p>Not thread-safe; owned by the connection's receive path. This class is not wired into the live
 * receive path yet (the U8f cutover does that).
 */
public final class SessionReadPump {
    /** Connection inflater state, about 40 KiB, charged once to the node INFLATER pool (13.12). */
    public static final long INFLATER_BYTES = 40L * 1024;
    /** Prefix staging per lane: the record_length VarInt plus the bounded prefix (13.12, 160-byte slice). */
    static final int PREFIX_STAGE = 5 + RecordCodec.CompactDecoder.PREFIX_WINDOW;

    private static final byte[] NO_BYTES = new byte[0];

    /** Supplies the bytes of one serverbound stream. */
    public interface ByteSource {
        /**
         * Copies up to {@code max} ready bytes of {@code stream} into {@code into} at {@code offset}.
         *
         * @return the count copied, 0 when none are ready; never more than {@code max}
         */
        int read(int stream, byte[] into, int offset, int max) throws IOException;
    }

    private final ServerReadPartition partition;
    private final ResourcePools pools;
    private final InboundExcessPool excess;
    private final Object owner;
    private final Object ip;
    private final Object account;
    private final RouteV2.ServerboundLimits limits;
    private final boolean compressed;
    private final ByteSource source;
    private final Lane control;
    private final Lane realtime;
    private final PressureRanking ranking;
    private final LongSupplier clock;
    private boolean inflaterCharged;
    private Inflater inflater;
    private int inflations;
    private boolean open = true;

    /**
     * Creates the pump for one session. Compressed sessions reserve the connection inflater here, so a
     * refused reservation fails before any byte is read.
     *
     * @param partition the connection's protected read partition, already built for its N
     * @param pools node pools; the INFLATER pool is charged when {@code compressed}
     * @param excess the node inbound excess pool
     * @param owner the connection's identity for excess and pool charges
     * @param ip the connection's source address key for per-IP excess caps
     * @param account the authenticated account key, or null when none
     * @param limits the reviewed serverbound limits of the current protocol state
     * @param compressed true when compression is enabled for the session
     * @param source the byte source for the CONTROL and REALTIME streams
     * @throws IllegalStateException when the node INFLATER pool refuses the session's reservation
     */
    public SessionReadPump(ServerReadPartition partition, ResourcePools pools, InboundExcessPool excess,
                           Object owner, Object ip, Object account, RouteV2.ServerboundLimits limits,
                           boolean compressed, ByteSource source) {
        this(partition, pools, excess, owner, ip, account, limits, compressed, source, null, null);
    }

    /**
     * Creates the pump for one session and registers the connection with the inbound pressure ranking.
     *
     * @param ranking the inbound pressure ranking the excess pool picks victims by, or null for none
     * @param clock monotonic nanosecond clock for the ranking; required with a ranking
     * @throws IllegalArgumentException with a ranking but no clock, or an {@code ip} that is not an
     *     {@link InetAddress}
     * @see #SessionReadPump(ServerReadPartition, ResourcePools, InboundExcessPool, Object, Object, Object,
     *     RouteV2.ServerboundLimits, boolean, ByteSource)
     */
    public SessionReadPump(ServerReadPartition partition, ResourcePools pools, InboundExcessPool excess,
                           Object owner, Object ip, Object account, RouteV2.ServerboundLimits limits,
                           boolean compressed, ByteSource source, PressureRanking ranking, LongSupplier clock) {
        if (partition == null || pools == null || excess == null || owner == null || ip == null
                || limits == null || source == null) {
            throw new NullPointerException("session read pump argument");
        }
        if (ranking != null && (clock == null || !(ip instanceof InetAddress))) {
            throw new IllegalArgumentException("an inbound ranking needs a clock and an InetAddress");
        }
        this.partition = partition;
        this.pools = pools;
        this.excess = excess;
        this.owner = owner;
        this.ip = ip;
        this.account = account;
        this.limits = limits;
        this.compressed = compressed;
        this.source = source;
        if (compressed) {
            if (!pools.reserve(ResourcePools.Pool.INFLATER, owner, INFLATER_BYTES)) {
                throw new IllegalStateException("node INFLATER pool refused the session");
            }
            inflaterCharged = true;
        }
        this.control = new Lane(LogicalStream.CONTROL);
        this.realtime = new Lane(LogicalStream.REALTIME);
        this.ranking = ranking;
        this.clock = clock;
        if (ranking != null) {
            ranking.connect(owner, (InetAddress) ip, account instanceof String ? (String) account : null,
                    clock.getAsLong());
        }
    }

    /**
     * Advances each stream by at most one read, REALTIME first, and returns the records completed by
     * this call. Call it again until the source is drained. A held stream does not stop the other one.
     * Completed compressed records are returned opaque; see {@link #inflate(Unit)}.
     *
     * @return completed units, each owning its charges until closed
     * @throws IOException when the byte source fails
     * @throws MultistreamProtocolException for a record that violates its caps (close 0x11), or a
     *     footprint no configured excess cap can admit (close 0x12)
     */
    public List<Unit> step() throws IOException, MultistreamProtocolException {
        if (!open) throw new IllegalStateException("session read pump is cancelled");
        List<Unit> done = new ArrayList<>(2);
        pumpLane(realtime, done);
        pumpLane(control, done);
        return done;
    }

    /**
     * Ends the session: drops partial records, closes held units, drops queued excess requests and
     * returns every charge, including the inflater. Idempotent.
     */
    public void cancel() {
        if (!open) return;
        open = false;
        ReceiveCleanup.run(() -> cancelLane(control), () -> cancelLane(realtime), () -> excess.cancel(owner), () -> {
            if (ranking != null) {
                ReceiveCleanup.run(() -> ranking.endRefusedWait(owner),
                        () -> ranking.disconnect(owner, clock.getAsLong()));
            }
        }, () -> {
            if (inflater != null) {
                inflater.end();
                inflater = null;
            }
        }, () -> {
            if (inflaterCharged) {
                pools.release(ResourcePools.Pool.INFLATER, owner, INFLATER_BYTES);
                inflaterCharged = false;
            }
        });
    }

    private void cancelLane(Lane lane) {
        ReceiveCleanup.run(lane.decoder::cancel, () -> {
            if (lane.held != null) lane.held.close();
        }, () -> {
            if (lane.partitionCharge > 0) partition.release(lane.stream, (int) lane.partitionCharge);
        }, () -> releasePrefix(lane), () -> {
            lane.partitionCharge = 0;
            lane.staged = false;
            lane.excessCharge = 0;
            lane.admitted = false;
            lane.excessWaiting = false;
            lane.held = null;
        });
    }

    /**
     * Inflates an opaque compressed unit to exactly its declared Data Length (13.4, 13.12). Call it only
     * once the unit is eligible to execute; the unit keeps its charges, and the surplus is returned once
     * the real size is known. Calling it again returns the same bytes. Uncompressed and stored units are
     * already decoded, so this returns them without inflating.
     *
     * @throws MultistreamProtocolException when the declared bounds or the zlib stream are violated (0x11)
     */
    public byte[] inflate(Unit unit) throws MultistreamProtocolException {
        if (unit == null || unit.owner != this || unit.closed) throw new IllegalStateException("unit not owned by this pump");
        if (unit.decoded != null) return unit.decoded;
        RecordCodec.Declarations d = unit.declared;
        // Admission checked these bounds already; they are repeated here so no inflation can skip them.
        if (unit.lane.stream == LogicalStream.REALTIME) {
            if (d.dataLength > ServerReadPartition.REALTIME_DECODED_CAP) throw refused("REALTIME Data Length over 1024");
        } else if (d.dataLength > limits.maxDecodedFootprint) {
            throw refused("CONTROL Data Length over its route cap");
        }
        byte[] array = unit.record.payloadArray();
        int start = unit.record.payloadOffset() + d.innerLengthBytes + d.dataLengthBytes;
        int end = unit.record.payloadOffset() + unit.record.payloadLength();
        byte[] out = inflate(array, start, end - start, (int) d.dataLength);
        settle(unit, array.length + (long) out.length);
        unit.decoded = out;
        return out;
    }

    /** Record bodies allocated on a stream so far; a held or refused declaration allocates none. */
    int frameAllocations(int stream) {
        return lane(stream).decoder.frameAllocations();
    }

    /** Inflation attempts so far; a record refused before inflating adds none. */
    int inflations() {
        return inflations;
    }

    private Lane lane(int stream) {
        if (stream == LogicalStream.CONTROL) return control;
        if (stream == LogicalStream.REALTIME) return realtime;
        throw new IllegalArgumentException("stream " + stream);
    }

    private void pumpLane(Lane lane, List<Unit> done) throws IOException, MultistreamProtocolException {
        if (lane.held != null) return; // one head per stream until its unit closes (13.12)
        if (!lane.admitted && lane.prefixCharge == 0) {
            // Staging the length and prefix uses the fixed prefix slice; bytes are read only once it is held.
            if (!partition.reservePrefix(PREFIX_STAGE)) return;
            lane.prefixCharge = PREFIX_STAGE;
        }
        Record record;
        int need = lane.decoder.wanted();
        if (need == 0) {
            record = lane.decoder.next(ByteBuffer.wrap(NO_BYTES)); // retry the held admission
        } else {
            // After admission the body bytes are already inside the admitted footprint, so no extra grant
            // is reserved; the grant is only capped at 16 KiB (13.12).
            int grant = lane.admitted ? Math.min(need, ServerReadPartition.MAX_READ_GRANT) : need;
            byte[] target = lane.decoder.stagingArray();
            int off = lane.decoder.stagingOffset();
            if (target == null) {
                target = lane.lengthByte; // the record_length VarInt is read one byte at a time
                off = 0;
                grant = 1;
            }
            int got = source.read(lane.stream, target, off, grant);
            if (got <= 0) {
                // An idle lane with nothing staged gives its prefix slice back; a staged record keeps it.
                if (!lane.staged) releasePrefix(lane);
                return;
            }
            if (got > grant) throw new IllegalStateException("byte source returned more than was requested");
            if (ranking != null) ranking.progress(owner, got, clock.getAsLong());
            lane.staged = true;
            record = lane.decoder.next(ByteBuffer.wrap(target, off, got));
        }
        // The staged prefix is returned only once it is transferred to the frame or the record completes.
        if (record != null || !lane.decoder.prefixStage()) releasePrefix(lane);
        if (record != null) done.add(complete(lane, record));
    }

    private void releasePrefix(Lane lane) {
        if (lane.prefixCharge > 0) {
            partition.releasePrefix(lane.prefixCharge);
            lane.prefixCharge = 0;
        }
    }

    /** Admission for one declared record, called by the lane's decoder before any body allocation. */
    private boolean admit(Lane lane, long recordLength, byte[] prefix, int prefixLength)
            throws MultistreamProtocolException {
        boolean realtimeLane = lane.stream == LogicalStream.REALTIME;
        RecordCodec.Declarations d = RecordCodec.inspectPrefix(prefix, prefixLength, recordLength, compressed);
        // Bundles are clientbound CONTROL only (13.15); a serverbound opener is refused before any allocation.
        if (d.kind == RecordCodec.KIND_BUNDLE_OPEN) throw refused("bundle open on a serverbound stream (13.15)");
        int lengthBytes = lane.decoder.lengthPrefixBytes();
        if (realtimeLane) {
            if (!ServerReadPartition.realtimeRecordLegal(recordLength, Math.max(0, d.dataLength), d.decodedLength)) {
                throw refused("REALTIME record over its 510-byte or 1024-byte cap");
            }
        } else {
            // L counts the whole wire record, outer length prefix included (13.12).
            if (recordLength + lengthBytes > limits.maxServerboundRecord) throw refused("CONTROL record over its route cap");
            if (d.decodedLength > limits.maxDecodedFootprint) throw refused("CONTROL decoded length over its route cap");
        }
        // The frame (the wire record, header included) plus the decoded bytes it can produce (13.12). The
        // CONTROL footprint also covers the length prefix it was read with, so it is never undercounted.
        long footprint = realtimeLane
                ? recordLength + d.decodedLength
                : lengthBytes + recordLength + d.decodedLength;
        if (realtimeLane) {
            if (!partition.reserve(LogicalStream.REALTIME, (int) footprint)) return false;
            lane.partitionCharge = footprint;
        } else if (partition.reserve(LogicalStream.CONTROL, (int) footprint)) {
            lane.partitionCharge = footprint;
        } else if (!admitExcess(lane, footprint)) {
            return false;
        }
        lane.declared = d;
        lane.admitted = true;
        lane.footprint = footprint;
        return true;
    }

    /**
     * Refusable excess for a CONTROL record that does not fit the protected allowance. The ledger queues
     * a refused request and grants it on release, and every new reserve call queues another one, so
     * a held record waits on its single outstanding request instead of re-requesting.
     */
    private boolean admitExcess(Lane lane, long footprint) throws MultistreamProtocolException {
        if (lane.excessWaiting) {
            if (excess.reserved(owner) != footprint) return false; // still queued
            lane.excessWaiting = false;
            if (ranking != null) ranking.endRefusedWait(owner);
        } else {
            boolean granted;
            try {
                granted = excess.reserve(owner, ip, account, footprint, false);
            } catch (IllegalArgumentException e) {
                // A footprint over a configured cap can never be admitted: a resource close (13.12), not a crash.
                throw new MultistreamProtocolException(Protocol.CLOSE_RESOURCE, "13.12",
                        "CONTROL footprint " + footprint + " exceeds its excess cap");
            }
            if (!granted) {
                lane.excessWaiting = true;
                if (ranking != null) ranking.beginRefusedWait(owner, clock.getAsLong());
                return false;
            }
        }
        lane.excessCharge = footprint;
        retained();
        return true;
    }

    private Unit complete(Lane lane, Record record) throws MultistreamProtocolException {
        RecordCodec.Declarations d = lane.declared;
        Unit unit = new Unit(lane, record, d, lane.partitionCharge, lane.excessCharge, lane.footprint);
        lane.held = unit;
        lane.admitted = false;
        lane.staged = false;
        lane.excessWaiting = false;
        lane.partitionCharge = 0;
        lane.excessCharge = 0;
        if (!compressed) {
            unit.decoded = slice(record, d.innerLengthBytes, record.payloadLength());
        } else if (d.dataLength == 0) {
            // Stored: the packet bytes are a plain copy, so nothing is inflated (13.12).
            unit.decoded = slice(record, d.innerLengthBytes + d.dataLengthBytes, record.payloadLength());
        }
        if (unit.decoded != null) settle(unit, record.payloadArray().length + (long) unit.decoded.length);
        return unit;
    }

    /** Returns the part of a unit's footprint that its real size did not use. */
    private void settle(Unit unit, long actual) {
        long surplus = unit.footprint - actual;
        if (surplus <= 0) return;
        if (unit.excessBytes > 0) {
            excess.release(owner, surplus);
            unit.excessBytes -= surplus;
            retained();
        } else {
            partition.release(unit.lane.stream, (int) surplus);
            unit.partitionBytes -= surplus;
        }
    }

    /** Reports the connection's current excess-pool charge to the inbound ranking. */
    private void retained() {
        if (ranking != null) ranking.setRetained(owner, excess.reserved(owner), clock.getAsLong());
    }

    private static byte[] slice(Record record, int from, int to) {
        int base = record.payloadOffset();
        return Arrays.copyOfRange(record.payloadArray(), base + from, base + to);
    }

    /** Inflates exactly {@code dataLength} bytes; anything else is a protocol error (13.4, 13.12). */
    private byte[] inflate(byte[] input, int offset, int length, int dataLength) throws MultistreamProtocolException {
        inflations++;
        if (inflater == null) inflater = new Inflater();
        inflater.reset();
        inflater.setInput(input, offset, length);
        byte[] out = new byte[dataLength];
        byte[] probe = new byte[1];
        int produced = 0;
        try {
            while (!inflater.finished()) {
                if (produced < dataLength) {
                    int n = inflater.inflate(out, produced, dataLength - produced);
                    if (n == 0 && !inflater.finished()) throw refused("zlib stream needs more input");
                    produced += n;
                } else if (inflater.inflate(probe, 0, 1) != 0 || !inflater.finished()) {
                    throw refused("inflated past Data Length");
                }
            }
        } catch (DataFormatException e) {
            throw refused("invalid zlib data");
        }
        if (produced != dataLength || inflater.getRemaining() != 0) {
            throw refused("inflated size differs from Data Length");
        }
        return out;
    }

    private static MultistreamProtocolException refused(String detail) {
        return new MultistreamProtocolException(MultistreamProtocolException.CLOSE_ORDERING, "13.12", detail);
    }

    /** Per-stream state: the decoder, the admitted record's charges and the unit awaiting execution. */
    private final class Lane {
        final int stream;
        final RecordCodec.CompactDecoder decoder;
        final byte[] lengthByte = new byte[1];
        RecordCodec.Declarations declared;
        boolean admitted;
        boolean staged;
        boolean excessWaiting;
        long footprint;
        long partitionCharge;
        long excessCharge;
        int prefixCharge;
        Unit held;

        Lane(int stream) {
            this.stream = stream;
            this.decoder = new RecordCodec.CompactDecoder(stream);
            this.decoder.setAdmission((s, recordLength, prefix, prefixLength) ->
                    admit(this, recordLength, prefix, prefixLength));
        }
    }

    /**
     * One completed record. Its charges stay held until {@link #close}; the caller closes it after the
     * game has executed the record, so queued work keeps its bytes counted.
     */
    public final class Unit implements AutoCloseable {
        private final SessionReadPump owner;
        private final Lane lane;
        private final Record record;
        private final RecordCodec.Declarations declared;
        private final long footprint;
        private byte[] decoded;
        private long partitionBytes;
        private long excessBytes;
        private boolean closed;

        private Unit(Lane lane, Record record, RecordCodec.Declarations declared, long partitionBytes,
                     long excessBytes, long footprint) {
            this.owner = SessionReadPump.this;
            this.lane = lane;
            this.record = record;
            this.declared = declared;
            this.partitionBytes = partitionBytes;
            this.excessBytes = excessBytes;
            this.footprint = footprint;
        }

        /** The stream this record arrived on. */
        public int stream() {
            return lane.stream;
        }

        /** The parsed record; its payload is the wire body after the compact header. */
        public Record record() {
            return record;
        }

        /** True when a compressed record still needs {@link SessionReadPump#inflate(Unit)}. */
        public boolean needsInflation() {
            return decoded == null;
        }

        /** Wire footprint charged for this record at admission: the bytes its receive slot holds (13.12). */
        long wireBytes() {
            return footprint;
        }

        /** Bytes that inflating this record allocates (its declared Data Length); 0 when none is needed. */
        long inflationBytes() {
            return needsInflation() ? declared.dataLength : 0;
        }

        /**
         * The packet content: the packet id and fields, uncompressed, without the inner length. The array
         * is the unit's own; callers must not modify it.
         *
         * @throws IllegalStateException while a compressed record has not been inflated
         */
        public byte[] decoded() {
            if (decoded == null) throw new IllegalStateException("record not inflated; call inflate first");
            return decoded;
        }

        /** Returns this record's charges. Idempotent. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            long partitionCharge = partitionBytes;
            long excessCharge = excessBytes;
            boolean excessReleased = excessBytes > 0;
            partitionBytes = 0;
            excessBytes = 0;
            if (lane.held == this) lane.held = null;
            ReceiveCleanup.run(() -> {
                if (partitionCharge > 0) partition.release(lane.stream, (int) partitionCharge);
            }, () -> {
                if (excessReleased) excess.release(SessionReadPump.this.owner, excessCharge);
            }, () -> {
                if (excessReleased) retained();
            });
        }

    }
}
