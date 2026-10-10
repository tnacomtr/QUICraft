// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * Remembers QUIC failures per (IP, QUIC port, fingerprint) with exponential backoff, persisted
 * across restarts (docs/protocol.md §6): 1 minute, doubling per consecutive failure, at most
 * 1 hour. A success clears the entry. A missing or corrupt file reads as empty. Thread-safe.
 */
public final class FailureCache {
    static final long INITIAL_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(1);
    static final long MAX_BACKOFF_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final Logger LOG = Logger.getLogger("QUICraft");

    private static final class Entry {
        final int failures;
        final long blockedUntil;

        Entry(int failures, long blockedUntil) {
            this.failures = failures;
            this.blockedUntil = blockedUntil;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new HashMap<>();

    private FailureCache(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Loads from {@code file} (null: memory only). */
    public static FailureCache load(Path file) {
        return load(file, System::currentTimeMillis);
    }

    static FailureCache load(Path file, LongSupplier clock) {
        FailureCache cache = new FailureCache(file, clock);
        if (file != null && Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(in);
                long now = clock.getAsLong();
                for (String key : p.stringPropertyNames()) {
                    String[] parts = p.getProperty(key).split(",");
                    Entry e = new Entry(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
                    // Ignore nonsense rather than trusting it; drop long-expired entries.
                    if (e.failures > 0 && e.blockedUntil - now <= MAX_BACKOFF_MILLIS
                            && now - e.blockedUntil < TimeUnit.DAYS.toMillis(7)) {
                        cache.entries.put(key, e);
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.INFO, "QUIC failure cache unreadable, starting empty: {0}", String.valueOf(e));
                cache.entries.clear();
            }
        }
        return cache;
    }

    static String key(InetAddress address, int port, Fingerprint fingerprint) {
        return address.getHostAddress() + "|" + port + "|" + fingerprint;
    }

    /** Whether QUIC may be tried now. */
    public synchronized boolean allows(InetAddress address, int port, Fingerprint fingerprint) {
        Entry e = entries.get(key(address, port, fingerprint));
        return e == null || clock.getAsLong() >= e.blockedUntil;
    }

    public synchronized void recordFailure(InetAddress address, int port, Fingerprint fingerprint) {
        String key = key(address, port, fingerprint);
        Entry previous = entries.get(key);
        int failures = previous == null ? 1 : Math.min(previous.failures + 1, 32);
        long backoff = Math.min(INITIAL_BACKOFF_MILLIS << Math.min(failures - 1, 20), MAX_BACKOFF_MILLIS);
        entries.put(key, new Entry(failures, clock.getAsLong() + backoff));
        save();
    }

    public synchronized void recordSuccess(InetAddress address, int port, Fingerprint fingerprint) {
        if (entries.remove(key(address, port, fingerprint)) != null) {
            save();
        }
    }

    /** Forgets every failure, e.g. after the player fixed their firewall. */
    public synchronized void clear() {
        if (!entries.isEmpty()) {
            entries.clear();
            save();
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            p.setProperty(e.getKey(), e.getValue().failures + "," + e.getValue().blockedUntil);
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
            try {
                try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    p.store(out, "QUICraft: QUIC failures per server (docs/protocol.md section 6)");
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "Could not save the QUIC failure cache: {0}", String.valueOf(e));
        }
    }
}
