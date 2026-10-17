// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rs.sudoe.quicraft.core.routing.Extractor;

/**
 * Closed, versioned packet layouts for reviewed schema-2 resources (13.7).
 *
 * <p>A decoder reads only the leading fields its layout defines from the id plus body bytes; bytes
 * after those fields belong to the rest of the real packet and are permitted and ignored. Truncation,
 * noncanonical or overflowing VarInts, a negative packet id and malformed list counts or lengths are
 * rejected: a packet that cannot supply the layout's fields is malformed, not a reason to guess.
 *
 * <p>Every layout has one synthetic identifier ({@code test.*}, {@code primitive.*}) and one
 * production identifier of the form {@code mc775.<layout>.v1}. The two identifiers decode with the
 * same reviewed math; the production identifier is the one a shipped table may name, and its
 * evidence is the captured real 775 packets checked in next to the code. The production load path
 * accepts only the production allowlist: renaming a primitive is not review (13.7).
 */
public final class ExtractorRegistry {
    public enum Kind { SINGLE, LIST, JOIN }

    /** Whether a table this build ships may name an identifier (13.7). */
    public enum Origin { SYNTHETIC, PRODUCTION }

    /**
     * The largest chunk-list count a legal record can carry. A record is at most 2097208 bytes
     * (13.7's {@code maxServerboundRecord} cap) and one entry is at least an eight-byte packed
     * ChunkPos plus a one-byte payload length, so a larger count cannot describe a real packet and
     * is refused before any allocation.
     */
    public static final int MAX_LIST_ENTRIES = 2097208 / 9;

    private static final Map<String, Decoder> DECODERS = decoders();

    private ExtractorRegistry() {}

    /** Resolves a known identifier, synthetic or production (the explicit test-facing path, 13.7). */
    public static Decoder decoder(String id) {
        Decoder decoder = id == null ? null : DECODERS.get(id);
        if (decoder == null) {
            throw new IllegalArgumentException("13.7: unknown extractor identifier");
        }
        return decoder;
    }

    /**
     * Resolves a production-approved identifier only: the closed allowlist a shipped resource may
     * name (13.7). Synthetic {@code test.*}/{@code primitive.*} identifiers reach only the test
     * parse path.
     */
    public static Decoder productionDecoder(String id) {
        Decoder decoder = decoder(id);
        if (decoder.origin != Origin.PRODUCTION) {
            throw new IllegalArgumentException(
                    "13.7: extractor identifier is not production-approved: \"" + decoder.identifier + "\"");
        }
        return decoder;
    }

    /** True when {@code id} is a production-approved identifier (13.7). */
    public static boolean isProduction(String id) {
        Decoder decoder = id == null ? null : DECODERS.get(id);
        return decoder != null && decoder.origin == Origin.PRODUCTION;
    }

