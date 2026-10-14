// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import rs.sudoe.quicraft.updater.FreshnessPolicy;

/**
 * The bounds the updater's own parser enforces (docs/updater.md 12.3 and 12.5), repeated here so the
 * tool can refuse a document before writing it.
 *
 * <p>The two lifetime limits are not repeated literals: they are read back from the updater's own
 * {@link FreshnessPolicy}, so {@code compose-release} and {@code compose-revocations} refuse exactly
 * the lifetimes {@code verify} and the updater reject.
 */
final class Limits {

    /** Largest integer of any sequence field: 2^53-1 (docs/updater.md 12.3, "Integers"). */
    static final long MAX_SEQ = 9_007_199_254_740_991L;

    /** Largest jar a release entry may name (docs/updater.md 12.3, {@code size}). */
    static final long MAX_JAR_BYTES = 67_108_864L;

    /** Manifest lifetime limit in days, from the updater's own rule (docs/updater.md 12.5). */
    static final long MAX_RELEASE_DAYS = FreshnessPolicy.MANIFEST_MAX_LIFETIME.toDays();

    /** Revocations lifetime limit in days, from the updater's own rule (docs/updater.md 12.5). */
    static final long MAX_REVOCATION_DAYS = FreshnessPolicy.REVOCATIONS_MAX_LIFETIME.toDays();

    /** Size cap of a trust file in bytes (docs/updater.md 12.3). */
    static final int TRUST_MAX_BYTES = 8192;

    /** Size cap of {@code META-INF/quicraft/build.json} in bytes (docs/updater.md 12.3). */
    static final int BUILD_MAX_BYTES = 1024;

    /** Size cap of any {@code *.sig} in bytes (docs/updater.md 12.3). */
    static final int SIGNATURES_MAX_BYTES = 4096;

    private Limits() {
    }
}
