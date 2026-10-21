// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

import rs.sudoe.quicraft.core.Protocol;

/**
 * Clientbound read pump over the N+3 streams: CONTROL, REALTIME, CHUNK[0..N-1] and ACK (protocol.md 13.12).
 *
 * <p>Each stream reads only into its own {@link ReceiveSlots} slot. Before a stream's first byte, a
 * provisional head of {@link SessionReadPump#PREFIX_STAGE} bytes is admitted for it, so early bytes (the
 * record_length VarInt and the bounded prefix) occupy that stream's slot. Once the declaration is known,
 * the provisional head is replaced by the record's full wire size, which must fit the slot. A stream
 * whose slot is already held (by a head that has not committed) is not read at all. A stream stops at
 * one unit: after its record completes it reads nothing more until {@link #commit} releases the slot.
 * A stream that is partway through a record keeps reading, so every incomplete head can finish.
 *
 * <p>A bundle (kind 4 opener, then its members and closer, 13.15) is one unit. The opener's declaration
 * admits the whole unit at once: its unit_wire_bytes and unit_records are charged to the CONTROL slot, and
 * the stream keeps reading the following kind 0 records into that same slot until the declared count is
 * reached. The declared bytes and the declared count must both match exactly, or the stream closes 0x11.
 *
 * <p>Grants never exceed the bytes the current head still needs, and that head's slot already holds
 * those bytes, so no separate grant is reserved and none has to be returned. Streams are served
 * round-robin, so no stream is starved by the others.
 *
 * <p>Not thread-safe; owned by the connection's receive path. Not wired into the live receive path yet.
 */
public final class ClientReadPump {
    /** A completed unit. Its slot stays held until {@link ClientReadPump#commit} releases it. */
    public static final class Unit {
        private final Lane lane;
        private final List<Record> records;
        private final long wireBytes;

        private Unit(Lane lane, List<Record> records, long wireBytes) {
            this.lane = lane;
            this.records = records;
            this.wireBytes = wireBytes;
        }

        /** The stream this unit arrived on. */
        public int stream() {
            return lane.stream;
        }

        /** The opening record: the record itself for a single-record unit, the opening delimiter for a bundle. */
        public Record record() {
            return records.get(0);
        }

        /** Every record of the unit in stream order, opener first; one record for a single-record unit. */
        public List<Record> records() {
            return Collections.unmodifiableList(records);
        }

        /** Records in the unit, delimiters included. */
        public int recordCount() {
            return records.size();
        }

        /** Sequence of the unit's last record: the closing sequence for {@link StreamReceiver#offer}. */
        public long closingSequence() {
            return records.get(records.size() - 1).sequence();
        }

        /** Total wire size of the unit, length prefixes included: the bytes its stream slot holds. */
        public long wireBytes() {
            return wireBytes;
        }
    }

    private final ReceiveSlots slots;
    private final boolean compressed;
    private final LongSupplier clock;
    private final SessionReadPump.ByteSource source;
    private final Lane[] lanes;
    private int cursor;
    private boolean open = true;

    /**
     * @param chunks negotiated chunk count N, 1..5
     * @param slots the connection's clientbound receive slots for the same N
     * @param compressed true when compression is enabled for the session
     * @param source the byte source, indexed by logical stream
     */
    public ClientReadPump(int chunks, ReceiveSlots slots, boolean compressed, SessionReadPump.ByteSource source) {
        this(chunks, slots, compressed, source, System::nanoTime);
    }

    /**
     * As above, with an injected monotonic clock for the pending-opener age (13.15 missing-close timer).
     *
     * @param clock monotonic nanosecond clock; read when a bundle opener is admitted
     */
    public ClientReadPump(int chunks, ReceiveSlots slots, boolean compressed, SessionReadPump.ByteSource source,
                          LongSupplier clock) {
        if (slots == null || source == null || clock == null) {
            throw new NullPointerException("client read pump argument");
        }
        LogicalStream.validateShardCount(chunks);
        this.slots = slots;
        this.compressed = compressed;
        this.source = source;
        this.clock = clock;
        this.lanes = new Lane[chunks + 3];
        for (int s = 0; s < lanes.length; s++) lanes[s] = new Lane(s);
    }

    /**
     * Advances every stream that may read, by at most one read each, and returns the units completed
     * by this call. Each returned unit holds its stream's slot until committed.
     *
     * @throws IOException when the byte source fails
     * @throws MultistreamProtocolException for a record that violates its declaration or does not fit
     *     its stream's slot, or a bundle whose declared bytes or count do not match (close 0x11)
     */
    public List<Unit> step() throws IOException, MultistreamProtocolException {
        if (!open) throw new IllegalStateException("client read pump is cancelled");
        List<Unit> done = new ArrayList<>(2);
        int start = cursor;
        cursor = (cursor + 1) % lanes.length;
        for (int i = 0; i < lanes.length; i++) {
            pumpLane(lanes[(start + i) % lanes.length], done);
        }
        return done;
    }

