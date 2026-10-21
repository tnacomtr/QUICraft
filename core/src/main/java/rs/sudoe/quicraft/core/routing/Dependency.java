// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

/**
 * The dependency kind of a routing entry (docs/protocol.md 13.7): 13.7 requires {@code fence} for
 * CONTROL and {@code lane} otherwise.
 *
 * <p>A CONTROL record is a full fence: it depends on every record previously assigned in that
 * sending direction on all lanes, and every later non-control record depends on it (13.5). An
 * auxiliary record depends on the latest CONTROL and its own lane predecessor (13.9); 13.8 may add
 * further cross-lane predecessors, which the sender takes as componentwise maxima.
 */
public enum Dependency {
    /** CONTROL only: the record fences all lanes (13.5, 13.9). */
    FENCE("fence"),
    /** Auxiliary only: the record is ordered within its lane behind the latest CONTROL (13.9). */
    LANE("lane"),
    /** CONTROL waits for own predecessor and most recent REALTIME record (13.7, 13.9). */
    INTERACTION("interaction"),
    /** CONTROL waits for the view frontier (13.5, 13.9). */
    VIEW("view"),
    /** Ordinary CONTROL predecessor only (13.5, 13.9). */
    ORDINARY("ordinary"),
    /** CONTROL fence before REALTIME progress (13.5, 13.9). */
    CONTROL("control"),
    /** CONTROL/view frontier semantics (13.7). */
    GLOBAL("global"),
    /** ACK lane only: waits for every stream's latest record, advances no frontier (13.7, 13.21). */
    TRAILING("trailing"),
    /** CHUNK lane only: lane order plus the last ACK-lane record, except a first chunk install (13.7, 13.21). */
    ACK("ack"),
    /** Chunk join dependency class (13.7, 13.19). */
    ACK_JOIN("ack_join"),
    /** Batched chunk join dependency class (13.7, 13.19). */
    BATCH_JOIN("batch_join");

    private final String wireName;

    Dependency(String wireName) {
        this.wireName = wireName;
    }

    /** The JSON {@code dependency} value of this dependency (13.7). */
    public String wireName() {
        return wireName;
    }

    /**
     * The dependency whose wire name is {@code wireName}.
     *
     * @throws IllegalArgumentException if no dependency has that name
     */
    public static Dependency of(String wireName) {
        for (Dependency dependency : values()) {
            if (dependency.wireName.equals(wireName)) {
                return dependency;
            }
        }
        throw new IllegalArgumentException("unknown dependency: " + wireName);
    }
}
