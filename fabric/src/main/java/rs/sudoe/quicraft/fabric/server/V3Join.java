// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import com.mojang.authlib.GameProfile;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
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
import rs.sudoe.quicraft.core.multistream.MultistreamSession;
import rs.sudoe.quicraft.core.multistream.NStreamSession;
import rs.sudoe.quicraft.core.multistream.OutboundPacket;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.multistream.SentPacket;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.QuicraftFabric;
import rs.sudoe.quicraft.fabric.mixin.CipherBaseAccessor;
import rs.sudoe.quicraft.fabric.mixin.CipherDecoderAccessor;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;
import rs.sudoe.quicraft.fabric.mixin.PacketEncoderAccessor;

import rs.sudoe.quicraft.fabric.net.BindingDecodeContext;
import rs.sudoe.quicraft.fabric.net.BindingPayload;
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
public final class V3Join implements BindingDecodeContext.Gate {
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

    private final QuicBridgeChannel channel;
    private final V3Connection connection;
    private final MinecraftServer server;
    private final byte[] fingerprint;
    private final BindingHold hold = new BindingHold();
    private final ReceiveBudget node;
    private final JoinState state = new JoinState();
    private final SecretHolder secrets = new SecretHolder();

    private BindingPayloads payloads;
    /** Stable connection origin, retained across listener swaps and preactivation queueing. */
    private CausalSendContext causal;
    /** The login profile the configuration listener holds, captured at begin (13.12 account key). */
    private GameProfile profile;
    /** The 13.12 account key for this join's pool and ranking charges, or null (set at start). */
    private String account;
    private CipherCutover cutover;
    private RoutingTable table;
    private int protocol;
    private GameLanes lanes;
    private PacketLaneCodec codec;
    /** The protocol state of the packet now in the encoder, set where it enters the pipeline. */
    private ProtocolState outboundState;
    private BindingSession session;
    /** The write being encoded right now, which the encoder sink collects into; event loop only. */
    private OutboundUnit unitInFlight;
    private ChannelHandlerContext sinkContext;
    /** True from the moment the lane session exists: the sink submits instead of replaying. */
    private boolean sinkActive;
    private OutboundHold outHold;
    private AckBacklog backlog;
    private final java.util.ArrayDeque<Long> decodedSizes = new java.util.ArrayDeque<>();
    private boolean retryScheduled;
    private OutboundFilter filter;
    private ChannelFuture lastBindingWrite;
    private ByteBuf remainder;

    private V3Join(QuicBridgeChannel channel, V3Connection connection, MinecraftServer server, byte[] fingerprint,
            ReceiveBudget node) {
        this.node = node;
        this.channel = channel;
        this.connection = connection;
        this.server = server;
        this.fingerprint = fingerprint;
    }

    // --- Attaching and finding the join.

