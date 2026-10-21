// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.EncoderException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.Packet;
import rs.sudoe.quicraft.bridge.GameLanes;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.fabric.client.mixin.CompressionDecoderAccessor;
import rs.sudoe.quicraft.fabric.client.mixin.CompressionEncoderAccessor;
import rs.sudoe.quicraft.fabric.client.mixin.PacketDecoderAccessor;
import rs.sudoe.quicraft.fabric.client.mixin.PacketEncoderAccessor;

/**
 * The game's packet codec behind {@link GameLanes} (docs/protocol.md 13.4, 13.6): one GAME record
 * body is exactly the frame vanilla would have put on a TCP stream, minus Minecraft's cipher.
 *
 * <p>Nothing is re-implemented. The handlers vanilla installed in the pipeline do the work, one
 * frame at a time: {@code decompress} and {@code decoder} for an inbound frame, {@code encoder} and
 * {@code compress} for an outbound packet. That keeps the negotiated compression threshold, the
 * current protocol and the terminal-packet swaps ({@code decoder} to {@code inbound_config},
 * {@code encoder} to {@code outbound_config}) exactly vanilla's, in every version this module
 * supports. The frame's VarInt length prefix is the only framing done here.</p>
 *
 * <p>Event loop only. The game channel is the bridge channel the pipeline was built on.</p>
 */
public final class LaneCodec implements GameLanes.Codec {
    /** A VarInt-length-prefixed frame never carries more than three length bytes (21 bits). */
    private static final int MAX_FRAME = (1 << 21) - 1;

    private final Channel channel;
    private final RoutingTable table;
    private final java.util.function.LongConsumer decodedBytes;
    /**
     * Routing input kept per packet: id VarInt (at most 5 bytes) plus the reviewed leading fields.
     * Valid only because CHUNK routes accept SINGLE and JOIN layouts (at most 13 bytes,
     * RoutingTableLoader); a LIST layout walks the body and must never read this prefix.
     */
    static final int ROUTING_PREFIX_BYTES = 64;
    /**
     * Fixed heap cost counted per decoded packet on top of its uncompressed size (object header, decoded
     * packet fields, routing prefix bookkeeping), so a flood of tiny packets still moves the backlog bound.
     */
    static final long PER_PACKET_OVERHEAD_BYTES = 64;

    private final Access access;

    /** Private Minecraft handler operations, substituted only at the external codec boundary in tests. */
    interface Access {
        ProtocolState state(PacketDecoder<?> decoder);
        void decode(ChannelHandlerContext context, ByteBuf bytes, List<Object> out) throws Exception;
        void decompress(ChannelHandlerContext context, ByteBuf bytes, List<Object> out) throws Exception;
    }

    private static final Access MIXINS = new Access() {
        @Override
        public ProtocolState state(PacketDecoder<?> decoder) {
            return stateOf(((PacketDecoderAccessor) decoder).quicraft$protocolInfo().id());
        }

        @Override
        public void decode(ChannelHandlerContext context, ByteBuf bytes, List<Object> out) throws Exception {
            ((PacketDecoderAccessor) context.handler()).quicraft$decode(context, bytes, out);
        }

        @Override
        public void decompress(ChannelHandlerContext context, ByteBuf bytes, List<Object> out) throws Exception {
            ((CompressionDecoderAccessor) context.handler()).quicraft$decode(context, bytes, out);
        }
    };
    /** Between {@code routeOf}, which encodes, and {@code encode}, which hands the bytes over. */
    private Packet<?> encodedPacket;
    private byte[] encodedFrame;
    /** Whether the last decoded packet opened a bundle that is not closed yet. */
    private boolean inBundle;

    public LaneCodec(Channel channel, RoutingTable table) {
        this(channel, table, null);
    }

    /** {@code decodedBytes} is told the wire size of every frame decoded (for the main-thread backlog bound). */
    LaneCodec(Channel channel, RoutingTable table, java.util.function.LongConsumer decodedBytes) {
        this(channel, table, decodedBytes, MIXINS);
    }

    LaneCodec(Channel channel, RoutingTable table, java.util.function.LongConsumer decodedBytes, Access access) {
        this.channel = channel;
        this.table = table;
        this.decodedBytes = decodedBytes;
        this.access = access;
    }

    // --- Inbound.

