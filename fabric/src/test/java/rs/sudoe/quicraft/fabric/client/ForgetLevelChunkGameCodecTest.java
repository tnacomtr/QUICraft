// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;

/**
 * Evidence for {@code mc775.chunk_pos_long.v1} (13.7): the real game encoder writes forget_level_chunk
 * (37) as one packed {@code ChunkPos} long after the packet id. The packet is produced by the game's
 * own play-packet codec ({@link ClientboundForgetLevelChunkPacket#STREAM_CODEC}) for negative and
 * positive coordinates; the extractor must return exactly the positions the game encoded.
 */
class ForgetLevelChunkGameCodecTest {
    private static final int FORGET_LEVEL_CHUNK = 37;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void gameEncoderProducesThePackedLongTheExtractorReads() {
        // The captured-vector file has no 37 frame (forget packets are rare in a capture run), so the
        // game's own encoder is the evidence: protocol 775 writes this id plus ChunkPos.pack().
        int[][] chunks = {
            {0, 0}, {-1, 0}, {0, -1}, {1, 2}, {-800, 1200}, {800, -1200}, {-1875000, 1875000}
        };
        ExtractorRegistry.Decoder decoder = ExtractorRegistry.productionDecoder("mc775.chunk_pos_long.v1");
        for (int[] chunk : chunks) {
            byte[] wire = encodeForget(chunk[0], chunk[1]);
            assertEquals(9, wire.length, "id VarInt plus one long: " + chunk[0] + "," + chunk[1]);
            Extractor.Position position = decoder.extract(wire);
            assertEquals(chunk[0], position.chunkX, "game-encoded x for " + chunk[0] + "," + chunk[1]);
            assertEquals(chunk[1], position.chunkZ, "game-encoded z for " + chunk[0] + "," + chunk[1]);
            // The layout is the game's ChunkPos.pack, so the wire also matches it byte for byte.
            assertEquals(ChunkPos.pack(chunk[0], chunk[1]),
                    ((long) (wire[1] & 255) << 56) | ((long) (wire[2] & 255) << 48)
                            | ((long) (wire[3] & 255) << 40) | ((long) (wire[4] & 255) << 32)
                            | ((long) (wire[5] & 255) << 24) | ((long) (wire[6] & 255) << 16)
                            | ((long) (wire[7] & 255) << 8) | (wire[8] & 255),
                    "wire long is ChunkPos.pack: " + chunk[0] + "," + chunk[1]);
        }
    }

    /** The real game encoder's bytes for one forget packet, with its protocol id prefixed. */
    private static byte[] encodeForget(int x, int z) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(buffer, FORGET_LEVEL_CHUNK);
            ClientboundForgetLevelChunkPacket.STREAM_CODEC.encode(buffer,
                    new ClientboundForgetLevelChunkPacket(new ChunkPos(x, z)));
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }
}
