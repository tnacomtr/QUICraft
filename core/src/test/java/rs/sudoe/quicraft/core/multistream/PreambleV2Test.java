// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/**
 * Wire tests for {@link PreambleV2} (docs/protocol.md 13.2).
 *
 * <p>Golden vectors, hand-derived:
 * <ul>
 *   <li>no token: {@code VarInt(2)=0x02 || version=0x02 || token_length=0x00}</li>
 *   <li>token: {@code VarInt(18)=0x12 || version=0x02 || token_length=0x10 || 16 token bytes}</li>
 * </ul>
 */
class PreambleV2Test {
    private static final byte[] NO_TOKEN = {0x02, 0x02, 0x00};
    private static final byte[] TOKEN = {
        (byte) 0x12, 0x02, 0x10,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    private static byte[] tokenBytes() {
        byte[] token = new byte[16];
        for (int i = 0; i < 16; i++) {
            token[i] = (byte) i;
        }
        return token;
    }

    @Test
    void encodesGoldenNoToken() {
        assertArrayEquals(NO_TOKEN, PreambleV2.encode(null));
        assertArrayEquals(NO_TOKEN, PreambleV2.encode(new byte[0]));
    }

    @Test
    void encodesGoldenToken() {
        assertArrayEquals(TOKEN, PreambleV2.encode(tokenBytes()));
    }

    @Test
    void encodeRejectsOtherTokenLengths() {
        assertThrows(IllegalArgumentException.class, () -> PreambleV2.encode(new byte[15]));
    }

    @Test
    void parsesGoldenNoToken() throws Exception {
        PreambleV2.Parser parser = new PreambleV2.Parser();
        ByteBuffer data = ByteBuffer.wrap(NO_TOKEN);
        assertTrue(parser.feed(data));
        assertTrue(parser.done());
        assertNull(parser.token());
        assertEquals(NO_TOKEN.length, parser.consumed());
        assertEquals(0, data.remaining());
    }

    @Test
    void parsesGoldenToken() throws Exception {
        PreambleV2.Parser parser = new PreambleV2.Parser();
        ByteBuffer data = ByteBuffer.wrap(TOKEN);
        assertTrue(parser.feed(data));
        assertArrayEquals(tokenBytes(), parser.token());
        assertEquals(TOKEN.length, parser.consumed());
    }

    @Test
    void leavesFollowingBytesWithTheCaller() throws Exception {
        byte[] input = new byte[NO_TOKEN.length + 3];
        System.arraycopy(NO_TOKEN, 0, input, 0, NO_TOKEN.length);
        input[3] = 0x7f;
        input[4] = 0x00;
        input[5] = (byte) 0xaa;
        PreambleV2.Parser parser = new PreambleV2.Parser();
        ByteBuffer data = ByteBuffer.wrap(input);
        assertTrue(parser.feed(data));
        assertEquals(3, data.remaining());
        assertEquals(0x7f, data.get() & 0xff);
    }

    @Test
    void consumesNothingAfterCompletion() throws Exception {
        PreambleV2.Parser parser = new PreambleV2.Parser();
        assertTrue(parser.feed(ByteBuffer.wrap(NO_TOKEN)));
        ByteBuffer more = ByteBuffer.wrap(new byte[] {1, 2, 3});
        assertTrue(parser.feed(more));
        assertEquals(3, more.remaining());
    }

    @Test
    void parsesSplitAtEveryBoundary() throws Exception {
        for (byte[] golden : new byte[][] {NO_TOKEN, TOKEN}) {
            for (int split = 0; split <= golden.length; split++) {
                PreambleV2.Parser parser = new PreambleV2.Parser();
                ByteBuffer first = ByteBuffer.wrap(golden, 0, split);
                assertEquals(split == golden.length, parser.feed(first),
                        "split " + split + " of length " + golden.length);
                ByteBuffer rest = ByteBuffer.wrap(golden, split, golden.length - split);
                assertTrue(parser.feed(rest), "rest after split " + split);
                assertEquals(golden.length, parser.consumed());
            }
        }
    }

    @Test
    void parsesByteByByte() throws Exception {
        PreambleV2.Parser parser = new PreambleV2.Parser();
        for (int i = 0; i < TOKEN.length; i++) {
            ByteBuffer one = ByteBuffer.wrap(TOKEN, i, 1);
            boolean complete = parser.feed(one);
            assertEquals(i == TOKEN.length - 1, complete, "byte " + i);
            assertFalse(parser.done() && !complete);
        }
        assertArrayEquals(tokenBytes(), parser.token());
    }

    @Test
    void rejectsLength1() {
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                new byte[] {0x01, 0x02, 0x00});
    }

    @Test
    void rejectsLength3() {
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                new byte[] {0x03, 0x02, 0x00});
    }

    @Test
    void rejectsLength17() {
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                new byte[] {0x11, 0x02, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    }

    @Test
    void rejectsVersion1() {
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                new byte[] {0x02, 0x01, 0x00});
    }

    @Test
    void rejectsTokenLengthInconsistentWithLength() {
        // length 2 but token length 16.
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                new byte[] {0x02, 0x02, 0x10});
        // length 18 but token length 0.
        byte[] eighteenZero = new byte[18 + 1];
        eighteenZero[0] = 0x12;
        eighteenZero[1] = 0x02;
        eighteenZero[2] = 0x00;
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, eighteenZero);
        // token length 17 is never legal.
        byte[] seventeen = new byte[18 + 1];
        seventeen[0] = 0x12;
        seventeen[1] = 0x02;
        seventeen[2] = 0x11;
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, seventeen);
    }

    @Test
    void rejectsNonCanonicalLengthVarInt() {
        // 2 encoded as 0x82 0x00 (two bytes) instead of 0x02.
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                new byte[] {(byte) 0x82, 0x00, 0x02, 0x00});
    }

    @Test
    void rejectsOverlongLengthVarInt() {
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f});
        // Five continuation bytes then a sixth byte.
        assertClose(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION,
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00});
    }

    private static void assertClose(int code, byte[] bytes) {
        PreambleV2.Parser parser = new PreambleV2.Parser();
        MultistreamProtocolException e =
                assertThrows(MultistreamProtocolException.class, () -> parser.feed(ByteBuffer.wrap(bytes)));
        assertEquals(code, e.closeCode());
    }
}
