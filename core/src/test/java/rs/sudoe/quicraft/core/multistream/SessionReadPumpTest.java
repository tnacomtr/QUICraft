// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.resource.InboundExcessPool;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.routing.RouteV2;

class SessionReadPumpTest {
    /** Connection excess E = L + F + 16 KiB for the test limits (13.12). */
    private static final long E = 65536L + 8192 + 16 * 1024;
    private static final RouteV2.ServerboundLimits LIMITS = new RouteV2.ServerboundLimits(65536, 510, 8192, 5459);

    /** Byte source with per-stream data and a read log. */
    static final class Source implements SessionReadPump.ByteSource {
        final byte[][] data = new byte[2][];
        final int[] pos = new int[2];
        final List<Integer> requests = new ArrayList<>();

        Source(byte[] control, byte[] realtime) {
            data[LogicalStream.CONTROL] = control;
            data[LogicalStream.REALTIME] = realtime;
        }

        @Override
        public int read(int stream, byte[] into, int offset, int max) {
            requests.add(max);
            byte[] src = data[stream];
            int n = Math.min(max, src.length - pos[stream]);
            System.arraycopy(src, pos[stream], into, offset, n);
            pos[stream] += n;
            return n;
        }

        int position(int stream) {
            return pos[stream];
        }
    }

    /** Byte source for N+2 streams: serves at most {@code chunk} bytes per read and counts reads per stream. */
    static final class Streams implements SessionReadPump.ByteSource {
        final byte[][] data;
        final int[] pos;
        final int[] reads;
        final int chunk;

        Streams(int streams, int chunk) {
            data = new byte[streams][];
            for (int i = 0; i < streams; i++) data[i] = new byte[0];
            pos = new int[streams];
            reads = new int[streams];
            this.chunk = chunk;
        }

        void put(int stream, byte[] bytes) {
            data[stream] = bytes;
        }

        @Override
        public int read(int stream, byte[] into, int offset, int max) {
            reads[stream]++;
            int n = Math.min(Math.min(max, chunk), data[stream].length - pos[stream]);
            System.arraycopy(data[stream], pos[stream], into, offset, n);
            pos[stream] += n;
            return n;
        }
    }

    /** A pool with no spare capacity for an excess request that is larger than what is free. */
    static final class Pools {
        final NodePools node;
        final InboundExcessPool excess;

        Pools(long excessCapacity) {
            node = new NodePools(1L << 30, excessCapacity, 1L << 30, 10, false, E);
            excess = new InboundExcessPool(node, E, E, owner -> 0.0);
        }
    }

    static byte[] inner(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarInt.write(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] gameWire(int stream, long seq, byte[] content) {
        return RecordCodec.encodeRecord(new Record(stream, seq, 0, Collections.<Record.Dependency>emptyList(),
                RecordCodec.KIND_GAME, inner(content)));
    }

    static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        return out.toByteArray();
    }

