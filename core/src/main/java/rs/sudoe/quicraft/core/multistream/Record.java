// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Decoded /3 record contract, separate from the legacy codec (docs/protocol.md 13.4). */
public final class Record {
    /** Sparse cross-stream prerequisite. */
    public static final class Dependency {
        private final int streamIndex;
        private final long sequence;
        public Dependency(int streamIndex, long sequence) {
            if (streamIndex < 0 || sequence <= 0) throw new IllegalArgumentException("dependency");
            this.streamIndex = streamIndex; this.sequence = sequence;
        }
        public int streamIndex() { return streamIndex; }
        public long sequence() { return sequence; }
    }
    private final int streamIndex, kind;
    private final long sequence, epoch;
    private final List<Dependency> dependencies;
    private final byte[] payload;
    private final int payloadOffset, payloadLength;

    public Record(int streamIndex, long sequence, long epoch, List<Dependency> dependencies, int kind, byte[] payload) {
        this(streamIndex, sequence, epoch, dependencies, kind, payload == null ? null : payload.clone(), 0,
                payload == null ? 0 : payload.length);
    }

    private Record(int streamIndex, long sequence, long epoch, List<Dependency> dependencies, int kind,
                   byte[] payload, int offset, int length) {
        if (streamIndex < 0 || sequence <= 0 || epoch < 0 || kind < 0 || kind > RecordCodec.KIND_BUNDLE_OPEN
                || dependencies == null
                || payload == null || offset < 0 || length < 0 || offset + length > payload.length)
            throw new IllegalArgumentException("record");
        this.streamIndex=streamIndex; this.sequence=sequence; this.epoch=epoch; this.kind=kind;
        this.dependencies=Collections.unmodifiableList(new ArrayList<Dependency>(dependencies));
        this.payload=payload; this.payloadOffset=offset; this.payloadLength=length;
    }

    /**
     * Wraps a decoded body in place, without copying (13.12): the record owns {@code array} from now on,
     * and the payload is {@code length} bytes starting at {@code offset}.
     */
    static Record adopt(int streamIndex, long sequence, long epoch, List<Dependency> dependencies, int kind,
                        byte[] array, int offset, int length) {
        return new Record(streamIndex, sequence, epoch, dependencies, kind, array, offset, length);
    }

    public int streamIndex() { return streamIndex; }
    public long sequence() { return sequence; }
    public long epoch() { return epoch; }
    public List<Dependency> dependencies() { return dependencies; }
    public int kind() { return kind; }
    public byte[] payload() { return Arrays.copyOfRange(payload, payloadOffset, payloadOffset + payloadLength); }

    /** The backing array; the payload starts at {@link #payloadOffset()}. Callers must not modify it. */
    byte[] payloadArray() { return payload; }
    /** Offset of the payload inside {@link #payloadArray()}. */
    int payloadOffset() { return payloadOffset; }
    /** Payload length in bytes. */
    int payloadLength() { return payloadLength; }

    /** True for a bundle opener (kind 4, 13.4). Its payload starts with the unit fields. */
    public boolean isBundleOpen() { return kind == RecordCodec.KIND_BUNDLE_OPEN; }

    /** Bundle opener only: records in the unit, delimiters included (13.4). */
    public int unitRecords() { return (int) unitFields()[0]; }

    /** Bundle opener only: total wire size of the unit, length prefixes included (13.4). */
    public long unitWireBytes() { return unitFields()[1]; }

    /**
     * The GAME frame this record carries: the whole payload, or for a bundle opener the opening delimiter after
     * its unit fields (13.4).
     */
    public byte[] gameFrame() {
        if (!isBundleOpen()) return payload();
        int skip = (int) unitFields()[2];
        return Arrays.copyOfRange(payload, payloadOffset + skip, payloadOffset + payloadLength);
    }

    private long[] unitFields() {
        if (!isBundleOpen()) throw new IllegalStateException("not a bundle opener");
        try {
            return RecordCodec.readBundleFields(payload, payloadOffset, payloadOffset + payloadLength);
        } catch (MultistreamProtocolException e) {
            throw new IllegalStateException(e); // a bundle opener's payload passed validation
        }
    }
}
