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
import rs.sudoe.quicraft.core.resource.InboundExcessPool;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.routing.RouteV2;

class SessionReadPumpTest {
    private static final long E = 64L * 1024;
    private static final RouteV2.ServerboundLimits LIMITS = new RouteV2.ServerboundLimits(65536, 510, 8192, 5459);

    /** Byte source with per-stream data, a read log and an optional stop. */
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

    /** Steps until a unit completes, failing after a bound so a stuck pump cannot hang the suite. */
    static SessionReadPump.Unit nextUnit(SessionReadPump pump) throws IOException, MultistreamProtocolException {
        for (int i = 0; i < 100_000; i++) {
            List<SessionReadPump.Unit> units = pump.step();
            if (!units.isEmpty()) return units.get(0);
        }
        throw new AssertionError("no unit completed");
    }

    static SessionReadPump pump(ServerReadPartition partition, Pools pools, boolean compressed, Source source) {
        return new SessionReadPump(partition, pools.node, pools.excess, "conn", "ip-a", null, LIMITS, compressed,
                source);
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
        // REALTIME: a declared Data Length of 1025 is refused before inflating; 1024 is accepted.
        byte[] tooBig = compressedWire(LogicalStream.REALTIME, 1, filled(1025, 2), 1025);
        SessionReadPump refused = pump(new ServerReadPartition(6), new Pools(1L << 20), true,
                new Source(new byte[0], tooBig));
        assertThrows(MultistreamProtocolException.class, () -> nextUnit(refused));
        assertEquals(0, refused.inflations(), "refused before inflation");

        byte[] edge = compressedWire(LogicalStream.REALTIME, 1, filled(1024, 2), 1024);
        ServerReadPartition partition = new ServerReadPartition(6);
        SessionReadPump accept = pump(partition, new Pools(1L << 20), true, new Source(new byte[0], edge));
        SessionReadPump.Unit unit = nextUnit(accept);
        assertArrayEquals(filled(1024, 2), unit.decoded());
        assertEquals(1, accept.inflations());
        assertEquals(2048 - 1024, partition.available(LogicalStream.REALTIME), "decoded bytes held while unit is open");
        unit.close();
        assertEquals(2048, partition.available(LogicalStream.REALTIME), "charges returned on close");
        accept.cancel();

        // CONTROL: a declared Data Length above the route's decoded cap is refused before inflating.
        byte[] control = compressedWire(LogicalStream.CONTROL, 1, filled(9000, 3), 9000);
        SessionReadPump overCap = pump(new ServerReadPartition(6), new Pools(1L << 20), true,
                new Source(control, new byte[0]));
        assertThrows(MultistreamProtocolException.class, () -> nextUnit(overCap));
        assertEquals(0, overCap.inflations());

        // A declared length that disagrees with the zlib stream fails after bounded inflation.
        byte[] liar = compressedWire(LogicalStream.CONTROL, 1, filled(200, 4), 100);
        SessionReadPump mismatch = pump(new ServerReadPartition(6), new Pools(1L << 20), true,
                new Source(liar, new byte[0]));
        MultistreamProtocolException e = assertThrows(MultistreamProtocolException.class, () -> nextUnit(mismatch));
        assertEquals(MultistreamProtocolException.CLOSE_ORDERING, e.closeCode());
        assertEquals(1, mismatch.inflations());
    }

    @Test void controlExcessWaitDoesNotBlockRealtime() throws Exception {
        Pools pools = new Pools(8192);
        // A holder that cannot be a victim takes the whole pool, so CONTROL's excess request must wait.
        assertTrue(pools.excess.reserve("blocker", "ip-b", null, 8192, true));
        byte[] control = gameWire(LogicalStream.CONTROL, 1, filled(3000, 5));
        byte[] realtime = gameWire(LogicalStream.REALTIME, 1, filled(20, 6));
        Source source = new Source(control, realtime);
        SessionReadPump pump = pump(new ServerReadPartition(6), pools, false, source);

        SessionReadPump.Unit first = nextUnit(pump);
        assertEquals(LogicalStream.REALTIME, first.stream(), "protected REALTIME completes during CONTROL's wait");
        first.close();
        for (int i = 0; i < 50; i++) {
            assertTrue(pump.step().isEmpty(), "CONTROL stays held while its excess is refused");
        }
        assertEquals(1, pools.excess.waitingCount());
        assertEquals(0, pump.frameAllocations(LogicalStream.CONTROL));

        pools.excess.release("blocker", 8192);
        SessionReadPump.Unit second = nextUnit(pump);
        assertEquals(LogicalStream.CONTROL, second.stream());
        assertArrayEquals(filled(3000, 5), second.decoded());
        second.close();
        assertEquals(0, pools.excess.reserved("conn"));
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
        assertEquals(1, pools.excess.waitingCount(), "one outstanding request, not one per retry");
        assertEquals(0, pump.frameAllocations(LogicalStream.CONTROL));

        pools.excess.release("blocker", 8192);
        SessionReadPump.Unit unit = nextUnit(pump);
        assertArrayEquals(filled(3000, 7), unit.decoded(), "exact resume once the declaration is admitted");
        unit.close();

        // Cancellation while held returns every charge and drops the queued request.
        assertTrue(pools.excess.reserve("blocker2", "ip-b", null, 8192, true));
        Source again = new Source(gameWire(LogicalStream.CONTROL, 2, filled(3000, 8)), new byte[0]);
        SessionReadPump held2 = pump(new ServerReadPartition(6), pools, false, again);
        for (int i = 0; i < 10; i++) held2.step();
        assertEquals(1, pools.excess.waitingCount());
        held2.cancel();
        assertEquals(0, pools.excess.waitingCount());
        assertEquals(0, pools.excess.reserved("conn"));
        assertThrows(IllegalStateException.class, held2::step);
    }

    @Test void allClientSlotsCanFinishTheirHead() {
        for (int chunks = 1; chunks <= 6; chunks++) {
            int streams = chunks + 2;
            for (int finishing = 0; finishing < streams; finishing++) {
                ReceiveSlots fresh = new ReceiveSlots(chunks);
                for (int other = 0; other < streams; other++) {
                    if (other == finishing) continue;
                    assertTrue(fresh.admitHead(other, ReceiveSlots.BYTES_PER_STREAM, ReceiveSlots.RECORDS_PER_STREAM));
                }
                assertFalse(fresh.admitHead(finishing, ReceiveSlots.BYTES_PER_STREAM + 1, 1), "over the slot");
                assertTrue(fresh.admitHead(finishing, ReceiveSlots.BYTES_PER_STREAM, ReceiveSlots.RECORDS_PER_STREAM),
                        "stream " + finishing + " of N=" + chunks + " finishes its head");
                assertTrue(fresh.reserveWorkspace(ReceiveSlots.EXECUTION_WORKSPACE));
                fresh.releaseWorkspace(ReceiveSlots.EXECUTION_WORKSPACE);
                assertFalse(fresh.admitHead(finishing, 1, 1), "one head per stream");
            }
        }
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
