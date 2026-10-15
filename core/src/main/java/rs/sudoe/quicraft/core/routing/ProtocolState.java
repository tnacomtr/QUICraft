// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

/**
 * The Minecraft protocol states a route can be bound to (docs/protocol.md 13.6: lookup is by
 * exact protocol number, protocol state, direction and packet id).
 *
 * <p>Records only exist in PLAY: 13.3 opens the auxiliary lanes after activation, which 12.11/12.12
 * place at the end of configuration, and 13.5 requires state transitions, acknowledgements,
 * registry/dimension changes, compression changes and unknown/custom packets to use CONTROL. The
 * loader therefore rejects an auxiliary lane in any state other than {@link #PLAY}.
 */
public enum ProtocolState {
    HANDSHAKING("handshaking"),
    STATUS("status"),
    LOGIN("login"),
    CONFIGURATION("configuration"),
    PLAY("play");

    private final String wireName;

    ProtocolState(String wireName) {
        this.wireName = wireName;
    }

    /** The JSON {@code state} value of this state (13.7). */
    public String wireName() {
        return wireName;
    }

    /** True for PLAY, the only state in which an auxiliary lane is permitted (13.3, 13.5). */
    public boolean isPlay() {
        return this == PLAY;
    }

    /**
     * The state whose wire name is {@code wireName}.
     *
     * @throws IllegalArgumentException if no state has that name
     */
    public static ProtocolState of(String wireName) {
        for (ProtocolState state : values()) {
            if (state.wireName.equals(wireName)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown state: " + wireName);
    }
}
