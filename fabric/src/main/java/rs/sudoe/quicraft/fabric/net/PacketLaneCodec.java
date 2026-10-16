// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import java.io.IOException;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import rs.sudoe.quicraft.bridge.GameLanes;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.fabric.mixin.CompressionDecoderAccessor;
import rs.sudoe.quicraft.fabric.mixin.PacketDecoderAccessor;

/**
 * The game's own packet pipeline behind {@link GameLanes} (docs/protocol.md 13.4, 13.6, 13.16).
 *
 * <p><b>Inbound.</b> A GAME record's body is the frame the game's length prepender and compression
 * encoder would write. {@link #decode} unwraps it (charging the receive budget before anything is
 * inflated, 13.12), reads the packet id and the state from the live {@code decoder}, and hands back
 * the packet as a frame in the form the game's own {@code decompress}/{@code decoder} handlers
 * accept: a stored (zero) compression header and the packet bytes. {@link GameLanes} fires that
 * frame into the pipeline right behind the frame splitter, so everything the game and the other
 * mods do from there on (the decoder itself and its terminal-packet swap, bundlers, Fabric API's
 * packet merger, the flow control that holds the stream while a protocol change is applied) runs
 * exactly as it does for TCP, and the game's state machine stays authoritative (13.16).</p>
 *
 * <p><b>Outbound</b> does not use {@link GameLanes}' own outbound handler, which would bypass the
 * handlers between the game's connection and its encoder: see {@code V3Join}'s encoder sink,
 * which frames what the encoder wrote with {@link #outboundFrame}.</p>
 *
 * <p>Threading: the connection's event loop, which is where {@link GameLanes} calls it.</p>
 */
public final class PacketLaneCodec implements GameLanes.Codec {
    private static final String DECODER = "decoder";
    private static final String DECOMPRESS = "decompress";
    private static final String COMPRESS = "compress";
    private static final String INBOUND_CONFIG = "inbound_config";

    /**
     * How the codec reads the game's private handler state: mixin accessors in the game, reflection
     * stand-ins in tests that start no game.
     */
    public interface Access {
        ProtocolInfo<?> decoderProtocol(PacketDecoder<?> decoder);

        int decompressThreshold(CompressionDecoder decoder);

        boolean decompressValidates(CompressionDecoder decoder);
    }

    /** The mixin accessors, for the running game. */
    public static final Access MIXINS = new Access() {
        @Override
        public ProtocolInfo<?> decoderProtocol(PacketDecoder<?> decoder) {
            return ((PacketDecoderAccessor) decoder).quicraft$protocolInfo();
        }

        @Override
        public int decompressThreshold(CompressionDecoder decoder) {
            return ((CompressionDecoderAccessor) decoder).quicraft$threshold();
        }

        @Override
        public boolean decompressValidates(CompressionDecoder decoder) {
            return ((CompressionDecoderAccessor) decoder).quicraft$validateDecompressed();
        }
    };

    private final ChannelPipeline pipeline;
    private final Access access;
    private java.util.function.LongConsumer decodedSizes = size -> { };

    /** Reports each decoded packet's uncompressed size, in decode order (what its delivery will weigh). */
    public void onDecoded(java.util.function.LongConsumer listener) {
        this.decodedSizes = listener;
    }

    public PacketLaneCodec(ChannelPipeline pipeline) {
        this(pipeline, MIXINS);
    }

    /** As above, with the game's private state read through {@code access}. */
    public PacketLaneCodec(ChannelPipeline pipeline, Access access) {
        if (pipeline == null || access == null) {
            throw new IllegalArgumentException("pipeline/access");
        }
        this.pipeline = pipeline;
        this.access = access;
    }

    @Override
    public LaneGame.Decoded decode(ByteBuf frame, ProtocolState state, LaneGame.Reservation reservation)
            throws Exception {
        PacketDecoder<?> decoder = (PacketDecoder<?>) pipeline.get(DECODER);
        if (decoder == null) {
            throw new IllegalStateException("no inbound protocol is configured (13.16)");
        }
        ProtocolInfo<?> info = access.decoderProtocol(decoder);
        int threshold = -1;
        boolean validate = false;
        boolean compressed = false;
        if (pipeline.get(DECOMPRESS) instanceof CompressionDecoder compression) {
            threshold = access.decompressThreshold(compression);
            validate = access.decompressValidates(compression);
            compressed = true;
        }
        long[] retained = new long[1];
        int[] uncompressed = new int[1];
        byte[] packet = FrameCodec.decode(ByteBufUtil.getBytes(frame), threshold, validate, (add, total) -> {
            reservation.reserve(add, total);
            retained[0] += add;
            uncompressed[0] = total;
        });
        int id = FrameCodec.packetId(packet);
        decodedSizes.accept(packet.length);
        // What the game's decompress handler takes after the frame splitter: a stored packet when
        // compression is on, the bare packet when it is not. Inflating happened once, above.
        ByteBuf forPipeline = compressed
                ? Unpooled.wrappedBuffer(FrameCodec.varInt(0), packet)
                : Unpooled.wrappedBuffer(packet);
        return new LaneGame.Decoded(forPipeline, stateOf(info.id()), id, Envelope.Bundle.NONE, false, retained[0],
                uncompressed[0]);
    }

    /**
     * The GAME record body for one packet the game's encoder wrote (its id VarInt and payload): the
     * frame its compression handler and length prepender would have produced, without encryption.
     */
    public byte[] outboundFrame(byte[] packet) throws IOException {
        int threshold = -1;
        if (pipeline.get(COMPRESS) instanceof CompressionEncoder compression) {
            threshold = compression.getThreshold();
        }
        return FrameCodec.encode(packet, threshold);
    }

    @Override
    public void encode(Object packet, ByteBuf out) {
        throw new UnsupportedOperationException("outbound packets go through the encoder sink, not GameLanes");
    }

    @Override
    public GameLanes.Route routeOf(Object packet) {
        throw new UnsupportedOperationException("outbound packets go through the encoder sink, not GameLanes");
    }

    /** True while an inbound protocol change is pending: the terminal packet was decoded, not yet applied. */
    public static boolean inboundSwapPending(ChannelPipeline pipeline) {
        return pipeline.get(INBOUND_CONFIG) != null;
    }

    /** The routing state of a game protocol. */
    public static ProtocolState stateOf(ConnectionProtocol protocol) {
        switch (protocol) {
            case HANDSHAKING:
                return ProtocolState.HANDSHAKING;
            case STATUS:
                return ProtocolState.STATUS;
            case LOGIN:
                return ProtocolState.LOGIN;
            case CONFIGURATION:
                return ProtocolState.CONFIGURATION;
            case PLAY:
                return ProtocolState.PLAY;
            default:
                throw new IllegalStateException("unknown protocol " + protocol);
        }
    }
}
