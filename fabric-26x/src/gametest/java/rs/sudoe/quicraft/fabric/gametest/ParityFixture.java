// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;

/**
 * The real-handler parity harness (security review r2/r3): a real joined client whose
 * ClientPacketListener, ClientLevel and LocalPlayer handle injected frames, isolated from the
 * server so that only the injected packets change client state.
 *
 * <p>Isolation: the server's tick is frozen and an outbound handler on the client pipeline drops
 * every serverbound packet except keep-alive and pong, so the server never answers a prediction or
 * "corrects" an injected position. State is reset between runs by two respawn packets (nether, then
 * overworld), which build a fresh ClientLevel with a fresh prediction handler and a fresh
 * LocalPlayer, followed by a fixed set of chunk installs and a fixed player placement.
 *
 * <p>Packets travel as frames (packet id plus body) encoded with the game's own clientbound codec
 * and are decoded again for every delivery, so the planner sees exactly the bytes the extractors
 * parse and every run handles fresh packet objects.
 */
final class ParityFixture implements AutoCloseable {
    /** Work on the client thread. */
    interface ClientStep {
        void run(Minecraft client) throws Exception;
    }

    final ClientGameTestContext context;
    final TestDedicatedServerContext server;
    final RegistryAccess access;
    /** The spawn position of the joined player; the origin of every case. */
    final Vec3 basePos;
    final BlockPos base;
    private final AutoCloseable connection;
    private final ProtocolInfo<ClientGamePacketListener> info;
    private final CommonPlayerSpawnInfo overworld;
    private final CommonPlayerSpawnInfo nether;
    private final Map<Long, byte[]> chunkFrames = new HashMap<>();

