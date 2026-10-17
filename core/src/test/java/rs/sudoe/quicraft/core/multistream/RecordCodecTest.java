// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    void compactMinimalAndMaximumHeaderBoundaries() throws Exception {
        rs.sudoe.quicraft.core.multistream.Record minimal = new rs.sudoe.quicraft.core.multistream.Record(
                0, 1, 0, java.util.Collections.emptyList(), 1, new byte[0]);
        byte[] minimalWire = RecordCodec.encodeRecord(minimal);
        assertEquals(5, minimalWire.length);
        assertEquals(1, RecordCodec.decodeRecord(minimalWire, 0).sequence());

        // 13.4 maximum header: kind + two 5-byte VarInts + count + 7 entries of (index + 5-byte VarInt)
        // = 1+5+5+1+42 = 54 bytes. Seven dependencies on the other seven indices, on CHUNK[0] (index 2).
        java.util.List<rs.sudoe.quicraft.core.multistream.Record.Dependency> deps = new java.util.ArrayList<>();
        for (int i : new int[] {0, 1, 3, 4, 5, 6, 7}) {
            deps.add(new rs.sudoe.quicraft.core.multistream.Record.Dependency(i, 0x7fffffffL));
        }
        rs.sudoe.quicraft.core.multistream.Record maximum = new rs.sudoe.quicraft.core.multistream.Record(
                2, 0x7fffffffL, 0x7fffffffL, deps, 0, new byte[] {1, 0});
        byte[] maxWire = RecordCodec.encodeRecord(maximum);
        // record_length = 54 header + 2 body = 56, a one-byte prefix, so 57 bytes on the wire.
        assertEquals(57, maxWire.length);
        assertEquals(56, maxWire[0] & 0xff);
        assertEquals(7, RecordCodec.decodeRecord(maxWire, 2).dependencies().size());
    }

    /** 13.4: a record_length over 2097208 is refused from the prefix alone, before any body allocation. */
    @Test
    void compactDecoderRefusesOversizeLengthBeforeAllocating() throws Exception {
        byte[] prefixOnly = VarInt.encode(RecordCodec.COMPACT_MAX_RECORD_LENGTH + 1);
        assertThrows(MultistreamProtocolException.class,
                () -> new RecordCodec.CompactDecoder(0).next(java.nio.ByteBuffer.wrap(prefixOnly)));
        // The largest permitted length is accepted up to its prefix: the decoder waits for the body.
        byte[] maxPrefix = VarInt.encode(RecordCodec.COMPACT_MAX_RECORD_LENGTH);
        assertNull(new RecordCodec.CompactDecoder(0).next(java.nio.ByteBuffer.wrap(maxPrefix)));
    }

    /** 13.4: records arrive split at any byte; the decoder yields each one exactly once. */
    @Test
    void compactDecoderReassemblesSplitRecords() throws Exception {
        byte[] first = RecordCodec.encodeRecord(new rs.sudoe.quicraft.core.multistream.Record(
                0, 1, 0, java.util.Collections.emptyList(), 0, new byte[] {1, 0}));
        byte[] second = RecordCodec.encodeRecord(new rs.sudoe.quicraft.core.multistream.Record(
                0, 2, 0, java.util.Collections.emptyList(), 1, new byte[0]));
        byte[] wire = new byte[first.length + second.length];
        System.arraycopy(first, 0, wire, 0, first.length);
        System.arraycopy(second, 0, wire, first.length, second.length);
        for (int split = 0; split <= wire.length; split++) {
            RecordCodec.CompactDecoder decoder = new RecordCodec.CompactDecoder(0);
            java.util.List<rs.sudoe.quicraft.core.multistream.Record> seen = new java.util.ArrayList<>();
            java.nio.ByteBuffer a = java.nio.ByteBuffer.wrap(wire, 0, split);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(wire, split, wire.length - split);
            for (java.nio.ByteBuffer in : new java.nio.ByteBuffer[] {a, b}) {
                rs.sudoe.quicraft.core.multistream.Record r;
                while ((r = decoder.next(in)) != null) seen.add(r);
            }
            assertEquals(2, seen.size(), "split " + split);
            assertEquals(1, seen.get(0).sequence());
            assertEquals(2, seen.get(1).sequence());
        }
    }

    @Test
    void compactRecordRoundTripsSparseDependenciesAndRejectsOversizeBeforeBodyCopy() throws Exception {
        rs.sudoe.quicraft.core.multistream.Record compact =
                new rs.sudoe.quicraft.core.multistream.Record(2, 1, 0,
                        java.util.Collections.emptyList(), 0, new byte[] {1, 0});
        byte[] wire = RecordCodec.encodeRecord(compact);
        assertEquals(7, wire.length);
        rs.sudoe.quicraft.core.multistream.Record decoded = RecordCodec.decodeRecord(wire, 2);
        assertEquals(1, decoded.sequence());
        assertEquals(0, decoded.epoch());
        assertArrayEquals(new byte[] {1, 0}, decoded.payload());

        byte[] oversize = VarInt.encode(RecordCodec.COMPACT_MAX_RECORD_LENGTH + 1);
        assertThrows(MultistreamProtocolException.class, () -> RecordCodec.decodeRecord(oversize, 0));
    }

    /** Header constants generated by test resources/shard-vectors.py with python3 -I. */
    @Test
    void matchesIndependentCompactHeaderVectors() throws Exception {
        assertArrayEquals(new byte[] {6, 0, 1, 0, 0, 1, 0}, RecordCodec.encodeRecord(
                new rs.sudoe.quicraft.core.multistream.Record(0, 1, 0,
                        java.util.Collections.emptyList(), 0, new byte[] {1, 0})));
        assertArrayEquals(new byte[] {7, 1, (byte) 0xac, 2, 2, 1, 1, 9}, RecordCodec.encodeRecord(
                new rs.sudoe.quicraft.core.multistream.Record(0, 300, 2,
                        java.util.Collections.singletonList(new rs.sudoe.quicraft.core.multistream.Record.Dependency(1, 9)),
                        1, new byte[0])));
        assertArrayEquals(new byte[] {11, 0, (byte) 0x80, 1, 127, 1, 2, (byte) 0x80, (byte) 0x80, 1, 1, 0},
                RecordCodec.encodeRecord(new rs.sudoe.quicraft.core.multistream.Record(1, 128, 127,
                        java.util.Collections.singletonList(new rs.sudoe.quicraft.core.multistream.Record.Dependency(2, 16384)),
                        0, new byte[] {1, 0})));
    }
    /** 13.4: each rule of the compact record format has a raw, hand-built rejection case. */
    @Test
    void compactRejectsInvalidDependenciesKindsAndStreams() throws Exception {
        assertCompactRejected(raw(0, 1, 0, 1, 1, 1, 1, 0), 1);            // self dependency
        assertCompactRejected(raw(0, 1, 0, 2, 0, 1, 0, 2, 1, 0), 2);      // duplicate index
        assertCompactRejected(raw(0, 1, 0, 2, 3, 1, 0, 1, 1, 0), 2);      // index not strictly increasing
        assertCompactRejected(raw(0, 1, 0, 1, 8, 1, 1, 0), 0);            // index beyond stream 7
        assertCompactRejected(raw(0, 1, 0, 1, 3, 0, 1, 0), 0);            // zero dependency sequence
        assertCompactRejected(raw(0, 0, 0, 0, 1, 0), 0);                  // sequence 0
        assertCompactRejected(raw(1, 1, 0, 0), 1);                        // END off CONTROL
        assertCompactRejected(raw(2, 1, 0, 0, 1, 0), 0);                  // join off CHUNK
        assertCompactRejected(raw(2, 1, 0, 0, 1, 0), 3);                  // join on CHUNK[1]
        assertCompactRejected(raw(3, 1, 0, 0, 1, 0), 7);                  // join on CHUNK[5]
        assertCompactRejected(raw(4, 1, 0, 0, 1, 0), 2);                  // unknown kind
        assertThrows(IllegalArgumentException.class,
                () -> new RecordCodec.CompactDecoder(9));                 // stream index beyond 7

        // A join is legal on a CHUNK stream (13.19), and trailing bytes after one record are rejected.
        assertEquals(RecordCodec.KIND_ACK_JOIN,
                RecordCodec.decodeRecord(raw(2, 1, 0, 0, 1, 0), 2).kind());
        byte[] twoRecords = concat(raw(1, 1, 0, 0), raw(1, 2, 0, 0));
        assertCompactRejected(twoRecords, 0);
    }

    private static void assertCompactRejected(byte[] wire, int streamIndex) {
        assertThrows(MultistreamProtocolException.class,
                () -> RecordCodec.decodeRecord(wire, streamIndex), "stream " + streamIndex);
    }

    /** Builds a raw compact record from its bytes after the length prefix (all under 128 bytes). */
    private static byte[] raw(int... rest) {
        byte[] out = new byte[rest.length + 1];
        out[0] = (byte) rest.length;
        for (int i = 0; i < rest.length; i++) {
            out[i + 1] = (byte) rest[i];
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

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

    private static RecordCodec.Decoder decoder(boolean control, RecordCodec.WireBudget budget) {
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
    void refusesRecordBeforeAllocatingWhenBudgetDeclines() throws Exception {
        // Only the length prefix is fed: the one-byte record_length VarInt 0x26 = 38 declares a
        // record of 1 + 38 = 39 wire bytes. The hook must be called with that exact wire size
        // from the prefix alone, before any further bytes arrive or are allocated, and a refusal
        // must throw 0x11.
        int[] seen = new int[] {-1};
        int[] calls = new int[] {0};
        RecordCodec.WireBudget budget = wireBytes -> {
            seen[0] = wireBytes;
            calls[0]++;
            return false;
        };
        RecordCodec.Decoder d = decoder(true);
        RecordCodec.Decoder refusing = decoder(true, budget);
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> refusing.next(ByteBuffer.wrap(new byte[] {0x26})));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
        assertEquals(1, calls[0]);
        assertEquals(GAME_GOLDEN.length, seen[0]);
        assertTrue(e.getMessage().contains("13.12"), e.getMessage());
        // A record the budget admits still decodes; the hook saw the same size.
        assertNotNull(d.next(ByteBuffer.wrap(GAME_GOLDEN)));
    }

    @Test
    void budgetIsChargedTheWholeWireRecordNotOnlyTheBody() throws Exception {
        // END has an empty body but occupies 1 + 33 = 34 wire bytes. A budget with 33 bytes left
        // would admit it if only the body (0) or only record_length (33) were charged; charging
        // the whole wire record refuses it, and exactly 34 bytes admits it.
        int[] seen = {-1};
        RecordCodec.Decoder tight = decoder(true, wireBytes -> {
            seen[0] = wireBytes;
            return wireBytes <= END_GOLDEN.length - 1;
        });
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> tight.next(ByteBuffer.wrap(END_GOLDEN)));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
        assertEquals(34, seen[0]);
        assertEquals(END_GOLDEN.length, seen[0]);

        RecordCodec.Decoder exact = decoder(true, wireBytes -> wireBytes <= END_GOLDEN.length);
        RecordCodec.Record end = exact.next(ByteBuffer.wrap(END_GOLDEN));
        assertNotNull(end);
        assertEquals(RecordCodec.KIND_END, end.kind());
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
        RecordCodec.WireBudget budget = wireBytes -> {
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

    /**
     * 13.12: the declarations are read in place from the staged prefix. A full-window prefix of a
     * compressed GAME record reads its declarations and allocates no per-call body copy.
     */
    @Test
    void inspectPrefixReadsDeclarationsInPlaceWithoutCopyingTheBody() throws Exception {
        byte[] prefix = new byte[RecordCodec.CompactDecoder.PREFIX_WINDOW];
        // Compact header: GAME, sequence 1, epoch 0, no dependencies (4 bytes).
        prefix[0] = 0;
        prefix[1] = 1;
        byte[] inner = VarInt.encode(20000);
        System.arraycopy(inner, 0, prefix, 4, inner.length);
        prefix[4 + inner.length] = 0; // Data Length 0: stored
        for (int i = 4 + inner.length + 1; i < prefix.length; i++) prefix[i] = 0x55; // packet bytes
        long recordLength = 4 + inner.length + 20000;

        RecordCodec.Declarations d = RecordCodec.inspectPrefix(prefix, prefix.length, recordLength, true);
        assertEquals(4, d.headerLength);
        assertEquals(inner.length, d.innerLengthBytes);
        assertEquals(1, d.dataLengthBytes);
        assertEquals(0, d.dataLength);
        assertEquals(19999, d.payloadLength);

        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean
                && ((com.sun.management.ThreadMXBean) bean).isThreadAllocatedMemorySupported());
        com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) bean;
        long thread = Thread.currentThread().getId();
        int calls = 5000;
        for (int i = 0; i < calls; i++) RecordCodec.inspectPrefix(prefix, prefix.length, recordLength, true);
        long before = sun.getThreadAllocatedBytes(thread);
        for (int i = 0; i < calls; i++) RecordCodec.inspectPrefix(prefix, prefix.length, recordLength, true);
        long allocated = sun.getThreadAllocatedBytes(thread) - before;
        assertTrue(allocated / calls < MAX_INSPECT_BYTES_PER_CALL,
                "inspectPrefix allocated " + (allocated / calls) + " bytes per call");
    }

    /**
     * Per-call allocation ceiling for inspectPrefix. Measured: 240 bytes per call without a body copy,
     * 328 with the 69-byte copy (the mutation this test must catch). 288 sits between them.
     */
    private static final long MAX_INSPECT_BYTES_PER_CALL = 288;

    private static void assertClose(byte[] bytes, RecordCodec.Decoder decoder) {
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> decoder.feed(ByteBuffer.wrap(bytes)));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
    }
}
