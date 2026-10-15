// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

/**
 * The three stream lanes of docs/protocol.md 13.3: exactly one CONTROL stream (QUIC stream 0),
 * one REALTIME stream (stream 4, client-opened) and one WORLD stream (stream 1, server-opened).
 *
 * <p>The wire names are the {@code lane} values of the routing resource (13.7).
 */
public enum Lane {
    /** Stream 0, both directions: ordered and a full fence (13.5). */
    CONTROL("control"),
    /** Stream 4, both directions: entity movement and metadata once their spawn is delivered. */
    REALTIME("realtime"),
    /** Stream 1, both directions: chunk-local updates in original order (13.8). */
    WORLD("world");

    private final String wireName;

    Lane(String wireName) {
        this.wireName = wireName;
    }

    /** The JSON {@code lane} value of this lane (13.7). */
    public String wireName() {
        return wireName;
    }

    /** True for CONTROL, the lane every missing or unspecified route defaults to (13.6). */
    public boolean isControl() {
        return this == CONTROL;
    }

    /**
     * The lane whose wire name is {@code wireName}.
     *
     * @throws IllegalArgumentException if no lane has that name
     */
    public static Lane of(String wireName) {
        for (Lane lane : values()) {
            if (lane.wireName.equals(wireName)) {
                return lane;
            }
        }
        throw new IllegalArgumentException("unknown lane: " + wireName);
    }
}
