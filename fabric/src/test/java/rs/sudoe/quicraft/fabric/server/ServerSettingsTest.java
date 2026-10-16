// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code config/quicraft/server.properties} (docs/platforms/fabric.md): the multistream switch is
 * off unless the owner turns it on, and a malformed value never takes the server down. Nothing here
 * starts a game.
 */
class ServerSettingsTest {

    @Test
    void aFreshFileWritesTheDefaultsAndMultistreamIsOff(@TempDir Path dir) throws Exception {
        ServerSettings settings = ServerSettings.load(dir);

        assertTrue(Files.exists(dir.resolve(ServerSettings.FILE)), "the commented default is written");
        assertFalse(settings.multistream(), "12.1: the /3 flag is off by default");
        assertEquals(256L * 1024 * 1024, settings.multistreamNodeBudgetBytes(),
                "13.12's provisional node budget");
        String template = new String(Files.readAllBytes(dir.resolve(ServerSettings.FILE)),
                StandardCharsets.UTF_8);
        assertTrue(template.contains("multistream=false"), "the default is documented in the file");
        assertTrue(template.contains("Provisional until the 50,000-connection benchmark"),
                "and the budget's provisional status is too (13.17)");
    }

    @Test
    void theMultistreamSettingsAreRead(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve(ServerSettings.FILE),
                ("enabled=true\nmultistream=true\nmultistream-node-budget-bytes=1048576\n")
                        .getBytes(StandardCharsets.UTF_8));

        ServerSettings settings = ServerSettings.load(dir);

        assertTrue(settings.multistream());
        assertEquals(1048576L, settings.multistreamNodeBudgetBytes());
        assertTrue(settings.enabled());
    }

    @Test
    void aMalformedBudgetFallsBackToTheDefault(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve(ServerSettings.FILE),
                "multistream-node-budget-bytes=not a number\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(ServerSettings.MULTISTREAM_NODE_BUDGET_DEFAULT,
                ServerSettings.load(dir).multistreamNodeBudgetBytes());
    }

    @Test
    void aNegativeBudgetFallsBackToTheDefault(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve(ServerSettings.FILE),
                "multistream-node-budget-bytes=-1\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(ServerSettings.MULTISTREAM_NODE_BUDGET_DEFAULT,
                ServerSettings.load(dir).multistreamNodeBudgetBytes());
    }

    @Test
    void anythingButTrueLeavesMultistreamOff(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve(ServerSettings.FILE),
                "multistream=yes\n".getBytes(StandardCharsets.UTF_8));

        assertFalse(ServerSettings.load(dir).multistream(),
                "only an explicit true turns the switch on");
    }

    /** The written template must parse as properties: a stray comment syntax error would hide it. */
    @Test
    void theWrittenTemplateParses(@TempDir Path dir) throws Exception {
        ServerSettings.load(dir);
        Properties parsed = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(dir.resolve(ServerSettings.FILE),
                StandardCharsets.UTF_8)) {
            parsed.load(reader);
        }

        assertEquals("false", parsed.getProperty("multistream"));
        assertEquals("268435456", parsed.getProperty("multistream-node-budget-bytes"));
    }
}
