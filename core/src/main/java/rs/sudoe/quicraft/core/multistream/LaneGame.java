// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.List;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * What a {@link LaneSession} needs from the game side. No Minecraft classes: packets are opaque
 * objects the game's own decoder produced. Every callback runs on the connection's event loop;
 * a game that applies packets on another thread hops threads itself and acknowledges with
 * {@link LaneSession#applied} when its serial handler has applied the packet (13.10).
 */
public interface LaneGame {
    /** Executor tick stall began; implementations may record timer-exclusion intervals (13.10). */
    default void onExecutorTickStallStart() { }

    /** Executor tick stall ended (13.10). */
    default void onExecutorTickStallEnd() { }

    /** Record applied watermark signal: Q is enqueued, D is applied (13.10, 13.12). */
    default void onRecordAppliedSignal(Lane lane, long sequence, boolean applied) { }

    /**
     * Decodes one dependency-eligible GAME body: exactly one Minecraft VarInt-length frame,
     * including the packet id and the negotiated compression wrapper, without CFB8 (13.4).
     *
     * <p>Runs synchronously and only when the record is eligible (13.16): the game's compression
     * and protocol-state changes of every earlier record have been applied. Use
     * {@code reservation} before allocating decompression output (13.12); a game that skips it
     * gets the sizes in the returned {@link Decoded} reserved after the fact. Any exception
     * closes the connection with 0x11, with one exception: a reservation that the shared node
     * budget cannot carry yet makes the scheduler throw out of this method (13.12's temporary
     * contention, {@link Reservation#reserve}).</p>
     *
     * <p>That throw is a retry, not a failure: the record stays undecoded and this method is called
     * again from the start. The game therefore owns the cleanup of everything it allocated for the
     * refused attempt: release it (a {@code finally} or a catch-and-release around the reservation)
     * before letting the throw propagate, or the attempt leaks its buffers and the rerun charges
     * the same output again. What the scheduler itself reserved for the attempt goes back on its
     * own.</p>
     *
     * @param insideBundle true for CONTROL records between a decoded bundle OPEN and its CLOSE
     */
    Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle,
                   Reservation reservation) throws Exception;

    /**
     * Hands over one decoded packet or one whole bundle, in dependency order and never a partial
     * bundle (13.15). The game applies it, in order, and then calls
     * {@link LaneSession#applied(Lane, long)} with the same lane and {@code sequence}, from any
     * thread. Nothing later is decoded or delivered before that acknowledgement.
     *
     * @param sequence the final sequence to acknowledge (never an intermediate bundle member)
     * @param packets  the decoded objects of {@link Decoded#packet()}; a bundle's delimiters and
     *                 members, in order
     */
    void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle);

    /**
     * The unsent-queue backpressure flag changed (13.12). While false, a game that honours it
     * submits nothing more; a producer that exceeds the cap anyway disconnects with 0x11.
     */
    void onWritabilityChanged(boolean writable);

    /**
     * The connection closed. Called once. {@code code} is the QUIC application error code (0x0
     * after END, 0x11 for an ordering or resource failure) or -1 when the transport ended
     * without one; {@code byPeer} is true when the peer closed first.
     */
    void onClosed(int code, boolean byPeer, Throwable cause);

    /** Charges decoded output against the receive budget before it is allocated (13.12). */
    interface Reservation {
        /**
         * @param additionalRetainedBytes bytes about to be allocated for output and decoded objects
         * @param totalUncompressedBytes  the packet's total uncompressed size, replacing any
         *                                earlier estimate (at most 8 MiB)
         * @throws RuntimeException when the shared node budget cannot carry the bytes yet (13.12):
         *     the call takes nothing, the game must release what it allocated for this attempt and
         *     let the throw leave {@link #decode}, which the scheduler then reruns from its start
         */
        void reserve(long additionalRetainedBytes, int totalUncompressedBytes);
    }

    /** The result of decoding one GAME body. */
    final class Decoded {
        private final Object packet;
        private final ProtocolState state;
        private final int packetId;
        private final Envelope.Bundle bundle;
        private final boolean stateChange;
        private final long retainedBytes;
        private final int uncompressedBytes;

        /**
         * @param packet      the opaque decoded packet handed to {@link LaneGame#deliver}
         * @param state       the protocol state the packet was decoded in, for the 13.6 lane check
         * @param packetId    its numeric packet id
         * @param bundle      NONE for an ordinary packet outside a bundle, OPEN/CLOSE for the
         *                    bundle delimiters, MEMBER for ordinary packets inside one
         * @param stateChange true for an activation, compression change or state transition
         */
        public Decoded(Object packet, ProtocolState state, int packetId, Envelope.Bundle bundle,
                       boolean stateChange) {
            this(packet, state, packetId, bundle, stateChange, 0, 0);
        }

        /** As above, also reporting what decoding retained, for games that did not reserve. */
        public Decoded(Object packet, ProtocolState state, int packetId, Envelope.Bundle bundle,
                       boolean stateChange, long retainedBytes, int uncompressedBytes) {
            if (state == null || bundle == null) {
                throw new IllegalArgumentException("state/bundle");
            }
            this.packet = packet;
            this.state = state;
            this.packetId = packetId;
            this.bundle = bundle;
            this.stateChange = stateChange;
            this.retainedBytes = retainedBytes;
            this.uncompressedBytes = uncompressedBytes;
        }

        public Object packet() {
            return packet;
        }

        public ProtocolState state() {
            return state;
        }

        public int packetId() {
            return packetId;
        }

        public Envelope.Bundle bundle() {
            return bundle;
        }

        public boolean stateChange() {
            return stateChange;
        }

        public long retainedBytes() {
            return retainedBytes;
        }

        public int uncompressedBytes() {
            return uncompressedBytes;
        }
    }
}
