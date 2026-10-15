// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The client's authenticated pins, persisted as {@code <configDir>/quicraft/pins-v2.json}
 * (docs/protocol.md 12.15). One pin per normalized logical server
 * ({@link PinKey}: normalized host and logical TCP port, never an SRV target or a resolved address)
 * records the fingerprint of the last endpoint that completed an authenticated login over
 * {@code /3}, with the UTC timestamp it was first and last bound at.
 *
 * <p>A pin means "the last endpoint at this name that completed an authenticated login", not proven
 * server identity (12.20). It is written only by an explicit {@link #recordPin(String, int, String)}
 * call, which the connect path makes after a valid SERVER_SWITCH; nothing here promotes the silent
 * v1 observations of section 6 into a pin, and there is deliberately no API that does.</p>
 *
 * <p><b>Disabled state.</b> A corrupt or unreadable existing file, and a failed write, put the store
 * into a disabled state ({@link #isUsable()} is false) that lasts for the object's lifetime.
 * Corruption is never treated as first contact and the corrupt file is never overwritten
 * automatically; while disabled every write is refused with {@link RecordResult#DISABLED} and the
 * caller notifies the player and uses {@code /2} or TCP. A <em>missing</em> file is an empty, usable
 * store. When {@link #isUsable()} is false the caller must not read an empty
 * {@link #pin(String, int)} result as "no pin was ever recorded".</p>
 *
 * <p><b>Capacity and expiry.</b> At most {@link #MAX_PINS} pins, and a pin only ever goes through
 * {@link #deletePin(String, int)}, which the player must confirm, or through expiry. A pin expires
 * when its last-bound time is more than {@link #PIN_TTL_MILLIS} before the store's clock, or when
 * that time is in the future: a clock moved backwards never revives or extends a pin
 * (docs/protocol.md 12.15). Expired pins are removed before every lookup and before every store-full
 * decision, in one atomic write, and an expired name is first contact again: the next join runs the
 * normal binding exchange, with no changed-fingerprint notice and no re-bind backoff. When the store
 * is full after that, recording a name it does not know returns {@link RecordResult#FULL} and the
 * caller uses TCP for that name until a pin expires or the player deletes one; updating a name it
 * already knows is always allowed.</p>
 *
 * <p><b>Expiry and a running attempt.</b> A pin is never expired while an attempt for its logical
 * server is running (12.15). The caller passes the keys of its attempts in flight,
 * {@link RebindState#activeKeys()}, and expiry skips them: {@link #pin(String, int, Set)} and
 * {@link #recordPin(String, int, String, Set)} for a lookup or a decision,
 * {@link #expiredKeys(Set)} to ask which pins have expired, {@link #purgeExpired(Set)} to remove
 * them. The two shorter overloads take no such set and are for tests only, so a caller with an
 * attempt running always has to say so. Expiry is finished in the order that leaks nothing when the
 * process dies in the middle of it: the expired pins' re-bind state is forgotten first, then the
 * pins go, which is what {@link PinExpiry#purge(PinStore, RebindState, Set)} does in one call.</p>
 *
 * <p>There is no cross-process lock: two clients sharing one config directory writing this file are
 * last-writer-wins, each write being atomic ({@link StateFile}).</p>
 *
 * <p>Writes go through a temporary file in the same directory and an atomic replacement, with
 * owner-only permissions where the file system supports them ({@link StateFile}). The file is JSON,
 * schema version 1, timestamps in UTC ISO-8601. Thread-safe: every method is synchronized.</p>
 *
 * <p><b>Calling it.</b> {@link #recordPin(String, int, String)} and {@link #deletePin(String, int)}
 * write the whole file synchronously, with an fsync of the file and of its directory: never call
 * them on the Netty event loop or the render thread, hand the work to a worker. A lookup writes too
 * when it removes expired pins, so {@link #pin(String, int)} belongs on the same worker: only a
 * lookup that finds nothing to expire reads. A caller that gets {@link RecordResult#DISABLED} should
 * tell the player that pinning is off and use {@code /2} or TCP; {@link #isUsable()} is the same fact
 * for code that only reads.</p>
 */
public final class PinStore {
    /** The most pins the store holds; docs/protocol.md 12.16. */
    public static final int MAX_PINS = 4096;
    /**
     * How long a pin survives without a new binding: 30 days after its last-bound time, after which
     * the pin has expired (docs/protocol.md 12.15). A last-bound time in the future has expired too.
     */
    public static final long PIN_TTL_MILLIS = Duration.ofDays(30).toMillis();
    /** The schema version of the pins file. */
    static final int SCHEMA_VERSION = 1;
    /** Four MiB is far above the largest pins file 4096 pins can make (about 1.8 MiB). */
    private static final long MAX_FILE_BYTES = 4L << 20;
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** What {@link #recordPin(String, int, String)} did. */
    public enum RecordResult {
        /** The name had no pin and now has one. */
        RECORDED,
        /** The name already had a pin; its fingerprint and last-bound time were replaced. */
        UPDATED,
        /** The store is full and does not know this name; the caller uses TCP (12.16). */
        FULL,
        /** The store is disabled (unreadable or unwritable); the caller uses TCP (12.15). */
        DISABLED
    }

    /** One authenticated pin. */
    public static final class Pin {
        private final String host;
        private final int port;
        private final String fingerprint;
        private final Instant firstBound;
        private final Instant lastBound;

        private Pin(String host, int port, String fingerprint, Instant firstBound, Instant lastBound) {
            this.host = host;
            this.port = port;
            this.fingerprint = fingerprint;
            this.firstBound = firstBound;
            this.lastBound = lastBound;
        }

        /** The normalized logical host. */
        public String host() {
            return host;
        }

        /** The logical TCP port. */
        public int port() {
            return port;
        }

        /** The pinned fingerprint, lowercase {@code sha256:<64 hex>}. */
        public String fingerprint() {
            return fingerprint;
        }

        /** When this name was first bound, which survives a later fingerprint change. */
        public Instant firstBound() {
            return firstBound;
        }

        /** When the pin was last written, that is when the last SERVER_SWITCH verified. */
        public Instant lastBound() {
            return lastBound;
        }
    }

    private final Path file;
    private final Clock clock;
    private final Map<String, Pin> pins = new LinkedHashMap<>();
    private boolean disabled;

    private PinStore(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /**
     * Loads the pins the client has recorded, deciding expiry from the system UTC clock; a missing
     * file is an empty, usable store. Use {@link #load(Path, Clock)} to inject a clock.
     */
    public static PinStore load(Path file) {
        return load(file, Clock.systemUTC());
    }

    /**
     * Loads the pins the client has recorded, deciding expiry from {@code clock}, which must be a
     * wall clock: the timestamps in the file are UTC instants, not monotonic readings.
     *
     * @param file the pins file, or null for a store that is never persisted
     * @param clock the clock every expiry decision of this store is made from
     */
    public static PinStore load(Path file, Clock clock) {
        PinStore store = new PinStore(file, Objects.requireNonNull(clock, "clock"));
        if (file == null) {
            return store;
        }
        StateFile.deleteStaleTemporaries(file);
        try {
            store.read(new String(StateFile.read(file, MAX_FILE_BYTES), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            // Only a file that is really not there is an empty store, docs/protocol.md 12.15.
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "QUICraft pin store is unreadable and stays disabled, not empty ({0})",
                    String.valueOf(e));
            store.pins.clear();
            store.disabled = true;
        }
        return store;
    }

    /** Whether the store may be read and written; false after corruption or a failed write. */
    public synchronized boolean isUsable() {
        return !disabled;
    }

    /**
     * The pin for {@code host} and {@code port}, normalized the same way {@link #recordPin} stores
     * it, or an empty result when there is none. Pins that expired at the store's clock are removed
     * first, so this may write the file (docs/protocol.md 12.15).
     *
     * <p>An empty result is only "no pin was ever recorded" while {@link #isUsable()} is true: a
     * store that a corrupt file or a failed write disabled answers empty for everything, because
     * what it holds is not a pin the caller may act on.</p>
     *
     * <p>The caller passes the keys of its running attempts, from {@link RebindState#activeKeys()},
     * so a pin is never expired under an attempt. It must also have forgotten the expired pins'
     * re-bind state first: see {@link #purgeExpired(Set)}.</p>
     */
    synchronized Optional<Pin> pin(String host, int port) {
        return pin(host, port, Collections.<PinKey>emptySet());
    }

    /**
     * The pin for {@code host} and {@code port}, after removing the pins that expired at the store's
     * clock, except those in {@code activeKeys} (docs/protocol.md 12.15).
     *
     * <p>The purge here does not forget the expired pins' re-bind state, which lives in another file
     * and another object: run {@link PinExpiry#purge(PinStore, RebindState, Set)} first, with the
     * same {@code activeKeys}, so that nothing is left for this call to purge. An empty result while
     * {@link #isUsable()} is false means the store is unusable, not that no pin was ever recorded.</p>
     *
     * @param activeKeys the logical servers an attempt is running for, as
     *     {@link RebindState#activeKeys()} returns them, whose pins are never expired here; empty
     *     when the caller has no attempt running
     */
    public synchronized Optional<Pin> pin(String host, int port, Set<PinKey> activeKeys) {
        PinKey key = PinKey.of(host, port);
        purgeExpired(activeKeys);
        if (disabled) {
            return Optional.empty();
        }
        return Optional.ofNullable(pins.get(PinKey.mapKey(key)));
    }

    /**
     * Records an authenticated pin after a valid SERVER_SWITCH. An existing name is always updated,
     * keeping the time it was first bound; a new name is refused with {@link RecordResult#FULL} once
     * the store holds {@link #MAX_PINS} pins, and the caller uses TCP for it.
     *
     * <p>Pins that expired at the store's clock are removed first (12.15), so a name whose pin has
     * expired is recorded as a new one: it is {@link RecordResult#FULL} only if the store is still
     * full after those removals. The caller passes the keys of its running attempts, from
     * {@link RebindState#activeKeys()}, so a pin is never expired under an attempt, and must have
     * forgotten the expired pins' re-bind state first: see
     * {@link #recordPin(String, int, String, Set)}.</p>
     *
     * <p><b>Only the post-SERVER_SWITCH online-mode path of docs/protocol.md 12.15 may call this</b>,
     * once the binding exchange has proved the connection that just completed is the one the pin is
     * for. The silent v1 fingerprint observations of section 6 are a different thing entirely and
     * must never be passed in: an authenticated pin means only "the last endpoint at this name that
     * completed an authenticated login", and 12.15 forbids promoting an observation into it.</p>
     *
     * @param fingerprint the advertised fingerprint, lowercase {@code sha256:<64 hex>}
     * @throws IllegalArgumentException when the host, port or fingerprint is not in its canonical form
     */
    synchronized RecordResult recordPin(String host, int port, String fingerprint) {
        return recordPin(host, port, fingerprint, Collections.<PinKey>emptySet());
    }

    /**
     * The same, after removing the pins that expired at the store's clock, except those in
     * {@code activeKeys} (docs/protocol.md 12.15).
     *
     * <p>The purge here does not forget the expired pins' re-bind state, which lives in another file
     * and another object: run {@link PinExpiry#purge(PinStore, RebindState, Set)} first, with the
     * same {@code activeKeys}, so that nothing is left for this call to purge.</p>
     *
     * @param activeKeys the logical servers an attempt is running for, as
     *     {@link RebindState#activeKeys()} returns them, whose pins are never expired here; empty
     *     when the caller has no attempt running
     */
    public synchronized RecordResult recordPin(String host, int port, String fingerprint,
                                               Set<PinKey> activeKeys) {
        PinKey key = PinKey.of(host, port);
        String canonical = Fingerprints.check(fingerprint);
        if (disabled) {
            return RecordResult.DISABLED;
        }
        purgeExpired(activeKeys);
        if (disabled) {
            return RecordResult.DISABLED;
        }
        String mapKey = PinKey.mapKey(key);
        Pin existing = pins.get(mapKey);
        if (existing == null && pins.size() >= MAX_PINS) {
            return RecordResult.FULL;
        }
        Instant now = clock.instant();
        Pin updated = existing == null
                ? new Pin(key.host(), key.port(), canonical, now, now)
                : new Pin(key.host(), key.port(), canonical, existing.firstBound, now);
        Map<String, Pin> next = new LinkedHashMap<>(pins);
        next.put(mapKey, updated);
        if (!write(next)) {
            disabled = true;
            return RecordResult.DISABLED;
        }
        pins.clear();
        pins.putAll(next);
        return existing == null ? RecordResult.RECORDED : RecordResult.UPDATED;
    }

    /**
     * The keys of the pins that have expired at the store's clock, without changing anything
     * (docs/protocol.md 12.15). A pin has expired when its last-bound time is more than
     * {@link #PIN_TTL_MILLIS} before that clock, or is in the future; a pin whose server is in
     * {@code activeKeys} has not, because an attempt for it is running. This is the same rule
     * {@link #purgeExpired(Set)} removes by, and it reads nothing else: no write, no reordering.
     *
     * <p>It exists so that expiry can be finished in the order that leaks nothing when the process
     * dies in the middle of it: forget the expired pins' re-bind state first, then remove the pins,
     * which is what {@link PinExpiry#purge(PinStore, RebindState, Set)} does. Both halves must run
     * with the same {@code activeKeys}.</p>
     *
     * @param activeKeys the logical servers an attempt is running for, as
     *     {@link RebindState#activeKeys()} returns them; empty when there is none
     * @return the keys that would be removed, empty when none has expired
     */
    public synchronized Set<PinKey> expiredKeys(Set<PinKey> activeKeys) {
        return keysOf(expiredMapKeys(activeKeys, clock.millis()));
    }

    /**
     * Removes the pins that expired at the store's clock, in one atomic write, and returns their
     * keys (docs/protocol.md 12.15). The rule is {@link #expiredKeys(Set)}'s.
     *
     * <p>This removes the pins only. Their re-bind backoff and detailed notices live in the re-bind
     * state's own file and must be forgotten first, with the keys
     * {@link #expiredKeys(Set)} returned, so that a crash between the two writes leaves a pin to
     * expire again rather than a re-bind entry nothing will ever remove:
     * {@link PinExpiry#purge(PinStore, RebindState, Set)} is that whole sequence in one call, and
     * the connect path uses it. A lookup or a {@link #recordPin(String, int, String, Set)} decision
     * purges by itself, so the caller must have run that sequence before them.</p>
     *
     * <p>Nothing is written when no pin has expired. A failed write disables the store, leaves the
     * old file in place and removes nothing, so the returned set is empty, exactly like every other
     * failed write here; the caller then has {@link #isUsable()} false and uses {@code /2} or TCP.</p>
     *
     * @param activeKeys the logical servers an attempt is running for, as
     *     {@link RebindState#activeKeys()} returns them; empty when there is none
     * @return the keys of the pins that were removed, empty when there was nothing to remove
     */
    public synchronized Set<PinKey> purgeExpired(Set<PinKey> activeKeys) {
        Objects.requireNonNull(activeKeys, "activeKeys");
        if (disabled) {
            return Collections.emptySet();
        }
        Set<String> expiredMapKeys = expiredMapKeys(activeKeys, clock.millis());
        if (expiredMapKeys.isEmpty()) {
            return Collections.emptySet();
        }
        Set<PinKey> expired = keysOf(expiredMapKeys);
        Map<String, Pin> next = new LinkedHashMap<>(pins);
        for (String mapKey : expiredMapKeys) {
            next.remove(mapKey);
        }
        if (!write(next)) {
            disabled = true;
            return Collections.emptySet();
        }
        pins.clear();
        pins.putAll(next);
        return expired;
    }

    /**
     * The map keys of the pins that have expired at {@code now}, except those whose server is in
     * {@code activeKeys}, which an attempt is running for. Reads the pins and nothing else.
     */
    private Set<String> expiredMapKeys(Set<PinKey> activeKeys, long now) {
        Objects.requireNonNull(activeKeys, "activeKeys");
        Set<String> expired = new LinkedHashSet<>();
        if (disabled) {
            return expired;
        }
        Set<String> active = new LinkedHashSet<>();
        for (PinKey key : activeKeys) {
            active.add(PinKey.mapKey(key));
        }
        for (Map.Entry<String, Pin> candidate : pins.entrySet()) {
            if (isExpired(candidate.getValue(), now) && !active.contains(candidate.getKey())) {
                expired.add(candidate.getKey());
            }
        }
        return expired;
    }

    /** The keys of the pins at {@code mapKeys}, which must still be in this store. */
    private Set<PinKey> keysOf(Set<String> mapKeys) {
        Set<PinKey> keys = new LinkedHashSet<>();
        for (String mapKey : mapKeys) {
            Pin pin = pins.get(mapKey);
            keys.add(PinKey.of(pin.host(), pin.port()));
        }
        return keys;
    }

    /**
     * Whether {@code pin} has expired at {@code now}: more than {@link #PIN_TTL_MILLIS} since it was
     * last bound, or a last-bound time in the future. A clock that moved backwards never revives a
     * pin, and never extends one either: the future timestamp it left behind has already expired
     * (docs/protocol.md 12.15).
     */
    private static boolean isExpired(Pin pin, long now) {
        long age = now - pin.lastBound.toEpochMilli();
        return age > PIN_TTL_MILLIS || age < 0;
    }

    /**
     * Deletes the pin for {@code host} and {@code port}, which the player must have confirmed
     * (docs/protocol.md 12.16). Returns whether there was a pin to delete, and false while the store
     * is disabled.
     */
    public synchronized boolean deletePin(String host, int port) {
        PinKey key = PinKey.of(host, port);
        if (disabled) {
            return false;
        }
        String mapKey = PinKey.mapKey(key);
        if (!pins.containsKey(mapKey)) {
            return false;
        }
        Map<String, Pin> next = new LinkedHashMap<>(pins);
        next.remove(mapKey);
        if (!write(next)) {
            disabled = true;
            return false;
        }
        pins.clear();
        pins.putAll(next);
        return true;
    }

    /**
     * How many pins the store holds, expired ones included: a pin is only gone once a lookup, a
     * decision or {@link #purgeExpired(Set)} has removed it (docs/protocol.md 12.15).
     */
    public synchronized int size() {
        return pins.size();
    }

    private void read(String content) {
        Map<String, Object> root = StrictJson.object(StrictJson.parse(content), "pin store");
        int version = StrictJson.integer(root.get("version"), "version", 0, Integer.MAX_VALUE);
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported pin store version " + version);
        }
        List<Object> entries = StrictJson.array(root.get("pins"), "pins");
        if (entries.size() > MAX_PINS) {
            throw new IllegalArgumentException("pin store holds more than " + MAX_PINS + " pins");
        }
        for (Object element : entries) {
            Map<String, Object> fields = StrictJson.object(element, "pin");
            PinKey key = PinKey.of(StrictJson.string(fields.get("host"), "host"),
                    StrictJson.integer(fields.get("port"), "port", 1, 65535));
            Pin pin = new Pin(key.host(), key.port(),
                    Fingerprints.check(StrictJson.string(fields.get("fingerprint"), "fingerprint")),
                    StrictJson.instant(fields.get("firstBound"), "firstBound"),
                    StrictJson.instant(fields.get("lastBound"), "lastBound"));
            if (pins.put(PinKey.mapKey(key), pin) != null) {
                throw new IllegalArgumentException("pin store repeats a server");
            }
        }
    }

    /** Writes {@code next} atomically; returns whether the file now holds it. */
    private boolean write(Map<String, Pin> next) {
        if (file == null) {
            return true;
        }
        try {
            StateFile.write(file, encode(next).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "QUICraft could not write its pin store and disables it ({0})",
                    String.valueOf(e));
            return false;
        }
    }

    private static String encode(Map<String, Pin> pins) {
        StringBuilder sb = new StringBuilder(64 + 256 * pins.size());
        sb.append("{\"version\":").append(SCHEMA_VERSION).append(",\"pins\":[");
        boolean first = true;
        for (Pin pin : pins.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"host\":").append(StrictJson.quote(pin.host));
            sb.append(",\"port\":").append(pin.port);
            sb.append(",\"fingerprint\":").append(StrictJson.quote(pin.fingerprint));
            sb.append(",\"firstBound\":").append(StrictJson.quotedInstant(pin.firstBound));
            sb.append(",\"lastBound\":").append(StrictJson.quotedInstant(pin.lastBound));
            sb.append('}');
        }
        return sb.append("]}").toString();
    }
}