    @Override
    public LaneGame.Decoded decode(ByteBuf frame, ProtocolState hint, LaneGame.Reservation reservation)
            throws Exception {
        ChannelPipeline pipeline = channel.pipeline();
        ChannelHandlerContext decoderContext = pipeline.context("decoder");
        if (decoderContext == null || !(decoderContext.handler() instanceof PacketDecoder)) {
            throw new IllegalStateException("no inbound protocol is configured (13.16)");
        }
        ProtocolState state = access.state((PacketDecoder<?>) decoderContext.handler());

        int length = VarInt.read(frame);
        if (length < 0 || length != frame.readableBytes()) {
            throw new IOException("a GAME body must be exactly one frame (13.4), length " + length + " of "
                    + frame.readableBytes());
        }
        ByteBuf packetBytes = frame;
        boolean owned = false;
        ChannelHandlerContext decompressContext = pipeline.context("decompress");
        try {
            if (decompressContext != null && decompressContext.handler() instanceof CompressionDecoder) {
                // 13.12: charge the declared size before the game allocates and inflates it.
                int declared = VarInt.read(frame.duplicate());
                int charge = declared > 0 ? declared : frame.readableBytes();
                reservation.reserve(charge, charge);
                List<Object> inflated = new ArrayList<>(1);
                access.decompress(decompressContext, frame, inflated);
                if (inflated.size() != 1) {
                    throw new IOException("decompression produced " + inflated.size() + " buffers");
                }
                packetBytes = (ByteBuf) inflated.get(0);
                owned = true;
            } else {
                reservation.reserve(frame.readableBytes(), frame.readableBytes());
            }
            int packetId = VarInt.read(packetBytes.duplicate());
            // The game's decoder consumes this buffer (and the inflated buffer is released below).
            // CHUNK validation needs the id and the leading position fields afterwards; an absent
            // position input closes 0x11. Keep only a bounded prefix: every reviewed extractor reads
            // the id VarInt plus at most 8 bytes, and a whole copy would halve the largest packet the
            // 8 MiB workspace accepts. Charge it before allocating, separately from decode storage.
            int uncompressedSize = packetBytes.readableBytes();
            if (decodedBytes != null) {
                // The backlog bound counts what the decoded packet holds, not the (possibly compressed) wire size.
                decodedBytes.accept(uncompressedSize + PER_PACKET_OVERHEAD_BYTES);
            }
            int prefix = Math.min(uncompressedSize, ROUTING_PREFIX_BYTES);
            reservation.reserve(prefix, prefix);
            byte[] uncompressed = ByteBufUtil.getBytes(packetBytes, packetBytes.readerIndex(), prefix);
            List<Object> out = new ArrayList<>(1);
            access.decode(decoderContext, packetBytes, out);
            if (out.size() != 1 || !(out.get(0) instanceof Packet)) {
                throw new IOException("the packet decoder produced " + out.size() + " objects");
            }
            Packet<?> packet = (Packet<?>) out.get(0);
            return new LaneGame.Decoded(packet, state, packetId, bundleOf(packet), packet.isTerminal(),
                    prefix, uncompressedSize, uncompressed);
        } finally {
            if (owned) {
                packetBytes.release();
            }
        }
    }

    /** OPEN, MEMBER and CLOSE follow the delimiter packets, in delivery order (13.15). */
    private Envelope.Bundle bundleOf(Packet<?> packet) {
        if (packet instanceof BundleDelimiterPacket) {
            if (!inBundle) {
                inBundle = true;
                return Envelope.Bundle.OPEN;
            }
            inBundle = false;
            return Envelope.Bundle.CLOSE;
        }
        return inBundle ? Envelope.Bundle.MEMBER : Envelope.Bundle.NONE;
    }

    // --- Outbound.

    @Override
    public GameLanes.Route routeOf(Object message) {
        if (!(message instanceof Packet)) {
            throw new IllegalStateException("not a game packet: " + message.getClass().getName());
        }
        Packet<?> packet = (Packet<?>) message;
        ChannelPipeline pipeline = channel.pipeline();
        ChannelHandlerContext encoderContext = pipeline.context("encoder");
        if (encoderContext == null || !(encoderContext.handler() instanceof PacketEncoder)) {
            throw new IllegalStateException("no outbound protocol is configured");
        }
        PacketEncoderAccessor encoder = (PacketEncoderAccessor) encoderContext.handler();
        // The protocol the packet is sent in, read before the encoder can swap itself out.
        ProtocolState state = stateOf(encoder.quicraft$protocolInfo().id());
        ByteBuf body = channel.alloc().buffer();
        try {
            encoder.quicraft$encode(encoderContext, packet, body);
            int packetId = VarInt.read(body.duplicate());
            encodedFrame = frame(channel, body);
            encodedPacket = packet;
            return new GameLanes.Route(state, packetId, table.laneFor(state, Direction.SERVERBOUND, packetId));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // The game's own encoder failed; routeOf cannot throw checked exceptions.
            throw new EncoderException(e);
        } finally {
            body.release();
        }
    }

    /**
     * What the encoder wrote (packet id and body) as the GAME record body of 13.4: through the game's
     * own compression handler if one is installed, behind a VarInt length. Does not release {@code body}.
     */
    static byte[] frame(Channel channel, ByteBuf body) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();
        ByteBuf compressed = null;
        ByteBuf framed = null;
        try {
            ByteBuf payload = body;
            ChannelHandlerContext compressContext = pipeline.context("compress");
            if (compressContext != null && compressContext.handler() instanceof CompressionEncoder) {
                compressed = channel.alloc().buffer();
                ((CompressionEncoderAccessor) compressContext.handler()).quicraft$encode(compressContext, body,
                        compressed);
                payload = compressed;
            }
            if (payload.readableBytes() > MAX_FRAME) {
                throw new EncoderException("packet too large: " + payload.readableBytes());
            }
            framed = channel.alloc().buffer(payload.readableBytes() + 3);
            VarInt.write(framed, payload.readableBytes());
            framed.writeBytes(payload);
            return ByteBufUtil.getBytes(framed);
        } finally {
            if (compressed != null) {
                compressed.release();
            }
            if (framed != null) {
                framed.release();
            }
        }
    }

    @Override
    public void encode(Object message, ByteBuf out) {
        if (message != encodedPacket || encodedFrame == null) {
            throw new IllegalStateException("encode() without the routeOf() of the same packet");
        }
        out.writeBytes(encodedFrame);
        encodedFrame = null;
        encodedPacket = null;
    }

    static ProtocolState stateOf(ConnectionProtocol protocol) {
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
