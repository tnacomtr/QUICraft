// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RouteV2;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;

/**
 * The N+2 {@code /3} session of one connection (docs/protocol.md 13.3-13.20): CONTROL on QUIC stream 0,
 * REALTIME on stream 4 and CHUNK[i] on stream {@code 1+4i}, for the negotiated N.
 *
 * <p>Assembly. The send side is a {@link SessionSendDriver} drained into the streams by a
 * {@link QuicStreamWriteSink}; every outbound packet is routed by {@link SendRouteResolver} from the reviewed
 * schema-2 table, and whatever cannot be resolved closes the connection 0x11 without writing it. The receive side
 * reads through a {@link QuicStreamByteSource}, behind a filter that checks each auxiliary stream's preamble,
 * into a {@link SessionReadPump} and {@link ServerReceiveDriver} on a server, or a {@link ClientReadPump} and
 * {@link SessionReceiveDriver} on a client.
 *
 * <p>Streams (13.3). The client opens REALTIME, the server opens the N CHUNK streams; each opener writes its
 * preamble {@code {2, logical index}} at once. The server answers on REALTIME with its own preamble. The client
 * never writes on a CHUNK stream, and the server does not read the reverse CHUNK direction (see
 * {@link #reverseChunkOpened}). Any other peer stream, a second open of an index, a server seeing REALTIME before
 * its activation, a wrong preamble, a FIN, a reset or a STOP_SENDING on any stream closes 0x11; so does an
 * expected peer stream that has not completed its preamble within the open timeout.
 *
 * <p>Closing. A close decided here goes to the peer as the QUIC application close with the receive driver's
 * reason phrase; a 0x12 without a classified phrase carries {@code "resource"}. END (13.14) is a fenced CONTROL
 * record; after it is written the session lingers, then closes 0x0 itself if the peer has not closed first.
 *
 * <p>Threading. Stream setup, receive, writes, ticks and closes run on the connection's event loop. Outbound
 * submissions, including END and the ACK_JOIN and BATCH_JOIN passthroughs, go straight to the synchronized send
 * driver on the caller's thread, so their handles and answers are available at once and their order is the call
 * order; the session then drains on the loop. After END, or after a send failed closed, the driver refuses every
 * further submission. A failure decided on a caller's thread never releases charges there: the driver only stops,
 * and the close and the release run on the loop, after any slice it already polled was written. A game
 * acknowledgement is always queued onto the loop.
 *
 * <p>Pressure rankings. The send driver registers the connection with the outbound ranking and the server's read
 * pump with the inbound ranking, keyed by the remote address.
 *
 * <p>TODO(U9): pool and ranking charges pass {@code account = null}, so the 13.12 per-account caps and account
 * histories do not apply yet; U9 plumbs the login identity in.
 */
public final class NStreamSession implements MultistreamSession {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    static final int CLOSE_NORMAL = 0x0;
    static final int CLOSE_INTERNAL_ERROR = 0x2;
    static final int CLOSE_ORDERING = MultistreamProtocolException.CLOSE_ORDERING;
    static final int CLOSE_RESOURCE = Protocol.CLOSE_RESOURCE;
    /** Receive steps per pump task before it yields the loop. */
    private static final int MAX_STEPS = 64;
    private static final byte[] NO_REASON = new byte[0];

    /**
     * quiche's {@code QUICHE_ERR_STREAM_STOPPED} for the idle STOP_SENDING probe (13.14); see
     * {@link LaneSession#stopSendingErrCode()}.
     */
    private static final int STOPPED_SENDING = LaneSession.stopSendingErrCode();

    /**
     * Shard placement and referenced CONTROL fence classes (13.7-13.10). Keep the latest applied interval
     * of each class, not an unbounded packet history. A forgotten or unreceived target is not evidence of
     * peer misconduct; only a retained, applied target can fail the class check. Non-CONTROL targets have
     * their lane checked by StreamReceiver itself.
     */
    static final class ShardValidator implements StreamReceiver.Validator {
        private final int shards;
        private final long[] opening = new long[Dependency.values().length];
        private final long[] closing = new long[Dependency.values().length];

        ShardValidator(int shards) {
            LogicalStream.validateShardCount(shards);
            this.shards = shards;
        }

        @Override
        public boolean valid(int stream, Record record, ReceiveRoute route) {
            for (Record.Dependency dependency : record.dependencies()) {
                if (dependency.streamIndex() != LogicalStream.CONTROL) {
                    continue;
                }
                for (Dependency fence : Dependency.values()) {
                    int index = fence.ordinal();
                    long sequence = dependency.sequence();
                    if (opening[index] != 0 && sequence >= opening[index] && sequence <= closing[index]
                            && !acceptsControlFence(route, fence)) {
                        return false;
                    }
                }
            }
            if (route.lane() != ReceiveRoute.Lane.CHUNK) {
                return true;
            }
            Extractor.Position position = route.position();
            if (position == null) {
                return false;
            }
            return stream == 2 + ShardHash.shardOf(position.chunkX, position.chunkZ, record.epoch(), shards);
        }

        private boolean acceptsControlFence(ReceiveRoute route, Dependency fence) {
            if (fence == Dependency.GLOBAL || fence == Dependency.FENCE || fence == Dependency.VIEW) {
                return true;
            }
            return route.lane() == ReceiveRoute.Lane.REALTIME && fence == Dependency.CONTROL;
        }

