// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import javax.crypto.Cipher;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.VarInt;
import rs.sudoe.quicraft.fabric.client.mixin.PacketEncoderAccessor;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.bridge.BindingPayloads;
import rs.sudoe.quicraft.bridge.CipherCutover;
import rs.sudoe.quicraft.bridge.GameLanes;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.binding.BindingDriver;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.connect.TransportSelection;
import rs.sudoe.quicraft.core.connect.V3Plan;
import rs.sudoe.quicraft.core.multistream.LaneSession;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.core.transport.V3ConnectionImpl;
import rs.sudoe.quicraft.fabric.QuicraftFabric;
import rs.sudoe.quicraft.fabric.net.BindingPayload;

/**
 * One {@code quicraft/3} join on the client (docs/protocol.md 12 and 13): from the QUIC handshake
 * through Minecraft's login, the channel-binding exchange in configuration, the cipher cutover and
 * the switch to three lanes. It is the platform side of core's {@link BindingSession}: the session
 * decides what is valid and when, this class does what the game needs for it (write the payload,
 * swap the cipher handlers, take stream 0 away from the bridge channel, start the lanes).
 *
 * <p><b>Fail safe.</b> Any failure before the lanes are active ends in {@link #fail}: the QUIC
 * connection closes with {@code 0x10}, {@link V3Plan#failed} records it, and (unless the player
 * chose {@code quic-only}) the same join starts again over fresh TCP with a line in the log and in
 * the chat. After activation a failure disconnects and the next join uses TCP.</p>
 *
 * <p>Threading: everything runs on the connection's event loop (the game's, for the hosted
 * transport) except the login hooks that vanilla runs elsewhere (volatile fields) and
 * {@link #acknowledge}, which hops to the render thread and back.</p>
 */
final class V3Client implements BindingDriver.Callbacks, BindingPayloads.PayloadSender {
    /** Where the join is. {@code LOGIN} also covers the QUIC handshake having just finished. */
    enum State {
        LOGIN, BINDING, ACTIVATING, ACTIVE, FAILED
    }

    /** Estimate used by the binding quiescence queue; its bound is independent of the lane queue. */
    private static final int BINDING_HELD_ESTIMATE_BYTES = 2048;
    /** 12.16: the bounded wait for OFFER on a changed-pin re-bind is twice the RTT, within these. */
    static final long REBIND_WAIT_MIN_MILLIS = 500;
    static final long REBIND_WAIT_MAX_MILLIS = 3000;
    /** Vanilla's read timeout (30 s), which a connection with lanes has to keep itself; tests shorten it. */
    static volatile long readTimeoutMillis = 30_000L;
    /** {@code -Dquicraft.multistream.offlineTest=true}: lets an unencrypted login reach the exchange (12.19). */
    static final String OFFLINE_TEST_PROPERTY = "quicraft.multistream.offlineTest";

    private final Connection connection;
    private final QuicBridgeChannel channel;
    private final V3Connection v3;
    private final V3Plan plan;
    private final RoutingTable table;
    private final int protocolNumber;
    private final boolean offlineTest;
    private final boolean quicOnly;
    private final EventLoop loop;
    private final ClientV3State files;
    private final String logicalHost;
    private final int logicalPort;
    private final CompletableFuture<String> oldFingerprint;
    private final LaneCodec codec;

    private volatile State state = State.LOGIN;
    private volatile @Nullable String failureText;

    // Login capture (set by the login hooks).
    /** {@link #onSecret} and {@link #wipeSecret} are one critical section each: see there. */
    private final Object secretLock = new Object();
    private volatile byte[] secret;
    private volatile boolean secretClosed;
    private volatile boolean authenticated;
    private volatile boolean encrypted;
    private volatile boolean onlineMode;
    private volatile boolean serverDisconnected;
    private boolean closingByGame;
    private boolean ended;
    /**
     * The exchange is over for good and cannot come back: the session ended (its self-tick stopped
     * and its queue released), its key material wiped, and everything still held here failed with
     * the connection. Set by {@link #cleanUp} on every way the join ends, so a late activation, a
     * deadline that fires afterwards, or a TCP retry nobody asked for is suppressed.
     */
    private boolean exchangeEnded;

