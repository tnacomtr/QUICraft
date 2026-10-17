// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only tables the loader would refuse: a reviewed table with one more schema-2 route. Lets a test reach a
 * session path that a valid table cannot express, such as a clientbound INTERACTION bundle member (13.15).
 */
public final class RoutingTables {
    private RoutingTables() {
    }

    /** A copy of {@code base} with {@code route} added or replaced under {@code key}. */
    public static RoutingTable withRoute(RoutingTable base, RouteKey key, RouteV2 route) {
        Map<RouteKey, RouteV2> routes = new LinkedHashMap<RouteKey, RouteV2>(base.routesV2());
        routes.put(key, route);
        return new RoutingTable(base.protocolNumber(), base.digest(), base.routes(), base.schema(), routes,
                base.extractors(), base.serverboundLimits());
    }
}
