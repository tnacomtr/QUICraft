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

/**
 * {@code config/quicraft/client.properties}: the transport setting (docs/protocol.md §4) and the
 * multistream switch (12.1: off by default, so a client without it set behaves exactly as before).
 */
final class ClientSettings {
    static final String FILE = "client.properties";

    /** What the file holds. */
    record Loaded(Mode mode, boolean multistream) {
        static final Loaded DEFAULTS = new Loaded(Mode.AUTO, false);
    }

    private ClientSettings() {}

    static Loaded load(Path dir) {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            save(dir, Loaded.DEFAULTS.mode(), Loaded.DEFAULTS.multistream());
            return Loaded.DEFAULTS;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | RuntimeException e) {
            return Loaded.DEFAULTS;
        }
        return new Loaded(parse(p.getProperty("transport", "auto")),
                parseBoolean(p.getProperty("multistream", "false")));
    }

    /** Only an explicit {@code true} turns a switch on: anything else, a typo included, is off. */
    static boolean parseBoolean(String value) {
        return value != null && value.trim().equalsIgnoreCase("true");
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

    static void save(Path dir, Mode mode, boolean multistream) {
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(dir.resolve(FILE), StandardCharsets.UTF_8)) {
                w.write("# QUICraft client.\n"
                        + "# auto: QUIC when the server advertises it, else TCP (default)\n"
                        + "# tcp-only: always TCP, like vanilla\n"
                        + "# quic-only: QUIC without TCP fallback (debugging); failures are shown\n"
                        + "transport=" + name(mode) + "\n"
                        + "# multistream: use QUIC multi-stream (quicraft/3) when the server offers it and the\n"
                        + "# QUIC library supports it. Off by default; on, a failed attempt falls back to TCP and says so.\n"
                        + "multistream=" + multistream + "\n");
            }
        } catch (IOException | RuntimeException e) {
            rs.sudoe.quicraft.fabric.QuicraftFabric.LOG.warn("QUICraft: could not save {}: {}", FILE, e.toString());
        }
    }
}
