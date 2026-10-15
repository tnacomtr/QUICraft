// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

/**
 * Packet direction of a routing entry (docs/protocol.md 13.7). Sequences and dependencies are
 * maintained independently per stream and direction (13.4), so a route is bound to one direction.
 */
public enum Direction {
    /** Server to client. */
    CLIENTBOUND("clientbound"),
    /** Client to server. */
    SERVERBOUND("serverbound");

    private final String wireName;

    Direction(String wireName) {
        this.wireName = wireName;
    }

    /** The JSON {@code direction} value of this direction (13.7). */
    public String wireName() {
        return wireName;
    }

    /**
     * The direction whose wire name is {@code wireName}.
     *
     * @throws IllegalArgumentException if no direction has that name
     */
    public static Direction of(String wireName) {
        for (Direction direction : values()) {
            if (direction.wireName.equals(wireName)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("unknown direction: " + wireName);
    }
}