        @Override
        public void applied(int stream, long first, long last, ReceiveRoute route) {
            if (stream == LogicalStream.CONTROL) {
                int index = route.fence().ordinal();
                if (closing[index] != first - 1 || opening[index] == 0) {
                    opening[index] = first;
                }
                closing[index] = last;
            }
        }
    }

    private final QuicChannel connection;
    private final LaneSession.Role role;
    private final QuicStreamChannel controlChannel;
    private final RoutingTable table;
    private final SessionContext context;
    private final SessionResources resources;
    private final LaneSession.Timing timing;
    private final EventLoop loop;
    private final boolean compressed;
    private final int shards;
    private final int streams;
    private final int protocolNumber;
    private final Direction sendDirection;
    private final Map<String, Extractor> extractors;
    private final SessionReceiveDriver.RouteSource routes;
    private final SessionSendDriver sendDriver;
    private final QuicStreamWriteSink sink;
    private final QuicStreamByteSource source;
    private final PreambleFilter preambles;
    private final QuicStreamChannel[] channels;
    /** Logical indexes ever attached, so a replacement stream for an index is refused (13.3). */
    private final boolean[] seen;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final GameProxy proxy = new GameProxy();
    /** Test hook: a bit per logical stream whose writes are held back. */
    private volatile int heldWrites;

    private SessionReadPump serverPump;
    private ServerReceiveDriver serverDriver;
    private ClientReadPump clientPump;
    private SessionReceiveDriver clientDriver;

    private volatile LaneGame game;
    private volatile boolean bound;
    /** A send failed closed on a caller's thread; nothing more is written and the loop closes 0x11. */
    private volatile boolean refused;
    private volatile String refusal;
    private volatile boolean writable = true;
    private volatile boolean closed;
    private boolean activated;
    private long activatedAt;
    private boolean pumpScheduled;
    private boolean closeNotified;
    private boolean executing;
    private int executingStream = -1;
    private long executingSequence;
    private boolean endSubmitted;
    private long endWrittenAt;
    private io.netty.util.concurrent.ScheduledFuture<?> ticker;
    private java.lang.reflect.Method streamCapacityMethod;
    private boolean streamCapacityLookedUp;

    /**
     * A production session for the negotiated context, uncompressed, with production timing.
     *
     * @throws IllegalArgumentException when the table is not schema 2 or lacks the PLAY serverbound limits a
     *     server needs, or when the resources' smallest outbound cap cannot hold the driver's headroom
     */
    public NStreamSession(QuicChannel connection, LaneSession.Role role, QuicStreamChannel control,
            RoutingTable table, LaneGame game, SessionContext context, SessionResources resources) {
        this(connection, role, control, table, game, context, resources, LaneSession.Timing.DEFAULT, false);
    }

    NStreamSession(QuicChannel connection, LaneSession.Role role, QuicStreamChannel control, RoutingTable table,
            LaneGame game, SessionContext context, SessionResources resources, LaneSession.Timing timing,
            boolean compressed) {
        if (connection == null || role == null || control == null || table == null || context == null
                || resources == null || timing == null) {
            throw new IllegalArgumentException("null argument");
        }
        if (control.streamId() != 0 || control.type() != QuicStreamType.BIDIRECTIONAL) {
            throw new IllegalArgumentException("CONTROL must be bidirectional stream 0");
        }
        if (table.schema() != 2) {
            throw new IllegalArgumentException("the N+2 session needs a reviewed schema-2 routing table (13.7)");
        }
        this.connection = connection;
        this.role = role;
        this.controlChannel = control;
        this.table = table;
        this.context = context;
        this.resources = resources;
        this.timing = timing;
        this.compressed = compressed;
        this.loop = connection.eventLoop();
        this.shards = context.shardCount();
        this.streams = context.logicalStreamCount();
        this.protocolNumber = table.protocolNumber();
        this.sendDirection = role == LaneSession.Role.CLIENT ? Direction.SERVERBOUND : Direction.CLIENTBOUND;
        this.routes = table::routeV2For;
        this.extractors = extractorsOf(table);
        this.channels = new QuicStreamChannel[streams];
        this.seen = new boolean[streams];
        this.game = game;
        this.bound = game != null;
        InetAddress ip = addressOf(connection);
        RouteV2.ServerboundLimits limits = null;
        if (role == LaneSession.Role.SERVER) {
            limits = table.serverboundLimits().get(ProtocolState.PLAY);
            if (limits == null) {
                throw new IllegalArgumentException("the table has no PLAY serverbound limits (13.7)");
            }
        }
        this.source = new QuicStreamByteSource(streams);
        this.preambles = new PreambleFilter();
        this.sendDriver = new SessionSendDriver(context, resources.profile(), resources.outbound(),
                resources.outboundRanking(), this, ip, null, timing.clock, role == LaneSession.Role.CLIENT);
        // Submissions run on callers' threads; a close they trigger only stops the driver, and its charges are
        // returned by release() on the loop, serialized with polling and writes (13.12).
        sendDriver.releaseOnOwnerClose();
        try {
            this.sink = new QuicStreamWriteSink(sendDriver, streams, this::writeAllowed, this::onSettled);
            StreamReceiver.Validator validator = new ShardValidator(shards);
            SessionReceiveDriver.CloseSink closeSink = this::onReceiveClose;
            if (role == LaneSession.Role.SERVER) {
                serverPump = new SessionReadPump(new ServerReadPartition(shards), resources.node(),
                        resources.inboundExcess(), this, ip, null, limits, compressed, preambles,
                        resources.inboundRanking(), resources.clock());
                serverDriver = new ServerReceiveDriver(shards, serverPump, validator, routes, protocolNumber, proxy,
                        timing.clock, closeSink);
            } else {
                ReceiveSlots slots = new ReceiveSlots(shards);
                clientPump = new ClientReadPump(shards, slots, compressed, preambles, timing.clock);
                clientDriver = new SessionReceiveDriver(shards, clientPump, slots, validator, routes, protocolNumber,
                        extractors, proxy, timing.clock, closeSink);
            }
        } catch (RuntimeException e) {
            sendDriver.close();
            if (serverPump != null) {
                serverPump.cancel(); // returns the inflater charge and leaves the inbound ranking
            }
            throw e;
        }
        resources.register(this, this::shed);
        connection.pipeline().addFirst(new ConnectionWatch());
    }

