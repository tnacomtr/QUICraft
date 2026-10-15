// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Phase 5 record format (docs/protocol.md 13.4):
 * {@code VarInt(record_length) || u8(kind) || u64(sequence) || u64(dep_control) ||
 * u64(dep_realtime) || u64(dep_world) || body}. The length excludes its own VarInt and
 * includes the 33-byte fixed header. Kind 0 GAME carries exactly one Minecraft VarInt-length
 * frame; kind 1 END is empty and CONTROL-only. u64 fields are unsigned and compared with
 * {@link Long#compareUnsigned}.
 */
public final class RecordCodec {
    public static final int KIND_GAME = 0;
    public static final int KIND_END = 1;

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
                int wireBytes = lengthPrefixBytes + recordLength;
                if (!budget.reserve(wireBytes)) {
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
