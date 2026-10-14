// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.core.transport.QuicServer;

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
        assertEquals(30000, new Settings(true, 0, 30000, "auto").quicPort(25577, true, 25577));
        assertEquals(65534, Settings.DEFAULTS.quicPort(65535, true, 65535));
    }

    @Test
    void anExplicitPortIsUsedUnlessQueryHasIt() {
        assertEquals(19132, new Settings(true, 19132, 0, "auto").quicPort(25577, true, 25577));
        assertEquals(25578, new Settings(true, 19132, 0, "auto").quicPort(25577, true, 19132));
    }

    @Test
    void udpSocketsDefaultsToAutoAndReads1to64() {
        // docs/protocol.md §11: "auto" is one per CPU core, at most 16, on Linux, and 1 elsewhere.
        assertEquals(QuicServer.defaultSocketCount(), Settings.DEFAULTS.udpSockets());
        assertEquals(1, new Settings(true, 0, 0, "1").udpSockets());
        assertEquals(64, new Settings(true, 0, 0, "64").udpSockets());
        assertEquals(4, new Settings(true, 0, 0, " 4 ").udpSockets());
        assertEquals(QuicServer.defaultSocketCount(), new Settings(true, 0, 0, "AUTO").udpSockets());
    }

    @Test
    void anInvalidUdpSocketsValueFallsBackToAuto() {
        for (String invalid : new String[] {"0", "65", "-1", "eight", "", "2.5"}) {
            assertEquals(QuicServer.defaultSocketCount(), new Settings(true, 0, 0, invalid).udpSockets(),
                    () -> "udp-sockets=" + invalid);
        }
    }

    @Test
    void firstRunWritesTheDefaultFile(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("quicraft");
        assertEquals(Settings.DEFAULTS, Settings.load(data));
        assertTrue(Files.readString(data.resolve(Settings.FILE)).contains("enabled=true"));
        assertTrue(Files.readString(data.resolve(Settings.FILE)).contains("udp-sockets=auto"));
        assertEquals(Settings.DEFAULTS, Settings.load(data));
    }

    @Test
    void invalidValuesFallBackToDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Settings.FILE), "enabled=yes\nport=-4\nalternative-port=abc\n",
                StandardCharsets.UTF_8);
        assertEquals(Settings.DEFAULTS, Settings.load(dir));
        Files.writeString(dir.resolve(Settings.FILE), "enabled = FALSE\nport= 25600\n", StandardCharsets.UTF_8);
        assertEquals(new Settings(false, 25600, 0, "auto"), Settings.load(dir));
    }

    @Test
    void theFileCarriesTheUdpSocketCount(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(Settings.FILE), "udp-sockets=8\n", StandardCharsets.UTF_8);
        assertEquals(8, Settings.load(dir).udpSockets());
        Files.writeString(dir.resolve(Settings.FILE), "udp-sockets=nonsense\n", StandardCharsets.UTF_8);
        assertEquals(QuicServer.defaultSocketCount(), Settings.load(dir).udpSockets());
    }
}
