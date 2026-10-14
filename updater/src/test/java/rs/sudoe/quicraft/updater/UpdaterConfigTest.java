// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.UpdaterConfig.CheckInterval;

/** {@link UpdaterConfig#load}: defaults, each valid value, unknown values and unreadable files. */
class UpdaterConfigTest {

    @TempDir
    Path tmp;

    private UpdaterConfig load(String text, List<String> warnings) throws Exception {
        Path file = tmp.resolve("server.properties");
        Files.writeString(file, text);
        return UpdaterConfig.load(file, warnings::add);
    }

    @Test
    void missingFileGivesTheDefaultsAndIsNotCreated() {
        Path file = tmp.resolve("absent.properties");
        assertEquals(UpdaterConfig.defaults(), UpdaterConfig.load(file));
        assertFalse(Files.exists(file));
    }

    @Test
    void defaultsAreHourlySilentAndLogBadSignatures() {
        UpdaterConfig d = UpdaterConfig.defaults();
        assertEquals(CheckInterval.HOURLY, d.checkInterval());
        assertTrue(d.checkSilent());
        assertTrue(d.logBadSignatures());
    }

    @Test
    void everyValidValueIsHonoured() throws Exception {
        List<String> warnings = new ArrayList<>();
        UpdaterConfig daily = load("update.check-interval=daily\nupdate.check-silent=false\n"
                + "update.log-bad-signatures=false\n", warnings);
        assertEquals(new UpdaterConfig(CheckInterval.DAILY, false, false), daily);
        assertEquals(CheckInterval.OFF, load("update.check-interval=off\n", warnings).checkInterval());
        assertEquals(CheckInterval.HOURLY, load("update.check-interval=hourly\n", warnings).checkInterval());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anUnknownIntervalFailsClosedToOffAndNamesTheKeyOnly() throws Exception {
        List<String> warnings = new ArrayList<>();
        UpdaterConfig c = load("update.check-interval=weekly\n", warnings);
        assertEquals(CheckInterval.OFF, c.checkInterval());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("update.check-interval"));
        assertFalse(warnings.get(0).contains("weekly"));
    }

    @Test
    void intervalValuesAreMatchedCaseInsensitively() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertEquals(CheckInterval.DAILY, load("update.check-interval=DAILY\n", warnings).checkInterval());
        assertEquals(CheckInterval.OFF, load("update.check-interval=Off\n", warnings).checkInterval());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void aMalformedFileWarnsWithoutEchoingItsValuesAndGivesTheDefaults() throws Exception {
        List<String> warnings = new ArrayList<>();
        UpdaterConfig c = load("update.check-silent=\\uZZZZ\n", warnings);
        assertEquals(UpdaterConfig.defaults(), c);
        assertEquals(1, warnings.size());
        assertFalse(warnings.get(0).contains("ZZZZ"));
    }

    @Test
    void anUnknownBooleanFallsBackToTheDefaultAndNamesTheKey() throws Exception {
        List<String> warnings = new ArrayList<>();
        UpdaterConfig c = load("update.check-silent=maybe\nupdate.log-bad-signatures=sometimes\n", warnings);
        assertTrue(c.checkSilent());
        assertTrue(c.logBadSignatures());
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("update.check-silent"));
        assertTrue(warnings.get(1).contains("update.log-bad-signatures"));
        assertFalse(warnings.toString().contains("maybe"));
    }

    @Test
    void anUnreadableFileGivesTheDefaults() {
        Path dirAsFile = tmp.resolve("is-a-directory.properties");
        try {
            Files.createDirectories(dirAsFile);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(UpdaterConfig.defaults(), UpdaterConfig.load(dirAsFile));
    }
}