    // Binding (event loop).
    private BindingSession session;
    private BindingPayloads payloads;
    private CipherCutover cutover;
    private ChannelFuture lastBindingWrite;
    private ByteBuffer coreLeftover;
    private final List<Held> deferred = new ArrayList<>();
    private GameLanes lanes;
    private V3Handler handler;
    /** Fully encoded/compressed writes the lanes cannot take yet, in original outbound order. */
    private final java.util.ArrayDeque<QueuedFrame> backlog = new java.util.ArrayDeque<>();
    private long backlogBytes;
    /** Protocol for the packet currently traversing the encoder; captured before terminal swaps. */
    private ProtocolState sendState = ProtocolState.CONFIGURATION;
    private LaneSend laneSend;
    /** Counts actual encoder output frames that reached the sink; exposed for the pipeline harness. */
    private int encodedFramesObserved;
    /** Counts final frame/compression passes performed for those encoder outputs. */
    private int framedFramesObserved;
    /**
     * What may be accepted but not yet run on the main thread before acknowledgements are held back.
     * Provisional: the 13.17 benchmark sets the real numbers.
     */
    static final int MAX_UNRUN_PACKETS = 256;
    static final long MAX_UNRUN_BYTES = 1L << 20;
    private final Object accounting = new Object();
    private long unrunPackets;
    private long unrunBytes;
    private GameLanes.Ack heldAck;
    /** Event loop only: packets fired since the last acknowledgement. */
    private long deliveredPackets;
    private final java.util.concurrent.atomic.AtomicLong decodedBytes = new java.util.concurrent.atomic.AtomicLong();
    private volatile java.util.function.Consumer<Runnable> mainThread = this::onGamePacketQueue;
    private volatile AckSink ackSink = (lane, sequence) -> lanes.applied(lane, sequence);

    V3Client(Connection connection, QuicBridgeChannel channel, EventLoop loop, V3Connection v3, V3Plan plan,
            RoutingTable table, int protocolNumber, boolean quicOnly, ClientV3State files, String logicalHost, int logicalPort,
            CompletableFuture<String> oldFingerprint) {
        this.connection = connection;
        this.channel = channel;
        this.v3 = v3;
        this.plan = plan;
        this.table = table;
        this.protocolNumber = protocolNumber;
        this.quicOnly = quicOnly;
        this.files = files;
        this.logicalHost = logicalHost;
        this.logicalPort = logicalPort;
        this.oldFingerprint = oldFingerprint;
        this.offlineTest = Boolean.getBoolean(OFFLINE_TEST_PROPERTY);
        this.loop = loop; // the channel is not registered yet when this is built
        this.codec = new LaneCodec(channel, table, decodedBytes::addAndGet);
    }

    /** Where an encoded packet goes: the lane session in production, a recorder in tests. */
    interface LaneSend {
        void send(ProtocolState state, int packetId, byte[] frame);
    }

    /** Tests: replaces the lane session as the destination of encoded packets. */
    void useLaneSend(LaneSend send) {
        this.laneSend = send;
    }

    int encodedFramesObserved() {
        return encodedFramesObserved;
    }

    int framedFramesObserved() {
        return framedFramesObserved;
    }

    GameLanes lanes() {
        return lanes;
    }

    /** The pipeline handler of this join, set by its constructor. */
    void attach(V3Handler handler) {
        this.handler = handler;
    }

    State state() {
        return state;
    }

    /** The text for the disconnect screen when {@code quic-only} refused to fall back, else null. */
    @Nullable String failureText() {
        return failureText;
    }

    /** Tests: the retained secret itself (not a copy), to check that it was wiped. */
    byte[] retainedSecret() {
        synchronized (secretLock) {
            return secret;
        }
    }

    V3Plan plan() {
        return plan;
    }

    // --- Login hooks (vanilla's handshake listener).

    /** Hello arrived. False: cancel vanilla's handling, the join is over (12.7). */
    boolean onHello(boolean shouldAuthenticate) {
        if (!shouldAuthenticate) {
            fail("login is not authenticated");
            return false;
        }
        return true;
    }