    /**
     * Returns the clock reading at which the oldest incomplete bundle opener was admitted, or -1 when no
     * bundle is incomplete. The receive driver compares it with the 10 s missing-close limit (13.15).
     */
    public long oldestPendingOpenerNanos() {
        long oldest = -1;
        for (Lane lane : lanes) {
            if (lane.pending != null && (oldest < 0 || lane.pending.admittedNanos < oldest)) {
                oldest = lane.pending.admittedNanos;
            }
        }
        return oldest;
    }

    boolean traceHasPartialUnit() {
        return tracePartialStreams() != 0;
    }

    int tracePartialStreams() {
        int mask = 0;
        for (Lane lane : lanes) {
            if (lane.ready == null && lane.traceStarted) mask |= 1 << lane.stream;
        }
        return mask;
    }

    boolean traceHasPartialBundle() {
        for (Lane lane : lanes) if (lane.pending != null) return true;
        return false;
    }

    /** Releases a completed unit's slot after the game has executed it. */
    public void commit(Unit unit) {
        if (unit == null || unit.lane.ready != unit) throw new IllegalStateException("unit is not awaiting commit");
        unit.lane.ready = null;
        unit.lane.traceStarted = false;
        slots.releaseHead(unit.lane.stream);
    }

    /** Drops partial records and releases every held slot. Idempotent. */
    public void cancel() {
        if (!open) return;
        open = false;
        for (Lane lane : lanes) {
            lane.decoder.cancel();
            lane.ready = null;
            lane.pending = null;
            lane.provisional = false;
            lane.admitted = false;
            if (slots.holdsHead(lane.stream)) slots.releaseHead(lane.stream);
        }
    }

    private void pumpLane(Lane lane, List<Unit> done) throws IOException, MultistreamProtocolException {
        if (lane.ready != null) return; // one unit per stream until it commits (13.12)
        Pending pending = lane.pending;
        if (pending != null && pending.consumed >= pending.wireBytes && pending.declared < pending.records) {
            throw bundleError("unit_wire_bytes " + pending.wireBytes + " reached before unit_records "
                    + pending.records);
        }
        if (pending == null && !lane.provisional && !lane.admitted) {
            // Early bytes need their own slot before any read; a full slot means no read on this stream.
            // Inside a bundle the unit already holds the slot, so its members need no admission here.
            if (!slots.admitHead(lane.stream, SessionReadPump.PREFIX_STAGE, 1)) return;
            lane.provisional = true;
        }
        int need = lane.decoder.wanted();
        if (need <= 0) throw new IllegalStateException("client decoder has no pending bytes");
        int grant = Math.min(need, ServerReadPartition.MAX_READ_GRANT);
        byte[] target = lane.decoder.stagingArray();
        int off = lane.decoder.stagingOffset();
        if (target == null) {
            target = lane.lengthByte;
            off = 0;
            grant = 1;
        }
        int got = source.read(lane.stream, target, off, grant);
        if (got <= 0) return;
        lane.traceStarted = true;
        if (got > grant) throw new IllegalStateException("byte source returned more than was requested");
        Record record = lane.decoder.next(ByteBuffer.wrap(target, off, got));
        if (record != null) complete(lane, record, done);
    }

    /** Adds a completed record to its unit; a unit that is whole becomes ready, awaiting commit. */
    private void complete(Lane lane, Record record, List<Unit> done) throws MultistreamProtocolException {
        lane.provisional = false;
        lane.admitted = false;
        Pending pending = lane.pending;
        if (pending == null) {
            lane.ready = new Unit(lane, Collections.singletonList(record), slots.heldBytes(lane.stream));
        } else {
            if (!pending.seen.isEmpty()
                    && record.sequence() != pending.seen.get(pending.seen.size() - 1).sequence() + 1) {
                throw bundleError("bundle record sequence " + record.sequence() + " is not consecutive");
            }
            // Members share the opener's epoch and carry no external dependencies: those attach to the opener.
            if (!pending.seen.isEmpty() && record.epoch() != pending.seen.get(0).epoch()) {
                throw bundleError("bundle member epoch " + record.epoch() + " differs from the opener's");
            }
            if (!pending.seen.isEmpty() && !record.dependencies().isEmpty()) {
                throw bundleError("bundle member carries " + record.dependencies().size() + " dependencies");
            }
            pending.seen.add(record);
            if (pending.seen.size() < pending.records) return;
            if (pending.consumed != pending.wireBytes) {
                throw bundleError("unit_records " + pending.records + " reached with " + pending.consumed
                        + " wire bytes, not unit_wire_bytes " + pending.wireBytes);
            }
            lane.pending = null;
            lane.ready = new Unit(lane, pending.seen, pending.wireBytes);
        }
        done.add(lane.ready);
    }

