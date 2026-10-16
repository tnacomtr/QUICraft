// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.bridge.BindingPayloads;
import rs.sudoe.quicraft.bridge.CipherCutover;
import rs.sudoe.quicraft.bridge.GameLanes;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.QuicraftFabric;
import rs.sudoe.quicraft.fabric.mixin.CipherBaseAccessor;
import rs.sudoe.quicraft.fabric.mixin.CipherDecoderAccessor;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;
import rs.sudoe.quicraft.fabric.mixin.PacketEncoderAccessor;

import rs.sudoe.quicraft.fabric.net.FrameCodec;
import rs.sudoe.quicraft.fabric.net.PacketLaneCodec;

/**
 * One {@code quicraft/3} connection on a dedicated server, from the end of login to activation
 * (docs/protocol.md 12 and 13): the binding exchange of 12.8 to 12.13 in the configuration stage,
 * the cipher and framing cutover, and the switch from stream 0 to the three lanes. It is attached
 * to the connection's channel when the listener accepts it; a connection that is not {@code /3}
 * has none, and every hook then does nothing.
 *
 * <p><b>Fail safe.</b> Before activation any failure here closes the QUIC connection with
 * {@code 0x10} (12.14), which makes the client retry the join over fresh TCP; after activation
 * it is a disconnect. Nothing in this class runs for a {@code /2} or TCP connection.</p>
 *
 * <p>Threading: everything runs on the connection's event loop (which is the game channel's own)
 * except {@link #holdTask}'s tick, which only reads a flag.</p>
 */
public final class V3Join {
    private static final AttributeKey<V3Join> KEY = AttributeKey.valueOf("quicraft.v3join");
    private static final String PACKET_HANDLER = "packet_handler";
    private static final String DECRYPT = "decrypt";
    private static final String ENCRYPT = "encrypt";
    private static final String SPLITTER = "splitter";
    private static final String FILTER = "quicraft_lane_out";
    private static final String ENCODER = "encoder";
    private static final String SINK = "quicraft_lane_sink";
    private static final String LANE_ACK = "quicraft_lane_ack";
    /** 12.19: the explicit offline test profile; production servers never set it. */
    static final String OFFLINE_TEST_PROPERTY = "quicraft.multistream.offlineTest";
    /** A queued packet's size is not known before it is encoded; 12.10's 256-packet bound is the real limit. */
    private static final int QUEUED_PACKET_ESTIMATE = 1024;
    private static final ConfigurationTask.Type HOLD_TYPE = new ConfigurationTask.Type("quicraft_binding");
    /** 12.14: one warning per minute per listener. */
    private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final AtomicLong LAST_WARNING = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

    private enum Phase {
        NEW, BINDING, ACTIVE, FAILED
    }

    private final QuicBridgeChannel channel;
    private final V3Connection connection;
    private final MinecraftServer server;
    private final byte[] fingerprint;
    private final BindingHold hold = new BindingHold();
    private final AtomicBoolean failedOnce = new AtomicBoolean();
    private volatile Phase phase = Phase.NEW;
    private volatile byte[] secret;

    private BindingPayloads payloads;
    private CipherCutover cutover;
    private RoutingTable table;
    private int protocol;
    private GameLanes lanes;
    private PacketLaneCodec codec;
    /** The protocol state of the packet now in the encoder, set where it enters the pipeline. */
    private ProtocolState outboundState;
    private OutboundFilter filter;
    private ChannelFuture lastBindingWrite;
    private ByteBuf remainder;

    private V3Join(QuicBridgeChannel channel, V3Connection connection, MinecraftServer server, byte[] fingerprint) {
        this.channel = channel;
        this.connection = connection;
        this.server = server;
        this.fingerprint = fingerprint;
    }

    // --- Attaching and finding the join.

    /** Hook (listener accept): marks a bridge channel as a {@code /3} connection. Never throws. */
    static void attach(QuicBridgeChannel channel, V3Connection connection, MinecraftServer server,
            byte[] fingerprint) {
        try {
            channel.attr(KEY).set(new V3Join(channel, connection, server, fingerprint));
        } catch (Throwable t) {
            // No attribute: the connection can never bind, so close it where it stands.
            Hooks.failed("v3 attach", t);
            try {
                connection.abort(Protocol.CLOSE_BINDING_FAILURE);
            } catch (Throwable ignored) {
                // The listener's own handshake deadline remains.
            }
        }
    }

