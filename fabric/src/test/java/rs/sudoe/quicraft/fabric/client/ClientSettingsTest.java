// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;

/**
 * {@code config/quicraft/client.properties}: multi-stream is off unless the player turns it on
 * (12.1), and a malformed file never changes what a vanilla join does. Nothing here starts a game.
 */
class ClientSettingsTest {

    @Test
    void aFreshFileWritesTheDefaultsAndMultistreamIsOff(@TempDir Path dir) throws Exception {
        ClientSettings.Loaded settings = ClientSettings.load(dir);

        assertEquals(Mode.AUTO, settings.mode());
        assertFalse(settings.multistream(), "12.1: the /3 switch is off by default");
        String file = new String(Files.readAllBytes(dir.resolve(ClientSettings.FILE)), StandardCharsets.UTF_8);
        assertTrue(file.contains("multistream=false"), "the default is written down in the file");
    }

    @Test
    void anExistingFileWithoutTheKeyKeepsMultistreamOff(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve(ClientSettings.FILE), "transport=quic-only\n".getBytes(StandardCharsets.UTF_8));

        ClientSettings.Loaded settings = ClientSettings.load(dir);

        assertEquals(Mode.QUIC_ONLY, settings.mode());
        assertFalse(settings.multistream(), "an old file from before this setting existed stays on /2 and TCP");
    }

    @Test
    void onlyAnExplicitTrueTurnsMultistreamOn(@TempDir Path dir) throws Exception {
        for (String value : new String[] {"yes", "1", "on", "tru", ""}) {
            Files.write(dir.resolve(ClientSettings.FILE),
                    ("multistream=" + value + "\n").getBytes(StandardCharsets.UTF_8));
            assertFalse(ClientSettings.load(dir).multistream(), "'" + value + "' is not true");
        }
        Files.write(dir.resolve(ClientSettings.FILE), "multistream= TRUE \n".getBytes(StandardCharsets.UTF_8));
        assertTrue(ClientSettings.load(dir).multistream());
    }

    @Test
    void saveKeepsBothValues(@TempDir Path dir) {
        ClientSettings.save(dir, Mode.TCP_ONLY, true);

        ClientSettings.Loaded settings = ClientSettings.load(dir);

        assertEquals(Mode.TCP_ONLY, settings.mode());
        assertTrue(settings.multistream());
    }

    @Test
    void anUnreadableFileFallsBackToTheDefaults(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve(ClientSettings.FILE)); // a directory where the file should be

        ClientSettings.Loaded settings = ClientSettings.load(dir);

        assertEquals(ClientSettings.Loaded.DEFAULTS, settings);
    }
}
