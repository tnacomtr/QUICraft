// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionPathStats;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LaneSession;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.multistream.MultistreamProtocolException;
import rs.sudoe.quicraft.core.multistream.PreambleV2;
import rs.sudoe.quicraft.core.routing.RoutingTable;

/**
 * {@link V3Connection} over one Netty QUIC connection, its stream 0 and one {@link LaneSession}.
 *
 * <p>Stream 0 is the game's byte stream until {@link #activate}: the peer's {@link PreambleV2} is
 * read and stripped here, and nothing reaches the game until the gate opens (after the handshake,
 * or at once for 0-RTT data whose token redeemed, docs/protocol.md §8). The 0-RTT client half of
 * §8 lives here too, exactly as it does for {@code /2}: the first flight sent as early data is
 * compared byte for byte, a mismatch abandons the connection, and otherwise the next flight is
 * recorded for the following join.
 *
 * <p>This class is stream 0's own preamble handler rather than a {@link PreambleStream}: {@code /3}
 * uses {@link PreambleV2} (version 2), which the v1 {@code Preamble} parser and encoder reject.
 *
 * <p>At activation the {@link LaneSession} installs its handler first in stream 0's pipeline, so
 * this class stops seeing stream-0 bytes; until then {@link #acceptPeerStream} keeps a peer stream
 * that may not be delivered yet (the client's WORLD stream, 13.3).
 */
public final class V3ConnectionImpl implements V3Connection, QuicByteStream.Listener {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    /** 12.14/12.7: binding or activation failure, including a missing routing table (12.3). */
    static final int CLOSE_ACTIVATION_FAILED = 0x10;

    /** On the event loop, once the peer's {@code /3} preamble is complete. {@code token} may be null. */
    interface PreambleHandler {
        void onPreamble(V3ConnectionImpl connection, byte[] token);
    }

    private final QuicChannel connection;
    private final QuicStreamChannel control;
    private final NettyQuicByteStream raw;
    private final LaneSession.Role role;
    private final PreambleV2.Parser parser = new PreambleV2.Parser();

    // Client role (null on a server).
    private final EarlyCache cache;
    private final List<Object> serverKey;
    private final String joinInputs;
    private final byte[] sentEarly;
    private final Object writeLock = new Object();
    /** Guarded by writeLock. */
    private int matched;
    /** Guarded by writeLock; null when not recording. */
    private FlightRecorder recorder;
    private volatile boolean mismatched;
    private volatile Runnable onMismatch;
    /** Event loop only: runs once the server's preamble is complete; null when none waits. */
    private Runnable onPreambleRead;

    // Server role (null on a client).
    private final V3Listener listener;
    private final PreambleHandler preambleHandler;
    private FlightRecorder earlyName;
    private java.util.function.Consumer<String> nameCallback;

    private volatile RoutingTable table;
    private volatile LaneSession session;
    private volatile boolean handshakeComplete;
    private volatile Listener game;
    private volatile boolean gameWrote;
    private volatile boolean detached;

    // Event loop only.
    private final ArrayDeque<ByteBuffer> kept = new ArrayDeque<ByteBuffer>();
    /** Bytes in {@link #kept}. */
    private int keptBytes;
    /** A peer stream seen before the session exists: the client's WORLD stream (13.3). */
    private final List<QuicStreamChannel> early = new ArrayList<QuicStreamChannel>(1);
    private boolean gateOpen;
    private boolean autoRead;
    private boolean readRequested;
    private boolean rawClosed;
    private Throwable rawCloseCause;
    private boolean closeDelivered;
    private boolean discarding;
    private volatile boolean activated;

    /** The client's side: the routing table comes from the dial and a token is stored for it. */
    static V3ConnectionImpl client(QuicChannel connection, QuicStreamChannel control, NettyQuicByteStream raw,
            EarlyCache cache, List<Object> serverKey, String joinInputs, byte[] sentEarly, RoutingTable table,
            boolean handshakeComplete) {
        return new V3ConnectionImpl(connection, control, raw, LaneSession.Role.CLIENT, table, cache, serverKey,
                joinInputs, sentEarly, null, null, handshakeComplete);
    }

