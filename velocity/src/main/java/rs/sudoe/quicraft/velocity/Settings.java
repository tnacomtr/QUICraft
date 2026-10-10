// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import rs.sudoe.quicraft.core.discovery.QuicPort;

/** {@code plugins/quicraft/config.properties}. Missing or invalid values fall back to defaults. */
record Settings(boolean enabled, int port, int alternativePort) {
    static final String FILE = "config.properties";
    static final Settings DEFAULTS = new Settings(true, 0, 0);

    private static final String TEMPLATE = """
            # QUICraft for Velocity. TCP is never affected by anything here.
            # Players with the QUICraft mod connect over QUIC (UDP) when this proxy advertises it.

            # false: no QUIC listener and no advertisement.
            enabled=true

            # UDP port for QUIC. 0 = the same number as Velocity's TCP port, unless the query
            # listener ([query] in velocity.toml) uses that UDP port; then alternative-port.
            # Open this UDP port in your firewall.
            port=0

            # Used when the query listener takes the game port. 0 = game port + 1.
            alternative-port=0
            """;

    /** Reads the file, writing the commented default first if there is none. */
    static Settings load(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(FILE);
        if (!Files.exists(file)) {
            Files.createDirectories(dataDirectory);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(TEMPLATE);
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new Settings(
                !"false".equalsIgnoreCase(p.getProperty("enabled", "true").trim()),
                port(p, "port"),
                port(p, "alternative-port"));
    }

    private static int port(Properties p, String key) {
        try {
            int value = Integer.parseInt(p.getProperty(key, "0").trim());
            return value >= 0 && value <= 65535 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The UDP port to bind (docs/protocol.md §2): the configured port, else the game port, unless
     * the query listener is on that UDP port; then the alternative port (default game port + 1).
     */
    int quicPort(int gamePort, boolean queryEnabled, int queryPort) {
        return QuicPort.choose(port, alternativePort, gamePort, queryEnabled, queryPort);
    }
}