    private ParityFixture(ClientGameTestContext context, TestDedicatedServerContext server, AutoCloseable connection) {
        this.context = context;
        this.server = server;
        this.connection = connection;
        this.access = context.computeOnClient(c -> c.level.registryAccess());
        this.info = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));
        CommonPlayerSpawnInfo[] spawn = server.computeOnServer(s -> {
            ServerPlayer player = s.getPlayerList().getPlayers().get(0);
            return new CommonPlayerSpawnInfo[] {player.createCommonSpawnInfo(s.overworld()),
                    player.createCommonSpawnInfo(s.getLevel(Level.NETHER))};
        });
        this.overworld = spawn[0];
        this.nether = spawn[1];
        this.basePos = context.computeOnClient(c -> c.player.position());
        this.base = BlockPos.containing(basePos);
    }

    /** Joins a fresh in-process server over TCP and isolates the client. */
    static ParityFixture open(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        QuicraftClient.get().setMode(Mode.TCP_ONLY);
        TestDedicatedServerContext server = GameTests.server(context);
        AutoCloseable connection = server.connect();
        GameTests.waitForChunks(connection);
        check(!GameTests.connectedOverQuic(context), "parity runs on the vanilla TCP pipeline");
        server.runCommand("tick freeze");
        context.waitTicks(5);
        context.runOnClient(c -> {
            Channel channel = ((ConnectionAccessor) c.getConnection().getConnection()).quicraft$channel();
            channel.pipeline().addLast("quicraft_parity_drop", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    if (msg instanceof ServerboundKeepAlivePacket || msg instanceof ServerboundPongPacket) {
                        ctx.write(msg, promise);
                    } else {
                        ReferenceCountUtil.release(msg);
                        promise.setSuccess();
                    }
                }
            });
        });
        context.waitTicks(2);
        return new ParityFixture(context, server, connection);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        server.close();
        QuicraftClient.get().setMode(Mode.AUTO);
    }

    // ---- frames -------------------------------------------------------------------------------

    byte[] encode(Packet<ClientGamePacketListener> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            info.codec().encode(buffer, packet);
            byte[] out = new byte[buffer.readableBytes()];
            buffer.readBytes(out);
            return out;
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unchecked")
    Packet<ClientGamePacketListener> decode(byte[] frame) {
        ByteBuf buffer = Unpooled.wrappedBuffer(frame);
        try {
            return (Packet<ClientGamePacketListener>) (Packet<?>) info.codec().decode(buffer);
        } finally {
            buffer.release();
        }
    }

    /** The real level_chunk_with_light frame the server would send for a chunk. */
    byte[] chunkFrame(int chunkX, int chunkZ) {
        return chunkFrames.computeIfAbsent(ChunkPos.pack(chunkX, chunkZ), k -> {
            Packet<ClientGamePacketListener> packet = server.computeOnServer(s -> {
                ServerLevel level = s.overworld();
                return new ClientboundLevelChunkWithLightPacket(level.getChunk(chunkX, chunkZ),
                        level.getLightEngine(), null, null);
            });
            return encode(packet);
        });
    }

    // ---- delivery -----------------------------------------------------------------------------

    /** Handles one packet with the real listener; call on the client thread. */
    static void handle(Minecraft client, Packet<ClientGamePacketListener> packet) {
        ClientPacketListener listener = client.getConnection();
        packet.handle(listener);
    }

    void deliver(byte[]... frames) {
        context.runOnClient(c -> {
            for (byte[] frame : frames) {
                handle(c, decode(frame));
            }
        });
    }

    // ---- reset --------------------------------------------------------------------------------

    /** The chunks every run starts with: a 3x3 block around the spawn chunk. */
    List<int[]> standardChunks() {
        List<int[]> out = new ArrayList<>();
        int cx = base.getX() >> 4;
        int cz = base.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                out.add(new int[] {cx + dx, cz + dz});
            }
        }
        return out;
    }

    /** A fresh ClientLevel, LocalPlayer and prediction handler, standard chunks, player at the base. */
    void reset() {
        List<byte[]> frames = new ArrayList<>();
        for (int[] chunk : standardChunks()) {
            frames.add(chunkFrame(chunk[0], chunk[1]));
        }
        context.runOnClient(c -> {
            handle(c, new ClientboundRespawnPacket(nether, (byte) 0));
            handle(c, new ClientboundRespawnPacket(overworld, (byte) 0));
            handle(c, new ClientboundSetChunkCacheCenterPacket(base.getX() >> 4, base.getZ() >> 4));
            for (byte[] frame : frames) {
                handle(c, decode(frame));
            }
            handle(c, new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
            LocalPlayer player = c.player;
            player.absSnapTo(basePos.x, basePos.y, basePos.z, 0.0F, 0.0F);
            player.setDeltaMovement(Vec3.ZERO);
        });
        for (int i = 0; i < 60 && !context.computeOnClient(c -> c.getConnection().hasClientLoaded()); i++) {
            context.waitTick();
        }
        if (!context.computeOnClient(c -> c.getConnection().hasClientLoaded())) {
            // The loading screen never reported ready; the state a loaded client is in is what counts.
            QuicraftFabricTestLog.warn("[QUICraft parity] WARN: the level-load tracker never reported ready;"
                    + " clientLoaded was set by reflection");
            context.runOnClient(c -> setField(c.getConnection(), "clientLoaded", Boolean.TRUE));
        }
        context.waitTicks(2);
    }

    // ---- reflection on game internals ---------------------------------------------------------

    static Object getField(Object target, String name) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> t = type; t != null; t = t.getSuperclass()) {
            try {
                return t.getDeclaredField(name);
            } catch (NoSuchFieldException next) {
                // keep climbing
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    /** ClientLevel's package-private prediction handler. */
    static Object predictionHandler(ClientLevel level) {
        try {
            Method m = ClientLevel.class.getDeclaredMethod("getBlockStatePredictionHandler");
            m.setAccessible(true);
            return m.invoke(level);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** What the game does for a predicted edit: one sequence, the edit made while predicting. */
    static void predict(ClientLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler handler =
                (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler) predictionHandler(level);
        try (var predicting = handler.startPredicting()) {
            level.setBlock(pos, state, 19);
        }
    }

    // ---- snapshot -----------------------------------------------------------------------------

    /**
     * Everything the security review asks parity on, as text: player position, rotation, collision
     * geometry, motion, pose, vehicle, scale and equipment; the prediction handler's pending states;
     * the watched blocks with their block-entity data; the held chunks; every entity.
     */
    String snapshot(List<BlockPos> watchBlocks, List<int[]> watchChunks) {
        return context.computeOnClient(c -> snapshot(c, watchBlocks, watchChunks));
    }

    private static String snapshot(Minecraft c, List<BlockPos> watchBlocks, List<int[]> watchChunks) {
        StringBuilder sb = new StringBuilder();
        LocalPlayer p = c.player;
        ClientLevel level = c.level;
        sb.append("player.pos=").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append('\n');
        sb.append("player.old=").append(p.xo).append(',').append(p.yo).append(',').append(p.zo).append('\n');
        sb.append("player.rot=").append(p.getYRot()).append(',').append(p.getXRot()).append(',')
                .append(p.getYHeadRot()).append('\n');
        sb.append("player.motion=").append(p.getDeltaMovement()).append('\n');
        sb.append("player.pose=").append(p.getPose()).append(" onGround=").append(p.onGround()).append('\n');
        sb.append("player.box=").append(p.getBoundingBox()).append('\n');
        sb.append("player.dimensions=").append(p.getDimensions(p.getPose())).append('\n');
        sb.append("player.noCollision=").append(level.noCollision(p)).append('\n');
        sb.append("player.scale=").append(p.getAttributeValue(Attributes.SCALE)).append('\n');
        Entity vehicle = p.getVehicle();
        sb.append("player.vehicle=").append(vehicle == null ? "none" : vehicle.getId()).append('\n');
        sb.append("player.passengers=");
        p.getPassengers().forEach(e -> sb.append(e.getId()).append(' '));
        sb.append('\n');
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            sb.append("equip.").append(slot).append('=').append(p.getItemBySlot(slot)).append('\n');
        }
        Object handler = predictionHandler(level);
        sb.append("prediction.sequence=").append(getField(handler, "currentSequenceNr")).append(" predicting=")
                .append(getField(handler, "isPredicting")).append(" teleport=")
                .append(getField(handler, "lastTeleportSequence")).append('\n');
        Long2ObjectMap<?> states = (Long2ObjectMap<?>) getField(handler, "serverVerifiedStates");
        List<Long> keys = new ArrayList<>(states.keySet());
        keys.sort(Comparator.naturalOrder());
        for (Long key : keys) {
            Object value = states.get((long) key);
            sb.append("prediction.at=").append(BlockPos.of(key)).append(" seq=").append(getField(value, "sequence"))
                    .append(" state=").append(getField(value, "blockState")).append(" playerPos=")
                    .append(getField(value, "playerPos")).append('\n');
        }
        for (BlockPos pos : watchBlocks) {
            sb.append("block.").append(pos.toShortString()).append('=').append(level.getBlockState(pos));
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity != null) {
                sb.append(" be=").append(entity.saveWithoutMetadata(level.registryAccess()));
            }
            sb.append('\n');
        }
        for (int[] chunk : watchChunks) {
            sb.append("chunk.").append(chunk[0]).append(',').append(chunk[1]).append('=')
                    .append(level.getChunkSource().hasChunk(chunk[0], chunk[1])).append('\n');
        }
        List<Entity> entities = new ArrayList<>();
        level.entitiesForRendering().forEach(entities::add);
        entities.sort(Comparator.comparingInt(Entity::getId));
        for (Entity e : entities) {
            if (e == p) {
                continue;
            }
            sb.append("entity.").append(e.getId()).append('=').append(net.minecraft.world.entity.EntityType
                    .getKey(e.getType())).append(' ').append(e.position()).append(" rot=").append(e.getYRot()).append(',')
                    .append(e.getXRot()).append(" motion=").append(e.getDeltaMovement()).append(" pose=")
                    .append(e.getPose()).append('\n');
        }
        return sb.toString();
    }
}