    /** The server's side: the routing table is resolved at activation from {@code listener}. */
    static V3ConnectionImpl server(QuicChannel connection, QuicStreamChannel control, NettyQuicByteStream raw,
            V3Listener listener, PreambleHandler handler, boolean handshakeComplete, ReceiveBudget node) {
        V3ConnectionImpl impl = new V3ConnectionImpl(connection, control, raw, LaneSession.Role.SERVER, null, null,
                null, null, null, listener, handler, handshakeComplete);
        impl.node = node == null ? ReceiveBudget.unlimited() : node;
        return impl;
    }

    /** The node's shared receive budget the lane session reserves against (13.12). */
    private ReceiveBudget node = ReceiveBudget.unlimited();

    private V3ConnectionImpl(QuicChannel connection, QuicStreamChannel control, NettyQuicByteStream raw,
            LaneSession.Role role, RoutingTable table, EarlyCache cache, List<Object> serverKey, String joinInputs,
            byte[] sentEarly, V3Listener listener, PreambleHandler preambleHandler, boolean handshakeComplete) {
        if (connection == null || control == null || raw == null || role == null) {
            throw new IllegalArgumentException("null argument");
        }
        if (control.streamId() != 0) {
            throw new IllegalArgumentException("CONTROL must be stream 0");
        }
        this.connection = connection;
        this.control = control;
        this.raw = raw;
        this.role = role;
        this.table = table;
        this.cache = cache;
        this.serverKey = serverKey;
        this.joinInputs = joinInputs;
        this.sentEarly = sentEarly;
        this.listener = listener;
        this.preambleHandler = preambleHandler;
        this.handshakeComplete = handshakeComplete;
        if (role == LaneSession.Role.CLIENT) {
            if (sentEarly != null) {
                gameWrote = true; // the server may act on the 0-RTT flight
            } else if (joinInputs != null) {
                recorder = new FlightRecorder();
            }
        }
        // First in the connection's pipeline: this connection is handed to the game from another
        // connection handler (QuicServer.Connection), and it must already know the handshake is
        // complete when the game sees it there (12.7).
        connection.pipeline().addFirst(new HandshakeWatch());
    }

    /** Starts reading the peer's preamble. On the event loop. */
    void start() {
        raw.setListener(this);
        raw.setAutoRead(true);
    }

    /** On the event loop: writes this side's v2 preamble with a fresh token (null for none). */
    void writePreamble(byte[] token) {
        raw.write(ByteBuffer.wrap(PreambleV2.encode(token)));
        raw.flush();
    }

    /** On the event loop: report the Login Start's player name once the first flight is in. */
    void readEarlyName(java.util.function.Consumer<String> onName) {
        this.earlyName = new FlightRecorder();
        this.nameCallback = onName;
    }

    /** Lets the kept data and everything after it through to the game (any thread). */
    void openGate() {
        run(new Runnable() {
            @Override
            public void run() {
                gateOpen = true;
                drain();
            }
        });
    }

    /** Whether the peer's preamble is complete; on the event loop. */
    boolean preambleRead() {
        return parser.done();
    }

    /** On the event loop: runs {@code task} once the server's preamble is read, at once if it is. */
    void whenPreambleRead(Runnable task) {
        if (parser.done()) {
            task.run();
        } else {
            onPreambleRead = task;
        }
    }

