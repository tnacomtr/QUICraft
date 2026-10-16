// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;

/**
 * The codec adapter of docs/protocol.md 13.4, 13.6 and 13.16 against the game's own config-protocol
 * packets, handlers and compression, on an in-memory channel. The private handler state the mixins
 * expose in the game is read by reflection here; no game is started.
 */
class PacketLaneCodecTest {
    private static final RoutingTable TABLE = RoutingTableLoader.parse(
            "{\"schema\":1,\"protocol\":775,\"default\":\"control\",\"routes\":[]}");

    @BeforeAll
    static void bootstrapTheRegistries() {
        // The protocol tables reference registries; no world, no assets, no server.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final PacketLaneCodec.Access REFLECTION = new PacketLaneCodec.Access() {
        @Override
        public ProtocolInfo<?> decoderProtocol(PacketDecoder<?> decoder) {
            return (ProtocolInfo<?>) read(decoder, "protocolInfo");
        }

        @Override
        public ProtocolInfo<?> encoderProtocol(PacketEncoder<?> encoder) {
            return (ProtocolInfo<?>) read(encoder, "protocolInfo");
        }

        @Override
        public int decompressThreshold(CompressionDecoder decoder) {
            return (Integer) read(decoder, "threshold");
        }

        @Override
        public boolean decompressValidates(CompressionDecoder decoder) {
            return (Boolean) read(decoder, "validateDecompressed");
        }
    };

    private static Object read(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A server-side pipeline in the configuration stage, as the game's own configureSerialization builds it. */
    private static EmbeddedChannel serverPipeline(int compressionThreshold) {
        EmbeddedChannel channel = new EmbeddedChannel();
        if (compressionThreshold >= 0) {
            channel.pipeline().addLast("decompress", new CompressionDecoder(compressionThreshold, true));
        }
        channel.pipeline().addLast("decoder", new PacketDecoder<>(ConfigurationProtocols.SERVERBOUND));
        if (compressionThreshold >= 0) {
            channel.pipeline().addLast("compress", new CompressionEncoder(compressionThreshold));
        }
        channel.pipeline().addLast("encoder", new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
        return channel;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static byte[] encoded(StreamCodec codec, Object packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, packet);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    void aFramedServerboundPacketDecodesToTheGamesPacketAndItsStateChangeIsFlagged() throws Exception {
        EmbeddedChannel channel = serverPipeline(-1);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), TABLE, Direction.CLIENTBOUND, REFLECTION);
        byte[] packet = encoded(ConfigurationProtocols.SERVERBOUND.codec(),
                ServerboundFinishConfigurationPacket.INSTANCE);
        ByteBuf frame = Unpooled.wrappedBuffer(FrameCodec.encode(packet, -1));

        LaneGame.Decoded decoded = codec.decode(frame, ProtocolState.CONFIGURATION, (add, total) -> { });

        assertInstanceOf(ServerboundFinishConfigurationPacket.class, decoded.packet());
        assertEquals(ProtocolState.CONFIGURATION, decoded.state());
        assertEquals(FrameCodec.packetId(packet), decoded.packetId());
        assertEquals(Envelope.Bundle.NONE, decoded.bundle());
        assertTrue(decoded.stateChange(), "a terminal packet is a state change (13.16)");
        assertNull(channel.pipeline().get("decoder"), "the game's own swap: the decoder is gone");
        assertNotNull(channel.pipeline().get("inbound_config"));
        assertTrue(PacketLaneCodec.inboundSwapPending(channel.pipeline()));
        channel.finishAndReleaseAll();
    }

    @Test
    void compressedAndStoredFramesBothDecodeAndChargeTheReservation() throws Exception {
        EmbeddedChannel channel = serverPipeline(64);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), TABLE, Direction.CLIENTBOUND, REFLECTION);
        byte[] packet = encoded(ConfigurationProtocols.SERVERBOUND.codec(),
                ServerboundFinishConfigurationPacket.INSTANCE);
        long[] charged = new long[1];

        // Below 64 bytes: stored behind a zero.
        LaneGame.Decoded decoded = codec.decode(Unpooled.wrappedBuffer(FrameCodec.encode(packet, 64)),
                ProtocolState.CONFIGURATION, (add, total) -> charged[0] += add);

        assertInstanceOf(ServerboundFinishConfigurationPacket.class, decoded.packet());
        assertEquals(packet.length, charged[0], "the packet's size was charged before decoding");
        channel.finishAndReleaseAll();
    }

    @Test
    void anOutboundPacketRoutesThenEncodesToTheFrameTheGamesEncoderWouldWriteAndSwapsOnTerminal() throws Exception {
        EmbeddedChannel channel = serverPipeline(-1);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), TABLE, Direction.CLIENTBOUND, REFLECTION);
        byte[] packet = encoded(ConfigurationProtocols.CLIENTBOUND.codec(), ClientboundFinishConfigurationPacket.INSTANCE);

        var route = codec.routeOf(ClientboundFinishConfigurationPacket.INSTANCE);
        assertEquals(ProtocolState.CONFIGURATION, route.state());
        assertEquals(FrameCodec.packetId(packet), route.packetId());
        assertEquals(Lane.CONTROL, route.lane(), "the table decides, and the default is CONTROL (13.6)");
        assertNotNull(channel.pipeline().get("encoder"), "routing alone changes nothing");

        ByteBuf out = Unpooled.buffer();
        codec.encode(ClientboundFinishConfigurationPacket.INSTANCE, out);

        assertArrayEquals(FrameCodec.encode(packet, -1), ByteBufUtil.getBytes(out));
        assertNull(channel.pipeline().get("encoder"), "the game's own swap on a terminal packet");
        assertNotNull(channel.pipeline().get("outbound_config"));
        out.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aMissingProtocolHandlerIsAnErrorNotASilentPass() {
        EmbeddedChannel channel = new EmbeddedChannel();
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), TABLE, Direction.CLIENTBOUND, REFLECTION);

        assertThrows(IllegalStateException.class, () -> codec.routeOf(ClientboundFinishConfigurationPacket.INSTANCE));
        assertThrows(IllegalStateException.class, () -> codec.decode(Unpooled.wrappedBuffer(new byte[] {1, 0}),
                ProtocolState.CONFIGURATION, (add, total) -> { }));
        assertFalse(PacketLaneCodec.inboundSwapPending(channel.pipeline()));
        channel.finishAndReleaseAll();
    }

    @Test
    void trailingBytesAfterAPacketAreRefused() {
        EmbeddedChannel channel = serverPipeline(-1);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), TABLE, Direction.CLIENTBOUND, REFLECTION);
        byte[] packet = encoded(ConfigurationProtocols.SERVERBOUND.codec(),
                ServerboundFinishConfigurationPacket.INSTANCE);
        byte[] longer = java.util.Arrays.copyOf(packet, packet.length + 3);

        assertThrows(java.io.IOException.class, () -> codec.decode(Unpooled.wrappedBuffer(FrameCodec.encode(longer, -1)),
                ProtocolState.CONFIGURATION, (add, total) -> { }));
        channel.finishAndReleaseAll();
    }
}
