// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VarInt} (docs/protocol.md 12.5). The strict reader is used for the new wire
 * fields (record length, stream preambles): minimally encoded, nonnegative, at most five bytes,
 * anything else fatal. The lenient reader is used for Minecraft's own packet lengths, which keep
 * Minecraft's encoding and therefore accept non-minimal encodings within the caller's byte cap.
 */
class VarIntTest {
    private static final int CLOSE = MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION;

    // --- Encoding.

    @Test
    void encodesMinimallyAtEveryWidth() {
        assertArrayEquals(new byte[] {0x00}, VarInt.encode(0));
        assertArrayEquals(new byte[] {0x7F}, VarInt.encode(127));
        assertArrayEquals(new byte[] {(byte) 0x80, 0x01}, VarInt.encode(128));
        assertArrayEquals(new byte[] {(byte) 0x80, (byte) 0x80, 0x01}, VarInt.encode(16384));
        assertArrayEquals(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01}, VarInt.encode(2097152));
        assertArrayEquals(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01},
                VarInt.encode(268435456));
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07},
                VarInt.encode(0x7FFFFFFF));
    }

    @Test
    void encodedLengthMatchesEmittedBytes() {
        int[] values = {0, 1, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456, 0x7FFFFFFF};
        for (int value : values) {
            assertEquals(VarInt.encode(value).length, VarInt.encodedLength(value), "value " + value);
        }
    }

    // --- Strict reading: one to five bytes.

    @Test
    void readsOneToFiveByteValuesStrict() throws Exception {
        assertEquals(0x00, read(CLOSE, true, 0x00));
        assertEquals(0x7F, read(CLOSE, true, 0x7F));
        assertEquals(128, read(CLOSE, true, 0x80, 0x01));
        assertEquals(16384, read(CLOSE, true, 0x80, 0x80, 0x01));
        assertEquals(2097152, read(CLOSE, true, 0x80, 0x80, 0x80, 0x01));
        assertEquals(268435456, read(CLOSE, true, 0x80, 0x80, 0x80, 0x80, 0x01));
    }

    @Test
    void readsMaximumFiveByteValueStrict() throws Exception {
        assertEquals(0x7FFFFFFF, read(CLOSE, true, 0xFF, 0xFF, 0xFF, 0xFF, 0x07));
    }

    // --- Strict rejections.

    @Test
    void rejectsSixthByteStrict() {
        // A nonnegative 32-bit value never needs a continuation on its fifth byte, so the
        // fifth 0x80 is already over the five-byte cap and a sixth byte is never read.
        assertThrows(MultistreamProtocolException.class,
                () -> read(CLOSE, true, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01));
    }

    @Test
    void rejectsOverflowingFifthByteStrict() {
        // The fifth byte may only carry bits 28..30; 0x08 sets bit 31 of the 32-bit value.
        assertThrows(MultistreamProtocolException.class,
                () -> read(CLOSE, true, 0x80, 0x80, 0x80, 0x80, 0x08));
    }

    @Test
    void rejectsNonMinimalEncodingStrict() {
        // 42 minimally is 0x2A; 0xAA 0x00 and 0xAA 0x80 0x00 are non-minimal.
        assertThrows(MultistreamProtocolException.class, () -> read(CLOSE, true, 0xAA, 0x00));
        assertThrows(MultistreamProtocolException.class,
                () -> read(CLOSE, true, 0xAA, 0x80, 0x00));
        // 0x80 0x00 would decode to 0.
        assertThrows(MultistreamProtocolException.class, () -> read(CLOSE, true, 0x80, 0x00));
    }

    // --- Lenient reading: Minecraft's own packet lengths.

    @Test
    void acceptsNonMinimalEncodingLenient() throws Exception {
        assertEquals(42, read(CLOSE, false, 0xAA, 0x00));
        assertEquals(42, read(CLOSE, false, 0xAA, 0x80, 0x00));
        assertEquals(0, read(CLOSE, false, 0x80, 0x00));
    }

    @Test
    void lenientStillEnforcesTheByteCapAndOverflow() {
        assertThrows(MultistreamProtocolException.class,
                () -> read(CLOSE, false, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01));
        assertThrows(MultistreamProtocolException.class,
                () -> read(CLOSE, false, 0x80, 0x80, 0x80, 0x80, 0x08));
    }

    // --- Helpers.

    private static int read(int closeCode, boolean minimal, int... bytes) throws MultistreamProtocolException {
        VarInt.Reader reader = new VarInt.Reader(closeCode, "test", minimal);
        for (int b : bytes) {
            reader.feed(b);
            if (reader.done()) {
                break;
            }
        }
        if (!reader.done()) {
            throw new AssertionError("VarInt incomplete after " + bytes.length + " bytes");
        }
        return reader.value();
    }
}