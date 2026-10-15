// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The client's changed-pin re-bind state (docs/protocol.md 12.16), persisted as
 * {@code <configDir>/quicraft/rebind-v1.json} plus the {@code <configDir>/quicraft/rebind-bloom-v1.bin}
 * filter of 12.16.
 *
 * <p>It answers two questions for the connect path, and does no I/O on the network itself:</p>
 *
 * <ul>
 *   <li>{@link #allowsAttempt(String, int, long)}: may the client make its one automatic re-binding
 *       attempt for this logical server now? The window is section 6's exponential schedule, one
 *       minute doubling per consecutive failure up to one hour, keyed exactly like a pin: normalized
 *       logical host and logical TCP port, never the fingerprint, so a rotating fingerprint cannot
 *       reset it. {@link #recordAttemptFailure(String, int)} starts or doubles the window and
 *       {@link #recordSuccess(String, int)} clears the server's entry.</li>
 *   <li>{@link #shouldNotify(String, int, String)}: may the player be told that this server's
 *       fingerprint changed to this value? True at most once per (server, fingerprint), ever: the
 *       detailed history keeps up to 16 fingerprints per server and
 *       {@link #MAX_FINGERPRINTS_TOTAL} in total, and the monotone Bloom filter still suppresses a
 *       tuple whose history entry has been evicted or whose entry was cleared by a success.</li>
 * </ul>
 *
 * <p>An attempt can be marked with {@link #beginAttempt(String, int)} and
 * {@link #endAttempt(String, int)}: its state is never evicted while it is active. At
 * {@link #MAX_ENTRIES} servers the least recently used entry that is not active is evicted; when
 * every entry is active there is nothing to evict, and {@link #allowsAttempt(String, int, long)}
 * returns false, so the caller uses TCP without a re-bind. Entries are persisted in
 * least-recently-used order, so the order survives a restart.</p>
 *
 * <p><b>Fail closed.</b> When the state cannot be read or written, everything is refused:
 * {@link #shouldNotify(String, int, String)} returns false and {@link #allowsAttempt(String, int, long)}
 * returns false, so the client uses TCP, never repeats a notice and never resets a backoff window.</p>
 *
 * <p>The two files are one unit. Both missing is an empty, usable state (a first run). If exactly
 * one of them exists, what is left cannot say which notices were already shown, so the state is
 * unreliable: it fails closed on load and neither file is ever written. Both present is loaded.
 * They cannot be replaced in one transaction, so every persist writes them in the same order, the
 * filter ({@code rebind-bloom-v1.bin}) first and the detailed state ({@code rebind-v1.json}) second,
 * each atomically through a temporary file and a rename. A failure in either disables the state, so
 * whatever the failed call changed in memory is never read again.</p>
 *
 * <p>Thread-safe: every method is synchronized. The time a window starts from is the injected
 * {@code clock}; {@code allowsAttempt}'s {@code now} must be read from that same clock, which is
 * what the two-argument overload does.</p>
 */
public final class RebindState {
    /** The most logical servers that keep re-bind state; docs/protocol.md 12.16. */
    public static final int MAX_ENTRIES = 4096;
    /** The most fingerprints remembered in detail per server; docs/protocol.md 12.16. */
    public static final int MAX_FINGERPRINTS_PER_SERVER = 16;
    /** The most fingerprints remembered in detail in total; docs/protocol.md 12.16. */
    public static final int MAX_FINGERPRINTS_TOTAL = 65536;

    /** Section 6's first backoff: one minute. */
    static final long INITIAL_BACKOFF_MILLIS = 60_000L;
    /** Section 6's longest backoff: one hour. */
    static final long MAX_BACKOFF_MILLIS = 3_600_000L;

    /** The schema version of the re-bind state file. */
    static final int SCHEMA_VERSION = 1;
    private static final int MAX_FAILURES = 32;
    /** Above the largest re-bind state 4096 servers with 16 notices each can make (about 10 MiB). */
    private static final long MAX_FILE_BYTES = 24L << 20;
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** One fingerprint this server was noticed with, oldest first in its server's history. */
    private static final class Notice {
        final String fingerprint;
        final Instant lastNoticed;

        Notice(String fingerprint, Instant lastNoticed) {
            this.fingerprint = fingerprint;
            this.lastNoticed = lastNoticed;
        }
    }

    /** One logical server: its backoff window, its active attempt and its notice history. */
    private static final class Entry {
        final String host;
        final int port;
        final List<Notice> notices = new ArrayList<>();
        int failures;
        long blockedUntil;
        boolean active;

        Entry(String host, int port) {
            this.host = host;
            this.port = port;
        }

        boolean hasNotice(String fingerprint) {
            for (Notice notice : notices) {
                if (notice.fingerprint.equals(fingerprint)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Path file;
    private final Path bloomFile;
    private final LongSupplier clock;
    private final int maxEntries;
    private final int maxFingerprintsPerServer;
    private final int maxFingerprintsTotal;
    /** Least recently used first, so one eviction order serves capacity and the file. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final RebindBloom bloom = new RebindBloom();
    private int noticeCount;
    private boolean disabled;

    private RebindState(Path file, Path bloomFile, LongSupplier clock, int maxEntries,
                        int maxFingerprintsPerServer, int maxFingerprintsTotal) {
        if (maxEntries < 1 || maxFingerprintsPerServer < 1
                || maxFingerprintsTotal < maxFingerprintsPerServer) {
            throw new IllegalArgumentException("re-bind state limits are not usable");
        }
        this.file = file;
        this.bloomFile = bloomFile;
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.maxFingerprintsPerServer = maxFingerprintsPerServer;
        this.maxFingerprintsTotal = maxFingerprintsTotal;
    }

    /** Loads the re-bind state; both files missing is an empty, usable one. */
    public static RebindState load(Path file, Path bloomFile) {
        return load(file, bloomFile, System::currentTimeMillis);
    }

    /**
     * Loads the re-bind state, reading the current time from {@code clock}.
     *
     * @param file the backoff and notice history file, or null for memory only
     * @param bloomFile the bloom filter file, or null for memory only
     */
    static RebindState load(Path file, Path bloomFile, LongSupplier clock) {
        return load(file, bloomFile, clock, MAX_ENTRIES, MAX_FINGERPRINTS_PER_SERVER, MAX_FINGERPRINTS_TOTAL);
    }

    /**
     * Loads the re-bind state with explicit limits. The public entry points use the caps of
     * docs/protocol.md 12.16 ({@link #MAX_ENTRIES}, {@link #MAX_FINGERPRINTS_PER_SERVER},
     * {@link #MAX_FINGERPRINTS_TOTAL}); tests inject smaller ones to exercise the eviction paths.
     */
    static RebindState load(Path file, Path bloomFile, LongSupplier clock, int maxEntries,
                            int maxFingerprintsPerServer, int maxFingerprintsTotal) {
        RebindState state = new RebindState(file, bloomFile, clock, maxEntries,
                maxFingerprintsPerServer, maxFingerprintsTotal);
        try {
            state.read();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "QUICraft re-bind state is unreadable and stays disabled ({0})",
                    String.valueOf(e));
            state.entries.clear();
            state.noticeCount = 0;
            state.disabled = true;
        }
        return state;
    }

    /** Whether the state may be read and written; false after corruption or a failed write. */
    public synchronized boolean isUsable() {
        return !disabled;
    }

    /**
     * Whether the one automatic re-binding attempt for {@code host}:{@code port} may start at
     * {@code now}, which must come from the same clock this state was loaded with.
     *
     * @return false during the backoff window, while the state is disabled, and when there is no
     *     evictable entry to hold a new server's state
     */
    public synchronized boolean allowsAttempt(String host, int port, long now) {
        PinKey key = PinKey.of(host, port);
        if (disabled) {
            return false;
        }
        String mapKey = PinKey.mapKey(key);
        Entry entry = entries.get(mapKey);
        if (entry == null) {
            return makeRoom();
        }
        touch(mapKey, entry);
        return entry.blockedUntil <= now;
    }

    /** {@link #allowsAttempt(String, int, long)} at the injected clock's current time. */
    public synchronized boolean allowsAttempt(String host, int port) {
        return allowsAttempt(host, port, clock.getAsLong());
    }

    /**
     * Starts a re-binding attempt for {@code host}:{@code port}, protecting its state from eviction
     * until {@link #endAttempt(String, int)}.
     *
     * @return false when the state is disabled or there is no room for a new server
     */
    public synchronized boolean beginAttempt(String host, int port) {
        PinKey key = PinKey.of(host, port);
        if (disabled) {
            return false;
        }
        Entry entry = entryFor(PinKey.mapKey(key), key);
        if (entry == null) {
            return false;
        }
        entry.active = true;
        return true;
    }

    /** Ends the re-binding attempt, so its state may be evicted again. */
    public synchronized void endAttempt(String host, int port) {
        Entry entry = entries.get(PinKey.mapKey(PinKey.of(host, port)));
        if (entry != null) {
            entry.active = false;
        }
    }

    /**
     * Records a failed re-binding attempt: the next one is allowed after section 6's window, one
     * minute doubling per consecutive failure up to one hour, starting at the injected clock's
     * current time.
     *
     * @return false when the state is disabled or there is no room for a new server
     */
    public synchronized boolean recordAttemptFailure(String host, int port) {
        PinKey key = PinKey.of(host, port);
        if (disabled) {
            return false;
        }
        Entry entry = entryFor(PinKey.mapKey(key), key);
        if (entry == null) {
            return false;
        }
        entry.failures = Math.min(entry.failures + 1, MAX_FAILURES);
        long backoff = Math.min(INITIAL_BACKOFF_MILLIS << Math.min(entry.failures - 1, 20), MAX_BACKOFF_MILLIS);
        entry.blockedUntil = clock.getAsLong() + backoff;
        if (!persist()) {
            disabled = true;
            return false;
        }
        return true;
    }

    /**
     * Records that the server was reached, which clears its backoff window (section 6). Its notice
     * history goes with it; the bloom filter still suppresses the tuples that were noticed.
     */
    public synchronized void recordSuccess(String host, int port) {
        PinKey key = PinKey.of(host, port);
        if (disabled) {
            return;
        }
        Entry removed = entries.remove(PinKey.mapKey(key));
        if (removed == null) {
            return;
        }
        noticeCount -= removed.notices.size();
        if (!persist()) {
            disabled = true;
        }
    }

    /**
     * Whether the player may be told that {@code host}:{@code port} now advertises
     * {@code newFingerprint}, which is the old fingerprint change notice of 12.16. At most once per
     * (server, fingerprint): the tuple is marked and persisted before this returns true, and a tuple
     * that was marked before returns false even after its detailed history entry is gone.
     *
     * @param newFingerprint the advertised fingerprint, lowercase {@code sha256:<64 hex>}
     * @return false while the state is disabled or cannot be written, so a notice is never repeated
     */
    public synchronized boolean shouldNotify(String host, int port, String newFingerprint) {
        PinKey key = PinKey.of(host, port);
        String fingerprint = Fingerprints.check(newFingerprint);
        if (disabled) {
            return false;
        }
        String mapKey = PinKey.mapKey(key);
        Entry entry = entries.get(mapKey);
        if (entry != null && entry.hasNotice(fingerprint)) {
            touch(mapKey, entry);
            return false;
        }
        if (entry == null) {
            entry = entryFor(mapKey, key);
            if (entry == null) {
                // No room for this server's history at all: say nothing rather than risk a repeat.
                return false;
            }
        } else {
            touch(mapKey, entry);
        }
        if (!bloom.add(key, fingerprint)) {
            // Every bit was already set: this tuple was noticed before, though its history is gone.
            return false;
        }
        entry.notices.add(new Notice(fingerprint, Instant.ofEpochMilli(clock.getAsLong())));
        noticeCount++;
        trimNotices(entry);
        if (!persist()) {
            disabled = true;
            return false;
        }
        return true;
    }

    /** How many logical servers keep re-bind state. */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Reads the state. The two files are one unit: both missing is empty and usable, exactly one
     * present is unreliable and fails closed, both present is loaded. Only a file that is really not
     * there counts as missing; a file the process may not read, or a directory in its place, is
     * unreadable state and fails closed like any other read failure.
     */
    private void read() throws IOException {
        boolean jsonPresent = false;
        if (file != null) {
            try {
                readJson(new String(StateFile.read(file, MAX_FILE_BYTES), StandardCharsets.UTF_8));
                jsonPresent = true;
            } catch (NoSuchFileException e) {
                // Not there at all.
            }
        }
        boolean bloomPresent = false;
        if (bloomFile != null) {
            try {
                bloom.read(StateFile.read(bloomFile, RebindBloom.BYTES));
                bloomPresent = true;
            } catch (NoSuchFileException e) {
                // Not there at all.
            }
        }
        if (file != null && bloomFile != null && jsonPresent != bloomPresent) {
            throw new IOException("only one of the two re-bind state files exists");
        }
    }

    private void readJson(String content) {
        Map<String, Object> root = StrictJson.object(StrictJson.parse(content), "re-bind state");
        int version = StrictJson.integer(root.get("version"), "version", 0, Integer.MAX_VALUE);
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported re-bind state version " + version);
        }
        List<Object> servers = StrictJson.array(root.get("servers"), "servers");
        if (servers.size() > maxEntries) {
            throw new IllegalArgumentException("re-bind state holds more than " + maxEntries + " servers");
        }
        for (Object element : servers) {
            Map<String, Object> fields = StrictJson.object(element, "server");
            PinKey key = PinKey.of(StrictJson.string(fields.get("host"), "host"),
                    StrictJson.integer(fields.get("port"), "port", 1, 65535));
            Entry entry = new Entry(key.host(), key.port());
            entry.failures = StrictJson.integer(fields.get("failures"), "failures", 0, MAX_FAILURES);
            entry.blockedUntil = StrictJson.instant(fields.get("blockedUntil"), "blockedUntil").toEpochMilli();
            List<Object> notices = StrictJson.array(fields.get("notices"), "notices");
            if (notices.size() > maxFingerprintsPerServer) {
                throw new IllegalArgumentException("re-bind state has too many notices for one server");
            }
            for (Object noticeElement : notices) {
                Map<String, Object> noticeFields = StrictJson.object(noticeElement, "notice");
                entry.notices.add(new Notice(
                        Fingerprints.check(StrictJson.string(noticeFields.get("fingerprint"), "fingerprint")),
                        StrictJson.instant(noticeFields.get("lastNoticed"), "lastNoticed")));
            }
            noticeCount += entry.notices.size();
            if (entries.put(PinKey.mapKey(key), entry) != null) {
                throw new IllegalArgumentException("re-bind state repeats a server");
            }
        }
        if (noticeCount > maxFingerprintsTotal) {
            throw new IllegalArgumentException("re-bind state holds more than " + maxFingerprintsTotal + " notices");
        }
    }

    /**
     * The entry for {@code mapKey}, created and made room for when it is missing.
     *
     * @return null when the state is full and no entry may be evicted
     */
    private Entry entryFor(String mapKey, PinKey key) {
        Entry entry = entries.get(mapKey);
        if (entry != null) {
            touch(mapKey, entry);
            return entry;
        }
        if (!makeRoom()) {
            return null;
        }
        entry = new Entry(key.host(), key.port());
        entries.put(mapKey, entry);
        return entry;
    }

    /** Evicts the least recently used entry that is not an active attempt, when there is no room. */
    private boolean makeRoom() {
        if (entries.size() < maxEntries) {
            return true;
        }
        String victim = null;
        for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
            if (!candidate.getValue().active) {
                victim = candidate.getKey();
                break;
            }
        }
        if (victim == null) {
            return false;
        }
        Entry evicted = entries.remove(victim);
        noticeCount -= evicted.notices.size();
        return true;
    }

    /** Moves an entry to the end of the eviction order: it was just used. */
    private void touch(String mapKey, Entry entry) {
        entries.remove(mapKey);
        entries.put(mapKey, entry);
    }

    /** Keeps {@code entry} inside the per-server cap and the total cap, evicting oldest first. */
    private void trimNotices(Entry entry) {
        while (entry.notices.size() > maxFingerprintsPerServer) {
            entry.notices.remove(0);
            noticeCount--;
        }
        while (noticeCount > maxFingerprintsTotal) {
            Entry oldest = null;
            for (Entry candidate : entries.values()) {
                if (!candidate.notices.isEmpty()) {
                    oldest = candidate;
                    break;
                }
            }
            if (oldest == null) {
                return;
            }
            oldest.notices.remove(0);
            noticeCount--;
        }
    }

    /**
     * Persists the whole state, the filter first and the detailed state second, so the invariant
     * "both files exist or neither exists" holds. A failure in either half leaves the state
     * disabled rather than half written.
     */
    private boolean persist() {
        return writeBloom() && write();
    }

    private boolean write() {
        if (file == null) {
            return true;
        }
        try {
            StateFile.write(file, encode().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "QUICraft could not write its re-bind state and disables it ({0})",
                    String.valueOf(e));
            return false;
        }
    }

    private boolean writeBloom() {
        if (bloomFile == null) {
            return true;
        }
        try {
            StateFile.write(bloomFile, bloom.copy());
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "QUICraft could not write its notice filter and disables it ({0})",
                    String.valueOf(e));
            return false;
        }
    }

    private String encode() {
        StringBuilder sb = new StringBuilder(1024 + 256 * entries.size());
        sb.append("{\"version\":").append(SCHEMA_VERSION).append(",\"servers\":[");
        boolean first = true;
        for (Entry entry : entries.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"host\":").append(StrictJson.quote(entry.host));
            sb.append(",\"port\":").append(entry.port);
            sb.append(",\"failures\":").append(entry.failures);
            sb.append(",\"blockedUntil\":")
                    .append(StrictJson.quotedInstant(Instant.ofEpochMilli(entry.blockedUntil)));
            sb.append(",\"notices\":[");
            boolean firstNotice = true;
            for (Notice notice : entry.notices) {
                if (!firstNotice) {
                    sb.append(',');
                }
                firstNotice = false;
                sb.append("{\"fingerprint\":").append(StrictJson.quote(notice.fingerprint));
                sb.append(",\"lastNoticed\":").append(StrictJson.quotedInstant(notice.lastNoticed));
                sb.append('}');
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }
}
