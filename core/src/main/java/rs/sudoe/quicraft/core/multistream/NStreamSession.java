// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
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
import java.util.function.LongSupplier;
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
import rs.sudoe.quicraft.core.time.StallClock;

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
 * <p>Stall accounting (13.10). Every receive timer and both rankings take one reading of the resources' shared
 * cumulative source; this session keeps no stall state and accepts no boundary reports. Until a detector is
 * installed on that source it reports zero exclusion, which is conservative (real time counts) and NOT
 * release-ready.
 *
 * <p>Account key (13.12). The pool and ranking charges of this connection carry the account the platform passes:
 * the authenticated profile UUID as a String on an online-authenticated server connection, and {@code null}
 * otherwise (offline mode, before authentication, and on clients). A non-String account is never charged:
 * the ranking path reads only Strings, so a null is the only honest value for anything else.
 */
public final class NStreamSession implements MultistreamSession {
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
    static final int CLOSE_RESOURCE = Protocol.CLOSE_RESOURCE;
    /** 13.12: unsent application queue cap, packets. */
    public static final int MAX_UNSENT_PACKETS = 1024;
    /** 13.12: unsent application queue cap, bytes. */
    public static final long MAX_UNSENT_BYTES = 16L * 1024 * 1024;
    /** 13.14: the peer's streams must be open within this long after activation. */
    public static final long OPEN_TIMEOUT_NANOS = 10_000_000_000L;
    /** 13.14: how long the END sender waits for the peer to close. */
    public static final long END_LINGER_NANOS = 10_000_000_000L;
    /**
     * The most activation leftover (the game's bytes past the switch packet plus what stream 0 kept
     * after the detach) one session takes as an admitted overdraft. A read delivers at most one 16 KiB
     * chunk and stream 0 stops reading at the detach, so an honest leftover is a few chunks; more is
     * a local invariant failure (0x2). The peer cannot make it larger, so this is not a protocol limit
     * the peer has to know.
     */
    public static final int MAX_ACTIVATION_LEFTOVER = 64 * 1024;
    /**
     * The most stream 0 may keep after the detach (13.12): half the leftover bound, leaving the rest
     * for the game's own bytes past the switch packet. See {@link #MAX_ACTIVATION_LEFTOVER}.
     */
    public static final int MAX_KEPT_AFTER_DETACH = MAX_ACTIVATION_LEFTOVER / 2;
    /** Receive steps per pump task before it yields the loop. */
    private static final int MAX_STEPS = 64;
    private static final byte[] NO_REASON = new byte[0];

    /** Clocks and periods; tests shorten them, production uses {@link #defaults(SessionResources)}. */
    static final class Timing {
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

        /**
         * The production timing of one session: the 13.14 default durations on the resources' owner clock. The
         * session's transport deadlines and its stall accounting must share that one clock, so callers never
         * create an independent clock for a session.
         */
        static Timing defaults(SessionResources resources) {
            if (resources == null) {
                throw new IllegalArgumentException("session resources");
            }
            return new Timing(resources.clock(), OPEN_TIMEOUT_NANOS, END_LINGER_NANOS, TimeUnit.SECONDS.toNanos(1));
        }
    }

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

    /**
     * Shard placement and evidence per frontier kind (docs/protocol.md 13.7, 13.9, 13.19).
     * Serverbound REALTIME uses max(G,C), allowing global/view/control (lines 1262-1265).
     * Clientbound REALTIME likewise adds C to G (lines 1327-1339); view sets C too (1332).
     * Ordinary CHUNK uses max(G,V), allowing global/view (1330, 1340).
     * ACK_JOIN coverage includes current CONTROL A[0], of any class (2040-2044).
     * BATCH_JOIN adds its view start to the normal G/V frontier (1341, 2092-2097), so allows
     * global/view. INTERACTION references any applied REALTIME record (1263-1265, 1336-1337).
     * References are monotonic per stream and kind, never across kinds. Each class-checked kind
     * has its own lazy 4,096-long ring, only on the receiving side (at most 96 KiB clientbound,
     * 32 KiB serverbound, plus fixed overhead). Only overflow evicts; older evidence is unprovable.
     */
    static final class ShardValidator implements StreamReceiver.Validator {
        private static final int MAX_CONTROL_HISTORY = 4096;
        private final int shards;
        private final int cap;
        private enum Kind { REALTIME, CHUNK, ACK_JOIN, BATCH_JOIN, INTERACTION }
        private final Direction direction;
        private final long[][] references;
        private final FenceRing[] rings = new FenceRing[Kind.values().length];
        private long highest;