    /**
     * The 16-byte shared secret S that vanilla generated (12.4). One critical section with
     * {@link #wipeSecret}: a capture either happens before the wipe (and the wipe then owns its
     * copy) or is refused and wipes its own, so no capture can slip past a close and leave a live
     * copy of S behind (12.6).
     */
    void onSecret(byte[] shared) {
        byte[] stored = shared == null ? null : shared.clone();
        byte[] replaced;
        synchronized (secretLock) {
            if (secretClosed) {
                wipe(stored);
                return;
            }
            replaced = secret;
            secret = stored;
        }
        wipe(replaced);
    }

    /** Wipes the retained secret and refuses any later capture (12.6), atomically with capture. */
    private void wipeSecret() {
        byte[] retained;
        synchronized (secretLock) {
            secretClosed = true;
            retained = secret;
            secret = null;
        }
        wipe(retained);
    }

    /** The session-server join result: true only when the join was authenticated (12.7). */
    void onAuthenticated(boolean ok) {
        authenticated = ok;
    }

    /** Vanilla just installed its cipher handlers: swap the decryptor for the boundary-aware one (12.13). */
    void onEncryptionStarted(Cipher decrypt) {
        encrypted = true;
        if (authenticated && secret != null) {
            cutover = CipherCutover.install(channel.pipeline(), "decrypt", "encrypt", decrypt);
        }
    }

    /** Login Finished arrived. False: cancel vanilla's handling, the join is over (12.7). */
    boolean onLoginFinished() {
        if (encrypted) {
            if (!authenticated || secret == null || cutover == null) {
                fail("login is encrypted but not authenticated");
                return false;
            }
            onlineMode = true;
            return true;
        }
        if (!offlineTest) {
            fail("login is not authenticated");
            return false;
        }
        if (plan.pinState() == TransportSelection.PinState.CHANGED) {
            // 12.16: a changed pin is only re-bound by an authenticated login, whatever the test flag says.
            fail(V3Plan.REBIND_FAILED);
            return false;
        }
        onlineMode = false; // 12.19: explicit offline test profile, mode 0
        return true;
    }

    /** Vanilla switched the connection to configuration: the exchange starts on the event loop (12.4). */
    void onConfigurationStarted() {
        if (loop.inEventLoop()) {
            enterConfiguration();
        } else {
            loop.execute(this::enterConfiguration);
        }
    }

    private void enterConfiguration() {
        try {
            if (state != State.LOGIN || exchangeEnded) {
                return; // the join was over before the game reached configuration
            }
            state = State.BINDING;
            long offerWaitNanos = 0;
            if (plan.pinState() == TransportSelection.PinState.CHANGED) {
                long rtt = v3.smoothedRttMillis();
                long millis = rtt > 0 ? Math.max(REBIND_WAIT_MIN_MILLIS, Math.min(REBIND_WAIT_MAX_MILLIS, 2 * rtt))
                        : REBIND_WAIT_MAX_MILLIS;
                offerWaitNanos = millis * 1_000_000L;
            }
            byte[] shared = onlineMode ? secret : null;
            session = new BindingSession(BindingStateMachine.Role.CLIENT, v3::exporterValue, shared,
                    onlineMode ? BindingStateMachine.MODE_ONLINE : BindingStateMachine.MODE_OFFLINE, protocolNumber,
                    plan.fingerprintBytes(), plan.routingDigest(), System::nanoTime, offerWaitNanos, this, loop,
                    loop::inEventLoop);
            wipeSecret();
            payloads = new BindingPayloads(session, this, this);
            payloads.onConfigurationEntered(System.nanoTime());
        } catch (Throwable t) {
            fail(t);
        }
    }

    // --- Pipeline hooks (V3Handler).

    void onBindingPayload(byte[] data) {
        if (state != State.BINDING || payloads == null) {
            // 12.9: a binding message outside the exchange terminates /3.
            fail("unexpected channel binding message");
            return;
        }
        payloads.onPayload(data);
    }

