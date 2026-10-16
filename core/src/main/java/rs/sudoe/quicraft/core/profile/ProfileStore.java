// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

/**
 * Persisted server-profile overrides (docs/protocol.md 12.13, 13.12, 13.20).
 *
 * <p>The override file is a properties file. {@link #save} validates first, so a bad combination
 * writes nothing; it then writes a temporary file and moves it over the old one atomically, so a crash
 * leaves either the old or the new profile, never a mix. {@link #load} returns the validated override,
 * or the defaults when there is no file. A loaded snapshot is immutable: callers apply it to new
 * connections only.
 */
public final class ProfileStore {
    private final Path file;
    private final ServerProfile defaults;

    /**
     * @param file the override file, created on first save
     * @param defaults the profile used when no override exists, and by {@link #restoreDefaults()}
     */
    public ProfileStore(Path file, ServerProfile defaults) {
        this.file = Objects.requireNonNull(file, "file");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        defaults.validate();
    }

    /**
     * Loads the override, validated, or the defaults when no override file exists. A file that is
     * present but malformed or fails validation throws; the listener keeps its current profile.
     */
    public ServerProfile load() throws IOException {
        if (!Files.exists(file)) {
            return defaults;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        ServerProfile value = new ServerProfile(
                intValue(p, "maxConnections"),
                intValue(p, "maxLanes"),
                longValue(p, "nodeBudgetBytes"),
                doubleValue(p, "preLoginPoolFraction"),
                intValue(p, "queuePacketLimit"),
                longValue(p, "queueByteLimit"),
                intValue(p, "controlWeight"),
                intValue(p, "realtimeWeight"),
                intValue(p, "chunkWeight"));
        value.validate();
        return value;
    }

    /**
     * Validates {@code value}, then atomically replaces the override file with it. A value that fails
     * validation throws before anything is written.
     */
    public void save(ServerProfile value) throws IOException {
        value.validate();
        Properties p = new Properties();
        p.setProperty("maxConnections", Integer.toString(value.maxConnections));
        p.setProperty("maxLanes", Integer.toString(value.maxLanes));
        p.setProperty("nodeBudgetBytes", Long.toString(value.nodeBudgetBytes));
        p.setProperty("preLoginPoolFraction", Double.toString(value.preLoginPoolFraction));
        p.setProperty("queuePacketLimit", Integer.toString(value.queuePacketLimit));
        p.setProperty("queueByteLimit", Long.toString(value.queueByteLimit));
        p.setProperty("controlWeight", Integer.toString(value.controlWeight));
        p.setProperty("realtimeWeight", Integer.toString(value.realtimeWeight));
        p.setProperty("chunkWeight", Integer.toString(value.chunkWeight));
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                p.store(out, "QUICraft server profile");
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Restores the defaults by removing the override file. Idempotent. */
    public void restoreDefaults() throws IOException {
        Files.deleteIfExists(file);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("missing profile field: " + key);
        }
        return value.trim();
    }

    private static int intValue(Properties p, String key) {
        try {
            return Integer.parseInt(required(p, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("profile field " + key + " is not an integer", e);
        }
    }

    private static long longValue(Properties p, String key) {
        try {
            return Long.parseLong(required(p, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("profile field " + key + " is not an integer", e);
        }
    }

    private static double doubleValue(Properties p, String key) {
        try {
            return Double.parseDouble(required(p, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("profile field " + key + " is not a number", e);
        }
    }
}