    /** Every production-approved identifier, unmodifiable, in registry order (13.7). */
    public static Set<String> productionIds() {
        Set<String> ids = new LinkedHashSet<String>();
        for (Decoder decoder : DECODERS.values()) {
            if (decoder.origin == Origin.PRODUCTION) {
                ids.add(decoder.identifier);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /** Every known identifier, synthetic or production, unmodifiable (13.7). */
    public static Set<String> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(DECODERS.keySet()));
    }

    private static Map<String, Decoder> decoders() {
        Map<String, Decoder> decoders = new LinkedHashMap<String, Decoder>();
        synthetic(decoders, "test.chunk_xz.v1", "chunk_xz");
        for (String layout : new String[] {"chunk_xz", "block_position", "section_position",
                "varint_xz", "chunk_list", "join"}) {
            synthetic(decoders, "primitive." + layout + ".v1", layout);
            add(decoders, new Decoder("mc775." + layout + ".v1", layout, kind(layout), Origin.PRODUCTION));
        }
        return Collections.unmodifiableMap(decoders);
    }

    private static void synthetic(Map<String, Decoder> decoders, String id, String layout) {
        add(decoders, new Decoder(id, layout, kind(layout), Origin.SYNTHETIC));
    }

    private static void add(Map<String, Decoder> decoders, Decoder decoder) {
        if (decoders.put(decoder.identifier, decoder) != null) {
            throw new IllegalStateException("duplicate extractor identifier " + decoder.identifier);
        }
    }

    private static Kind kind(String layout) {
        if (layout.equals("join")) return Kind.JOIN;
        if (layout.equals("chunk_list")) return Kind.LIST;
        return Kind.SINGLE;
    }

    /** A bounded immutable result, with join distinguished from an empty list (13.7). */
    public static final class Result {
        public final Kind kind;
        public final List<Extractor.Position> positions;
        private Result(Kind kind, List<Extractor.Position> positions) {
            this.kind = kind;
            this.positions = Collections.unmodifiableList(positions);
        }
    }

    public static final class Decoder implements Extractor {
        public final String identifier;
        public final String layout;
        public final Kind kind;
        public final Origin origin;
        private Decoder(String id, String layout, Kind kind, Origin origin) {
            this.identifier = id;
            this.layout = layout;
            this.kind = kind;
            this.origin = origin;
        }

        /** Implements the frozen single-position contract; lists/joins cannot masquerade as one (13.7). */
        @Override
        public Extractor.Position extract(byte[] packet) {
            if (kind != Kind.SINGLE) throw new IllegalArgumentException("not a single-position extractor");
            return decode(packet).positions.get(0);
        }

        /**
         * Decodes the leading {@code id + body} bytes of one packet (13.7). Bytes after the layout
         * are the rest of the real packet and are ignored; a {@code join} returns its marker
         * without inspecting a body.
         */
        public Result decode(byte[] packet) {
            Cursor c = new Cursor(packet);
            if (c.varint() < 0) throw new IllegalArgumentException("negative packet id");
            List<Extractor.Position> positions = new ArrayList<>();
            switch (layout) {
                case "join": break;
                case "chunk_list":
                    // One VarInt count, then per entry one packed ChunkPos (eight bytes) followed by
                    // a length-prefixed payload this layout does not use. Both the count and every
                    // declared length are bounds-checked before anything is read for them.
                    int count = c.varint();
                    if (count < 0 || count > MAX_LIST_ENTRIES)
                        throw new IllegalArgumentException("invalid chunk list count");
                    for (int i = 0; i < count; i++) {
                        long packed = c.longValue();
                        positions.add(new Extractor.Position((int) packed, (int) (packed >> 32)));
                        int length = c.varint();
                        if (length < 0 || length > c.remaining())
                            throw new IllegalArgumentException("invalid chunk list entry length");
                        c.skip(length);
                    }
                    break;
                case "block_position":
                    long block = c.longValue();
                    positions.add(new Extractor.Position(Math.floorDiv((int) (block >> 38), 16),
                            Math.floorDiv((int) (block << 26 >> 38), 16)));
                    break;
                case "section_position":
                    long section = c.longValue();
                    positions.add(new Extractor.Position((int) (section >> 42), (int) (section << 22 >> 42)));
                    break;
                case "varint_xz":
                    positions.add(new Extractor.Position(c.varint(), c.varint()));
                    break;
                default:
                    positions.add(new Extractor.Position(c.integer(), c.integer()));
            }
            return new Result(kind, positions);
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private int offset;
        Cursor(byte[] data) { this.data = java.util.Objects.requireNonNull(data, "packet"); }
        int remaining() { return data.length - offset; }
        int octet() {
            if (remaining() == 0) throw new IllegalArgumentException("short extractor input");
            return data[offset++] & 255;
        }
        int integer() { return octet() << 24 | octet() << 16 | octet() << 8 | octet(); }
        long longValue() { return (long) integer() << 32 | (integer() & 0xffffffffL); }
        void skip(int length) {
            if (length < 0 || length > remaining()) throw new IllegalArgumentException("short extractor input");
            offset += length;
        }
        int varint() {
            int value = 0;
            for (int i = 0; i < 5; i++) {
                int b = octet();
                if (i == 4 && (b & 0xf0) != 0) throw new IllegalArgumentException("VarInt overflow");
                value |= (b & 127) << (7 * i);
                if ((b & 128) == 0) {
                    if (i > 0 && (b & 127) == 0) throw new IllegalArgumentException("noncanonical VarInt");
                    return value;
                }
            }
            throw new IllegalArgumentException("long VarInt");
        }
    }
}
