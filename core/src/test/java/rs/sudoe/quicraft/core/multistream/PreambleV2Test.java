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
 *   <li>no token: {@code VarInt(4)=0x04 || version=0x02 || token_length=0x00 || 1 || 5}</li>
 *   <li>token: {@code VarInt(20)=0x14 || version=0x02 || token_length=0x10 || 16 token bytes || 1 || 5}</li>
 * </ul>
 */
class PreambleV2Test {
    private static final byte[] NO_TOKEN = {0x04, 0x02, 0x00, 0x01, 0x05};
    private static final byte[] TOKEN = {
        0x14, 0x02, 0x10,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 1, 5};

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
        assertEquals(1, parser.minShards());
        assertEquals(5, parser.maxShards());
        assertEquals(3, parser.select(4, 3));
    }

    @Test
    void negotiatesSelectedRangeAndReportsEmptyIntersection() throws Exception {
        PreambleV2.Parser parser = new PreambleV2.Parser();
        parser.feed(ByteBuffer.wrap(PreambleV2.encode(null, 4, 5)));
        assertEquals(4, parser.select(5, 2));
        assertThrows(MultistreamProtocolException.class, () -> parser.select(3, 3));
        assertArrayEquals(new byte[] {0x04, 0x02, 0x00, 0x04, 0x04}, PreambleV2.encode(null, 4, 4));
    }

    /** 13.2: an empty intersection is the typed 0x10 failure, not a clamp outside the offer. */
    @Test
    void emptyIntersectionCloses0x10() throws Exception {
        PreambleV2.Parser parser = new PreambleV2.Parser();
        parser.feed(ByteBuffer.wrap(PreambleV2.encode(null, 5, 5)));
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class, () -> parser.select(4, 4));
        assertEquals(0x10, e.closeCode());
        assertEquals(0x10, MultistreamProtocolException.CLOSE_NO_COMMON_SHARDS);
    }

    /** 13.2: malformed shard ranges on the wire close with 0x1, never with an unchecked exception. */
    @Test
    void malformedWireRangeCloses0x1() {
        int[][] ranges = {{0, 5}, {1, 6}, {4, 3}, {0, 0}};
        for (int[] range : ranges) {
            byte[] wire = {0x04, 0x02, 0x00, (byte) range[0], (byte) range[1]};
            PreambleV2.Parser parser = new PreambleV2.Parser();
            MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                    () -> parser.feed(ByteBuffer.wrap(wire)), "range " + range[0] + ".." + range[1]);
            assertEquals(0x1, e.closeCode());
        }
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
        input[NO_TOKEN.length] = 0x7f;
        input[NO_TOKEN.length + 1] = 0x00;
        input[NO_TOKEN.length + 2] = (byte) 0xaa;
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
