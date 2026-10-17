// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import rs.sudoe.quicraft.core.Protocol;

/**
 * Clientbound read pump over the N+2 streams: CONTROL, REALTIME and CHUNK[0..N-1] (protocol.md 13.12).
 *
 * <p>Each stream reads only into its own {@link ReceiveSlots} slot. Before a stream's first byte, a
 * provisional head of {@link SessionReadPump#PREFIX_STAGE} bytes is admitted for it, so early bytes (the
 * record_length VarInt and the bounded prefix) occupy that stream's slot. Once the declaration is known,
 * the provisional head is replaced by the record's full wire size, which must fit the slot. A stream
 * whose slot is already held (by a head that has not committed) is not read at all. A stream stops at
 * one unit: after its record completes it reads nothing more until {@link #commit} releases the slot.
 * A stream that is partway through a record keeps reading, so every incomplete head can finish.
 *
 * <p>Grants never exceed the bytes the current head still needs, and that head's slot already holds
 * those bytes, so no separate grant is reserved and none has to be returned. Streams are served
 * round-robin, so no stream is starved by the others.
 *
 * <p>Not thread-safe; owned by the connection's receive path. Not wired into the live receive path yet.
 */
public final class ClientReadPump {
    private static final byte[] NO_BYTES = new byte[0];

    /** A completed record. Its slot stays held until {@link ClientReadPump#commit} releases it. */
    public static final class Unit {
        private final Lane lane;
        private final Record record;

        private Unit(Lane lane, Record record) {
            this.lane = lane;
            this.record = record;
        }

        /** The stream this record arrived on. */
        public int stream() {
            return lane.stream;
        }

        /** The parsed record. */
        public Record record() {
            return record;
        }
    }

    private final ReceiveSlots slots;
    private final boolean compressed;
    private final SessionReadPump.ByteSource source;
    private final Lane[] lanes;
    private int cursor;
    private boolean open = true;

    /**
     * @param chunks negotiated chunk count N, 1..6
     * @param slots the connection's clientbound receive slots for the same N
     * @param compressed true when compression is enabled for the session
     * @param source the byte source, indexed by logical stream
     */
    public ClientReadPump(int chunks, ReceiveSlots slots, boolean compressed, SessionReadPump.ByteSource source) {
        if (slots == null || source == null) throw new NullPointerException("client read pump argument");
        LogicalStream.validateShardCount(chunks);
        this.slots = slots;
        this.compressed = compressed;
        this.source = source;
        this.lanes = new Lane[chunks + 2];
        for (int s = 0; s < lanes.length; s++) lanes[s] = new Lane(s);
    }

    /**
     * Advances every stream that may read, by at most one read each, and returns the records completed
     * by this call. Each returned unit holds its stream's slot until committed.
     *
     * @throws IOException when the byte source fails
     * @throws MultistreamProtocolException for a record that violates its declaration or does not fit
     *     its stream's slot (close 0x11)
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

    /** Releases a completed unit's slot after the game has executed it. */
    public void commit(Unit unit) {
        if (unit == null || unit.lane.ready != unit) throw new IllegalStateException("unit is not awaiting commit");
        unit.lane.ready = null;
        slots.releaseHead(unit.lane.stream);
    }

    /** Drops partial records and releases every held slot. Idempotent. */
    public void cancel() {
        if (!open) return;
        open = false;
        for (Lane lane : lanes) {
            lane.decoder.cancel();
            lane.ready = null;
            lane.provisional = false;
            lane.admitted = false;
            if (slots.holdsHead(lane.stream)) slots.releaseHead(lane.stream);
        }
    }

    private void pumpLane(Lane lane, List<Unit> done) throws IOException, MultistreamProtocolException {
        if (lane.ready != null) return; // one unit per stream until it commits (13.12)
        if (!lane.provisional && !lane.admitted) {
            // Early bytes need their own slot before any read; a full slot means no read on this stream.
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
        if (got > grant) throw new IllegalStateException("byte source returned more than was requested");
        Record record = lane.decoder.next(ByteBuffer.wrap(target, off, got));
        if (record != null) {
            lane.provisional = false;
            lane.admitted = false;
            lane.ready = new Unit(lane, record);
            done.add(lane.ready);
        }
    }

    /** Replaces the provisional head with the declared record's full wire size (13.12). */
    private boolean declare(Lane lane, long recordLength, byte[] prefix, int prefixLength)
            throws MultistreamProtocolException {
        RecordCodec.inspectPrefix(prefix, prefixLength, recordLength, compressed);
        long wire = (long) lane.decoder.lengthPrefixBytes() + recordLength;
        slots.releaseHead(lane.stream);
        if (!slots.admitHead(lane.stream, wire, 1)) {
            throw new MultistreamProtocolException(Protocol.CLOSE_RESOURCE, "13.12",
                    "record of " + wire + " wire bytes does not fit stream " + lane.stream + "'s slot");
        }
        lane.provisional = false;
        lane.admitted = true;
        return true;
    }

    /** Per-stream state: the decoder, whether a head is admitted, and the unit awaiting commit. */
    private final class Lane {
        final int stream;
        final RecordCodec.CompactDecoder decoder;
        final byte[] lengthByte = new byte[1];
        boolean provisional;
        boolean admitted;
        Unit ready;

        Lane(int stream) {
            this.stream = stream;
            this.decoder = new RecordCodec.CompactDecoder(stream);
            this.decoder.setAdmission((s, recordLength, prefix, prefixLength) ->
                    declare(this, recordLength, prefix, prefixLength));
        }
    }
}