        ShardValidator(int shards) {
            this(shards, Direction.CLIENTBOUND);
        }

        ShardValidator(int shards, Direction direction) {
            this(shards, direction, MAX_CONTROL_HISTORY);
        }

        ShardValidator(int shards, Direction direction, int cap) {
            LogicalStream.validateShardCount(shards);
            if (direction == null || cap < 1) {
                throw new IllegalArgumentException("direction/cap");
            }
            this.shards = shards;
            this.cap = cap;
            this.direction = direction;
            references = new long[direction == Direction.SERVERBOUND ? 2 : shards + 2][Kind.values().length];
        }

        @Override
        public boolean valid(int stream, Record record, ReceiveRoute route) {
            if (route.lane() == ReceiveRoute.Lane.CHUNK && !route.join()) {
                Extractor.Position position = route.position();
                if (position == null || stream != 2
                        + ShardHash.shardOf(position.chunkX, position.chunkZ, record.epoch(), shards)) {
                    return false;
                }
            }
            Kind kind = kind(route);
            for (Record.Dependency dependency : record.dependencies()) {
                long reference = dependency.sequence();
                if (reference == 0) continue; // Zero/absent frontiers are not evidence.
                if (isFrontier(kind, dependency)) {
                    if (reference < references[stream][kind.ordinal()]) return false;
                    if (kind != Kind.ACK_JOIN && kind != Kind.INTERACTION && reference <= highest) {
                        FenceRing ring = rings[kind.ordinal()];
                        if (ring == null || !ring.accepts(reference)) return false;
                    }
                }
            }
            // Commit references only after every check succeeded. Unapplied targets stay unknown here;
            // StreamReceiver waits for D before invoking us, then checks their exact applied class.
            for (Record.Dependency dependency : record.dependencies()) {
                if (dependency.sequence() == 0) continue;
                if (isFrontier(kind, dependency)) {
                    references[stream][kind.ordinal()] = dependency.sequence();
                }
            }
            return true;
        }

        private Kind kind(ReceiveRoute route) {
            if (route.lane() == ReceiveRoute.Lane.REALTIME) return Kind.REALTIME;
            if (route.lane() == ReceiveRoute.Lane.CHUNK) {
                if (route.fence() == Dependency.ACK_JOIN) return Kind.ACK_JOIN;
                if (route.fence() == Dependency.BATCH_JOIN) return Kind.BATCH_JOIN;
                return Kind.CHUNK;
            }
            return route.fence() == Dependency.INTERACTION ? Kind.INTERACTION : null;
        }

        private boolean isFrontier(Kind kind, Record.Dependency dependency) {
            return kind != null && dependency.streamIndex() ==
                    (kind == Kind.INTERACTION ? LogicalStream.REALTIME : LogicalStream.CONTROL);
        }

        private void remember(Kind kind, long first, long last) {
            int index = kind.ordinal();
            if (rings[index] == null) rings[index] = new FenceRing(cap);
            for (long sequence = first; sequence <= last; sequence++) rings[index].add(sequence);
        }

        int retainedEntries() {
            int count = 0;
            for (FenceRing ring : rings) if (ring != null) count += ring.size;
            return count;
        }

        @Override
        public void applied(int stream, long first, long last, ReceiveRoute route) {
            if (stream == LogicalStream.CONTROL) {
                Dependency fence = route.fence();
                boolean common = fence == Dependency.GLOBAL || fence == Dependency.FENCE || fence == Dependency.VIEW;
                if (common || fence == Dependency.CONTROL) remember(Kind.REALTIME, first, last);
                if (common && direction == Direction.CLIENTBOUND) {
                    remember(Kind.CHUNK, first, last);
                    remember(Kind.BATCH_JOIN, first, last);
                }
                highest = last;
            }
        }