    /** Admits a declared record (13.12). Single records replace the provisional head; bundles admit the whole unit. */
    private boolean declare(Lane lane, long recordLength, byte[] prefix, int prefixLength)
            throws MultistreamProtocolException {
        RecordCodec.Declarations d = RecordCodec.inspectPrefix(prefix, prefixLength, recordLength, compressed);
        long wire = (long) lane.decoder.lengthPrefixBytes() + recordLength;
        if (lane.stream == lanes.length - 1 && (wire > ReceiveSlots.ACK_RECORD_MAX_BYTES
                || d.kind != RecordCodec.KIND_GAME)) {
            // 13.21: the ACK stream carries only small GAME records; anything else is a violation, not a resource issue.
            throw new MultistreamProtocolException(MultistreamProtocolException.CLOSE_ORDERING, "13.21",
                    "ACK stream record of " + wire + " wire bytes and kind " + d.kind);
        }
        if (lane.pending != null) return declareMember(lane, d, wire);
        if (d.kind == RecordCodec.KIND_BUNDLE_OPEN) return declareOpener(lane, d, wire);
        slots.releaseHead(lane.stream);
        if (!slots.admitHead(lane.stream, wire, 1)) {
            throw new MultistreamProtocolException(Protocol.CLOSE_RESOURCE, "13.12",
                    "record of " + wire + " wire bytes does not fit stream " + lane.stream + "'s slot");
        }
        lane.provisional = false;
        lane.admitted = true;
        return true;
    }

    /** A bundle opener (13.15): clientbound CONTROL only; its unit replaces the provisional head. */
    private boolean declareOpener(Lane lane, RecordCodec.Declarations d, long wire)
            throws MultistreamProtocolException {
        if (lane.stream != LogicalStream.CONTROL) throw bundleError("bundle open on stream " + lane.stream);
        if (d.unitWireBytes < wire) {
            throw bundleError("unit_wire_bytes " + d.unitWireBytes + " is smaller than the opener's " + wire);
        }
        slots.releaseHead(lane.stream);
        if (!slots.admitHead(lane.stream, d.unitWireBytes, (int) d.unitRecords)) {
            throw bundleError("bundle of " + d.unitRecords + " records and " + d.unitWireBytes
                    + " wire bytes does not fit stream " + lane.stream + "'s slot");
        }
        Pending pending = new Pending((int) d.unitRecords, d.unitWireBytes, clock.getAsLong());
        pending.declared = 1;
        pending.consumed = wire;
        lane.pending = pending;
        lane.provisional = false;
        lane.admitted = true;
        return true;
    }

    /** A bundle member or closer (13.15): a kind 0 GAME record that must fit the declared bytes and count. */
    private boolean declareMember(Lane lane, RecordCodec.Declarations d, long wire) throws MultistreamProtocolException {
        Pending pending = lane.pending;
        if (d.kind != RecordCodec.KIND_GAME) throw bundleError("bundle member of kind " + d.kind);
        if (pending.declared >= pending.records) {
            throw bundleError("more records than unit_records " + pending.records);
        }
        if (pending.consumed + wire > pending.wireBytes) {
            throw bundleError("bundle bytes exceed unit_wire_bytes " + pending.wireBytes);
        }
        pending.declared++;
        pending.consumed += wire;
        lane.provisional = false;
        lane.admitted = true;
        return true;
    }

    private static MultistreamProtocolException bundleError(String detail) {
        return new MultistreamProtocolException(MultistreamProtocolException.CLOSE_ORDERING, "13.15", detail);
    }

    /** A bundle being read on one stream: its declared totals and the records completed so far. */
    private static final class Pending {
        final int records;
        final long wireBytes;
        /** Clock reading when the opener was admitted (13.15). */
        final long admittedNanos;
        final List<Record> seen = new ArrayList<>();
        /** Records declared so far, opener included. */
        int declared;
        /** Wire bytes of the records declared so far, prefixes included. */
        long consumed;

        Pending(int records, long wireBytes, long admittedNanos) {
            this.records = records;
            this.wireBytes = wireBytes;
            this.admittedNanos = admittedNanos;
        }
    }

    /** Per-stream state: the decoder, whether a head is admitted, the bundle in progress and the unit awaiting commit. */
    private final class Lane {
        final int stream;
        final RecordCodec.CompactDecoder decoder;
        final byte[] lengthByte = new byte[1];
        boolean provisional;
        boolean admitted;
        Pending pending;
        boolean traceStarted;
        Unit ready;

        Lane(int stream) {
            this.stream = stream;
            this.decoder = new RecordCodec.CompactDecoder(stream);
            this.decoder.setAdmission((s, recordLength, prefix, prefixLength) ->
                    declare(this, recordLength, prefix, prefixLength));
        }
    }
}
