// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.routing.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rs.sudoe.quicraft.core.routing.Extractor;

/** Closed, versioned primitive packet layouts for reviewed schema-2 resources (13.7).
 * These layouts consume exactly a packet id followed by the named fields, not game adapters.
 */
public final class ExtractorRegistry {
    public enum Kind { SINGLE, LIST, JOIN }
    private ExtractorRegistry() {}

    /** Resolves an identifier, never an offset or executable resource value (13.7). */
    public static Decoder decoder(String id) {
        switch (id) {
            case "test.chunk_xz.v1":
            case "primitive.chunk_xz.v1":
            case "primitive.block_position.v1":
            case "primitive.section_position.v1":
            case "primitive.varint_xz.v1": return new Decoder(id, Kind.SINGLE);
            case "primitive.chunk_list.v1": return new Decoder(id, Kind.LIST);
            case "primitive.join.v1": return new Decoder(id, Kind.JOIN);
            default: throw new IllegalArgumentException("13.7: unknown extractor identifier");
        }
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
        public final Kind kind;
        private Decoder(String id, Kind kind) { this.identifier = id; this.kind = kind; }

        /** Implements the frozen single-position contract; lists/joins cannot masquerade as one (13.7). */
        @Override
        public Extractor.Position extract(byte[] packet) {
            if (kind != Kind.SINGLE) throw new IllegalArgumentException("not a single-position extractor");
            return decode(packet).positions.get(0);
        }

        /** Decodes the complete uncompressed packet id and body, rejecting trailing bytes (13.7). */
        public Result decode(byte[] packet) {
            Cursor c = new Cursor(packet);
            if (c.varint() < 0) throw new IllegalArgumentException("negative packet id");
            List<Extractor.Position> positions = new ArrayList<>();
            switch (identifier) {
                case "primitive.join.v1": break;
                case "primitive.chunk_list.v1":
                    int count = c.varint();
                    // This primitive's reviewed bound is 1024 exact eight-byte entries.
                    if (count < 0 || count > 1024 || c.remaining() != count * 8)
                        throw new IllegalArgumentException("invalid chunk list count");
                    for (int i = 0; i < count; i++) positions.add(new Extractor.Position(c.integer(), c.integer()));
                    break;
                case "primitive.block_position.v1":
                    long block = c.longValue();
                    positions.add(new Extractor.Position(Math.floorDiv((int) (block >> 38), 16),
                            Math.floorDiv((int) (block << 26 >> 38), 16)));
                    break;
                case "primitive.section_position.v1":
                    long section = c.longValue();
                    positions.add(new Extractor.Position((int) (section >> 42), (int) (section << 22 >> 42)));
                    break;
                case "primitive.varint_xz.v1":
                    positions.add(new Extractor.Position(c.varint(), c.varint()));
                    break;
                default: positions.add(new Extractor.Position(c.integer(), c.integer()));
            }
            if (c.remaining() != 0) throw new IllegalArgumentException("trailing extractor input");
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