    /** The join of a game connection, or null when it is not {@code /3} (TCP, {@code /2}). */
    public static @Nullable V3Join of(@Nullable Connection game) {
        if (game == null) {
            return null;
        }
        io.netty.channel.Channel ch = ((ConnectionAccessor) game).quicraft$channel();
        return ch == null ? null : ch.attr(KEY).get();
    }

    // --- Hooks, called by the mixins.

    /** Hook (login): Minecraft's shared secret {@code S} (12.6), kept only until binding starts. */
    public void onSecret(byte[] encoded) {
        secret = encoded == null ? null : encoded.clone();
    }

    /**
     * Hook (start of the configuration stage): starts the binding exchange and returns the
     * configuration task that holds Finish Configuration until it is over (12.4).
     */
    public ConfigurationTask begin() {
        try {
            Hooks.enter("binding start");
            if (!channel.eventLoop().inEventLoop()) {
                // Packets queue only on the loop; elsewhere the exchange would start after the
                // first ordinary packets had already gone out.
                throw new IllegalStateException("configuration started off the connection's event loop");
            }
            start();
        } catch (Throwable t) {
            Hooks.failed("binding start", t);
            fail("binding start: " + describe(t), t);
        }
        return holdTask();
    }

    /** Hook (a {@code quicraft:binding} payload arrived): never reaches any other handler. */
    public void onPayload(byte[] payload) {
        try {
            Hooks.enter("binding payload");
            BindingPayloads current = payloads;
            if (current == null) {
                // 12.9: unsolicited, before the exchange began.
                fail("binding: unsolicited payload", null);
                return;
            }
            current.onPayload(payload);
        } catch (Throwable t) {
            Hooks.failed("binding payload", t);
            fail("binding payload: " + describe(t), t);
        }
    }

    /** The task that holds the configuration stage's finish until activation (12.4). */
    ConfigurationTask holdTask() {
        return new ConfigurationTask() {
            @Override
            public void start(java.util.function.Consumer<Packet<?>> sender) {
                // Nothing to send: the exchange runs on the loop and the stage waits here.
            }

            @Override
            public boolean tick() {
                return hold.open();
            }

            @Override
            public Type type() {
                return HOLD_TYPE;
            }
        };
    }

    // --- The exchange.

    private void start() throws Exception {
        if (phase != Phase.NEW) {
            throw new IllegalStateException("binding already started");
        }
        phase = Phase.BINDING;
        if (!connection.handshakeComplete()) {
            // 12.7: never start binding before the server's own TLS handshake completed.
            throw new IllegalStateException("the server's TLS handshake is not complete");
        }
        ChannelPipeline pipeline = channel.pipeline();
        protocol = SharedConstants.getProtocolVersion();
        table = MultistreamSwitch.tableFor(protocol);
        if (table == null) {
            throw new IllegalStateException("no routing table for protocol " + protocol + " (13.1)");
        }
        byte[] shared = secret;
        secret = null;
        int mode;
        try {
            if (server.usesAuthentication()) {
                if (shared == null || shared.length != 16) {
                    // 12.7: an authenticated login without the secret cannot be bound.
                    throw new IllegalStateException("online mode without Minecraft's shared secret");
                }
                mode = BindingStateMachine.MODE_ONLINE;
            } else {
                if (shared != null) {
                    // 12.19: encrypted but unauthenticated never takes mode 0.
                    throw new IllegalStateException("encrypted but unauthenticated login");
                }
                if (!Boolean.getBoolean(OFFLINE_TEST_PROPERTY)) {
                    throw new IllegalStateException("offline mode needs -D" + OFFLINE_TEST_PROPERTY + "=true (12.19)");
                }
                mode = BindingStateMachine.MODE_OFFLINE;
            }
            filter = new OutboundFilter(false);
            pipeline.addLast(FILTER, filter);
            if (mode == BindingStateMachine.MODE_ONLINE) {
                // 12.13: the boundary-aware decoder takes the game's decrypt handler over, with its
                // cipher, before OFFER goes out.
                Cipher cipher = cipherOf(pipeline);
                cutover = CipherCutover.install(pipeline, DECRYPT, ENCRYPT, cipher);
            }
            Forward forward = new Forward();
            BindingSession session = new BindingSession(BindingStateMachine.Role.SERVER, connection::exporterValue, shared,
                    mode, protocol, fingerprint, table.digest(), System::nanoTime, 0L, forward, channel.eventLoop(),
                    channel.eventLoop()::inEventLoop);
            payloads = new BindingPayloads(session, new Platform(), this::sendBindingPayload);
            forward.target = payloads;
        } finally {
            if (shared != null) {
                Arrays.fill(shared, (byte) 0);
            }
        }
        payloads.onConfigurationEntered(System.nanoTime());
    }

