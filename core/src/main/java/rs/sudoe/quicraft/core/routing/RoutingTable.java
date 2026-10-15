// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, thread-safe routing table for one Minecraft protocol number (docs/protocol.md 13.6,
 * 13.7). Build one with {@link RoutingTableLoader}; it is never mutated after construction.
 *
 * <p>Lookup is by exact protocol number, state, direction and packet id. Any missing entry routes
 * to CONTROL, never an inferred category (13.6). {@link #digest()} is SHA-256 over the exact bytes
 * of the resource the table was parsed from, whitespace included, with no normalization (13.7);
 * both peers must ship the identical resource.
 *
 * <p>Entries keep the order they have in the resource, at every step from the parsed JSON to
 * {@link #routes()}.
 *
 * <p>Deferred: 13.7 also requires ordering tests for every non-control entry. Those tests belong to
 * the barrier unit of 13.9 to 13.11, which does not exist yet, so this table ships without them and
 * no ordering test is written here; they land with that unit.
 */
public final class RoutingTable {
    private final int protocolNumber;
    private final byte[] digest;
    private final String digestHex;
    private final Map<RouteKey, Route> routes;

    RoutingTable(int protocolNumber, byte[] digest, Map<RouteKey, Route> routes) {
        this.protocolNumber = protocolNumber;
        this.digest = digest.clone();
        this.digestHex = toHex(digest);
        this.routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
    }

    /** The Minecraft protocol number this table is for (13.7). */
    public int protocolNumber() {
        return protocolNumber;
    }

    /**
     * R, the digest of the exact resource bytes (13.7). The returned array is a copy, so mutating it
     * cannot change this table.
     */
    public byte[] digest() {
        return digest.clone();
    }

    /** R as lowercase hex, the form a digest goes on the wire in. */
    public String digestHex() {
        return digestHex;
    }

    /** The route for an exact lookup, or empty when the entry is missing (13.6). */
    public Optional<Route> routeFor(RouteKey key) {
        return Optional.ofNullable(routes.get(key));
    }

    /** The lane for an exact lookup, defaulting to CONTROL for any missing entry (13.6). */
    public Lane laneFor(ProtocolState state, Direction direction, int packetId) {
        Route route = routes.get(new RouteKey(protocolNumber, state, direction, packetId));
        return route == null ? Lane.CONTROL : route.lane();
    }

    /** All explicit entries, in resource order. Unmodifiable. */
    public Map<RouteKey, Route> routes() {
        return routes;
    }

    @Override
    public String toString() {
        return "routing table for protocol " + protocolNumber + " (" + routes.size() + " routes, R="
                + digestHex + ")";
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
