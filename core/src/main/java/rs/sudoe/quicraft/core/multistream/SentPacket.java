// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import rs.sudoe.quicraft.core.routing.Lane;

/**
 * A handle for one submitted outbound packet. The session assigns its lane and sequence on the
 * connection's event loop, in submission order; a later submission names this handle as a
 * semantic predecessor (13.9) so the game never needs the numbers themselves. A submission the
 * session dropped (closed connection) stays unassigned.
 */
public final class SentPacket {
    private volatile Lane lane;
    private volatile int logicalStream = -1;
    private volatile long sequence;

    SentPacket() {}

    /** Legacy three-lane assignment: the logical stream is the category's legacy index. */
    void assign(Lane lane, long sequence) {
        assign(lane, SessionContext.legacyLogicalIndex(lane), sequence);
    }

    /** Assignment with the logical stream identity (13.3), written before the lane is published. */
    void assign(Lane lane, int logicalStream, long sequence) {
        this.sequence = sequence;
        this.logicalStream = logicalStream;
        this.lane = lane;
    }

    /** True once the session assigned a lane and sequence. */
    public boolean isAssigned() {
        return lane != null;
    }

    /**
     * The assigned lane, or null while unassigned. This is the category compatibility accessor;
     * {@link #logicalStream()} carries the stream identity.
     */
    public Lane lane() {
        return lane;
    }

    /**
     * The assigned logical stream index (13.3: CONTROL 0, REALTIME 1, CHUNK[i] 2+i), or -1 while
     * unassigned. Two chunk shards may share a sequence, so this, not the sequence, identifies a stream.
     */
    public int logicalStream() {
        return logicalStream;
    }

    /** The assigned sequence (unsigned), meaningful only when {@link #isAssigned()}. */
    public long sequence() {
        return sequence;
    }
}
