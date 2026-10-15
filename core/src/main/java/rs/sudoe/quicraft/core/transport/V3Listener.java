// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import rs.sudoe.quicraft.core.routing.RoutingTable;

/**
 * Enables the {@code /3} listener of docs/protocol.md 13.1 and supplies the routing tables it
 * needs. A listener bound with a {@code V3Listener} offers only
 * {@link rs.sudoe.quicraft.core.Protocol#ALPN_V3}, uses the 13.13 transport parameters, keeps
 * 0-RTT (user decision 2026-10-16, 12.18), and accepts exactly the three streams of 13.3; a
 * listener bound without one is today's {@code /2} listener unchanged. There is no dual listener:
 * a {@code /2}-only client that dials a {@code /3} listener fails the QUIC handshake on the ALPN
 * and races to TCP as usual (5, 13.1).
 */
public interface V3Listener {
    /**
     * The routing table (13.7) for a Minecraft protocol number, or {@code null} when this listener
     * has none. The application calls this with the protocol number it read from the Minecraft
     * handshake, and passes the number to
     * {@link V3Connection#activate(rs.sudoe.quicraft.core.multistream.LaneGame, int,
     * java.nio.ByteBuffer)}. A null table fails the activation closed (12.3, 12.14); {@code /3}
     * never falls back to a guessed table (13.1).
     */
    RoutingTable tableFor(int protocol);
}
