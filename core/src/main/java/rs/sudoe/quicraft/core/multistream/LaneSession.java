// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;

/**
 * The three QUIC streams of docs/protocol.md 13.3 for one connection, run over Netty QUIC and
 * connected to the record codec, {@link SenderSequencer} and {@link ReceiverScheduler}.
 *
 * <p>Lifecycle: construct when the connection is established, hand every peer-created stream
 * other than stream 0 to {@link #acceptStream} from the stream handler's {@code initChannel},
 * and call {@link #activate} at the activation point of 12.11/12.12. Before that the session
 * touches nothing on stream 0, which still belongs to the single-stream login code; after it, it
 * installs its handler first in stream 0's pipeline, removes the handlers that preceded it so the
 * old single-stream owner can no longer read from or drive stream 0 (13.14), and never forwards
 * events to older handlers.
 *
 * <p>Threading: the sequencer, the scheduler and every callback of {@link LaneGame} run on the
 * connection's event loop. {@link #send}, {@link #sendBundle}, {@link #end}, {@link #abort},
 * {@link #activate} and {@link #applied} may be called from any thread and are marshalled in
 * call order; {@link #acceptStream} must be called on the loop.
 *
 * <p>Receive: each stream's bytes are parsed by a {@link RecordCodec.Decoder}; reads use
 * {@code autoRead=false} and ask for more only while the scheduler has budget, so QUIC flow
 * control, not memory, absorbs a flood. A parser buffer is reserved through the scheduler at the
 * first byte of its record and grown to the full record size when the length is known. The
 * transport chunk waiting to be parsed (at most one {@link #READ_CHUNK} chunk per stream) is
 * charged to the node receive budget while it is held and released when it is consumed (13.12).
 * Every failure closes the whole connection, never one lane: 0x11 for protocol, ordering and
 * resource errors and a lost, reset or finished lane, 0x2 for local faults, 0x0 after END.
 *
 * <p>Node budget: when a {@link ReceiveBudget} is given, every retained byte is also reserved
 * against the node's aggregate budget (13.12). While the node budget cannot carry the next read,
 * this lane stops reading, exactly like the per-connection budget; when bytes already received can
 * never be charged, this connection closes with 0x11 and no other connection's data is evicted.
 * The send queues are charged the same way: a record is reserved when it is accepted and gives its
 * bytes back as they reach the stream, or when the connection drops them with {@code shutdown}, so
 * one connection holds its bytes in both directions against one shared budget.
 *
 * <p>Send: records are sequenced at submission, encoded and queued per lane. CONTROL, REALTIME
 * and WORLD each get one write quantum of at most {@link #WRITE_QUANTUM} bytes per round, in that
 * order, so CONTROL goes first and neither auxiliary lane can be starved by it. Writes stop on a
 * stream while it is not writable. The unsent queue is capped at {@link #MAX_UNSENT_PACKETS}
 * packets or {@link #MAX_UNSENT_BYTES} bytes; {@link LaneGame#onWritabilityChanged} turns false at
 * three quarters of either and true again at one quarter. Exceeding the cap disconnects (13.12);
 * END alone bypasses the cap, though the node budget still has to carry its record like any other.
 * A bundle must fit the cap as a whole, so the game checks
 * {@link #isWritable()} before submitting a large one.
 *
 * <p>Call {@link #activate} without delay: the peer's 10 second clock for opening its streams
 * starts at its own activation. A STOP_SENDING from the peer terminates the connection with 0x11
 * (13.14) and must be noticed on a lane that never writes, so while the session is active the
 * periodic tick reads each lane's send capacity. The pinned Netty fires no event for STOP_SENDING
 * and an empty write is a silent no-op in its stream channel, so the send capacity (a negative
 * quiche stream-stopped result) is the only API-visible state that changes without sending bytes;
 * see {@link #peerStoppedReading}.
 */
public final class LaneSession {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** Which end of the connection this session is. */
    public enum Role {
        CLIENT,
        SERVER
    }

    /** Normal close after the peer's END (13.14). */
    public static final int CLOSE_NORMAL = 0x0;
    /** Local fault, not the peer's doing. */
    public static final int CLOSE_INTERNAL_ERROR = 0x2;
    /** Protocol, ordering or resource failure (13.3, 13.12, 13.14). */
    public static final int CLOSE_ORDERING = MultistreamProtocolException.CLOSE_ORDERING;

    /** 13.13: the most one write quantum may carry. */
    public static final int WRITE_QUANTUM = 64 * 1024;
    /** 13.12: unsent application queue cap, packets. */
    public static final int MAX_UNSENT_PACKETS = 1024;
    /** 13.12: unsent application queue cap, bytes. */
    public static final long MAX_UNSENT_BYTES = 16L * 1024 * 1024;
    /** Backpressure engages at three quarters of the caps... */
    static final int PAUSE_PACKETS = MAX_UNSENT_PACKETS * 3 / 4;
    static final long PAUSE_BYTES = MAX_UNSENT_BYTES * 3 / 4;
    /** ...and releases at one quarter. */
    static final int RESUME_PACKETS = MAX_UNSENT_PACKETS / 4;
    static final long RESUME_BYTES = MAX_UNSENT_BYTES / 4;
    /** 13.14: the peer's streams must be open within this long after activation. */
    public static final long OPEN_TIMEOUT_NANOS = 10_000_000_000L;
    /** 13.14: how long the END sender waits for the peer to close. */
    public static final long END_LINGER_NANOS = 10_000_000_000L;
    /** The largest chunk one read delivers; bounds the unparsed bytes held per stream. */
    static final int READ_CHUNK = 16 * 1024;
    /** The smallest record on the wire: length prefix, kind, sequence and three dependencies. */
    private static final int MAX_RECORD_WIRE = 4 + RecordCodec.MAX_RECORD_LENGTH;
    private static final int CONTROL_STREAM_ID = 0;
    private static final int REALTIME_STREAM_ID = 4;
    private static final int WORLD_STREAM_ID = 1;

    /**
     * quiche's {@code QUICHE_ERR_STREAM_STOPPED}, read once from the pinned Netty's package-private
     * {@code Quiche} class (13.14). {@link Integer#MIN_VALUE} when it cannot be read, which disables
     * the idle STOP_SENDING probe and leaves detection to the lane's first write.
     */
    private static final int STOPPED_SENDING = quicheErrStreamStopped();

