// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * What the install step may do on this platform.
 *
 * <p>{@link #VERIFIED_STAGING_ONLY}: the verified jar is staged and checked, but never moved over
 * the live jar. Every OS other than Linux and macOS always runs with this value, and so does a Linux
 * or macOS server whose owner set {@code update.install=stage-only}. {@link #ATOMIC_REPLACEMENT}: the
 * live jar may be swapped atomically; {@link InstallCapabilities} decides it from {@code os.name} and
 * the {@code update.install} setting, and {@link UpdaterTransaction} downgrades it to staging when
 * the update directory cannot be synced.
 */
public enum InstallCapability {
    VERIFIED_STAGING_ONLY,
    ATOMIC_REPLACEMENT
}
