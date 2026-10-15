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
    private volatile long sequence;

    SentPacket() {}

    void assign(Lane lane, long sequence) {
        this.sequence = sequence;
        this.lane = lane;
    }

    /** True once the session assigned a lane and sequence. */
    public boolean isAssigned() {
        return lane != null;
    }

    /** The assigned lane, or null while unassigned. */
    public Lane lane() {
        return lane;
    }

    /** The assigned sequence (unsigned), meaningful only when {@link #isAssigned()}. */
    public long sequence() {
        return sequence;
    }
}
