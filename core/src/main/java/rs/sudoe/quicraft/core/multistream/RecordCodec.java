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
        public static final int PREFIX_WINDOW = COMPACT_MAX_HEADER + 3 * 5;

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
        /** Record kind (0..3). */
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

        Declarations(int kind, int headerLength, int innerLengthBytes, int dataLengthBytes, long dataLength,
                     long decodedLength, long payloadLength) {
            this.kind = kind;
            this.headerLength = headerLength;
            this.innerLengthBytes = innerLengthBytes;
            this.dataLengthBytes = dataLengthBytes;
            this.dataLength = dataLength;
            this.decodedLength = decodedLength;
            this.payloadLength = payloadLength;
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
        if (kind > 3) throw compactError("invalid kind " + kind);
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
            return new Declarations(kind, header, 0, 0, compressed ? 0 : -1, 0, 0);
        }
        if (header >= prefixLength) throw compactError("GAME record without an inner length");
        byte[] body = Arrays.copyOfRange(prefix, header, prefixLength);
        int innerLength = readInnerLength(body, 0, body.length);
        int innerBytes = innerVarIntBytes(body, 0, body.length);
        if (header + innerBytes + (long) innerLength != recordLength) {
            throw compactError("inner frame length " + innerLength + " does not match record_length");
        }
        if (!compressed) {
            return new Declarations(kind, header, innerBytes, 0, -1, innerLength, innerLength);
        }
        VarInt.Reader data = new VarInt.Reader(CLOSE_CODE, "13.4", false);
        int p = innerBytes;
        while (!data.done()) {
            if (p >= body.length || p - innerBytes >= MAX_INNER_VARINT_BYTES + 2) {
                throw compactError("truncated Data Length");
            }
            data.feed(body[p++] & 0xff);
        }
        long dataLength = data.value();
        int dataBytes = p - innerBytes;
        if (innerLength < dataBytes) throw compactError("Data Length longer than the frame");
        long payload = innerLength - dataBytes;
        if (dataLength == 0) {
            return new Declarations(kind, header, innerBytes, dataBytes, 0, payload, payload);
        }
        return new Declarations(kind, header, innerBytes, dataBytes, dataLength, dataLength, payload);
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
                return readInnerLength(body, 0, body.length);
            } catch (MultistreamProtocolException e) {
                throw new IllegalStateException(e); // a decoded GAME body already passed validation
            }
        }

        /** GAME only: the framed packet content, without the inner VarInt length prefix. */
        public byte[] gameContent() {
            int prefix = innerVarIntBytes(body, 0, body.length);
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
        validateBody(kind, body, 0, body.length, controlStream);
    }

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
