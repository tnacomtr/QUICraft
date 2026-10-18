// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing.extract;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Closed accounting policies for schema-2 resources (13.7, 13.12).
 *
 * <p>Two identifiers name the same closed-form bound. {@code primitive.raw_copies.v1} is the
 * synthetic identifier for test parses. {@code mc775.raw_copies.v1} is the production identifier a
 * shipped 775 table may name. It is the footprint-bound algorithm, not a packet layout, so the
 * captured-pair rule for extractor identifiers does not apply; it is reviewed by the 13.12 formula
 * and its boundary vectors in {@code ExtractorGoldenTest}. A renamed primitive is not review, so the
 * production load path accepts only the production identifier.
 */
public final class FootprintRegistry {
    /** Production-approved accounting identifiers (13.7). */
    private static final Set<String> PRODUCTION = Collections.unmodifiableSet(
            new LinkedHashSet<String>(java.util.Arrays.asList("mc775.raw_copies.v1")));
    /** Every known accounting identifier, synthetic or production. */
    private static final Set<String> KNOWN = Collections.unmodifiableSet(new LinkedHashSet<String>(
            java.util.Arrays.asList("primitive.raw_copies.v1", "mc775.raw_copies.v1")));

    private FootprintRegistry() {}

    /** Rejects unknown policies before activation (13.7). Synthetic and production both pass. */
    public static void require(String identifier) {
        if (identifier == null || !KNOWN.contains(identifier))
            throw new IllegalArgumentException("13.7: unknown decodeFootprintBound identifier");
    }

    /**
     * Rejects anything but a production-approved policy: the production load path (13.7). A
     * synthetic {@code primitive.*} identifier is rejected here, as extractor identifiers are.
     */
    public static void requireProduction(String identifier) {
        if (identifier == null || !PRODUCTION.contains(identifier))
            throw new IllegalArgumentException("13.7: decodeFootprintBound is not production-approved: \""
                    + identifier + "\"");
    }

    /** Two wire copies, output, and 160 prefix bytes; this raw-only policy never inflates (13.12). */
    public static long bound(String identifier, int wireBytes, int outputBytes, boolean compressed) {
        require(identifier);
        if (wireBytes < 0 || outputBytes < 0 || compressed)
            throw new IllegalArgumentException("unsupported footprint declaration");
        return 160L + 2L * wireBytes + outputBytes;
    }
}
