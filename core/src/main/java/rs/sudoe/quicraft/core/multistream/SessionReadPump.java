// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import rs.sudoe.quicraft.core.resource.InboundExcessPool;
import rs.sudoe.quicraft.core.resource.ResourcePools;
import rs.sudoe.quicraft.core.routing.RouteV2;

/**
 * Bounded serverbound read pump for one N+2 session (protocol.md 13.12).
 *
 * <p>The pump reads the CONTROL and REALTIME streams one record at a time. Before a record's body is
 * read, its bounded prefix is inspected and its whole footprint is charged: REALTIME inside its
 * protected 2 KiB slice, CONTROL inside the protected allowance or else from the refusable inbound
 * excess pool. A declaration that cannot be admitted is held. The decoder keeps its position and the
 * pump retries the same record, consuming no further bytes, while the other stream keeps progressing.
 *
 * <p>Each stream holds at most one unit. A unit's charges are returned by {@link Unit#close}, which the
 * caller invokes only after the game has executed the record. Compressed records are inflated only
 * after admission, bounded to their declared Data Length.
 *
 * <p>Not thread-safe; owned by the connection's receive path. This class is not wired into the live
 * receive path yet (the U8f cutover does that).
 */
public final class SessionReadPump {
    /** Connection inflater state, about 40 KiB, charged once to the node INFLATER pool (13.12). */
    public static final long INFLATER_BYTES = 40L * 1024;

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
        if (partition == null || pools == null || excess == null || owner == null || ip == null
                || limits == null || source == null) {
            throw new NullPointerException("session read pump argument");
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
    }

    /**
     * Advances each stream by at most one read, REALTIME first, and returns the records completed by
     * this call. Call it again until the source is drained. A held stream does not stop the other one.
     *
     * @return completed units, each owning its charges until closed
     * @throws IOException when the byte source fails
     * @throws MultistreamProtocolException for a record that violates its caps (close 0x11)
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
        for (Lane lane : new Lane[] {control, realtime}) {
            lane.decoder.cancel();
            if (lane.held != null) lane.held.close();
            if (lane.partitionCharge > 0) partition.release(lane.stream, (int) lane.partitionCharge);
            lane.partitionCharge = 0;
            lane.excessCharge = 0;
            lane.admitted = false;
            lane.excessWaiting = false;
        }
        excess.cancel(owner);
        if (inflater != null) {
            inflater.end();
            inflater = null;
        }
        if (inflaterCharged) {
            pools.release(ResourcePools.Pool.INFLATER, owner, INFLATER_BYTES);
            inflaterCharged = false;
        }
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
        Record record;
        int need = lane.decoder.wanted();
        if (need == 0) {
            record = lane.decoder.next(ByteBuffer.wrap(NO_BYTES)); // retry the held admission
        } else {
            // Before admission only the bounded prefix is read, under a transient prefix-slice grant.
            // After admission the record's footprint already covers its body reads.
            boolean transientGrant = !lane.admitted;
            int grant = transientGrant
                    ? Math.min(need, partition.readGrant(lane.stream, need))
                    : Math.min(need, ServerReadPartition.MAX_READ_GRANT);
            if (grant <= 0) return;
            if (transientGrant && !partition.reservePrefix(grant)) return;
            byte[] buf = new byte[grant];
            int got;
            try {
                got = source.read(lane.stream, buf, 0, grant);
            } finally {
                if (transientGrant) partition.releasePrefix(grant);
            }
            if (got <= 0) return;
            if (got > grant) throw new IllegalStateException("byte source returned more than was requested");
            record = lane.decoder.next(ByteBuffer.wrap(buf, 0, got));
        }
        if (record != null) done.add(complete(lane, record));
    }

    /** Admission for one declared record, called by the lane's decoder before any body allocation. */
    private boolean admit(Lane lane, long recordLength, byte[] prefix, int prefixLength)
            throws MultistreamProtocolException {
        boolean realtimeLane = lane.stream == LogicalStream.REALTIME;
        RecordCodec.Declarations d = RecordCodec.inspectPrefix(prefix, prefixLength, recordLength, compressed);
        int lengthBytes = lane.decoder.lengthPrefixBytes();
        if (realtimeLane) {
            if (!ServerReadPartition.realtimeRecordLegal(recordLength, Math.max(0, d.dataLength), d.decodedLength)) {
                throw refused("REALTIME record over its 510-byte or 1024-byte cap");
            }
        } else {
            if (recordLength > limits.maxServerboundRecord) throw refused("CONTROL record over its route cap");
            if (d.decodedLength > limits.maxDecodedFootprint) throw refused("CONTROL decoded length over its route cap");
        }
        // Wire record including its length prefix, plus the decoded bytes it can produce (13.12).
        long footprint = realtimeLane ? d.decodedLength : recordLength + lengthBytes + d.decodedLength;
        // Prefix copies are charged only for the admission step: once the frame copy exists, the
        // footprint covers those bytes. Charging here means a held record never pins the prefix slice.
        if (!partition.reservePrefix(2 * prefixLength)) return false;
        try {
            if (realtimeLane) {
                if (!partition.reserve(LogicalStream.REALTIME, (int) footprint)) return false;
                lane.partitionCharge = footprint;
            } else if (partition.reserve(LogicalStream.CONTROL, (int) footprint)) {
                lane.partitionCharge = footprint;
            } else if (!admitExcess(lane, footprint)) {
                return false;
            }
        } finally {
            partition.releasePrefix(2 * prefixLength);
        }
        lane.declared = d;
        lane.admitted = true;
        lane.footprint = footprint;
        lane.admittedLength = recordLength;
        lane.lengthBytes = lengthBytes;
        return true;
    }

