// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server.update;

import org.jspecify.annotations.Nullable;

/**
 * Maps the running Minecraft version to the platform name this project's build metadata carries
 * (docs/updater.md 12.14). The value comes from the loaded game version alone, never from the discovered
 * jar: that jar's metadata is exactly what the facade checks this value against.
 *
 * <p>Plain logic with no game classes, so it is easy to unit test later.
 */
final class FabricPlatformNames {
    /** The one 1.21 build this project ships a jar for. */
    static final String VERSION_1_21_11 = "1.21.11";
    /** Every 26.x build shares one jar, built from the same source. */
    static final String PREFIX_26X = "26.";
    /** The platform name of the 1.21.11 jar. */
    static final String PLATFORM_1_21_11 = "fabric-1.21.11";
    /** The platform name of the 26.x jar. */
    static final String PLATFORM_26X = "fabric-26x";

    private FabricPlatformNames() {
    }

    /**
     * @param gameVersion the version the loader reports for {@code minecraft}, for example {@code 26.1.2}
     * @return the platform name, or null when this project has no jar for that version
     */
    static @Nullable String platformOrNull(String gameVersion) {
        if (VERSION_1_21_11.equals(gameVersion)) {
            return PLATFORM_1_21_11;
        }
        if (gameVersion.startsWith(PREFIX_26X)) {
            return PLATFORM_26X;
        }
        return null;
    }
}
