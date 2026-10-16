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
 *
 * <p><b>Activation failures are a separate state.</b> §12.14 makes an activation failure
 * ({@code /3} binding or cipher cutover) override a previous handshake success, and clears it only
 * by a later successful activation for that key. So {@link #recordSuccess(InetAddress, int,
 * Fingerprint)}, which the connect path calls when the QUIC handshake wins, must not clear it:
 * the two live in different maps, keyed the same way. {@link #recordActivationFailure(InetAddress,
 * int, Fingerprint)} sets the activation entry, {@link #isActivationBlocked(InetAddress, int,
 * Fingerprint, long)} reads it and {@link #clearActivationFailure(InetAddress, int, Fingerprint)}
 * is what a successful activation calls. Both maps use §6's schedule and both are persisted; a file
 * written before this state existed loads unchanged, because the activation entries carry their own
 * key prefix.
 *
 * <p><b>Unseen-prerequisite closes.</b> 13.12 makes repeated authenticated {@code unseen-prerequisite}
 * closes the only resource close that writes this cache: {@link #recordUnseenPrerequisite(InetAddress,
 * int, Fingerprint)} keeps the time of the last counted event per key, under its own key prefix, and
 * the second counted event within ten minutes writes a shortest-step entry through
 * {@link #recordShortestFailure(InetAddress, int, Fingerprint)}. A handshake success does not clear
 * the counter, so successive joins can reach two events.</p>
 */
public final class FailureCache {
    static final long INITIAL_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(1);
    static final long MAX_BACKOFF_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /**
     * Prefix of an activation-failure key in the properties file. §6's on-disk format is one
     * {@code key=failures,blockedUntil} property per entry, and this prefix is what tells the two
     * kinds apart, so a file written before activation failures existed still reads as handshake
     * failures only.
     */
    static final String ACTIVATION_PREFIX = "activation|";
    /** Prefix of 13.12's unseen-prerequisite counter: {@code key=1,lastCountedEventTime}. */
    static final String UNSEEN_PREFIX = "unseen|";
    /** 13.12: two counted unseen-prerequisite events within this interval write an entry. */
    static final long UNSEEN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);

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
    /** §12.14's activation failures, keyed exactly like {@link #entries} and never cleared by a handshake success. */
    private final Map<String, Entry> activationEntries = new HashMap<>();
    /** 13.12's last counted unseen-prerequisite event per key, in {@link Entry#blockedUntil}. */
    private final Map<String, Entry> unseen = new HashMap<>();

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
                    if (key.startsWith(UNSEEN_PREFIX)) {
                        // The counter's time is the last counted event: keep it only while it can
                        // still pair with a new one, and never trust a time far in the future.
                        if (e.failures == 1 && now - e.blockedUntil < UNSEEN_INTERVAL_MILLIS
                                && e.blockedUntil - now <= UNSEEN_INTERVAL_MILLIS) {
                            cache.unseen.put(key.substring(UNSEEN_PREFIX.length()), e);
                        }
                        continue;
                    }
                    // Ignore nonsense rather than trusting it; drop long-expired entries. A
                    // shortest-step entry with no failure counted (13.12) is kept only while its
                    // window runs, because it has nothing else to remember.
                    if ((e.failures > 0 || (e.failures == 0 && e.blockedUntil > now))
                            && e.blockedUntil - now <= MAX_BACKOFF_MILLIS
                            && now - e.blockedUntil < TimeUnit.DAYS.toMillis(7)) {
                        if (key.startsWith(ACTIVATION_PREFIX)) {
                            cache.activationEntries.put(key.substring(ACTIVATION_PREFIX.length()), e);
                        } else {
                            cache.entries.put(key, e);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.INFO, "QUIC failure cache unreadable, starting empty: {0}", String.valueOf(e));
                cache.entries.clear();
                cache.activationEntries.clear();
                cache.unseen.clear();
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

    /**
     * Records a failure at §6's SHORTEST backoff step, for docs/protocol.md 13.12's two shortest-step
     * writers: a peer {@code 0x12} received after the client's own ack-eliciting data sat unACKed
     * for the fixed 10 seconds, and two counted unseen-prerequisite events. The window is one minute
     * from now, but an existing longer window is kept as it is, and the consecutive-failure count
     * (the exponent) is never increased: a fresh entry counts zero failures, so the next ordinary
     * failure still starts at one minute.
     */
    public synchronized void recordShortestFailure(InetAddress address, int port, Fingerprint fingerprint) {
        shortest(key(address, port, fingerprint));
        save();
    }

    private void shortest(String key) {
        Entry previous = entries.get(key);
        long until = clock.getAsLong() + INITIAL_BACKOFF_MILLIS;
        if (previous != null && previous.blockedUntil > until) {
            until = previous.blockedUntil;
        }
        entries.put(key, new Entry(previous == null ? 0 : previous.failures, until));
    }

    /**
     * Counts one authenticated unseen-prerequisite event for this key (docs/protocol.md 13.12,
     * "Repeated unseen-prerequisite closes"): either a received application {@code CONNECTION_CLOSE}
     * with {@code 0x12} and exactly the {@code unseen-prerequisite} phrase while {@code /3} was active
     * after SERVER_SWITCH verified, or the client's own blocked-head timer expiry under the same
     * conditions. The caller has already checked those conditions. When the previous counted event
     * for this key lies within ten minutes, this writes a shortest-step entry
     * ({@link #recordShortestFailure(InetAddress, int, Fingerprint)}), so the next join uses TCP until
     * it expires; a single event writes no entry. The last event's time is kept either way, so two
     * successive events always pair.
     *
     * @return true when this event wrote the shortest-step entry
     */
    public synchronized boolean recordUnseenPrerequisite(InetAddress address, int port, Fingerprint fingerprint) {
        String key = key(address, port, fingerprint);
        long now = clock.getAsLong();
        Entry previous = unseen.get(key);
        // A time ahead of the clock (it moved back) still pairs: the stricter reading.
        boolean second = previous != null && now - previous.blockedUntil < UNSEEN_INTERVAL_MILLIS;
        unseen.put(key, new Entry(1, now));
        if (second) {
            shortest(key);
        }
        save();
        return second;
    }

    public synchronized void recordSuccess(InetAddress address, int port, Fingerprint fingerprint) {
        if (entries.remove(key(address, port, fingerprint)) != null) {
            save();
        }
    }

    /**
     * Records a {@code /3} activation failure for this key (docs/protocol.md 12.14), which §6's
     * backoff schedule then applies to exactly like a handshake failure. It is a state of its own:
     * {@link #recordSuccess(InetAddress, int, Fingerprint)} does not clear it, because an
     * activation failure overrides a handshake success, and only a later successful activation does
     * ({@link #clearActivationFailure(InetAddress, int, Fingerprint)}).
     */
    public synchronized void recordActivationFailure(InetAddress address, int port, Fingerprint fingerprint) {
        String key = key(address, port, fingerprint);
        Entry previous = activationEntries.get(key);
        int failures = previous == null ? 1 : Math.min(previous.failures + 1, 32);
        long backoff = Math.min(INITIAL_BACKOFF_MILLIS << Math.min(failures - 1, 20), MAX_BACKOFF_MILLIS);
        activationEntries.put(key, new Entry(failures, clock.getAsLong() + backoff));
        save();
    }

    /**
     * Whether a {@code /3} activation is still backing off for this key at {@code now}, which the
     * caller reads from the same clock this cache was loaded with. While it is blocked the connect
     * path uses TCP, exactly as §6 does for a handshake failure (12.14).
     */
    public synchronized boolean isActivationBlocked(InetAddress address, int port, Fingerprint fingerprint,
            long now) {
        Entry e = activationEntries.get(key(address, port, fingerprint));
        return e != null && now < e.blockedUntil;
    }

    /**
     * Clears the activation-failure state for this key, which only a successful activation does
     * (docs/protocol.md 12.14).
     */
    public synchronized void clearActivationFailure(InetAddress address, int port, Fingerprint fingerprint) {
        if (activationEntries.remove(key(address, port, fingerprint)) != null) {
            save();
        }
    }

    /** Forgets every failure, e.g. after the player fixed their firewall. */
    public synchronized void clear() {
        if (!entries.isEmpty() || !activationEntries.isEmpty() || !unseen.isEmpty()) {
            entries.clear();
            activationEntries.clear();
            unseen.clear();
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
        for (Map.Entry<String, Entry> e : activationEntries.entrySet()) {
            p.setProperty(ACTIVATION_PREFIX + e.getKey(),
                    e.getValue().failures + "," + e.getValue().blockedUntil);
        }
        for (Map.Entry<String, Entry> e : unseen.entrySet()) {
            p.setProperty(UNSEEN_PREFIX + e.getKey(), e.getValue().failures + "," + e.getValue().blockedUntil);
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
