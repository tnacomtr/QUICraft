// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.util.Set;

/**
 * The platform identifiers the updater compiles in (docs/updater.md 12.3, {@code platform}).
 *
 * <p>The updater keeps the same set in {@code SharedChecks.PLATFORMS}, which is package-private; the
 * re-parse of every composed document with the updater's own parser is what really enforces it.
 */
final class Platforms {

    static final Set<String> ALL = Set.of("velocity", "fabric-26x", "fabric-1.21.11");

    private Platforms() {
    }

    /**
     * @throws ToolException if {@code platform} is not one of the v1 platforms
     */
    static String require(String platform) throws ToolException {
        if (!ALL.contains(platform)) {
            throw new ToolException("unknown platform '" + platform + "', expected one of " + ALL);
        }
        return platform;
    }
}
