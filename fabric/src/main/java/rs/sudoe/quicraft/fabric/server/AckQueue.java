// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.function.Consumer;
import rs.sudoe.quicraft.core.routing.Lane;

/**
 * Acknowledges a delivery to the lane layer only after the game has processed it (docs/protocol.md
 * 13.10): the acknowledgement is queued on the game's own serial queue of packet handlers, behind
 * the handler tasks of the packets it acknowledges, so no credit is released while one of them is
 * still waiting for the main thread.
 */
final class AckQueue {
    /** The layer's acknowledgement. */
    interface Sink {
        void applied(Lane lane, long sequence);
    }

    private final Consumer<Runnable> serialQueue;
    private final Sink sink;

    /**
     * @param serialQueue appends a task behind everything already queued for the game's handlers
     */
    AckQueue(Consumer<Runnable> serialQueue, Sink sink) {
        this.serialQueue = serialQueue;
        this.sink = sink;
    }

    void acknowledge(Lane lane, long sequence) {
        serialQueue.accept(() -> sink.applied(lane, sequence));
    }
}