    /**
     * Hands a peer-created stream other than stream 0 to the session (13.3). Before this side
     * activates there is no session: a server must treat any such stream as fatal {@code 0x11},
     * while a client keeps the server's WORLD stream unread and hands it over when the session
     * appears (13.3 lets the client see stream 1 before it validates SERVER_SWITCH). Called from
     * the stream handler's {@code initChannel}, on the event loop.
     */
    void acceptPeerStream(QuicStreamChannel stream) {
        LaneSession current = session;
        if (current != null) {
            current.acceptStream(stream);
            return;
        }
        stream.config().setAutoRead(false);
        if (role == LaneSession.Role.SERVER) {
            // 13.3: a client stream before the server's own activation is fatal.
            Codecs.closeLater(connection, true, MultistreamProtocolException.CLOSE_ORDERING);
            return;
        }
        early.add(stream);
    }

    /** The Netty stream of the connection, for the package's own code such as {@link TlsExporter}. */
    NettyQuicByteStream rawStream() {
        return raw;
    }

    // --- V3Connection.

    @Override
    public byte[] exporterValue() {
        if (!raw.inEventLoop()) {
            throw new IllegalStateException("the TLS exporter must run on the connection's event loop");
        }
        byte[] value = TlsExporter.bindingValue(this);
        if (value == null) {
            throw new IllegalStateException("no TLS exporter value: the handshake is not complete, the "
                    + "connection is closed, or the native exporter is unavailable (12.3, 12.7)");
        }
        return value;
    }