    /**
     * Refusable excess for a CONTROL record that does not fit the protected allowance. The ledger queues
     * a refused request and grants it on release, and every new reserve call queues another one, so
     * a held record waits on its single outstanding request instead of re-requesting.
     */
    private boolean admitExcess(Lane lane, long footprint) {
        if (lane.excessWaiting) {
            if (excess.reserved(owner) != footprint) return false; // still queued
            lane.excessWaiting = false;
        } else if (!excess.reserve(owner, ip, account, footprint, false)) {
            lane.excessWaiting = true;
            return false;
        }
        lane.excessCharge = footprint;
        return true;
    }

    private Unit complete(Lane lane, Record record) throws MultistreamProtocolException {
        RecordCodec.Declarations d = lane.declared;
        byte[] body = record.payload();
        byte[] decoded;
        if (!compressed) {
            decoded = Arrays.copyOfRange(body, d.innerLengthBytes, body.length);
        } else if (d.dataLength == 0) {
            decoded = Arrays.copyOfRange(body, d.innerLengthBytes + d.dataLengthBytes, body.length);
        } else {
            decoded = inflate(body, d.innerLengthBytes + d.dataLengthBytes, (int) d.dataLength);
        }
        // Return the part of the declared footprint that the record did not use.
        long actual = lane.stream == LogicalStream.REALTIME
                ? decoded.length
                : lane.admittedLength + lane.lengthBytes + decoded.length;
        long surplus = lane.footprint - actual;
        if (surplus > 0) {
            if (lane.excessCharge > 0) {
                excess.release(owner, surplus);
                lane.excessCharge -= surplus;
            } else {
                partition.release(lane.stream, (int) surplus);
                lane.partitionCharge -= surplus;
            }
        }
        Unit unit = new Unit(lane, record, decoded, lane.partitionCharge, lane.excessCharge);
        lane.held = unit;
        lane.admitted = false;
        lane.excessWaiting = false;
        lane.partitionCharge = 0;
        lane.excessCharge = 0;
        return unit;
    }

    /** Inflates exactly {@code dataLength} bytes; anything else is a protocol error (13.4, 13.12). */
    private byte[] inflate(byte[] input, int offset, int dataLength) throws MultistreamProtocolException {
        inflations++;
        if (inflater == null) inflater = new Inflater();
        inflater.reset();
        inflater.setInput(input, offset, input.length - offset);
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
        RecordCodec.Declarations declared;
        boolean admitted;
        boolean excessWaiting;
        long footprint;
        long admittedLength;
        int lengthBytes;
        long partitionCharge;
        long excessCharge;
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
        private final Lane lane;
        private final Record record;
        private final byte[] decoded;
        private long partitionBytes;
        private long excessBytes;
        private boolean closed;

        private Unit(Lane lane, Record record, byte[] decoded, long partitionBytes, long excessBytes) {
            this.lane = lane;
            this.record = record;
            this.decoded = decoded;
            this.partitionBytes = partitionBytes;
            this.excessBytes = excessBytes;
        }

        /** The stream this record arrived on. */
        public int stream() {
            return lane.stream;
        }

        /** The parsed record; its payload is the wire body after the compact header. */
        public Record record() {
            return record;
        }

        /** The packet content: the packet id and fields, uncompressed, without the inner length. */
        public byte[] decoded() {
            return decoded.clone();
        }

        /** Returns this record's charges. Idempotent. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (partitionBytes > 0) partition.release(lane.stream, (int) partitionBytes);
            if (excessBytes > 0) excess.release(owner, excessBytes);
            partitionBytes = 0;
            excessBytes = 0;
            if (lane.held == this) lane.held = null;
        }
    }
}
