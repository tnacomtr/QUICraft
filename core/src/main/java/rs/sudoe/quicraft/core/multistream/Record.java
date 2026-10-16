// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
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
    public Record(int streamIndex, long sequence, long epoch, List<Dependency> dependencies, int kind, byte[] payload) {
        if (streamIndex < 0 || sequence <= 0 || epoch < 0 || kind < 0 || kind > 3 || dependencies == null || payload == null)
            throw new IllegalArgumentException("record");
        this.streamIndex=streamIndex; this.sequence=sequence; this.epoch=epoch; this.kind=kind;
        this.dependencies=Collections.unmodifiableList(new ArrayList<Dependency>(dependencies)); this.payload=payload.clone();
    }
    public int streamIndex() { return streamIndex; }
    public long sequence() { return sequence; }
    public long epoch() { return epoch; }
    public List<Dependency> dependencies() { return dependencies; }
    public int kind() { return kind; }
    public byte[] payload() { return payload.clone(); }
}
