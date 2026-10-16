// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.ArrayDeque;
import java.util.List;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
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

    private final ReceiveBudget node;
    private final Sender sender;
    private final int maxUnits;
    private final long maxBytes;
    private final ArrayDeque<OutboundUnit> held = new ArrayDeque<>();
    private long heldBytes;

    OutboundHold(Sender sender, int maxUnits, long maxBytes) {
        this(sender, maxUnits, maxBytes, ReceiveBudget.unlimited());
    }

    /** As above; held units are also reserved against the node budget until they are sent or dropped. */
    OutboundHold(Sender sender, int maxUnits, long maxBytes, ReceiveBudget node) {
        this.node = node;
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
        if (!unit.reserve(node)) {
            throw new IllegalStateException("the node budget refused " + unit.bytes() + " held bytes (13.12)");
        }
        if (held.size() + 1 > maxUnits || heldBytes + unit.bytes() > maxBytes) {
            // An unpausable producer over the cap disconnects (13.12), here as in the layer. The
            // reservation goes back with the rejected unit: this hold never retains it, so leaving
            // it charged would keep the bytes against the shared node budget for ever.
            unit.release();
            throw new IllegalStateException("the outbound hold is over its cap (13.12)");
        }
        held.add(unit);
        heldBytes += unit.bytes();
    }

    /**
     * Hands over everything 12.13 released from the quiescence queue, whole and in order. The
     * connection is closing either way when one submission fails, but a unit that was never handed
     * over still owns its node reservation, so each of those gives its bytes back before the failure
     * is rethrown: no unit may be stranded outside both this hold and the lane layer (13.12).
     */
    void submitAll(List<Object> released) {
        int next = 0;
        try {
            for (; next < released.size(); next++) {
                submit((OutboundUnit) released.get(next));
            }
        } catch (Throwable t) {
            for (int i = next; i < released.size(); i++) {
                try {
                    ((OutboundUnit) released.get(i)).release();
                } catch (Throwable releaseFailure) {
                    t.addSuppressed(releaseFailure);
                }
            }
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new IllegalStateException(t);
        }
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

    /** The connection ended: every held unit gives its reservation back. */
    void teardown() {
        for (OutboundUnit unit : held) {
            unit.release();
        }
        held.clear();
        heldBytes = 0;
    }

    private void dispatch(OutboundUnit unit) {
        try {
            send(unit);
        } finally {
            unit.release();
        }
    }

    private void send(OutboundUnit unit) {
        if (unit.bundle() && unit.packets().size() >= 2) {
            sender.sendBundle(unit.state(), unit.packets());
            return;
        }
        for (byte[] packet : unit.packets()) {
            sender.send(unit.state(), packet);
        }
    }
}
