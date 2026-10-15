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
     * The incremental record decoder. Feed arbitrary chunks; it returns zero or more complete
     * records and keeps a partial one. It rejects a partial record whose declared length would
     * make the buffered bytes exceed {@code maxBufferedBytes} before allocating its body, so
     * the caller's memory budget (13.12 counts partial records) is enforced.
     */
    public static final class Decoder {
        private final boolean controlStream;
        private final int maxBufferedBytes;
        private final VarInt.Reader length = new VarInt.Reader(CLOSE_CODE, "13.4");
        private int recordLength = -1;
        private byte[] frame;
        private int filled;
        private int lengthPrefixBytes;

        /**
         * @param controlStream whether this direction is on the CONTROL stream (stream 0):
         *     only there is an END record legal.
         * @param maxBufferedBytes the most bytes a single partial record may retain
         *     (length prefix included); a larger declared record is rejected before its body
         *     is allocated.
         */
        public Decoder(boolean controlStream, int maxBufferedBytes) {
            this.controlStream = controlStream;
            this.maxBufferedBytes = maxBufferedBytes;
        }

        public List<Record> feed(ByteBuffer data) throws MultistreamProtocolException {
            List<Record> records = new ArrayList<>();
            while (true) {
                if (recordLength < 0) {
                    while (!length.done()) {
                        if (!data.hasRemaining()) {
                            return records;
                        }
                        length.feed(data.get() & 0xff);
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
                    if ((long) lengthPrefixBytes + recordLength > maxBufferedBytes) {
                        throw new MultistreamProtocolException(CLOSE_CODE, "13.4",
                                "record of " + recordLength + " bytes exceeds the buffered-bytes limit "
                                        + maxBufferedBytes);
                    }
                    frame = new byte[recordLength];
                    filled = 0;
                }
                int n = Math.min(recordLength - filled, data.remaining());
                data.get(frame, filled, n);
                filled += n;
                if (filled < recordLength) {
                    return records;
                }
                records.add(decode(frame, recordLength, controlStream));
                recordLength = -1;
                frame = null;
                filled = 0;
                lengthPrefixBytes = 0;
                length.reset();
                if (!data.hasRemaining()) {
                    return records;
                }
            }
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

    private static void validateBody(int kind, byte[] body, boolean controlStream)
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

    /** The inner Minecraft VarInt value; at most three bytes, minimally encoded, nonnegative. */
    private static int readInnerLength(byte[] body) throws MultistreamProtocolException {
        VarInt.Reader reader = new VarInt.Reader(CLOSE_CODE, "13.4");
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
