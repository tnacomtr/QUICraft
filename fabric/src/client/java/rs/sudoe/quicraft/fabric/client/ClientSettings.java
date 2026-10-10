// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;

/** {@code config/quicraft/client.properties}: the transport setting (docs/protocol.md §4). */
final class ClientSettings {
    static final String FILE = "client.properties";

    private ClientSettings() {}

    static Mode load(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            save(dir, Mode.AUTO);
            return Mode.AUTO;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | RuntimeException e) {
            return Mode.AUTO;
        }
        return parse(p.getProperty("transport", "auto"));
    }

    static Mode parse(String value) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "tcp-only":
                return Mode.TCP_ONLY;
            case "quic-only":
                return Mode.QUIC_ONLY;
            default:
                return Mode.AUTO;
        }
    }

    static String name(Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static void save(Path dir, Mode mode) {
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(dir.resolve(FILE), StandardCharsets.UTF_8)) {
                w.write("# QUICraft client.\n"
                        + "# auto: QUIC when the server advertises it, else TCP (default)\n"
                        + "# tcp-only: always TCP, like vanilla\n"
                        + "# quic-only: QUIC without TCP fallback (debugging); failures are shown\n"
                        + "transport=" + name(mode) + "\n");
            }
        } catch (IOException | RuntimeException e) {
            rs.sudoe.quicraft.fabric.QuicraftFabric.LOG.warn("QUICraft: could not save {}: {}", FILE, e.toString());
        }
    }
}