    /** A compressed GAME record whose Data Length is declared as given, regardless of the actual bytes. */
    static byte[] compressedWire(int stream, long seq, byte[] packet, int declaredDataLength) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        VarInt.write(content, declaredDataLength);
        byte[] z = deflate(packet);
        content.write(z, 0, z.length);
        return RecordCodec.encodeRecord(new Record(stream, seq, 0, Collections.<Record.Dependency>emptyList(),
                RecordCodec.KIND_GAME, inner(content.toByteArray())));
    }

    static byte[] filled(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (seed + (i % 7));
        return b;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** The wire length of a record whose length prefix is included, found from its own length. */
    static int recordLengthOf(byte[] wire) {
        for (int n = 1; n <= 3; n++) {
            if (VarInt.encodedLength(wire.length - n) == n) return wire.length - n;
        }
        throw new AssertionError("no consistent record_length prefix");
    }

    /** Wire bytes of an uncompressed CONTROL record with {@code content} bytes, plus that content (13.12 footprint). */
    static long controlFootprint(int content) {
        return gameWire(LogicalStream.CONTROL, 1, filled(content, 1)).length + content;
    }

    /** The content length whose CONTROL footprint is exactly {@code target}; footprints grow with content. */
    static int contentFor(long target) {
        int lo = 0;
        int hi = 1 << 20;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (controlFootprint(mid) < target) lo = mid + 1;
            else hi = mid;
        }
        assertEquals(target, controlFootprint(lo), "a content length gives exactly " + target);
        return lo;
    }

    /**
     * The largest CONTROL content whose footprint is at most {@code limit}. Footprints step by two at a
     * VarInt boundary, so an exact value is not always reachable; the edge is then the neighbour pair.
     */
    static int contentAtMost(long limit) {
        int lo = 0;
        int hi = 1 << 20;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (controlFootprint(mid) <= limit) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** Steps until a unit completes, failing after a bound so a stuck pump cannot hang the suite. */
    static SessionReadPump.Unit nextUnit(SessionReadPump pump) throws IOException, MultistreamProtocolException {
        for (int i = 0; i < 100_000; i++) {
            List<SessionReadPump.Unit> units = pump.step();
            if (!units.isEmpty()) return units.get(0);
        }
        throw new AssertionError("no unit completed");
    }

    static ClientReadPump.Unit nextClientUnit(ClientReadPump pump) throws IOException, MultistreamProtocolException {
        for (int i = 0; i < 100_000; i++) {
            List<ClientReadPump.Unit> units = pump.step();
            if (!units.isEmpty()) return units.get(0);
        }
        throw new AssertionError("no client unit completed");
    }

    static SessionReadPump pump(ServerReadPartition partition, Pools pools, boolean compressed, Source source) {
        return new SessionReadPump(partition, pools.node, pools.excess, "conn", "ip-a", null, LIMITS, compressed,
                source);
    }

    static SessionReadPump pump(ServerReadPartition partition, Pools pools, RouteV2.ServerboundLimits limits,
                                Source source) {
        return new SessionReadPump(partition, pools.node, pools.excess, "conn", "ip-a", null, limits, false, source);
    }

    @Test void oversizedRealtimeRejectedBeforeBodyAllocation() throws Exception {
        // 511 bytes is one past the REALTIME cap of 510 (record_length), 510 is legal.
        byte[] over = gameWire(LogicalStream.REALTIME, 1, filled(505, 1));
        assertEquals(513, over.length, "two-byte record_length prefix plus 511 bytes");
        Source source = new Source(new byte[0], over);
        SessionReadPump pump = pump(new ServerReadPartition(6), new Pools(1L << 20), false, source);
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class, () -> nextUnit(pump));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
        assertEquals(0, pump.frameAllocations(LogicalStream.REALTIME), "no body allocated for a refused declaration");
        assertTrue(source.position(LogicalStream.REALTIME) <= 2 + RecordCodec.CompactDecoder.PREFIX_WINDOW,
                "only the bounded prefix is read");

        byte[] exact = gameWire(LogicalStream.REALTIME, 1, filled(504, 1));
        assertEquals(512, exact.length, "two-byte record_length prefix plus 510 bytes");
        Source ok = new Source(new byte[0], exact);
        SessionReadPump accept = pump(new ServerReadPartition(6), new Pools(1L << 20), false, ok);
        try (SessionReadPump.Unit unit = nextUnit(accept)) {
            assertEquals(LogicalStream.REALTIME, unit.stream());
            assertArrayEquals(filled(504, 1), unit.decoded());
        }
        assertEquals(1, accept.frameAllocations(LogicalStream.REALTIME));
    }

    @Test void compressedDeclaredBoundIsCheckedBeforeInflation() throws Exception {
        // REALTIME: a declared Data Length of 1025 is refused at admission; 1024 is admitted and inflated on demand.
        byte[] tooBig = compressedWire(LogicalStream.REALTIME, 1, filled(1025, 2), 1025);
        SessionReadPump refused = pump(new ServerReadPartition(6), new Pools(1L << 20), true,
                new Source(new byte[0], tooBig));
        assertThrows(MultistreamProtocolException.class, () -> nextUnit(refused));
        assertEquals(0, refused.inflations(), "refused before inflation");

        byte[] edge = compressedWire(LogicalStream.REALTIME, 1, filled(1024, 2), 1024);
        int recordLength = recordLengthOf(edge);
        ServerReadPartition partition = new ServerReadPartition(6);
        SessionReadPump accept = pump(partition, new Pools(1L << 20), true, new Source(new byte[0], edge));
        SessionReadPump.Unit unit = nextUnit(accept);
        assertEquals(0, accept.inflations(), "a completed compressed unit is opaque");
        assertTrue(unit.needsInflation());
        assertEquals(2048 - recordLength - 1024, partition.available(LogicalStream.REALTIME),
                "frame and decoded footprint held while the unit is open");
        assertArrayEquals(filled(1024, 2), accept.inflate(unit));
        assertEquals(1, accept.inflations());
        assertEquals(2048 - recordLength - 1024, partition.available(LogicalStream.REALTIME));
        unit.close();
        assertEquals(2048, partition.available(LogicalStream.REALTIME), "charges returned on close");
        accept.cancel();

        // CONTROL: a declared Data Length above the route's decoded cap is refused at admission.
        byte[] control = compressedWire(LogicalStream.CONTROL, 1, filled(9000, 3), 9000);
        SessionReadPump overCap = pump(new ServerReadPartition(6), new Pools(1L << 20), true,
                new Source(control, new byte[0]));
        assertThrows(MultistreamProtocolException.class, () -> nextUnit(overCap));
        assertEquals(0, overCap.inflations());

        // A declared length that disagrees with the zlib stream is refused by the inflate call, not by step().
        byte[] liar = compressedWire(LogicalStream.CONTROL, 1, filled(200, 4), 100);
        SessionReadPump mismatch = pump(new ServerReadPartition(6), new Pools(1L << 20), true,
                new Source(liar, new byte[0]));
        SessionReadPump.Unit opaque = nextUnit(mismatch);
        assertEquals(0, mismatch.inflations());
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class,
                () -> mismatch.inflate(opaque));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
        assertEquals(1, mismatch.inflations());
    }

    @Test void stepDoesNotInflateCompletedCompressedHeads() throws Exception {
        byte[] control = compressedWire(LogicalStream.CONTROL, 1, filled(300, 3), 300);
        byte[] realtime = compressedWire(LogicalStream.REALTIME, 1, filled(100, 4), 100);
        SessionReadPump pump = pump(new ServerReadPartition(6), new Pools(1L << 20), true,
                new Source(control, realtime));
        List<SessionReadPump.Unit> units = new ArrayList<>();
        for (int i = 0; i < 100_000 && units.size() < 2; i++) units.addAll(pump.step());
        assertEquals(2, units.size(), "both compressed heads complete");
        assertEquals(0, pump.inflations(), "step() inflates no completed head");
        for (SessionReadPump.Unit unit : units) {
            assertTrue(unit.needsInflation());
            assertThrows(IllegalStateException.class, unit::decoded);
        }
        for (SessionReadPump.Unit unit : units) {
            byte[] expected = unit.stream() == LogicalStream.CONTROL ? filled(300, 3) : filled(100, 4);
            assertArrayEquals(expected, pump.inflate(unit));
            assertFalse(unit.needsInflation());
        }
        assertEquals(2, pump.inflations(), "inflation happens only when asked");
        for (SessionReadPump.Unit unit : units) unit.close();
        pump.cancel();
    }

    @Test void controlWireLimitCountsTheOuterLengthPrefix() throws Exception {
        byte[] exact = gameWire(LogicalStream.CONTROL, 1, filled(200, 1));
        int wire = exact.length;
        RouteV2.ServerboundLimits atLimit = new RouteV2.ServerboundLimits(wire, 510, 8192, 5459);
        SessionReadPump accept = pump(new ServerReadPartition(6), new Pools(1L << 20), atLimit,
                new Source(exact, new byte[0]));
        try (SessionReadPump.Unit unit = nextUnit(accept)) {
            assertEquals(200, unit.decoded().length, "wire size exactly at L is accepted");
        }

        byte[] over = gameWire(LogicalStream.CONTROL, 1, filled(201, 1));
        assertEquals(wire + 1, over.length, "one byte more is the L+1 wire record");
        SessionReadPump refused = pump(new ServerReadPartition(6), new Pools(1L << 20), atLimit,
                new Source(over, new byte[0]));
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class, () -> nextUnit(refused));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode(), "L+1 is refused as ordering (0x11)");
        assertEquals(0, refused.frameAllocations(LogicalStream.CONTROL));
    }

    @Test void excessFootprintAtCapIsAdmittedAndOverCapIsResourceClose() throws Exception {
        RouteV2.ServerboundLimits wide = new RouteV2.ServerboundLimits(1 << 20, 510, 1 << 20, 5459);
        int atCap = contentFor(E);
        Pools pools = new Pools(1L << 20);
        SessionReadPump pump = pump(new ServerReadPartition(6), pools, wide,
                new Source(gameWire(LogicalStream.CONTROL, 1, filled(atCap, 1)), new byte[0]));
        // Admission accepts a footprint of exactly E; the held excess then drops the length prefix it no longer uses.
        long wireBody = recordLengthOf(gameWire(LogicalStream.CONTROL, 1, filled(atCap, 1)));
        try (SessionReadPump.Unit unit = nextUnit(pump)) {
            assertEquals(wireBody + atCap, pools.excess.reserved("conn"), "held: frame plus decoded bytes");
            assertEquals(atCap, unit.decoded().length);
        }
        assertEquals(0, pools.excess.reserved("conn"));
        pump.cancel();

        int overCap = contentFor(E) + 1;
        assertTrue(controlFootprint(overCap) > E, "one step past E");
        SessionReadPump over = pump(new ServerReadPartition(6), new Pools(1L << 20), wide,
                new Source(gameWire(LogicalStream.CONTROL, 1, filled(overCap, 1)), new byte[0]));
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class, () -> nextUnit(over));
        assertEquals(Protocol.CLOSE_RESOURCE, e.closeCode(), "a footprint over the cap is a resource close (0x12)");
        assertEquals(0, over.frameAllocations(LogicalStream.CONTROL));
    }

    @Test void protectedShareEdgeAdmitsInShareThenSpillsToExcess() throws Exception {
        int allowance = new ServerReadPartition(6).controlAllowance();
        ServerReadPartition exactPartition = new ServerReadPartition(6);
        assertTrue(exactPartition.reserve(LogicalStream.CONTROL, allowance), "exactly the allowance fits in-share");
        assertFalse(exactPartition.reserve(LogicalStream.CONTROL, 1), "one byte more does not");

        RouteV2.ServerboundLimits wide = new RouteV2.ServerboundLimits(1 << 20, 510, 1 << 20, allowance);
        ServerReadPartition partition = new ServerReadPartition(6);
        Pools pools = new Pools(1L << 20);
        // The largest record whose footprint still fits the allowance is admitted in-share.
        int inShare = contentAtMost(allowance);
        assertTrue(controlFootprint(inShare) <= allowance);
        SessionReadPump pumpIn = pump(partition, pools, wide,
                new Source(gameWire(LogicalStream.CONTROL, 1, filled(inShare, 1)), new byte[0]));
        byte[] record = gameWire(LogicalStream.CONTROL, 1, filled(inShare, 1));
        long held = recordLengthOf(record) + inShare; // the frame plus the decoded bytes, once settled
        try (SessionReadPump.Unit unit = nextUnit(pumpIn)) {
            assertEquals(allowance - held, partition.available(LogicalStream.CONTROL), "held in-share");
            assertEquals(0, pools.excess.reserved("conn"), "no excess for an in-share record");
            assertEquals(inShare, unit.decoded().length);
        }
        pumpIn.cancel();
    }

    @Test void protectedShareSpillHoldsExcessOnlyForTheRecordAboveAllowance() throws Exception {
        int allowance = new ServerReadPartition(6).controlAllowance();
        RouteV2.ServerboundLimits wide = new RouteV2.ServerboundLimits(1 << 20, 510, 1 << 20, allowance);
        Pools pools = new Pools(1L << 20);
        ServerReadPartition partition2 = new ServerReadPartition(6);
        int plusOne = contentAtMost(allowance) + 1;
        long footprint = controlFootprint(plusOne);
        assertTrue(footprint > allowance);
        SessionReadPump spill = pump(partition2, pools, wide,
                new Source(gameWire(LogicalStream.CONTROL, 1, filled(plusOne, 1)), new byte[0]));
        try (SessionReadPump.Unit unit = nextUnit(spill)) {
            assertEquals(allowance, partition2.available(LogicalStream.CONTROL), "in-share bytes stay unused");
            long wireBody = recordLengthOf(gameWire(LogicalStream.CONTROL, 1, filled(plusOne, 1)));
            assertEquals(wireBody + plusOne, pools.excess.reserved("conn"), "the held record is charged to excess");
            assertEquals(plusOne, unit.decoded().length);
        }
        assertEquals(0, pools.excess.reserved("conn"));
        spill.cancel();
    }

    @Test void controlExcessWaitDoesNotBlockRealtime() throws Exception {
        Pools pools = new Pools(8192);
        // A holder that cannot be a victim takes the whole pool, so CONTROL's excess request must wait.
        assertTrue(pools.excess.reserve("blocker", "ip-b", null, 8192, true));
        byte[] control = gameWire(LogicalStream.CONTROL, 1, filled(3000, 5));
        byte[] realtime = gameWire(LogicalStream.REALTIME, 1, filled(20, 6));
        Source source = new Source(control, realtime);
        ServerReadPartition partition = new ServerReadPartition(6);
        SessionReadPump pump = pump(partition, pools, false, source);

        SessionReadPump.Unit first = nextUnit(pump);
        assertEquals(LogicalStream.REALTIME, first.stream(), "protected REALTIME completes during CONTROL's wait");
        first.close();
        for (int i = 0; i < 50; i++) {
            assertTrue(pump.step().isEmpty(), "CONTROL stays held while its excess is refused");
            assertTrue(partition.prefixInUse() <= ServerReadPartition.PREFIX_SLICE, "prefix slice never exceeded");
        }
        assertEquals(SessionReadPump.PREFIX_STAGE, partition.prefixInUse(), "the held prefix keeps its slice");
        assertEquals(1, pools.excess.waitingCount());
        assertEquals(0, pump.frameAllocations(LogicalStream.CONTROL));

        pools.excess.release("blocker", 8192);
        SessionReadPump.Unit second = nextUnit(pump);
        assertEquals(LogicalStream.CONTROL, second.stream());
        assertArrayEquals(filled(3000, 5), second.decoded());
        second.close();
        assertEquals(0, pools.excess.reserved("conn"));
        assertEquals(0, partition.prefixInUse(), "the prefix slice is returned once the record is admitted");
    }

    @Test void refusedDeclarationDoesNotAdvanceParser() throws Exception {
        Pools pools = new Pools(8192);
        assertTrue(pools.excess.reserve("blocker", "ip-b", null, 8192, true));
        byte[] control = gameWire(LogicalStream.CONTROL, 1, filled(3000, 7));
        Source source = new Source(control, new byte[0]);
        ServerReadPartition partition = new ServerReadPartition(6);
        SessionReadPump pump = pump(partition, pools, false, source);

        for (int i = 0; i < 20; i++) pump.step();
        int held = source.position(LogicalStream.CONTROL);
        assertTrue(held <= 2 + RecordCodec.CompactDecoder.PREFIX_WINDOW, "held after the prefix only");
        for (int i = 0; i < 50; i++) {
            pump.step();
            assertEquals(held, source.position(LogicalStream.CONTROL), "a refused declaration reads no further bytes");
        }
        assertEquals(SessionReadPump.PREFIX_STAGE, partition.prefixInUse(), "a held prefix keeps its staging bytes");
        assertEquals(1, pools.excess.waitingCount(), "one outstanding request, not one per retry");
        assertEquals(0, pump.frameAllocations(LogicalStream.CONTROL));

        pools.excess.release("blocker", 8192);
        SessionReadPump.Unit unit = nextUnit(pump);
        assertArrayEquals(filled(3000, 7), unit.decoded(), "exact resume once the declaration is admitted");
        unit.close();

        // Cancellation while held returns every charge, the prefix slice included, and drops the queued request.
        assertTrue(pools.excess.reserve("blocker2", "ip-b", null, 8192, true));
        Source again = new Source(gameWire(LogicalStream.CONTROL, 2, filled(3000, 8)), new byte[0]);
        ServerReadPartition heldPartition = new ServerReadPartition(6);
        SessionReadPump held2 = pump(heldPartition, pools, false, again);
        for (int i = 0; i < 10; i++) held2.step();
        assertEquals(1, pools.excess.waitingCount());
        assertEquals(SessionReadPump.PREFIX_STAGE, heldPartition.prefixInUse());
        held2.cancel();
        assertEquals(0, pools.excess.waitingCount());
        assertEquals(0, pools.excess.reserved("conn"));
        assertEquals(0, heldPartition.prefixInUse());
        assertThrows(IllegalStateException.class, held2::step);
    }

    @Test void clientEveryStreamFinishesItsHeadWhenEverySiblingSlotIsFull() throws Exception {
        for (int chunks = 1; chunks <= 6; chunks++) {
            int streams = chunks + 2;
            for (int finishing = 0; finishing < streams; finishing++) {
                ReceiveSlots slots = new ReceiveSlots(chunks);
                for (int other = 0; other < streams; other++) {
                    if (other == finishing) continue;
                    assertTrue(slots.admitHead(other, ReceiveSlots.BYTES_PER_STREAM, ReceiveSlots.RECORDS_PER_STREAM));
                }
                // Fragmented reads of 3 bytes; two records, the second must wait for the first one's commit.
                Streams source = new Streams(streams, 3);
                byte[] first = gameWire(finishing, 1, filled(300, 1));
                source.put(finishing, concat(first, gameWire(finishing, 2, filled(40, 2))));
                ClientReadPump pump = new ClientReadPump(chunks, slots, false, source);

                // Early bytes: the first read stages the record_length VarInt in the stream's own slot.
                pump.step();
                assertEquals(SessionReadPump.PREFIX_STAGE, slots.heldBytes(finishing),
                        "early bytes occupy the stream's slot (N=" + chunks + ")");
                ClientReadPump.Unit unit = nextClientUnit(pump);
                assertEquals(finishing, unit.stream(), "stream " + finishing + " of N=" + chunks + " completes its head");
                assertArrayEquals(inner(filled(300, 1)), unit.record().payload());
                assertEquals(first.length, slots.heldBytes(finishing), "the declared wire size is held until commit");

                int readsAfterCompletion = source.reads[finishing];
                for (int i = 0; i < 20; i++) pump.step();
                assertEquals(readsAfterCompletion, source.reads[finishing], "no read past a completed unit");
                for (int other = 0; other < streams; other++) {
                    if (other != finishing) assertEquals(0, source.reads[other], "a full slot is never read");
                }

                pump.commit(unit);
                assertFalse(slots.holdsHead(finishing), "commit releases the slot");
                ClientReadPump.Unit second = nextClientUnit(pump);
                assertEquals(2, second.record().sequence());
                pump.commit(second);
                assertFalse(slots.holdsHead(finishing));
                pump.cancel();
            }
        }
    }

    @Test void clientBundleFitsItsSlotAndReadsStopAtOneUnit() throws Exception {
        int chunks = 6;
        int chunkStream = 2;
        ReceiveSlots slots = new ReceiveSlots(chunks);
        byte[] packet = filled(1_500_000, 5);
        byte[] bundle = gameWire(chunkStream, 1, packet);
        assertTrue(bundle.length <= ReceiveSlots.BYTES_PER_STREAM, "a bundle's wire size fits the slot");
        Streams source = new Streams(chunks + 2, 16 * 1024);
        source.put(chunkStream, bundle);
        ClientReadPump pump = new ClientReadPump(chunks, slots, false, source);

        ClientReadPump.Unit unit = nextClientUnit(pump);
        assertEquals(chunkStream, unit.stream());
        assertEquals(bundle.length, slots.heldBytes(chunkStream), "the whole bundle is held in its slot");
        assertArrayEquals(inner(packet), unit.record().payload());
        int reads = source.reads[chunkStream];
        for (int i = 0; i < 5; i++) pump.step();
        assertEquals(reads, source.reads[chunkStream], "the stream is not read while its bundle is held");
        pump.commit(unit);
        assertFalse(slots.holdsHead(chunkStream));
        pump.cancel();
    }

    @Test void cachedAllocatorHonoursReducedGrant() throws Exception {
        ReadGrantAllocator allocator = new ReadGrantAllocator();
        RecvByteBufAllocator.Handle cached = allocator.newHandle();
        assertEquals(16 * 1024, cached.guess());
        allocator.setLimit(4096);
        assertEquals(4096, cached.guess(), "a handle cached before the change honours the reduced grant");
        ByteBuf buf = cached.allocate(UnpooledByteBufAllocator.DEFAULT);
        try {
            assertEquals(4096, buf.capacity());
        } finally {
            buf.release();
        }
        allocator.setLimit(16 * 1024);
        assertEquals(16 * 1024, cached.guess(), "an idle grant grows back on the same handle");
        assertThrows(IllegalArgumentException.class, () -> allocator.setLimit(16 * 1024 + 1));
    }

    @Test void readsStopAtTheUnitBoundary() throws Exception {
        byte[] first = gameWire(LogicalStream.CONTROL, 1, filled(40, 9));
        byte[] next = gameWire(LogicalStream.CONTROL, 2, filled(40, 10));
        Source source = new Source(concat(first, next), new byte[0]);
        SessionReadPump pump = pump(new ServerReadPartition(6), new Pools(1L << 20), false, source);
        SessionReadPump.Unit unit = nextUnit(pump);
        assertEquals(first.length, source.position(LogicalStream.CONTROL),
                "the suffix belonging to the next record is not read");
        assertArrayEquals(filled(40, 9), unit.decoded());
        for (int requested : source.requests) {
            assertTrue(requested <= 16 * 1024, "grant within the 16 KiB cap");
        }
        unit.close();
    }
}
