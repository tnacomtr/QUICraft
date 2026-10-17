// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/** Wire tests for the compact {@link RecordCodec} format (docs/protocol.md 13.4). */
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
}
