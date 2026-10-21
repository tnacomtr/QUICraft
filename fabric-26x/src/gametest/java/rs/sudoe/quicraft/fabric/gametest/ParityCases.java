// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import rs.sudoe.quicraft.fabric.gametest.ParityRunner.Expect;
import rs.sudoe.quicraft.fabric.gametest.ParityRunner.ParityCase;

/** The parity cases, in the order of the work plan: control sanity, then the r2 list, then the r3 additions. */
final class ParityCases {
    private ParityCases() {}

    static List<ParityCase> all() {
        List<ParityCase> cases = new ArrayList<>();
        cases.add(new ControlSanity());
        cases.add(new AckCollisionSnap());
        return cases;
    }

    // ---- packet builders ----------------------------------------------------------------------

    static ParityPlanner.Item item(ParityFixture f, String label, Packet<ClientGamePacketListener> packet) {
        return new ParityPlanner.Item(label, f.encode(packet));
    }

    static Packet<ClientGamePacketListener> blockUpdate(BlockPos pos, BlockState state) {
        return new ClientboundBlockUpdatePacket(pos, state);
    }

    /** A section_blocks_update (84) with one change, built through the game's own codec. */
    static Packet<ClientGamePacketListener> sectionUpdate(BlockPos pos, BlockState state) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SectionPos.STREAM_CODEC.encode(buffer, SectionPos.of(pos));
        buffer.writeVarInt(1);
        buffer.writeVarLong((long) Block.getId(state) << 12 | SectionPos.sectionRelativePos(pos));
        return ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buffer);
    }

    static Packet<ClientGamePacketListener> ack(int sequence) {
        return new ClientboundBlockChangedAckPacket(sequence);
    }

    // ---- case 10: control sanity ----------------------------------------------------------------

    /**
     * Entity creation (CONTROL fence), two REALTIME entity packets and two ordinary inventory packets:
     * the shipped routes let the two groups interleave, so the harness has real reorders to run.
     */
    static final class ControlSanity extends ParityCase {
        private static final int ENTITY = 900001;

        @Override String name() { return "control-sanity"; }

        @Override Expect expect() { return Expect.REORDERABLE; }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            BlockPos at = f.base.offset(2, 0, 2);
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "add_entity(1)", new ClientboundAddEntityPacket(ENTITY, UUID.nameUUIDFromBytes(
                    "parity".getBytes()), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F,
                    EntityType.ARMOR_STAND, 0, Vec3.ZERO, 0.0)));
            items.add(item(f, "move_entity_pos(53)", new ClientboundMoveEntityPacket.Pos(ENTITY, (short) 512,
                    (short) 0, (short) 0, true)));
            items.add(item(f, "block_destruction(5)", new ClientboundBlockDestructionPacket(ENTITY + 1,
                    f.base.offset(1, 0, 1), 3)));
            items.add(item(f, "set_player_inventory(108)#2", new ClientboundSetPlayerInventoryPacket(2,
                    new ItemStack(Items.DIAMOND, 3))));
            items.add(item(f, "set_player_inventory(108)#5", new ClientboundSetPlayerInventoryPacket(5,
                    new ItemStack(Items.TORCH, 7))));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {}

        @Override List<BlockPos> watchBlocks(ParityFixture f) {
            return List.of(f.base.offset(1, 0, 1));
        }
    }

    // ---- case 1: ACK, then 8/84 collision snap -------------------------------------------------

    /**
     * Two outstanding predictions (a break of P with the player now standing in P, a placement at Q)
     * and the server's real emission order: block_update (8) for Q, section_blocks_update (84) for P,
     * then block_changed_ack (4), then a first install (45) of an unrelated chunk. Delivering the ACK
     * before 84 restores stone at P and snaps the player back to the position recorded when the
     * prediction was made; delivering 84 first leaves the player in the now-empty P.
     */
    static final class AckCollisionSnap extends ParityCase {
        private static final int ACK_SEQUENCE = 2;
        private int[] otherChunk;

        @Override String name() { return "ack-collision-snap-8-84"; }

        @Override Expect expect() { return Expect.REORDERABLE; }

        private static BlockPos p(ParityFixture f) { return f.base.offset(3, 0, 0); }

        private static BlockPos q(ParityFixture f) { return f.base.offset(3, 0, 2); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            BlockPos p = p(f);
            otherChunk = ParityRunner.chunkInOtherShard(f, p.getX() >> 4, p.getZ() >> 4);
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "block_update(8) Q", blockUpdate(q(f), Blocks.STONE.defaultBlockState())));
            items.add(item(f, "section_blocks_update(84) P", sectionUpdate(p, Blocks.AIR.defaultBlockState())));
            items.add(item(f, "block_changed_ack(4)", ack(ACK_SEQUENCE)));
            items.add(new ParityPlanner.Item("level_chunk_with_light(45) other chunk",
                    f.chunkFrame(otherChunk[0], otherChunk[1])));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            ClientLevel level = client.level;
            BlockPos p = p(f);
            level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
            ParityFixture.predict(level, p, Blocks.AIR.defaultBlockState());
            ParityFixture.predict(level, q(f), Blocks.STONE.defaultBlockState());
            client.player.absSnapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
            int sequence = (Integer) ParityFixture.getField(ParityFixture.predictionHandler(level), "currentSequenceNr");
            if (sequence != ACK_SEQUENCE) {
                throw new AssertionError("fixture made " + sequence + " predictions, the ACK covers " + ACK_SEQUENCE);
            }
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) {
            return List.of(p(f), q(f), new BlockPos(otherChunk[0] << 4, f.base.getY() - 1, otherChunk[1] << 4),
                    new BlockPos(otherChunk[0] << 4 | 7, f.base.getY() - 1, otherChunk[1] << 4 | 7));
        }

        @Override List<int[]> watchChunks(ParityFixture f) {
            List<int[]> chunks = new ArrayList<>(f.standardChunks());
            chunks.add(otherChunk);
            return chunks;
        }

        /** The ACK first: the order the universal ACK rule exists to forbid. */
        @Override int[] illegalOrder(int items) {
            return new int[] {2, 0, 1, 3};
        }
    }
}
