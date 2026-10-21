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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import java.util.Optional;
import java.util.Set;
import rs.sudoe.quicraft.fabric.gametest.ParityRunner.Expect;
import rs.sudoe.quicraft.fabric.gametest.ParityRunner.ParityCase;

/** The parity cases, in the order of the work plan: control sanity, then the r2 list, then the r3 additions. */
final class ParityCases {
    private ParityCases() {}

    static List<ParityCase> all() {
        List<ParityCase> cases = new ArrayList<>();
        cases.add(new ControlSanity());
        cases.add(new AckCollisionSnap());
        cases.add(new BlockEntityLifecycle());
        cases.add(new ForgetOverflowReinstall());
        cases.add(new PlayerMotionAroundAck());
        cases.add(new VehicleAndMetadataAroundAck());
        cases.add(new RiderPositionSync());
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

    // ---- shared fixtures ------------------------------------------------------------------------

    /** A block position inside the neighbour chunk (cx+1, cz) of the spawn chunk. */
    static BlockPos inNeighbour(ParityFixture f) {
        return new BlockPos(((f.base.getX() >> 4) + 1) * 16 + 2, f.base.getY(), (f.base.getZ() >> 4) * 16 + 2);
    }

    static int[] neighbourChunk(ParityFixture f) {
        return new int[] {(f.base.getX() >> 4) + 1, f.base.getZ() >> 4};
    }

    /** Local stone at pos, then a predicted break of it: the retained state is stone, the player position the spawn. */
    static void predictedBreak(ClientLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ParityFixture.predict(level, pos, Blocks.AIR.defaultBlockState());
    }

    static void expectSequence(ClientLevel level, int expected) {
        int sequence = (Integer) ParityFixture.getField(ParityFixture.predictionHandler(level), "currentSequenceNr");
        if (sequence != expected) {
            throw new AssertionError("fixture made " + sequence + " predictions, the ACK covers " + expected);
        }
    }

    // ---- case 2: block-entity lifecycle around a pending ACK -------------------------------------

    /**
     * A predicted sign at B whose server data (6) follows its block_update (8), and a predicted break of
     * C (server truth: stone) in the neighbour chunk, which the server then forgets (37) and reinstalls
     * (45 from the real server, where C is air). The ACK must trail 8 and 6 and precede the forget and the
     * reinstall: reinstalled before the ACK, the chunk would later be overwritten with the stone the ACK
     * restores.
     */
    static final class BlockEntityLifecycle extends ParityCase {
        private static final int ACK_SEQUENCE = 2;

        @Override String name() { return "block-entity-lifecycle"; }

        @Override Expect expect() { return Expect.TCP_ONLY; }

        private static BlockPos b(ParityFixture f) { return f.base.offset(0, 0, 3); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            BlockPos b = b(f);
            BlockState sign = Blocks.OAK_SIGN.defaultBlockState();
            Packet<ClientGamePacketListener> data = f.server.computeOnServer(s -> {
                net.minecraft.server.level.ServerLevel level = s.overworld();
                level.setBlock(b, sign, 3);
                SignBlockEntity entity = (SignBlockEntity) level.getBlockEntity(b);
                entity.setText(entity.getFrontText().setMessage(0, Component.literal("parity")), true);
                Packet<ClientGamePacketListener> packet = ClientboundBlockEntityDataPacket.create(entity);
                level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
                return packet;
            });
            int[] y = neighbourChunk(f);
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "block_update(8) sign", blockUpdate(b, sign)));
            items.add(item(f, "block_entity_data(6) sign", data));
            items.add(item(f, "block_changed_ack(4)", ack(ACK_SEQUENCE)));
            items.add(item(f, "forget_level_chunk(37)", new ClientboundForgetLevelChunkPacket(new ChunkPos(y[0], y[1]))));
            items.add(new ParityPlanner.Item("level_chunk_with_light(45) reinstall", f.chunkFrame(y[0], y[1])));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            ClientLevel level = client.level;
            ParityFixture.predict(level, b(f), Blocks.OAK_SIGN.defaultBlockState());
            predictedBreak(level, inNeighbour(f));
            expectSequence(level, ACK_SEQUENCE);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) {
            return List.of(b(f), inNeighbour(f));
        }