        private static final class FenceRing {
            private final long[] sequences;
            private int head;
            private int size;
            private long floor = 1;

            FenceRing(int cap) { sequences = new long[cap]; }

            void add(long sequence) {
                if (size == sequences.length) {
                    floor = sequences[head] + 1;
                    head = (head + 1) % sequences.length;
                    size--;
                }
                sequences[(head + size) % sequences.length] = sequence;
                size++;
            }

            boolean accepts(long sequence) {
                if (sequence < floor) return true;
                int low = 0;
                int high = size - 1;
                while (low <= high) {
                    int middle = (low + high) >>> 1;
                    long value = sequences[(head + middle) % sequences.length];
                    if (value < sequence) low = middle + 1;
                    else if (value > sequence) high = middle - 1;
                    else return true;
                }
                return false;
            }
        }
    }

    private final QuicChannel connection;
    private final Role role;
    private final QuicStreamChannel controlChannel;
    private final RoutingTable table;
    private final SessionContext context;
    private final SessionResources resources;
    private final Timing timing;
    private final EventLoop loop;
    private final boolean compressed;
    /** The 13.12 account key of this connection's charges, or null (see the class comment). */
    private final String account;
    private final int shards;
    private final int streams;
    private final int protocolNumber;
    private final Direction sendDirection;
    private final Map<String, Extractor> extractors;
    private final SessionReceiveDriver.RouteSource routes;
    private SessionSendDriver sendDriver;
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
    /** Test seam to hold guard reads before they reach the transport. */
    java.util.function.Consumer<QuicStreamChannel> reverseGuardSetupForTests;

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
    private final ReceiveTrace receiveTrace = ReceiveTrace.enabled() ? new ReceiveTrace() : null;
    private long traceAckReceivedNanos;
    private boolean traceChunkReported;
    private boolean endSubmitted;
    private long endWrittenAt;
    private io.netty.util.concurrent.ScheduledFuture<?> ticker;
    private java.lang.reflect.Method streamCapacityMethod;
    private boolean streamCapacityLookedUp;

    /**
     * A production session for the negotiated context with production timing.
     *
     * @param compressed the game's stream compression state at activation (13.4), read once by the
     *     platform: it decides whether a GAME frame carries a Data Length VarInt and how bundle
     *     member ids are read. Vanilla sets the threshold during login and never changes it.
     * @throws IllegalArgumentException when the table is not schema 2 or lacks the PLAY serverbound limits a
     *     server needs, or when the resources' smallest outbound cap cannot hold the driver's headroom
     */
    public NStreamSession(QuicChannel connection, Role role, QuicStreamChannel control,
            RoutingTable table, LaneGame game, SessionContext context, SessionResources resources,
            boolean compressed) {
        this(connection, role, control, table, game, context, resources, Timing.defaults(resources), compressed, null);
    }

    /**
     * As {@link #NStreamSession(QuicChannel, Role, QuicStreamChannel, RoutingTable, LaneGame, SessionContext,
     * SessionResources, boolean)}, with the 13.12 account key of this connection's charges (see the class comment).
     *
     * @param account the authenticated account as a String, or null when unauthenticated
     */
    public NStreamSession(QuicChannel connection, Role role, QuicStreamChannel control,
            RoutingTable table, LaneGame game, SessionContext context, SessionResources resources,
            boolean compressed, String account) {
        this(connection, role, control, table, game, context, resources, Timing.defaults(resources), compressed, account);
    }

    NStreamSession(QuicChannel connection, Role role, QuicStreamChannel control, RoutingTable table,
            LaneGame game, SessionContext context, SessionResources resources, Timing timing,
            boolean compressed) {
        this(connection, role, control, table, game, context, resources, timing, compressed, null);
    }

