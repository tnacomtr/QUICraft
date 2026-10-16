// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.ArrayDeque;
import java.util.List;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * Submits {@link OutboundUnit}s to the lane layer in order, whole, and holds them back while the
 * layer says it is not writable (docs/protocol.md 13.12): producers that ignore backpressure end up
 * here instead of overrunning the layer's unsent cap, which would disconnect. The hold is bounded
 * like the layer's own queue; a producer that fills it too fails the join.
 *
 * <p>Threading: the connection's event loop.</p>
 */
final class OutboundHold {
    /** What the lane layer offers. */
    interface Sender {
        boolean writable();

        /** Frames one packet (compression wrapper and length), then submits it. */
        void send(ProtocolState state, byte[] packet);

        /** Submits one bundle's packets atomically: all of them or, by throwing, none. */
        void sendBundle(ProtocolState state, List<byte[]> packets);
    }

    private final Sender sender;
    private final int maxUnits;
    private final long maxBytes;
    private final ArrayDeque<OutboundUnit> held = new ArrayDeque<>();
    private long heldBytes;

    OutboundHold(Sender sender, int maxUnits, long maxBytes) {
        this.sender = sender;
        this.maxUnits = maxUnits;
        this.maxBytes = maxBytes;
    }

    /** Submits {@code unit} now, or holds it behind earlier units or a layer that is not writable. */
    void submit(OutboundUnit unit) {
        if (unit.isEmpty()) {
            return;
        }
        if (held.isEmpty() && sender.writable()) {
            dispatch(unit);
            return;
        }
        if (held.size() + 1 > maxUnits || heldBytes + unit.bytes() > maxBytes) {
            // An unpausable producer over the cap disconnects (13.12), here as in the layer.
            throw new IllegalStateException("the outbound hold is over its cap (13.12)");
        }
        held.add(unit);
        heldBytes += unit.bytes();
    }

    /** The layer became writable: releases held units, in order, while it stays so. */
    void drain() {
        while (!held.isEmpty() && sender.writable()) {
            OutboundUnit unit = held.poll();
            heldBytes -= unit.bytes();
            dispatch(unit);
        }
    }

    int heldUnits() {
        return held.size();
    }

    private void dispatch(OutboundUnit unit) {
        if (unit.bundle() && unit.packets().size() >= 2) {
            sender.sendBundle(unit.state(), unit.packets());
            return;
        }
        for (byte[] packet : unit.packets()) {
            sender.send(unit.state(), packet);
        }
    }
}
