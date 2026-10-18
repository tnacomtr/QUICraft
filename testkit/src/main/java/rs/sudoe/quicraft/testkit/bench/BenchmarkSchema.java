// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import java.util.regex.Pattern;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;

/**
 * Schema constants and shape checks for benchmark provenance. This class contains no measured
 * values; it only defines what a run must carry before a summary may trust it.
 */
public final class BenchmarkSchema {
    /** Version of the run result schema written by {@link RunResult}. */
    public static final int VERSION = 1;

    /**
     * Routing resource schema required for a verified {@code /3} row. Kept in lockstep with core's
     * reviewed schema 2 (docs/protocol.md 13.7) instead of copied as a second magic number.
     */
    public static final int ROUTING_SCHEMA_V3 = RoutingTableLoader.SCHEMA_VERSION_V2;

    /** Placeholder used when a manual bench run cannot supply a Git commit. */
    public static final String UNKNOWN_COMMIT = "unknown";

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern ROUTING_DIGEST = Pattern.compile("[0-9a-f]{64}");

    private BenchmarkSchema() {}

    /** True for a lowercase Git SHA-1, 7 to 40 hex characters. A dirty suffix is not accepted. */
    public static boolean isBuildCommit(String commit) {
        return commit != null && COMMIT.matcher(commit).matches();
    }

    /** True for the lowercase hex form of core's routing digest R (SHA-256, 64 hex characters). */
    public static boolean isRoutingDigest(String digest) {
        return digest != null && ROUTING_DIGEST.matcher(digest).matches();
    }

    /** True for a routing resource identity, not a blank or a placeholder. */
    public static boolean isRoutingTableId(String id) {
        if (id == null) {
            return false;
        }
        String trimmed = id.trim();
        return !trimmed.isEmpty() && !"unknown".equalsIgnoreCase(trimmed)
                && !"none".equalsIgnoreCase(trimmed);
    }
}
