// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.routing.Lane;

/**
 * Acknowledges deliveries to the lane layer (docs/protocol.md 13.10) as soon as the game's serial
 * packet handler has <em>accepted</em> them in order, which is when {@code Connection.channelRead0}
 * returned: the packet was either handled inline or queued on the server's FIFO queue, so order
 * holds. What is bounded instead is the work accepted but not yet run on the main thread: every
 * delivery is counted (packets and bytes, the bytes also reserved against the node's receive budget)
 * until a marker queued right behind its handlers has run, and while that backlog is over its bound
 * the acknowledgements wait, in order, until it drains. A listener that is not accepting messages
 * (so the marker cannot be queued) holds its acknowledgement and every later one until it accepts;
 * nothing is dropped.
 *
 * <p>The bounds are provisional: 13.17's benchmark sets them.</p>
 *
 * <p>Threading: {@link #delivered} and {@link #retry} on the connection's event loop, the markers
 * on the main thread; everything is guarded by this object.</p>
 */
final class AckBacklog {
    /** Provisional bound on accepted-not-yet-run packets, until the 13.17 benchmark sets it. */
    static final long MAX_PACKETS = 2048;
    /** Provisional bound on accepted-not-yet-run bytes, until the 13.17 benchmark sets it. */
    static final long MAX_BYTES = 2L << 20;

    /** The layer's acknowledgement. */
    interface Sink {
        void applied(Lane lane, long sequence);
    }

    /** The game's serial queue of packet handlers. */
    interface SerialQueue {
        /** Queues the task behind everything queued; false when the listener is not accepting messages. */
        boolean offer(Runnable task);
    }

    private static final class Entry {
        final Lane lane;
        final long sequence;
        final long packets;
        final long bytes;
        boolean blocked;
        boolean ran;

        Entry(Lane lane, long sequence, long packets, long bytes) {
            this.lane = lane;
            this.sequence = sequence;
            this.packets = packets;
            this.bytes = bytes;
        }
    }

    private final Sink sink;
    private final SerialQueue queue;
    private final ReceiveBudget node;
    private final long maxPackets;
    private final long maxBytes;
    /** Deliveries not yet acknowledged, in order. */
    private final ArrayDeque<Entry> held = new ArrayDeque<>();
    /** Deliveries whose marker has not run, in order; their bytes are reserved. */
    private final Set<Entry> live = new LinkedHashSet<>();
    private long pendingPackets;
    private long pendingBytes;
    private boolean closed;

    AckBacklog(Sink sink, SerialQueue queue, ReceiveBudget node, long maxPackets, long maxBytes) {
        this.sink = sink;
        this.queue = queue;
        this.node = node == null ? ReceiveBudget.unlimited() : node;
        this.maxPackets = maxPackets;
        this.maxBytes = maxBytes;
    }

    /**
     * One delivery of {@code packets} packets and {@code bytes} bytes was accepted by the game.
     *
     * @throws IllegalStateException when the node budget refuses the bytes
     */
    synchronized void delivered(Lane lane, long sequence, long packets, long bytes) {
        if (closed) {
            return;
        }
        if (!node.tryReserve(bytes)) {
            throw new IllegalStateException("the node receive budget refused " + bytes + " accepted bytes (13.12)");
        }
        Entry entry = new Entry(lane, sequence, packets, bytes);
        live.add(entry);
        held.add(entry);
        pendingPackets += packets;
        pendingBytes += bytes;
        if (!queue.offer(() -> ran(entry))) {
            entry.blocked = true;
        }
        releaseReady();
    }

    /** Queues the markers of deliveries a not-accepting listener refused earlier, in order. */
    synchronized void retry() {
        for (Entry entry : held) {
            if (!entry.blocked) {
                continue;
            }
            if (!queue.offer(() -> ran(entry))) {
                break; // still not accepting; order is kept
            }
            entry.blocked = false;
        }
        releaseReady();
    }

    synchronized boolean hasBlocked() {
        for (Entry entry : held) {
            if (entry.blocked) {
                return true;
            }
        }
        return false;
    }

    /** The marker behind a delivery's handlers ran on the main thread. */
    private synchronized void ran(Entry entry) {
        if (live.remove(entry)) {
            pendingPackets -= entry.packets;
            pendingBytes -= entry.bytes;
            node.release(entry.bytes);
        }
        releaseReady();
    }

    private void releaseReady() {
        while (!closed && !held.isEmpty()) {
            Entry head = held.peek();
            if (head.blocked || pendingPackets > maxPackets || pendingBytes > maxBytes) {
                return;
            }
            held.poll();
            sink.applied(head.lane, head.sequence);
        }
    }

    /** The connection ended: gives back every byte still reserved, whether or not its marker ever ran. */
    synchronized void teardown() {
        closed = true;
        for (Entry entry : live) {
            node.release(entry.bytes);
        }
        live.clear();
        held.clear();
        pendingPackets = 0;
        pendingBytes = 0;
    }

    synchronized long pendingPackets() {
        return pendingPackets;
    }

    synchronized long pendingBytes() {
        return pendingBytes;
    }
}