    private static Cipher cipherOf(ChannelPipeline pipeline) {
        Object decrypt = pipeline.get(DECRYPT);
        if (!(decrypt instanceof net.minecraft.network.CipherDecoder)) {
            throw new IllegalStateException("no cipher decoder in the pipeline");
        }
        return ((CipherBaseAccessor) ((CipherDecoderAccessor) decrypt).quicraft$cipherBase()).quicraft$cipher();
    }

    /** The session needs its callbacks before the glue that wraps it exists. */
    private static final class Forward implements BindingDriver.Callbacks {
        BindingPayloads target;

        @Override
        public void send(byte[] payload) {
            target.send(payload);
        }

        @Override
        public void activateOutbound() {
            target.activateOutbound();
        }

        @Override
        public void activateInbound() {
            target.activateInbound();
        }

        @Override
        public void fail(int code, String reason) {
            target.fail(code, reason);
        }
    }

    /** The platform's half of 12.11 and 12.12. */
    private final class Platform implements BindingDriver.Callbacks {
        @Override
        public void send(byte[] payload) {
            sendBindingPayload(payload);
        }

        @Override
        public void activateInbound() {
            try {
                Hooks.enter("binding inbound");
                // 12.11: the client's final encrypted packet verified; what follows is not ours to
                // decrypt or decode.
                if (cutover != null) {
                    remainder = cutover.stopAndTakeRemainder();
                    // The boundary decoder emits whole frames only, so the splitter holds nothing
                    // of the records; anything there would be bytes nobody owns (12.13).
                    ByteBuf stray = takeSplitterBytes();
                    boolean any = stray.isReadable();
                    stray.release();
                    if (any) {
                        throw new IllegalStateException("plaintext left in the frame splitter at the cutover");
                    }
                } else {
                    remainder = takeSplitterBytes();
                }
            } catch (Throwable t) {
                Hooks.failed("binding inbound", t);
                V3Join.this.fail("binding inbound: " + describe(t), t);
            }
        }

        @Override
        public void activateOutbound() {
            try {
                Hooks.enter("binding outbound");
                activateNow();
            } catch (Throwable t) {
                Hooks.failed("binding outbound", t);
                V3Join.this.fail("binding outbound: " + describe(t), t);
            }
        }

        @Override
        public void fail(int code, String reason) {
            V3Join.this.fail(reason, null);
        }
    }

    /** Writes one binding payload below the filter, so it bypasses the quiescence queue (12.10). */
    private void sendBindingPayload(byte[] payload) {
        ChannelHandlerContext self = filter == null ? null : filter.self;
        if (self == null) {
            throw new IllegalStateException("no outbound filter to write binding payloads below");
        }
        lastBindingWrite = self.writeAndFlush(new ClientboundCustomPayloadPacket(new ServerBindingPayload(payload)));
    }

    /**
     * Offline test profile: nothing is encrypted, so the bytes after CLIENT_SWITCH are in the frame
     * splitter's cumulation. They are copied for the lane session and dropped from the splitter, so its
     * decode loop cannot parse them as packets.
     */
    private ByteBuf takeSplitterBytes() throws Exception {
        Object splitter = channel.pipeline().get(SPLITTER);
        if (splitter == null) {
            throw new IllegalStateException("no frame splitter");
        }
        Method internal = io.netty.handler.codec.ByteToMessageDecoder.class.getDeclaredMethod("internalBuffer");
        internal.setAccessible(true);
        ByteBuf cumulation = (ByteBuf) internal.invoke(splitter);
        ByteBuf copy = channel.alloc().buffer(cumulation.readableBytes());
        copy.writeBytes(cumulation, cumulation.readerIndex(), cumulation.readableBytes());
        cumulation.skipBytes(cumulation.readableBytes());
        return copy;
    }

    /**
     * The cutover of 12.11 to 12.13 in one event-loop task, in the order the bridge needs: core's
     * undelivered bytes, then stream 0's hand-over, then the lanes (13.3: the server activates
     * before, or in the same task as, the flush of SERVER_SWITCH).
     */
    private void activateNow() {
        if (cutover != null) {
            // 12.12: the cipher encoder goes only after the final encrypted frame was written.
            cutover.outboundCutover(lastBindingWrite);
        }
        ByteBuf taken = remainder;
        remainder = null;
        ByteBuffer[] core = new ByteBuffer[1];
        connection.detachForActivation(bytes -> {
            if (bytes != null && bytes.hasRemaining()) {
                ByteBuffer copy = ByteBuffer.allocate(bytes.remaining());
                copy.put(bytes).flip();
                core[0] = copy;
            }
        });
        CompletableFuture<ByteBuf> handedOver;
        try {
            handedOver = channel.handOver(taken);
        } finally {
            if (taken != null) {
                taken.release();
            }
        }
        handedOver.whenComplete((handed, error) -> {
            try {
                if (error != null) {
                    fail("binding hand-over: " + describe(error), error);
                    return;
                }
                finishActivation(handed, core[0]);
            } catch (Throwable t) {
                fail("binding activation: " + describe(t), t);
            }
        });
    }

