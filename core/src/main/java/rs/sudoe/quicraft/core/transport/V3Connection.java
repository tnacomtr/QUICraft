// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LaneSession;
import rs.sudoe.quicraft.core.multistream.SessionContext;

/**
 * One {@code quicraft/3} connection (docs/protocol.md 12 and 13): stream 0 as the game sees it
 * during login and configuration, plus the core transport operations the application needs at the
 * cutover. It extends {@link QuicByteStream} so the existing acceptor path hands it to the game
 * exactly like a {@code /2} stream; the extra methods are the {@code /3} lifecycle.
 *
 * <p>Stream 0 is the game's byte stream until {@link #activate}, and the {@link LaneSession}'s
 * CONTROL stream after it. At the byte boundary of 12.11/12.12 the application calls
 * {@link #detachForActivation} and then {@link #activate}: from activation on the session owns
 * stream 0, and the application sends and closes through it, never through {@link #write} or
 * {@link #close}.
 *
 * <p>Threading: callbacks of {@link QuicByteStream.Listener} run on the connection's event loop,
 * every method may be called from any thread, and {@link #exporterValue()} and
 * {@link #smoothedRttMillis()} must run on that loop.
 *
 * <p>Never returned from a {@code /2} dial or listener: a {@code /2} connection is a
 * {@link ClientStream} or {@link ServerStream}, exactly as before 12.1.
 */
public interface V3Connection extends QuicByteStream {
    /**
     * The 12.7 TLS exporter value {@code E} of this connection: 32 bytes for
     * {@link TlsExporter#BINDING_LABEL} with the empty context. On the connection's event loop.
     * Never null: an unavailable or failed exporter throws, because after {@code /3} selection an
     * exporter error is fatal (12.3), not permission to continue without binding.
     *
     * @throws IllegalStateException off the event loop, before the handshake completes, or when
     *                               the native export fails or is unavailable
     */
    byte[] exporterValue();

    /**
     * The connection's current smoothed round-trip time in milliseconds, for 12.16's bounded OFFER
     * wait and diagnostics, or {@code -1} when it is not available.
     *
     * <p>Source: the pinned Netty QUIC connection's path statistics for path 0
     * ({@code QuicChannel.collectPathStats(0)}, native {@code quiche_conn_path_stats}, whose
     * {@code rtt} is in nanoseconds). Netty's QUIC codec exposes no other RTT. The call is answered
     * on the connection's event loop, so call it there for a value; from another thread it returns
     * {@code -1}. It also returns {@code -1} before the first measurement and after close.
     */
    long smoothedRttMillis();

    /**
     * The 12.10/12.13 cutover: stream 0 stops delivering to the game's listener, and every byte
     * core had already read but not delivered is handed to {@code leftover} (usually nothing: the
     * game consumed the read that contained the switch). Call before {@link #activate}, on any
     * thread; auto-read stops so nothing else is read until the session takes over. What the
     * application itself holds (the suffix of the read that contained CLIENT_SWITCH or
     * SERVER_SWITCH) is passed to {@link #activate} instead.
     */
    void detachForActivation(Consumer<ByteBuffer> leftover);

    /**
     * Activation (12.11/12.12): this side's switch is validated, so the three-lane session takes
     * over stream 0 with the game side {@code game}. {@code controlLeftover} is what the
     * application read past the switch (null when none): the start of the 13.4 record stream.
     *
     * <p>On a server this needs the negotiated protocol number, so use
     * {@link #activate(LaneGame, int, ByteBuffer)}; this form is the client's, whose routing table
     * came from the dial.
     *
     * <p>Runs on the connection's event loop, inline when called there, so a server can activate
     * before, or in the same task as, the flush that sends SERVER_SWITCH (13.3).
     *
     * <p>12.7: a server must not start binding before it has seen its own TLS handshake complete
     * ({@link #handshakeComplete()}). A server-role activation before that fails closed with
     * {@code 0x10} rather than starting a session, so a caller cannot skip the check by accident;
     * the client role has no such guard.
     */
    void activate(LaneGame game, ByteBuffer controlLeftover);

    /**
     * As {@link #activate(LaneGame, ByteBuffer)}, for a server, resolving its routing table from
     * the listener's {@link V3Listener#tableFor(int)}. A listener without a table for
     * {@code protocolNumber} closes the connection with {@code 0x10} (12.3, 12.14): a {@code /3}
     * listener never falls back to {@code /2} or to a guessed table.
     */
    void activate(LaneGame game, int protocolNumber, ByteBuffer controlLeftover);

    /**
     * The activated three-lane session, or null before {@link #activate} has run on the event
     * loop. The game sends, ends and aborts through it after activation.
     */
    LaneSession session();

    /**
     * The shard count N selected by the preamble exchange (13.2), fixed for the connection. Available
     * once both preambles are read, from any thread. Fail-closed: before negotiation, or on an
     * implementation that does not negotiate, this throws rather than guessing N.
     *
     * @throws IllegalStateException before the preambles have been negotiated
     */
    default SessionContext sessionContext() {
        throw new IllegalStateException("the shard count is not negotiated yet (13.2)");
    }

    /**
     * Normal shutdown (13.14): queues END as a full fence through the session, so any disconnect
     * packet goes first. Before activation it closes the connection normally.
     */
    void end();

    /** Closes the whole connection now with an application error code (13.14). */
    void abort(int code);

    /** {@link rs.sudoe.quicraft.core.Protocol#ALPN_V3} for a {@code /3} connection. */
    String alpn();

    /**
     * Whether this connection's TLS handshake is complete (user decision 2026-10-16, 12.7): on
     * the server once its own {@code SslHandshakeCompletionEvent} succeeded, on the client once
     * the connect's handshake future is done. A server must refuse to start binding (0x10) while
     * this is false.
     */
    boolean handshakeComplete();
}