    /** A packet the game wants to write. True: held, the handler must not pass it on. */
    boolean hold(Object packet, ChannelPromise promise) {
        switch (state) {
            case BINDING: {
                BindingDriver.QueueResult result = payloads.queueOrdinary(new Held(packet, promise),
                        BINDING_HELD_ESTIMATE_BYTES);
                if (result == BindingDriver.QueueResult.DROPPED_FAILED) {
                    promise.tryFailure(new ClosedChannelException());
                    return true;
                }
                return result == BindingDriver.QueueResult.QUEUED;
            }
            case ACTIVATING:
                deferred.add(new Held(packet, promise));
                return true;
            case FAILED:
                promise.tryFailure(new ClosedChannelException());
                return true;
            default:
                return false;
        }
    }

    boolean serverHasDisconnected() {
        return serverDisconnected;
    }

    // --- Outbound after activation.

    /** The protocol the next packet is encoded in, read before the encoder can swap itself out. */
    void stampSendState() {
        ChannelHandlerContext encoder = channel.pipeline().context("encoder");
        if (encoder != null && encoder.handler() instanceof PacketEncoder) {
            sendState = LaneCodec.stateOf(
                    ((PacketEncoderAccessor) encoder.handler()).quicraft$protocolInfo().id());
        }
    }

    /** The lanes' writability changed: on recovery the encoded frames go on, in order. */
    void writabilityChanged() {
        GameLanes current = lanes;
        if (current == null || state != State.ACTIVE) {
            return;
        }
        // Re-checked per frame: a send can fail the client, and nothing may go out after that.
        while (state == State.ACTIVE && current.isWritable() && !backlog.isEmpty()) {
            QueuedFrame next = backlog.poll();
            backlogBytes -= next.frame.length;
            sendFrame(next);
        }
    }

    /** Encoded by the game's encoder: sent on the lane the routing table names (13.6). */
    void sendEncoded(ByteBuf body, ChannelPromise promise) {
        try {
            if (state != State.ACTIVE) {
                promise.tryFailure(new ClosedChannelException());
                return;
            }
            encodedFramesObserved++;
            int packetId = VarInt.read(body.duplicate());
            byte[] frame = LaneCodec.frame(channel, body);
            framedFramesObserved++;
            GameLanes current = lanes;
            if (!backlog.isEmpty() || current == null || !current.isWritable()) {
                if (backlog.size() >= LaneSession.MAX_UNSENT_PACKETS
                        || backlogBytes + frame.length > LaneSession.MAX_UNSENT_BYTES) {
                    promise.tryFailure(new ClosedChannelException());
                    failActive("send queue overflow", LaneSession.CLOSE_ORDERING);
                    return;
                }
                backlog.add(new QueuedFrame(sendState, packetId, frame, promise));
                backlogBytes += frame.length;
            } else {
                sendFrame(new QueuedFrame(sendState, packetId, frame, promise));
            }
        } catch (Throwable t) {
            promise.tryFailure(t);
            fail(t);
        } finally {
            body.release();
        }
    }

    /** Transfers one fully encoded GAME frame to the lane session. */
    private void sendFrame(QueuedFrame queued) {
        try {
            laneSend.send(queued.state, queued.packetId, queued.frame);
            queued.promise.trySuccess();
        } catch (Throwable t) {
            queued.promise.tryFailure(t);
            failActive("lane send failed", LaneSession.CLOSE_INTERNAL_ERROR);
        }
    }

    void serverDisconnects() {
        serverDisconnected = true;
    }

    /** The bridge channel went inactive. */
    void channelClosed() {
        wipeSecret();
        // The channel is gone, so the exchange is over whatever the state: the session closes here
        // too, and can no longer tick itself into a failure and a TCP join (12.6, 12.14).
        cleanUp();
        if (state == State.ACTIVE || state == State.FAILED || serverDisconnected || closingByGame) {
            return;
        }
        fail("connection closed before multi-stream was active");
    }

