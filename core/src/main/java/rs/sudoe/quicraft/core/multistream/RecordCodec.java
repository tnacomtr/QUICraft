// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The Phase 5 record format (docs/protocol.md 13.4): {@link #encodeRecord(Record)} and
 * {@link #decodeRecord(byte[], int)} implement the compact /3 header and body, and
 * {@link CompactDecoder} decodes the framed records of one stream.
 */
public final class RecordCodec {
    /** Maximum compact /3 header size (§13.4). */
    public static final int COMPACT_MAX_HEADER = 54;
    /** Maximum compact record_length, checked before copying the body (§13.4). */
    public static final int COMPACT_MAX_RECORD_LENGTH = 2097208;
    /** At most N+1 = 7 sparse dependencies per record (13.4). */
    public static final int MAX_COMPACT_DEPENDENCIES = 7;
    public static final int KIND_GAME = 0;
    public static final int KIND_END = 1;
    /** Kind 2 ACK_JOIN: one GAME frame of the designated packet, on a chunk stream (13.4, 13.19). */
    public static final int KIND_ACK_JOIN = 2;
    /** Kind 3 BATCH_JOIN: one GAME frame of the designated packet, on a chunk stream (13.4, 13.19). */
    public static final int KIND_BATCH_JOIN = 3;
    /**
     * Kind 4 BUNDLE_OPEN: clientbound CONTROL only (13.4, 13.15). Its body is VarInt(unit_records) ||
     * VarInt(unit_wire_bytes) || one GAME frame, the opening delimiter. The members and the closer are
     * ordinary kind 0 GAME records that follow it on the same stream.
     */
    public static final int KIND_BUNDLE_OPEN = 4;

    public static final int MAX_INNER_VARINT_BYTES = 3;
    /** A three-byte Minecraft VarInt carries at most 2^21 - 1. */
    public static final int MAX_INNER_CONTENT = 2097151;

    private static final int CLOSE_CODE = MultistreamProtocolException.CLOSE_ORDERING; // 13.12: parser errors use 0x11

    private RecordCodec() {}

    /** Encodes the compact /3 wire record described in §13.4. */
    public static byte[] encodeRecord(rs.sudoe.quicraft.core.multistream.Record record) {
        if (record == null) throw new NullPointerException("record");
        if (record.streamIndex() > 7) throw new IllegalArgumentException("logical stream index");
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(record.kind());
        VarInt.write(header, checked31(record.sequence(), "sequence"));
        VarInt.write(header, checked31(record.epoch(), "epoch"));
        List<rs.sudoe.quicraft.core.multistream.Record.Dependency> deps = record.dependencies();
        if (deps.size() > MAX_COMPACT_DEPENDENCIES) throw new IllegalArgumentException("too many dependencies");
        VarInt.write(header, deps.size());
        int previous = -1;
        for (rs.sudoe.quicraft.core.multistream.Record.Dependency dep : deps) {
            if (dep.streamIndex() > 7 || dep.streamIndex() <= previous || dep.streamIndex() == record.streamIndex())
                throw new IllegalArgumentException("duplicate, unordered or self dependency");
            previous = dep.streamIndex(); header.write(dep.streamIndex());
            VarInt.write(header, checked31(dep.sequence(), "dependency sequence"));
        }
        byte[] payload = record.payload();
        try {
            validateCompactBody(record.streamIndex(), record.kind(), payload, 0, payload.length);
        } catch (MultistreamProtocolException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        int length = header.size() + payload.length;
        if (header.size() > COMPACT_MAX_HEADER || length > COMPACT_MAX_RECORD_LENGTH)
            throw new IllegalArgumentException("compact record too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.write(out, length); out.write(header.toByteArray(), 0, header.size());
        out.write(payload, 0, payload.length); return out.toByteArray();
    }

    /**
     * Encodes a bundle opener (kind 4, 13.4, 13.15). The body is VarInt(unit_records) ||
     * VarInt(unit_wire_bytes) || {@code frame}, where {@code frame} is one complete length-prefixed GAME
     * body (the same bytes a kind 0 record carries). Returns the opener's wire bytes; its own wire size
     * must not exceed {@code unitWireBytes}. Member and closer records are encoded separately.
     */
    public static byte[] encodeBundleOpen(int streamIndex, long sequence, long epoch,
            List<rs.sudoe.quicraft.core.multistream.Record.Dependency> deps, int unitRecords, long unitWireBytes,
            byte[] frame) {
        if (frame == null) throw new NullPointerException("frame");
        if (unitRecords < 2 || unitRecords > ReceiveSlots.RECORDS_PER_STREAM) {
            throw new IllegalArgumentException("unit_records outside 2..128");
        }
        if (unitWireBytes < 0 || unitWireBytes > ReceiveSlots.BYTES_PER_STREAM) {
            throw new IllegalArgumentException("unit_wire_bytes outside 0..B");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        VarInt.write(body, unitRecords);
        VarInt.write(body, (int) unitWireBytes);
        body.write(frame, 0, frame.length);
        byte[] wire = encodeRecord(new rs.sudoe.quicraft.core.multistream.Record(streamIndex, sequence, epoch, deps,
                KIND_BUNDLE_OPEN, body.toByteArray()));
        if (wire.length > unitWireBytes) throw new IllegalArgumentException("opener larger than its unit");
        return wire;
    }

    /**
     * Decodes one complete compact /3 record (13.4). The bytes must be exactly one record: a
     * short, oversize or trailing input is rejected. Delegates to {@link CompactDecoder}, so the
     * same length rules apply as on a stream.
     */
    public static rs.sudoe.quicraft.core.multistream.Record decodeRecord(byte[] wire, int streamIndex)
            throws MultistreamProtocolException {
        if (wire == null) throw new NullPointerException("wire");
        ByteBuffer in = ByteBuffer.wrap(wire);
        rs.sudoe.quicraft.core.multistream.Record record = new CompactDecoder(streamIndex).next(in);
        if (record == null || in.hasRemaining()) throw compactError("record is not exactly one record");
        return record;
    }

    /**
     * Streaming decoder for one direction's compact /3 records (13.4). {@link #next} consumes
     * bytes and returns at most one complete record per call, or null while more bytes are
     * needed. {@code record_length} is checked against {@link #COMPACT_MAX_RECORD_LENGTH} before
     * the record buffer is allocated, so an oversize prefix never allocates.
     */
    public static final class CompactDecoder {
        /** Largest bounded prefix: the 54-byte header plus three at-most-5-byte lengths (13.12). */
        public static final int PREFIX_WINDOW = COMPACT_MAX_HEADER + 4 * 5;

        private final int streamIndex;
        private final VarInt.Reader length = new VarInt.Reader(CLOSE_CODE, "13.4");
        private Admission admission;
        private int recordLength = -1;
        private int lengthBytes;
        private boolean admitted;
        private byte[] prefix;
        private byte[] frame;
        private int filled;
        private int frameAllocations;

        /** @param streamIndex the logical index the bytes arrived on, 0..7 (13.3). */
        public CompactDecoder(int streamIndex) {
            if (streamIndex < 0 || streamIndex > 7) throw new IllegalArgumentException("logical stream index");
            this.streamIndex = streamIndex;
        }

        /** Installs the admission hook. Set it before the first byte; without one, bodies allocate at once. */
        public void setAdmission(Admission admission) {
            if (recordLength >= 0) throw new IllegalStateException("admission set mid-record");
            this.admission = admission;
        }

        /**
         * How many bytes the next {@link #next} call can use: 1 while record_length is incomplete,
         * the remaining bounded prefix before admission, then the remaining body. 0 means the
         * record is held at admission; call {@link #next} with an empty buffer to retry it.
         */
        public int wanted() {
            if (recordLength < 0) return 1;
            if (!admitted) return prefix.length - filled;
            return recordLength - filled;
        }

        /** Bytes the record_length VarInt occupied; valid once it is complete. */
        public int lengthPrefixBytes() {
            return lengthBytes;
        }

        /**
         * The array the next bytes belong in: the bounded prefix before admission, the record frame after.
         * Null while the record_length VarInt is pending. A caller may read straight into it at
         * {@link #stagingOffset()} and pass that same region to {@link #next}, so no read buffer is copied.
         */
        public byte[] stagingArray() {
            if (recordLength < 0) return null;
            return admitted ? frame : prefix;
        }

        /** Offset in {@link #stagingArray()} where the next bytes go. */
        public int stagingOffset() {
            return filled;
        }

        /** True while the length or the bounded prefix is staged and not yet transferred to the frame. */
        public boolean prefixStage() {
            return recordLength < 0 || !admitted;
        }

        /** Record bodies allocated so far. A held or refused declaration allocates none. */
        public int frameAllocations() {
            return frameAllocations;
        }

        /** Drops any partial record and returns to the initial state (connection cleanup). */
        public void cancel() {
            recordLength = -1;
            lengthBytes = 0;
            admitted = false;
            prefix = null;
            frame = null;
            filled = 0;
            length.reset();
        }

        /** Consumes bytes from {@code in} and returns one complete record, or null if more are needed. */
        public rs.sudoe.quicraft.core.multistream.Record next(ByteBuffer in) throws MultistreamProtocolException {
            if (recordLength < 0) {
                while (!length.done()) {
                    if (!in.hasRemaining()) return null;
                    length.feed(in.get() & 0xff);
                    lengthBytes++;
                }
                int declared = length.value();
                if (declared > COMPACT_MAX_RECORD_LENGTH) {
                    throw compactError("record_length " + declared + " > " + COMPACT_MAX_RECORD_LENGTH);
                }
                if (declared < 4) throw compactError("record_length " + declared + " < 4");
                recordLength = declared;
                filled = 0;
                admitted = admission == null;
                if (admitted) {
                    allocateFrame();
                } else {
                    prefix = new byte[Math.min(declared, PREFIX_WINDOW)];
                }
            }
            if (!admitted) {
                int n = Math.min(prefix.length - filled, in.remaining());
                in.get(prefix, filled, n);
                filled += n;
                if (filled < prefix.length) return null;
                // Claim-by-retry: a hold returns before any more bytes are consumed.
                if (!admission.admit(streamIndex, recordLength, prefix, prefix.length)) return null;
                admitted = true;
                allocateFrame();
                System.arraycopy(prefix, 0, frame, 0, prefix.length);
                prefix = null;
            }
            int n = Math.min(recordLength - filled, in.remaining());
            in.get(frame, filled, n);
            filled += n;
            if (filled < recordLength) return null;
            rs.sudoe.quicraft.core.multistream.Record record = parseCompact(frame, streamIndex);
            cancel();
            return record;
        }

        private void allocateFrame() {
            frame = new byte[recordLength];
            frameAllocations++;
        }
    }

    /**
     * Decides whether a declared record may be buffered (13.12). {@link CompactDecoder} calls it once
     * the bounded prefix is complete and before any body allocation or inflation.
     */
    public interface Admission {
        /**
         * @return true to admit the record (its body is allocated next); false to hold it, in which
         *     case the decoder calls again with the same prefix and consumes nothing further
         * @throws MultistreamProtocolException to refuse the record as a protocol error
         */
        boolean admit(int streamIndex, long recordLength, byte[] prefix, int prefixLength)
                throws MultistreamProtocolException;
    }

    /** What a record's bounded prefix declares, read before its body is allocated (13.12). */
    public static final class Declarations {
        /** Record kind (0..4). */
        public final int kind;
        /** Compact header length, through the dependency list. */
        public final int headerLength;
        /** Bytes of the inner length VarInt, 0 for END. The packet content starts after it. */
        public final int innerLengthBytes;
        /** Bytes of the Data Length VarInt, 0 when compression is disabled or the record is not GAME. */
        public final int dataLengthBytes;
        /** Declared Data Length, or -1 when compression is disabled; 0 means stored (uncompressed). */
        public final long dataLength;
        /** Decoded packet bytes this record can produce: the footprint bound before inflation. */
        public final long decodedLength;
        /** Bytes after the Data Length prefix: the zlib input when compressed, else the packet. */
        public final long payloadLength;
        /** KIND_BUNDLE_OPEN only: the declared unit_records, delimiters included; 0 otherwise (13.4). */
        public final long unitRecords;
        /** KIND_BUNDLE_OPEN only: the declared unit_wire_bytes, prefixes included; 0 otherwise (13.4). */
        public final long unitWireBytes;

        Declarations(int kind, int headerLength, int innerLengthBytes, int dataLengthBytes, long dataLength,
                     long decodedLength, long payloadLength, long unitRecords, long unitWireBytes) {
            this.kind = kind;
            this.headerLength = headerLength;
            this.innerLengthBytes = innerLengthBytes;
            this.dataLengthBytes = dataLengthBytes;
            this.dataLength = dataLength;
            this.decodedLength = decodedLength;
            this.payloadLength = payloadLength;
            this.unitRecords = unitRecords;
            this.unitWireBytes = unitWireBytes;
        }
    }

    /**
     * Reads a record's declarations from its bounded prefix (13.12): the compact envelope, the inner
     * length and, when {@code compressed}, the Data Length. Nothing is inflated and nothing beyond the
     * prefix is read. The total length must match {@code recordLength} exactly.
     *
     * @param prefix the first {@code prefixLength} bytes of the record body (at most PREFIX_WINDOW)
     * @param recordLength the record_length as declared
     * @throws MultistreamProtocolException for an envelope that does not match its declared length
     */
    public static Declarations inspectPrefix(byte[] prefix, int prefixLength, long recordLength, boolean compressed)
            throws MultistreamProtocolException {
        if (prefix == null || prefixLength < 1 || prefixLength > prefix.length) {
            throw compactError("prefix length");
        }
        int[] at = {1};
        int kind = prefix[0] & 255;
        if (kind > KIND_BUNDLE_OPEN) throw compactError("invalid kind " + kind);
        readCompact(prefix, prefixLength, at);
        readCompact(prefix, prefixLength, at);
        long count = readCompact(prefix, prefixLength, at);
        if (count > MAX_COMPACT_DEPENDENCIES) throw compactError("dependency count " + count);
        for (int i = 0; i < count; i++) {
            if (at[0] >= prefixLength) throw compactError("truncated dependency");
            at[0]++;
            readCompact(prefix, prefixLength, at);
        }
        int header = at[0];
        if (header > COMPACT_MAX_HEADER) throw compactError("header over " + COMPACT_MAX_HEADER + " bytes");
        if (kind == KIND_END) {
            if (recordLength != header) throw compactError("END length does not match its header");
            return new Declarations(kind, header, 0, 0, compressed ? 0 : -1, 0, 0, 0, 0);
        }
        // A bundle opener carries its unit fields before the frame (13.4); both are bounded here, before admission.
        int frame = header;
        long unitRecords = 0;
        long unitWireBytes = 0;
        if (kind == KIND_BUNDLE_OPEN) {
            long[] unit = readBundleFields(prefix, header, prefixLength);
            if (unit[0] < 2 || unit[0] > ReceiveSlots.RECORDS_PER_STREAM) {
                throw compactError("unit_records " + unit[0] + " outside 2..128");
            }
            if (unit[1] > ReceiveSlots.BYTES_PER_STREAM) {
                throw compactError("unit_wire_bytes " + unit[1] + " over B");
            }
            unitRecords = unit[0];
            unitWireBytes = unit[1];
            frame = header + (int) unit[2];
        }
        if (frame >= prefixLength) throw compactError("GAME record without an inner length");
        // The inner length and Data Length are read in place from the prefix at offset `frame`: no copy
        // of the body is made, so the staged prefix is the only bytes this method touches (13.12).
        int bodyLength = prefixLength - frame;
        int innerLength = readInnerLength(prefix, frame, bodyLength);
        int innerBytes = innerVarIntBytes(prefix, frame, bodyLength);
        if (frame + innerBytes + (long) innerLength != recordLength) {
            throw compactError("inner frame length " + innerLength + " does not match record_length");
        }
        if (!compressed) {
            return new Declarations(kind, header, innerBytes, 0, -1, innerLength, innerLength,
                    unitRecords, unitWireBytes);
        }
        VarInt.Reader data = new VarInt.Reader(CLOSE_CODE, "13.4", false);
        int p = innerBytes;
        while (!data.done()) {
            if (p >= bodyLength || p - innerBytes >= MAX_INNER_VARINT_BYTES + 2) {
                throw compactError("truncated Data Length");
            }
            data.feed(prefix[frame + p++] & 0xff);
        }
        long dataLength = data.value();
        int dataBytes = p - innerBytes;
        if (innerLength < dataBytes) throw compactError("Data Length longer than the frame");
        long payload = innerLength - dataBytes;
        if (dataLength == 0) {
            return new Declarations(kind, header, innerBytes, dataBytes, 0, payload, payload,
                    unitRecords, unitWireBytes);
        }
        return new Declarations(kind, header, innerBytes, dataBytes, dataLength, dataLength, payload,
                unitRecords, unitWireBytes);
    }

    /** Reads a bundle opener's unit_records and unit_wire_bytes (13.4): {records, bytes, bytes consumed}. */
    static long[] readBundleFields(byte[] b, int off, int end) throws MultistreamProtocolException {
        int[] at = {off};
        long records = readCompact(b, end, at);
        long bytes = readCompact(b, end, at);
        return new long[] {records, bytes, at[0] - off};
    }

    /** Parses one complete record body (everything after the length prefix), 13.4. */
    private static rs.sudoe.quicraft.core.multistream.Record parseCompact(byte[] frame, int streamIndex)
            throws MultistreamProtocolException {
        int end = frame.length;
        int[] at = {1};
        int kind = frame[0] & 255;
        if (kind > KIND_BUNDLE_OPEN) throw compactError("invalid kind " + kind);
        long seq = readCompact(frame, end, at);
        long epoch = readCompact(frame, end, at);
        long countLong = readCompact(frame, end, at);
        if (seq == 0) throw compactError("record sequence 0");
        if (countLong > MAX_COMPACT_DEPENDENCIES) throw compactError("dependency count " + countLong);
        int count = (int) countLong, previous = -1;
        List<rs.sudoe.quicraft.core.multistream.Record.Dependency> deps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (at[0] >= end) throw compactError("truncated dependency");
            int index = frame[at[0]++] & 255;
            long dseq = readCompact(frame, end, at);
            if (index > 7 || index <= previous || index == streamIndex || dseq == 0) {
                throw compactError("invalid dependency");
            }
            previous = index;
            deps.add(new rs.sudoe.quicraft.core.multistream.Record.Dependency(index, dseq));
        }
        if (at[0] > COMPACT_MAX_HEADER) throw compactError("header over " + COMPACT_MAX_HEADER + " bytes");
        // The payload stays in the frame (13.12): no second copy of the record is made.
        int offset = at[0];
        validateCompactBody(streamIndex, kind, frame, offset, end - offset);
        return rs.sudoe.quicraft.core.multistream.Record.adopt(streamIndex, seq, epoch, deps, kind, frame, offset,
                end - offset);
    }

    private static int checked31(long value, String name) {
        if (value < 0 || value > 0x7fffffffL) throw new IllegalArgumentException(name + " outside VarInt range");
        return (int)value;
    }
    private static long readCompact(byte[] b, int end, int[] at) throws MultistreamProtocolException {
        VarInt.Reader r = new VarInt.Reader(CLOSE_CODE,"13.4");
        while (!r.done()) { if (at[0] >= end) throw compactError("truncated header"); r.feed(b[at[0]++] & 255); }
        return r.value();
    }
    private static MultistreamProtocolException compactError(String message) {
        return new MultistreamProtocolException(CLOSE_CODE,"13.4",message);
    }

    /** Validates compact record kind placement and its §13.4 body form. */
    private static void validateCompactBody(int streamIndex, int kind, byte[] payload, int off, int len)
            throws MultistreamProtocolException {
        if (kind == KIND_GAME) {
            validateBody(KIND_GAME, payload, off, len, true);
        } else if (kind == KIND_END) {
            if (streamIndex != LogicalStream.CONTROL) throw compactError("END outside CONTROL");
            if (len != 0) throw compactError("END body");
        } else if (kind == 2 || kind == 3) {
            // 13.19: joins live on CHUNK[0] only.
            if (streamIndex != 2) throw compactError("join outside CHUNK[0]");
            validateBody(KIND_GAME, payload, off, len, true);
        } else if (kind == KIND_BUNDLE_OPEN) {
            // 13.4, 13.15: clientbound CONTROL only; the direction is enforced by the receivers.
            if (streamIndex != LogicalStream.CONTROL) throw compactError("bundle open outside CONTROL");
            long[] unit = readBundleFields(payload, off, off + len);
            if (unit[0] < 2 || unit[0] > ReceiveSlots.RECORDS_PER_STREAM) {
                throw compactError("unit_records " + unit[0] + " outside 2..128");
            }
            if (unit[1] > ReceiveSlots.BYTES_PER_STREAM) throw compactError("unit_wire_bytes " + unit[1] + " over B");
            validateBody(KIND_GAME, payload, off + (int) unit[2], len - (int) unit[2], true);
        } else {
            throw compactError("invalid kind");
        }
    }

    // --- Shared validation and helpers.

    /** Validates the body bytes {@code body[off, off+len)} in place (13.4). */
    static void validateBody(int kind, byte[] body, int off, int len, boolean controlStream)
            throws MultistreamProtocolException {
        if (kind == KIND_GAME) {
            if (len < 1) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4", "GAME record with empty body");
            }
            int innerLength = readInnerLength(body, off, len);
            int prefix = innerVarIntBytes(body, off, len);
            if (innerLength < 1) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "GAME inner frame length 0");
            }
            if (innerLength > MAX_INNER_CONTENT) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "GAME inner frame length " + innerLength + " > " + MAX_INNER_CONTENT);
            }
            if (prefix + innerLength != len) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "GAME inner frame length " + innerLength + " does not match body length "
                                + (len - prefix));
            }
        } else if (kind == KIND_END) {
            if (!controlStream) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "END record on a non-CONTROL stream");
            }
            if (len != 0) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "END record with " + len + " body bytes");
            }
        } else {
            throw new MultistreamProtocolException(CLOSE_CODE, "13.4", "unknown record kind " + kind);
        }
    }

    /**
     * The inner Minecraft VarInt value; at most three bytes (13.4). It uses Minecraft's own
     * encoding, which accepts non-minimal encodings within the cap (12.5 keeps Minecraft's
     * packet lengths unchanged), so the reader is lenient here; 13.4 still caps it at three
     * bytes and the value must be nonnegative.
     */
    private static int readInnerLength(byte[] body, int off, int len) throws MultistreamProtocolException {
        VarInt.Reader reader = new VarInt.Reader(CLOSE_CODE, "13.4", false);
        int i = 0;
        while (!reader.done()) {
            if (i >= MAX_INNER_VARINT_BYTES) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "inner frame length prefix over 3 bytes");
            }
            if (i >= len) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4", "truncated inner frame length");
            }
            reader.feed(body[off + i++] & 0xff);
        }
        return reader.value();
    }

    private static int innerVarIntBytes(byte[] body, int off, int len) {
        for (int i = 0; i < MAX_INNER_VARINT_BYTES && i < len; i++) {
            if ((body[off + i] & 0x80) == 0) {
                return i + 1;
            }
        }
        return MAX_INNER_VARINT_BYTES;
    }
}
