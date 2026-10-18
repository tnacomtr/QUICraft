// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;

/**
 * Captures the real wire bytes behind each reviewed {@code mc775.*} extractor layout, with the
 * position or list the game itself reports for the same packet (13.7's review evidence).
 *
 * <p>Two handlers are put around the game's own {@link PacketDecoder}: the first copies each
 * inbound frame as it arrives from the decompressor, the second sees the decoded packet that
 * frame produced. The bytes are untouched (a copy, the reader index is not moved) and the game
 * decodes exactly what it would have without the capture. Nothing is recorded unless
 * {@link #phase} says so, and nothing escapes into the pipeline: a failure to understand a packet
 * is collected and reported by the test, never thrown at the client.
 *
 * <p>The result is a checked-in vector file for core's golden test. Every claim in
 * {@link #CLAIMS} must produce a sample before the test passes, so a mapping that cannot be
 * captured fails loudly instead of shipping as a comment. Every position layout must also produce
 * a negative-coordinate sample and a nonnegative-coordinate sample, and {@link #selected} keeps
 * both in the file.
 */
final class ExtractorVectorCapture {
    /** How the game's own packet reports its position (or that it has none). */
    private enum Shape { COORDINATES, BLOCK_POSITION, SECTION_POSITION, CHUNK_LIST, JOIN }

    private static final class Claim {
        final String layout;
        final String packetName;
        final String packetClass;
        final Shape shape;

        Claim(String layout, String packetName, String packetClass, Shape shape) {
            this.layout = layout;
            this.packetName = packetName;
            this.packetClass = packetClass;
            this.shape = shape;
        }
    }

    /**
     * One row per real 775 packet a reviewed layout serves (13.7). The layout reads only the
     * leading fields, so every packet that shares them can share the layout; each one is captured
     * on its own so the mapping is evidence, not assumption. There is no row for varint_xz: no
     * light_update was ever emitted on 26.1.2, so that layout has no production identifier.
     */
    private static final Claim[] CLAIMS = {
        new Claim("mc775.chunk_xz.v1", "minecraft:level_chunk_with_light",
                "ClientboundLevelChunkWithLightPacket", Shape.COORDINATES),
        new Claim("mc775.block_position.v1", "minecraft:block_update",
                "ClientboundBlockUpdatePacket", Shape.BLOCK_POSITION),
        new Claim("mc775.block_position.v1", "minecraft:block_entity_data",
                "ClientboundBlockEntityDataPacket", Shape.BLOCK_POSITION),
        new Claim("mc775.section_position.v1", "minecraft:section_blocks_update",
                "ClientboundSectionBlocksUpdatePacket", Shape.SECTION_POSITION),
        new Claim("mc775.chunk_list.v1", "minecraft:chunks_biomes",
                "ClientboundChunksBiomesPacket", Shape.CHUNK_LIST),
        new Claim("mc775.join.v1", "minecraft:chunk_batch_finished",
                "ClientboundChunkBatchFinishedPacket", Shape.JOIN),
        new Claim("mc775.join.v1", "minecraft:block_changed_ack",
                "ClientboundBlockChangedAckPacket", Shape.JOIN),
    };

    /** At most this many samples per claim are checked in; all are loggable, the file is not huge. */
    private static final int MAX_SAMPLES_PER_CLAIM = 3;

    private static final String FRAME_HANDLER = "quicraft-capture-frame";
    private static final String PACKET_HANDLER = "quicraft-capture-packet";

    private static final class Sample {
        final String layout;
        final String packetName;
        final int packetId;
        final byte[] frame;
        final String expectation;

        Sample(String layout, String packetName, int packetId, byte[] frame, String expectation) {
            this.layout = layout;
            this.packetName = packetName;
            this.packetId = packetId;
            this.frame = frame;
            this.expectation = expectation;
        }

        /** True when the sample reports at least one negative coordinate (any entry counts). */
        boolean negative() {
            for (long coordinate : coordinates()) {
                if (coordinate < 0) return true;
            }
            return false;
        }

        /** True when the sample reports coordinates and all of them are nonnegative. */
        boolean nonnegative() {
            List<Long> coordinates = coordinates();
            if (coordinates.isEmpty()) return false;
            for (long coordinate : coordinates) {
                if (coordinate < 0) return false;
            }
            return true;
        }

        /** The sample's chunk coordinates in file order; empty for a join. */
        private List<Long> coordinates() {
            List<Long> coordinates = new ArrayList<Long>();
            for (String pair : expectation.split(";")) {
                if (pair.isEmpty() || pair.equals("JOIN")) continue;
                for (String value : pair.split(",")) {
                    coordinates.add(Long.parseLong(value));
                }
            }
            return coordinates;
        }
    }

