// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.binding;

import java.util.List;

/**
 * One endpoint's side of the {@code /3} binding exchange (docs/protocol.md 12.4 to 12.14), driven
 * by the platform adapter that owns the connection, its event loop, the cipher and the framing.
 *
 * <p>The adapter's responsibilities, which stay outside this package because they need Netty, the
 * game's cipher and the game's configuration state:</p>
 *
 * <ul>
 *   <li>Entering the exchange at the right moment (12.4: configuration state, after a successful
 *       online authentication and TLS handshake confirmation) and calling
 *       {@link #onConfigurationEntered(long)} exactly once, from the connection's event loop.</li>
 *   <li>The 12.7 confirmation gate, which only the adapter can see. The pinned Netty reports TLS
 *       handshake completion, not RFC 9001 confirmation, and {@code /3} allows 0-RTT, so a server
 *       MUST NOT start binding until it has seen its own handshake completion: it enters
 *       configuration only after processing a 1-RTT packet (Encryption Response in online mode,
 *       Login Acknowledged under the offline test flag) and refuses to start binding with
 *       {@code 0x10} otherwise. With 0-RTT accepted, neither side may start binding before the
 *       server's handshake is complete.</li>
 *   <li>Draining earlier outbound writes before the server's OFFER and before the client's
 *       CLIENT_PROOF, and installing the boundary-aware decoder of 12.13 before the client sends
 *       CLIENT_SWITCH and before the server sends OFFER.</li>
 *   <li>Feeding every {@code quicraft:binding} payload to {@link #onPayload(byte[])} and nothing
 *       else: those payloads are never delivered to ordinary plugins (12.8).</li>
 *   <li>Calling {@link #tick(long)} from its event loop while the exchange is running, unless the
 *       session was given a scheduler and ticks itself, and
 *       {@link #queueOrdinary(Object, int)} for every ordinary outbound game packet or state change
 *       while the session says it is pausing them (12.10).</li>
 *   <li>Refusing {@code /3} in the cases this package cannot see: a login that is encrypted but not
 *       authenticated must never offer or continue {@code /3} (12.7, 12.19), and an exporter error
 *       is fatal rather than permission to continue with Minecraft's cipher off (12.3).</li>
 * </ul>
 *
 * <p>Every failure inside the session becomes {@link Callbacks#fail(int, String)} with
 * {@code 0x10}, {@link rs.sudoe.quicraft.core.Protocol#CLOSE_BINDING_FAILURE} (12.14). No method
 * here throws for anything a peer or a callback can influence: a callback that throws ends the
 * session with {@code 0x10} instead, because the adapter's only safe answer is to close the
 * connection (12.14). Only local misuse, such as a null packet, throws.</p>
 */
public interface BindingDriver {
    /** What {@link BindingDriver#queueOrdinary(Object, int)} did with an ordinary packet (12.10). */
    enum QueueResult {
        /** The session queued it, and hands it back through {@link BindingDriver#releaseQueued()}. */
        QUEUED,
        /** The session is not pausing ordinary packets, so the adapter sends it itself. */
        SEND_NOW,
        /**
         * The packet overflowed the quiescence queue, so the session has already reported
         * {@code 0x10} and the connection is being closed (12.10).
         */
        DROPPED_FAILED
    }

    /** What the session asks the adapter to do, always from the connection's event loop. */
    interface Callbacks {
        /**
         * Writes one binding payload of 12.8 verbatim. The adapter must not send it as an ordinary
         * packet: it bypasses the quiescence queue (12.10).
         */
        void send(byte[] payload);

        /**
         * The outbound direction switches from Minecraft's AES/CFB8 to §13 records. Called
         * immediately after the switch packet that ends the encrypted direction was handed to
         * {@link #send(byte[])}: on the client after CLIENT_SWITCH (12.11), on the server after
         * SERVER_SWITCH was queued (12.12). The adapter applies the transition after that packet's
         * final encrypted byte, in the same event-loop task (13.3).
         */
        void activateOutbound();

        /**
         * The inbound direction switches to the §13 parser on the very next byte (12.12). Called
         * after the peer's final switch packet verified: on the client for SERVER_SWITCH, on the
         * server for CLIENT_SWITCH (12.11).
         */
        void activateInbound();

        /**
         * Closes the whole QUIC connection with the application error code, {@code 0x10} for every
         * binding failure (12.14). Called at most once, after the first failure.
         *
         * <p>It can follow {@link #activateInbound()}: on the server, that callback and
         * {@link #activateOutbound()} both run when CLIENT_SWITCH verifies, so an outbound cutover
         * that fails after the inbound one leaves the session no choice but to close the whole
         * connection (12.11, 12.12, 12.14).</p>
         */
        void fail(int code, String reason);
    }

    /**
     * The exchange begins: the adapter entered configuration and, for the server, has drained its
     * earlier outbound writes. The session starts the 12.14 deadline here.
     *
     * @param nowNanos the current value of the monotonic clock the session was built with
     */
    void onConfigurationEntered(long nowNanos);

    /**
     * One received {@code quicraft:binding} payload of 12.8. The session validates it against the
     * fixed order of 12.9 and reports what to send or that activation completed.
     */
    void onPayload(byte[] payload);

    /**
     * Offers one ordinary outbound game packet or state change to the quiescence queue of 12.10,
     * bounded at 256 packets or 8 MiB, whichever is reached first.
     *
     * @param packet the packet as the adapter holds it, kept until
     *     {@link #releaseQueued()} hands it back to be encoded as a record
     * @param bytes the packet's wire size, counted against the 8 MiB bound
     * @return {@link QueueResult#QUEUED} when the session queued it,
     *     {@link QueueResult#SEND_NOW} when the session is not pausing ordinary packets right now
     *     and the adapter sends it itself, and {@link QueueResult#DROPPED_FAILED} when it overflowed
     *     the queue, in which case the session has already reported {@code 0x10} through
     *     {@link Callbacks#fail(int, String)} and the connection is being closed
     * @throws IllegalArgumentException when the packet is null or {@code bytes} is negative
     */
    QueueResult queueOrdinary(Object packet, int bytes);

    /**
     * Hands back everything {@link #queueOrdinary(Object, int)} accepted, in order, and forgets it,
     * so the adapter can encode those packets anew as §13 records after activation (12.13). The
     * caller must stop queueing and release once instead; releasing before activation would put
     * those packets on the wire outside the agreed boundary.
     *
     * @return the queued packets, in the order they were offered; empty when there are none
     */
    List<Object> releaseQueued();

    /**
     * Advances the session's timers: the 10 second activation deadline and the client's offer wait
     * (12.14). The adapter calls this from its event loop while the exchange is running, whether or
     * not a payload arrived. A session built with a scheduler ticks itself, so an adapter that has
     * one need not call this at all.
     *
     * @param nowNanos the current value of the monotonic clock the session was built with
     */
    void tick(long nowNanos);
}
