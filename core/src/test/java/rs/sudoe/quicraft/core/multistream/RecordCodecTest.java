// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wire tests for {@link RecordCodec} (docs/protocol.md 13.4).
 *
 * <p>Golden vectors, hand-derived. GAME record with sequence 1, all dependencies 0, and a
 * Minecraft frame of length 4 (content {@code 00 01 02 03}):
 * <pre>
 *   0x26              record_length 38 = 33 header + 5 body
 *   0x00              kind GAME
 *   00..01            sequence 1 (u64 big-endian)
 *   00*8, 00*8, 00*8  dep_control, dep_realtime, dep_world = 0
 *   04 00 01 02 03    body: inner VarInt length 4, content 4 bytes
 * </pre>
 * END record with sequence 5 on CONTROL: {@code record_length 33 = 0x21, kind 0x01, sequence 5,
 * zero dependencies, empty body}.
 */
class RecordCodecTest {
    private static final byte[] GAME_GOLDEN = {
        0x26, // record_length 38
        0x00, // kind GAME
        0, 0, 0, 0, 0, 0, 0, 1, // sequence 1
        0, 0, 0, 0, 0, 0, 0, 0, // dep_control 0
        0, 0, 0, 0, 0, 0, 0, 0, // dep_realtime 0
        0, 0, 0, 0, 0, 0, 0, 0, // dep_world 0
        0x04, 0x00, 0x01, 0x02, 0x03 // body frame
    };

    private static final byte[] END_GOLDEN = {
        0x21, // record_length 33
        0x01, // kind END
        0, 0, 0, 0, 0, 0, 0, 5, // sequence 5
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0
    };

    private static final byte[] GAME_FRAME = {0x04, 0x00, 0x01, 0x02, 0x03};

    private static RecordCodec.Decoder decoder(boolean control) {
        return new RecordCodec.Decoder(control, RecordCodec.ADMIT_ALL);
    }

    private static RecordCodec.Decoder decoder(boolean control, RecordCodec.BodyBudget budget) {
        return new RecordCodec.Decoder(control, budget);
    }

    @Test
    void encodesGoldenGame() {
        assertArrayEquals(GAME_GOLDEN, RecordCodec.encodeGame(1, 0, 0, 0, GAME_FRAME));
    }

    @Test
    void encodesGoldenEnd() {
        assertArrayEquals(END_GOLDEN, RecordCodec.encodeEnd(5, 0, 0, 0, true));
    }

    @Test
    void decodesGoldenGame() throws Exception {
        List<RecordCodec.Record> records = decoder(true).feed(ByteBuffer.wrap(GAME_GOLDEN));
        assertEquals(1, records.size());
        RecordCodec.Record record = records.get(0);
        assertEquals(RecordCodec.KIND_GAME, record.kind());
        assertEquals(1, record.sequence());
        assertEquals(0, record.depControl());
        assertEquals(0, record.depRealtime());
        assertEquals(0, record.depWorld());
        assertEquals(5, record.bodyLength());
        assertEquals(4, record.gameFrameLength());
        assertArrayEquals(new byte[] {0x00, 0x01, 0x02, 0x03}, record.gameContent());
        assertArrayEquals(GAME_FRAME, record.body());
    }

    @Test
    void decodesGoldenEndOnControl() throws Exception {
        List<RecordCodec.Record> records = decoder(true).feed(ByteBuffer.wrap(END_GOLDEN));
        assertEquals(1, records.size());
        assertEquals(RecordCodec.KIND_END, records.get(0).kind());
        assertEquals(5, records.get(0).sequence());
        assertEquals(0, records.get(0).bodyLength());
    }