    /**
     * The game closes the channel (the player cancels the join, leaves, or the game disconnects).
     * The attempt is over: the session ends with it (the copy of {@code S} and the key material it
     * derived, its quiescence queue, its self-tick), so nothing of the exchange survives the
     * connection and no later deadline can start a TCP join the player did not ask for.
     */
    void closing() {
        wipeSecret();
        closingByGame = true;
        cleanUp();
        if (state == State.ACTIVE && !ended) {
            ended = true;
            // 13.14: after activation the session ends stream 0, never a bare FIN.
            v3.end();
        }
    }

    void laneClosed(GameLanes.LaneClosedException e) {
        if (state == State.ACTIVE) {
            state = State.FAILED;
            failureText = "QUIC multi-stream failed (" + e.getMessage() + ")";
            Transports.v3Failure(connection, failureText);
            QuicraftFabric.LOG.warn("QUICraft: {}; the next join uses TCP", failureText);
            plan.failed(V3Plan.Phase.PLAY, e.getMessage());
        }
    }

    /**
     * 13.10: the game's serial handler has ACCEPTED a delivery, which is when this runs: the packets
     * were fired through the pipeline and {@code Connection.channelRead0} returned for each (handled
     * inline, or queued first-in-first-out for the main thread). Acknowledging now, not when the main
     * thread has run them, is what lets the lane scheduler (one outstanding delivery at a time) go on
     * at network speed instead of at the frame rate.
     *
     * <p>What is accepted but not yet run is counted ({@link #unrunPackets}, {@link #unrunBytes}); a
     * marker queued behind the packets on the game's own packet queue ({@link AckPacket}) takes them
     * off the count when it runs. Only while the count is over the bound is the acknowledgement held
     * back, until the main thread has caught up: that is the backpressure on a game that cannot keep up.</p>
     */
    void acknowledge(GameLanes.Ack ack) {
        final long packets = deliveredPackets;
        deliveredPackets = 0;
        final long bytes = decodedBytes.getAndSet(0);
        final V3Handler watcher = handler;
        boolean hold;
        synchronized (accounting) {
            unrunPackets += packets;
            unrunBytes += bytes;
            hold = overBound();
            if (hold) {
                heldAck = ack;
            }
        }
        if (watcher != null) {
            watcher.deliveryQueued();
        }
        try {
            mainThread.accept(() -> {
                GameLanes.Ack release = null;
                synchronized (accounting) {
                    unrunPackets -= packets;
                    unrunBytes -= bytes;
                    if (heldAck != null && !overBound()) {
                        release = heldAck;
                        heldAck = null;
                    }
                }
                if (watcher != null) {
                    watcher.deliveryApplied();
                }
                if (release != null) {
                    applyAck(release);
                }
            });
        } catch (RejectedExecutionException closing) {
            return; // the game is shutting down; nothing is left to acknowledge
        }
        if (!hold) {
            applyAck(ack);
        }
    }

    /** Production: behind the packets already queued for the main thread. */
    private void onGamePacketQueue(Runnable marker) {
        Minecraft minecraft = Minecraft.getInstance();
        PacketListener listener = connection.getPacketListener();
        if (listener == null) {
            minecraft.execute(marker);
        } else {
            minecraft.packetProcessor().scheduleIfPossible(listener, new AckPacket(marker));
        }
    }

    private boolean overBound() {
        return unrunPackets > MAX_UNRUN_PACKETS || unrunBytes > MAX_UNRUN_BYTES;
    }

