// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import rs.sudoe.quicraft.updater.UpdaterConfig.CheckInterval;
import rs.sudoe.quicraft.updater.UpdaterConfig.InstallMode;

/**
 * {@link InstallCapabilities#decide}: the OS name and {@code update.install} matrix behind the alpha's
 * live swap (docs/updater.md 12.10, 12.13).
 */
class InstallCapabilitiesTest {

    /** One cell of the matrix: an OS name, the settings it runs with, and what {@code update} may do. */
    private record Cell(String osName, String setting, UpdaterConfig config, InstallCapability expected) {
        @Override
        public String toString() {
            return osName + " with " + setting;
        }
    }

    private static UpdaterConfig configured(InstallMode mode) {
        return new UpdaterConfig(CheckInterval.HOURLY, true, true, mode);
    }

    /** The default settings, as a server without an {@code update.install} line gets them. */
    private static final UpdaterConfig DEFAULTS = UpdaterConfig.defaults();

    /** Four OS names crossed with the three settings a server can end up with. */
    private static final List<Cell> MATRIX = List.of(
            new Cell("Linux", "the default settings", DEFAULTS, InstallCapability.ATOMIC_REPLACEMENT),
            new Cell("Linux", "update.install=swap", configured(InstallMode.SWAP),
                    InstallCapability.ATOMIC_REPLACEMENT),
            new Cell("Linux", "update.install=stage-only", configured(InstallMode.STAGE_ONLY),
                    InstallCapability.VERIFIED_STAGING_ONLY),
            new Cell("Mac OS X", "the default settings", DEFAULTS, InstallCapability.ATOMIC_REPLACEMENT),
            new Cell("Mac OS X", "update.install=swap", configured(InstallMode.SWAP),
                    InstallCapability.ATOMIC_REPLACEMENT),
            new Cell("Mac OS X", "update.install=stage-only", configured(InstallMode.STAGE_ONLY),
                    InstallCapability.VERIFIED_STAGING_ONLY),
            new Cell("Windows 11", "the default settings", DEFAULTS, InstallCapability.VERIFIED_STAGING_ONLY),
            new Cell("Windows 11", "update.install=swap", configured(InstallMode.SWAP),
                    InstallCapability.VERIFIED_STAGING_ONLY),
            new Cell("Windows 11", "update.install=stage-only", configured(InstallMode.STAGE_ONLY),
                    InstallCapability.VERIFIED_STAGING_ONLY),
            new Cell("FreeBSD", "the default settings", DEFAULTS, InstallCapability.VERIFIED_STAGING_ONLY),
            new Cell("FreeBSD", "update.install=swap", configured(InstallMode.SWAP),
                    InstallCapability.VERIFIED_STAGING_ONLY),
            new Cell("FreeBSD", "update.install=stage-only", configured(InstallMode.STAGE_ONLY),
                    InstallCapability.VERIFIED_STAGING_ONLY));

    @TestFactory
    Stream<DynamicTest> everyOsAndInstallModeCell() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Cell cell : MATRIX) {
            tests.add(DynamicTest.dynamicTest(cell.toString(),
                    () -> assertEquals(cell.expected(), InstallCapabilities.decide(cell.osName(),
                            cell.config()), cell.toString())));
        }
        return tests.stream();
    }

    @Test
    void theMatrixCoversFourOsNamesAndThreeSettings() {
        assertEquals(12, MATRIX.size());
        assertEquals(4, MATRIX.stream().map(Cell::osName).distinct().count());
        assertEquals(3, MATRIX.stream().map(Cell::setting).distinct().count());
    }

    @Test
    void linuxAndMacNamesAreMatchedCaseInsensitively() {
        assertEquals(InstallCapability.ATOMIC_REPLACEMENT,
                InstallCapabilities.decide("LiNuX 6.8.0", DEFAULTS));
        assertEquals(InstallCapability.ATOMIC_REPLACEMENT,
                InstallCapabilities.decide("macOS 15.1", DEFAULTS));
        assertEquals(InstallCapability.ATOMIC_REPLACEMENT,
                InstallCapabilities.decide("  Mac OS X  ", DEFAULTS));
    }

    @Test
    void anUnknownPlatformKeepsVerifiedStagingOnly() {
        for (String osName : new String[] {null, "", " ", "Darwin", "SunOS", "Windows", "AudioLinux"}) {
            assertEquals(InstallCapability.VERIFIED_STAGING_ONLY,
                    InstallCapabilities.decide(osName, DEFAULTS),
                    "os.name was " + osName);
        }
    }
}
