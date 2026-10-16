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
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.Packet;
import rs.sudoe.quicraft.bridge.GameLanes;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.fabric.mixin.CompressionDecoderAccessor;
import rs.sudoe.quicraft.fabric.mixin.PacketDecoderAccessor;
import rs.sudoe.quicraft.fabric.mixin.PacketEncoderAccessor;

/**
 * The game's own packet codec, compression and protocol state machine behind {@link GameLanes}
 * (docs/protocol.md 13.4, 13.6, 13.16): a GAME record's body is exactly the frame the game's
 * length prepender and compression encoder would write, so this class asks the live
 * {@code decoder}/{@code encoder} handlers of the connection's pipeline for the current protocol
 * and the {@code compress}/{@code decompress} handlers for the negotiated threshold, and does the
 * rest with {@link FrameCodec}. The game's state machine stays authoritative: a terminal packet
 * swaps the handler exactly as the game's own handlers do, except that it never touches auto-read,
 * because stream 0 now belongs to the lane session (13.16).
 *
 * <p>Threading: the connection's event loop, which is where {@link GameLanes} calls it.</p>
 */
public final class PacketLaneCodec implements GameLanes.Codec {
    private static final String DECODER = "decoder";
    private static final String ENCODER = "encoder";
    private static final String DECOMPRESS = "decompress";
    private static final String COMPRESS = "compress";
    private static final String INBOUND_CONFIG = "inbound_config";
    private static final String OUTBOUND_CONFIG = "outbound_config";

    /**
     * How the codec reads the game's private handler state: mixin accessors in the game, reflection
     * stand-ins in tests that start no game.
     */
    public interface Access {
        ProtocolInfo<?> decoderProtocol(PacketDecoder<?> decoder);

