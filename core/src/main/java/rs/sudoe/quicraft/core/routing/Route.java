// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing;

import java.util.Objects;

/** One routing decision: the lane a packet is assigned to and its dependency kind (13.7). */
public final class Route {
    private final Lane lane;
    private final Dependency dependency;

    public Route(Lane lane, Dependency dependency) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.dependency = Objects.requireNonNull(dependency, "dependency");
    }

    public Lane lane() {
        return lane;
    }

    public Dependency dependency() {
        return dependency;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Route)) {
            return false;
        }
        Route route = (Route) other;
        return lane == route.lane && dependency == route.dependency;
    }

    @Override
    public int hashCode() {
        return 31 * lane.hashCode() + dependency.hashCode();
    }

    @Override
    public String toString() {
        return lane.wireName() + "/" + dependency.wireName();
    }
}
