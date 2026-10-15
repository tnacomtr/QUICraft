// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.pin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
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
 * <p><b>Capacity.</b> At most {@link #MAX_PINS} pins; a pin is never evicted automatically. When the
 * store is full, recording a name it does not know returns {@link RecordResult#FULL} and the caller
 * uses TCP for that name until the player deletes a pin; updating a name it already knows is always
 * allowed. Deleting requires the player's explicit confirmation and is therefore
 * {@link #deletePin(String, int)}, never automatic.</p>
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
 * them on the Netty event loop or the render thread, hand the work to a worker. A caller that gets
 * {@link RecordResult#DISABLED} should tell the player that pinning is off and use {@code /2} or TCP;
 * {@link #isUsable()} is the same fact for code that only reads.</p>
 */
public final class PinStore {
    /** The most pins the store holds; docs/protocol.md 12.16. */
    public static final int MAX_PINS = 4096;
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
    private final LongSupplier clock;
    private final Map<String, Pin> pins = new LinkedHashMap<>();
    private boolean disabled;

    private PinStore(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Loads the pins the client has recorded; a missing file is an empty, usable store. */
    public static PinStore load(Path file) {
        return load(file, System::currentTimeMillis);
    }

    /**
     * Loads the pins the client has recorded, reading the current time from {@code clock}.
     *
     * @param file the pins file, or null for a store that is never persisted
     */
    static PinStore load(Path file, LongSupplier clock) {
        PinStore store = new PinStore(file, clock);
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
     * it, or an empty result when there is none. Meaningless as "no pin" while {@link #isUsable()}
     * is false.
     */
    public synchronized Optional<Pin> pin(String host, int port) {
        return Optional.ofNullable(pins.get(PinKey.mapKey(PinKey.of(host, port))));
    }

    /**
     * Records an authenticated pin after a valid SERVER_SWITCH. An existing name is always updated,
     * keeping the time it was first bound; a new name is refused with {@link RecordResult#FULL} once
     * the store holds {@link #MAX_PINS} pins, and the caller uses TCP for it.
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
    public synchronized RecordResult recordPin(String host, int port, String fingerprint) {
        PinKey key = PinKey.of(host, port);
        String canonical = Fingerprints.check(fingerprint);
        if (disabled) {
            return RecordResult.DISABLED;
        }
        String mapKey = PinKey.mapKey(key);
        Pin existing = pins.get(mapKey);
        if (existing == null && pins.size() >= MAX_PINS) {
            return RecordResult.FULL;
        }
        Instant now = Instant.ofEpochMilli(clock.getAsLong());
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

    /** How many pins the store holds. */
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