        ProtocolInfo<?> encoderProtocol(PacketEncoder<?> encoder);

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
        public ProtocolInfo<?> encoderProtocol(PacketEncoder<?> encoder) {
            return ((PacketEncoderAccessor) encoder).quicraft$protocolInfo();
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
    private final RoutingTable table;
    private final Direction outboundDirection;
    /** {@link GameLanes} routes and then encodes the same packet: encode once, keep it for the second call. */
    private Object cachedPacket;
    private byte[] cachedBytes;
    private ProtocolState cachedState;
    private int cachedId;

    /**
     * @param pipeline the game's pipeline on this connection
     * @param table the routing table (13.6) for the connection's protocol number
     * @param outboundDirection {@link Direction#CLIENTBOUND} on a server, {@link Direction#SERVERBOUND}
     *     on a client
     */
    public PacketLaneCodec(ChannelPipeline pipeline, RoutingTable table, Direction outboundDirection) {
        this(pipeline, table, outboundDirection, MIXINS);
    }

    /** As above, with the game's private state read through {@code access}. */
    public PacketLaneCodec(ChannelPipeline pipeline, RoutingTable table, Direction outboundDirection,
            Access access) {
        if (pipeline == null || table == null || outboundDirection == null || access == null) {
            throw new IllegalArgumentException("pipeline/table/outboundDirection/access");
        }
        this.pipeline = pipeline;
        this.access = access;
        this.table = table;
        this.outboundDirection = outboundDirection;
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
        if (pipeline.get(DECOMPRESS) instanceof CompressionDecoder compression) {
            threshold = access.decompressThreshold(compression);
            validate = access.decompressValidates(compression);
        }
        long[] retained = new long[1];
        int[] uncompressed = new int[1];
        byte[] bytes = FrameCodec.decode(ByteBufUtil.getBytes(frame), threshold, validate, (add, total) -> {
            reservation.reserve(add, total);
            retained[0] += add;
            uncompressed[0] = total;
        });
        ByteBuf in = Unpooled.wrappedBuffer(bytes);
        try {
            int id = FrameCodec.packetId(bytes);
            Packet<?> packet = info.codec().decode(in);
            if (in.isReadable()) {
                throw new IOException("Packet " + info.id().id() + "/" + id + " (" + packet.getClass().getSimpleName()
                        + ") was larger than I expected, found " + in.readableBytes() + " bytes extra");
            }
            boolean terminal = packet.isTerminal();
            if (terminal) {
                swapInboundForTerminal();
            }
            return new LaneGame.Decoded(packet, stateOf(info.id()), id, Envelope.Bundle.NONE, terminal, retained[0],
                    uncompressed[0]);
        } finally {
            in.release();
        }
    }

    /**
     * Bundles are clientbound, so a server's inbound packets are never part of one. A client
     * decodes with {@link #decodeWithBundles}.
     */
    public LaneGame.Decoded decodeWithBundles(ByteBuf frame, ProtocolState state, LaneGame.Reservation reservation,
            boolean insideBundle) throws Exception {
        LaneGame.Decoded decoded = decode(frame, state, reservation);
        Envelope.Bundle bundle;
        if (decoded.packet() instanceof BundleDelimiterPacket) {
            bundle = insideBundle ? Envelope.Bundle.CLOSE : Envelope.Bundle.OPEN;
        } else {
            bundle = insideBundle ? Envelope.Bundle.MEMBER : Envelope.Bundle.NONE;
        }
        if (bundle == Envelope.Bundle.NONE) {
            return decoded;
        }
        return new LaneGame.Decoded(decoded.packet(), decoded.state(), decoded.packetId(), bundle,
                decoded.stateChange(), decoded.retainedBytes(), decoded.uncompressedBytes());
    }

    @Override
    public void encode(Object packet, ByteBuf out) throws Exception {
        Prepared prepared = prepare(packet);
        int threshold = -1;
        if (pipeline.get(COMPRESS) instanceof CompressionEncoder compression) {
            threshold = compression.getThreshold();
        }
        out.writeBytes(FrameCodec.encode(prepared.bytes, threshold));
        // A pair of routeOf and encode ends here: a packet object that changes before it is sent
        // again is encoded afresh.
        cachedPacket = null;
        cachedBytes = null;
        if (((Packet<?>) packet).isTerminal()) {
            swapOutboundForTerminal();
        }
    }

    @Override
    public GameLanes.Route routeOf(Object packet) {
        Prepared prepared = prepare(packet);
        Lane lane = table.laneFor(prepared.state, outboundDirection, prepared.id);
        return new GameLanes.Route(prepared.state, prepared.id, lane);
    }

    /** The packet's id VarInt and payload under the encoder's current protocol, encoded once. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Prepared prepare(Object packet) {
        if (packet == cachedPacket && cachedBytes != null) {
            return new Prepared(cachedBytes, cachedState, cachedId);
        }
        PacketEncoder<?> encoder = (PacketEncoder<?>) pipeline.get(ENCODER);
        if (encoder == null) {
            throw new IllegalStateException("no outbound protocol is configured (13.16)");
        }
        ProtocolInfo<?> info = access.encoderProtocol(encoder);
        ByteBuf buffer = Unpooled.buffer();
        try {
            ((StreamCodec) info.codec()).encode(buffer, packet);
            byte[] bytes = ByteBufUtil.getBytes(buffer);
            int id = FrameCodec.packetId(bytes);
            ProtocolState state = stateOf(info.id());
            cachedPacket = packet;
            cachedBytes = bytes;
            cachedState = state;
            cachedId = id;
            return new Prepared(bytes, state, id);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            buffer.release();
        }
    }

    /**
     * The game's PacketEncoder reacts to a terminal packet by replacing itself with
     * {@code outbound_config}, which is where the next protocol is configured from.
     */
    private void swapOutboundForTerminal() {
        cachedPacket = null;
        cachedBytes = null;
        pipeline.addAfter(ENCODER, OUTBOUND_CONFIG, new UnconfiguredPipelineHandler.Outbound());
        pipeline.remove(ENCODER);
    }

    /**
     * As the game's PacketDecoder does for a terminal packet, minus the auto-read switch: the lane
     * session, not auto-read, holds back the records that follow until the game has applied this
     * one (13.10, 13.16).
     */
    private void swapInboundForTerminal() {
        pipeline.addBefore(DECODER, INBOUND_CONFIG, new UnconfiguredPipelineHandler.Inbound());
        pipeline.remove(DECODER);
    }

    /** True while an inbound protocol change is pending: the terminal packet was decoded, not yet applied. */
    public static boolean inboundSwapPending(ChannelPipeline pipeline) {
        return pipeline.get(INBOUND_CONFIG) != null;
    }

    private static ProtocolState stateOf(ConnectionProtocol protocol) {
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

    private static final class Prepared {
        final byte[] bytes;
        final ProtocolState state;
        final int id;

        Prepared(byte[] bytes, ProtocolState state, int id) {
            this.bytes = bytes;
            this.state = state;
            this.id = id;
        }
    }
}
