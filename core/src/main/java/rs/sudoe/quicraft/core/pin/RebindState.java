// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The client's changed-pin re-bind state (docs/protocol.md 12.16), persisted as the single file
 * {@code <configDir>/quicraft/rebind-v1.bin}.
 *
 * <p>The file is binary and self-checking, so a half-written or damaged one is never read as a
 * valid state: the magic {@code QCRB}, the format version, the length of the UTF-8 JSON detailed
 * state, that JSON, the 1 MiB Bloom bitmap of 12.16, and a SHA-256 over everything before it. The
 * whole thing is replaced atomically, so either the old or the new state is on disk. A missing file
 * is an empty, usable state (a first run); a wrong magic, an unknown format version, an impossible
 * or mismatching length, a bad checksum, an unreadable file or malformed JSON is corruption, which
 * fails closed and is never overwritten.</p>
 *
 * <p>It answers two questions for the connect path, and does no I/O on the network itself:</p>
 *
 * <ul>
 *   <li>{@link #tryBeginAttempt(String, int, long)}: may the client make its one automatic re-binding
 *       attempt for this logical server now? The window is section 6's exponential schedule, one
 *       minute doubling per consecutive failure up to one hour, keyed exactly like a pin: normalized
 *       logical host and logical TCP port, never the fingerprint, so a rotating fingerprint cannot
 *       reset it. {@link #recordAttemptFailure(String, int)} starts or doubles the window and
 *       {@link #recordSuccess(String, int)} clears it. Admission is one call: it checks the window,
 *       capacity and that no attempt for the server is already running, marks the attempt active and
 *       returns the token {@link #endAttempt(Attempt, boolean)} needs, so the same server cannot be
 *       admitted twice.</li>
 *   <li>{@link #shouldNotify(String, int, String)}: may the player be told that this server's
 *       fingerprint changed to this value? True at most once per (server, fingerprint), ever: the
 *       detailed history keeps up to 16 fingerprints per server and
 *       {@link #MAX_FINGERPRINTS_TOTAL} in total, and the monotone Bloom filter still suppresses a
 *       tuple whose history entry has been evicted or whose entry was cleared by a success.</li>
 * </ul>
 *
 * <p>An attempt is active from {@link #tryBeginAttempt(String, int, long)} to
 * {@link #endAttempt(Attempt, boolean)}, and its state is never evicted while it is active. At
 * {@link #MAX_ENTRIES} servers the least recently used entry that is not active is evicted, and only
 * inside a call that then persists the result; when every entry is active there is nothing to evict,
 * and {@link #tryBeginAttempt(String, int, long)} returns null, so the caller uses TCP without a
 * re-bind. A query never changes anything: no insertion, no eviction, no ordering. Entries are
 * persisted in least-recently-used order, so the order survives a restart.</p>
 *
 * <p><b>Fail closed.</b> When the state cannot be read or written, everything is refused:
 * {@link #shouldNotify(String, int, String)} returns false and
 * {@link #tryBeginAttempt(String, int, long)} returns null, so the client uses TCP, never repeats a
 * notice and never resets a backoff window. A failed write disables the state, so whatever the failed
 * call changed in memory is never read again.</p>
 *
 * <p>Thread-safe: every method is synchronized. The time a window starts from is the injected
 * {@code clock}; the {@code now} of {@link #tryBeginAttempt(String, int, long)} must be read from
 * that same clock.</p>
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

    /** The JSON schema version of the detailed state. */
    static final int SCHEMA_VERSION = 1;
    /** The magic of the state file: QUICraft re-bind state. */
    private static final byte[] MAGIC = {'Q', 'C', 'R', 'B'};
    /** The format version of the state file. */
    private static final int FORMAT_VERSION = 1;
    /** Magic, format version and the JSON length, before the JSON. */
    private static final int HEADER_BYTES = MAGIC.length + 4 + 4;
    /** The SHA-256 after the bitmap. */
    private static final int DIGEST_BYTES = 32;
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
        Attempt active;

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

    /**
     * One re-binding attempt: the token {@link #tryBeginAttempt(String, int, long)} returns and
     * {@link #endAttempt(Attempt, boolean)} accepts. Its identity is what makes a late end a no-op,
     * so only the caller that started an attempt can finish it, and a query can never end one.
     */
    public static final class Attempt {
        private final String host;
        private final int port;

        private Attempt(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** The normalized logical host this attempt belongs to. */
        public String host() {
            return host;
        }

        /** The logical TCP port this attempt belongs to. */
        public int port() {
            return port;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final int maxEntries;
    private final int maxFingerprintsPerServer;
    private final int maxFingerprintsTotal;
    /** Least recently used first, so one eviction order serves capacity and the file. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final RebindBloom bloom = new RebindBloom();
    private int noticeCount;
    private boolean disabled;

    private RebindState(Path file, LongSupplier clock, int maxEntries,
                        int maxFingerprintsPerServer, int maxFingerprintsTotal) {
        if (maxEntries < 1 || maxFingerprintsPerServer < 1
                || maxFingerprintsTotal < maxFingerprintsPerServer) {
            throw new IllegalArgumentException("re-bind state limits are not usable");
        }
        this.file = file;
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.maxFingerprintsPerServer = maxFingerprintsPerServer;
        this.maxFingerprintsTotal = maxFingerprintsTotal;
    }

    /** Loads the re-bind state; a missing file is an empty, usable one. */
    public static RebindState load(Path file) {
        return load(file, System::currentTimeMillis);
    }

    /**
     * Loads the re-bind state, reading the current time from {@code clock}.
     *
     * @param file the state file, or null for a state that is never persisted
     */
    static RebindState load(Path file, LongSupplier clock) {
        return load(file, clock, MAX_ENTRIES, MAX_FINGERPRINTS_PER_SERVER, MAX_FINGERPRINTS_TOTAL);
    }

    /**
     * Loads the re-bind state with explicit limits. The public entry points use the caps of
     * docs/protocol.md 12.16 ({@link #MAX_ENTRIES}, {@link #MAX_FINGERPRINTS_PER_SERVER},
     * {@link #MAX_FINGERPRINTS_TOTAL}); tests inject smaller ones to exercise the eviction paths.
     */
    static RebindState load(Path file, LongSupplier clock, int maxEntries,
                            int maxFingerprintsPerServer, int maxFingerprintsTotal) {
        RebindState state = new RebindState(file, clock, maxEntries,
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
     * Starts the one automatic re-binding attempt for {@code host}:{@code port} at {@code now},
     * which must come from the same clock this state was loaded with. It checks everything in one
     * place, so admission cannot be raced or half-taken: the state must be reliable, the server must
     * not be inside its backoff window, no attempt for it may already be running, and its state must
     * fit (docs/protocol.md 12.16). When it returns a token the attempt is active, its state is
     * protected from eviction, and the caller must end it with {@link #endAttempt(Attempt, boolean)}.
     *
     * @return the attempt token, or null while the state is disabled, the server is inside its
     *     window, an attempt for it is already active, or there is no evictable entry to hold a new
     *     server's state, in which case the caller uses TCP without a re-bind
     */
    public synchronized Attempt tryBeginAttempt(String host, int port, long now) {
        PinKey key = PinKey.of(host, port);
        if (disabled) {
            return null;
        }
        String mapKey = PinKey.mapKey(key);
        Entry entry = entries.get(mapKey);
        boolean evicted = false;
        if (entry == null) {
            int before = entries.size();
            if (!makeRoom()) {
                return null;
            }
            evicted = entries.size() < before;
            entry = new Entry(key.host(), key.port());
            entries.put(mapKey, entry);
        } else {
            if (entry.active != null || entry.blockedUntil > now) {
                return null;
            }
            touch(mapKey, entry);
        }
        Attempt attempt = new Attempt(key.host(), key.port());
        entry.active = attempt;
        if (evicted && !persist()) {
            disabled = true;
            return null;
        }
        return attempt;
    }

    /**
     * Ends the attempt {@code attempt} started, and nothing else: a token that is not this server's
     * active attempt is ignored, so a late caller cannot clear an attempt that replaced it.
     *
     * @param success whether the server was reached, which clears this server's window and notice
     *     history (section 6); on failure the caller records the window with
     *     {@link #recordAttemptFailure(String, int)}
     * @return false when the token was not the active attempt, or when a successful end could not be
     *     persisted, which disables the state
     */
    public synchronized boolean endAttempt(Attempt attempt, boolean success) {
        if (attempt == null) {
            return false;
        }
        PinKey key = PinKey.of(attempt.host(), attempt.port());
        if (disabled) {
            return false;
        }
        String mapKey = PinKey.mapKey(key);
        Entry entry = entries.get(mapKey);
        if (entry == null || entry.active != attempt) {
            return false;
        }
        entry.active = null;
        if (!success) {
            return true;
        }
        entries.remove(mapKey);
        noticeCount -= entry.notices.size();
        if (!persist()) {
            disabled = true;
            return false;
        }
        return true;
    }

    /**
     * Records a failed re-binding attempt: the next one is allowed after section 6's window, one
     * minute doubling per consecutive failure up to one hour, starting at the injected clock's
     * current time. The connect path calls it after {@link #endAttempt(Attempt, boolean)} with
     * {@code success} false; an entry recorded without a live attempt is honoured all the same.
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
     * <p>A suppressed notice changes nothing at all: the tuple is looked up in the detailed history
     * and in the bitmap before any insertion, eviction or reordering happens, so a query-like call
     * that returns false leaves the state exactly as it was. A notice that cannot be recorded inside
     * the caps without trimming an active attempt's notices is refused the same way.</p>
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
            return false;
        }
        if (bloom.contains(key, fingerprint)) {
            // Marked before, though its detailed history entry has been evicted or cleared.
            return false;
        }
        if (!noticeFits(entry)) {
            // The caps cannot be met without trimming an active attempt's notices: no notice.
            return false;
        }
        if (entry == null) {
            if (!makeRoom()) {
                // No room for this server's history at all: say nothing rather than risk a repeat.
                return false;
            }
            entry = new Entry(key.host(), key.port());
            entries.put(mapKey, entry);
        } else {
            touch(mapKey, entry);
        }
        if (!bloom.add(key, fingerprint)) {
            // Unreachable after contains said the bits were not all set; fail closed all the same.
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
     * Reads the state file. Only a file that is really not there is an empty state; every other
     * failure (permissions, a directory in its place, a damaged container) is corruption.
     */
    private void read() throws IOException {
        if (file == null) {
            return;
        }
        byte[] content;
        try {
            content = StateFile.read(file, MAX_FILE_BYTES);
        } catch (NoSuchFileException e) {
            return;
        }
        if (content.length < HEADER_BYTES + RebindBloom.BYTES + DIGEST_BYTES) {
            throw new IOException("re-bind state is too short");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (content[i] != MAGIC[i]) {
                throw new IOException("re-bind state has the wrong magic");
            }
        }
        ByteBuffer header = ByteBuffer.wrap(content);
        header.position(MAGIC.length);
        int format = header.getInt();
        if (format != FORMAT_VERSION) {
            throw new IOException("unsupported re-bind state format version " + format);
        }
        int jsonLength = header.getInt();
        long expected = (long) HEADER_BYTES + jsonLength + RebindBloom.BYTES;
        if (jsonLength < 0 || expected != content.length - DIGEST_BYTES) {
            throw new IOException("re-bind state does not match its declared length");
        }
        byte[] stored = new byte[DIGEST_BYTES];
        System.arraycopy(content, content.length - DIGEST_BYTES, stored, 0, DIGEST_BYTES);
        if (!MessageDigest.isEqual(sha256(content, content.length - DIGEST_BYTES), stored)) {
            throw new IOException("re-bind state failed its checksum");
        }
        readJson(new String(content, HEADER_BYTES, jsonLength, StandardCharsets.UTF_8));
        bloom.read(content, HEADER_BYTES + jsonLength);
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
     * Whether one more notice for {@code entry} fits the caps, assuming the oldest notice of any
     * non-active entry may be evicted and none of an active attempt may be. A null {@code entry} is a
     * server that has no state yet.
     */
    private boolean noticeFits(Entry entry) {
        if (entry != null && entry.active != null && entry.notices.size() >= maxFingerprintsPerServer) {
            return false;
        }
        if (noticeCount + 1 <= maxFingerprintsTotal) {
            return true;
        }
        return evictableNoticeExists();
    }

    /** Whether some entry may give up a notice: any entry that is not an active attempt. */
    private boolean evictableNoticeExists() {
        for (Entry candidate : entries.values()) {
            if (candidate.active == null && !candidate.notices.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The entry for {@code mapKey}, created and made room for when it is missing. Only mutating calls
     * use this; a query never inserts.
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
            if (candidate.getValue().active == null) {
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

    /**
     * Keeps {@code entry} inside the per-server cap and the total cap, evicting oldest first. An
     * active attempt's notices are never trimmed, so the caller must have checked
     * {@link #noticeFits(Entry)} first.
     */
    private void trimNotices(Entry entry) {
        while (entry.active == null && entry.notices.size() > maxFingerprintsPerServer) {
            entry.notices.remove(0);
            noticeCount--;
        }
        while (noticeCount > maxFingerprintsTotal) {
            Entry oldest = null;
            for (Entry candidate : entries.values()) {
                if (candidate.active == null && !candidate.notices.isEmpty()) {
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
     * Persists the whole state as one atomically replaced file: the JSON detailed state and the
     * Bloom bitmap travel together, so a crash leaves either the old or the new state, never a
     * mixture. A failure disables the state.
     */
    private boolean persist() {
        if (file == null) {
            return true;
        }
        try {
            StateFile.write(file, encode());
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "QUICraft could not write its re-bind state and disables it ({0})",
                    String.valueOf(e));
            return false;
        }
    }

    /**
     * The whole state file: magic, format version, JSON length, the JSON, the 1 MiB bitmap and a
     * SHA-256 over everything before it.
     */
    private byte[] encode() {
        byte[] json = encodeJson().getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[HEADER_BYTES + json.length + RebindBloom.BYTES + DIGEST_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(content);
        buffer.put(MAGIC);
        buffer.putInt(FORMAT_VERSION);
        buffer.putInt(json.length);
        buffer.put(json);
        bloom.copyTo(content, HEADER_BYTES + json.length);
        byte[] digest = sha256(content, content.length - DIGEST_BYTES);
        System.arraycopy(digest, 0, content, content.length - DIGEST_BYTES, DIGEST_BYTES);
        return content;
    }

    /** The first {@code length} bytes of {@code content} hashed with SHA-256. */
    private static byte[] sha256(byte[] content, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content, 0, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private String encodeJson() {
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
