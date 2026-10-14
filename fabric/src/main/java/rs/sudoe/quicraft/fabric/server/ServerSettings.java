// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import rs.sudoe.quicraft.bridge.GameHost;
import rs.sudoe.quicraft.core.transport.QuicServer;

/** {@code config/quicraft/server.properties}. Missing or invalid values fall back to defaults. */
record ServerSettings(boolean enabled, int port, int alternativePort, String udpSocketsSetting) {
    static final String FILE = "server.properties";
    static final String UDPS_SOCKETS_DEFAULT = "auto";

    private static final String TEMPLATE = """
            # QUICraft dedicated server. TCP is never affected by anything here.
            # Players with the QUICraft mod connect over QUIC (UDP) when this server advertises it.

            # false: no QUIC listener and no advertisement.
            enabled=true

            # UDP port for QUIC. 0 = the same number as server-port, unless the query listener
            # (enable-query, query.port in server.properties) uses that UDP port; then
            # alternative-port. Open this UDP port in your firewall.
            port=0

            # Used when the query listener takes the game port. 0 = server-port + 1.
            alternative-port=0

            # UDP sockets the QUIC listener binds, one per game worker loop, each with
            # SO_REUSEPORT, so a busy server spreads QUIC over several threads instead of one
            # (docs/protocol.md section 11). "auto" = one per CPU core, at most 16, where
            # SO_REUSEPORT balances UDP (Linux); 1..64 = that many. One socket everywhere several
            # are impossible (another OS, a transport without SO_REUSEPORT, the option refused).
            udp-sockets=auto
            """;

    /** Reads the file, writing the commented default first if there is none. */
    static ServerSettings load(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(TEMPLATE);
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new ServerSettings(!"false".equalsIgnoreCase(p.getProperty("enabled", "true").trim()),
                port(p, "port"), port(p, "alternative-port"),
                p.getProperty("udp-sockets", UDPS_SOCKETS_DEFAULT).trim());
    }

    /**
     * UDP sockets the QUIC listener binds: {@code "auto"} is
     * {@link QuicServer#defaultSocketCount()} (one per CPU core, at most 16, on Linux), 1..64 is
     * that many, and anything else is {@code "auto"} with one warning (docs/protocol.md §11).
     */
    int udpSockets() {
        return GameHost.socketCount(udpSocketsSetting);
    }

    private static int port(Properties p, String key) {
        try {
            int value = Integer.parseInt(p.getProperty(key, "0").trim());
            return value >= 0 && value <= 65535 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