    NStreamSession(QuicChannel connection, Role role, QuicStreamChannel control, RoutingTable table,
            LaneGame game, SessionContext context, SessionResources resources, Timing timing,
            boolean compressed, String account) {
        if (connection == null || role == null || control == null || table == null || context == null
                || resources == null || timing == null) {
            throw new IllegalArgumentException("null argument");
        }
        if (account != null && account.isEmpty()) {
            throw new IllegalArgumentException("account key must be null or non-empty");
        }
        if (!resources.cumulative()) {
            throw new IllegalArgumentException("the N+2 session needs cumulative session resources (13.10)");
        }
        if (timing.clock != resources.clock()) {
            throw new IllegalArgumentException("the session timing clock must be the resources' owner clock (13.10)");
        }
        this.account = account;
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
        this.sendDirection = role == Role.CLIENT ? Direction.SERVERBOUND : Direction.CLIENTBOUND;
        this.routes = table::routeV2For;
        this.extractors = extractorsOf(table);
        this.channels = new QuicStreamChannel[streams];
        this.seen = new boolean[streams];
        this.game = game;
        this.bound = game != null;
        InetAddress ip = addressOf(connection);
        RouteV2.ServerboundLimits limits = null;
        if (role == Role.SERVER) {
            limits = table.serverboundLimits().get(ProtocolState.PLAY);
            if (limits == null) {
                throw new IllegalArgumentException("the table has no PLAY serverbound limits (13.7)");
            }
        }
        this.source = new QuicStreamByteSource(streams);
        this.preambles = new PreambleFilter();
        try {
            // The send driver's constructor registers with the ranking and charges the slice; if it
            // throws (e.g. the stall source is invalid), the driver is null and must not be closed
            // again here. A failure inside the block below closes it via the null-check.
            this.sendDriver = new SessionSendDriver(context, resources.profile(), resources.outbound(),
                    resources.outboundRanking(), this, ip, account, resources.clock(), role == Role.CLIENT,
                    SessionResources.Accounting.CUMULATIVE);
            // Submissions run on callers' threads; a close they trigger only stops the driver, and its charges are
            // returned by release() on the loop, serialized with polling and writes (13.12).
            sendDriver.releaseOnOwnerClose();
            this.sink = new QuicStreamWriteSink(sendDriver, streams, this::writeAllowed, this::onSettled);
            StreamReceiver.Validator validator = new ShardValidator(shards,
                    role == Role.SERVER ? Direction.SERVERBOUND : Direction.CLIENTBOUND);
            SessionReceiveDriver.CloseSink closeSink = this::onReceiveClose;
            if (role == Role.SERVER) {
                serverPump = new SessionReadPump(new ServerReadPartition(shards), resources.node(),
                        resources.inboundExcess(), this, ip, account, limits, compressed, preambles,
                        resources.inboundRanking(), resources.clock(), SessionResources.Accounting.CUMULATIVE);
                serverDriver = new ServerReceiveDriver(shards, serverPump, validator, routes, protocolNumber, proxy,
                        resources.clock(), closeSink, resources.stallClock());
            } else {
                ReceiveSlots slots = new ReceiveSlots(shards);
                clientPump = new ClientReadPump(shards, slots, compressed, preambles, resources.clock());
                clientDriver = new SessionReceiveDriver(shards, clientPump, slots, validator, routes, protocolNumber,
                        extractors, proxy, resources.clock(), closeSink, resources.stallClock());
            }
        } catch (RuntimeException e) {
            // The construction failure is the cause the caller classifies; cleanup must never replace it.
            if (sendDriver != null) {
                try {
                    sendDriver.close();
                } catch (RuntimeException | Error cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            if (serverPump != null) {
                try {
                    serverPump.cancel(); // returns the inflater charge and leaves the inbound ranking
                } catch (RuntimeException | Error cleanup) {
                    e.addSuppressed(cleanup);
                }
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
            if (decoder.kind == ExtractorRegistry.Kind.SINGLE
                    || decoder.kind == ExtractorRegistry.Kind.JOIN) {
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
                    && (role == Role.SERVER ? index == LogicalStream.REALTIME : index >= 2);
            if (!allowed) {
                fail(CLOSE_ORDERING, NO_REASON, "stream " + id + " is not allowed for N=" + shards + " (13.3)", null);
                return;
            }
            if (seen[index]) {
                fail(CLOSE_ORDERING, NO_REASON, "second open of stream " + id + " (13.3)", null);
                return;
            }
            if (role == Role.SERVER && !activated) {
                fail(CLOSE_ORDERING, NO_REASON, "REALTIME before the server's activation (13.3)", null);
                return;
            }
            seen[index] = true;
            if (role == Role.SERVER) {
                // The server answers on REALTIME with its own preamble before any record (13.3).
                writePreamble(stream, index);
                attach(index, stream, true, true);
            } else {
                // The client never writes on a CHUNK stream (13.3).
                attach(index, stream, true, false);
            }
            schedulePump();
            scheduleDrain();
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
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

    /**
     * A level-creating or replacing packet (13.18): assigned as the GLOBAL epoch transition, which advances the
     * sender's epoch right after it. Serverbound epochs never change (13.18), and the transition takes no
     * explicit dependencies, so a call that names any, or comes from the client, fails closed (0x11).
     */
    private SentPacket sendEpochTransition(ProtocolState state, int packetId, byte[] frame, SentPacket... after) {
        if (sendDirection == Direction.SERVERBOUND) {
            refuse("packet " + packetId + " in " + state + " changes the serverbound epoch (13.18)");
            return new SentPacket();
        }
        if (after != null && after.length > 0) {
            refuse("epoch transition " + packetId + " in " + state + " names dependencies (13.18)");
            return new SentPacket();
        }
        SentPacket handle;
        try {
            handle = sendDriver.submitEpochTransition(new OutboundPacket(frame));
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new SentPacket();
        } catch (RuntimeException e) {
            refuse("epoch transition " + packetId + " in " + state + " was refused: " + e.getMessage());
            return new SentPacket();
        }
        afterSubmit();
        return handle;
    }

    private SentPacket sendResolved(ProtocolState state, int packetId, byte[] frame, byte[] uncompressed,
            SentPacket... after) {
        if (state == null || frame == null || frame.length < 1) {
            throw new IllegalArgumentException("state/frame");
        }
        if (refused || closed) {
            return new SentPacket();
        }
        RouteV2 epochRoute = routes.routeFor(new RouteKey(protocolNumber, state, sendDirection, packetId));
        if (epochRoute != null && epochRoute.epochChange) {
            return sendEpochTransition(state, packetId, frame, after);
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
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new SentPacket();
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
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new ArrayList<SentPacket>();
        } catch (RuntimeException e) {
            refuse("bundle refused: " + e.getMessage());
            return new ArrayList<SentPacket>();
        }
        afterSubmit();
        return handles;
    }

    /**
     * One covered bundle (U2b, 13.19): resolves each member's route exactly as {@link #sendBundle}
     * does, then hands the snapshotted shape and coverage to the send driver's one-monitor
     * transaction. Caller errors (an invalid token, frame, coverage shape or run constraint) throw
     * before the session is touched; an unresolvable member route fails closed 0x11 like every
     * other unresolved outbound packet.
     */
    @Override
    public List<SentPacket> sendCoveredBundle(OutboundPacket open, List<OutboundPacket> members,
            OutboundPacket close, List<List<UpdateToken>> memberCoverage) {
        if (open == null || members == null || close == null || memberCoverage == null) {
            throw new IllegalArgumentException("bundle coverage");
        }
        if (refused || closed) {
            return new ArrayList<SentPacket>();
        }
        List<OutboundPacket> copy = new ArrayList<OutboundPacket>(members);
        if (memberCoverage.size() != copy.size()) {
            throw new IllegalArgumentException("bundle coverage");
        }
        for (List<UpdateToken> coverage : memberCoverage) {
            if (coverage == null) {
                throw new IllegalArgumentException("bundle coverage");
            }
        }
        int[] ids = new int[copy.size()];
        for (int i = 0; i < ids.length; i++) {
            OutboundPacket member = copy.get(i);
            if (member == null) {
                throw new IllegalArgumentException("bundle member");
            }
            ids[i] = packetIdOf(member.frame(), compressed);
            if (ids[i] < 0) {
                refuse("covered bundle member " + i + " has no readable packet id (13.15)");
                return new ArrayList<SentPacket>();
            }
        }
        SendRouteResolver.BundleScopes resolved = SendRouteResolver.bundleScopes(routes, protocolNumber,
                sendDirection, ProtocolState.PLAY, ids);
        if (resolved.failed()) {
            refuse(resolved.failure());
            return new ArrayList<SentPacket>();
        }
        List<SentPacket> handles;
        try {
            handles = sendDriver.submitCoveredBundle(open, copy, resolved.scopes(), close, memberCoverage);
        } catch (IllegalArgumentException misuse) {
            // A token, frame or run-shape contract violation before assignment: caller error, not a wire refusal.
            throw misuse;
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new ArrayList<SentPacket>();
        } catch (RuntimeException e) {
            refuse("covered bundle refused: " + e.getMessage());
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
        SentPacket handle;
        try {
            handle = sendDriver.submitEnd();
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return;
        }
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
        final long receivedNanos = receiveTrace == null ? 0 : System.nanoTime();
        loop.execute(() -> guarded(() -> {
            if (receiveTrace != null) {
                receiveTrace.ackEventLoopQueue.add(System.nanoTime() - receivedNanos);
                traceAckReceivedNanos = receivedNanos;
                receiveTrace.waits.start(receivedNanos);
                receiveTrace.waits.state(System.nanoTime(), ReceiveTrace.WaitReason.DRIVE_CPU);
            }
            doApplied(lane, sequence);
            traceReceiveState();
        }));
    }

    /** Diagnostic snapshot only; ack-to-next includes idle/input waits, not just scheduler overhead. */
    public void traceReceiveChunkLoadFinished() {
        if (receiveTrace == null) return;
        loop.execute(() -> {
            if (!traceChunkReported) {
                traceChunkReported = true;
                LOG.info("/3 receive trace core phase=chunk-load-finished " + receiveTrace.timingSummary()
                        + " source=" + source.traceSummary());
            }
        });
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
        boolean created;
        try {
            created = sendDriver.createAck(id, payload, chargedBytes);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return false;
        }
        afterSubmit();
        return created;
    }

    @Override
    public void coverAssigned(long ackId, SentPacket covered) {
        try {
            sendDriver.coverAssigned(ackId, covered);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return;
        }
        afterSubmit();
    }

    @Override
    public boolean coverPending(long ackId, long updateToken) {
        boolean covered;
        try {
            covered = sendDriver.coverPending(ackId, updateToken);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return false;
        }
        afterSubmit();
        return covered;
    }

    @Override
    public void assignedPending(long updateToken, SentPacket covered) {
        try {
            sendDriver.assignedPending(updateToken, covered);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return;
        }
        afterSubmit();
    }

    @Override
    public void suppressPending(long updateToken) {
        try {
            sendDriver.suppressPending(updateToken);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return;
        }
        afterSubmit();
    }

    @Override
    public void markInexact(long ackId) {
        try {
            sendDriver.markInexact(ackId);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return;
        }
        afterSubmit();
    }

    @Override
    public SentPacket takeAck(long ackId) {
        SentPacket handle;
        try {
            handle = sendDriver.takeAck(ackId);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new SentPacket();
        }
        afterSubmit();
        return handle;
    }

    @Override
    public void expireAcks() {
        try {
            sendDriver.expireAcks();
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return;
        }
        afterSubmit();
    }

    @Override
    public SentPacket beginBatch(long batchId, OutboundPacket start) {
        SentPacket handle;
        try {
            handle = sendDriver.beginBatch(batchId, start);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new SentPacket();
        }
        afterSubmit();
        return handle;
    }

    @Override
    public SentPacket batchInstall(long batchId, OutboundPacket install, int chunkX, int chunkZ) {
        SentPacket handle;
        try {
            handle = sendDriver.batchInstall(batchId, install, chunkX, chunkZ);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new SentPacket();
        }
        afterSubmit();
        return handle;
    }

    @Override
    public SentPacket finishBatch(long batchId, OutboundPacket join) {
        SentPacket handle;
        try {
            handle = sendDriver.finishBatch(batchId, join);
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new SentPacket();
        }
        afterSubmit();
        return handle;
    }

    // --- Causal scope API (U2, 13.19) ---
    // Each override delegates to the send driver under its monitor, the same place the send path
    // assigns sequences. The session adds no policy.

    @Override
    public CausalScope openScope() {
        return sendDriver.openScope();
    }

    @Override
    public UpdateToken registerUpdate(CausalScope scope) {
        return sendDriver.registerUpdate(scope);
    }

    /**
     * Resolves the packet's pure route first (GLOBAL and globally promoted routes included), then
     * submits it and resolves its token atomically in the driver (U2, 13.19). A token the driver
     * rejects is an argument error and leaves the connection untouched; a route that fails closed
     * refuses the session as every other submission does.
     */
    @Override
    public SentPacket sendCovered(UpdateToken token, ProtocolState state, int packetId, byte[] frame,
            byte[] uncompressedPacket) {
        if (state == null || frame == null || frame.length < 1) {
            throw new IllegalArgumentException("state/frame");
        }
        if (refused || closed) {
            return new SentPacket();
        }
        SendRouteResolver.Resolved resolved = SendRouteResolver.resolve(routes, extractors, protocolNumber,
                sendDirection, state, packetId, uncompressedPacket);
        if (resolved.failed()) {
            refuse("covered packet " + packetId + " in " + state + ": " + resolved.failure());
            return new SentPacket();
        }
        SentPacket handle;
        try {
            handle = sendDriver.submitCovered(token, new OutboundPacket(frame), resolved.fence(),
                    resolved.interaction(), resolved.chunkX(), resolved.chunkZ());
        } catch (IllegalArgumentException misuse) {
            // A token or route-shape contract violation before assignment: caller error, not a wire refusal.
            throw misuse;
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
            return new SentPacket();
        } catch (RuntimeException e) {
            refuse("covered packet " + packetId + " in " + state + " was refused: " + e.getMessage());
            return new SentPacket();
        }
        afterSubmit();
        return handle;
    }

    @Override
    public void suppressUpdate(UpdateToken token) {
        sendDriver.suppressUpdate(token);
    }

    @Override
    public void completeScope(CausalScope scope) {
        sendDriver.completeScope(scope);
    }

    @Override
    public void coverScope(long ackId, CausalScope scope) {
        sendDriver.coverScope(ackId, scope);
    }

    @Override
    public void sealAckCoverage(long ackId) {
        sendDriver.sealAckCoverage(ackId);
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
        if (role == Role.CLIENT) {
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
     * Stream-0 takeover (13.14): every handler the single-stream owner installed is
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
     * One guard copy per CHUNK is charged by ServerReadPartition's fixed 2N+1 admission reservation.
     * Never rearm: reading the first byte must close quiche in this call stack, before Netty can send
     * replenished credit or execute another queued send. There is no reverse preamble (13.13).
     */
    private void reverseChunkOpened(int index, QuicStreamChannel stream) {
        if (reverseGuardSetupForTests != null) {
            reverseGuardSetupForTests.accept(stream);
        }
        stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                try {
                    if (((ByteBuf) message).isReadable() && !closed) {
                        fail(CLOSE_ORDERING, NO_REASON, "byte on reverse CHUNK " + index, null, true);
                    }
                } finally {
                    io.netty.util.ReferenceCountUtil.release(message);
                }
            }
        });
        ReadGrantAllocator.limit(stream, 1);
        stream.read();
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
        if (receiveTrace != null) receiveTrace.waits.state(System.nanoTime(), ReceiveTrace.WaitReason.DRIVE_CPU);
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
        traceReceiveState();
        return !closed;
    }

    private void traceReceiveState() {
        if (receiveTrace == null || clientDriver == null || closed) return;
        StreamReceiver receiver = clientDriver.receiver();
        ReceiveTrace.WaitReason reason;
        if (receiver.nextToEnqueue() >= 0) {
            reason = receiver.slots().workspaceInUse() > 0 ? ReceiveTrace.WaitReason.WORKSPACE
                    : ReceiveTrace.WaitReason.ELIGIBLE_HEAD;
        } else if ((reason = receiver.traceWaitReason()) != null) {
            // Sequence dependencies cannot reveal the sender's fence/join type without speculative decode.
        } else if (source.traceBufferedBytes() > 0) {
            reason = ReceiveTrace.WaitReason.BUFFERED_AWAIT_PUMP;
        } else if (clientPump.traceHasPartialBundle()) {
            reason = ReceiveTrace.WaitReason.BUNDLE_PARTIAL;
        } else if (clientPump.traceHasPartialUnit()) {
            reason = source.traceGrantConsumed(clientPump.tracePartialStreams()) ? ReceiveTrace.WaitReason.READ_GRANT_CONSUMED
                    : ReceiveTrace.WaitReason.PARTIAL_UNIT;
        } else {
            reason = ReceiveTrace.WaitReason.NO_LOCAL_BYTES;
        }
        receiveTrace.waits.state(System.nanoTime(), reason);
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
        } catch (StallClock.UnavailableException e) {
            throw e;
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
        SendTrace trace = sendDriver.trace();
        long beforeWrites = trace == null ? 0 : trace.writeCount();
        if (trace != null) trace.tickStarted(System.nanoTime());
        try {
            tickNow();
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
        } finally {
            if (trace != null) trace.tick(beforeWrites, sendDriver.awaitingGrant());
        }
    }

    private void tickNow() {
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
            streamCapacityMethod = findStreamCapacity(connection.getClass());
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
        fail(code, reason, why, cause, false);
    }

    private void fail(int code, byte[] reason, String why, Throwable cause, boolean immediate) {
        if (closed) {
            return;
        }
        LOG.log(Level.FINE, "closing N+2 connection with 0x{0}: {1}", new Object[] {Integer.toHexString(code), why});
        closed = true;
        // A 0x12 without a classified phrase carries the generic "resource" (13.12).
        final byte[] phrase = reason != null && reason.length > 0 ? reason
                : code == CLOSE_RESOURCE ? SessionReceiveDriver.GENERIC_RESOURCE_REASON.clone() : NO_REASON;
        if (immediate) {
            connection.close(true, code, Unpooled.wrappedBuffer(phrase));
        } else {
            loop.execute(() -> connection.close(true, code, Unpooled.wrappedBuffer(phrase)));
        }
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
        if (sendDriver.trace() != null) {
            sink.traceWritability();
            LOG.info("/3 send trace phase=disconnect tickPeriodMs=" + timing.tickNanos / 1e6
                    + " blockedHeadTimer=deadline-only ackExpiryMs=" + AckJoinTracker.DEADLINE_NANOS / 1e6
                    + " " + sendDriver.trace().summary(System.nanoTime()));
        }
        if (receiveTrace != null) {
            LOG.info("/3 receive trace core phase=disconnect " + receiveTrace.timingSummary()
                    + " source=" + source.traceSummary());
        }
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
        } catch (StallClock.UnavailableException e) {
            closeOnUnavailable(e);
        } catch (Throwable t) {
            failFrom(t);
        }
    }

    /**
     * A cumulative accounting source can no longer describe this session's executor incarnation. That is a local
     * resource failure, not a peer or ordering violation: close 0x12 with the cause, release every charge, and let
     * the caller treat the close as a local resource event (no failure-cache entry).
     */
    private void closeOnUnavailable(StallClock.UnavailableException cause) {
        sendDriver.seal();
        run(() -> fail(CLOSE_RESOURCE, NO_REASON, "stall accounting is unavailable", cause));
    }

    private long now() {
        return resources.clock().getAsLong();
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

    /** Whether every peer preamble this side expects has been read (13.3); call on the loop. */
    boolean peerPreamblesSeen() {
        return preambles.allExpectedSeen();
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
                if (index == LogicalStream.REALTIME || role == Role.CLIENT) {
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
            if (receiveTrace != null) {
                receiveTrace.deliveredOnStream(executingStream);
                receiveTrace.waits.finish(System.nanoTime());
            }
            if (receiveTrace != null && traceAckReceivedNanos != 0) {
                receiveTrace.ackToNextDelivery.add(System.nanoTime() - traceAckReceivedNanos);
                traceAckReceivedNanos = 0;
            }
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
            traceReceiveState();
            schedulePump();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.fireChannelReadComplete();
            traceReceiveState();
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
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            sink.traceWritability();
            ctx.fireChannelWritabilityChanged();
        }

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
