// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.ShardHash;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;
import rs.sudoe.quicraft.fabric.net.FrameCodec;

/** Wire-layout verification for block updates (8, 6, 84) shipped on chunk shards (13.7, 13.19). */
class BlockUpdateChunkShardTest {

    /** BlockPos is a packed long (BlockPos.STREAM_CODEC / FriendlyByteBuf.readBlockPos): x bits 38-63,
     * z bits 12-37, y bits 0-11. Chunk = floor(x/16), floor(z/16). */
    @Test
    void blockUpdateWireIsPackedLongAndDecodesToChunkShard() throws Exception {
        // Captured-775-vectors line for packet 8, chunk (-1, 0); same chunk as chunk_xz (-1, 0).
        byte[] wire = hex("08fffffe8000006fcae829");
        assertEquals(8, varInt(wire), "packet id from wire");
        var extractor = ExtractorRegistry.productionDecoder("mc775.block_position.v1");
        var pos = extractor.extract(wire);
        assertEquals(-1, pos.chunkX);
        assertEquals(0, pos.chunkZ);
        assertEquals(ShardHash.shardOf(-1, 0, 0, 2), ShardHash.shardOf(pos.chunkX, pos.chunkZ, 0, 2),
                "negative-coordinate block update lands on the same shard as chunk_xz at (-1, 0)");
    }

    @Test
    void blockEntityDataWireIsPackedLongAndDecodesToChunkShard() throws Exception {
        // Packet 6, chunk (0, 0) - positive nonnegative; same chunk as level_chunk_with_light at (0, 0).
        byte[] wire = hex("06000000000000000000");
        assertEquals(6, varInt(wire));
        var extractor = ExtractorRegistry.productionDecoder("mc775.block_position.v1");
        var pos = extractor.extract(wire);
        assertEquals(0, pos.chunkX);
        assertEquals(0, pos.chunkZ);
        assertEquals(ShardHash.shardOf(0, 0, 0, 2), ShardHash.shardOf(pos.chunkX, pos.chunkZ, 0, 2),
                "block entity data at origin lands on the same shard as level_chunk_with_light at (0, 0)");
    }

    @Test
    void sectionBlocksUpdateWireIsPackedSectionPosAndDecodesToChunkShard() throws Exception {
        // Packet 84, chunk (0, -1) from captured-775-vectors (section blocks update).
        byte[] wire = hex("54000003fffff00000");
        assertEquals(84, varInt(wire));
        var extractor = ExtractorRegistry.productionDecoder("mc775.section_position.v1");
        var pos = extractor.extract(wire);
        assertEquals(0, pos.chunkX, "section position x maps directly to chunk x");
        assertEquals(-1, pos.chunkZ, "section position z maps directly to chunk z");
        assertEquals(ShardHash.shardOf(0, -1, 0, 2), ShardHash.shardOf(pos.chunkX, pos.chunkZ, 0, 2),
                "section update lands on the chunk shard");
    }

    @Test
    void compressedFramesStillDecodeCorrectlyAtThresholdZero() throws Exception {
        // LaneCodecChunkPositionTest pattern: FrameCodec.encode with threshold 0 (always compressed).
        // The extractor reads the uncompressed packet bytes, never the compressed form.
        byte[][] packets = {
            hex("08fffffe8000006fcae829"),  // packet 8, chunk (-1, 0)
            hex("06000000000000000000"),  // packet 6, chunk (0, 0)
            hex("54000003fffff00000")    // packet 84, chunk (0, -1)
        };
        int[] ids = {8, 6, 84};
        String[] extractors = {"mc775.block_position.v1", "mc775.block_position.v1", "mc775.section_position.v1"};
        int[][] expectedChunk = {{-1, 0}, {0, 0}, {0, -1}};
        for (int i = 0; i < packets.length; i++) {
            byte[] uncompressed = packets[i];
            byte[] compressedFrame = FrameCodec.encode(uncompressed, 0);
            // The routing prefix (LaneCodec) retains only the bounded prefix, but the extractor
            // reads from the full uncompressed packet that the codec produces.
            var extractor = ExtractorRegistry.productionDecoder(extractors[i]);
            var pos = extractor.extract(uncompressed);
            assertEquals(ids[i], varInt(uncompressed), "packet id preserved through compression");
            assertEquals(expectedChunk[i][0], pos.chunkX, "chunk x for packet " + ids[i]);
            assertEquals(expectedChunk[i][1], pos.chunkZ, "chunk z for packet " + ids[i]);
        }
    }

    private static int varInt(byte[] data) {
        int value = 0;
        for (int i = 0; i < 5 && i < data.length; i++) {
            int b = data[i] & 0xff;
            value |= (b & 0x7f) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("invalid varint in wire");
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
