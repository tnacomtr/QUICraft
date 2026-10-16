// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.load;

import java.util.List;
import java.util.function.BiConsumer;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The game side of an activated /3 connection in the load spike: it holds the lanes open and
 * never carries Minecraft traffic. Nothing is sent on the lanes, so {@link #decode} is only reached
 * if a peer sends something, and then it accepts the frame as an opaque PLAY packet. No protocol
 * step is faked here: activation is the public core call, made without a binding proof (the
 * spike has no Minecraft login).
 */
final class IdleLaneGame implements LaneGame {
    private final BiConsumer<Integer, Throwable> closed;

    IdleLaneGame(BiConsumer<Integer, Throwable> closed) { this.closed = closed; }

    @Override
    public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle,
            Reservation reservation) {
        int prefix = 0;
        while (prefix < frame.length && (frame[prefix] & 0x80) != 0) {
            prefix++;
        }
        int id = prefix + 1 < frame.length ? frame[prefix + 1] & 0xff : 0;
        reservation.reserve(0, frame.length);
        return new Decoded(frame, ProtocolState.PLAY, id, Envelope.Bundle.NONE, false);
    }

    @Override
    public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {}

    @Override
    public void onWritabilityChanged(boolean writable) {}

    @Override
    public void onClosed(int code, boolean byPeer, Throwable cause) { closed.accept(code, cause); }
}