    /** Hook (listener accept): marks a bridge channel as a {@code /3} connection. Never throws. */
    static void attach(QuicBridgeChannel channel, V3Connection connection, MinecraftServer server,
            byte[] fingerprint, ReceiveBudget node) {
        V3Join join = null;
        try {
            V3Join attached = new V3Join(channel, connection, server, fingerprint, node);
            join = attached;
            // Every way a join ends, a held secret, the binding session and a taken-over buffer go.
            channel.closeFuture().addListener(f -> attached.teardown());
            if (channel.closeFuture().isDone()) {
                throw new IllegalStateException("cannot attach binding to a closed channel");
            }
            channel.attr(KEY).set(attached);
            channel.attr(BindingDecodeContext.SERVER_GATE).set(attached);
        } catch (Throwable t) {
            if (join != null) {
                join.clearBindingGate();
                channel.attr(KEY).compareAndSet(join, null);
            }
            // No owned gate: the connection can never bind, so close it where it stands.
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

    /** Keep unsolicited NEW and unexpected ACTIVE payloads visible to the fail-closed validator. */
    @Override
    public boolean expectsBinding() {
        return !state.failed();
    }

    private void clearBindingGate() {
        channel.attr(BindingDecodeContext.SERVER_GATE).compareAndSet(this, null);
    }

    /** Hook (login): Minecraft's shared secret {@code S} (12.6), kept only until binding starts. */
    public void onSecret(byte[] encoded) {
        // The holder zeroes any copy it held before; the caller zeroes the array it passed.
        secrets.set(encoded);
    }

    /**
     * Hook (start of the configuration stage): starts the binding exchange and returns the
     * configuration task that holds Finish Configuration until it is over (12.4).
     */
    public ConfigurationTask begin(GameProfile player) {
        this.profile = player;
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
        if (!state.startBinding()) {
            throw new IllegalStateException("binding already started or failed");
        }
        if (!connection.handshakeComplete()) {
            // 12.7: never start binding before the server's own TLS handshake completed.
            throw new IllegalStateException("the server's TLS handshake is not complete");
        }
        // 13.2: the binding must carry the N the preamble exchange selected. It is read here, before
        // the pipeline is touched and before the secret is consumed, so an unnegotiated context
        // fails the join closed (12.14) with no half-installed pipeline and no cipher cutover.
        int shardCount = connection.sessionContext().shardCount();
        ChannelPipeline pipeline = channel.pipeline();
        causal = CausalSendContext.attached(channel);
        if (causal == null) {
            throw new IllegalStateException("no causal context for /3 binding");
        }
        causal.bindTransport(connection);
        protocol = SharedConstants.getProtocolVersion();
        table = MultistreamSwitch.tableFor(protocol);
        if (table == null) {
            throw new IllegalStateException("no routing table for protocol " + protocol + " (13.1)");
        }
        byte[] shared = secrets.take();
        int mode;
        try {
            if (server.usesAuthentication()) {
                if (shared == null || shared.length != 16) {
                    // 12.7: an authenticated login without the secret cannot be bound.
                    throw new IllegalStateException("online mode without Minecraft's shared secret");
                }
                mode = BindingStateMachine.MODE_ONLINE;
                account = accountKey(profile, true);
            } else {
                account = accountKey(profile, false);
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
            // The encoder's output is collected per write from here on, so the quiescence queue
            // counts real bytes and a bundle stays whole (12.10, 13.15).
            pipeline.addBefore(ENCODER, SINK, new EncoderSink());
            if (mode == BindingStateMachine.MODE_ONLINE) {
                // 12.13: the boundary-aware decoder takes the game's decrypt handler over, with its
                // cipher, before OFFER goes out.
                Cipher cipher = cipherOf(pipeline);
                cutover = CipherCutover.install(pipeline, DECRYPT, ENCRYPT, cipher);
            }
            Forward forward = new Forward();
            session = new BindingSession(BindingStateMachine.Role.SERVER, connection::exporterValue, shared,
                    mode, protocol, shardCount, fingerprint, table.digest(), System::nanoTime, 0L, forward,
                    channel.eventLoop(), channel.eventLoop()::inEventLoop);
            payloads = new BindingPayloads(session, new Platform(), this::sendBindingPayload);
            forward.target = payloads;
        } finally {
            if (shared != null) {
                Arrays.fill(shared, (byte) 0);
            }
        }
        if (state.failed()) {
            return;
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
                if (state.failed()) {
                    return;
                }
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
                if (state.failed()) {
                    return;
                }
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
        lastBindingWrite = self.writeAndFlush(new ClientboundCustomPayloadPacket(new BindingPayload(payload)));
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
                if (error == null && state.failed()) {
                    // Failed meanwhile: a failed join moves nowhere, and the buffer is nobody's.
                    handed.release();
                    return;
                }
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
        codec.onDecoded(size -> decodedSizes.add(Long.valueOf(size)));
        // Inbound: decoded records re-enter the game's pipeline right behind the frame splitter, as
        // the frames TCP would have delivered, so every handler after it runs as usual.
        ChannelHandlerContext deliverFrom = pipeline.context(SPLITTER);
        if (deliverFrom == null) {
            throw new IllegalStateException("no frame splitter to deliver behind");
        }
        lanes = new GameLanes(channel, codec, connection::session, ProtocolState.CONFIGURATION, deliverFrom);
        pipeline.addAfter(PACKET_HANDLER, LANE_ACK, new AckHandler());
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
        outHold = new OutboundHold(new LaneSender(), NStreamSession.MAX_UNSENT_PACKETS, NStreamSession.MAX_UNSENT_BYTES,
                node);
        backlog = new AckBacklog((lane, sequence) -> lanes.applied(lane, sequence), this::offerBehindHandlers, node,
                AckBacklog.MAX_PACKETS, AckBacklog.MAX_BYTES);
        // 13.4: the platform reads the live compression state once, here, and the session keeps it.
        connection.activate(lanes, protocol, PacketLaneCodec.compressionEnabled(pipeline), account, control);
        causalContext().bindSession(connection, connection.session());
        sinkActive = true;
        // 12.13: what was queued during the exchange goes out as records, whole units in order. A
        // submission that fails takes every unit it did not hand over with it and gives their node
        // reservations back (OutboundHold.submitAll), so a failure here strands none of them.
        outHold.submitAll(payloads.releaseQueued());
        if (state.failed()) {
            return;
        }
        // ACTIVE only now that the cutover is complete: a failure before this is a binding failure
        // (0x10), and the hold on the configuration stage opens only for a join that got here.
        if (!state.activate()) {
            return;
        }
        hold.release();
    }

    /** The lane layer as {@link OutboundHold} uses it. */
    private final class LaneSender implements OutboundHold.Sender {
        @Override
        public boolean writable() {
            GameLanes current = lanes;
            return current == null || current.isWritable();
        }

        @Override
        public void send(ProtocolState packetState, byte[] packet) {
            throw new IllegalStateException("V3 requires whole-unit causal admission");
        }

        @Override
        public void sendBundle(ProtocolState packetState, List<byte[]> packets) {
            throw new IllegalStateException("V3 requires whole-unit causal admission");
        }

        @Override
        public void send(OutboundUnit unit) {
            try {
                submitUnit(unit);
            } catch (Throwable t) {
                // Also covers hold/drain callers: unsupported coverage always ends the core owner.
                fail("causal outbound admission: " + describe(t), t);
                throw t instanceof RuntimeException ? (RuntimeException) t : new IllegalStateException(t);
            }
        }
    }

    private CausalSendContext causalContext() {
        CausalSendContext current = causal;
        if (current == null || CausalSendContext.attached(channel) != current) {
            throw new IllegalStateException("missing or replaced /3 causal context");
        }
        return current;
    }

    /** Frames the entire unit before any core operation, including the last split member. */
    private List<OutboundPacket> frameUnit(OutboundUnit unit, PacketLaneCodec framing) throws java.io.IOException {
        unit.requireSupportedCoverage();
        if (unit.envelope() == null || unit.state() == null) {
            throw new IllegalStateException("missing outbound envelope or protocol state");
        }
        List<OutboundPacket> frames = new ArrayList<>();
        for (byte[] packet : unit.packets()) {
            if (FrameCodec.packetId(packet) < 0) {
                throw new IllegalStateException("negative outbound packet id");
            }
            frames.add(new OutboundPacket(framing.outboundFrame(packet), packet.length, false));
        }
        return frames;
    }

    /** A bounded real consumer: one token/one packet, or validated empty-coverage ordinary units. */
    private void submitUnit(OutboundUnit unit) throws java.io.IOException {
        List<OutboundPacket> frames = frameUnit(unit, codec);
        CausalSendContext context = causalContext();
        MultistreamSession core = connection.session();
        context.admit(channel, connection, core, unit.envelope(), () -> {
            if (unit.hasCoverage()) {
                requireAssigned(core.sendCovered(unit.envelope().producer().tokens().get(0), unit.state(),
                        packetId(unit.packets().get(0)), frames.get(0).frame(), unit.packets().get(0)));
            } else if (unit.bundle() && frames.size() >= 2) {
                List<SentPacket> sent = core.sendBundle(frames.get(0), frames.subList(1, frames.size() - 1),
                        frames.get(frames.size() - 1));
                if (sent == null || sent.size() != frames.size()) {
                    throw new IllegalStateException("bundle admission was refused");
                }
                for (SentPacket handle : sent) {
                    requireAssigned(handle);
                }
            } else {
                for (int i = 0; i < frames.size(); i++) {
                    final int member = i;
                    // Reentrant invalidation during any member forbids all later members. The outer
                    // monitor also excludes separate-thread invalidation throughout this unit.
                    context.admit(channel, connection, core, unit.envelope(), () ->
                            requireAssigned(core.send(unit.state(), packetId(unit.packets().get(member)),
                                    frames.get(member).frame(), unit.packets().get(member))));
                }
            }
        });
    }

    private static int packetId(byte[] packet) {
        try {
            return FrameCodec.packetId(packet);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireAssigned(@Nullable SentPacket handle) {
        if (handle == null || !handle.isAssigned()) {
            throw new IllegalStateException("core outbound assignment was refused");
        }
    }

    /** Invalidate before queue discard or transport shutdown; never suppress individual tokens. */
    private void invalidateAdmission() {
        CausalSendContext current = causal;
        if (current == null) {
            current = CausalSendContext.attached(channel);
        }
        if (current != null) {
            current.teardown();
        }
    }

    /**
     * Submits one encoder-written GAME packet (id and body, uncompressed) to the session with its
     * uncompressed bytes, so a CHUNK route can extract its position (13.8). Without them the session
     * fails the route closed (0x11). Package-private for the unit test.
     */
    static void submitUncompressed(MultistreamSession session, PacketLaneCodec codec, ProtocolState state,
            byte[] packet) throws java.io.IOException {
        session.send(state, FrameCodec.packetId(packet), codec.outboundFrame(packet), packet);
    }

    /**
     * The 13.12 account key of one login: the authenticated profile UUID when the login was
     * authenticated, otherwise the player name (docs/protocol.md 13.12: offline accounts are
     * unauthenticated names, so the key is spoofable there by design of the spec). Null only when
     * the profile is absent or has no usable identity. Package-private for the unit test.
     */
    static String accountKey(GameProfile profile, boolean authenticated) {
        if (profile == null) {
            return null;
        }
        if (authenticated && profile.id() != null) {
            return profile.id().toString();
        }
        String name = profile.name();
        return name == null || name.isEmpty() ? null : name;
    }

    /** Gives every byte the join still retains back to the node's budget; safe to call more than once. */
    private void discardRetained() {
        BindingPayloads current = payloads;
        if (current != null) {
            OutboundQuiescence.discard(current.releaseQueued());
        }
        OutboundHold holding = outHold;
        if (holding != null) {
            holding.teardown();
        }
        AckBacklog pending = backlog;
        if (pending != null) {
            pending.teardown();
        }
    }

    /** The connection ended, whichever way: nothing of the join outlives it. */
    private void teardown() {
        clearBindingGate();
        invalidateAdmission();
        secrets.wipe();
        discardRetained();
        BindingSession current = session;
        if (current != null) {
            current.close();
        }
        ByteBuf leftover = remainder;
        remainder = null;
        if (leftover != null) {
            leftover.release();
        }
    }

    // --- Failure.

    /**
     * Ends the connection: {@code 0x10} before activation (the client retries over fresh TCP, 12.14),
     * an internal-error close after it. Idempotent.
     */
    void fail(String reason, @Nullable Throwable cause) {
        clearBindingGate();
        invalidateAdmission();
        int code = state.fail();
        if (code < 0) {
            return; // terminal: only the first failure closes
        }
        hold.fail();
        secrets.wipe();
        discardRetained();
        BindingSession current = session;
        if (current != null) {
            current.close();
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
            connection.abort(code);
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
     * Tail-most outbound handler. It notes the state each packet is encoded in, lets the game's own
     * pipeline encode it, and then takes what the encoder sink collected as one unit: held back while the
     * exchange runs (12.10), or handed to the lane layer whole after it. It ends the lane session when
     * the game closes the channel (13.14).
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
            if (!(msg instanceof Packet) || payloads == null) {
                // The game's own pipeline-configuration messages (Connection.setupInboundProtocol and
                // setupOutboundProtocol) are for its handlers, not for a record.
                ctx.write(msg, promise);
                return;
            }
            if (state.failed()) {
                promise.tryFailure(new java.nio.channels.ClosedChannelException());
                return;
            }
            OutboundUnit unit = new OutboundUnit(msg instanceof BundlePacket,
                    CausalSendContext.currentEnvelope(ctx.channel()));
            OutboundUnit outer = unitInFlight;
            try {
                if (unit.envelope() == null) {
                    throw new IllegalStateException("filtered GAME write has no causal envelope");
                }
                ProtocolState encoding = encoderState(ctx.pipeline());
                if (encoding != null) {
                    // The encoder swaps itself out when it writes a terminal packet, so this is where
                    // the state a packet is sent in can still be read (bundles and splits share it).
                    outboundState = encoding;
                }
                unitInFlight = unit;
                try {
                    // The whole pipeline up to the encoder runs as on TCP (unbundler, Fabric API's
                    // splitter...); the sink collects what the encoder wrote into this unit.
                    ctx.write(msg, promise);
                } finally {
                    unitInFlight = outer;
                }
                if (promise.isDone() && !promise.isSuccess()) {
                    if (unit.hasCoverage()) {
                        throw new IllegalStateException("encoder refused a covered write", promise.cause());
                    }
                    return; // the encoder refused it: nothing partial goes anywhere
                }
                finish(unit);
            } catch (Throwable t) {
                promise.tryFailure(t);
                fail("binding outbound filter: " + describe(t), t);
            }
        }

        private void finish(OutboundUnit unit) {
            unit.requireSupportedCoverage();
            if (unit.isEmpty()) {
                return;
            }
            if (!active) {
                switch (OutboundQuiescence.offer(payloads.driver(), unit, node)) {
                    case QUEUED:
                        return;
                    case DROPPED_FAILED:
                        throw new IllegalStateException("binding quiescence queue overflow");
                    default:
                        replay(unit);
                        return;
                }
            }
            outHold.submit(unit);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            invalidateAdmission();
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

    /** The exchange does not hold this unit back: send it down the byte path exactly as TCP would. */
    private void replay(OutboundUnit unit) {
        try {
            // Validate all framing before the first byte-path write, without consuming coverage.
            frameUnit(unit, new PacketLaneCodec(channel.pipeline()));
            CausalSendContext context = causalContext();
            ChannelHandlerContext ctx = sinkContext;
            context.replay(channel, connection, unit.envelope(), () -> {
                for (byte[] packet : unit.packets()) {
                    context.replay(channel, connection, unit.envelope(), () ->
                            ctx.write(io.netty.buffer.Unpooled.wrappedBuffer(packet)));
                }
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Sits behind the game's encoder, where its output (packet id and payload) would go on to the
     * compression, length-prefix and cipher handlers. During a write that went through the filter it
     * only collects that output into the write's unit; the filter then holds the unit back (binding
     * runs) or submits it to the lane layer as GAME records (13.4), whole. Output nobody asked for
     * through the filter (the binding payloads themselves) goes straight on before activation.
     */
    private final class EncoderSink extends ChannelOutboundHandlerAdapter {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            sinkContext = ctx;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof ByteBuf)) {
                ctx.write(msg, promise);
                return;
            }
            OutboundUnit unit = unitInFlight;
            if (unit == null && !sinkActive) {
                ctx.write(msg, promise); // binding payloads and early packets: the byte path as usual
                return;
            }
            ByteBuf buffer = (ByteBuf) msg;
            try {
                if (state.failed()) {
                    throw new java.nio.channels.ClosedChannelException();
                }
                ProtocolState encoding = outboundState;
                if (encoding == null) {
                    throw new IllegalStateException("no outbound state for an encoded packet");
                }
                byte[] packet = ByteBufUtil.getBytes(buffer);
                if (unit != null) {
                    unit.add(encoding, packet);
                } else {
                    throw new IllegalStateException("active encoder output has no causal outbound unit");
                }
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
     * After the game's packet handler: acknowledges each delivery (13.10) once the game accepted it
     * (see {@link AckBacklog}: the work accepted but not yet run is bounded and accounted). For a
     * protocol-changing packet it waits until the game has applied the change, because the next
     * records are decoded with the new protocol.
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

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            // The lane layer can take more again: release what the write hold kept back (13.12).
            try {
                if (outHold != null && !state.failed()) {
                    outHold.drain();
                }
            } catch (Throwable t) {
                fail("binding write hold: " + describe(t), t);
            }
            ctx.fireChannelWritabilityChanged();
        }

        private void acknowledge(ChannelHandlerContext ctx, GameLanes.Ack ack) {
            try {
                if (!ctx.channel().isOpen() || lanes.isClosed() || state.failed()) {
                    return;
                }
                if (PacketLaneCodec.inboundSwapPending(ctx.pipeline())) {
                    // The protocol change the packet asked for is not applied yet; the next record would
                    // be decoded with the old one.
                    ctx.executor().schedule(() -> acknowledge(ctx, ack), 1, TimeUnit.MILLISECONDS);
                    return;
                }
                Long size = decodedSizes.poll();
                backlog.delivered(ack.lane(), ack.sequence(), 1, size == null ? 0 : size.longValue());
                scheduleRetry(ctx);
            } catch (Throwable t) {
                fail("binding ack: " + describe(t), t);
            }
        }

        /** While a not-accepting listener holds acknowledgements back, try its queue again shortly. */
        private void scheduleRetry(ChannelHandlerContext ctx) {
            if (retryScheduled || !backlog.hasBlocked()) {
                return;
            }
            retryScheduled = true;
            ctx.executor().schedule(() -> {
                retryScheduled = false;
                try {
                    if (ctx.channel().isOpen() && !state.failed()) {
                        backlog.retry();
                        scheduleRetry(ctx);
                    }
                } catch (Throwable t) {
                    fail("binding ack retry: " + describe(t), t);
                }
            }, 1, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The game's serial queue of packet handlers: a marker queued here runs on the main thread right
     * after the handlers queued before it. False while the listener does not accept messages.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean offerBehindHandlers(Runnable task) {
        Connection game = channel.pipeline().get(Connection.class);
        net.minecraft.network.PacketListener listener = game == null ? null : game.getPacketListener();
        if (listener == null) {
            return false;
        }
        LaneAckPacket marker = new LaneAckPacket(task);
        if (!listener.shouldHandleMessage(marker)) {
            return false;
        }
        server.packetProcessor().scheduleIfPossible(listener, marker);
        return true;
    }
}