    private void finishActivation(ByteBuf handed, ByteBuffer coreLeftover) {
        ByteBuffer control;
        try {
            int length = handed.readableBytes() + (coreLeftover == null ? 0 : coreLeftover.remaining());
            control = null;
            if (length > 0) {
                control = ByteBuffer.allocate(length);
                control.put(ByteBufUtil.getBytes(handed));
                if (coreLeftover != null) {
                    control.put(coreLeftover);
                }
                control.flip();
            }
        } finally {
            handed.release();
        }
        ChannelPipeline pipeline = channel.pipeline();
        codec = new PacketLaneCodec(pipeline);
        // Inbound: decoded records re-enter the game's pipeline right behind the frame splitter, as
        // the frames TCP would have delivered, so every handler after it runs as usual.
        ChannelHandlerContext deliverFrom = pipeline.context(SPLITTER);
        if (deliverFrom == null) {
            throw new IllegalStateException("no frame splitter to deliver behind");
        }
        lanes = new GameLanes(channel, codec, connection::session, ProtocolState.CONFIGURATION, deliverFrom);
        pipeline.addAfter(PACKET_HANDLER, LANE_ACK, new AckHandler());
        // Outbound: the game's encoder keeps encoding, and its output goes to the session instead of
        // the compression, length and cipher handlers. The filter stays the first handler a write
        // meets and notes the state it is encoded in.
        pipeline.addBefore(ENCODER, SINK, new EncoderSink());
        // Vanilla's read timeout watches the byte path, which carries no game traffic from here on and
        // would kick the player after 30 s of "silence". QUIC's idle timeout and the lane session's own
        // deadlines (13.12, 13.14) take over.
        if (pipeline.get("timeout") != null) {
            pipeline.remove("timeout");
        }
        pipeline.remove(filter);
        filter = new OutboundFilter(true);
        pipeline.addLast(FILTER, filter);
        outboundState = encoderState(pipeline);
        phase = Phase.ACTIVE;
        connection.activate(lanes, protocol, control);
        // 12.13: what was queued during the exchange is encoded anew, as records.
        for (Object packet : payloads.releaseQueued()) {
            channel.write(packet);
        }
        channel.flush();
        hold.release();
    }

    // --- Failure.

    /**
     * Ends the connection: {@code 0x10} before activation (the client retries over fresh TCP, 12.14),
     * an internal-error close after it. Idempotent.
     */
    void fail(String reason, @Nullable Throwable cause) {
        if (!failedOnce.compareAndSet(false, true)) {
            return;
        }
        boolean activated = phase == Phase.ACTIVE;
        phase = Phase.FAILED;
        hold.fail();
        byte[] leftoverSecret = secret;
        secret = null;
        if (leftoverSecret != null) {
            // 12.6: erase task-owned copies of S on failure.
            Arrays.fill(leftoverSecret, (byte) 0);
        }
        try {
            long now = System.nanoTime();
            long last = LAST_WARNING.get();
            if (now - last >= WARN_INTERVAL_NANOS && LAST_WARNING.compareAndSet(last, now)) {
                QuicraftFabric.LOG.warn("QUICraft: multi-stream join failed ({}); the client falls back to TCP",
                        reason);
            } else {
                QuicraftFabric.LOG.debug("QUICraft: multi-stream join failed ({})", reason, cause);
            }
        } catch (Throwable ignored) {
            // Logging is best effort.
        }
        try {
            ByteBuf leftover = remainder;
            remainder = null;
            if (leftover != null) {
                leftover.release();
            }
            connection.abort(activated ? Protocol.CLOSE_INTERNAL_ERROR : Protocol.CLOSE_BINDING_FAILURE);
        } catch (Throwable t) {
            QuicraftFabric.LOG.debug("QUICraft: closing a failed multi-stream join", t);
        }
    }

