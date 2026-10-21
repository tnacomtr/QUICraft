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
record ServerSettings(boolean enabled, int port, int alternativePort, String udpSocketsSetting,
        boolean multistream, long multistreamNodeBudgetBytes) {
    static final String FILE = "server.properties";
    static final String UDPS_SOCKETS_DEFAULT = "auto";

    /**
     * The node-wide budget for bytes waiting in {@code /3} receive and unsend queues (13.12), 256
     * MiB. <b>Provisional until the 50,000-connection benchmark (13.17) sets it.</b>
     */
    static final long MULTISTREAM_NODE_BUDGET_DEFAULT = 256L * 1024 * 1024;

    /**
     * The share of the node budget that accepted-but-unrun backlogs (13.10) may hold together, in
     * percent: the rest always stays for reading and decoding, so backlog can never starve admission.
     */
    static final int MULTISTREAM_BACKLOG_SHARE_PERCENT = 25;

    /** The backlog sub-cap in bytes for this node budget. */
    long multistreamBacklogShareBytes() {
        return multistreamNodeBudgetBytes / 100 * MULTISTREAM_BACKLOG_SHARE_PERCENT;
    }

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

            # false: advertise and listen with quicraft/2 only, exactly as before.
            # true: offer quicraft/3 (multi-stream, docs/protocol.md sections 12 and 13) where this
            # machine and this build can: the native must export TLS keying material and cap the
            # multi-stream flow-control windows (sections 12.2, 13.13), and a routing resource must
            # load for the client's protocol number (section 13.7). The /3 listener speaks /3 only:
            # clients without it use TCP. Everywhere a prerequisite is missing this server logs why
            # once and keeps advertising quicraft/2.
            multistream=false

            # The node-wide budget, in bytes, for everything waiting in /3 receive and unsend queues
            # (docs/protocol.md section 13.12). Provisional until the 50,000-connection benchmark
            # (section 13.17) sets it. A smaller budget refuses new /3 admissions sooner; TCP is
            # never affected.
            multistream-node-budget-bytes=268435456
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
                p.getProperty("udp-sockets", UDPS_SOCKETS_DEFAULT).trim(),
                Boolean.parseBoolean(p.getProperty("multistream", "false").trim()),
                bytes(p, "multistream-node-budget-bytes", MULTISTREAM_NODE_BUDGET_DEFAULT));
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

    /** A nonnegative byte count; anything else (absent, malformed, negative) is the default. */
    private static long bytes(Properties p, String key, long defaultValue) {
        try {
            long value = Long.parseLong(p.getProperty(key, Long.toString(defaultValue)).trim());
            return value >= 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