    private static Map<String, Extractor> extractorsOf(RoutingTable table) {
        Map<String, Extractor> result = new HashMap<String, Extractor>();
        for (String identifier : table.extractors().values()) {
            ExtractorRegistry.Decoder decoder = ExtractorRegistry.decoder(identifier);
            if (decoder.kind == ExtractorRegistry.Kind.SINGLE) {
                result.put(identifier, decoder);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static InetAddress addressOf(QuicChannel connection) {
        SocketAddress remote = connection.remoteSocketAddress();
        if (remote instanceof InetSocketAddress && ((InetSocketAddress) remote).getAddress() != null) {
            return ((InetSocketAddress) remote).getAddress();
        }
        return InetAddress.getLoopbackAddress();
    }

    // --- MultistreamSession.

    @Override
    public void bindGame(final LaneGame bind) {
        if (bind == null) {
            throw new IllegalArgumentException("game");
        }
        if (bound) {
            throw new IllegalStateException("the game is already bound");
        }
        bound = true;
        run(() -> {
            if (closed) {
                return;
            }
            game = bind;
            schedulePump();
        });
    }

    @Override
    public void activate(ByteBuffer controlLeftover) {
        final ByteBuffer leftover = controlLeftover == null || !controlLeftover.hasRemaining() ? null
                : copy(controlLeftover);
        run(() -> doActivate(leftover));
    }

    @Override
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
            int index = -1;
            if (id > 0 && id <= Integer.MAX_VALUE) {
                try {
                    index = context.logicalIndexOf(id);
                } catch (IllegalArgumentException outside) {
                    index = -1;
                }
            }
            boolean allowed = index > 0 && stream.type() == QuicStreamType.BIDIRECTIONAL && !stream.isLocalCreated()
                    && (role == LaneSession.Role.SERVER ? index == LogicalStream.REALTIME : index >= 2);
            if (!allowed) {
                fail(CLOSE_ORDERING, NO_REASON, "stream " + id + " is not allowed for N=" + shards + " (13.3)", null);
                return;
            }
            if (seen[index]) {
                fail(CLOSE_ORDERING, NO_REASON, "second open of stream " + id + " (13.3)", null);
                return;
            }
            if (role == LaneSession.Role.SERVER && !activated) {
                fail(CLOSE_ORDERING, NO_REASON, "REALTIME before the server's activation (13.3)", null);
                return;
            }
            seen[index] = true;
            if (role == LaneSession.Role.SERVER) {
                // The server answers on REALTIME with its own preamble before any record (13.3).
                writePreamble(stream, index);
                attach(index, stream, true, true);
            } else {
                // The client never writes on a CHUNK stream (13.3).
                attach(index, stream, true, false);
            }
            schedulePump();
            scheduleDrain();
        } catch (Throwable t) {
            failFrom(t);
        }
    }

    @Override
    public SentPacket send(ProtocolState state, int packetId, byte[] frame, SentPacket... after) {
        return sendResolved(state, packetId, frame, null, after);
    }

    @Override
    public SentPacket send(ProtocolState state, int packetId, byte[] frame, int uncompressedBytes,
            SentPacket... after) {
        return sendResolved(state, packetId, frame, null, after);
    }

    @Override
    public SentPacket send(ProtocolState state, int packetId, byte[] frame, byte[] uncompressedPacket,
            SentPacket... after) {
        return sendResolved(state, packetId, frame, uncompressedPacket, after);
    }

    private SentPacket sendResolved(ProtocolState state, int packetId, byte[] frame, byte[] uncompressed,
            SentPacket... after) {
        if (state == null || frame == null || frame.length < 1) {
            throw new IllegalArgumentException("state/frame");
        }
        if (refused || closed) {
            return new SentPacket();
        }
        SendRouteResolver.Resolved resolved = SendRouteResolver.resolve(routes, extractors, protocolNumber,
                sendDirection, state, packetId, uncompressed);
        if (resolved.failed()) {
            refuse("packet " + packetId + " in " + state + ": " + resolved.failure());
            return new SentPacket();
        }
        SentPacket handle;
        try {
            handle = sendDriver.submit(new OutboundPacket(frame), resolved.fence(), resolved.interaction(),
                    resolved.chunkX(), resolved.chunkZ(), dependenciesOf(after));
        } catch (RuntimeException e) {
            refuse("packet " + packetId + " in " + state + " was refused: " + e.getMessage());
            return new SentPacket();
        }
        afterSubmit();
        return handle;
    }

    @Override
    public List<SentPacket> sendBundle(OutboundPacket open, List<OutboundPacket> members, OutboundPacket close) {
        if (open == null || members == null || close == null) {
            throw new IllegalArgumentException("bundle");
        }
        if (refused || closed) {
            return new ArrayList<SentPacket>();
        }
        List<OutboundPacket> copy = new ArrayList<OutboundPacket>(members);
        int[] ids = new int[copy.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = packetIdOf(copy.get(i).frame(), compressed);
            if (ids[i] < 0) {
                refuse("bundle member " + i + " has no readable packet id (13.15)");
                return new ArrayList<SentPacket>();
            }
        }
        // The 13.15 strongest-member scope, without position extraction; the sequencer combines the scopes.
        SendRouteResolver.BundleScopes resolved = SendRouteResolver.bundleScopes(routes, protocolNumber,
                sendDirection, ProtocolState.PLAY, ids);
        if (resolved.failed()) {
            refuse(resolved.failure());
            return new ArrayList<SentPacket>();
        }
        StreamSequencer.Fence[] scopes = resolved.scopes();
        List<SentPacket> handles;
        try {
            handles = sendDriver.submitBundle(open, copy, scopes, close);
        } catch (RuntimeException e) {
            refuse("bundle refused: " + e.getMessage());
            return new ArrayList<SentPacket>();
        }
        afterSubmit();
        return handles;
    }

    /**
     * Assigns END at once on the caller's thread, like every other submission, so its place is the call order and
     * the driver refuses every later submission from then on (13.14). The loop then drains up to END.
     */
    @Override
    public void end() {
        if (closed || refused) {
            return;
        }
        SentPacket handle = sendDriver.submitEnd();
        if (!handle.isAssigned()) {
            // Already ended, sealed or closed: nothing new to drain beyond what is scheduled.
            afterSubmit();
            return;
        }
        run(() -> {
            if (closed) {
                return;
            }
            endSubmitted = true;
            drainNow();
        });
    }

    @Override
    public void abort(int code) {
        run(() -> fail(code, NO_REASON, "aborted by the owner", null));
    }

    @Override
    public void applied(final Lane lane, final long sequence) {
        // Always queued: a game may acknowledge from inside deliver, which runs in the receive driver.
        loop.execute(() -> guarded(() -> doApplied(lane, sequence)));
    }

    @Override
    public SessionContext context() {
        return context;
    }

    @Override
    public Lane outboundLane(ProtocolState state, int packetId) {
        if (state == null) {
            throw new IllegalArgumentException("state");
        }
        RouteV2 route = table.routeV2For(new RouteKey(protocolNumber, state, sendDirection, packetId));
        if (route == null || route.lane == RouteV2.StreamLane.CONTROL) {
            return Lane.CONTROL;
        }
        return route.lane == RouteV2.StreamLane.REALTIME ? Lane.REALTIME : Lane.WORLD;
    }

    @Override
    public boolean isWritable() {
        return writable;
    }

    @Override
    public boolean createAck(long id, byte[] payload, int chargedBytes) {
        boolean created = sendDriver.createAck(id, payload, chargedBytes);
        afterSubmit();
        return created;
    }

    @Override
    public void coverAssigned(long ackId, SentPacket covered) {
        sendDriver.coverAssigned(ackId, covered);
        afterSubmit();
    }

    @Override
    public boolean coverPending(long ackId, long updateToken) {
        boolean covered = sendDriver.coverPending(ackId, updateToken);
        afterSubmit();
        return covered;
    }

    @Override
    public void assignedPending(long updateToken, SentPacket covered) {
        sendDriver.assignedPending(updateToken, covered);
        afterSubmit();
    }

    @Override
    public void suppressPending(long updateToken) {
        sendDriver.suppressPending(updateToken);
        afterSubmit();
    }

    @Override
    public void markInexact(long ackId) {
        sendDriver.markInexact(ackId);
        afterSubmit();
    }

    @Override
    public SentPacket takeAck(long ackId) {
        SentPacket handle = sendDriver.takeAck(ackId);
        afterSubmit();
        return handle;
    }

    @Override
    public void expireAcks() {
        sendDriver.expireAcks();
        afterSubmit();
    }

    @Override
    public SentPacket beginBatch(long batchId, OutboundPacket start) {
        SentPacket handle = sendDriver.beginBatch(batchId, start);
        afterSubmit();
        return handle;
    }

    @Override
    public SentPacket batchInstall(long batchId, OutboundPacket install, int chunkX, int chunkZ) {
        SentPacket handle = sendDriver.batchInstall(batchId, install, chunkX, chunkZ);
        afterSubmit();
        return handle;
    }

    @Override
    public SentPacket finishBatch(long batchId, OutboundPacket join) {
        SentPacket handle = sendDriver.finishBatch(batchId, join);
        afterSubmit();
        return handle;
    }

    // --- Activation and streams.

    private void doActivate(ByteBuffer leftover) {
        if (closed || activated) {
            return;
        }
        activated = true;
        activatedAt = now();
        takeOverStreamZero();
        controlChannel.pipeline().addFirst(new ControlCloseGuard());
        preambles.leftover = leftover;
        seen[LogicalStream.CONTROL] = true;
        attach(LogicalStream.CONTROL, controlChannel, true, true);
        if (role == LaneSession.Role.CLIENT) {
            openOwn(LogicalStream.REALTIME);
        } else {
            for (int index = 2; index < streams; index++) {
                openOwn(index);
            }
        }
        long period = timing.tickNanos;
        ticker = loop.scheduleAtFixedRate(() -> guarded(this::tick), period, period, TimeUnit.NANOSECONDS);
        schedulePump();
        scheduleDrain();
    }

    /**
     * Stream-0 takeover (13.14), as in {@link LaneSession}: every handler the single-stream owner installed is
     * removed, and {@link ControlCloseGuard} turns any later close of stream 0 into END.
     */
    private void takeOverStreamZero() {
        io.netty.channel.ChannelPipeline pipeline = controlChannel.pipeline();
        for (String name : new ArrayList<String>(pipeline.names())) {
            io.netty.channel.ChannelHandler handler = pipeline.get(name);
            if (handler != null) {
                LOG.log(Level.FINE, "13.14: stream-0 takeover removes the handler {0}", name);
                pipeline.remove(handler);
            }
        }
    }

    private void openOwn(final int index) {
        connection.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel stream) {
                stream.config().setAutoRead(false);
                ReadGrantAllocator.install(stream);
            }
        }).addListener(future -> guarded(() -> {
            if (!future.isSuccess()) {
                fail(CLOSE_INTERNAL_ERROR, NO_REASON, "cannot open logical stream " + index, future.cause());
                return;
            }
            QuicStreamChannel stream = (QuicStreamChannel) future.getNow();
            if (closed) {
                return;
            }
            if (stream.streamId() != context.quicIdOf(index)) {
                fail(CLOSE_ORDERING, NO_REASON, "logical stream " + index + " opened as id " + stream.streamId(),
                        null);
                return;
            }
            seen[index] = true;
            writePreamble(stream, index);
            if (index == LogicalStream.REALTIME) {
                attach(index, stream, true, true);
            } else {
                attach(index, stream, false, true);
                reverseChunkOpened(index, stream);
            }
            schedulePump();
            scheduleDrain();
        }));
    }

    /**
     * Hook for the reverse CHUNK direction of a server-opened CHUNK stream (U8f-2). The client must send nothing
     * there; the server never reads it, so its bytes are never acknowledged and the peer's flow-control credit
     * on it stays at the transport's initial window. The 1-byte reverse-window guard belongs here.
     */
    private void reverseChunkOpened(int index, QuicStreamChannel stream) {
        // Intentionally empty until U8f-2.
    }

    private void writePreamble(QuicStreamChannel stream, int index) {
        stream.writeAndFlush(Unpooled.wrappedBuffer(AuxPreamble.encode(index)))
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        guarded(() -> fail(CLOSE_ORDERING, NO_REASON, "the preamble of logical stream " + index
                                + " could not be written", future.cause()));
                    }
                });
    }

    /** Installs the watch, then the byte source (reads) and the write sink (writes) on one stream. */
    private void attach(int index, QuicStreamChannel stream, boolean read, boolean write) {
        channels[index] = stream;
        stream.pipeline().addLast(new StreamWatch(index));
        if (read) {
            source.attach(index, stream);
            preambles.attached[index] = true;
        }
        if (write) {
            sink.attach(index, stream);
        }
    }

    // --- Receive.

    private void schedulePump() {
        if (pumpScheduled || closed) {
            return;
        }
        pumpScheduled = true;
        loop.execute(() -> guarded(this::pumpNow));
    }

    private void pumpNow() {
        pumpScheduled = false;
        if (closed || !activated || game == null) {
            return;
        }
        for (int step = 0; step < MAX_STEPS && !closed; step++) {
            long before = preambles.delivered;
            if (!receiveOnce()) {
                return;
            }
            if (preambles.delivered == before) {
                afterWork();
                return;
            }
        }
        afterWork();
        schedulePump();
    }

    /** One receive step; false when it closed the session. */
    private boolean receiveOnce() {
        try {
            if (serverDriver != null) {
                serverDriver.receive();
            } else {
                clientDriver.receive();
            }
        } catch (IOException e) {
            fail(CLOSE_ORDERING, NO_REASON, "stream read failed: " + e.getMessage(), e);
            return false;
        }
        return !closed;
    }

    private void doApplied(Lane lane, long sequence) {
        if (closed) {
            return;
        }
        if (!executing || executingSequence != sequence || laneOf(executingStream) != lane) {
            fail(CLOSE_ORDERING, NO_REASON, "acknowledgement for " + lane + "#" + sequence
                    + " does not name the executing unit (13.10)", null);
            return;
        }
        executing = false;
        try {
            if (serverDriver != null) {
                serverDriver.acknowledged(executingStream, sequence);
            } else {
                clientDriver.acknowledged(executingStream, sequence);
            }
        } catch (IllegalStateException e) {
            fail(CLOSE_ORDERING, NO_REASON, "acknowledgement refused: " + e.getMessage(), e);
            return;
        }
        afterWork();
        schedulePump();
    }

    /** The receive drivers' one close: 0x0 after END, otherwise the code and reason go to the peer. */
    private void onReceiveClose(int code, byte[] reason) {
        if (code == CLOSE_NORMAL) {
            closeNormally();
        } else {
            fail(code, reason == null ? NO_REASON : reason, "receive driver closed with 0x"
                    + Integer.toHexString(code), null);
        }
    }

    // --- Send.

    private void refuse(String why) {
        if (refused) {
            return;
        }
        refusal = why;
        refused = true;
        LOG.log(Level.FINE, "13.6: an outbound packet cannot be routed, closing 0x11: {0}", why);
        // The caller's thread only refuses further submissions. The close, the driver's shutdown and its charge
        // release run on the loop, so they cannot land between the loop's poll of a slice and its write.
        sendDriver.seal();
        run(() -> fail(CLOSE_ORDERING, NO_REASON, why, null));
    }

    private void afterSubmit() {
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (closed || !drainScheduled.compareAndSet(false, true)) {
            return;
        }
        loop.execute(() -> guarded(() -> {
            drainScheduled.set(false);
            drainNow();
        }));
    }

    private void drainNow() {
        if (closed) {
            return;
        }
        // After a refusal nothing more is written; afterWork closes the session on this loop.
        if (activated && !refused) {
            sink.drain();
        }
        afterWork();
    }

    private boolean writeAllowed(int stream) {
        return (heldWrites & (1 << stream)) == 0;
    }

    /** A slice's write completed: a release can grant this or another owner's records, so drain again. */
    private void onSettled(boolean success) {
        if (!success && !closed) {
            fail(CLOSE_ORDERING, NO_REASON, "a stream write failed (13.14)", null);
            return;
        }
        scheduleDrain();
        resources.wakeWaiters();
    }

    /** Checks the send driver after any driver work, notes END, registers wakeups and dispatches sheds. */
    private void afterWork() {
        if (!closed) {
            if (refused) {
                fail(CLOSE_ORDERING, NO_REASON, String.valueOf(refusal), null);
            } else if (sendDriver.isClosed()) {
                int code = sendDriver.closeCode();
                fail(code == 0 ? CLOSE_INTERNAL_ERROR : code,
                        code == CLOSE_RESOURCE ? SessionReceiveDriver.GENERIC_RESOURCE_REASON.clone() : NO_REASON,
                        "the send driver closed with 0x" + Integer.toHexString(code), null);
            }
        }
        if (!closed) {
            if (endSubmitted && endWrittenAt == 0 && sendDriver.queuedRecords() == 0) {
                endWrittenAt = now();
            }
            if (sendDriver.awaitingGrant()) {
                resources.awaitGrant(this, this::scheduleDrain);
            }
            updateWritability();
        }
        resources.dispatchShed();
    }

    private void updateWritability() {
        int queued = sendDriver.queuedRecords();
        int limit = resources.profile().queuePacketLimit;
        boolean next = writable ? queued < limit * 3 / 4 : queued <= limit / 4;
        if (next != writable) {
            writable = next;
            LaneGame current = game;
            if (current != null) {
                try {
                    current.onWritabilityChanged(next);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "game onWritabilityChanged failed", t);
                }
            }
        }
    }

    // --- Timer.

    /** One periodic tick (13.12, 13.14): STOP_SENDING probe, open timeout, receive deadlines, ACK expiry, END. */
    void tick() {
        if (closed || !activated) {
            return;
        }
        long t = now();
        if (peerStoppedReading()) {
            fail(CLOSE_ORDERING, NO_REASON, "the peer stopped reading a stream (13.14)", null);
            return;
        }
        if (!preambles.allExpectedSeen() && t - activatedAt >= timing.openTimeoutNanos) {
            fail(CLOSE_ORDERING, NO_REASON, "a peer stream did not open in time (13.3)", null);
            return;
        }
        if (game != null && !receiveOnce()) {
            return;
        }
        sendDriver.expireAcks();
        drainNow();
        if (closed) {
            return;
        }
        if (endWrittenAt != 0 && t - endWrittenAt >= timing.endLingerNanos) {
            closeNormally();
        }
    }

    private boolean peerStoppedReading() {
        for (QuicStreamChannel stream : channels) {
            if (stream != null && sendStopped(stream)) {
                return true;
            }
        }
        return false;
    }

    private boolean sendStopped(QuicStreamChannel stream) {
        if (!streamCapacityLookedUp) {
            streamCapacityLookedUp = true;
            streamCapacityMethod = LaneSession.findStreamCapacity(connection.getClass());
        }
        if (streamCapacityMethod == null || STOPPED_SENDING == Integer.MIN_VALUE) {
            return false;
        }
        try {
            Object value = streamCapacityMethod.invoke(stream.parent(), Long.valueOf(stream.streamId()));
            return value instanceof Long && ((Long) value).longValue() == STOPPED_SENDING;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "cannot read the stream send capacity", t);
            return false;
        }
    }

    // --- Closing.

    /** A pool selected this connection as a victim: close it 0x12, then confirm to the pool (13.12). */
    private void shed(Runnable confirm) {
        loop.execute(() -> {
            try {
                if (closed) {
                    release();
                    confirm.run();
                    return;
                }
                LOG.log(Level.FINE, "13.12: closing a shed connection with 0x12");
                closed = true;
                try {
                    connection.close(true, CLOSE_RESOURCE,
                            Unpooled.wrappedBuffer(SessionReceiveDriver.GENERIC_RESOURCE_REASON.clone()));
                } finally {
                    try {
                        release();
                        notifyClosed(CLOSE_RESOURCE, false, new IOException("shed under resource pressure (13.12)"));
                    } finally {
                        confirm.run();
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "shedding failed", t);
            }
        });
    }

    private void closeNormally() {
        if (closed) {
            return;
        }
        closed = true;
        loop.execute(() -> connection.close(true, CLOSE_NORMAL, Unpooled.EMPTY_BUFFER));
        release();
        notifyClosed(CLOSE_NORMAL, false, null);
    }

    /** Closes the whole connection with {@code code} and {@code reason}; idempotent. Event loop only. */
    private void fail(int code, byte[] reason, String why, Throwable cause) {
        if (closed) {
            return;
        }
        LOG.log(Level.FINE, "closing N+2 connection with 0x{0}: {1}", new Object[] {Integer.toHexString(code), why});
        closed = true;
        // A 0x12 without a classified phrase carries the generic "resource" (13.12).
        final byte[] phrase = reason != null && reason.length > 0 ? reason
                : code == CLOSE_RESOURCE ? SessionReceiveDriver.GENERIC_RESOURCE_REASON.clone() : NO_REASON;
        loop.execute(() -> connection.close(true, code, Unpooled.wrappedBuffer(phrase)));
        release();
        notifyClosed(code, false, cause != null ? cause : new IOException(why));
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
        fail(code, NO_REASON, String.valueOf(t.getMessage()), t);
    }

    /**
     * Returns every charge once (13.12): the send driver's outbound bytes and ranking entry, the pumps' inbound
     * charges and the source's buffers. Each release is guarded so a failing one cannot skip the rest.
     */
    private void release() {
        io.netty.util.concurrent.ScheduledFuture<?> scheduled = ticker;
        ticker = null;
        if (scheduled != null) {
            quietly(() -> scheduled.cancel(false), "ticker");
        }
        quietly(sendDriver::close, "send driver");
        if (serverDriver != null) {
            quietly(serverDriver::close, "server receive driver");
            quietly(serverPump::cancel, "server read pump");
        }
        if (clientDriver != null) {
            quietly(clientDriver::close, "client receive driver");
            quietly(clientPump::cancel, "client read pump");
        }
        quietly(source::close, "byte source");
        quietly(() -> resources.unregister(this), "resource registration");
        quietly(resources::wakeWaiters, "grant wakeups");
    }

    private static void quietly(Runnable step, String what) {
        try {
            step.run();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "releasing the " + what + " failed", t);
        }
    }

    private void notifyClosed(int code, boolean byPeer, Throwable cause) {
        if (closeNotified) {
            return;
        }
        closeNotified = true;
        LaneGame current = game;
        if (current == null) {
            return;
        }
        try {
            current.onClosed(code, byPeer, cause);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "game onClosed failed", t);
        }
    }

    // --- Plumbing.

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

    private static ByteBuffer copy(ByteBuffer from) {
        ByteBuffer copy = ByteBuffer.allocate(from.remaining());
        copy.put(from.duplicate());
        copy.flip();
        return copy;
    }

    private static Lane laneOf(int stream) {
        if (stream == LogicalStream.CONTROL) {
            return Lane.CONTROL;
        }
        return stream == LogicalStream.REALTIME ? Lane.REALTIME : Lane.WORLD;
    }

    private static Record.Dependency[] dependenciesOf(SentPacket... after) {
        List<Record.Dependency> result = new ArrayList<Record.Dependency>();
        if (after != null) {
            for (SentPacket handle : after) {
                if (handle != null && handle.isAssigned()) {
                    result.add(new Record.Dependency(handle.logicalStream(), handle.sequence()));
                }
            }
        }
        return result.toArray(new Record.Dependency[result.size()]);
    }

    /**
     * The packet id of one Minecraft frame (13.4): VarInt length, then, when compression is on, a VarInt data
     * length that must be 0 for the id to be readable, then the VarInt id. Returns -1 when it cannot be read.
     */
    static int packetIdOf(byte[] frame, boolean compressed) {
        int[] at = {0};
        if (readVarInt(frame, at) < 0) {
            return -1;
        }
        if (compressed) {
            int dataLength = readVarInt(frame, at);
            if (dataLength != 0) {
                return -1;
            }
        }
        return readVarInt(frame, at);
    }

    private static int readVarInt(byte[] bytes, int[] at) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (at[0] >= bytes.length) {
                return -1;
            }
            int b = bytes[at[0]++] & 0xff;
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value < 0 ? -1 : value;
            }
        }
        return -1;
    }

    // --- Test hooks (package-private).

    /** Holds back (or releases) the writes of one logical stream; any thread. */
    void holdWrites(int stream, boolean hold) {
        synchronized (this) {
            heldWrites = hold ? heldWrites | (1 << stream) : heldWrites & ~(1 << stream);
        }
        if (!hold) {
            scheduleDrain();
        }
    }

    /** The channel of a logical stream, or null; call on the loop. */
    QuicStreamChannel channelOf(int stream) {
        return channels[stream];
    }

    EventLoop eventLoop() {
        return loop;
    }

    SessionSendDriver sendDriver() {
        return sendDriver;
    }

    QuicStreamWriteSink sink() {
        return sink;
    }

    // --- Handlers.

    /**
     * Strips and checks each auxiliary stream's preamble (13.3) in front of the byte source, serves the CONTROL
     * activation leftover first, and counts delivered bytes so the pump knows when a step made progress. An
     * unattached stream reads as empty. A preamble that names another index fails the read.
     */
    private final class PreambleFilter implements SessionReadPump.ByteSource {
        final boolean[] attached = new boolean[streams];
        private final AuxPreamble.Parser[] parsers = new AuxPreamble.Parser[streams];
        private final byte[] scratch = new byte[2];
        ByteBuffer leftover;
        long delivered;

        PreambleFilter() {
            for (int index = 1; index < streams; index++) {
                // A server reads only REALTIME; a client reads REALTIME and every CHUNK stream.
                if (index == LogicalStream.REALTIME || role == LaneSession.Role.CLIENT) {
                    parsers[index] = new AuxPreamble.Parser();
                }
            }
        }

        boolean allExpectedSeen() {
            for (AuxPreamble.Parser parser : parsers) {
                if (parser != null && !parser.done()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int read(int stream, byte[] into, int offset, int max) throws IOException {
            if (stream < 0 || stream >= streams) {
                throw new IOException("logical stream " + stream + " is outside N+2");
            }
            if (!attached[stream] || max == 0) {
                return 0;
            }
            if (stream == LogicalStream.CONTROL && leftover != null) {
                int take = Math.min(max, leftover.remaining());
                leftover.get(into, offset, take);
                if (!leftover.hasRemaining()) {
                    leftover = null;
                }
                delivered += take;
                return take;
            }
            AuxPreamble.Parser parser = parsers[stream];
            if (parser != null && !parser.done()) {
                int got = source.read(stream, scratch, 0, 2 - parser.consumed());
                if (got == 0) {
                    return 0;
                }
                delivered += got;
                try {
                    parser.feed(ByteBuffer.wrap(scratch, 0, got));
                } catch (MultistreamProtocolException e) {
                    throw new IOException("bad preamble on logical stream " + stream + ": " + e.getMessage(), e);
                }
                if (!parser.done()) {
                    return 0;
                }
                if (parser.logicalStreamIndex() != stream) {
                    throw new IOException("the preamble names logical stream " + parser.logicalStreamIndex()
                            + " on logical stream " + stream + " (13.3)");
                }
            }
            int got = source.read(stream, into, offset, max);
            delivered += got;
            return got;
        }
    }

    /** The game side for the receive drivers: records the executing unit and forwards to the bound game. */
    private final class GameProxy implements LaneGame {
        @Override
        public void onExecutorTickStallStart() {
            game.onExecutorTickStallStart();
        }

        @Override
        public void onExecutorTickStallEnd() {
            game.onExecutorTickStallEnd();
        }

        @Override
        public void onRecordAppliedSignal(int logicalStream, long sequence, boolean applied) {
            game.onRecordAppliedSignal(logicalStream, sequence, applied);
        }

        @Override
        public void onDecodedUncompressed(Lane lane, int logicalStream, long sequence, byte[] uncompressedFrame) {
            game.onDecodedUncompressed(lane, logicalStream, sequence, uncompressedFrame);
        }

        @Override
        public void onEnqueued(Lane lane, int logicalStream, long sequence) {
            executing = true;
            executingStream = logicalStream;
            executingSequence = sequence;
            game.onEnqueued(lane, logicalStream, sequence);
        }

        @Override
        public void onApplicationComplete(Lane lane, int logicalStream, long sequence) {
            game.onApplicationComplete(lane, logicalStream, sequence);
        }

        @Override
        public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle, Reservation reservation)
                throws Exception {
            return game.decode(lane, sequence, frame, insideBundle, reservation);
        }

        @Override
        public Decoded decodeInflated(Lane lane, long sequence, byte[] content, boolean insideBundle,
                Reservation reservation) throws Exception {
            return game.decodeInflated(lane, sequence, content, insideBundle, reservation);
        }

        @Override
        public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            game.deliver(lane, sequence, packets, bundle);
        }

        @Override
        public void onWritabilityChanged(boolean writable) {
            game.onWritabilityChanged(writable);
        }

        @Override
        public void onClosed(int code, boolean byPeer, Throwable cause) {
            // The session reports the close itself, once.
        }
    }

    /**
     * Watches one stream ahead of the byte source: data and read-complete schedule the pump; a FIN, a reset
     * (any exception) or the stream going away while the connection lives closes 0x11 (13.14).
     */
    private final class StreamWatch extends ChannelInboundHandlerAdapter {
        private final int index;

        StreamWatch(int index) {
            this.index = index;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
            schedulePump();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.fireChannelReadComplete();
            schedulePump();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            ctx.fireUserEventTriggered(evt);
            if (evt instanceof ChannelInputShutdownReadComplete) {
                guarded(() -> fail(CLOSE_ORDERING, NO_REASON, "FIN on logical stream " + index + " (13.14)", null));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.fireExceptionCaught(cause);
            guarded(() -> fail(CLOSE_ORDERING, NO_REASON, "logical stream " + index + " failed: " + cause, cause));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            ctx.fireChannelInactive();
            if (!closed && connection.isActive()) {
                guarded(() -> fail(CLOSE_ORDERING, NO_REASON, "logical stream " + index + " closed (13.14)", null));
            }
        }
    }

    /** Turns any close of stream 0 after activation into END (13.14). */
    private final class ControlCloseGuard extends ChannelOutboundHandlerAdapter {
        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            if (promise.setUncancellable()) {
                promise.setSuccess();
            }
            end();
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
            if (promise.setUncancellable()) {
                promise.setSuccess();
            }
            end();
        }
    }

    /** Reports the peer's CONNECTION_CLOSE and any other end of the connection. */
    private final class ConnectionWatch extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof QuicConnectionCloseEvent && !closed) {
                QuicConnectionCloseEvent close = (QuicConnectionCloseEvent) evt;
                closed = true;
                release();
                notifyClosed(close.isApplicationClose() ? close.error() : -1, true, null);
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!closed) {
                closed = true;
                release();
                notifyClosed(-1, true, null);
            }
            ctx.fireChannelInactive();
        }
    }
}
