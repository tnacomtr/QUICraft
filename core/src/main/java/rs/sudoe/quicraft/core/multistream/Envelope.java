// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import rs.sudoe.quicraft.core.routing.Lane;

/** Undecoded record. Sequence/dependency longs represent unsigned wire u64 values. */
public final class Envelope {
    public enum Kind { GAME, END }
    public enum Bundle { NONE, OPEN, MEMBER, CLOSE }
    private final Lane lane;
    private final Kind kind;
    private final long sequence;
    private final long[] dependencies;
    private final int bodyLength;
    private final Object body;
    private final Bundle bundle;
    private final boolean stateChange;

    public Envelope(Lane lane, Kind kind, long sequence, long[] dependencies,
                    int bodyLength, Object body, Bundle bundle, boolean stateChange) {
        if (lane == null || kind == null || bundle == null || dependencies == null
                || dependencies.length != 3) throw new OrderingException("invalid header");
        if (bodyLength < 0 || bodyLength > 2097154) throw new OrderingException("body size");
        if (sequence == 0) throw new OrderingException("zero sequence");
        if (kind == Kind.END && (lane != Lane.CONTROL || bodyLength != 0 || bundle != Bundle.NONE))
            throw new OrderingException("invalid END");
        if (bundle != Bundle.NONE && (lane != Lane.CONTROL || stateChange))
            throw new OrderingException("invalid bundle member");
        this.lane = lane; this.kind = kind; this.sequence = sequence;
        this.dependencies = dependencies.clone(); this.bodyLength = bodyLength;
        this.body = body; this.bundle = bundle; this.stateChange = stateChange;
    }
    public Lane lane() { return lane; }
    public Kind kind() { return kind; }
    public long sequence() { return sequence; }
    public long dependency(Lane lane) { return dependencies[lane.ordinal()]; }
    public long[] dependencies() { return dependencies.clone(); }
    public int bodyLength() { return bodyLength; }
    public Object body() { return body; }
    public Bundle bundle() { return bundle; }
    public boolean stateChange() { return stateChange; }
    public int wireBytes() {
        int length = 33 + bodyLength;
        return length + (length < 128 ? 1 : length < 16384 ? 2 : length < 2097152 ? 3 : 4);
    }
}
