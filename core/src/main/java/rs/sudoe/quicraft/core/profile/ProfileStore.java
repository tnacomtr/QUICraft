// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import rs.sudoe.quicraft.core.multistream.LogicalStream;

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
    /** Moves the temporary file over the override file. Package-private so tests can inject a failing move. */
    interface FileMover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private final Path file;
    private final ServerProfile defaults;
    private final FileMover mover;

    /**
     * @param file the override file, created on first save
     * @param defaults the profile used when no override exists, and by {@link #restoreDefaults()}
     */
    public ProfileStore(Path file, ServerProfile defaults) {
        this(file, defaults, Files::move);
    }

    ProfileStore(Path file, ServerProfile defaults, FileMover mover) {
        this.file = Objects.requireNonNull(file, "file");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.mover = Objects.requireNonNull(mover, "mover");
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
                clampLanes(intValue(p, "maxLanes")),
                longValue(p, "nodeBudgetBytes"),
                doubleValue(p, "preLoginPoolFraction"),
                intValue(p, "queuePacketLimit"),
                longValue(p, "queueByteLimit"),
                intValue(p, "controlWeight"),
                intValue(p, "realtimeWeight"),
                intValue(p, "chunkWeight"),
                // Added after the first profile format: an override file without them keeps the defaults.
                optionalInt(p, "sessionCallsPerPrefix", defaults.sessionCallsPerPrefix),
                optionalLong(p, "declaredBytesPerPrefix", defaults.declaredBytesPerPrefix));
        value.validate();
        return value;
    }

    /**
     * The /3 shard cap is 5 (the ACK stream is the sixth server-opened stream). An override file written
     * for the earlier cap of 6 loads as 5 instead of failing, so an old file never disables /3.
     */
    private static int clampLanes(int lanes) {
        return lanes == LogicalStream.MAX_CHUNKS + 1 ? LogicalStream.MAX_CHUNKS : lanes;
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
        p.setProperty("sessionCallsPerPrefix", Integer.toString(value.sessionCallsPerPrefix));
        p.setProperty("declaredBytesPerPrefix", Long.toString(value.declaredBytesPerPrefix));
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
            // No non-atomic fallback: a plain replace can leave a half-written profile after a crash. The
            // temporary file is deleted below and the old override stays in place.
            try {
                mover.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("the filesystem cannot replace " + absolute + " atomically; profile not saved",
                        unsupported);
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

    /** An optional integer field: the fallback when the key is absent, a named error when it is malformed. */
    private static int optionalInt(Properties p, String key, int fallback) {
        return p.getProperty(key) == null ? fallback : intValue(p, key);
    }

    /** An optional long field: the fallback when the key is absent, a named error when it is malformed. */
    private static long optionalLong(Properties p, String key, long fallback) {
        return p.getProperty(key) == null ? fallback : longValue(p, key);
    }

    private static double doubleValue(Properties p, String key) {
        try {
            return Double.parseDouble(required(p, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("profile field " + key + " is not a number", e);
        }
    }
}
