// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.util.Objects;

/**
 * The exact lookup key of docs/protocol.md 13.6: Minecraft protocol number, protocol state,
 * direction and numeric packet id. Immutable and safe to share between threads.
 */
public final class RouteKey {
    private final int protocolNumber;
    private final ProtocolState state;
    private final Direction direction;
    private final int packetId;

    /**
     * @param protocolNumber Minecraft protocol number the routing resource is for (13.7)
     * @param packetId nonnegative numeric packet id (13.7)
     */
    public RouteKey(int protocolNumber, ProtocolState state, Direction direction, int packetId) {
        if (protocolNumber < 0) {
            throw new IllegalArgumentException("protocol number out of range: " + protocolNumber);
        }
        if (packetId < 0) {
            throw new IllegalArgumentException("packet id out of range: " + packetId);
        }
        this.protocolNumber = protocolNumber;
        this.state = Objects.requireNonNull(state, "state");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.packetId = packetId;
    }

    public int protocolNumber() {
        return protocolNumber;
    }

    public ProtocolState state() {
        return state;
    }

    public Direction direction() {
        return direction;
    }

    public int packetId() {
        return packetId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RouteKey)) {
            return false;
        }
        RouteKey key = (RouteKey) other;
        return protocolNumber == key.protocolNumber
                && packetId == key.packetId
                && state == key.state
                && direction == key.direction;
    }

    @Override
    public int hashCode() {
        int result = protocolNumber;
        result = 31 * result + state.hashCode();
        result = 31 * result + direction.hashCode();
        result = 31 * result + packetId;
        return result;
    }

    @Override
    public String toString() {
        return "protocol " + protocolNumber + " " + state.wireName() + " " + direction.wireName()
                + " packet " + packetId;
    }
}