    @Override
    public long smoothedRttMillis() {
        if (!connection.eventLoop().inEventLoop()) {
            return -1;
        }
        try {
            Promise<QuicConnectionPathStats> promise = connection.eventLoop().newPromise();
            connection.collectPathStats(0, promise);
            if (!promise.isSuccess()) {
                return -1;
            }
            long nanos = promise.getNow().rtt();
            return nanos <= 0 ? -1 : nanos / 1_000_000L;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "reading the QUIC path statistics failed", t);
            return -1;
        }
    }

    @Override
    public void detachForActivation(final Consumer<ByteBuffer> leftover) {
        if (leftover == null) {
            throw new NullPointerException("leftover");
        }
        run(new Runnable() {
            @Override
            public void run() {
                doDetach(leftover);
            }
        });
    }

    @Override
    public void activate(final LaneGame game, ByteBuffer controlLeftover) {
        activate(game, -1, controlLeftover);
    }

    @Override
    public void activate(final LaneGame game, final int protocolNumber, final ByteBuffer controlLeftover) {
        if (game == null) {
            throw new NullPointerException("game");
        }
        final ByteBuffer leftover = controlLeftover == null ? null : controlLeftover.duplicate();
        run(new Runnable() {
            @Override
            public void run() {
                doActivate(game, protocolNumber, leftover);
            }
        });
    }

    @Override
    public LaneSession session() {
        return session;
    }

    @Override
    public void end() {
        LaneSession current = session;
        if (current != null) {
            current.end();
        } else {
            close();
        }
    }

    @Override
    public void abort(int code) {
        LaneSession current = session;
        if (current != null) {
            current.abort(code);
        } else {
            Codecs.closeLater(connection, true, code);
        }
    }

    @Override
    public String alpn() {
        return Protocol.ALPN_V3;
    }

    @Override
    public boolean handshakeComplete() {
        return handshakeComplete;
    }

    // --- QuicByteStream (stream 0 as the game sees it).

    @Override
    public InetSocketAddress remoteAddress() {
        return raw.remoteAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        return raw.localAddress();
    }

    @Override
    public void setListener(Listener listener) {
        this.game = listener;
        run(new Runnable() {
            @Override
            public void run() {
                drain();
            }
        });
    }

    @Override
    public void setAutoRead(final boolean autoRead) {
        run(new Runnable() {
            @Override
            public void run() {
                V3ConnectionImpl.this.autoRead = autoRead;
                drain();
            }
        });
    }

    @Override
    public void read() {
        run(new Runnable() {
            @Override
            public void run() {
                readRequested = true;
                drain();
            }
        });
    }

    @Override
    public void write(ByteBuffer data) {
        if (activated) {
            // 12.11/12.12: after the switch, game packets must go through the session.
            throw new IllegalStateException("Game writes after /3 activation must use the session");
        }
        gameWrote = true;
        if (role == LaneSession.Role.CLIENT) {
            clientWrite(data);
        } else {
            raw.write(data);
        }
    }

    @Override
    public void flush() {
        raw.flush();
    }

    @Override
    public boolean isWritable() {
        return raw.isWritable();
    }

    @Override
    public boolean isOpen() {
        return raw.isOpen();
    }

    /**
     * Before activation this is stream 0's close. After activation a bare FIN is fatal (13.14), so
     * the caller must use {@link #end()}; this closes through the session then.
     */
    @Override
    public void close() {
        LaneSession current = session;
        if (current != null) {
            current.end();
            return;
        }
        if (gameWrote) {
            raw.close();
        } else {
            raw.markClosing();
            Codecs.closeLater(connection, true, Protocol.CLOSE_NORMAL);
        }
    }

    @Override
    public CompletableFuture<Map<String, Long>> connectionStats() {
        return raw.connectionStats();
    }

    @Override
    public void execute(Runnable task) {
        raw.execute(task);
    }

    @Override
    public boolean inEventLoop() {
        return raw.inEventLoop();
    }

    // --- Listener on the underlying stream (event loop).

    @Override
    public void onData(ByteBuffer data) {
        if (!parser.done()) {
            boolean complete;
            try {
                complete = parser.feed(data);
            } catch (MultistreamProtocolException e) {
                onInvalidPreamble(e);
                return;
            }
            if (!complete) {
                return;
            }
            if (!gateOpen) {
                raw.setAutoRead(false);
            }
            onPreamble(parser.token());
        }
        if (!data.hasRemaining() || discarding) {
            return;
        }
        onPeerData(data);
        Listener l = game;
        if (!detached && gateOpen && kept.isEmpty() && l != null) {
            l.onData(data);
        } else {
            keep(data);
        }
    }

    /**
     * Keeps bytes for the game or for the session's activation leftover. After the detach stream 0
     * is no longer read, so only a read already in flight can still land here, one 16 KiB chunk. The
     * bytes later become the session's admitted overdraft (13.12), so they are bounded here: more
     * than {@link LaneSession#MAX_KEPT_AFTER_DETACH} can only mean a local read-control fault, and
     * the connection closes with the internal-error code, never 0x11, because the peer cannot make
     * this larger.
     */
    private void keep(ByteBuffer data) {
        if (detached && keptBytes + data.remaining() > LaneSession.MAX_KEPT_AFTER_DETACH) {
            LOG.log(Level.FINE, "stream 0 delivered more than {0} bytes after the detach; closing",
                    LaneSession.MAX_KEPT_AFTER_DETACH);
            discarding = true;
            kept.clear();
            keptBytes = 0;
            Codecs.closeLater(connection, true, Protocol.CLOSE_INTERNAL_ERROR);
            return;
        }
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data).flip();
        keptBytes += copy.remaining();
        kept.add(copy);
    }

    @Override
    public void onWritabilityChanged(boolean writable) {
        Listener l = game;
        if (l != null) {
            l.onWritabilityChanged(writable);
        }
    }

    @Override
    public void onClosed(Throwable cause) {
        rawClosed = true;
        rawCloseCause = cause;
        if (!gateOpen && role == LaneSession.Role.CLIENT && sentEarly == null) {
            // Without 0-RTT data the gate waits only for the server's preamble: if the connection
            // ends first, the game must still see the close.
            openGate();
        }
        drain();
    }

    // --- Preamble handling.

    private void onPreamble(byte[] token) {
        if (role == LaneSession.Role.CLIENT) {
            if (token != null) {
                cache.putToken(serverKey, token);
            }
            if (sentEarly == null) {
                openGate();
            }
            Runnable task = onPreambleRead;
            if (task != null) {
                onPreambleRead = null;
                task.run();
            }
        } else {
            preambleHandler.onPreamble(this, token);
        }
    }

    private void onInvalidPreamble(MultistreamProtocolException e) {
        LOG.log(Level.FINE, "invalid /3 preamble; closing", e);
        Codecs.closeLater(connection, true, MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION);
    }

    /** On the event loop: the server's early-name recorder, if one is set. */
    private void onPeerData(ByteBuffer data) {
        if (role != LaneSession.Role.SERVER) {
            return;
        }
        FlightRecorder r = earlyName;
        if (r == null) {
            return;
        }
        int state = r.add(data);
        if (state == FlightRecorder.MORE) {
            return;
        }
        String name = state == FlightRecorder.DONE ? FirstFlight.playerName(r.flight()) : null;
        earlyName = null;
        java.util.function.Consumer<String> callback = nameCallback;
        nameCallback = null;
        if (name != null && callback != null) {
            callback.accept(name);
        }
    }

    // --- Gate.

    private void drain() {
        if (!gateOpen) {
            return;
        }
        Listener l = game;
        if (l == null) {
            return;
        }
        while (!kept.isEmpty() && (autoRead || readRequested)) {
            readRequested = false;
            ByteBuffer next = kept.poll();
            keptBytes -= next.remaining();
            try {
                l.onData(next);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "game onData failed", t);
            }
        }
        if (!kept.isEmpty()) {
            return;
        }
        if (rawClosed) {
            if (!closeDelivered) {
                closeDelivered = true;
                l.onClosed(rawCloseCause);
            }
            return;
        }
        if (detached) {
            return;
        }
        raw.setAutoRead(autoRead);
        if (readRequested && !autoRead) {
            readRequested = false;
            raw.read();
        }
    }

    private void discardKeptAndOpenGate() {
        run(new Runnable() {
            @Override
            public void run() {
                discarding = true;
                kept.clear();
                keptBytes = 0;
                gateOpen = true;
                drain();
            }
        });
    }

    // --- Client 0-RTT (docs/protocol.md §8), as for /2.

    /** The game's first flight differs from the one sent as 0-RTT data: the platform retries TCP. */
    public void onFirstFlightMismatch(Runnable handler) {
        this.onMismatch = handler;
        if (mismatched) {
            handler.run();
        }
    }

    /** Whether this client connection's first flight went out as 0-RTT data. */
    public boolean sentEarlyData() {
        return sentEarly != null;
    }

    private void clientWrite(ByteBuffer data) {
        boolean open = false;
        boolean mismatchNow = false;
        synchronized (writeLock) {
            if (mismatched) {
                return;
            }
            if (sentEarly != null && matched < sentEarly.length) {
                int n = Math.min(data.remaining(), sentEarly.length - matched);
                boolean same;
                try {
                    Faults.check(Faults.EARLY_COMPARE);
                    same = equal(data, sentEarly, matched, n);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "comparing the first flight failed; treating it as a mismatch", t);
                    same = false;
                }
                if (same) {
                    data.position(data.position() + n);
                    matched += n;
                    open = matched == sentEarly.length;
                } else {
                    mismatched = true;
                    mismatchNow = true;
                }
            }
            if (!mismatchNow && recorder != null) {
                try {
                    Faults.check(Faults.EARLY_RECORD);
                    int state = recorder.add(data);
                    if (state == FlightRecorder.DONE) {
                        cache.putFlight(serverKey, joinInputs, recorder.flight());
                    }
                    if (state != FlightRecorder.MORE) {
                        recorder = null;
                    }
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "recording the first flight failed; no 0-RTT next time", t);
                    recorder = null;
                }
            }
        }
        if (mismatchNow) {
            mismatch();
            return;
        }
        if (open) {
            openGate();
        }
        if (data.hasRemaining()) {
            raw.write(data);
        }
    }

    private void mismatch() {
        LOG.info("QUICraft: the game's first packets differ from the 0-RTT data sent ahead of them; "
                + "closing QUIC and joining again over TCP");
        cache.forget(serverKey);
        discardKeptAndOpenGate(); // the server's answer was to the other packets: never shown
        Codecs.closeLater(connection, true, Protocol.CLOSE_NORMAL);
        Runnable handler = onMismatch;
        if (handler != null) {
            handler.run();
        }
    }

    private static boolean equal(ByteBuffer data, byte[] expected, int offset, int n) {
        int p = data.position();
        for (int i = 0; i < n; i++) {
            if (data.get(p + i) != expected[offset + i]) {
                return false;
            }
        }
        return true;
    }

    // --- Activation.

    private void doDetach(Consumer<ByteBuffer> sink) {
        if (detached) {
            return;
        }
        detached = true;
        raw.setAutoRead(false);
        if (sink == null) {
            return;
        }
        ByteBuffer b;
        while ((b = kept.poll()) != null) {
            keptBytes -= b.remaining();
            try {
                sink.accept(b);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "the activation leftover sink failed", t);
            }
        }
    }

    private void doActivate(LaneGame game, int protocolNumber, ByteBuffer controlLeftover) {
        if (activated || rawClosed) {
            return;
        }
        if (role == LaneSession.Role.SERVER && !handshakeComplete) {
            // 12.7 (user decision 2026-10-16): the server enters configuration only after processing
            // a 1-RTT packet, so configuration-stage messages prove its own handshake is complete. It
            // must refuse to start binding (0x10) before that, whatever the game asks.
            failActivation("the server's own TLS handshake is not complete yet (12.7)");
            return;
        }
        doDetach(null); // implicit detach keeps whatever was buffered for the session below
        RoutingTable resolved = table;
        if (resolved == null) {
            if (listener == null) {
                failActivation("no routing table and no listener");
                return;
            }
            resolved = listener.tableFor(protocolNumber);
            if (resolved == null) {
                failActivation("the listener has no routing table for protocol " + protocolNumber + " (12.3, 13.1)");
                return;
            }
            table = resolved;
        }
        ByteBuffer leftover = combine(controlLeftover, kept);
        kept.clear();
        keptBytes = 0;
        // The session installs itself first in stream 0's pipeline and drives reads itself.
        LaneSession started = new LaneSession(connection, role, control, resolved, game, node);
        session = started;
        activated = true;
        for (QuicStreamChannel stream : early) {
            started.acceptStream(stream);
        }
        early.clear();
        started.activate(leftover);
    }

    /** Concatenates the application's leftover and what core read after the detach; null for none. */
    private static ByteBuffer combine(ByteBuffer first, ArrayDeque<ByteBuffer> rest) {
        int size = first == null ? 0 : first.remaining();
        for (ByteBuffer buffer : rest) {
            size += buffer.remaining();
        }
        if (size == 0) {
            return null;
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        if (first != null) {
            out.put(first.duplicate());
        }
        for (ByteBuffer buffer : rest) {
            out.put(buffer);
        }
        out.flip();
        return out;
    }

    private void failActivation(String why) {
        LOG.warning("QUICraft: /3 activation failed closed: " + why);
        abort(CLOSE_ACTIVATION_FAILED);
    }

    // --- Plumbing.

    private void run(Runnable task) {
        if (raw.inEventLoop()) {
            guarded(task);
        } else {
            raw.execute(new Runnable() {
                @Override
                public void run() {
                    guarded(task);
                }
            });
        }
    }

    private void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            LOG.log(Level.FINE, "a /3 connection task failed", t);
            Codecs.closeLater(connection, true, CLOSE_ACTIVATION_FAILED);
        }
    }

    /** Marks this connection's own TLS handshake complete (12.7). */
    private final class HandshakeWatch extends ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof SslHandshakeCompletionEvent && ((SslHandshakeCompletionEvent) evt).isSuccess()) {
                handshakeComplete = true;
            }
            ctx.fireUserEventTriggered(evt);
        }
    }
}