    @Test
    void roundTripsDependenciesAndSequenceBigEndian() throws Exception {
        byte[] frame = {0x01, 0x2a};
        byte[] encoded = RecordCodec.encodeGame(0x0102030405060708L, 9, 8, 7, frame);
        // sequence bytes are big-endian.
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
                java.util.Arrays.copyOfRange(encoded, 2, 10));
        RecordCodec.Record record = decoder(false).feed(ByteBuffer.wrap(encoded)).get(0);
        assertEquals(0x0102030405060708L, record.sequence());
        assertEquals(9, record.depControl());
        assertEquals(8, record.depRealtime());
        assertEquals(7, record.depWorld());
        assertArrayEquals(frame, record.body());
    }

    @Test
    void acceptsSequenceWithHighBitSet() throws Exception {
        long sequence = 0x8000000000000000L;
        byte[] encoded = RecordCodec.encodeGame(sequence, 0, 0, 0, GAME_FRAME);
        RecordCodec.Record record = decoder(false).feed(ByteBuffer.wrap(encoded)).get(0);
        assertEquals(sequence, record.sequence());
    }

    @Test
    void splitsAtEveryBoundary() throws Exception {
        for (int split = 0; split <= GAME_GOLDEN.length; split++) {
            RecordCodec.Decoder d = decoder(true);
            List<RecordCodec.Record> first =
                    d.feed(ByteBuffer.wrap(GAME_GOLDEN, 0, split));
            assertEquals(split == GAME_GOLDEN.length ? 1 : 0, first.size(), "split " + split);
            List<RecordCodec.Record> rest = d.feed(
                    ByteBuffer.wrap(GAME_GOLDEN, split, GAME_GOLDEN.length - split));
            assertEquals(split == GAME_GOLDEN.length ? 0 : 1, rest.size(), "rest after split " + split);
        }
    }

    @Test
    void parsesByteByByte() throws Exception {
        RecordCodec.Decoder d = decoder(true);
        for (int i = 0; i < END_GOLDEN.length; i++) {
            List<RecordCodec.Record> records = d.feed(ByteBuffer.wrap(END_GOLDEN, i, 1));
            assertEquals(i == END_GOLDEN.length - 1 ? 1 : 0, records.size(), "byte " + i);
        }
    }

    @Test
    void decodesSeveralRecordsInOneFeed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(GAME_GOLDEN, 0, GAME_GOLDEN.length);
        out.write(END_GOLDEN, 0, END_GOLDEN.length);
        List<RecordCodec.Record> records = decoder(true).feed(ByteBuffer.wrap(out.toByteArray()));
        assertEquals(2, records.size());
        assertEquals(RecordCodec.KIND_GAME, records.get(0).kind());
        assertEquals(RecordCodec.KIND_END, records.get(1).kind());
    }

    @Test
    void keepsPartialRecordAcrossFeeds() throws Exception {
        RecordCodec.Decoder d = decoder(true);
        assertTrue(d.feed(ByteBuffer.wrap(GAME_GOLDEN, 0, 3)).isEmpty());
        assertEquals(1, d.feed(ByteBuffer.wrap(GAME_GOLDEN, 3, GAME_GOLDEN.length - 3)).size());
    }

    // --- Pull decoding (next): one record per call, consumed counts and partial accounting.

    @Test
    void nextReturnsOneRecordPerCallWithCorrectConsumedCounts() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(GAME_GOLDEN, 0, GAME_GOLDEN.length);
        out.write(END_GOLDEN, 0, END_GOLDEN.length);
        out.write(GAME_GOLDEN, 0, GAME_GOLDEN.length);
        byte[] all = out.toByteArray();
        ByteBuffer in = ByteBuffer.wrap(all);
        RecordCodec.Decoder d = decoder(true);

        RecordCodec.Record first = d.next(in);
        assertEquals(RecordCodec.KIND_GAME, first.kind());
        assertEquals(GAME_GOLDEN.length, in.position(), "one record consumed, no lookahead");

        RecordCodec.Record second = d.next(in);
        assertEquals(RecordCodec.KIND_END, second.kind());
        assertEquals(GAME_GOLDEN.length + END_GOLDEN.length, in.position());

        RecordCodec.Record third = d.next(in);
        assertEquals(RecordCodec.KIND_GAME, third.kind());
        assertEquals(all.length, in.position());

        assertNull(d.next(in), "no record left");
        assertFalse(d.hasPartial());
        assertEquals(0, d.partialWireBytes());
    }

    @Test
    void partialWireBytesCountsIncompleteLengthPrefixAndHeader() throws Exception {
        // VarInt(166) = A6 01, a two-byte prefix; body is 166 - 33 = 133 bytes.
        RecordCodec.Decoder d = decoder(true);
        assertNull(d.next(ByteBuffer.wrap(new byte[] {(byte) 0xA6})));
        assertEquals(1, d.partialWireBytes(), "one prefix byte consumed");
        assertTrue(d.hasPartial());
        assertTrue(d.partialStarted(), "timer starts at the first byte");
        assertNull(d.next(ByteBuffer.wrap(new byte[] {0x01})));
        assertEquals(2, d.partialWireBytes(), "prefix complete, body still missing");
        assertTrue(d.hasPartial());

        // Once body bytes arrive they count too: prefix (1) + 4 header bytes.
        RecordCodec.Decoder g = decoder(true);
        assertNull(g.next(ByteBuffer.wrap(GAME_GOLDEN, 0, 5)));
        assertEquals(5, g.partialWireBytes());
        assertTrue(g.hasPartial());
        assertEquals(1, g.feed(ByteBuffer.wrap(GAME_GOLDEN, 5, GAME_GOLDEN.length - 5)).size());
        assertFalse(g.hasPartial());
        assertEquals(0, g.partialWireBytes());
    }

    // --- Rejections.

    @Test
    void rejectsUnknownKind() {
        assertClose(raw(2, 1, new byte[0]), decoder(true));
    }

    @Test
    void rejectsRecordLengthBelowHeader() {
        // VarInt(32) alone is enough: record_length < 33.
        assertClose(new byte[] {0x20}, decoder(true));
    }

    @Test
    void rejectsRecordLengthAboveMaximumBeforeAllocating() {
        // VarInt(2097188) = A4 80 80 01; the decoder must reject it from the prefix alone.
        RecordCodec.Decoder d = new RecordCodec.Decoder(true, RecordCodec.ADMIT_ALL);
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> d.next(ByteBuffer.wrap(new byte[] {(byte) 0xA4, (byte) 0x80, (byte) 0x80, 0x01})));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
        assertTrue(e.getMessage().contains("> 2097187"), e.getMessage());
    }

    @Test
    void rejectsGameWithEmptyBody() {
        assertClose(raw(RecordCodec.KIND_GAME, 1, new byte[0]), decoder(true));
    }

    @Test
    void rejectsGameInnerLengthZero() {
        assertClose(raw(RecordCodec.KIND_GAME, 1, new byte[] {0x00}), decoder(true));
    }

    @Test
    void rejectsGameInnerLengthMismatchAndTrailingBytes() {
        // Says 4, only two content bytes follow.
        assertClose(raw(RecordCodec.KIND_GAME, 1, new byte[] {0x04, 0x00, 0x01}), decoder(true));
        // Says 4, five content bytes follow.
        assertClose(raw(RecordCodec.KIND_GAME, 1,
                new byte[] {0x04, 0x00, 0x01, 0x02, 0x03, 0x04}), decoder(true));
    }

    @Test
    void rejectsGameInnerLengthPrefixOverThreeBytes() {
        // 12.5/13.4 cap the inner Minecraft length at three bytes: a fourth continuation byte
        // is fatal even though Minecraft's own reader would keep going.
        assertClose(raw(RecordCodec.KIND_GAME, 1,
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01}), decoder(true));
    }

    @Test
    void acceptsGameNonMinimalInnerLengthWithinThreeBytes() throws Exception {
        // 42 needs one byte minimally (0x2A). Minecraft's own packet-length encoding accepts
        // non-minimal forms, so 12.5 keeps that and 13.4 only caps the prefix at three bytes.
        // 0xAA 0x00: 0xAA = continuation + 0b0101010 = 42, then 0x00 ends. Value = 42.
        assertGameFrameLength(42, new byte[] {(byte) 0xAA, 0x00});
        // Three-byte form of 42: 0xAA (42) + 0x80 (continuation, 0 bits) + 0x00 (end).
        assertGameFrameLength(42, new byte[] {(byte) 0xAA, (byte) 0x80, 0x00});
    }

    /** A GAME record whose inner prefix is {@code prefix} and whose content is {@code length} bytes. */
    private static void assertGameFrameLength(int length, byte[] prefix) throws Exception {
        byte[] frame = new byte[prefix.length + length];
        System.arraycopy(prefix, 0, frame, 0, prefix.length);
        for (int i = prefix.length; i < frame.length; i++) {
            frame[i] = (byte) i;
        }
        RecordCodec.Decoder d = decoder(true);
        RecordCodec.Record record = d.next(ByteBuffer.wrap(RecordCodec.encodeGame(1, 0, 0, 0, frame)));
        assertNotNull(record);
        assertEquals(length, record.gameFrameLength());
        assertEquals(length, record.gameContent().length);
        assertEquals(prefix.length, record.bodyLength() - length);
    }

    @Test
    void rejectsEndOnNonControlStream() {
        assertClose(END_GOLDEN, decoder(false));
    }

    @Test
    void rejectsEndWithBody() {
        assertClose(raw(RecordCodec.KIND_END, 1, new byte[] {0x00}), decoder(true));
    }

    @Test
    void rejectsZeroSequence() {
        assertClose(raw(RecordCodec.KIND_GAME, 0, GAME_FRAME), decoder(true));
    }

    @Test
    void refusesRecordBeforeAllocatingBodyWhenBudgetDeclines() throws Exception {
        // Only the length prefix is fed: the record_length VarInt 0x26 = 38 declares a body of
        // 38 - 33 = 5 bytes. The hook must be called with that exact size from the prefix alone,
        // before any body bytes arrive or are allocated, and a refusal must throw 0x11.
        int[] seen = new int[] {-1};
        int[] calls = new int[] {0};
        RecordCodec.BodyBudget budget = bodyBytes -> {
            seen[0] = bodyBytes;
            calls[0]++;
            return false;
        };
        RecordCodec.Decoder d = decoder(true);
        RecordCodec.Decoder refusing = decoder(true, budget);
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> refusing.next(ByteBuffer.wrap(new byte[] {0x26})));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
        assertEquals(1, calls[0]);
        assertEquals(GAME_FRAME.length, seen[0]);
        assertTrue(e.getMessage().contains("13.12"), e.getMessage());
        // A record the budget admits still decodes; the hook saw the same size.
        assertNotNull(d.next(ByteBuffer.wrap(GAME_GOLDEN)));
    }

    @Test
    void aggregateBudgetRefusesThirdOfThreeRecords() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(GAME_GOLDEN, 0, GAME_GOLDEN.length);
        out.write(GAME_GOLDEN, 0, GAME_GOLDEN.length);
        out.write(GAME_GOLDEN, 0, GAME_GOLDEN.length);
        ByteBuffer in = ByteBuffer.wrap(out.toByteArray());
        int[] remaining = {2};
        int[] asked = {0};
        RecordCodec.BodyBudget budget = bodyBytes -> {
            asked[0]++;
            return remaining[0]-- > 0;
        };
        RecordCodec.Decoder d = decoder(true, budget);
        assertNotNull(d.next(in));
        assertNotNull(d.next(in));
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> d.next(in));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
        assertEquals(3, asked[0]); // the third record's size was offered, then refused
    }

    @Test
    void rejectsNonCanonicalRecordLengthVarInt() {
        // 33 encoded as 0xA1 0x00.
        assertClose(new byte[] {(byte) 0xA1, 0x00}, decoder(true));
    }

    // --- Encoder rejections.

    @Test
    void encoderRejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordCodec.encodeGame(0, 0, 0, 0, GAME_FRAME));
        assertThrows(IllegalArgumentException.class,
                () -> RecordCodec.encodeGame(1, 0, 0, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> RecordCodec.encodeEnd(1, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> RecordCodec.encode(2, 1, 0, 0, 0, new byte[0], true));
    }

    // --- Max-length boundary.

    @Test
    void acceptsMaximumLengthRecord() throws Exception {
        int contentLength = RecordCodec.MAX_INNER_CONTENT; // 2097151 = 0x1FFFFF
        byte[] frame = new byte[3 + contentLength];
        frame[0] = (byte) 0xFF; // 2097151 as a three-byte Minecraft VarInt: FF FF 7F
        frame[1] = (byte) 0xFF;
        frame[2] = 0x7F;
        byte[] encoded = RecordCodec.encodeGame(1, 0, 0, 0, frame);
        // VarInt(2097187) is four bytes (A3 80 80 01), then the record itself.
        assertEquals(4 + RecordCodec.MAX_RECORD_LENGTH, encoded.length);
        RecordCodec.Decoder d = new RecordCodec.Decoder(true, RecordCodec.ADMIT_ALL);
        List<RecordCodec.Record> records = d.feed(ByteBuffer.wrap(encoded));
        assertEquals(1, records.size());
        assertEquals(RecordCodec.MAX_BODY_LENGTH, records.get(0).bodyLength());
        assertEquals(RecordCodec.MAX_INNER_CONTENT, records.get(0).gameFrameLength());
        assertEquals(RecordCodec.MAX_INNER_CONTENT, records.get(0).gameContent().length);
    }

    /** Builds a record with minimal record_length, big-endian sequence, zero dependencies. */
    private static byte[] raw(int kind, long sequence, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = RecordCodec.HEADER_LENGTH + body.length;
        int value = length;
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        out.write(kind);
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((sequence >>> shift) & 0xff));
        }
        for (int i = 0; i < 24; i++) {
            out.write(0);
        }
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void assertClose(byte[] bytes, RecordCodec.Decoder decoder) {
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> decoder.feed(ByteBuffer.wrap(bytes)));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
    }
}
