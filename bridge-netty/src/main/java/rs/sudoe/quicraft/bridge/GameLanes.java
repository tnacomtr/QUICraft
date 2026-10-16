// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LaneSession;
import rs.sudoe.quicraft.core.multistream.SentPacket;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The game's side of a {@code /3} connection: the {@link LaneGame} of
 * {@link rs.sudoe.quicraft.core.multistream.LaneSession} for a game pipeline, over the bridge
 * channel the connection runs on. No Minecraft classes: what a game packet is, how it is encoded
 * and where it routes are the platform's, behind {@link Codec}.
 *
 * <p><b>Inbound.</b> Every delivery is fired into the game pipeline on the game channel's event
 * loop, in the order the session delivered it, followed by one {@link Ack} naming the lane and the
 * final sequence. A game that applies packets on another thread (its main thread) hops there itself
 * and calls {@link #applied(Lane, long)} once its serial handler has applied them, which is what
 * 13.10 requires the acknowledgement to follow.</p>
 *
 * <p><b>Outbound.</b> {@link #installOutbound()} adds one handler at the tail of the game pipeline,
 * so it sees every packet the game writes before the game's own encoder does. Each packet is asked
 * for its routing answer ({@link Codec#routeOf}), the answer is validated against the routing table
 * ({@link LaneSession#outboundLane}) <em>before</em> the session is handed anything, then encoded by
 * the platform and submitted through the session, which assigns the sequence (13.6, 13.9). Nothing
 * the game writes reaches stream 0 afterwards. A lane the table disagrees with is a routing bug and
 * fails the connection with {@code 0x11} without a byte reaching the wire.</p>
 *
 * <p><b>Close and backpressure.</b> {@link LaneGame#onWritabilityChanged(boolean)} fires
 * {@code channelWritabilityChanged} on the game channel and is what {@link #isWritable()} answers;
 * the game channel's own {@code isWritable()} stays true, because the game's writes never enter its
 * outbound buffer. {@link LaneGame#onClosed(int, boolean, Throwable)} closes the game channel with
 * an exception whose message names the QUIC application error code, so the game's disconnect
 * handling sees why (13.14).</p>
 *
 * <p>Threading: the session's callbacks arrive on the connection's event loop, which for the
 * hosted transport is the game channel's own (CLAUDE.md "Architecture"). A delivery from another
 * loop is queued and fired on the game channel's loop in arrival order.</p>
 */
public final class GameLanes implements LaneGame {
    /**
     * What the platform must supply: its packet codec and its outbound routing knowledge. Every
     * method runs on the connection's event loop.
     */
    public interface Codec {
        /**
         * Decodes one GAME body (13.4) into a packet object. Exactly one Minecraft VarInt-length
         * frame, including the packet id and the negotiated compression wrapper, without CFB8.
         *
         * @param frame the body, valid only during the call: the codec must not keep or release it
         * @param state the protocol state this direction was activated in; 13.16 makes the game's
         *     own state machine authoritative, so a codec need not use it
         * @param reservation the receive budget of this connection (13.12). Charge decoded output
         *     through it before allocating anything: an over-budget reservation throws, which fails
         *     the connection with {@code 0x11} while the allocation has not happened yet. A codec
         *     that decodes without reserving reports the sizes in {@link Decoded} instead, and the
         *     session charges them after the fact
         */
        LaneGame.Decoded decode(ByteBuf frame, ProtocolState state, LaneGame.Reservation reservation)
                throws Exception;

        /**
         * Appends one outbound packet's Minecraft frame (VarInt length, packet id, negotiated
         * compression wrapper) to {@code out}, exactly as the game's own encoder would.
         */
        void encode(Object packet, ByteBuf out) throws Exception;

        /** The routing answer for one outbound packet (13.6): never null for a packet the game sends. */
        Route routeOf(Object packet);
    }

    /** One outbound packet's routing answer, as the game's own tables give it (13.6). */
    public static final class Route {
        private final ProtocolState state;
        private final int packetId;
        private final Lane lane;

        public Route(ProtocolState state, int packetId, Lane lane) {
            if (state == null || lane == null) {
                throw new IllegalArgumentException("state/lane");
            }
            this.state = state;
            this.packetId = packetId;
            this.lane = lane;
        }

        /** The protocol state the packet is sent in, the one the routing lookup uses. */
        public ProtocolState state() {
            return state;
        }

        /** The packet's numeric id, the other half of the routing lookup. */
        public int packetId() {
            return packetId;
        }

        /** The lane the game believes the packet routes to; the session assigns the authoritative one. */
        public Lane lane() {
            return lane;
        }
    }

    /**
     * Fired into the game pipeline right behind a delivery's packets: the lane and final sequence
     * the game must acknowledge with {@link #applied(Lane, long)} once it has applied them (13.10).
     */
    public static final class Ack {
        private final Lane lane;
        private final long sequence;
        private final boolean bundle;

        Ack(Lane lane, long sequence, boolean bundle) {
            this.lane = lane;
            this.sequence = sequence;
            this.bundle = bundle;
        }

        public Lane lane() {
            return lane;
        }

        /** The final sequence of the delivery, never an intermediate bundle member (13.15). */
        public long sequence() {
            return sequence;
        }

        public boolean bundle() {
            return bundle;
        }
    }

    /** The cause the game channel is closed with when the multi-stream connection ends (13.14). */
    public static final class LaneClosedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int code;
        private final boolean byPeer;

        LaneClosedException(int code, boolean byPeer, Throwable cause) {
            super(describe(code, byPeer), cause);
            this.code = code;
            this.byPeer = byPeer;
        }

        /** The QUIC application error code the session reported, or -1 when there was none. */
        public int code() {
            return code;
        }

        /** True when the peer closed first. */
        public boolean byPeer() {
            return byPeer;
        }

        private static String describe(int code, boolean byPeer) {
            String hex = code < 0 ? "no application error code" : String.format("0x%x", Integer.valueOf(code));
            if (code == LaneSession.CLOSE_ORDERING) {
                return "the multi-stream connection closed with 0x11 (ordering or resource failure)"
                        + (byPeer ? ", by the peer" : "");
            }
            return "the multi-stream connection closed with " + hex + (byPeer ? ", by the peer" : "");
        }
    }

    /** The one place this class talks to the session; {@link LaneSessionSink} is production. */
    interface Session {
        /**
         * The lane the routing table assigns to one outbound packet (13.6), without submitting it:
         * the check a game's own routing answer must pass before the packet is sequenced.
         */
        Lane outboundLane(ProtocolState state, int packetId);

        /** Submits one outbound GAME packet and answers the lane the session assigned, or null. */
        Lane send(ProtocolState state, int packetId, byte[] frame);

        void applied(Lane lane, long sequence);

        void abort(int code);
    }

    private final Channel game;
    private final Codec codec;
    /**
     * The session, resolved on first use: the platform builds this object before activation, and the
     * session is what activation creates (12.11, 12.12). {@link V3ConnectionImpl} assigns its own
     * session field before it runs the activation task, so a delivery the activation itself causes
     * already finds it here.
     */
    private final Supplier<Session> sessionSource;
    /** The state every inbound decode is handed; the codec's own state machine governs after that. */
    private final ProtocolState state;
    /** Where decoded packets enter the game pipeline, or null for the pipeline's head. */
    private final ChannelHandlerContext deliverFrom;
    /**
     * Guarded by itself: deliveries waiting to be fired on the game channel's loop, in arrival
     * order. One queue for every delivery, whether it arrived on that loop or not, so a delivery
     * from another thread can never be overtaken (13.10).
     */
    private final ArrayDeque<Delivery> queued = new ArrayDeque<Delivery>();
    private boolean draining;
    private volatile boolean writable = true;
    private volatile boolean closed;
    private volatile Session session;

    /**
     * Production: a game pipeline over the {@link LaneSession} that activation creates. Build it
     * before calling
     * {@link rs.sudoe.quicraft.core.transport.V3Connection#activate(LaneGame, int, java.nio.ByteBuffer)}
     * and pass {@code connection::session} as the source, so the session is picked up exactly when it
     * exists: there is no construction cycle, and the first callback (which activation can cause by
     * delivering the {@code controlLeftover} records) already has one.
     *
     * @param game the bridge channel the game's pipeline runs on, already the connection's transport
     * @param codec the platform's packet codec
     * @param session the connection's session, e.g. {@code connection::session}; must answer a
     *     non-null session by the time the first callback or write happens
     * @param state the protocol state of the activation (12.4: configuration)
     * @param deliverFrom where decoded packets enter the game pipeline, or null for its head
     */
    public GameLanes(Channel game, Codec codec, Supplier<LaneSession> session, ProtocolState state,
                     ChannelHandlerContext deliverFrom) {
        this(game, codec, state, deliverFrom, laneSessionSource(session));
    }

    /** The test seam: the session as an interface supplied lazily, so no QUIC connection is needed. */
    static GameLanes forTest(Channel game, Codec codec, Session session, ProtocolState state,
            ChannelHandlerContext deliverFrom) {
        return new GameLanes(game, codec, state, deliverFrom, () -> session);
    }

    /** Internal, with the session last so it does not clash with the public constructor's erasure. */
    private GameLanes(Channel game, Codec codec, ProtocolState state,
                      ChannelHandlerContext deliverFrom, Supplier<Session> session) {
        if (game == null || codec == null || session == null || state == null) {
            throw new IllegalArgumentException("game, codec, session and state are all required");
        }
        this.game = game;
        this.codec = codec;
        this.sessionSource = session;
        this.state = state;
        this.deliverFrom = deliverFrom;
    }

    private static Supplier<Session> laneSessionSource(final Supplier<LaneSession> sessions) {
        if (sessions == null) {
            throw new IllegalArgumentException("session");
        }
        return new Supplier<Session>() {
            @Override
            public Session get() {
                LaneSession session = sessions.get();
                return session == null ? null : new LaneSessionSink(session);
            }
        };
    }

    /**
     * The session, or an exception when it does not exist yet. Only a caller that writes or acts
     * before activation can reach this: every {@link LaneGame} callback comes from the session.
     */
    private Session session() {
        Session current = session;
        if (current != null) {
            return current;
        }
        Session resolved = sessionSource.get();
        if (resolved == null) {
            throw new IllegalStateException("the lane session does not exist yet (12.11, 12.12)");
        }
        session = resolved;
        return resolved;
    }

    /**
     * Adds the outbound handler at the tail of the game pipeline (13.6): from here on, every packet
     * the game writes goes through the session instead of stream 0. Call it on the game channel's
     * event loop, from the activation point on, once and once only.
     */
    public void installOutbound() {
        game.pipeline().addLast(new Outbound());
    }

    /** True while the session's unsent queue is below its backpressure mark (13.12). */
    public boolean isWritable() {
        return writable;
    }

    /** True once the session reported the connection closed. */
    public boolean isClosed() {
        return closed;
    }

    /** The game channel this connection's pipeline runs on. */
    public Channel channel() {
        return game;
    }

    // --- LaneGame, inbound.

    @Override
    public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle,
            Reservation reservation) throws Exception {
        ByteBuf body = Unpooled.wrappedBuffer(frame);
        try {
            // 13.12: the platform charges decoded output before it allocates it, so an over-budget
            // packet is refused before any decompression or object is created.
            Decoded decoded = codec.decode(body, state, reservation);
            if (decoded == null) {
                throw new IllegalStateException("the codec decoded nothing for " + lane + " " + sequence);
            }
            return decoded;
        } finally {
            body.release();
        }
    }

    @Override
    public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {
        if (lane == null || packets == null || packets.isEmpty()) {
            throw new IllegalArgumentException("lane/packets");
        }
        Delivery delivery = new Delivery(lane, sequence, new ArrayList<Object>(packets), bundle);
        EventLoop loop = game.eventLoop();
        boolean drainNow = false;
        synchronized (queued) {
            // One queue for every delivery: a delivery that arrives on the loop goes behind what
            // another thread queued before it, and fires in that order (13.10).
            queued.add(delivery);
            if (!draining) {
                draining = true;
                drainNow = true;
            }
        }
        if (!drainNow) {
            return;
        }
        if (loop.inEventLoop()) {
            drain();
        } else {
            loop.execute(this::drain);
        }
    }

    /** Event loop only: fires what another thread queued, in arrival order. */
    private void drain() {
        while (true) {
            Delivery next;
            synchronized (queued) {
                next = queued.poll();
                if (next == null) {
                    draining = false;
                    return;
                }
            }
            fire(next);
        }
    }

    /** Event loop only. */
    private void fire(Delivery delivery) {
        try {
            for (Object packet : delivery.packets) {
                if (deliverFrom != null) {
                    deliverFrom.fireChannelRead(packet);
                } else {
                    game.pipeline().fireChannelRead(packet);
                }
            }
            Ack ack = new Ack(delivery.lane, delivery.sequence, delivery.bundle);
            if (deliverFrom != null) {
                deliverFrom.fireChannelRead(ack);
            } else {
                game.pipeline().fireChannelRead(ack);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * The game's serial handler applied a delivery: acknowledge it, so the session decodes on
     * (13.10). From any thread, exactly once per {@link Ack}, with that ack's lane and sequence.
     */
    public void applied(Lane lane, long sequence) {
        if (lane == null) {
            throw new IllegalArgumentException("lane");
        }
        session().applied(lane, sequence);
    }

    // --- LaneGame, backpressure and close.

    @Override
    public void onWritabilityChanged(boolean writable) {
        this.writable = writable;
        EventLoop loop = game.eventLoop();
        if (loop.inEventLoop()) {
            fireWritabilityChanged();
        } else {
            loop.execute(this::fireWritabilityChanged);
        }
    }

    /** Event loop only. */
    private void fireWritabilityChanged() {
        try {
            game.pipeline().fireChannelWritabilityChanged();
        } catch (Throwable t) {
            fail(t);
        }
    }

    @Override
    public void onClosed(int code, boolean byPeer, Throwable cause) {
        closed = true;
        EventLoop loop = game.eventLoop();
        if (loop.inEventLoop()) {
            closeGameChannel(code, byPeer, cause);
        } else {
            loop.execute(() -> closeGameChannel(code, byPeer, cause));
        }
    }

    /** Event loop only: the game channel goes away, with a cause that names the code (13.14). */
    private void closeGameChannel(int code, boolean byPeer, Throwable cause) {
        try {
            if (code != LaneSession.CLOSE_NORMAL) {
                // The game's disconnect handling sees why, in the exception's own message.
                game.pipeline().fireExceptionCaught(new LaneClosedException(code, byPeer, cause));
            }
        } catch (Throwable ignored) {
            // Closing is what matters; a handler that throws here must not prevent it.
        }
        game.close();
    }

    // --- Failures.

    /** Fails the whole connection after a local fault and lets the game see it. */
    private void fail(Throwable t) {
        try {
            game.pipeline().fireExceptionCaught(t);
        } catch (Throwable ignored) {
            // The abort below is the answer that counts.
        }
        try {
            // 13.6: a lane the routing table disagrees with is an ordering failure, not an internal one.
            session().abort(t instanceof LaneMismatchException ? LaneSession.CLOSE_ORDERING
                    : LaneSession.CLOSE_INTERNAL_ERROR);
        } catch (Throwable notAttached) {
            // No session to abort: the write that failed is already failed and the game's pipeline
            // has the cause, so there is nothing else this side can do.
        }
    }

    // --- Outbound.

    /** One delivery, kept whole so a thread hop cannot reorder it. */
    private static final class Delivery {
        final Lane lane;
        final long sequence;
        final List<Object> packets;
        final boolean bundle;

        Delivery(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            this.lane = lane;
            this.sequence = sequence;
            this.packets = packets;
            this.bundle = bundle;
        }
    }

    /**
     * The tail-end outbound handler: the game's packets leave through the session, encoded by the
     * platform, and never through stream 0. It consumes the message, so the game's own encoder
     * (nearer the head) is not used for a {@code /3} connection.
     *
     * <p>Every write is handled on the game channel's event loop: the hosted transport makes that
     * the connection's own loop, and a write from another thread is marshalled there, so writes keep
     * their submission order and the lane check below always runs.</p>
     */
    private final class Outbound extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            EventLoop loop = game.eventLoop();
            if (loop.inEventLoop()) {
                handle(ctx, msg, promise);
            } else {
                loop.execute(() -> handle(ctx, msg, promise));
            }
        }

        /** Game loop only. */
        private void handle(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            try {
                Route route = codec.routeOf(msg);
                if (route == null) {
                    throw new IllegalStateException("the codec has no route for "
                            + msg.getClass().getName());
                }
                // 13.6: the routing table decides the lane. Validate the game's own answer BEFORE
                // the session is handed the packet, because sending assigns, queues and drains
                // inline: a check afterwards could not stop a wrong-lane packet reaching the wire.
                Lane expected = session().outboundLane(route.state(), route.packetId());
                if (expected != route.lane()) {
                    throw new LaneMismatchException(route.lane(), expected);
                }
                byte[] frame = encode(ctx, msg);
                session().send(route.state(), route.packetId(), frame);
                promise.trySuccess();
            } catch (Throwable t) {
                fail(t);
                promise.tryFailure(t);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        private byte[] encode(ChannelHandlerContext ctx, Object packet) throws Exception {
            ByteBuf out = ctx.alloc().buffer();
            try {
                codec.encode(packet, out);
                byte[] frame = new byte[out.readableBytes()];
                out.readBytes(frame);
                return frame;
            } finally {
                out.release();
            }
        }
    }

    /** The platform named a lane the routing table does not: a 13.6 routing bug, not a byte on the wire. */
    static final class LaneMismatchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        LaneMismatchException(Lane named, Lane routed) {
            super("the game routed the packet to " + named + ", but the routing table assigns "
                    + routed + " (13.6)");
        }
    }

    /** Production {@link Session}: the activated lane session of the connection. */
    private static final class LaneSessionSink implements Session {
        private final LaneSession session;

        LaneSessionSink(LaneSession session) {
            if (session == null) {
                throw new IllegalArgumentException("session");
            }
            this.session = session;
        }

        @Override
        public Lane outboundLane(ProtocolState state, int packetId) {
            return session.outboundLane(state, packetId);
        }

        @Override
        public Lane send(ProtocolState state, int packetId, byte[] frame) {
            SentPacket handle = session.send(state, packetId, frame);
            // Assigned inline when this runs on the session's loop, which is the game's own loop for
            // a hosted connection; an off-loop submission reports null.
            return handle.isAssigned() ? handle.lane() : null;
        }

        @Override
        public void applied(Lane lane, long sequence) {
            session.applied(lane, sequence);
        }

        @Override
        public void abort(int code) {
            session.abort(code);
        }
    }
}
