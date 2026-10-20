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
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.bridge.GameLanes;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The codec adapter of docs/protocol.md 13.4 and 13.16 against the game's own config-protocol
 * packets, handlers and compression, on an in-memory channel: what it hands to the pipeline is what
 * the game's real decoder turns back into the packet. The private handler state the mixins expose
 * in the game is read by reflection here; no game is started.
 */
class PacketLaneCodecTest {
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
        return serverPipeline(compressionThreshold, true);
    }

    private static EmbeddedChannel serverPipeline(int compressionThreshold, boolean validate) {
        EmbeddedChannel channel = new EmbeddedChannel();
        if (compressionThreshold >= 0) {
            channel.pipeline().addLast("decompress", new CompressionDecoder(compressionThreshold, validate));
        }
        channel.pipeline().addLast("decoder", new PacketDecoder<>(ConfigurationProtocols.SERVERBOUND));
        if (compressionThreshold >= 0) {
            channel.pipeline().addFirst("compress", new CompressionEncoder(compressionThreshold));
        }
        return channel;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static byte[] finishConfigurationPacket() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ((StreamCodec) ConfigurationProtocols.SERVERBOUND.codec()).encode(buffer,
                    ServerboundFinishConfigurationPacket.INSTANCE);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    @Test
    void aRecordBecomesTheFrameTheGamesOwnDecoderTurnsBackIntoThePacket() throws Exception {
        EmbeddedChannel channel = serverPipeline(-1);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), REFLECTION);
        byte[] packet = finishConfigurationPacket();
        ByteBuf record = Unpooled.wrappedBuffer(FrameCodec.encode(packet, -1));

        LaneGame.Decoded decoded = codec.decode(record, ProtocolState.CONFIGURATION, (add, total) -> { });

        assertEquals(ProtocolState.CONFIGURATION, decoded.state());
        assertEquals(FrameCodec.packetId(packet), decoded.packetId());
        assertEquals(Envelope.Bundle.NONE, decoded.bundle());
        ByteBuf forPipeline = (ByteBuf) decoded.packet();
        assertArrayEquals(packet, ByteBufUtil.getBytes(forPipeline));
        assertArrayEquals(packet, decoded.uncompressed(), "retain the existing packet array for route extraction");
        assertNotNull(channel.pipeline().get("decoder"), "decoding for the pipeline changes nothing in it");

        // Fired into the pipeline, the game's own decoder produces the packet and, being a terminal
        // one, swaps itself out exactly as it does for TCP.
        channel.writeInbound(forPipeline);
        Object produced = channel.readInbound();
        assertInstanceOf(ServerboundFinishConfigurationPacket.class, produced);
        assertNull(channel.pipeline().get("decoder"));
        assertTrue(PacketLaneCodec.inboundSwapPending(channel.pipeline()));
        channel.finishAndReleaseAll();
    }

    @Test
    void withCompressionTheFrameIsStoredAndTheReservationChargedBeforeAnyInflate() throws Exception {
        // Not validating, so a tiny packet may arrive compressed (the game's own option).
        EmbeddedChannel channel = serverPipeline(64, false);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), REFLECTION);
        byte[] packet = finishConfigurationPacket();
        long[] charged = new long[1];

        // A record compressed by the peer even though it is tiny: the codec still inflates it once
        // and hands the game's decompress handler a stored frame.
        byte[] compressedByPeer = FrameCodec.encode(packet, 0);
        LaneGame.Decoded decoded = codec.decode(Unpooled.wrappedBuffer(compressedByPeer),
                ProtocolState.CONFIGURATION, (add, total) -> charged[0] += add);

        assertEquals(packet.length, charged[0], "the packet's size was charged");
        ByteBuf forPipeline = (ByteBuf) decoded.packet();
        assertEquals(0, forPipeline.getByte(0), "a stored (zero) compression header");
        channel.writeInbound(forPipeline);
        assertInstanceOf(ServerboundFinishConfigurationPacket.class, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void anOverBudgetReservationStopsTheRecordBeforeItReachesThePipeline() {
        EmbeddedChannel channel = serverPipeline(64);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), REFLECTION);
        byte[] big = new byte[3000];
        big[0] = 3;

        assertThrows(IllegalStateException.class, () -> codec.decode(Unpooled.wrappedBuffer(FrameCodec.encode(big, 64)),
                ProtocolState.CONFIGURATION, (add, total) -> {
                    throw new IllegalStateException("over budget (13.12)");
                }));
        channel.finishAndReleaseAll();
    }

    /**
     * The serverbound receive driver hands {@code decodeInflated} the packet id and body, already
     * inflated (13.4). The production codec must answer it: the interface default is deliberately
     * fail-closed, and before this method existed the first serverbound record closed 0x11.
     */
    @Test
    void inflatedContentBecomesTheBarePacketWithoutCompression() throws Exception {
        EmbeddedChannel channel = serverPipeline(-1);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), REFLECTION);
        byte[] packet = finishConfigurationPacket();
        long[] charged = new long[1];
        int[] total = new int[1];

        // Through the interface: proves the production codec overrides the fail-closed default.
        LaneGame.Decoded decoded = ((GameLanes.Codec) codec).decodeInflated(Unpooled.wrappedBuffer(packet),
                ProtocolState.CONFIGURATION, (add, size) -> {
                    charged[0] += add;
                    total[0] = size;
                });

        assertEquals(ProtocolState.CONFIGURATION, decoded.state());
        assertEquals(FrameCodec.packetId(packet), decoded.packetId());
        assertEquals(Envelope.Bundle.NONE, decoded.bundle());
        assertEquals(packet.length, charged[0], "the retained copy was charged before it existed");
        assertEquals(packet.length, total[0]);
        assertArrayEquals(packet, decoded.uncompressed(), "inflated input retains the same routing bytes");
        ByteBuf forPipeline = (ByteBuf) decoded.packet();
        assertArrayEquals(packet, ByteBufUtil.getBytes(forPipeline));

        channel.writeInbound(forPipeline);
        assertInstanceOf(ServerboundFinishConfigurationPacket.class, channel.readInbound());
        assertNull(channel.pipeline().get("decoder"), "the terminal packet swapped the decoder as on TCP");
        channel.finishAndReleaseAll();
    }

    /**
     * With compression on, inflated content still gets the stored (zero) frame the game's
     * decompress handler takes, for a packet below and one above the threshold: the threshold is
     * the peer's decision on the wire and does not survive into this already-inflated path.
     */
    @Test
    void inflatedContentGetsAStoredFrameUnderCompressionForSmallAndLargePackets() throws Exception {
        EmbeddedChannel channel = serverPipeline(64);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), REFLECTION);
        byte[] small = finishConfigurationPacket();
        long[] charged = new long[1];
        LaneGame.Decoded smallDecoded = codec.decodeInflated(Unpooled.wrappedBuffer(small),
                ProtocolState.CONFIGURATION, (add, total) -> charged[0] += add);
        assertEquals(small.length, charged[0]);
        ByteBuf smallFrame = (ByteBuf) smallDecoded.packet();
        assertEquals(0, smallFrame.getByte(0), "a stored (zero) compression header");
        assertArrayEquals(small, ByteBufUtil.getBytes(smallFrame, 1, smallFrame.readableBytes() - 1));

        // Above the threshold: the same stored form, never a bare packet the decompress handler
        // would misread as a Data Length. Before the small frame reaches the pipeline, because its
        // terminal packet swaps the decoder out.
        byte[] large = new byte[3000];
        large[0] = 3;
        LaneGame.Decoded largeDecoded = codec.decodeInflated(Unpooled.wrappedBuffer(large),
                ProtocolState.CONFIGURATION, (add, total) -> { });
        ByteBuf largeFrame = (ByteBuf) largeDecoded.packet();
        assertEquals(0, largeFrame.getByte(0), "stored even above the threshold");
        assertEquals(large.length + 1, largeFrame.readableBytes());
        assertArrayEquals(large, ByteBufUtil.getBytes(largeFrame, 1, largeFrame.readableBytes() - 1));

        channel.writeInbound(smallFrame);
        assertInstanceOf(ServerboundFinishConfigurationPacket.class, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void anOverBudgetInflatedReservationStopsBeforeTheCopy() throws Exception {
        EmbeddedChannel channel = serverPipeline(64);
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), REFLECTION);
        byte[] packet = finishConfigurationPacket();

        // The content fails loudly if anything copies it: the reservation must refuse first.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> codec.decodeInflated(new NoCopyBuf(packet), ProtocolState.CONFIGURATION,
                        (add, total) -> {
                            throw new IllegalStateException("over budget (13.12)");
                        }));
        assertEquals("over budget (13.12)", refused.getMessage());
        assertNotNull(channel.pipeline().get("decoder"), "nothing reached the pipeline");
        channel.finishAndReleaseAll();
    }

    /** A buffer whose every byte-exposing access fails: proves the reservation ran before any copy. */
    private static final class NoCopyBuf extends UnpooledHeapByteBuf {
        NoCopyBuf(byte[] bytes) {
            super(UnpooledByteBufAllocator.DEFAULT, bytes, bytes.length);
        }

        @Override
        public byte[] array() {
            throw new AssertionError("the packet was copied before the reservation");
        }

        @Override
        public java.nio.ByteBuffer nioBuffer() {
            throw new AssertionError("the packet was copied before the reservation");
        }

        @Override
        public java.nio.ByteBuffer nioBuffer(int index, int length) {
            throw new AssertionError("the packet was copied before the reservation");
        }

        @Override
        public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
            throw new AssertionError("the packet was copied before the reservation");
        }
    }

    @Test
    void anOutboundPacketIsFramedAsTheGamesCompressionAndPrependerWould() throws Exception {
        EmbeddedChannel plain = serverPipeline(-1);
        EmbeddedChannel compressing = serverPipeline(64);
        byte[] small = {5, 1, 2, 3};
        byte[] large = new byte[500];
        large[0] = 5;

        assertArrayEquals(FrameCodec.encode(small, -1), new PacketLaneCodec(plain.pipeline(), REFLECTION).outboundFrame(small));
        assertArrayEquals(FrameCodec.encode(small, 64), new PacketLaneCodec(compressing.pipeline(), REFLECTION).outboundFrame(small));
        assertArrayEquals(FrameCodec.encode(large, 64), new PacketLaneCodec(compressing.pipeline(), REFLECTION).outboundFrame(large));
        plain.finishAndReleaseAll();
        compressing.finishAndReleaseAll();
    }

    @Test
    void aMissingInboundProtocolIsAnErrorNotASilentPass() {
        EmbeddedChannel channel = new EmbeddedChannel();
        PacketLaneCodec codec = new PacketLaneCodec(channel.pipeline(), REFLECTION);

        assertThrows(IllegalStateException.class, () -> codec.decode(Unpooled.wrappedBuffer(new byte[] {1, 0}),
                ProtocolState.CONFIGURATION, (add, total) -> { }));
        assertFalse(PacketLaneCodec.inboundSwapPending(channel.pipeline()));
        channel.finishAndReleaseAll();
    }

    @Test
    void outboundGoesThroughTheSinkNotTheLaneGamesHandler() {
        PacketLaneCodec codec = new PacketLaneCodec(new EmbeddedChannel().pipeline(), REFLECTION);

        assertThrows(UnsupportedOperationException.class, () -> codec.routeOf(new Object()));
        assertThrows(UnsupportedOperationException.class, () -> codec.encode(new Object(), Unpooled.buffer()));
    }
}
