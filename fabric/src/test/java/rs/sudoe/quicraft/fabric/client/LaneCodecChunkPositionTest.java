// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;
import rs.sudoe.quicraft.core.multistream.ClientReadPump;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.ReceiveSlots;
import rs.sudoe.quicraft.core.multistream.RecordCodec;
import rs.sudoe.quicraft.core.multistream.SessionReceiveDriver;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.RouteV2;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.fabric.net.FrameCodec;

/** The client must retain position input before Minecraft's destructive decode consumes it. */
class LaneCodecChunkPositionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void clientDecodeRetainsChargedUncompressedChunkPositionBeforeGameConsumesBuffer() throws Exception {
        for (boolean compressed : new boolean[] {false, true}) {
            EmbeddedChannel channel = pipeline(compressed);
            LaneCodec codec = new LaneCodec(channel, null, null, access(compressed));
            byte[] packet = ByteBuffer.allocate(9).put((byte) 45).putInt(-7).putInt(8).array();
            ByteBuf frame = Unpooled.wrappedBuffer(FrameCodec.encode(packet, compressed ? 0 : -1));
            long[] charged = {0};
            try {
                var decoded = codec.decode(frame, ProtocolState.PLAY, (add, total) -> charged[0] += add);
                assertEquals(45, decoded.packetId());
                assertArrayEquals(packet, decoded.uncompressed(), "missing bytes fail closed on the first CHUNK record");
                var position = ExtractorRegistry.productionDecoder("mc775.chunk_xz.v1").extract(decoded.uncompressed());
                assertEquals(-7, position.chunkX);
                assertEquals(8, position.chunkZ);
                assertEquals(packet.length, decoded.uncompressedBytes());
                assertEquals(2L * packet.length, charged[0], "both decode storage and retained routing copy are charged");
            } finally {
                frame.release();
                channel.finishAndReleaseAll();
            }
        }
    }

    /** Block updates (8, 6) and section updates (84) keep their position through the real decode, compressed or not. */
    @Test
    void blockAndSectionUpdatesKeepTheirPositionThroughCompressedAndPlainDecode() throws Exception {
        String[] hex = {"08fffffe8000006fcae829", "06000000000000000000", "54000003fffff00000"};
        String[] extractors = {"mc775.block_position.v1", "mc775.block_position.v1", "mc775.section_position.v1"};
        int[][] chunks = {{-1, 0}, {0, 0}, {0, -1}};
        for (boolean compressed : new boolean[] {false, true}) {
            for (int i = 0; i < hex.length; i++) {
                EmbeddedChannel channel = pipeline(compressed);
                LaneCodec codec = new LaneCodec(channel, null, null, access(compressed));
                byte[] packet = io.netty.buffer.ByteBufUtil.decodeHexDump(hex[i]);
                ByteBuf frame = Unpooled.wrappedBuffer(FrameCodec.encode(packet, compressed ? 0 : -1));
                try {
                    var decoded = codec.decode(frame, ProtocolState.PLAY, (add, total) -> { });
                    assertArrayEquals(packet, decoded.uncompressed(), "routing copy is the inflated packet");
                    var position = ExtractorRegistry.productionDecoder(extractors[i]).extract(decoded.uncompressed());
                    assertEquals(chunks[i][0], position.chunkX, hex[i] + " compressed=" + compressed);
                    assertEquals(chunks[i][1], position.chunkZ, hex[i] + " compressed=" + compressed);
                } finally {
                    frame.release();
                    channel.finishAndReleaseAll();
                }
            }
        }
    }

    /** forget_level_chunk (37) keeps its packed-long chunk position through the real decode, compressed or not. */
    @Test
    void forgetLevelChunkKeepsItsPositionThroughCompressedAndPlainDecode() throws Exception {
        int[][] chunks = {{-1, 0}, {0, -1}, {-7, 8}};
        for (boolean compressed : new boolean[] {false, true}) {
            for (int[] chunk : chunks) {
                EmbeddedChannel channel = pipeline(compressed);
                LaneCodec codec = new LaneCodec(channel, null, null, access(compressed));
                long packed = chunk[0] & 0xffffffffL | (chunk[1] & 0xffffffffL) << 32;
                byte[] packet = ByteBuffer.allocate(9).put((byte) 37).putLong(packed).array();
                ByteBuf frame = Unpooled.wrappedBuffer(FrameCodec.encode(packet, compressed ? 0 : -1));
                try {
                    var decoded = codec.decode(frame, ProtocolState.PLAY, (add, total) -> { });
                    assertEquals(37, decoded.packetId());
                    assertArrayEquals(packet, decoded.uncompressed(), "routing copy is the inflated packet");
                    var position = ExtractorRegistry.productionDecoder("mc775.chunk_pos_long.v1")
                            .extract(decoded.uncompressed());
                    assertEquals(chunk[0], position.chunkX, "compressed=" + compressed);
                    assertEquals(chunk[1], position.chunkZ, "compressed=" + compressed);
                } finally {
                    frame.release();
                    channel.finishAndReleaseAll();
                }
            }
        }
    }

    /** A large packet keeps only the bounded routing prefix, so the 8 MiB workspace still fits it. */
    @Test
    void largePacketRetainsOnlyTheBoundedRoutingPrefix() throws Exception {
        EmbeddedChannel channel = pipeline(true);
        LaneCodec codec = new LaneCodec(channel, null, null, access(true));
        byte[] packet = new byte[5 << 20];
        ByteBuffer.wrap(packet).put((byte) 45).putInt(-7).putInt(8);
        ByteBuf frame = Unpooled.wrappedBuffer(FrameCodec.encode(packet, 0));
        long[] charged = {0};
        try {
            var decoded = codec.decode(frame, ProtocolState.PLAY, (add, total) -> charged[0] += add);
            assertEquals(LaneCodec.ROUTING_PREFIX_BYTES, decoded.uncompressed().length);
            assertEquals(packet.length, decoded.uncompressedBytes());
            var position = ExtractorRegistry.productionDecoder("mc775.chunk_xz.v1").extract(decoded.uncompressed());
            assertEquals(-7, position.chunkX);
            assertEquals(8, position.chunkZ);
            assertEquals((long) packet.length + LaneCodec.ROUTING_PREFIX_BYTES, charged[0],
                    "the declared size plus the bounded prefix, never a second whole copy");
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
    }

    /**
     * The backlog bound counts decoded size, not wire size: a highly compressible packet is a few hundred
     * bytes on the wire but 5 MiB once decoded, so the count is the uncompressed size plus the fixed overhead.
     */
    @Test
    void decodedByteCountUsesUncompressedSizeNotWireSizeForCompressiblePacket() throws Exception {
        EmbeddedChannel channel = pipeline(true);
        long[] counted = {-1};
        LaneCodec codec = new LaneCodec(channel, null, size -> counted[0] = size, access(true));
        byte[] packet = new byte[5 << 20];
        ByteBuffer.wrap(packet).put((byte) 45).putInt(-7).putInt(8);
        ByteBuf frame = Unpooled.wrappedBuffer(FrameCodec.encode(packet, 0));
        long wireBytes = frame.readableBytes();
        try {
            assertTrue(wireBytes < 8192,"the frame must be highly compressible, was " + wireBytes + " bytes");
            codec.decode(frame, ProtocolState.PLAY, (add, total) -> { });
            assertEquals(packet.length + LaneCodec.PER_PACKET_OVERHEAD_BYTES, counted[0]);
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void routingCopyReservationRefusalPrecedesDestructiveGameDecode() throws Exception {
        EmbeddedChannel channel = pipeline(false);
        long[] counted = {0};
        LaneCodec codec = new LaneCodec(channel, null, size -> counted[0] += size, access(false));
        byte[] packet = ByteBuffer.allocate(9).put((byte) 45).putInt(-7).putInt(8).array();
        ByteBuf frame = Unpooled.wrappedBuffer(FrameCodec.encode(packet, -1));
        int[] reservations = {0};
        try {
            assertThrows(IllegalStateException.class, () -> codec.decode(frame, ProtocolState.PLAY, (add, total) -> {
                if (++reservations[0] == 2) throw new IllegalStateException("routing copy budget refused");
            }));
            assertEquals(1, frame.readerIndex(), "only the framing prefix was consumed; game decode did not run");
            assertEquals(0, counted[0], "a refused attempt must not inflate the next delivery's byte count");
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void realClientReceiveDriverAcceptsChunkButClosesOrderingWithoutAdapterPositionInput() throws Exception {
        for (boolean missingMetadata : new boolean[] {false, true}) {
            EmbeddedChannel channel = pipeline(false);
            LaneCodec codec = new LaneCodec(channel, null, null, access(false));
            byte[] packet = ByteBuffer.allocate(9).put((byte) 45).putInt(-7).putInt(8).array();
            byte[] framed = FrameCodec.encode(packet, -1);
            byte[] wire = RecordCodec.encodeRecord(new rs.sudoe.quicraft.core.multistream.Record(2, 1, 0,
                    List.of(), RecordCodec.KIND_GAME, framed));
            ByteBuffer source = ByteBuffer.wrap(wire);
            ReceiveSlots slots = new ReceiveSlots(1);
            ClientReadPump pump = new ClientReadPump(1, slots, false, (stream, into, offset, max) -> {
                int count = stream == 2 ? Math.min(max, source.remaining()) : 0;
                source.get(into, offset, count);
                return count;
            });
            int[] delivered = {0};
            java.util.List<Integer> closed = new java.util.ArrayList<>();
            LaneGame game = new LaneGame() {
                @Override public Decoded decode(Lane lane, long seq, byte[] frame, boolean bundle, Reservation budget)
                        throws Exception {
                    ByteBuf input = Unpooled.wrappedBuffer(frame);
                    try {
                        Decoded decoded = codec.decode(input, ProtocolState.PLAY, budget);
                        // The pre-fix production adapter used the no-routing-input constructor.
                        return missingMetadata ? new Decoded(decoded.packet(), decoded.state(), decoded.packetId(),
                                decoded.bundle(), false) : decoded;
                    } finally {
                        input.release();
                    }
                }
                @Override public void deliver(Lane lane, long seq, List<Object> packets, boolean bundle) { delivered[0]++; }
                @Override public void onWritabilityChanged(boolean writable) { }
                @Override public void onClosed(int code, boolean peer, Throwable cause) { }
            };
            var extractor = ExtractorRegistry.productionDecoder("mc775.chunk_xz.v1");
            SessionReceiveDriver driver = new SessionReceiveDriver(1, pump, slots,
                    (stream, record, route) -> route.position() != null && route.position().chunkX == -7
                            && route.position().chunkZ == 8,
                    key -> new RouteV2(RouteV2.StreamLane.CHUNK, Dependency.LANE, extractor.identifier,
                            "chunk_xz", false, null, null, null),
                    775, java.util.Map.of(extractor.identifier, extractor), game, System::nanoTime,
                    (code, reason) -> closed.add(code));
            try {
                for (int i = 0; i < 30 && closed.isEmpty() && delivered[0] == 0; i++) driver.receive();
                if (missingMetadata) {
                    assertEquals(List.of(0x11), closed, "missing input closes before the first chunk handler");
                    assertEquals(0, delivered[0]);
                } else {
                    assertTrue(closed.isEmpty(), "production client metadata satisfies reviewed CHUNK validation");
                    assertEquals(1, delivered[0]);
                }
            } finally {
                driver.close();
                pump.cancel();
                channel.finishAndReleaseAll();
            }
        }
    }

    private static EmbeddedChannel pipeline(boolean compressed) {
        EmbeddedChannel channel = new EmbeddedChannel();
        if (compressed) channel.pipeline().addLast("decompress", new CompressionDecoder(0, false));
        channel.pipeline().addLast("decoder", new PacketDecoder<>(ConfigurationProtocols.SERVERBOUND));
        return channel;
    }

    /** External game handlers only; the production adapter does framing, accounting and metadata. */
    private static LaneCodec.Access access(boolean compressed) {
        return new LaneCodec.Access() {
            @Override public ProtocolState state(PacketDecoder<?> decoder) { return ProtocolState.PLAY; }
            @Override public void decode(ChannelHandlerContext context, ByteBuf bytes, List<Object> out) {
                bytes.skipBytes(bytes.readableBytes());
                out.add(new ClientboundKeepAlivePacket(1));
            }
            @Override public void decompress(ChannelHandlerContext context, ByteBuf bytes, List<Object> out)
                    throws Exception {
                byte[] body = new byte[bytes.readableBytes()];
                bytes.readBytes(body);
                ByteBuf framedBuffer = Unpooled.buffer();
                try {
                    net.minecraft.network.VarInt.write(framedBuffer, body.length);
                    framedBuffer.writeBytes(body);
                    byte[] framed = io.netty.buffer.ByteBufUtil.getBytes(framedBuffer);
                    out.add(Unpooled.wrappedBuffer(FrameCodec.decode(framed, compressed ? 0 : -1, false, (a, t) -> { })));
                } finally {
                    framedBuffer.release();
                }
            }
        };
    }
}
