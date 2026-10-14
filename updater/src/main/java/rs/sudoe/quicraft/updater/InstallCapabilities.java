// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Locale;
import rs.sudoe.quicraft.updater.UpdaterConfig.InstallMode;

/**
 * The one place that decides what {@code update} may do with the verified jar (docs/updater.md 12.10,
 * 12.13, blocker 10).
 *
 * <p>The rule for the alpha, from the approved live-swap evidence (12.13) and the owner's choice of
 * {@code update.install} (section 6): {@link InstallCapability#ATOMIC_REPLACEMENT} on Linux and macOS
 * with the default {@link InstallMode#SWAP}, and {@link InstallCapability#VERIFIED_STAGING_ONLY}
 * everywhere else. Every other OS name, an unknown {@code os.name}, and an owner who sets
 * {@code update.install=stage-only} keep the verified staging path only.
 *
 * <p>This decision is not the last word on a swap: {@link UpdaterTransaction} still downgrades to
 * staging when the update directory cannot be synced, and still refuses a swap whose update directory
 * is on another file store than the live jar.
 */
final class InstallCapabilities {

    private InstallCapabilities() {
    }

    /**
     * @param osName the platform's {@code os.name} property, for example {@code Linux}, {@code Mac OS X},
     *     {@code Windows 11}; {@code null} is treated as unknown
     * @param config the loaded updater settings
     * @return what {@code update} may do on this platform
     */
    static InstallCapability decide(String osName, UpdaterConfig config) {
        if (config.installMode() != InstallMode.SWAP) {
            return InstallCapability.VERIFIED_STAGING_ONLY;
        }
        return swappableOs(osName)
                ? InstallCapability.ATOMIC_REPLACEMENT
                : InstallCapability.VERIFIED_STAGING_ONLY;
    }

    /**
     * True for the two OS families whose live-swap evidence the alpha rests on: any {@code Linux} and
     * macOS, whose {@code os.name} is {@code Mac OS X}. The match is on the lower-cased name, so
     * {@code mac} also covers a versioned name such as {@code Mac OS X 10.15}.
     */
    private static boolean swappableOs(String osName) {
        if (osName == null) {
            return false;
        }
        String name = osName.trim().toLowerCase(Locale.ROOT);
        return name.startsWith("linux") || name.contains("mac");
    }
}