    private static final Map<Integer, String> LIVE_NAMES = new HashMap<Integer, String>();
    private static final Map<String, Claim> BY_CLASS = new LinkedHashMap<String, Claim>();
    /** Written from the connection's event loop thread, read from the test thread. */
    private static final List<Sample> SAMPLES = new CopyOnWriteArrayList<Sample>();
    private static final List<String> ERRORS = new CopyOnWriteArrayList<String>();
    /** Clientbound classes the client decoded while capturing, for diagnosing a missing sample. */
    private static final Set<String> SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<byte[]> PENDING = new ThreadLocal<byte[]>();

    private static volatile String phase = "";
    private static volatile boolean installed;
    private static volatile boolean namesRead;

    private ExtractorVectorCapture() {}

    static {
        for (Claim claim : CLAIMS) {
            BY_CLASS.put(claim.packetClass, claim);
        }
    }

    /** Records while {@code name} is set (a phase label for the log); an empty name stops recording. */
    static void phase(String name) {
        phase = name;
    }

    static void install(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ClientPacketListener listener = client.getConnection();
            if (listener == null) {
                throw new AssertionError("no client connection to capture from");
            }
            Channel channel = ((ConnectionAccessor) listener.getConnection()).quicraft$channel();
            if (channel == null) {
                throw new AssertionError("the client connection has no channel yet");
            }
            ChannelPipeline pipeline = channel.pipeline();
            ChannelHandlerContext decoder = pipeline.context(PacketDecoder.class);
            if (decoder == null) {
                throw new AssertionError("no PacketDecoder in the client pipeline: " + pipeline.names());
            }
            pipeline.addBefore(decoder.name(), FRAME_HANDLER, new FrameHandler());
            pipeline.addAfter(decoder.name(), PACKET_HANDLER, new PacketHandler());
            installed = true;
            QuicraftFabricTestLog.info("[QUICraft] extractor capture installed around the packet decoder");
        });
    }

    static boolean installed() {
        return installed;
    }

    /**
     * Writes the samples collected so far, so a crash later still leaves evidence. The last call
     * of a run also prints them between BEGIN/END markers, like the play-id inventory emission.
     */
    static void emit(Path path, int protocol, boolean printBlock) {
        try {
            String text = render(protocol);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, text.getBytes(StandardCharsets.UTF_8));
            if (printBlock) {
                StringBuilder block = new StringBuilder("QUICraft extractor vectors, protocol ")
                        .append(protocol).append(" BEGIN\n").append(text);
                block.append("QUICraft extractor vectors, protocol ").append(protocol).append(" END");
                System.out.println(block);
            }
            System.out.println("[QUICraft] extractor vectors written to " + path.toAbsolutePath());
        } catch (Exception e) {
            ERRORS.add("writing " + path + ": " + e);
        }
    }

    /** The whole vector file: header, then one line per selected sample. */
    private static String render(int protocol) {
        StringBuilder out = new StringBuilder();
        out.append("# QUICraft captured extractor vectors, protocol ").append(protocol)
                .append(", live client, uncompressed\n");
        out.append("# frames as they leave decompression and enter the game's packet decoder.\n");
        out.append("# <layout id>|<frame hex>|<game-side expectation>|<live packet name>|<live packet id>\n");
        out.append("# regenerate: ./gradlew :fabric-26x:runClientGameTest")
                .append(" -Pquicraft.emitExtractorVectors=<file>\n");
        for (Sample sample : selected()) {
            out.append(sample.layout).append('|').append(hex(sample.frame)).append('|')
                    .append(sample.expectation).append('|').append(sample.packetName).append('|')
                    .append(sample.packetId).append('\n');
        }
        return out.toString();
    }

    /**
     * Fails unless every claim produced a sample and every position layout saw both coordinate
     * signs: a negative sample and a sample whose coordinates are all nonnegative.
     */
    static void assertCoverage(Path path) {
        List<String> problems = new ArrayList<String>(ERRORS);
        Map<String, Integer> perRow = new LinkedHashMap<String, Integer>();
        Map<String, Boolean> negative = new LinkedHashMap<String, Boolean>();
        Map<String, Boolean> nonnegative = new LinkedHashMap<String, Boolean>();
        for (Sample sample : SAMPLES) {
            String row = sample.layout + " " + sample.packetName;
            perRow.merge(row, 1, Integer::sum);
            if (sample.negative()) {
                negative.put(sample.layout, Boolean.TRUE);
            }
            if (sample.nonnegative()) {
                nonnegative.put(sample.layout, Boolean.TRUE);
            }
        }
        for (Claim claim : CLAIMS) {
            String row = claim.layout + " " + claim.packetName;
            if (!perRow.containsKey(row)) {
                problems.add("no sample for " + row);
            }
            if (claim.shape != Shape.JOIN) {
                if (!Boolean.TRUE.equals(negative.get(claim.layout))) {
                    problems.add("no negative-coordinate sample for " + claim.layout);
                }
                if (!Boolean.TRUE.equals(nonnegative.get(claim.layout))) {
                    problems.add("no nonnegative-coordinate sample for " + claim.layout);
                }
            }
        }
        QuicraftFabricTestLog.info("[QUICraft] extractor capture summary (" + SAMPLES.size() + " samples, "
                + selected().size() + " checked in): " + perRow);
        if (!problems.isEmpty()) {
            throw new AssertionError("extractor capture incomplete: " + problems);
        }
        QuicraftFabricTestLog.info("[QUICraft] extractor capture complete: " + path.toAbsolutePath());
    }

    /**
     * The subset that is checked in: the first sample of each claim, the first one that reports a
     * negative coordinate and the first one that reports only nonnegative coordinates, so every
     * position row keeps both signs. Three per claim keeps the file reviewable.
     */
    private static List<Sample> selected() {
        Map<String, List<Sample>> byRow = new LinkedHashMap<String, List<Sample>>();
        for (Sample sample : SAMPLES) {
            byRow.computeIfAbsent(sample.layout + " " + sample.packetName, key -> new ArrayList<Sample>())
                    .add(sample);
        }
        List<Sample> selected = new ArrayList<Sample>();
        for (List<Sample> row : byRow.values()) {
            List<Sample> keep = new ArrayList<Sample>();
            keep.add(row.get(0));
            Sample negative = null;
            Sample nonnegative = null;
            for (Sample sample : row) {
                if (negative == null && sample.negative()) negative = sample;
                if (nonnegative == null && sample.nonnegative()) nonnegative = sample;
            }
            if (negative != null && !keep.contains(negative)) keep.add(negative);
            if (nonnegative != null && !keep.contains(nonnegative)) keep.add(nonnegative);
            while (keep.size() > MAX_SAMPLES_PER_CLAIM) keep.remove(keep.size() - 1);
            selected.addAll(keep);
        }
        return selected;
    }

    /** Called on the connection's event loop for every inbound frame before the packet decoder. */
    private static final class FrameHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                ByteBuf frame = (ByteBuf) msg;
                byte[] bytes = new byte[frame.readableBytes()];
                frame.getBytes(frame.readerIndex(), bytes);
                PENDING.set(bytes);
            }
            ctx.fireChannelRead(msg);
        }
    }

    /** Called on the same event loop with the packet the game decoded from those bytes. */
    private static final class PacketHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            byte[] frame = PENDING.get();
            PENDING.remove();
            if (frame != null && !phase.isEmpty()) {
                try {
                    record(frame, msg);
                } catch (Throwable t) {
                    ERRORS.add("capturing " + (msg == null ? "null" : msg.getClass().getSimpleName()) + ": " + t);
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    private static void record(byte[] frame, Object packet) {
        if (packet == null) {
            return;
        }
        String seen = packet.getClass().getSimpleName();
        if (seen.startsWith("Clientbound") && SEEN.add(seen)) {
            QuicraftFabricTestLog.info("[QUICraft] clientbound packet seen: " + seen);
        }
        Claim claim = BY_CLASS.get(seen);
        if (claim == null) {
            return; // not one of the reviewed packets; login, configuration and everything else pass by
        }
        int packetId = varInt(frame, 0);
        String liveName = liveNames().get(packetId);
        if (!claim.packetName.equals(liveName)) {
            ERRORS.add("id " + packetId + " decodes to " + packet.getClass().getSimpleName()
                    + " named " + liveName + ", the claim expects " + claim.packetName);
            return;
        }
        String expectation = expectation(claim, packet);
        String decoded;
        try {
            decoded = result(ExtractorRegistry.productionDecoder(claim.layout).decode(frame));
        } catch (IllegalArgumentException e) {
            decoded = "rejected (" + e.getMessage() + ")";
        }
        SAMPLES.add(new Sample(claim.layout, claim.packetName, packetId, frame, expectation));
        if (!decoded.equals(expectation)) {
            // The bytes are the evidence either way, but a layout that does not read them the way
            // the game reports them is not a mapping: fail the run and let the mapping be dropped.
            ERRORS.add(claim.packetName + ": " + claim.layout + " decoded " + decoded
                    + " but the game reports " + expectation);
        }
        QuicraftFabricTestLog.info(String.format(Locale.ROOT,
                "[QUICraft] captured %s/%s id=%d bytes=%d expectation=%s phase=%s",
                claim.layout, claim.packetName, packetId, frame.length, expectation, phase));
    }

    /** The decoded result in the capture file's notation; the core golden test reads it the same way. */
    private static String result(ExtractorRegistry.Result result) {
        if (result.kind == ExtractorRegistry.Kind.JOIN) {
            return "JOIN";
        }
        StringBuilder out = new StringBuilder();
        for (Extractor.Position position : result.positions) {
            if (out.length() != 0) out.append(';');
            out.append(position.chunkX).append(',').append(position.chunkZ);
        }
        return out.toString();
    }

    /** What the game itself says the packet's position (or list, or join) is. */
    private static String expectation(Claim claim, Object packet) {
        switch (claim.shape) {
            case JOIN:
                return "JOIN";
            case COORDINATES:
                if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                    return coords(chunk.getX(), chunk.getZ());
                }
                throw unexpected(packet);
            case BLOCK_POSITION: {
                BlockPos pos;
                if (packet instanceof ClientboundBlockUpdatePacket update) {
                    pos = update.getPos();
                } else if (packet instanceof ClientboundBlockEntityDataPacket data) {
                    pos = data.getPos();
                } else {
                    throw unexpected(packet);
                }
                return coords(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
            }
            case SECTION_POSITION: {
                if (!(packet instanceof ClientboundSectionBlocksUpdatePacket section)) {
                    throw unexpected(packet);
                }
                // The packet exposes its updates, not its section, so the section is the one the
                // first update reports: the game's own block position, floor-divided.
                BlockPos[] first = new BlockPos[1];
                section.runUpdates((pos, state) -> {
                    if (first[0] == null) {
                        first[0] = (BlockPos) pos;
                    }
                });
                if (first[0] == null) {
                    throw new IllegalStateException("section update with no updates");
                }
                return coords(Math.floorDiv(first[0].getX(), 16), Math.floorDiv(first[0].getZ(), 16));
            }
            default: {
                if (!(packet instanceof ClientboundChunksBiomesPacket biomes)) {
                    throw unexpected(packet);
                }
                List<ClientboundChunksBiomesPacket.ChunkBiomeData> data = biomes.chunkBiomeData();
                StringBuilder out = new StringBuilder();
                for (ClientboundChunksBiomesPacket.ChunkBiomeData entry : data) {
                    ChunkPos pos = entry.pos();
                    if (out.length() != 0) out.append(';');
                    out.append(coords(chunkCoordinate(pos, "x"), chunkCoordinate(pos, "z")));
                }
                return out.toString();
            }
        }
    }

    private static IllegalStateException unexpected(Object packet) {
        return new IllegalStateException("no expectation shape for " + packet.getClass().getName());
    }

    /**
     * One ChunkPos coordinate as the game reports it. The source set is shared with 1.21.11, whose
     * ChunkPos exposes {@code x}/{@code z} fields where 26.x has {@code x()}/{@code z()} accessors;
     * the capture itself only ever runs on the 26.x protocol.
     */
    private static int chunkCoordinate(ChunkPos pos, String name) {
        try {
            return ((Number) pos.getClass().getMethod(name).invoke(pos)).intValue();
        } catch (NoSuchMethodException e) {
            try {
                return ((Number) pos.getClass().getField(name).get(pos)).intValue();
            } catch (ReflectiveOperationException f) {
                throw new IllegalStateException("ChunkPos has no " + name, f);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The live clientbound PLAY inventory: packet id to {@code minecraft:name}, from the game. */
    private static Map<Integer, String> liveNames() {
        if (namesRead) {
            return LIVE_NAMES;
        }
        try {
            Class<?> owner = Class.forName("net.minecraft.network.protocol.game.GameProtocols");
            Object template = owner.getField("CLIENTBOUND_TEMPLATE").get(null);
            Class<?> provider = Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider");
            Object details = provider.getMethod("details").invoke(template);
            Class<?> detailsClass = Class.forName("net.minecraft.network.ProtocolInfo$Details");
            Class<?> visitorClass = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor");
            Object visitor = Proxy.newProxyInstance(visitorClass.getClassLoader(), new Class<?>[] {visitorClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("accept")) {
                            Object type = args[0];
                            Object identifier = type.getClass().getMethod("id").invoke(type);
                            LIVE_NAMES.put((Integer) args[1], identifier.toString());
                        }
                        return null;
                    });
            detailsClass.getMethod("listPackets", visitorClass).invoke(details, visitor);
            namesRead = true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the live clientbound play inventory", e);
        }
        return LIVE_NAMES;
    }

    private static int varInt(byte[] frame, int offset) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = frame[offset + i] & 255;
            value |= (b & 127) << (7 * i);
            if ((b & 128) == 0) {
                return value;
            }
        }
        throw new IllegalStateException("frame does not start with a packet id");
    }

    private static String coords(int x, int z) {
        return x + "," + z;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }
}