    /** Closes a {@code /3} connection whose hook threw before it could tell the exchange. Never throws. */
    public static void failOf(@Nullable Connection game, String reason, Throwable cause) {
        try {
            V3Join join = of(game);
            if (join != null) {
                join.fail(reason, cause);
            }
        } catch (Throwable ignored) {
            // The listener's handshake deadline and idle timeout remain.
        }
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    // --- Pipeline handlers.

    /**
     * Tail-most outbound handler. Before activation it holds ordinary packets back while the exchange
     * runs (12.10). After activation it notes the state each packet is encoded in and ends the lane
     * session when the game closes the channel (13.14).
     */
    private final class OutboundFilter extends ChannelOutboundHandlerAdapter {
        private final boolean active;
        ChannelHandlerContext self;

        OutboundFilter(boolean active) {
            this.active = active;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            self = ctx;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            try {
                if (!(msg instanceof Packet)) {
                    // The game's own pipeline-configuration messages (Connection.setupInboundProtocol
                    // and setupOutboundProtocol) are for its handlers, not for a record.
                    ctx.write(msg, promise);
                    return;
                }
                if (!active) {
                    BindingPayloads current = payloads;
                    if (current != null) {
                        switch (current.queueOrdinary(msg, QUEUED_PACKET_ESTIMATE)) {
                            case QUEUED:
                                promise.trySuccess();
                                return;
                            case DROPPED_FAILED:
                                promise.tryFailure(new IllegalStateException("binding quiescence queue overflow"));
                                return;
                            default:
                                break;
                        }
                    }
                } else {
                    // The encoder swaps itself out when it writes a terminal packet, so this is where the
                    // state a packet is sent in can still be read (bundles and split payloads share it).
                    ProtocolState state = encoderState(ctx.pipeline());
                    if (state != null) {
                        outboundState = state;
                    }
                }
                ctx.write(msg, promise);
            } catch (Throwable t) {
                promise.tryFailure(t);
                fail("binding outbound filter: " + describe(t), t);
            }
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            if (active) {
                try {
                    connection.end();
                } catch (Throwable t) {
                    QuicraftFabric.LOG.debug("QUICraft: ending the multi-stream connection", t);
                }
            }
            ctx.close(promise);
        }
    }

    /** The state of the protocol the game's encoder runs right now, or null while none is configured. */
    private static ProtocolState encoderState(ChannelPipeline pipeline) {
        io.netty.channel.ChannelHandler encoder = pipeline.get(ENCODER);
        if (!(encoder instanceof net.minecraft.network.PacketEncoder<?>)) {
            return null;
        }
        return PacketLaneCodec.stateOf(((PacketEncoderAccessor) encoder).quicraft$protocolInfo().id());
    }

    /**
     * Sits behind the game's encoder, where its output (packet id and payload) would go on to the
     * compression, length-prefix and cipher handlers. After activation that output is a GAME record
     * instead (13.4), framed as those handlers would have framed it. The packets before it ran the
     * game's whole outbound pipeline, so bundles, split payloads and protocol swaps behaved as on TCP.
     */
    private final class EncoderSink extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof ByteBuf)) {
                ctx.write(msg, promise);
                return;
            }
            ByteBuf buffer = (ByteBuf) msg;
            try {
                byte[] packet = ByteBufUtil.getBytes(buffer);
                ProtocolState state = outboundState;
                if (state == null) {
                    throw new IllegalStateException("no outbound state for an encoded packet");
                }
                connection.session().send(state, FrameCodec.packetId(packet), codec.outboundFrame(packet));
                promise.trySuccess();
            } catch (Throwable t) {
                promise.tryFailure(t);
                fail("binding outbound sink: " + describe(t), t);
            } finally {
                buffer.release();
            }
        }
    }

    /**
     * After the game's packet handler: acknowledges each delivery (13.10). The ack follows the
     * handler having accepted the packets; for a protocol-changing packet it waits until the game has
     * applied the change, because the next records are decoded with the new protocol.
     */
    private final class AckHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof GameLanes.Ack) {
                acknowledge(ctx, (GameLanes.Ack) msg);
                return;
            }
            ctx.fireChannelRead(msg);
        }

        private void acknowledge(ChannelHandlerContext ctx, GameLanes.Ack ack) {
            try {
                if (!ctx.channel().isOpen() || lanes.isClosed()) {
                    return;
                }
                if (PacketLaneCodec.inboundSwapPending(ctx.pipeline())) {
                    ctx.executor().schedule(() -> acknowledge(ctx, ack), 1, TimeUnit.MILLISECONDS);
                    return;
                }
                lanes.applied(ack.lane(), ack.sequence());
            } catch (Throwable t) {
                fail("binding ack: " + describe(t), t);
            }
        }
    }
}