    private void applyAck(GameLanes.Ack ack) {
        try {
            GameLanes current = lanes;
            if (current != null && !current.isClosed()) {
                ackSink.accept(ack.lane(), ack.sequence());
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** A decoded game packet is being fired into the game (counted for the next acknowledgement). */
    void packetDelivered() {
        deliveredPackets++;
    }

    /** Tests: where acknowledgements run, and how the game's main thread is asked to run a marker. */
    void useAcknowledgement(java.util.function.Consumer<Runnable> mainThread, AckSink sink) {
        this.mainThread = mainThread;
        this.ackSink = sink;
    }

    /** Tests: bytes the codec decoded since the last acknowledgement. */
    void noteDecodedBytes(long bytes) {
        decodedBytes.addAndGet(bytes);
    }

    /** What an acknowledgement does: tell the lane session the game applied up to a sequence. */
    interface AckSink {
        void accept(Lane lane, long sequence);
    }

    // --- BindingDriver.Callbacks and PayloadSender.

    @Override
    public void send(byte[] payload) {
        sendBindingPayload(payload);
    }

    @Override
    public void sendBindingPayload(byte[] payload) {
        ChannelHandlerContext ctx = channel.pipeline().context(V3Handler.NAME);
        // Written from the handler's own context, below the quiescence queue (12.10).
        lastBindingWrite = ctx.writeAndFlush(new ServerboundCustomPayloadPacket(new BindingPayload(payload)));
    }

    @Override
    public void activateOutbound() {
        // 12.11: CLIENT_SWITCH was the last encrypted packet; the encrypt handler goes once it is out.
        if (cutover != null) {
            cutover.outboundCutover(lastBindingWrite);
        }
    }

    @Override
    public void activateInbound() {
        // 12.12/12.13: the whole cutover, in this one task, inside the read that carried SERVER_SWITCH.
        ByteBuf remainder = null;
        try {
            remainder = cutover != null ? cutover.stopAndTakeRemainder() : takeSplitterBytes();
            v3.detachForActivation(buffer -> {
                ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());
                copy.put(buffer).flip();
                coreLeftover = copy;
            });
            CompletableFuture<ByteBuf> handedOver = channel.handOver(remainder);
            remainder.release();
            remainder = null;
            state = State.ACTIVATING;
            // After this task, when the session has settled as activated.
            handedOver.whenCompleteAsync((control, error) -> afterHandOver(control, error), loop);
        } catch (Throwable t) {
            if (remainder != null) {
                remainder.release();
            }
            // The session turns anything thrown here into 0x10 (BindingDriver.Callbacks).
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new IllegalStateException(t);
        }
    }

    /** Offline test profile only: the undecoded bytes the frame splitter holds after the switch frame. */
    private ByteBuf takeSplitterBytes() throws ReflectiveOperationException {
        ChannelHandlerContext splitter = channel.pipeline().context("splitter");
        if (splitter == null || !(splitter.handler() instanceof ByteToMessageDecoder)) {
            return Unpooled.EMPTY_BUFFER.retainedSlice();
        }
        Method internal = ByteToMessageDecoder.class.getDeclaredMethod("internalBuffer");
        internal.setAccessible(true);
        ByteBuf held = (ByteBuf) internal.invoke(splitter.handler());
        ByteBuf copy = channel.alloc().buffer(held.readableBytes());
        copy.writeBytes(held, held.readerIndex(), held.readableBytes());
        held.skipBytes(held.readableBytes()); // nothing for the splitter to forward when it is removed
        return copy;
    }

    @Override
    public void fail(int code, String reason) {
        QuicraftFabric.LOG.info("QUICraft: the multi-stream exchange failed ({})", reason);
        fail(V3Plan.BINDING_FAILED);
    }

    // --- Activation.

    private void afterHandOver(ByteBuf control, Throwable error) {
        if (error != null) {
            fail("could not take over the connection");
            return;
        }
        if (exchangeEnded) {
            // The game closed the channel while the hand-over was in flight: no lanes, no pin.
            control.release();
            return;
        }
        ByteBuffer leftover;
        try {
            leftover = join(control, coreLeftover);
        } finally {
            control.release();
        }
        coreLeftover = null;
        if (state == State.FAILED || exchangeEnded) {
            return; // the channel was closed in the middle of the hand-over: no pin, no lanes
        }
        CompletableFuture<Boolean> pinned;
        try {
            pinned = plan.activated(session);
        } catch (Throwable t) {
            fail(t);
            return;
        }
        pinned.whenCompleteAsync((ok, pinError) -> {
            if (pinError != null || !Boolean.TRUE.equals(ok)) {
                fail(V3Plan.PIN_WRITE_FAILED);
                return;
            }
            try {
                startLanes(leftover);
            } catch (Throwable t) {
                fail(t);
            }
        }, loop);
    }

    private void startLanes(ByteBuffer leftover) {
        if (state == State.FAILED || exchangeEnded) {
            return; // the channel was closed before the pin write answered
        }
        ChannelPipeline pipeline = channel.pipeline();
        GameLanes started = new GameLanes(channel, codec, v3::session, ProtocolState.CONFIGURATION,
                DeliveryContext.of(pipeline));
        lanes = started;
        laneSend = (state, id, frame) -> v3.session().send(state, id, frame);
        // Outbound: the game's own encoder runs (with everything in front of it); its output is taken
        // right behind it and sent as a record. Held writes wait at the tail while the lanes are full.
        String encoderName = pipeline.get("encoder") != null ? "encoder" : "outbound_config";
        pipeline.addBefore(encoderName, V3Handler.SINK_NAME, new V3Handler.Sink(this));
        pipeline.addLast(V3Handler.TAIL_NAME, new V3Handler.Tail(this));
        // The byte path's read timeout (vanilla's "timeout" handler) can no longer see reads.
        if (pipeline.get("timeout") != null) {
            pipeline.remove("timeout");
        }
        handler.startSilenceWatch(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis));
        v3.activate(started, leftover);
        state = State.ACTIVE;
        QuicraftFabric.LOG.info("QUICraft: multi-stream active to {}", v3.remoteAddress());
        // The packets the exchange held, in order, now as records.
        List<Object> held = new ArrayList<>(payloads.releaseQueued());
        held.addAll(deferred);
        deferred.clear();
        for (Object o : held) {
            Held packet = (Held) o;
            pipeline.write(packet.packet, packet.promise);
        }
        if (!held.isEmpty()) {
            pipeline.flush();
        }
        announceRebind();
    }

    /** 12.16: tell the player once that a changed certificate was checked and pinned. */
    private void announceRebind() {
        if (plan.pinState() != TransportSelection.PinState.CHANGED || !onlineMode) {
            return;
        }
        final String fresh = plan.fingerprint().toString();
        files.worker().execute(() -> {
            try {
                if (files.rebinds().shouldNotify(logicalHost, logicalPort, fresh)) {
                    String old = oldFingerprint.getNow("an earlier certificate");
                    V3Notices.rebound(shortFingerprint(old), shortFingerprint(fresh));
                }
            } catch (Throwable t) {
                QuicraftFabric.LOG.debug("QUICraft: re-bind notice skipped: {}", t.toString());
            }
        });
    }

    static String shortFingerprint(String fingerprint) {
        int colon = fingerprint.indexOf(':');
        String hex = colon >= 0 ? fingerprint.substring(colon + 1) : fingerprint;
        return hex.length() > 16 ? hex.substring(0, 16) + "..." : hex;
    }

    private static @Nullable ByteBuffer join(ByteBuf control, @Nullable ByteBuffer core) {
        int size = control.readableBytes() + (core == null ? 0 : core.remaining());
        if (size == 0) {
            return null;
        }
        ByteBuffer joined = ByteBuffer.allocate(size);
        joined.put(ByteBufUtil.getBytes(control));
        if (core != null) {
            joined.put(core);
        }
        joined.flip();
        return joined;
    }

    // --- Failure.

    void fail(Throwable cause) {
        QuicraftFabric.LOG.warn("QUICraft: multi-stream hook failed", cause);
        fail(V3Plan.INTERNAL_ERROR);
    }

    /**
     * The {@code /3} attempt failed before the lanes were active (12.14): close QUIC with {@code 0x10},
     * record it, and start the same join over TCP unless the player asked for {@code quic-only}.
     * The first failure wins.
     */
    void fail(String reason) {
        if (!loop.inEventLoop()) {
            loop.execute(() -> fail(reason));
            return;
        }
        if (state == State.ACTIVE) {
            failActive(reason);
            return;
        }
        if (state == State.FAILED) {
            return;
        }
        if (closingByGame || serverDisconnected) {
            // The join is already over: the player closed it, or the server ended the connection
            // itself. Neither is a /3 failure, so nothing is reported and no TCP join is started
            // (12.14); the session is gone either way.
            cleanUp();
            return;
        }
        state = State.FAILED;
        boolean retry;
        try {
            retry = plan.failed(V3Plan.Phase.JOINING, reason);
        } catch (Throwable t) {
            retry = !quicOnly;
        }
        if (retry) {
            String message = V3Notices.fellBack(reason);
            QuicraftFabric.LOG.warn("QUICraft: {}", message);
            Transports.retryOverTcp(connection);
        } else {
            failureText = "QUIC multi-stream failed (" + reason + ")";
            Transports.v3Failure(connection, failureText);
            QuicraftFabric.LOG.warn("QUICraft: {}; quic-only never falls back to TCP", failureText);
        }
        cleanUp();
        v3.abort(Protocol.CLOSE_BINDING_FAILURE);
    }

    /**
     * A failure after the lanes are active (docs/protocol.md 12.14): no retry, the join was already
     * in play. The plan records it, so the next join to this server goes straight to TCP; the player
     * gets a clear reason on the disconnect screen; the QUIC connection is closed.
     */
    void failActive(String reason) {
        failActive(reason, LaneSession.CLOSE_INTERNAL_ERROR);
    }

    void failActive(String reason, int code) {
        if (!loop.inEventLoop()) {
            loop.execute(() -> failActive(reason, code));
            return;
        }
        if (state != State.ACTIVE) {
            return;
        }
        state = State.FAILED;
        failureText = "QUIC multi-stream failed (" + reason + ")";
        Transports.v3Failure(connection, failureText);
        QuicraftFabric.LOG.warn("QUICraft: {}; the next join uses TCP", failureText);
        try {
            plan.failed(V3Plan.Phase.PLAY, reason);
        } catch (Throwable ignored) {
            // The bookkeeping fails closed; the connection still has to end.
        }
        cleanUp();
        v3.abort(code);
    }

    /** Nothing was delivered for the read timeout and the game is not busy (see {@link V3Handler}). */
    void silent() {
        failActive("timed out");
    }

    /**
     * The exchange is over, whichever way it ended: the session closes (its self-tick stops, its
     * quiescence queue is released along with its promises, and the tags its machine derived are
     * wiped), every write still waiting in this controller fails with the connection, and
     * {@link #exchangeEnded} makes sure nothing of the attempt runs after this. Idempotent, and
     * never throws.
     */
    private void cleanUp() {
        exchangeEnded = true;
        // The held packets first: closing the session drops its quiescence queue.
        List<Object> held = new ArrayList<>(deferred);
        deferred.clear();
        if (payloads != null) {
            held.addAll(payloads.releaseQueued());
        }
        try {
            if (session != null) {
                session.close();
            }
        } catch (Throwable ignored) {
            // The connection is closing anyway.
        }
        wipeSecret();
        for (Object o : held) {
            ((Held) o).promise.tryFailure(new ClosedChannelException());
        }
        QueuedFrame waiting;
        while ((waiting = backlog.poll()) != null) {
            waiting.promise.tryFailure(new ClosedChannelException());
        }
        backlogBytes = 0;
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** A packet the exchange keeps back until the lanes are active, with the promise to complete when it is written. */
    private static final class Held {
        final Object packet;
        final ChannelPromise promise;

        Held(Object packet, ChannelPromise promise) {
            this.packet = packet;
            this.promise = promise;
        }
    }

    /** The queue owns final framed bytes after the game encoder and compression have run once. */
    private static final class QueuedFrame {
        final ProtocolState state;
        final int packetId;
        final byte[] frame;
        final ChannelPromise promise;

        QueuedFrame(ProtocolState state, int packetId, byte[] frame, ChannelPromise promise) {
            this.state = state;
            this.packetId = packetId;
            this.frame = frame;
            this.promise = promise;
        }
    }

    /** For {@link ClientConnect}: the pin the join replaces, read on the worker before the exchange. */
    static CompletableFuture<String> oldPin(ClientV3State files, String host, int port) {
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            files.worker().execute(() -> {
                try {
                    result.complete(files.pins().pin(host, port, files.rebinds().activeKeys())
                            .map(rs.sudoe.quicraft.core.pin.PinStore.Pin::fingerprint).orElse(null));
                } catch (Throwable t) {
                    result.complete(null);
                }
            });
        } catch (RuntimeException e) {
            result.complete(null);
        }
        return result;
    }
}
