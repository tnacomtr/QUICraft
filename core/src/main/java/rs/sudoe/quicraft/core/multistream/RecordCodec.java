// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Phase 5 record format (docs/protocol.md 13.4):
 * The legacy fixed-header codec remains below for {@link LaneSession}. New callers use
 * {@link #encodeRecord(Record)} and {@link #decodeRecord(byte[], int)}, which implement the
 * compact /3 format in §13.4.
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

    /** kind (1) + four unsigned 64-bit fields (32). */
    public static final int HEADER_LENGTH = 33;
    public static final int MAX_INNER_VARINT_BYTES = 3;
    /** A three-byte Minecraft VarInt carries at most 2^21 - 1. */
    public static final int MAX_INNER_CONTENT = 2097151;
    public static final int MAX_BODY_LENGTH = MAX_INNER_VARINT_BYTES + MAX_INNER_CONTENT;
    /** 33 header bytes plus at most a three-byte length prefix and 2097151 content bytes. */
    public static final int MAX_RECORD_LENGTH = HEADER_LENGTH + MAX_BODY_LENGTH;

    private static final int CLOSE_CODE = MultistreamProtocolException.CLOSE_ORDERING; // 13.12: parser errors use 0x11
    private static final byte[] NO_BODY = new byte[0];

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
            validateCompactBody(record.streamIndex(), record.kind(), payload);
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
        private final int streamIndex;
        private final VarInt.Reader length = new VarInt.Reader(CLOSE_CODE, "13.4");
        private int recordLength = -1;
        private byte[] frame;
        private int filled;

        /** @param streamIndex the logical index the bytes arrived on, 0..7 (13.3). */
        public CompactDecoder(int streamIndex) {
            if (streamIndex < 0 || streamIndex > 7) throw new IllegalArgumentException("logical stream index");
            this.streamIndex = streamIndex;
        }

        /** Consumes bytes from {@code in} and returns one complete record, or null if more are needed. */
        public rs.sudoe.quicraft.core.multistream.Record next(ByteBuffer in) throws MultistreamProtocolException {
            if (recordLength < 0) {
                while (!length.done()) {
                    if (!in.hasRemaining()) return null;
                    length.feed(in.get() & 0xff);
                }
                int declared = length.value();
                if (declared > COMPACT_MAX_RECORD_LENGTH) {
                    throw compactError("record_length " + declared + " > " + COMPACT_MAX_RECORD_LENGTH);
                }
                if (declared < 4) throw compactError("record_length " + declared + " < 4");
                recordLength = declared;
                frame = new byte[declared];
            }
            int n = Math.min(recordLength - filled, in.remaining());
            in.get(frame, filled, n);
            filled += n;
            if (filled < recordLength) return null;
            rs.sudoe.quicraft.core.multistream.Record record = parseCompact(frame, streamIndex);
            recordLength = -1;
            frame = null;
            filled = 0;
            length.reset();
            return record;
        }
    }

    /** Parses one complete record body (everything after the length prefix), 13.4. */
    private static rs.sudoe.quicraft.core.multistream.Record parseCompact(byte[] frame, int streamIndex)
            throws MultistreamProtocolException {
        int end = frame.length;
        int[] at = {1};
        int kind = frame[0] & 255;
        if (kind > 3) throw compactError("invalid kind " + kind);
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
        byte[] payload = Arrays.copyOfRange(frame, at[0], end);
        validateCompactBody(streamIndex, kind, payload);
        return new rs.sudoe.quicraft.core.multistream.Record(streamIndex, seq, epoch, deps, kind, payload);
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
    private static void validateCompactBody(int streamIndex, int kind, byte[] payload)
            throws MultistreamProtocolException {
        if (kind == KIND_GAME) {
            validateBody(KIND_GAME, payload, true);
        } else if (kind == KIND_END) {
            if (streamIndex != LogicalStream.CONTROL) throw compactError("END outside CONTROL");
            if (payload.length != 0) throw compactError("END body");
        } else if (kind == 2 || kind == 3) {
            if (streamIndex < 2) throw compactError("join outside CHUNK");
            validateBody(KIND_GAME, payload, true);
        } else {
            throw compactError("invalid kind");
        }
    }

    /** One decoded record. The body excludes the fixed header. */
    public static final class Record {
        private final int kind;
        private final long sequence;
        private final long depControl;
        private final long depRealtime;
        private final long depWorld;
        private final byte[] body;

        Record(int kind, long sequence, long depControl, long depRealtime, long depWorld, byte[] body) {
            this.kind = kind;
            this.sequence = sequence;
            this.depControl = depControl;
            this.depRealtime = depRealtime;
            this.depWorld = depWorld;
            this.body = body;
        }

        public int kind() {
            return kind;
        }

        public long sequence() {
            return sequence;
        }

        public long depControl() {
            return depControl;
        }

        public long depRealtime() {
            return depRealtime;
        }

        public long depWorld() {
            return depWorld;
        }

        /** The body: the full Minecraft frame for GAME, empty for END. A fresh copy. */
        public byte[] body() {
            return body.clone();
        }

        public int bodyLength() {
            return body.length;
        }

        /** GAME only: the inner Minecraft frame's declared length. */
        public int gameFrameLength() {
            if (kind != KIND_GAME) {
                throw new IllegalStateException("not a GAME record");
            }
            try {
                return readInnerLength(body);
            } catch (MultistreamProtocolException e) {
                throw new IllegalStateException(e); // a decoded GAME body already passed validation
            }
        }

        /** GAME only: the framed packet content, without the inner VarInt length prefix. */
        public byte[] gameContent() {
            int prefix = innerVarIntBytes(body);
            return Arrays.copyOfRange(body, prefix, body.length);
        }
    }

    // --- Encoding.

    public static byte[] encodeGame(long sequence, long depControl, long depRealtime, long depWorld,
            byte[] gameFrame) {
        return encode(KIND_GAME, sequence, depControl, depRealtime, depWorld, gameFrame, true);
    }

    public static byte[] encodeEnd(long sequence, long depControl, long depRealtime, long depWorld,
            boolean controlStream) {
        return encode(KIND_END, sequence, depControl, depRealtime, depWorld, NO_BODY, controlStream);
    }

    public static byte[] encode(int kind, long sequence, long depControl, long depRealtime, long depWorld,
            byte[] body, boolean controlStream) {
        if (sequence == 0) {
            throw new IllegalArgumentException("record sequence 0");
        }
        byte[] content = body == null ? NO_BODY : body;
        try {
            validateBody(kind, content, controlStream);
        } catch (MultistreamProtocolException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        int recordLength = HEADER_LENGTH + content.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(VarInt.encodedLength(recordLength) + recordLength);
        VarInt.write(out, recordLength);
        out.write(kind);
        writeU64(out, sequence);
        writeU64(out, depControl);
        writeU64(out, depRealtime);
        writeU64(out, depWorld);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    // --- Incremental decoding.

    /**
     * A caller-supplied admission hook (13.12). Before the decoder allocates a record it calls
     * {@link #reserve(int)} with the record's full wire size: the {@code record_length} VarInt
     * prefix bytes plus {@code record_length} (13.4: the 33-byte fixed header plus the body).
     * This is the whole amount the record occupies in the receive buffer, so the caller charges
     * it once and never adds the prefix separately. Returning false refuses the record; the
     * decoder then throws {@code 0x11}. The caller keeps its own connection-wide budget
     * (1024 records / 16 MiB in 13.12) and uses this to refuse rather than evict: a predecessor
     * larger than the remaining budget disconnects.
     */
    @FunctionalInterface
    public interface WireBudget {
        /**
         * @param wireBytes the record's wire size in bytes (length prefix + {@code record_length}),
         *     known from the validated length prefix before any allocation.
         * @return true to admit the record, false to refuse it.
         */
        boolean reserve(int wireBytes) throws MultistreamProtocolException;
    }

    /** Admits every record: the default when the caller has no budget to enforce. */
    public static final WireBudget ADMIT_ALL = wireBytes -> true;

    /**
     * The pull record decoder. {@link #next(ByteBuffer)} consumes bytes from the caller's buffer
     * (advancing its position; the consumed count is the position delta) and returns at most one
     * complete record per call, or null when more bytes are needed. It never decodes more than
     * one record per call, so a caller driving a shared budget (13.12) always sees each record's
     * admission decision before the next one is considered.
     *
     * <p>Before allocating a record it calls the {@link WireBudget} with the record's wire size;
     * a refusal throws {@link MultistreamProtocolException} with {@code 0x11} and nothing is
     * returned (never evict). The current partial record's consumed wire bytes are exposed by
     * {@link #partialWireBytes()} / {@link #hasPartial()}, and {@link #partialStarted()} lets the
     * caller start the incomplete-record timer (13.12) at the record's first byte.
     */
    public static final class Decoder {
        private final boolean controlStream;
        private final WireBudget budget;
        private final VarInt.Reader length = new VarInt.Reader(CLOSE_CODE, "13.4");
        private int recordLength = -1;
        private byte[] frame;
        private int filled;
        private int lengthPrefixBytes;

        /**
         * @param controlStream whether this direction is on the CONTROL stream (stream 0):
         *     only there is an END record legal.
         * @param budget the per-record admission hook (13.12); see {@link WireBudget}.
         *     {@link #ADMIT_ALL} when the caller enforces no budget.
         */
        public Decoder(boolean controlStream, WireBudget budget) {
            this.controlStream = controlStream;
            this.budget = budget == null ? ADMIT_ALL : budget;
        }

        /** Convenience for callers that enforce no per-record budget. */
        public Decoder(boolean controlStream) {
            this(controlStream, ADMIT_ALL);
        }

        /**
         * Consumes bytes from {@code in} and returns one complete record, or null if the buffer
         * is exhausted before a whole record arrives. Only one record is ever returned per call.
         * The consumed byte count is the buffer position delta.
         */
        public Record next(ByteBuffer in) throws MultistreamProtocolException {
            return next(in, false);
        }

        /** Session-only pull mode: a refused allocation leaves the validated header retryable. */
        Record next(ByteBuffer in, boolean withholdOnRefusal) throws MultistreamProtocolException {
            if (recordLength < 0) {
                while (!length.done()) {
                    if (!in.hasRemaining()) {
                        return null;
                    }
                    length.feed(in.get() & 0xff);
                    lengthPrefixBytes++;
                }
                recordLength = length.value();
                if (recordLength < HEADER_LENGTH) {
                    throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                            "record_length " + recordLength + " < 33");
                }
                if (recordLength > MAX_RECORD_LENGTH) {
                    throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                            "record_length " + recordLength + " > " + MAX_RECORD_LENGTH);
                }
            }
            if (frame == null) {
                int wireBytes = lengthPrefixBytes + recordLength;
                if (!budget.reserve(wireBytes)) {
                    if (withholdOnRefusal) {
                        return null;
                    }
                    throw new MultistreamProtocolException(CLOSE_CODE, "13.12",
                            "record of " + wireBytes + " wire bytes refused by the receive budget");
                }
                frame = new byte[recordLength];
                filled = 0;
            }
            int n = Math.min(recordLength - filled, in.remaining());
            in.get(frame, filled, n);
            filled += n;
            if (filled < recordLength) {
                return null;
            }
            Record record = decode(frame, recordLength, controlStream);
            reset();
            return record;
        }

        boolean awaitingAdmission() {
            return recordLength >= 0 && frame == null;
        }

        /** Bytes of the current incomplete record consumed so far (length prefix and header). */
        public int partialWireBytes() {
            return lengthPrefixBytes + filled;
        }

        /** Whether an incomplete record is in progress (some bytes consumed, none returned). */
        public boolean hasPartial() {
            return partialWireBytes() > 0;
        }

        /**
         * True from the first consumed byte of an incomplete record until it completes. The
         * caller starts 13.12's ten-second incomplete-record timer on the first true.
         */
        public boolean partialStarted() {
            return partialWireBytes() > 0;
        }

        private void reset() {
            recordLength = -1;
            frame = null;
            filled = 0;
            lengthPrefixBytes = 0;
            length.reset();
        }

        /**
         * Test helper only: loops {@link #next(ByteBuffer)} until the buffer is exhausted. A
         * production caller must use {@code next()} so it sees one record at a time against its
         * shared budget.
         */
        public List<Record> feed(ByteBuffer data) throws MultistreamProtocolException {
            List<Record> records = new ArrayList<>();
            Record record;
            while ((record = next(data)) != null) {
                records.add(record);
            }
            return records;
        }
    }

    // --- Shared validation and helpers.

    private static Record decode(byte[] frame, int length, boolean controlStream)
            throws MultistreamProtocolException {
        int kind = frame[0] & 0xff;
        if (kind != KIND_GAME && kind != KIND_END) {
            throw new MultistreamProtocolException(CLOSE_CODE, "13.4", "unknown record kind " + kind);
        }
        long sequence = readU64(frame, 1);
        if (sequence == 0) {
            throw new MultistreamProtocolException(CLOSE_CODE, "13.4", "record sequence 0");
        }
        long depControl = readU64(frame, 9);
        long depRealtime = readU64(frame, 17);
        long depWorld = readU64(frame, 25);
        byte[] body = Arrays.copyOfRange(frame, HEADER_LENGTH, length);
        validateBody(kind, body, controlStream);
        return new Record(kind, sequence, depControl, depRealtime, depWorld, body);
    }

    /**
     * Validates one record body against 13.4. Package-private so a sender can validate a whole
     * bundle before it assigns any sequence (LaneSession.checkBundle).
     */
    static void validateBody(int kind, byte[] body, boolean controlStream)
            throws MultistreamProtocolException {
        if (kind == KIND_GAME) {
            if (body.length < 1) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4", "GAME record with empty body");
            }
            int innerLength = readInnerLength(body);
            int prefix = innerVarIntBytes(body);
            if (innerLength < 1) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "GAME inner frame length 0");
            }
            if (innerLength > MAX_INNER_CONTENT) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "GAME inner frame length " + innerLength + " > " + MAX_INNER_CONTENT);
            }
            if (prefix + innerLength != body.length) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "GAME inner frame length " + innerLength + " does not match body length "
                                + (body.length - prefix));
            }
        } else if (kind == KIND_END) {
            if (!controlStream) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "END record on a non-CONTROL stream");
            }
            if (body.length != 0) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "END record with " + body.length + " body bytes");
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
    private static int readInnerLength(byte[] body) throws MultistreamProtocolException {
        VarInt.Reader reader = new VarInt.Reader(CLOSE_CODE, "13.4", false);
        int i = 0;
        while (!reader.done()) {
            if (i >= MAX_INNER_VARINT_BYTES) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                        "inner frame length prefix over 3 bytes");
            }
            if (i >= body.length) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.4", "truncated inner frame length");
            }
            reader.feed(body[i++] & 0xff);
        }
        return reader.value();
    }

    private static int innerVarIntBytes(byte[] body) {
        for (int i = 0; i < MAX_INNER_VARINT_BYTES && i < body.length; i++) {
            if ((body[i] & 0x80) == 0) {
                return i + 1;
            }
        }
        return MAX_INNER_VARINT_BYTES;
    }

    static long readU64(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xffL);
        }
        return value;
    }

    private static void writeU64(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xff));
        }
    }
}
