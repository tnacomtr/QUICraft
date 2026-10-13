// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * What the install step may do on this platform.
 *
 * <p>{@link #VERIFIED_STAGING_ONLY}: the verified jar is staged and checked, but never moved over
 * the live jar. Windows always runs with this value. {@link #ATOMIC_REPLACEMENT}: the live jar may
 * be swapped atomically. Linux and macOS get it only when the platform adapter's evidence gate says
 * so, never from OS detection alone, and never when directory fsync is unsupported.
 */
public enum InstallCapability {
    VERIFIED_STAGING_ONLY,
    ATOMIC_REPLACEMENT
}