        /** Forget and reinstall before the ACK: the ACK then restores stone over the fresh chunk. */
        @Override int[] illegalOrder(int items) {
            return new int[] {3, 4, 2, 0, 1};
        }
    }

    // ---- case 3: 257 distinct forgets, then a reinstall -------------------------------------------

    /**
     * The held-chunk ring overflows on the 257th distinct forget (13.21), after which every install must
     * wait for the latest ACK. A predicted break is pending in the forgotten chunk Y; the TCP order is
     * 257 forgets (Y first), the ACK, the reinstall of Y. The planner must show the reinstall depending on
     * the ACK as a CHUNK_ACK record, and no derived order may differ from TCP on the client.
     */
    static final class ForgetOverflowReinstall extends ParityCase {
        @Override String name() { return "forget-overflow-reinstall"; }

        @Override Expect expect() { return Expect.REORDERABLE; }

        @Override boolean spread() { return false; }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            int[] y = neighbourChunk(f);
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "forget Y", new ClientboundForgetLevelChunkPacket(new ChunkPos(y[0], y[1]))));
            for (int i = 0; i < 256; i++) {
                items.add(item(f, "forget far#" + i, new ClientboundForgetLevelChunkPacket(new ChunkPos(5000 + i, 5000))));
            }
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(new ParityPlanner.Item("level_chunk_with_light(45) reinstall Y", f.chunkFrame(y[0], y[1])));
            return items;
        }

        @Override void checkPlan(ParityPlanner plan, List<String> problems) {
            int n = plan.steps().size();
            ParityPlanner.Step reinstall = plan.steps().get(n - 1);
            if (reinstall.fence() != rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence.CHUNK_ACK) {
                problems.add("the reinstall after 257 distinct forgets is " + reinstall.fence() + ", not CHUNK_ACK");
            }
            if (!plan.mustFollow(n - 1, n - 2)) {
                problems.add("the reinstall after 257 distinct forgets does not wait for the ACK");
            }
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            predictedBreak(client.level, inNeighbour(f));
            expectSequence(client.level, 1);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) {
            return List.of(inNeighbour(f));
        }

        /** The reinstall before the ACK. */
        @Override int[] illegalOrder(int items) {
            int[] order = new int[items];
            for (int i = 0; i < items; i++) {
                order[i] = i;
            }
            order[items - 2] = items - 1;
            order[items - 1] = items - 2;
            return order;
        }
    }

    // ---- cases 5 and 6: player motion, metadata and vehicles around an ACK -----------------------

    private static final int VEHICLE = 900010;

    /** An armor stand at the spawn side, added by the real handler. */
    static void addVehicle(Minecraft client, ParityFixture f) {
        ParityFixture.handle(client, new ClientboundAddEntityPacket(VEHICLE, UUID.nameUUIDFromBytes("vehicle".getBytes()),
                f.basePos.x - 2.0, f.basePos.y, f.basePos.z, 0.0F, 0.0F, EntityType.ARMOR_STAND, 0, Vec3.ZERO, 0.0));
    }

    /** Stone at P with a predicted break (recorded player position: the spawn), then the player standing in P. */
    static void playerInBrokenBlock(Minecraft client, BlockPos p) {
        predictedBreak(client.level, p);
        client.player.absSnapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
        expectSequence(client.level, 1);
    }

    static PositionMoveRotation into(BlockPos p) {
        return new PositionMoveRotation(new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5), Vec3.ZERO, 0.0F, 0.0F);
    }

    /**
     * The player stands in a block it broke by prediction; the ACK restores the stone and snaps the player
     * back to the spawn, then 72 (player_position, relative) moves it by a delta from wherever it is, 71
     * (look_at) turns it and 125 (teleport_entity) moves another entity. All three are GLOBAL, so the
     * derived order is TCP's. A relative 72 delivered before the ACK would start from inside the block and
     * mark a teleport that stops the snap.
     */
    static final class PlayerMotionAroundAck extends ParityCase {
        @Override String name() { return "global-71-72-125-around-ack"; }

        @Override Expect expect() { return Expect.TCP_ONLY; }

        private static BlockPos p(ParityFixture f) { return f.base.offset(3, 0, 0); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(item(f, "player_position(72) relative", new ClientboundPlayerPositionPacket(7,
                    new PositionMoveRotation(new Vec3(0, 0, -3), Vec3.ZERO, 90.0F, 10.0F),
                    Set.of(Relative.X, Relative.Y, Relative.Z))));
            items.add(item(f, "player_look_at(71)", new ClientboundPlayerLookAtPacket(EntityAnchorArgument.Anchor.EYES,
                    f.basePos.x + 4, f.basePos.y + 1, f.basePos.z - 3)));
            items.add(item(f, "teleport_entity(125)", new ClientboundTeleportEntityPacket(VEHICLE, into(p(f)), Set.of(),
                    true)));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            playerInBrokenBlock(client, p(f));
            addVehicle(client, f);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) { return List.of(p(f)); }

        /** The relative teleport before the ACK. */
        @Override int[] illegalOrder(int items) { return new int[] {1, 0, 2, 3}; }
    }

    /**
     * The block the player broke by prediction is the bed cell: 99 (set_entity_data with a sleeping pose
     * and sleeping position) moves the player into it, then the ACK restores the stone and snaps the player
     * back; 35 (entity_position_sync) and 2 (animate wake-up) follow. All are GLOBAL.
     */
    static final class VehicleAndMetadataAroundAck extends ParityCase {
        @Override String name() { return "global-2-99-35-around-ack"; }

        @Override Expect expect() { return Expect.TCP_ONLY; }

        private static BlockPos p(ParityFixture f) { return f.base.offset(3, 0, 0); }

        @SuppressWarnings("unchecked")
        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            List<ParityPlanner.Item> items = new ArrayList<>();
            net.minecraft.network.syncher.EntityDataAccessor<Optional<BlockPos>> sleeping =
                    (net.minecraft.network.syncher.EntityDataAccessor<Optional<BlockPos>>) ParityFixture.staticField(
                            net.minecraft.world.entity.LivingEntity.class, "SLEEPING_POS_ID");
            net.minecraft.network.syncher.EntityDataAccessor<net.minecraft.world.entity.Pose> pose =
                    (net.minecraft.network.syncher.EntityDataAccessor<net.minecraft.world.entity.Pose>)
                    ParityFixture.staticField(Entity.class, "DATA_POSE");
            int playerId = f.context.computeOnClient(c -> c.player.getId());
            items.add(item(f, "set_entity_data(99) sleeping position", new ClientboundSetEntityDataPacket(playerId,
                    List.of(net.minecraft.network.syncher.SynchedEntityData.DataValue.create(pose,
                            net.minecraft.world.entity.Pose.SLEEPING),
                            net.minecraft.network.syncher.SynchedEntityData.DataValue.create(sleeping,
                                    Optional.of(p(f)))))));
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(item(f, "entity_position_sync(35)", new ClientboundEntityPositionSyncPacket(VEHICLE,
                    into(f.base.offset(0, 0, -3)), true)));
            items.add(item(f, "animate(2) wake up", new ClientboundAnimatePacket(f.context.computeOnClient(
                    c -> c.player), ClientboundAnimatePacket.WAKE_UP)));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            predictedBreak(client.level, p(f));
            addVehicle(client, f);
            expectSequence(client.level, 1);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) { return List.of(p(f)); }

        /** The ACK before the sleeping position. */
        @Override int[] illegalOrder(int items) { return new int[] {1, 0, 2, 3}; }
    }

    /**
     * The player rides an armor stand it does not control; 35 moves the vehicle into the block broken by
     * prediction, the ACK restores the stone, 2 follows. The rider is repositioned every tick, so a snap
     * is not observable and this case has no illegal-order check; it is a parity run only (reported).
     */
    static final class RiderPositionSync extends ParityCase {
        @Override String name() { return "global-35-rider"; }

        @Override Expect expect() { return Expect.TCP_ONLY; }

        private static BlockPos p(ParityFixture f) { return f.base.offset(3, 0, 0); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "entity_position_sync(35) vehicle into P", new ClientboundEntityPositionSyncPacket(VEHICLE,
                    into(p(f)), true)));
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(item(f, "animate(2) wake up", new ClientboundAnimatePacket(f.context.computeOnClient(
                    c -> c.player), ClientboundAnimatePacket.WAKE_UP)));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            predictedBreak(client.level, p(f));
            addVehicle(client, f);
            client.player.startRiding(client.level.getEntity(VEHICLE), true, false);
            expectSequence(client.level, 1);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) { return List.of(p(f)); }
    }
}