    private static int quicheErrStreamStopped() {
        try {
            Class<?> quiche = Class.forName(
                    QuicStreamChannel.class.getName().replace("QuicStreamChannel", "Quiche"));
            java.lang.reflect.Field field = quiche.getDeclaredField("QUICHE_ERR_STREAM_STOPPED");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "cannot read QUICHE_ERR_STREAM_STOPPED from the pinned Netty", t);
            return Integer.MIN_VALUE;
        }
    }

    /** Clocks and periods; tests shorten them, production uses {@link #DEFAULT}. */
    static final class Timing {
        static final Timing DEFAULT = new Timing(System::nanoTime, OPEN_TIMEOUT_NANOS, END_LINGER_NANOS,
                TimeUnit.SECONDS.toNanos(1));

        final LongSupplier clock;
        final long openTimeoutNanos;
        final long endLingerNanos;
        final long tickNanos;

        Timing(LongSupplier clock, long openTimeoutNanos, long endLingerNanos, long tickNanos) {
            this.clock = clock;
            this.openTimeoutNanos = openTimeoutNanos;
            this.endLingerNanos = endLingerNanos;
            this.tickNanos = tickNanos;
        }
    }

    private final QuicChannel connection;
    private final Role role;
    private final RoutingTable table;
    private final ReceiveBudget node;
    private final Timing timing;
    private final Direction sendDirection;
    private final Direction receiveDirection;
    private final io.netty.channel.EventLoop loop;
    private final LaneStream[] streams = new LaneStream[3];
    private final List<ArrayDeque<byte[]>> out = new ArrayList<ArrayDeque<byte[]>>();
    private final int[] outOffset = new int[3];
    private final boolean[] held = new boolean[3];
    private final SenderSequencer sender = new SenderSequencer();
    private final ReceiverScheduler receiver;
    private final QuicStreamChannel controlChannel;

    /** Attached at construction or later with {@link #bindGame}; null until then. */
    private LaneGame game;
    /** Set by the first {@link #bindGame} call, from any thread, before the game is attached. */
    private volatile boolean bound;
    private boolean activated;
    private long activatedAt;
    private boolean closed;
    /** A node-budget refusal withheld at least one lane: the tick retries and clears it. */
    private boolean withheldForNode;
    private boolean processing;
    private boolean reprocess;
    private boolean draining;
    private boolean endReceived;
    private boolean endQueued;
    private byte[] endRecord;
    private long endWrittenAt;
    private int nextAux;
    private int unsentPackets;
    private long unsentBytes;
    /** Bytes of the send queues that are currently reserved against {@link #node} (13.12). */
    private long queuedNodeBytes;
    private volatile boolean writable = true;
    private LoopTicker ticker;
    private java.lang.reflect.Method streamCapacityMethod;
    private boolean streamCapacityLookedUp;

    /** A session with production timing, no node budget and the game already attached. */
    public LaneSession(QuicChannel connection, Role role, QuicStreamChannel control, RoutingTable table,
                       LaneGame game) {
        this(connection, role, control, table, game, ReceiveBudget.unlimited(), Timing.DEFAULT);
    }

    /**
     * A session that reserves every retained byte against the node's aggregate receive budget
     * (13.12) and takes its game later with {@link #bindGame(LaneGame)}.
     *
     * <p>Until the game is bound nothing is decoded or delivered: stream bytes already accepted
     * stay buffered under the 13.12 limits, and binding resumes parsing on the connection's event
     * loop. The game may be bound before or after {@link #activate}.
     */
    public LaneSession(QuicChannel connection, Role role, QuicStreamChannel control, RoutingTable table,
                       LaneGame game, ReceiveBudget node) {
        this(connection, role, control, table, game, node, Timing.DEFAULT);
    }

    public LaneSession(QuicChannel connection, Role role, QuicStreamChannel control, RoutingTable table,
                       ReceiveBudget node) {
        this(connection, role, control, table, null, node, Timing.DEFAULT);
    }

    LaneSession(QuicChannel connection, Role role, QuicStreamChannel control, RoutingTable table,
                LaneGame game, ReceiveBudget node, Timing timing) {
        if (connection == null || role == null || control == null || table == null) {
            throw new IllegalArgumentException("null argument");
        }
        if (control.streamId() != CONTROL_STREAM_ID || control.type() != QuicStreamType.BIDIRECTIONAL) {
            throw new IllegalArgumentException("CONTROL must be bidirectional stream 0");
        }
        this.connection = connection;
        this.role = role;
        this.controlChannel = control;
        this.table = table;
        this.game = game;
        this.bound = game != null;
        this.node = node == null ? ReceiveBudget.unlimited() : node;
        this.timing = timing;
        this.loop = connection.eventLoop();
        this.sendDirection = role == Role.CLIENT ? Direction.SERVERBOUND : Direction.CLIENTBOUND;
        this.receiveDirection = role == Role.CLIENT ? Direction.CLIENTBOUND : Direction.SERVERBOUND;
        for (int i = 0; i < 3; i++) {
            out.add(new ArrayDeque<byte[]>());
        }
        this.receiver = new ReceiverScheduler(this::onDecode, this::onDeliver, this.node);
        connection.pipeline().addFirst(new ConnectionWatch());
    }

    // --- Public API.

    /**
     * Attaches the game side, at most once, before or after {@link #activate} (13.12). Bytes
     * accepted while no game is bound stay buffered and undecoded under the receive limits;
     * binding resumes parsing on the connection's event loop, in call order with the other calls
     * from any thread.
     */
    public void bindGame(final LaneGame game) {
        if (game == null) {
            throw new IllegalArgumentException("game");
        }
        if (bound) {
            throw new IllegalStateException("the game is already bound");
        }
        bound = true;
        run(() -> doBindGame(game));
    }

    private void doBindGame(LaneGame bound) {
        if (closed) {
            return;
        }
        this.game = bound;
        process();
    }

    /**
     * Activation (12.11/12.12): this side's switch is validated. Takes over stream 0, opens this
     * side's auxiliary stream (client stream 4, server stream 1) and starts the 10 second clock
     * for the peer's. {@code controlLeftover} holds bytes already read from stream 0 beyond the
     * switch packet; they are the start of the record stream. May be null.
     *
     * <p>Activation runs on the connection's event loop; a call from another thread is queued
     * there. A server must therefore call this on the event loop before, or in the same task as,
     * the flush that sends SERVER_SWITCH (13.3): a queued activation could run after the client
     * has already opened stream 4, which is fatal.
     *
     * <p>From that point stream 0 belongs to this session (13.14): the handlers the single-stream
     * owner installed before activation are removed from stream 0's pipeline so none of them can
     * read from it, answer a peer FIN or drive its own bare FIN. The old owner must route its
     * stream-0 close into {@link #end} (or {@link #abort}), never into its own FIN path.
     */
    public void activate(ByteBuffer controlLeftover) {
        final ByteBuf leftover = controlLeftover == null || !controlLeftover.hasRemaining() ? null
                : Unpooled.copiedBuffer(controlLeftover);
        run(() -> doActivate(leftover));
    }

    /**
     * Hands over a peer-created stream other than stream 0, from the stream handler's
     * {@code initChannel} (so autoRead is off before the first read). Anything but the one
     * allowed stream is fatal: a unidirectional stream, any other id, a second open, or the
     * server seeing stream 4 before its own activation.
     */
    public void acceptStream(QuicStreamChannel stream) {
        if (!loop.inEventLoop()) {
            throw new IllegalStateException("acceptStream must run on the connection's event loop");
        }
        stream.config().setAutoRead(false);
        try {
            if (closed) {
                return;
            }
            long id = stream.streamId();
            Lane lane = id == REALTIME_STREAM_ID ? Lane.REALTIME : id == WORLD_STREAM_ID ? Lane.WORLD : null;
            boolean allowed = lane != null && stream.type() == QuicStreamType.BIDIRECTIONAL
                    && !stream.isLocalCreated()
                    && (role == Role.SERVER ? id == REALTIME_STREAM_ID : id == WORLD_STREAM_ID);
            if (!allowed) {
                fail(CLOSE_ORDERING, "stream " + id + " is not allowed (13.3)", null);
                return;
            }
            if (streams[lane.ordinal()] != null) {
                fail(CLOSE_ORDERING, "second open of stream " + id, null);
                return;
            }
            if (role == Role.SERVER && !activated) {
                fail(CLOSE_ORDERING, "stream 4 before the server's activation (13.3)", null);
                return;
            }
            LaneStream s = new LaneStream(lane);
            streams[lane.ordinal()] = s;
            stream.pipeline().addFirst(s);
            if (activated) {
                s.readable = true;
                writePreamble(s);
                process();
            }
        } catch (Throwable t) {
            failFrom(t);
        }
    }

    /**
     * Submits one outbound GAME packet in the game's original order. The lane comes from the
     * routing table (a missing entry is CONTROL); {@code after} names packets already submitted
     * that this one semantically depends on (13.9, 13.8). The uncompressed size is the frame length.
     */
    public SentPacket send(ProtocolState state, int packetId, byte[] frame, SentPacket... after) {
        return send(state, packetId, frame, frame == null ? 0 : frame.length, after);
    }

    /** As {@link #send(ProtocolState, int, byte[], SentPacket...)} with an explicit uncompressed size. */
    public SentPacket send(final ProtocolState state, final int packetId, final byte[] frame,
                           final int uncompressedBytes, final SentPacket... after) {
        if (state == null || frame == null || frame.length < 1 || frame.length > RecordCodec.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("state/frame");
        }
        final SentPacket handle = new SentPacket();
        run(() -> doSend(state, packetId, frame, handle, after));
        return handle;
    }

    /**
     * Submits one vanilla bundle (13.15): opening delimiter, members, closing delimiter, all on
     * CONTROL with nothing else assigned between them. The whole bundle is validated before the
     * sequencer assigns anything, so a bundle beyond 1022 members, 8 MiB, the record bounds or the
     * unsent-queue cap consumes no sequence and closes the connection with 0x11 instead of being
     * emitted. Returns the handles in wire order, at most one per legally possible record.
     */
    public List<SentPacket> sendBundle(final OutboundPacket open, final List<OutboundPacket> members,
                                       final OutboundPacket close) {
        if (open == null || members == null || close == null) {
            throw new IllegalArgumentException("bundle");
        }
        final List<OutboundPacket> copy = new ArrayList<OutboundPacket>(members);
        final List<SentPacket> handles = new ArrayList<SentPacket>();
        int handleCount = Math.min(copy.size(), SenderSequencer.MAX_BUNDLE_MEMBERS) + 2;
        for (int i = 0; i < handleCount; i++) {
            handles.add(new SentPacket());
        }
        run(() -> doSendBundle(open, copy, close, handles));
        return handles;
    }

    /**
     * Normal shutdown (13.14): queues END as a full fence after everything submitted so far (send
     * any disconnect packet first). The peer closes with 0x0 when END is delivered; this side
     * closes after at most 10 seconds from the moment END is written. Later sends are dropped.
     *
     * <p>This is also the stream-0 close path after activation: the owner of the old single-stream
     * handler hands over at {@link #activate} and must route its close here, because a bare FIN
     * would make the peer close with 0x11 instead of a graceful END.
     */
    public void end() {
        run(this::doEnd);
    }

    /** Closes the connection now with an application error code; queued data is dropped. */
    public void abort(int code) {
        run(() -> fail(code, "aborted by the owner", null));
    }

    /**
     * The game's serial handler has applied a delivered unit (13.10): acknowledge its lane and
     * final sequence. From any thread.
     */
    public void applied(final Lane lane, final long sequence) {
        run(() -> doApplied(lane, sequence));
    }

    /**
     * The lane the routing table assigns to one outbound packet of this connection (13.6): the same
     * lookup {@link #send} does, without submitting anything, so a game can validate its own routing
     * answer before a packet is sequenced. A missing entry is CONTROL, never an inferred category.
     *
     * <p>Safe from any thread: the table is immutable and never changes for a connection. A packet
     * this answers CONTROL for is not thereby safe to send as REALTIME or WORLD; the answer is
     * authoritative and a disagreement is a 13.6 routing error.</p>
     */
    public Lane outboundLane(ProtocolState state, int packetId) {
        if (state == null) {
            throw new IllegalArgumentException("state");
        }
        return table.laneFor(state, sendDirection, packetId);
    }

    /** True while the unsent queue is below its backpressure mark. Readable from any thread. */
    public boolean isWritable() {
        return writable;
    }

    // --- Test hooks (package-private).

    /** Stops writing a lane's queued bytes until released. */
    void holdWrites(Lane lane) {
        run(() -> held[lane.ordinal()] = true);
    }

    void releaseWrites(Lane lane) {
        run(() -> {
            held[lane.ordinal()] = false;
            drain();
        });
    }

    /**
     * {pending records, pending bytes, unsent packets, unsent bytes, REALTIME record in progress
     * (0 or 1)}; call on the loop.
     */
    long[] snapshot() {
        LaneStream r = streams[Lane.REALTIME.ordinal()];
        return new long[] {receiver.pendingRecords(), receiver.pendingBytes(), unsentPackets, unsentBytes,
                r != null && r.decoder.hasPartial() ? 1 : 0};
    }

    /** Test hook: the sender's last-assigned sequences per lane; call on the loop. */
    long[] assignedSequences() {
        return sender.assigned();
    }

    /**
     * Test hook: the chunks a lane still holds, the one it parses from first; call on the loop.
     */
    List<ByteBuf> heldChunks(Lane lane) {
        LaneStream s = streams[lane.ordinal()];
        List<ByteBuf> chunks = new ArrayList<ByteBuf>();
        if (s == null) {
            return chunks;
        }
        if (s.currentBuf != null) {
            chunks.add(s.currentBuf);
        }
        chunks.addAll(s.inbound);
        return chunks;
    }

    /**
     * Test hook: the tick period the shared ticker of {@code loop} uses right now, in nanoseconds,
     * or {@code -1} when that loop has no ticker; call on that loop.
     */
    static long tickPeriodNanos(io.netty.channel.EventLoop loop) {
        LoopTicker ticker = LoopTicker.TICKERS.get(loop);
        return ticker == null ? -1L : ticker.periodNanos;
    }

    /**
     * Test hook: the pinned Netty's {@code QUICHE_ERR_STREAM_STOPPED}, or {@link Integer#MIN_VALUE}
     * when the probe cannot resolve it.
     */
    static int stopSendingErrCode() {
        return STOPPED_SENDING;
    }

    /**
     * The pinned Netty's {@code streamCapacity(long)} accessor for a connection class, or null when
     * it is not there. See {@link #peerStoppedReading}.
     */
    static java.lang.reflect.Method findStreamCapacity(Class<?> connectionClass) {
        Class<?> type = connectionClass;
        while (type != null) {
            try {
                java.lang.reflect.Method method = type.getDeclaredMethod("streamCapacity", long.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException next) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /** The stream channel of a lane, or null before it exists; call on the loop. */
    QuicStreamChannel channelFor(Lane lane) {
        if (lane == Lane.CONTROL) {
            return controlChannel;
        }
        LaneStream s = streams[lane.ordinal()];
        return s == null ? null : s.channel;
    }

    io.netty.channel.EventLoop eventLoop() {
        return loop;
    }

    // --- Plumbing.

    /** A call on the event loop runs at once, so it may overtake earlier calls other threads queued. */
    private void run(Runnable task) {
        if (loop.inEventLoop()) {
            guarded(task);
        } else {
            loop.execute(() -> guarded(task));
        }
    }

    private void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            failFrom(t);
        }
    }

    private long now() {
        return timing.clock.getAsLong();
    }

    private void failFrom(Throwable t) {
        int code;
        if (t instanceof MultistreamProtocolException) {
            code = ((MultistreamProtocolException) t).closeCode();
        } else if (t instanceof OrderingException) {
            code = CLOSE_ORDERING;
        } else {
            code = CLOSE_INTERNAL_ERROR;
        }
        fail(code, String.valueOf(t.getMessage()), t);
    }

    /** Closes the whole connection with {@code code}; idempotent. Event loop only. */
    private void fail(int code, String why, Throwable cause) {
        if (closed) {
            return;
        }
        LOG.log(Level.FINE, "closing multi-stream connection with {0}: {1}", new Object[] {code, why});
        shutdown();
        final int closeCode = code;
        loop.execute(() -> connection.close(true, closeCode, Unpooled.EMPTY_BUFFER));
        notifyClosed(code, false, cause != null ? cause : new java.io.IOException(why));
    }

    private void closeNormally() {
        if (closed) {
            return;
        }
        shutdown();
        loop.execute(() -> connection.close(true, CLOSE_NORMAL, Unpooled.EMPTY_BUFFER));
        notifyClosed(CLOSE_NORMAL, false, null);
    }

    private void shutdown() {
        closed = true;
        disarmTick();
        // 13.12: every release must run even if one of them fails. A throw here would skip the
        // rest, leaking this connection's node reservations to a budget every other connection
        // shares, so each is guarded on its own.
        for (LaneStream s : streams) {
            if (s != null) {
                try {
                    s.releaseInbound();
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "releasing a lane's chunks failed", t);
                }
            }
        }
        try {
            receiver.releaseAll();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "releasing the receive budgets failed", t);
        }
        for (ArrayDeque<byte[]> q : out) {
            q.clear();
        }
        unsentPackets = 0;
        unsentBytes = 0;
        // 13.12: whatever the send queues still held gives its node charge back with them. Guarded
        // like the receive releases above: a broken budget must not skip the rest of the shutdown.
        long held = queuedNodeBytes;
        queuedNodeBytes = 0;
        if (held > 0) {
            try {
                node.release(held);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "releasing the send queues' node charge failed", t);
            }
        }
    }

    private void notifyClosed(int code, boolean byPeer, Throwable cause) {
        if (game == null) {
            return;
        }
        try {
            game.onClosed(code, byPeer, cause);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "game onClosed failed", t);
        }
    }

    /**
     * Checks a lane's outstanding record grant against a lane that wants to start a record, and
     * records when the lane first waited so 13.12's incomplete-record deadline can close it.
     */
    private void noteWaiting(LaneStream s, boolean withheld) {
        if (withheld && s.waitingSince == 0) {
            s.waitingSince = now();
        }
    }

    /**
     * Charges an unparsed transport chunk to the node budget while it is held (13.12). The bytes
     * already arrived, so a refusal is the fatal case of 13.12: this connection closes with 0x11
     * rather than evicting another connection's reservation. The charge goes back when the lane
     * stream consumes or discards the chunk.
     */
    private void chargeChunk(int bytes) {
        if (bytes > 0 && !node.tryReserve(bytes)) {
            throw new OrderingException("node receive budget exhausted by an unparsed chunk (13.12)");
        }
    }

    // --- Activation and stream setup.

    private void doActivate(ByteBuf leftover) {
        if (closed || activated) {
            if (leftover != null) {
                leftover.release();
            }
            return;
        }
        activated = true;
        activatedAt = now();
        LaneStream control = new LaneStream(Lane.CONTROL);
        control.readable = true;
        control.opened = true;
        streams[Lane.CONTROL.ordinal()] = control;
        controlChannel.pipeline().addFirst(control);
        controlChannel.pipeline().addFirst(new ControlCloseGuard());
        takeOverStreamZero(control);
        if (leftover != null) {
            // 13.12: the leftover is a read chunk like any other, charged to the node budget while
            // it is held and released when it is consumed. If the node cannot carry bytes that
            // already arrived, this one connection closes; another connection's data is never
            // evicted.
            try {
                chargeChunk(leftover.readableBytes());
            } catch (Throwable t) {
                leftover.release();
                throw t;
            }
            control.inbound.add(leftover);
        }
        Lane mine = role == Role.CLIENT ? Lane.REALTIME : Lane.WORLD;
        Lane theirs = role == Role.CLIENT ? Lane.WORLD : Lane.REALTIME;
        openOwn(mine);
        LaneStream early = streams[theirs.ordinal()];
        if (early != null) {
            // A client may have seen stream 1 before its switch was validated; it was not read.
            early.readable = true;
            writePreamble(early);
        }
        process();
    }

    private void openOwn(final Lane lane) {
        final LaneStream s = new LaneStream(lane);
        streams[lane.ordinal()] = s;
        connection.createStream(QuicStreamType.BIDIRECTIONAL, s).addListener(f -> {
            if (!f.isSuccess()) {
                fail(CLOSE_INTERNAL_ERROR, "cannot open the " + lane + " stream", f.cause());
                return;
            }
            QuicStreamChannel channel = (QuicStreamChannel) f.getNow();
            long expected = lane == Lane.REALTIME ? REALTIME_STREAM_ID : WORLD_STREAM_ID;
            if (channel.streamId() != expected) {
                fail(CLOSE_ORDERING, "the " + lane + " stream opened as id " + channel.streamId(), null);
                return;
            }
            s.readable = true;
            writePreamble(s);
            process();
        });
    }

    private void writePreamble(LaneStream s) {
        if (s.preambleWritten || s.channel == null || closed) {
            return;
        }
        s.preambleWritten = true;
        byte[] bytes = AuxPreamble.encode(s.lane == Lane.REALTIME ? AuxPreamble.TYPE_REALTIME
                : AuxPreamble.TYPE_WORLD);
        s.channel.writeAndFlush(s.channel.alloc().buffer(bytes.length).writeBytes(bytes))
                .addListener(s.writeFailed);
        drain();
    }

    /**
     * Stream-0 takeover of 13.14: after activation this session owns stream 0, so the handlers the
     * single-stream owner installed before it are removed from stream 0's pipeline. They can no
     * longer read bytes, answer a peer FIN or drive a bare FIN of their own, and the lane END/close
     * path (see {@link #end}) is the only close left. The session's own lane handler stays first;
     * a peer FIN that the lane handler does not forward no longer reaches them either.
     *
     * <p>{@link ControlCloseGuard} stays at the head of the pipeline: a close from an old owner,
     * including one closing stream 0 from its own {@code handlerRemoved}, becomes the lane close.
     *
     * <p>What this cannot cover: {@code QuicStreamChannel.shutdownOutput()} writes its FIN straight
     * to the transport, outside the pipeline, so no handler can intercept it. The old owner (the
     * bridge in U4) must therefore hand its stream-0 close over to this session, calling
     * {@link #end} or {@link #abort}, and never its own FIN path, once it hears of the activation.
     */
    private void takeOverStreamZero(LaneStream mine) {
        io.netty.channel.ChannelPipeline pipeline = controlChannel.pipeline();
        for (String name : new ArrayList<String>(pipeline.names())) {
            io.netty.channel.ChannelHandler handler = pipeline.get(name);
            if (handler != null && handler != mine && !(handler instanceof ControlCloseGuard)) {
                LOG.log(Level.FINE, "13.14: stream-0 takeover removes the handler {0}", name);
                pipeline.remove(handler);
            }
        }
    }

    /** The stream-0 close of 13.14: END as a full fence, never a bare FIN or a reset. */
    private void closeStreamZero() {
        LOG.log(Level.FINE, "13.14: stream 0 was closed, closing through the END path instead");
        run(this::doEnd);
    }

    /**
     * Turns a close (or disconnect) of stream 0 into the lane close path. It sits at the head of
     * stream 0's pipeline, so every outbound close from a handler installed before activation,
     * removed or not, passes through it before it can reach the transport.
     */
    private final class ControlCloseGuard extends ChannelOutboundHandlerAdapter {
        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            if (promise.setUncancellable()) {
                promise.setSuccess();
            }
            closeStreamZero();
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
            if (promise.setUncancellable()) {
                promise.setSuccess();
            }
            closeStreamZero();
        }
    }

    // --- Receive.

    private void onDecode(Envelope envelope, boolean insideBundle, long now) {
        byte[] frame = (byte[]) envelope.body();
        final Envelope current = envelope;
        final boolean[] reserved = new boolean[1];
        LaneGame.Reservation reservation = (extra, total) -> {
            receiver.reserveDecoded(current, extra, total, now());
            reserved[0] = true;
        };
        LaneGame.Decoded d;
        try {
            d = game.decode(envelope.lane(), envelope.sequence(), frame, insideBundle, reservation);
        } catch (ReceiverScheduler.RetryLater retry) {
            // 13.12: the node budget cannot carry the decompressed output yet. The game has released
            // what it allocated for this attempt (LaneGame#decode) and the scheduler has already
            // given this attempt's own reservations back, so the record stays undecoded and the
            // tick reruns the whole decode.
            withheldForNode = true;
            throw retry;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new OrderingException("decode failed: " + e, e);
        }
        if (d == null) {
            throw new OrderingException("decoder returned nothing");
        }
        if (!reserved[0]) {
            try {
                receiver.reserveDecoded(envelope, d.retainedBytes(), d.uncompressedBytes(), now());
            } catch (ReceiverScheduler.RetryLater retry) {
                withheldForNode = true;
                throw retry;
            }
        }
        if (d.bundle() == Envelope.Bundle.NONE
                && table.laneFor(d.state(), receiveDirection, d.packetId()) != envelope.lane()) {
            throw new OrderingException("packet " + d.packetId() + " arrived on " + envelope.lane()
                    + ", not its routed lane (13.6)");
        }
        receiver.decoded(envelope, d.bundle(), d.stateChange(), d.packet(), now());
    }

    private void onDeliver(ReceiverScheduler.Delivery delivery) {
        Envelope first = delivery.records().get(0);
        if (first.kind() == Envelope.Kind.END) {
            receiver.applied(Lane.CONTROL, first.sequence(), now());
            if (receiver.ended()) {
                closeNormally();
            }
            return;
        }
        List<Object> packets = new ArrayList<Object>(delivery.records().size());
        for (Envelope e : delivery.records()) {
            packets.add(e.body());
        }
        game.deliver(delivery.lane(), delivery.sequence(), packets, delivery.isBundle());
    }

    private void doApplied(Lane lane, long sequence) {
        if (closed) {
            return;
        }
        receiver.applied(lane, sequence, now());
        process();
    }

    /** Parses what is buffered, in lane priority order, then asks for more where there is budget. */
    private void process() {
        if (closed) {
            return;
        }
        if (processing) {
            reprocess = true;
            return;
        }
        processing = true;
        try {
            do {
                reprocess = false;
                for (Lane lane : Lane.values()) {
                    LaneStream s = streams[lane.ordinal()];
                    if (s != null && !closed) {
                        consume(s);
                    }
                }
            } while (reprocess && !closed);
        } catch (Throwable t) {
            failFrom(t);
        } finally {
            processing = false;
        }
        if (!closed) {
            drain();
            armTick();
        }
    }

    /**
     * Whether a new record may be started on {@code lane}. An auxiliary record needs room for the
     * largest legal record, so a conformant peer's record can always complete (13.12: never fatal
     * for a legal record); otherwise reading waits and QUIC flow control pushes back. The same
     * amount must fit the per-connection budget. The shared node budget is taken before the record
     * starts ({@link LaneStream#reserveRecord}) and before each read ({@link LaneStream#grantRead}),
     * each of which withholds this lane instead of failing it and is remembered in
     * {@link #withheldForNode} so the tick retries once other connections have released space.
     */
    private boolean credit(Lane lane) {
        if (lane == Lane.CONTROL) {
            return receiver.pendingRecords() < ReceiverScheduler.MAX_RECORDS
                    && receiver.pendingBytes() < ReceiverScheduler.SHARED_BYTES + ReceiverScheduler.CONTROL_RESERVE_BYTES;
        }
        return receiver.auxiliaryCreditAvailable(MAX_RECORD_WIRE, 1);
    }

    private void consume(LaneStream s) throws MultistreamProtocolException {
        if (!s.readable) {
            return;
        }
        while (!closed) {
            if (!s.nextBuffer()) {
                break;
            }
            if (s.preamble != null && !s.opened) {
                if (!s.preamble.feed(s.current)) {
                    continue;
                }
                int expected = s.lane == Lane.REALTIME ? AuxPreamble.TYPE_REALTIME : AuxPreamble.TYPE_WORLD;
                if (s.preamble.type() != expected) {
                    throw new MultistreamProtocolException(CLOSE_ORDERING, "13.3",
                            "stream preamble type " + s.preamble.type() + " on the " + s.lane + " stream");
                }
                s.opened = true;
                continue;
            }
            if (game == null) {
                // 13.3/13.12: no game is bound yet, so nothing may be decoded or delivered. Keep the
                // bytes already read buffered (they stay charged) and stop reading until bindGame().
                break;
            }
            boolean atBoundary = s.partial == null && !s.decoder.hasPartial();
            if (!s.midRecord() && !credit(s.lane)) {
                // 13.12: the per-connection budget has no room for this record (or its growth), so
                // the lane waits; the wait starts its incomplete-record deadline.
                noteWaiting(s, true);
                break;
            }
            if (atBoundary && !s.recordGranted && !s.reserveRecord()) {
                noteWaiting(s, true);
                break;
            }
            RecordCodec.Record record = s.decoder.next(s.current);
            if (record != null) {
                admit(s, record);
            } else if (s.partial == null && s.decoder.hasPartial()) {
                long size = s.decoder.partialWireBytes();
                long prepaid = Math.min(size, MAX_RECORD_WIRE - s.recordGrantUsed);
                s.partial = receiver.reservePartial(s.lane, size, prepaid, s.takeWaitingSince(), now());
                s.recordGrantUsed += prepaid;
                s.charged = size;
            } else if (s.recordGrantUsed > 0 && !s.recordGranted) {
                // the record finished; nothing to carry over
                s.recordGrantUsed = 0;
            }
        }
        if (closed) {
            return;
        }
        if (s.fin && !s.hasUnparsed() && !endReceived) {
            throw new MultistreamProtocolException(CLOSE_ORDERING, "13.14",
                    "unexpected FIN on the " + s.lane + " lane");
        }
        // The rest of a record whose length is known, or of a preamble, already holds its charge.
        // A bare, still incomplete length prefix holds almost nothing, so it needs credit again.
        if (!s.fin && !s.readPending && !s.hasUnparsed() && s.channel != null
                && (s.midRecord() || !s.opened || credit(s.lane)) && s.grantRead()) {
            s.readPending = true;
            s.channel.read();
        }
    }

    private void admit(LaneStream s, RecordCodec.Record record) {
        ReceiverScheduler.Partial partial = s.partial;
        s.partial = null;
        s.charged = 0;
        s.lengthKnown = false;
        if (partial == null) {
            throw new OrderingException("record without a reservation");
        }
        boolean end = record.kind() == RecordCodec.KIND_END;
        Envelope envelope = new Envelope(s.lane, end ? Envelope.Kind.END : Envelope.Kind.GAME,
                record.sequence(), new long[] {record.depControl(), record.depRealtime(), record.depWorld()},
                record.bodyLength(), end ? null : record.body());
        if (end) {
            endReceived = true;
        }
        receiver.complete(partial, envelope, envelope.wireBytes(), now());
    }

    // --- Send.

    private void doSend(ProtocolState state, int packetId, byte[] frame, SentPacket handle,
                        SentPacket[] after) {
        if (closed || endQueued) {
            return;
        }
        // 13.4: a frame that is not exactly one Minecraft length-framed GAME body never goes on the
        // wire, and this is the same answer a bundle part gets: 0x11, not an internal error. A
        // relaying adapter sends frames it received from its own peer, so a malformed frame is peer
        // input here, not a bug of ours.
        checkGameFrame(frame, "frame");
        Lane lane = table.laneFor(state, sendDirection, packetId);
        long[] semantic = null;
        if (after != null && after.length > 0) {
            semantic = new long[3];
            for (SentPacket p : after) {
                if (p == null || !p.isAssigned()) {
                    throw new OrderingException("semantic predecessor is not assigned");
                }
                int i = p.lane().ordinal();
                if (Long.compareUnsigned(p.sequence(), semantic[i]) > 0) {
                    semantic[i] = p.sequence();
                }
            }
        }
        reserveQueue(1, RecordCodec.HEADER_LENGTH + frame.length + 4L);
        Envelope e = sender.assign(lane, frame.length, frame, semantic, now());
        queue(e, frame);
        handle.assign(lane, e.sequence());
        drain();
        armTick();
    }

    private void doSendBundle(OutboundPacket open, List<OutboundPacket> members, OutboundPacket close,
                              List<SentPacket> handles) {
        if (closed || endQueued) {
            return;
        }
        long bytes = (RecordCodec.HEADER_LENGTH + 4L) * (members.size() + 2L) + open.frame().length
                + close.frame().length;
        for (OutboundPacket m : members) {
            bytes += m.frame().length;
        }
        // 13.15: the whole bundle, both delimiters included, is validated before the sequencer is
        // touched, so a bundle that cannot be emitted consumes no sequence and leaves no buffered
        // bundle behind. A sender that would exceed any bound closes with 0x11 instead.
        checkBundle(open, members, close);
        reserveQueue(members.size() + 2L, bytes);
        long t = now();
        sender.beginBundle(t);
        for (OutboundPacket m : members) {
            sender.addBundleMember(m.uncompressedBytes(), m.frame().length, m.frame(),
                    m.changesStateCompressionOrActivation(), t);
        }
        List<Envelope> records = sender.endBundle(open.uncompressedBytes(), open.frame().length, open.frame(),
                close.uncompressedBytes(), close.frame().length, close.frame(), t);
        List<byte[]> encoded = new ArrayList<byte[]>(records.size());
        long bundleNodeBytes = 0;
        for (Envelope e : records) {
            byte[] record = encode(e, (byte[]) e.body());
            encoded.add(record);
            bundleNodeBytes += record.length;
        }
        // 13.12/13.15: the whole bundle is charged before any of it is queued, so a bundle the node
        // cannot carry as a whole leaves nothing queued, nothing charged and no partial bundle.
        chargeNode(bundleNodeBytes);
        for (int i = 0; i < records.size(); i++) {
            Envelope e = records.get(i);
            enqueue(e.lane(), encoded.get(i));
            handles.get(i).assign(e.lane(), e.sequence());
        }
        drain();
        armTick();
    }

    /** The 13.15 sender bounds of one whole bundle, checked before any sequence is assigned. */
    private void checkBundle(OutboundPacket open, List<OutboundPacket> members, OutboundPacket close) {
        if (members.size() > SenderSequencer.MAX_BUNDLE_MEMBERS) {
            throw new OrderingException("bundle over the 1022-member bound (13.15)");
        }
        checkBundlePart(open);
        checkBundlePart(close);
        long uncompressed = (long) open.uncompressedBytes() + close.uncompressedBytes();
        for (OutboundPacket m : members) {
            if (m.changesStateCompressionOrActivation()) {
                throw new OrderingException("bundle member changes state, compression or activation (13.15)");
            }
            checkBundlePart(m);
            uncompressed += m.uncompressedBytes();
        }
        if (uncompressed > 8L * 1024 * 1024) {
            throw new OrderingException("bundle over its 8 MiB uncompressed bound (13.15)");
        }
    }

    /** One bundle part must carry a valid GAME body and a sane uncompressed size (13.4, 13.15). */
    private void checkBundlePart(OutboundPacket part) {
        if (part.uncompressedBytes() < 0) {
            throw new OrderingException("bundle part with a negative uncompressed size");
        }
        byte[] frame = part.frame();
        if (frame == null || frame.length > RecordCodec.MAX_BODY_LENGTH) {
            throw new OrderingException("bundle part frame over the record bound (13.4)");
        }
        checkGameFrame(frame, "bundle part");
    }

    /**
     * 13.4: a GAME body is exactly one Minecraft VarInt-length frame, so its inner length must
     * match what follows it. A frame that does not is never put on the wire; the sender closes with
     * 0x11, exactly like 13.15's other sender bounds, whether the frame came from the game or was
     * relayed from the peer. {@code what} names the caller's unit in the message.
     */
    private void checkGameFrame(byte[] frame, String what) {
        try {
            RecordCodec.validateBody(RecordCodec.KIND_GAME, frame, true);
        } catch (MultistreamProtocolException e) {
            throw new OrderingException(what + " is not a GAME body: " + e.getMessage(), e);
        }
    }

    private void doEnd() {
        if (closed || endQueued) {
            return;
        }
        // END bypasses the unsent cap: a full queue must still be able to close gracefully. The
        // node budget still has to carry its record, because it is bytes this session retains until
        // the lane writes them (13.12).
        Envelope e = sender.end(now());
        endRecord = RecordCodec.encodeEnd(e.sequence(), e.dependency(Lane.CONTROL),
                e.dependency(Lane.REALTIME), e.dependency(Lane.WORLD), true);
        chargeNode(endRecord.length);
        endQueued = true;
        enqueue(Lane.CONTROL, endRecord);
        drain();
        armTick();
    }

    private void queue(Envelope e, byte[] frame) {
        byte[] record = encode(e, frame);
        chargeNode(record.length);
        enqueue(e.lane(), record);
    }

    /** One sender envelope as the exact bytes that go on the wire (13.4). */
    private static byte[] encode(Envelope e, byte[] frame) {
        return RecordCodec.encodeGame(e.sequence(), e.dependency(Lane.CONTROL),
                e.dependency(Lane.REALTIME), e.dependency(Lane.WORLD), frame);
    }

    private void enqueue(Lane lane, byte[] record) {
        out.get(lane.ordinal()).add(record);
        unsentPackets++;
        unsentBytes += record.length;
        updateWritability();
    }

    /**
     * 13.12: charges one record this session is about to retain to the node's aggregate budget, so
     * the send queues count exactly like the receive side. A refusal is the send-refusal path of
     * 13.12: the record is not queued and the connection closes with 0x11, rather than letting one
     * connection's unsent records overrun a budget every connection of the node shares.
     */
    private void chargeNode(long bytes) {
        if (bytes <= 0) {
            return;
        }
        if (!node.tryReserve(bytes)) {
            throw new OrderingException("the node budget refused " + bytes + " queued bytes (13.12)");
        }
        queuedNodeBytes += bytes;
    }

    /**
     * Gives back the bytes of a record that has left this session's queues: written to the stream,
     * or dropped by {@link #shutdown}. The charge is dropped before the release, so a release that
     * throws cannot free the same bytes twice.
     */
    private void creditNode(long bytes) {
        if (bytes <= 0) {
            return;
        }
        queuedNodeBytes -= bytes;
        node.release(bytes);
    }

    /** 13.12: a producer that overruns the unsent cap disconnects. */
    private void reserveQueue(long packets, long bytes) {
        if (unsentPackets + packets > MAX_UNSENT_PACKETS || unsentBytes + bytes > MAX_UNSENT_BYTES) {
            throw new OrderingException("unsent queue over its cap (13.12)");
        }
    }

    private void updateWritability() {
        boolean now = writable;
        if (writable && (unsentPackets >= PAUSE_PACKETS || unsentBytes >= PAUSE_BYTES)) {
            now = false;
        } else if (!writable && unsentPackets <= RESUME_PACKETS && unsentBytes <= RESUME_BYTES) {
            now = true;
        }
        if (now != writable) {
            writable = now;
            LaneGame attached = game;
            if (attached == null) {
                return;
            }
            try {
                attached.onWritabilityChanged(now);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "game onWritabilityChanged failed", t);
            }
        }
    }

    /** CONTROL first, then one quantum per auxiliary lane in alternating order, until nothing moves. */
    private void drain() {
        if (closed || draining) {
            return;
        }
        draining = true;
        try {
            boolean progressed;
            do {
                progressed = writeQuantum(Lane.CONTROL);
                Lane first = nextAux == 0 ? Lane.REALTIME : Lane.WORLD;
                Lane second = nextAux == 0 ? Lane.WORLD : Lane.REALTIME;
                nextAux ^= 1;
                progressed |= writeQuantum(first);
                progressed |= writeQuantum(second);
            } while (progressed && !closed);
        } catch (Throwable t) {
            failFrom(t);
        } finally {
            draining = false;
        }
        updateWritability();
    }

    private boolean writeQuantum(Lane lane) {
        int i = lane.ordinal();
        LaneStream s = streams[i];
        ArrayDeque<byte[]> queue = out.get(i);
        if (s == null || s.channel == null || !s.preambleWritten || held[i] || queue.isEmpty()) {
            return false;
        }
        int budget = WRITE_QUANTUM;
        boolean wrote = false;
        while (budget > 0 && !queue.isEmpty() && s.channel.isWritable()) {
            byte[] head = queue.peek();
            int n = Math.min(head.length - outOffset[i], budget);
            ByteBuf buf = s.channel.alloc().buffer(n);
            try {
                buf.writeBytes(head, outOffset[i], n);
            } catch (RuntimeException e) {
                buf.release();
                throw e;
            }
            s.channel.write(buf).addListener(s.writeFailed);
            outOffset[i] += n;
            budget -= n;
            wrote = true;
            if (outOffset[i] == head.length) {
                queue.poll();
                outOffset[i] = 0;
                unsentPackets--;
                unsentBytes -= head.length;
                if (head == endRecord) {
                    endWrittenAt = now();
                    armTick();
                }
            }
            // 13.12: these bytes have reached the stream, so the node no longer carries them.
            creditNode(n);
        }
        if (wrote) {
            s.channel.flush();
        }
        return wrote;
    }

    // --- Timers.

    private boolean awaitingPeerStreams() {
        if (!activated) {
            return false;
        }
        LaneStream r = streams[Lane.REALTIME.ordinal()];
        LaneStream w = streams[Lane.WORLD.ordinal()];
        return r == null || w == null || !r.opened || !w.opened;
    }

    /**
     * Registers this session with its event loop's ticker (13.12/13.14). One timer serves every
     * activated session of a loop, so 50,000 connections cost 50,000/loopThread wakeups instead of
     * one timer each. Event loop only; idempotent.
     */
    private void armTick() {
        if (ticker != null || closed || !activated) {
            return;
        }
        ticker = LoopTicker.of(loop);
        ticker.register(this);
    }

    /** Removes this session from its loop's ticker; event loop only. */
    private void disarmTick() {
        LoopTicker registered = ticker;
        if (registered != null) {
            ticker = null;
            registered.unregister(this);
        }
    }

    /**
     * One tick of the loop's timer (13.14, 13.12): notice a peer STOP_SENDING on an idle lane,
     * retry the lanes a full node budget withheld, and advance the receiver's and sender's
     * deadlines. Runs for every activated session of the loop, whether or not it has work.
     */
    void tick() {
        if (closed) {
            return;
        }
        try {
            long t = now();
            if (withheldForNode) {
                // 13.12: another connection may have released node space since the refusal, so
                // retry the withheld lanes and the undecoded head before checking deadlines.
                // process() and retry() set the flag again when the node is still too full.
                withheldForNode = false;
                process();
                if (closed) {
                    return;
                }
                receiver.retry(t);
            }
            if (firstRecordByteBlocked(t)) {
                fail(CLOSE_ORDERING, "the first bytes of a record were blocked for too long (13.12)", null);
                return;
            }
            receiver.checkDeadlines(t);
            sender.checkDeadlines(t);
            if (activated && peerStoppedReading()) {
                fail(CLOSE_ORDERING, "the peer stopped reading a lane (13.14)", null);
                return;
            }
            if (awaitingPeerStreams() && t - activatedAt >= timing.openTimeoutNanos) {
                fail(CLOSE_ORDERING, "an allowed stream did not open in time (13.14)", null);
                return;
            }
            if (endWrittenAt != 0 && t - endWrittenAt >= timing.endLingerNanos) {
                closeNormally();
            }
        } catch (Throwable e) {
            failFrom(e);
        }
    }

    /** One periodic tick per event loop over that loop's activated sessions. */
    private static final class LoopTicker implements Runnable {
        /** Live tickers by event loop; an entry goes away with its last session. Loop thread only. */
        private static final java.util.concurrent.ConcurrentHashMap<io.netty.channel.EventLoop, LoopTicker>
                TICKERS = new java.util.concurrent.ConcurrentHashMap<io.netty.channel.EventLoop, LoopTicker>();

        private final io.netty.channel.EventLoop loop;
        private final java.util.HashSet<LaneSession> active = new java.util.HashSet<LaneSession>();
        private long periodNanos = Long.MAX_VALUE;
        private ScheduledFuture<?> task;

        private LoopTicker(io.netty.channel.EventLoop loop) {
            this.loop = loop;
        }

        /** The ticker of {@code loop}, created on first use; call from that loop only. */
        static LoopTicker of(io.netty.channel.EventLoop loop) {
            LoopTicker ticker = TICKERS.get(loop);
            if (ticker == null) {
                ticker = new LoopTicker(loop);
                LoopTicker raced = TICKERS.putIfAbsent(loop, ticker);
                if (raced != null) {
                    ticker = raced;
                }
            }
            return ticker;
        }

        /** Adds a session, shortening the shared period when it asks for a faster one. */
        void register(LaneSession session) {
            active.add(session);
            recompute();
            if (task == null) {
                task = loop.scheduleAtFixedRate(this, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
            }
        }

        /** Drops a session; the timer stops and the loop keeps no ticker while nothing is active. */
        void unregister(LaneSession session) {
            active.remove(session);
            if (active.isEmpty()) {
                if (task != null) {
                    task.cancel(false);
                    task = null;
                }
                periodNanos = Long.MAX_VALUE;
                TICKERS.remove(loop, this);
                return;
            }
            recompute();
            if (task == null) {
                task = loop.scheduleAtFixedRate(this, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
            }
        }

        /**
         * Sets the shared period to the fastest period of the sessions still registered and
         * reschedules when it changed. A session that leaves may have been the fastest, so the
         * period has to grow back: keeping the smallest period ever seen would tick this loop
         * faster than any session left on it asks for.
         */
        private void recompute() {
            long fastest = Long.MAX_VALUE;
            for (LaneSession session : active) {
                fastest = Math.min(fastest, session.timing.tickNanos);
            }
            if (fastest == periodNanos) {
                return;
            }
            periodNanos = fastest;
            if (task != null) {
                task.cancel(false);
                task = null;
            }
        }

        @Override
        public void run() {
            for (LaneSession session : new ArrayList<LaneSession>(active)) {
                session.tick();
            }
        }
    }

    /**
     * 13.12: a lane that has held bytes of a record it could not admit for {@link
     * ReceiverScheduler#TIMEOUT_NANOS} closes the connection with 0x11, exactly like an incomplete
     * record whose first byte was parsed. The deadline starts when the lane first waited, so a
     * node-budget refusal before the first prefix byte is not a way to wait forever.
     */
    private boolean firstRecordByteBlocked(long now) {
        for (Lane lane : Lane.values()) {
            LaneStream s = streams[lane.ordinal()];
            if (s != null && s.waitingSince != 0
                    && now - s.waitingSince >= ReceiverScheduler.TIMEOUT_NANOS) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the peer has sent STOP_SENDING on any lane (13.14). Netty fires no event for it and
     * an empty write is a no-op in its stream channel, so the only API-visible state that changes
     * without sending bytes is the lane's send capacity: it becomes quiche's stream-stopped error,
     * while a healthy lane reports its send window ({@code >= 0}). Reached reflectively because the
     * pinned Netty keeps it package-private; the class is looked up from the stream channel, so a
     * relocated Netty resolves the same way. If the lookup ever fails the probe is inert and a
     * STOP_SENDING is still caught by the first write on that lane.
     */
    private boolean peerStoppedReading() {
        for (Lane lane : Lane.values()) {
            LaneStream s = streams[lane.ordinal()];
            if (s != null && s.channel != null && sendStopped(s.channel)) {
                return true;
            }
        }
        return false;
    }

    /** One lane's send capacity; see {@link #peerStoppedReading} for why this is reflective. */
    private boolean sendStopped(QuicStreamChannel channel) {
        java.lang.reflect.Method capacity = streamCapacity();
        if (capacity == null || STOPPED_SENDING == Integer.MIN_VALUE) {
            return false;
        }
        try {
            Object value = capacity.invoke(channel.parent(), Long.valueOf(channel.streamId()));
            return value instanceof Long && ((Long) value).longValue() == STOPPED_SENDING;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "cannot read the stream send capacity", t);
            return false;
        }
    }

    /** Resolves the pinned Netty's {@code QuicheQuicChannel.streamCapacity(long)} once, if at all. */
    private java.lang.reflect.Method streamCapacity() {
        if (!streamCapacityLookedUp) {
            streamCapacityLookedUp = true;
            streamCapacityMethod = findStreamCapacity(connection.getClass());
            if (streamCapacityMethod == null) {
                LOG.log(Level.FINE, "the pinned Netty exposes no stream send capacity; a STOP_SENDING "
                        + "is then still noticed by the first write on the lane");
            }
        }
        return streamCapacityMethod;
    }

    // --- Per-stream handler.

    /** One lane's stream: parser state, unparsed chunks and its handler in the stream pipeline. */
    private final class LaneStream extends ChannelInboundHandlerAdapter {
        final Lane lane;
        final RecordCodec.Decoder decoder;
        final AuxPreamble.Parser preamble;
        final ArrayDeque<ByteBuf> inbound = new ArrayDeque<ByteBuf>();
        final io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<? super Void>> writeFailed =
                f -> {
                    if (!f.isSuccess()) {
                        fail(CLOSE_ORDERING, "write on the " + lane() + " lane failed: " + f.cause(), f.cause());
                    }
                };
        QuicStreamChannel channel;
        ByteBuf currentBuf;
        ByteBuffer current;
        ReceiverScheduler.Partial partial;
        long charged;
        boolean readable;
        boolean opened;
        boolean readPending;
        boolean fin;
        boolean preambleWritten;
        /** The record in progress has announced its length, so its full size is charged. */
        boolean lengthKnown;
        /** An outstanding node read grant of {@link LaneSession#READ_CHUNK} for the next read. */
        boolean granted;
        /** An outstanding node record grant of {@link LaneSession#MAX_RECORD_WIRE} for this record. */
        boolean recordGranted;
        /** How many bytes of the record grant have been transferred to the parser buffer. */
        long recordGrantUsed;
        /**
         * When this lane first held bytes of a record it could not admit; 0 while it has none. It
         * moves into the parser reservation ({@link #takeWaitingSince()}) and is cleared there.
         */
        long waitingSince;

        LaneStream(Lane lane) {
            this.lane = lane;
            this.preamble = lane == Lane.CONTROL ? null : new AuxPreamble.Parser();
            this.preambleWritten = lane == Lane.CONTROL;
            this.decoder = new RecordCodec.Decoder(lane == Lane.CONTROL, this::reserveWire);
        }

        private Lane lane() {
            return lane;
        }

        /**
         * The moment this lane first waited for bytes it could not admit, consumed once they are
         * handed to the parser reservation that carries their deadline (13.12). Zero while the lane
         * is not waiting: a lane that resumed must not be closed later by the wait it recovered
         * from, so the timestamp is never kept after it has moved.
         */
        private long takeWaitingSince() {
            long waited = waitingSince;
            waitingSince = 0;
            return waited != 0 ? waited : now();
        }

        /** The decoder's admission hook: charges the full parser buffer once the length is known. */
        private boolean reserveWire(int wireBytes) {
            int growth = wireBytes - (partial == null ? 0 : (int) charged);
            long prepaid = Math.min(growth, MAX_RECORD_WIRE - recordGrantUsed);
            if (partial == null) {
                // 13.12: whatever this lane waited for these bytes moves into the parser
                // reservation, whose own deadline takes over from there.
                partial = receiver.reservePartial(lane, wireBytes, prepaid, takeWaitingSince(), now());
            } else {
                receiver.growPartial(partial, growth, prepaid, now());
            }
            recordGrantUsed += prepaid;
            charged = wireBytes;
            lengthKnown = true;
            // 13.12: the record's real size is known, so the rest of its grant goes back.
            releaseRecordGrant();
            return true;
        }

        /** A record in progress whose whole parser buffer is already charged. */
        boolean midRecord() {
            return decoder.hasPartial() && lengthKnown;
        }

        /**
         * Takes the node read grant for the next {@code channel.read()} (13.12/13.13). The bytes a
         * read can deliver are reserved before the read, so no legal record's chunk is ever fatal
         * at the node level; a refusal withholds the read instead (non-fatal) and is retried by the
         * tick. The arriving chunk converts the grant into the chunk's charge, and the unused part
         * goes back.
         */
        boolean grantRead() {
            if (granted) {
                return true;
            }
            if (!node.tryReserve(READ_CHUNK)) {
                withheldForNode = true;
                return false;
            }
            granted = true;
            return true;
        }

        /**
         * Reserves the record grant for this lane (13.12): {@link LaneSession#MAX_RECORD_WIRE}
         * before the record's first byte is consumed. A refusal withholds the lane instead of
         * failing it, so the owner retries from its tick once another connection frees space.
         */
        boolean reserveRecord() {
            if (recordGranted) {
                return true;
            }
            if (!node.tryReserve(MAX_RECORD_WIRE)) {
                withheldForNode = true;
                return false;
            }
            recordGranted = true;
            recordGrantUsed = 0;
            return true;
        }

        /** Returns whatever is left of the record grant once the record's real size is known. */
        void releaseRecordGrant() {
            if (recordGranted) {
                recordGranted = false;
                long unused = MAX_RECORD_WIRE - recordGrantUsed;
                recordGrantUsed = 0;
                if (unused > 0) {
                    node.release(unused);
                }
            }
        }

        /** Gives an unused read grant back: the lane stopped reading or the session closed. */
        void releaseGrant() {
            if (granted) {
                granted = false;
                node.release(READ_CHUNK);
            }
        }

        /**
         * Releases one chunk that was charged when it arrived and is now consumed or discarded:
         * gives its bytes back to the node budget and frees it. A chunk's reader index never moves
         * while it is parsed (the decoder works on a {@code nioBuffer} view), so the amount
         * released is exactly the amount charged. The buffer is freed even when the release fails:
         * nothing else will ever free it, so a throwing release must not leak it.
         */
        void releaseChunk(ByteBuf chunk) {
            try {
                node.release(chunk.readableBytes());
            } finally {
                chunk.release();
            }
        }

        boolean nextBuffer() {
            if (current != null && current.hasRemaining()) {
                return true;
            }
            if (currentBuf != null) {
                releaseChunk(currentBuf);
                currentBuf = null;
                current = null;
            }
            ByteBuf next = inbound.poll();
            if (next == null) {
                return false;
            }
            currentBuf = next;
            current = next.nioBuffer();
            return current.hasRemaining() || nextBuffer();
        }

        boolean hasUnparsed() {
            return !inbound.isEmpty() || (current != null && current.hasRemaining());
        }

        /**
         * 13.12: gives everything this lane still holds back, whatever fails. Each step is guarded
         * on its own and ownership is dropped before the release, so one throwing release can
         * neither skip the rest of this lane's chunks nor free the same bytes twice. A lane that
         * kept its chunks charged would leak them into a budget every other connection shares.
         */
        void releaseInbound() {
            quietly(this::releaseGrant, "read grant");
            quietly(this::releaseRecordGrant, "record grant");
            ByteBuf queued;
            while ((queued = inbound.poll()) != null) {
                final ByteBuf chunk = queued;
                quietly(() -> releaseChunk(chunk), "queued chunk");
            }
            if (currentBuf != null) {
                final ByteBuf chunk = currentBuf;
                currentBuf = null;
                current = null;
                quietly(() -> releaseChunk(chunk), "held chunk");
            }
        }

        /** Runs one release step, logging a failure instead of letting it skip the later steps. */
        private void quietly(Runnable step, String what) {
            try {
                step.run();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "releasing a lane's " + what + " failed", t);
            }
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            attach((QuicStreamChannel) ctx.channel());
        }

        void attach(QuicStreamChannel ch) {
            channel = ch;
            ch.config().setAutoRead(false);
            ch.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(READ_CHUNK).maxMessagesPerRead(1));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            readPending = false;
            if (closed || !buf.isReadable()) {
                buf.release();
                return;
            }
            int bytes = buf.readableBytes();
            try {
                if (granted) {
                    // 13.12: the read was granted in advance, so this chunk's bytes are already
                    // reserved. Take the chunk's own charge first and give the grant up only once
                    // that has succeeded: a refusal has to leave the READ_CHUNK reservation owned by
                    // this lane, or closing cannot return it and the node budget leaks it.
                    if (bytes > READ_CHUNK) {
                        chargeChunk(bytes - READ_CHUNK);
                    }
                    granted = false;
                    if (bytes < READ_CHUNK) {
                        node.release(READ_CHUNK - bytes);
                    }
                } else {
                    // A chunk can only arrive without a grant if it was read before this rule, as a
                    // session's activation leftover does: charge it, and close only if bytes that
                    // already arrived cannot fit (13.12).
                    chargeChunk(bytes);
                }
            } catch (Throwable t) {
                buf.release();
                failFrom(t);
                return;
            }
            inbound.add(buf);
            process();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof ChannelInputShutdownReadComplete) {
                fin = true;
                run(LaneSession.this::process);
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            drain();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // A lane that closes while its connection is already gone is not an extra protocol
            // violation: the connection's own close, whether a clean 0x0 or a reset, is what the
            // game is told about.
            if (!closed && !endReceived && connection.isActive()) {
                fail(CLOSE_ORDERING, "the " + lane + " lane closed (13.14)", null);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!closed) {
                fail(CLOSE_ORDERING, "the " + lane + " lane failed: " + cause, cause);
            }
        }
    }

    /** Reports the peer's CONNECTION_CLOSE and any other end of the connection. */
    private final class ConnectionWatch extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof QuicConnectionCloseEvent && !closed) {
                QuicConnectionCloseEvent close = (QuicConnectionCloseEvent) evt;
                shutdown();
                notifyClosed(close.isApplicationClose() ? close.error() : -1, true, null);
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!closed) {
                shutdown();
                notifyClosed(-1, true, null);
            }
            ctx.fireChannelInactive();
        }
    }
}
