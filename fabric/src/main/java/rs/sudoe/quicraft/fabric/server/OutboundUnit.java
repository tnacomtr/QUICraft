// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * Everything the game's encoder produced for one write of one game object: a single packet, or the
 * delimiters and members of a bundle (docs/protocol.md 13.15), or the parts of a split payload. It is
 * the unit that is queued while the binding exchange runs (12.10, charged at its real encoded size)
 * and the unit that goes to the lane layer, so a bundle is never cut in two by admission,
 * backpressure or a refusal.
 */
final class OutboundUnit {
    private final boolean bundle;
    private final List<byte[]> packets = new ArrayList<>();
    private ProtocolState state;
    private long bytes;
    private ReceiveBudget reservedAgainst;

    OutboundUnit(boolean bundle) {
        this.bundle = bundle;
    }

    /** One encoded packet (its id VarInt and payload), in the state the encoder wrote it in. */
    void add(ProtocolState state, byte[] packet) {
        this.state = state;
        packets.add(packet);
        bytes += packet.length;
    }

    boolean bundle() {
        return bundle;
    }

    ProtocolState state() {
        return state;
    }

    List<byte[]> packets() {
        return Collections.unmodifiableList(packets);
    }

    /**
     * Reserves this unit's real size against the node's budget (13.12) for as long as the platform
     * retains it; a second call does nothing. False when the budget refuses.
     */
    boolean reserve(ReceiveBudget budget) {
        if (reservedAgainst != null) {
            return true;
        }
        if (!budget.tryReserve(bytes)) {
            return false;
        }
        reservedAgainst = budget;
        return true;
    }

    /** Gives the reservation back; a second call, or a unit that reserved nothing, does nothing. */
    void release() {
        ReceiveBudget budget = reservedAgainst;
        if (budget != null) {
            reservedAgainst = null;
            budget.release(bytes);
        }
    }

    boolean isEmpty() {
        return packets.isEmpty();
    }

    /** The real encoded size, which is what the quiescence queue and the write hold count. */
    long bytes() {
        return bytes;
    }
}
