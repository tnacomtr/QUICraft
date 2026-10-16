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
    private final int schema;
    private final Map<RouteKey, RouteV2> routesV2;
    private final Map<String, String> extractors;
    private final Map<ProtocolState, RouteV2.ServerboundLimits> serverboundLimits;

    RoutingTable(int protocolNumber, byte[] digest, Map<RouteKey, Route> routes) {
        this(protocolNumber, digest, routes, 1, Collections.<RouteKey, RouteV2>emptyMap(),
                Collections.<String, String>emptyMap(),
                Collections.<ProtocolState, RouteV2.ServerboundLimits>emptyMap());
    }

    RoutingTable(int protocolNumber, byte[] digest, Map<RouteKey, Route> routes, int schema,
                 Map<RouteKey, RouteV2> routesV2, Map<String, String> extractors,
                 Map<ProtocolState, RouteV2.ServerboundLimits> limits) {
        this.protocolNumber = protocolNumber;
        this.digest = digest.clone();
        this.digestHex = toHex(digest);
        this.routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
        this.schema = schema;
        this.routesV2 = Collections.unmodifiableMap(new LinkedHashMap<>(routesV2));
        this.extractors = Collections.unmodifiableMap(new LinkedHashMap<>(extractors));
        this.serverboundLimits = Collections.unmodifiableMap(new LinkedHashMap<>(limits));
    }

    /** Resource schema, keeping schema-1 callers distinguishable (13.7). */
    public int schema() { return schema; }

    /** Reviewed schema-2 entries in resource order (13.7). */
    public Map<RouteKey, RouteV2> routesV2() { return routesV2; }

    /** Versioned decoder identifiers covered by R (13.7). */
    public Map<String, String> extractors() { return extractors; }

    /** Reviewed per-state read/decode limits (13.7). */
    public Map<ProtocolState, RouteV2.ServerboundLimits> serverboundLimits() { return serverboundLimits; }

    /**
     * Exact schema-2 lookup, defaulting to CONTROL/global without guessing (13.6). A defaulted route
     * carries its state's accounting identifier, the same one an explicit route in that state gets,
     * so unknown ids are footprint-bounded like known ones (13.7).
     */
    public RouteV2 routeV2For(RouteKey key) {
        RouteV2 route = routesV2.get(key);
        if (route != null) return route;
        RouteV2.ServerboundLimits bound = serverboundLimits.get(key.state());
        return new RouteV2(null, null, false, bound, bound == null ? null : bound.decodeFootprintBound);
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

    /** Legacy schema-1 lookup; schema 2 uses {@link #routeV2For(RouteKey)} (13.6). */
    public Optional<Route> routeFor(RouteKey key) {
        return Optional.ofNullable(routes.get(key));
    }

    /** Legacy schema-1 lane lookup, defaulting to CONTROL for missing entries (13.6). */
    public Lane laneFor(ProtocolState state, Direction direction, int packetId) {
        Route route = routes.get(new RouteKey(protocolNumber, state, direction, packetId));
        return route == null ? Lane.CONTROL : route.lane();
    }

    /** All legacy schema-1 entries, in resource order. Unmodifiable (13.7). */
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
