// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.ShardHash;
import rs.sudoe.quicraft.core.routing.Extractor;

/**
 * {@code mc775.chunk_pos_long.v1} (13.7): one big-endian packed {@code ChunkPos} long after the
 * packet id, x in the low 32 bits and z in the high 32 ({@code ChunkPos.pack}, protocol 775).
 * forget_level_chunk (37) writes exactly that long ({@code FriendlyByteBuf.writeChunkPos}), so the
 * int,int {@code chunk_xz} layout would read its coordinates swapped and must not be reused.
 */
class ChunkPosLongExtractorTest {
    private static final int FORGET_LEVEL_CHUNK = 37;
    private static final int LEVEL_CHUNK_WITH_LIGHT = 45;

    @Test
    void roundTripsNegativeAndPositiveCoordinatesThroughTheGamesPacking() {
        ExtractorRegistry.Decoder decoder = ExtractorRegistry.productionDecoder("mc775.chunk_pos_long.v1");
        int[][] chunks = {
            {0, 0}, {1, 2}, {-1, 0}, {0, -1}, {-1, -1},
            {-800, 1200}, {800, -1200}, {-1875000, 1875000}, {1875000, -1875000}
        };
        for (int[] chunk : chunks) {
            byte[] wire = packet(FORGET_LEVEL_CHUNK, pack(chunk[0], chunk[1]));
            Extractor.Position position = decoder.extract(wire);
            assertEquals(chunk[0], position.chunkX, "x for " + chunk[0] + "," + chunk[1]);
            assertEquals(chunk[1], position.chunkZ, "z for " + chunk[0] + "," + chunk[1]);
        }
        // ChunkPos.pack uses x & 0xffffffff | (z & 0xffffffff) << 32, so the full 32-bit range
        // round-trips, including Integer.MIN_VALUE (not a legal chunk, but the packing's behaviour).
        byte[] extreme = packet(FORGET_LEVEL_CHUNK, pack(Integer.MIN_VALUE, Integer.MAX_VALUE));
        Extractor.Position position = decoder.extract(extreme);
        assertEquals(Integer.MIN_VALUE, position.chunkX);
        assertEquals(Integer.MAX_VALUE, position.chunkZ);
    }

    @Test
    void forgetLevelChunkAndChunkInstallForTheSameChunkShareAChunkShard() {
        ExtractorRegistry.Decoder forget = ExtractorRegistry.productionDecoder("mc775.chunk_pos_long.v1");
        ExtractorRegistry.Decoder install = ExtractorRegistry.productionDecoder("mc775.chunk_xz.v1");
        int[][] chunks = {{-1, 0}, {0, -1}, {-7, 8}, {123, -456}};
        for (int[] chunk : chunks) {
            Extractor.Position forgotten = forget.extract(packet(FORGET_LEVEL_CHUNK, pack(chunk[0], chunk[1])));
            Extractor.Position installed = install.extract(chunkXzPacket(LEVEL_CHUNK_WITH_LIGHT, chunk[0], chunk[1]));
            assertEquals(chunk[0], forgotten.chunkX);
            assertEquals(chunk[1], forgotten.chunkZ);
            assertEquals(installed.chunkX, forgotten.chunkX);
            assertEquals(installed.chunkZ, forgotten.chunkZ);
            for (int shards : new int[] {1, 2, 3, 5}) {
                assertEquals(ShardHash.shardOf(installed.chunkX, installed.chunkZ, 0L, shards),
                        ShardHash.shardOf(forgotten.chunkX, forgotten.chunkZ, 0L, shards),
                        "37 and 45 for chunk " + chunk[0] + "," + chunk[1] + " with N=" + shards);
            }
        }
    }

    @Test
    void aTruncatedPackedLongIsRejected() {
        ExtractorRegistry.Decoder decoder = ExtractorRegistry.productionDecoder("mc775.chunk_pos_long.v1");
        byte[] full = packet(FORGET_LEVEL_CHUNK, pack(-1, 0));
        for (int length = 0; length < full.length; length++) {
            byte[] cut = java.util.Arrays.copyOf(full, length);
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(cut),
                    "cut to " + length + " must be short input");
        }
        assertEquals(-1, decoder.extract(full).chunkX);
    }

    /** The game's {@code ChunkPos.pack}: x in the low 32 bits, z in the high 32. */
    private static long pack(int x, int z) {
        return x & 0xffffffffL | (z & 0xffffffffL) << 32;
    }

    /** Packet {@code id} (one-byte VarInt) followed by one big-endian packed ChunkPos. */
    private static byte[] packet(int id, long packed) {
        return ByteBuffer.allocate(9).put((byte) id).putLong(packed).array();
    }

    /** {@code chunk_xz} wire: id, big-endian int32 x, big-endian int32 z. */
    private static byte[] chunkXzPacket(int id, int x, int z) {
        return ByteBuffer.allocate(9).put((byte) id).putInt(x).putInt(z).array();
    }
}
