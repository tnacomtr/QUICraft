// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing.extract;

/** Closed accounting policies for schema-2 resources (13.7, 13.12). */
public final class FootprintRegistry {
    private FootprintRegistry() {}

    /** Rejects unknown policies before activation (13.7). */
    public static void require(String identifier) {
        if (!"primitive.raw_copies.v1".equals(identifier))
            throw new IllegalArgumentException("13.7: unknown decodeFootprintBound identifier");
    }

    /** Two wire copies, output, and 160 prefix bytes; this raw-only policy never inflates (13.12). */
    public static long bound(String identifier, int wireBytes, int outputBytes, boolean compressed) {
        require(identifier);
        if (wireBytes < 0 || outputBytes < 0 || compressed)
            throw new IllegalArgumentException("unsupported footprint declaration");
        return 160L + 2L * wireBytes + outputBytes;
    }
}
