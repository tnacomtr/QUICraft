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
import net.minecraft.world.level.block.entity.SignText;
import java.util.Optional;
import java.util.Set;
import rs.sudoe.quicraft.fabric.gametest.ParityRunner.Expect;
import rs.sudoe.quicraft.fabric.gametest.ParityRunner.ParityCase;

/** The parity cases, in the order of the work plan: control sanity, then the r2 list, then the r3 additions. */
final class ParityCases {
    private ParityCases() {}

    /*
     * Version-portable accessors for the 26.x API that moved between builds: ARMOR_STAND lives in EntityTypes from
     * 26.2, the move packet takes a VecDelta from 26.3, and the sign text API changed in 26.2 and 26.3. Reflection
     * keeps one gametest source compiling and exercising the same packets on every 26.x build.
     */

    static EntityType<?> armorStandType() {
        try {
            try {
                return (EntityType<?>) Class.forName("net.minecraft.world.entity.EntityType").getField("ARMOR_STAND").get(null);
            } catch (NoSuchFieldException e) {
                return (EntityType<?>) Class.forName("net.minecraft.world.entity.EntityTypes").getField("ARMOR_STAND").get(null);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no ARMOR_STAND entity type", e);
        }
    }

    /** A relative move packet of (dx, dy, dz) in 1/4096 block units: a VecDelta.Linear from 26.3, plain shorts before. */
    static ClientboundMoveEntityPacket.Pos movePos(int id, short dx, short dy, short dz, boolean onGround) {
        try {
            Class<?> vecDelta;
            try {
                vecDelta = Class.forName("net.minecraft.network.protocol.game.VecDelta");
            } catch (ClassNotFoundException e) {
                return ClientboundMoveEntityPacket.Pos.class
                        .getConstructor(int.class, short.class, short.class, short.class, boolean.class)
                        .newInstance(id, dx, dy, dz, onGround);
            }
            Object delta = Class.forName("net.minecraft.network.protocol.game.VecDelta$Linear")
                    .getConstructor(short.class, short.class, short.class).newInstance(dx, dy, dz);
            return ClientboundMoveEntityPacket.Pos.class.getConstructor(int.class, vecDelta, boolean.class)
                    .newInstance(id, delta, onGround);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no usable ClientboundMoveEntityPacket.Pos constructor", e);
        }
    }

    /** Sets line 0 of the front text: getFrontText/setText(text, bool) before 26.3, the slot API from 26.3. */
    static void setFrontLine(SignBlockEntity entity, Component line) {
        try {
            if (hasMethod(SignBlockEntity.class, "getFrontText")) {
                Object text = SignBlockEntity.class.getMethod("getFrontText").invoke(entity);
                Object updated = text.getClass().getMethod("setMessage", int.class, Component.class).invoke(text, 0, line);
                SignBlockEntity.class.getMethod("setText", SignText.class, boolean.class).invoke(entity, updated, true);
                return;
            }
            Class<?> slot = Class.forName("net.minecraft.world.level.block.entity.SignTextSlot");
            Object front = slot.getField("FRONT").get(null);
            Object text = SignBlockEntity.class.getMethod("getText", slot).invoke(entity, front);
            Object mutable = SignText.class.getMethod("asMutable").invoke(text);
            mutable = mutable.getClass().getMethod("setLine", int.class, Component.class).invoke(mutable, 0, line);
            Object immutable = mutable.getClass().getMethod("asImmutable").invoke(mutable);
            SignBlockEntity.class.getMethod("setText", SignText.class, slot).invoke(entity, immutable, front);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no usable sign text API on " + SignBlockEntity.class.getName(), e);
        }
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    static List<ParityCase> all() {
        List<ParityCase> cases = new ArrayList<>();
        cases.add(new ControlSanity());
        cases.add(new AckCollisionSnap());
        cases.add(new BlockEntityLifecycle());
        cases.add(new ForgetOverflowReinstall());
        cases.add(new PlayerMotionAroundAck());
        cases.add(new VehicleAndMetadataAroundAck());
        cases.add(new RiderPositionSync());
        cases.add(new RespawnWithHeldChunks());
        cases.add(new LoginLikeEpochChange());
        cases.add(new MountPoseAroundAck());
        cases.add(new EquipmentPowderSnowAroundAck());
        cases.add(new DelayedAckMovementAndScale());
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

    // ---- hazard checks -------------------------------------------------------------------------------

    private static double xOf(String value) {
        return Double.parseDouble(value.substring(0, value.indexOf(',')));
    }

    /** The player's x is within 0.05 of {@code x} (player.pos=x,y,z). */
    static ParityRunner.Check playerXNear(double x) {
        return new ParityRunner.Check("player.pos=", "x within 0.05 of " + x, v -> Math.abs(xOf(v) - x) < 0.05);
    }

    /** The player's x is below {@code x}. */
    static ParityRunner.Check playerXBelow(double x) {
        return new ParityRunner.Check("player.pos=", "x below " + x, v -> xOf(v) < x);
    }

    /** The player's x is above {@code x}. */
    static ParityRunner.Check playerXAbove(double x) {
        return new ParityRunner.Check("player.pos=", "x above " + x, v -> xOf(v) > x);
    }

    /** The watched block at {@code pos} is {@code name} (air or stone). */
    static ParityRunner.Check blockIs(BlockPos pos, String name) {
        return new ParityRunner.Check("block." + pos.toShortString() + "=", "is " + name,
                v -> v.startsWith("Block{minecraft:" + name + "}"));
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
                    armorStandType(), 0, Vec3.ZERO, 0.0)));
            items.add(item(f, "move_entity_pos(53)", movePos(ENTITY, (short) 512,
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

        /** The state the control case changes: both inventory slots and the renderer's destruction progress. */
        @Override List<String> expectedTcpLines(ParityFixture f) {
            return List.of("inv.2=" + new ItemStack(Items.DIAMOND, 3), "inv.5=" + new ItemStack(Items.TORCH, 7),
                    "destroy." + (ENTITY + 1) + "=" + f.base.offset(1, 0, 1) + " progress=3");
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
        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            double px = f.basePos.x;
            // Observed: P is air in both orders (84 lands after the ACK either way); the hazard is the player,
            // which the early ACK snaps back to the spawn.
            return List.of(new ParityRunner.Hazard(playerXNear(p(f).getX() + 0.5), playerXNear(px)));
        }

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
                setFrontLine(entity, Component.literal("parity"));
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
        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            return List.of(new ParityRunner.Hazard(blockIs(inNeighbour(f), "air"), blockIs(inNeighbour(f), "stone")));
        }

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
        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            return List.of(new ParityRunner.Hazard(blockIs(inNeighbour(f), "air"), blockIs(inNeighbour(f), "stone")));
        }

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
                f.basePos.x - 2.0, f.basePos.y, f.basePos.z, 0.0F, 0.0F, armorStandType(), 0, Vec3.ZERO, 0.0));
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
     * An entity position sync to block P (centre x/z, rotation 0): the constructor takes a PositionMoveRotation before
     * 26.3 and a PositionPath plus yRot and xRot from 26.3. Reflection keeps one source compiling on every 26.x build.
     */
    static ClientboundEntityPositionSyncPacket positionSync(int id, BlockPos p, boolean onGround) {
        Vec3 at = new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        try {
            Class<?> pathType;
            try {
                pathType = Class.forName("net.minecraft.world.entity.PositionPath");
            } catch (ClassNotFoundException e) {
                return ClientboundEntityPositionSyncPacket.class
                        .getConstructor(int.class, PositionMoveRotation.class, boolean.class)
                        .newInstance(id, into(p), onGround);
            }
            Object path = pathType.getMethod("of", Vec3.class).invoke(null, at);
            return ClientboundEntityPositionSyncPacket.class
                    .getConstructor(int.class, pathType, float.class, float.class, boolean.class)
                    .newInstance(id, path, 0.0F, 0.0F, onGround);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no usable ClientboundEntityPositionSyncPacket constructor", e);
        }
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
        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            // TCP: the ACK snaps the player to the spawn, then the relative teleport moves it from there.
            // Illegal: the teleport starts inside P and marks a teleport, which stops the snap.
            return List.of(new ParityRunner.Hazard(playerXNear(f.basePos.x), playerXNear(p(f).getX() + 0.5)));
        }

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
            items.add(item(f, "entity_position_sync(35)", positionSync(VEHICLE, f.base.offset(0, 0, -3), true)));
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
        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            // TCP: sleeping in P, then the ACK snaps the player back to the spawn. Illegal: the ACK finds the
            // player at the spawn, nothing to snap, and the player then sleeps in P.
            // Same-tick the player sleeps at P's centre; after settling it is pushed about 3.8
            // blocks east of basePos. CI 26.1: -4.5 -> -0.7; 26.1.1: -5.5 -> -1.7.
            // The original threshold 3.0 assumed basePos.x=0.5, not a fixed world origin.
            return List.of(new ParityRunner.Hazard(playerXNear(f.basePos.x),
                    spread ? playerXAbove(f.basePos.x + 2.5) : playerXNear(p(f).getX() + 0.5)));
        }

        @Override int[] illegalOrder(int items) { return new int[] {1, 0, 2, 3}; }
    }

    /**
     * The player rides an armor stand it does not control; 35 moves the vehicle into the block broken by
     * prediction, the ACK restores the stone, 2 follows. The rider is repositioned every tick, so a snap
     * is not observable: this case is structural coverage only (the TCP order's repeatability), not hazard coverage.
     */
    static final class RiderPositionSync extends ParityCase {
        @Override String name() { return "global-35-rider-structural"; }

        @Override Expect expect() { return Expect.TCP_ONLY; }

        private static BlockPos p(ParityFixture f) { return f.base.offset(3, 0, 0); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "entity_position_sync(35) vehicle into P", positionSync(VEHICLE, p(f), true)));
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

        /**
         * Tried and not feasible: the vehicle moves by interpolation and the rider is repositioned every tick,
         * and the snapshot right after delivery is identical for ACK-first and TCP order (player stays at the
         * spawn). So this is repeatability only, and the log says so.
         */
        @Override String structuralOnlyReason() {
            return "a rider's position is derived from its vehicle each tick, so the ACK snap cannot be observed";
        }
    }

    // ---- case 4: epoch changes (same-dimension respawn, login) --------------------------------------

    /** The index of the epoch-change record, found by the table's flag via its packet id 82's label. */
    private static int indexOf(ParityPlanner plan, String label) {
        for (int i = 0; i < plan.steps().size(); i++) {
            if (plan.steps().get(i).label().equals(label)) {
                return i;
            }
        }
        throw new AssertionError("no record labelled " + label);
    }

    /**
     * (a) A same-dimension respawn while chunks are held and a prediction is pending: the client keeps its
     * ClientLevel and prediction map. The sender cannot tell it from a dimension change, so after it every
     * install carries the ack dependency, and nothing assigned after the respawn can cross it. TCP order:
     * respawn (epoch change), ACK, reinstall of a held chunk, install of a new chunk. Illegal: both installs
     * before the ACK, which makes the ACK overwrite the fresh chunk with the stone it restores.
     */
    static final class RespawnWithHeldChunks extends ParityCase {
        @Override String name() { return "epoch-respawn-held-chunks"; }

        @Override Expect expect() { return Expect.REORDERABLE; }

        private int[] fresh;

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            int[] y = neighbourChunk(f);
            fresh = ParityRunner.chunkInOtherShard(f, y[0], y[1]);
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "respawn(82) same dimension", f.sameDimensionRespawn()));
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(new ParityPlanner.Item("level_chunk_with_light(45) reinstall held", f.chunkFrame(y[0], y[1])));
            items.add(new ParityPlanner.Item("level_chunk_with_light(45) new chunk", f.chunkFrame(fresh[0], fresh[1])));
            return items;
        }

        @Override void checkPlan(ParityPlanner plan, List<String> problems) {
            int respawn = indexOf(plan, "respawn(82) same dimension");
            int ack = indexOf(plan, "block_changed_ack(4)");
            for (int i = 0; i < plan.steps().size(); i++) {
                if (i != respawn && i > respawn && !plan.mustFollow(i, respawn)) {
                    problems.add(plan.steps().get(i).label() + " can cross the epoch transition");
                }
            }
            for (String label : List.of("level_chunk_with_light(45) reinstall held", "level_chunk_with_light(45) new chunk")) {
                int install = indexOf(plan, label);
                if (plan.steps().get(install).fence() != rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence.CHUNK_ACK
                        || !plan.mustFollow(install, ack)) {
                    problems.add(label + " after a respawn with chunks held does not carry the ack dependency ("
                            + plan.steps().get(install).fence() + ")");
                }
            }
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            predictedBreak(client.level, inNeighbour(f));
            expectSequence(client.level, 1);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) {
            return List.of(inNeighbour(f), new BlockPos(fresh[0] << 4, f.base.getY() - 1, fresh[1] << 4));
        }

        @Override List<int[]> watchChunks(ParityFixture f) {
            List<int[]> chunks = new ArrayList<>(f.standardChunks());
            chunks.add(fresh);
            return chunks;
        }

        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            return List.of(new ParityRunner.Hazard(blockIs(inNeighbour(f), "air"), blockIs(inNeighbour(f), "stone")));
        }

        @Override int[] illegalOrder(int items) { return new int[] {0, 2, 3, 1}; }
    }

    /**
     * (b) The login-like shape: an epoch change while the sender holds nothing (a fresh session). The
     * first-install exemption survives, so the install of a new chunk floats against the ACK; every order
     * must still match TCP.
     */
    static final class LoginLikeEpochChange extends ParityCase {
        @Override String name() { return "epoch-login-like-nothing-held"; }

        @Override Expect expect() { return Expect.REORDERABLE; }

        private int[] fresh;

        @Override List<int[]> heldChunks(ParityFixture f) { return List.of(); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            int[] y = neighbourChunk(f);
            fresh = ParityRunner.chunkInOtherShard(f, y[0], y[1]);
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "respawn(82) same dimension", f.sameDimensionRespawn()));
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(new ParityPlanner.Item("level_chunk_with_light(45) first install", f.chunkFrame(fresh[0], fresh[1])));
            return items;
        }

        @Override void checkPlan(ParityPlanner plan, List<String> problems) {
            int respawn = indexOf(plan, "respawn(82) same dimension");
            int install = indexOf(plan, "level_chunk_with_light(45) first install");
            int ack = indexOf(plan, "block_changed_ack(4)");
            if (plan.steps().get(install).fence() != rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence.CHUNK) {
                problems.add("the first install after an epoch change with nothing held is "
                        + plan.steps().get(install).fence() + ", not the exempt CHUNK");
            }
            if (!plan.mustFollow(install, respawn) || plan.mustFollow(install, ack)) {
                problems.add("the first install must follow the transition and float against the ACK");
            }
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            predictedBreak(client.level, inNeighbour(f));
            expectSequence(client.level, 1);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) {
            return List.of(inNeighbour(f), new BlockPos(fresh[0] << 4, f.base.getY() - 1, fresh[1] << 4));
        }

        @Override List<int[]> watchChunks(ParityFixture f) {
            List<int[]> chunks = new ArrayList<>(f.standardChunks());
            chunks.add(fresh);
            return chunks;
        }
    }

    // ---- cases 7-9: the r3 additions ----------------------------------------------------------------

    private static net.minecraft.world.item.ItemStack leatherBoots() {
        return new ItemStack(Items.LEATHER_BOOTS);
    }

    /**
     * Case 7. A crawling player straddles two cells; a ceiling block keeps it crawling, and a block above
     * the other cell was broken by prediction. A crawling box (0.6 high) does not touch the restored block,
     * a standing box does. 107 (set_passengers) mounts the player, which forces STANDING. TCP order: ACK,
     * then the mount. Delivered the other way round, the ACK snaps the now-standing player.
     */
    static final class MountPoseAroundAck extends ParityCase {
        @Override String name() { return "mount-pose-107-around-ack"; }

        @Override Expect expect() { return Expect.TCP_ONLY; }

        private static BlockPos ceiling(ParityFixture f) { return f.base.offset(3, 1, 0); }

        private static BlockPos broken(ParityFixture f) { return f.base.offset(4, 1, 0); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            buffer.writeVarInt(VEHICLE);
            buffer.writeVarIntArray(new int[] {f.context.computeOnClient(c -> c.player.getId())});
            Packet<ClientGamePacketListener> passengers = net.minecraft.network.protocol.game
                    .ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(item(f, "set_passengers(107) mount", passengers));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            ClientLevel level = client.level;
            level.setBlock(ceiling(f), Blocks.STONE.defaultBlockState(), 3);
            predictedBreak(level, broken(f));
            addVehicle(client, f);
            expectSequence(level, 1);
            // On the boundary of the two cells, crawling under the ceiling.
            client.player.absSnapTo(broken(f).getX(), f.base.getY(), broken(f).getZ() + 0.5, 0.0F, 0.0F);
            client.player.setPose(net.minecraft.world.entity.Pose.SWIMMING);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) { return List.of(ceiling(f), broken(f)); }

        /** The mounted player is repositioned every tick, which erases the snap. */
        @Override boolean illegalChecksSpread() { return false; }

        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            // TCP: the crawling player is not touching the restored block. Illegal: mounted first (standing),
            // the ACK snaps it back to the spawn.
            return List.of(new ParityRunner.Hazard(playerXNear(broken(f).getX()), playerXNear(f.basePos.x)));
        }

        @Override int[] illegalOrder(int items) { return new int[] {1, 0}; }
    }

    /**
     * Case 8. Powder snow is solid for a player in leather boots only, and the ACK consults that when it
     * restores a block the player overlaps by an epsilon at the surface. TCP: ACK, then 102 (set_equipment)
     * and 108 (set_player_inventory, the feet slot) put the boots on. Boots first would make the restored
     * powder snow solid and snap the player.
     */
    static final class EquipmentPowderSnowAroundAck extends ParityCase {
        @Override String name() { return "equipment-powder-snow-102-108-around-ack"; }

        /** 102 (REALTIME) and 108 (ordinary CONTROL) both follow the ACK but not each other, so they can swap. */
        @Override Expect expect() { return Expect.REORDERABLE; }

        private static BlockPos t(ParityFixture f) { return f.base.offset(3, -1, 0); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            int playerId = f.context.computeOnClient(c -> c.player.getId());
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(item(f, "set_equipment(102) boots", new net.minecraft.network.protocol.game
                    .ClientboundSetEquipmentPacket(playerId, List.of(com.mojang.datafixers.util.Pair.of(
                            net.minecraft.world.entity.EquipmentSlot.FEET, leatherBoots())))));
            items.add(item(f, "set_player_inventory(108) feet slot", new ClientboundSetPlayerInventoryPacket(36,
                    leatherBoots())));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            ClientLevel level = client.level;
            BlockPos t = t(f);
            level.setBlock(t, Blocks.POWDER_SNOW.defaultBlockState(), 3);
            ParityFixture.predict(level, t, Blocks.AIR.defaultBlockState());
            expectSequence(level, 1);
            // Standing on the surface with a sliver of overlap: inside the epsilon the context allows.
            client.player.absSnapTo(t.getX() + 0.5, t.getY() + 1 - 5.0E-6, t.getZ() + 0.5, 0.0F, 0.0F);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) { return List.of(t(f)); }

        /** Observed: after the settle ticks both orders end in the powder snow (3.5, -61, 0.5), so only the same-tick snap is a hazard. */
        @Override boolean illegalChecksSpread() { return false; }

        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            // TCP: bare feet at the ACK, powder snow not solid, no snap. Illegal: boots on first, solid, snap to spawn.
            return List.of(new ParityRunner.Hazard(playerXNear(t(f).getX() + 0.5), playerXNear(f.basePos.x)));
        }

        @Override int[] illegalOrder(int items) { return new int[] {1, 2, 0}; }
    }

    /**
     * Case 9. A predicted-broken block sits one step beside the player. 131 (update_attributes, SCALE 2)
     * and 101 (set_entity_motion) are REALTIME; they follow the ACK in TCP order. Before the ACK they would
     * let the scaled or moving player overlap the block the ACK restores. The effect needs ticks, so the
     * illegal-order check runs in the tick-spread mode only.
     */
    static final class DelayedAckMovementAndScale extends ParityCase {
        @Override String name() { return "delayed-ack-131-101"; }

        @Override Expect expect() { return Expect.TCP_ONLY; }

        @Override boolean illegalNeedsSpread() { return true; }

        private static BlockPos t(ParityFixture f) { return f.base.offset(3, 0, 0); }

        @Override List<ParityPlanner.Item> items(ParityFixture f) {
            int playerId = f.context.computeOnClient(c -> c.player.getId());
            net.minecraft.world.entity.ai.attributes.AttributeInstance scale =
                    new net.minecraft.world.entity.ai.attributes.AttributeInstance(
                            net.minecraft.world.entity.ai.attributes.Attributes.SCALE, i -> { });
            scale.setBaseValue(2.0);
            List<ParityPlanner.Item> items = new ArrayList<>();
            items.add(item(f, "block_changed_ack(4)", ack(1)));
            items.add(item(f, "update_attributes(131) scale 2", new net.minecraft.network.protocol.game
                    .ClientboundUpdateAttributesPacket(playerId, List.of(scale))));
            items.add(item(f, "set_entity_motion(101)", new net.minecraft.network.protocol.game
                    .ClientboundSetEntityMotionPacket(playerId, new Vec3(0.25, 0.0, 0.0))));
            return items;
        }

        @Override void setup(Minecraft client, ParityFixture f) {
            predictedBreak(client.level, t(f));
            expectSequence(client.level, 1);
            client.player.absSnapTo(t(f).getX() - 0.35, f.base.getY(), t(f).getZ() + 0.5, 0.0F, 0.0F);
        }

        @Override List<BlockPos> watchBlocks(ParityFixture f) { return List.of(t(f)); }

        @Override List<ParityRunner.Hazard> hazards(ParityFixture f, boolean spread) {
            // TCP ends about basePos.x+1.896; illegal order overlaps the restored block, so the ACK
            // snaps to basePos and remaining motion ends about basePos.x+0.397. Both offsets occur
            // on 26.1 (-4.5 base) and 26.1.1 (-5.5 base), as on 26.1.2 (0.5 base).
            return List.of(new ParityRunner.Hazard(playerXAbove(f.basePos.x + 1.0),
                    playerXBelow(f.basePos.x + 0.7)));
        }

        @Override int[] illegalOrder(int items) { return new int[] {1, 2, 0}; }

        /** Without a prediction the ACK has no rollback, so the player must not end near the spawn. */
        @Override List<ParityRunner.Check> controlForbidden(ParityFixture f, boolean spread) {
            return List.of(playerXBelow(f.basePos.x + 0.7));
        }

        @Override boolean hasControl() { return true; }

        /** The same block and player, but the break was never predicted: the ACK has nothing to roll back. */
        @Override void setupControl(Minecraft client, ParityFixture f) {
            client.level.setBlock(t(f), Blocks.STONE.defaultBlockState(), 3);
            ParityFixture.predict(client.level, f.base.offset(0, 0, -6), client.level.getBlockState(f.base.offset(0, 0, -6)));
            client.player.absSnapTo(t(f).getX() - 0.35, f.base.getY(), t(f).getZ() + 0.5, 0.0F, 0.0F);
        }
    }
}
