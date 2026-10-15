// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.ByteArrayOutputStream;

/**
 * Minecraft VarInt (little-endian, 7 bits a byte). docs/protocol.md §12.5: new VarInts in
 * §12/§13 MUST be minimally encoded, nonnegative and at most five bytes; anything else is
 * fatal. Used for the stream preambles (13.2) and the record length (13.4).
 */
final class VarInt {
    static final int MAX_BYTES = 5;

    private VarInt() {}

    /** The number of bytes {@link #write} would emit for {@code value}. */
    static int encodedLength(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative VarInt " + value);
        }
        int length = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            remaining >>>= 7;
            length++;
        }
        return length;
    }

    static void write(ByteArrayOutputStream out, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative VarInt " + value);
        }
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static byte[] encode(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(encodedLength(value));
        write(out, value);
        return out.toByteArray();
    }

    /**
     * Incremental reader: feed one unsigned byte at a time, across as many reads as it takes.
     * Rejects a sixth byte, an overflowing or otherwise non-canonical encoding.
     */
    static final class Reader {
        private final int closeCode;
        private final String rule;
        private int value;
        private int count;
        private boolean done;

        Reader(int closeCode, String rule) {
            this.closeCode = closeCode;
            this.rule = rule;
        }

        /** Returns true once the value is complete; then {@link #value()} is valid. */
        boolean feed(int unsignedByte) throws MultistreamProtocolException {
            if (done) {
                throw new IllegalStateException("VarInt already complete");
            }
            count++;
            if (count > MAX_BYTES) {
                throw new MultistreamProtocolException(closeCode, rule, "VarInt over 5 bytes");
            }
            if (count == MAX_BYTES && (unsignedByte & 0xF8) != 0) {
                // The 5th byte may only carry bits 28..30 of a nonnegative 32-bit value.
                throw new MultistreamProtocolException(closeCode, rule, "VarInt overflow");
            }
            value |= (unsignedByte & 0x7F) << (7 * (count - 1));
            if ((unsignedByte & 0x80) == 0) {
                if (count > 1 && unsignedByte == 0) {
                    throw new MultistreamProtocolException(closeCode, rule, "non-minimal VarInt");
                }
                done = true;
                return true;
            }
            return false;
        }

        boolean done() {
            return done;
        }

        int value() {
            return value;
        }

        int bytesRead() {
            return count;
        }

        void reset() {
            value = 0;
            count = 0;
            done = false;
        }
    }
}
