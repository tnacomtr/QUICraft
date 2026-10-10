// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {
    @Test
    void quicUsesTheGamePortNumberByDefault() {
        assertEquals(25577, Settings.DEFAULTS.quicPort(25577, false, 25577));
        assertEquals(25577, Settings.DEFAULTS.quicPort(25577, true, 25600));
    }

    @Test
    void queryOnTheGamePortMovesQuicToGamePortPlusOne() {
        // Velocity's default: [query] port = 25577 = the game port (docs/protocol.md §2).
        assertEquals(25578, Settings.DEFAULTS.quicPort(25577, true, 25577));
        assertEquals(30000, new Settings(true, 0, 30000).quicPort(25577, true, 25577));
        assertEquals(65534, Settings.DEFAULTS.quicPort(65535, true, 65535));
    }

    @Test
    void anExplicitPortIsUsedUnlessQueryHasIt() {
        assertEquals(19132, new Settings(true, 19132, 0).quicPort(25577, true, 25577));
        assertEquals(25578, new Settings(true, 19132, 0).quicPort(25577, true, 19132));
    }

    @Test
    void firstRunWritesTheDefaultFile(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("quicraft");
        assertEquals(Settings.DEFAULTS, Settings.load(data));
        assertTrue(Files.readString(data.resolve(Settings.FILE)).contains("enabled=true"));
        assertEquals(Settings.DEFAULTS, Settings.load(data));
    }

    @Test
    void invalidValuesFallBackToDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Settings.FILE), "enabled=yes\nport=-4\nalternative-port=abc\n",
                StandardCharsets.UTF_8);
        assertEquals(Settings.DEFAULTS, Settings.load(dir));
        Files.writeString(dir.resolve(Settings.FILE), "enabled = FALSE\nport= 25600\n", StandardCharsets.UTF_8);
        assertEquals(new Settings(false, 25600, 0), Settings.load(dir));
    }
}
