// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import rs.sudoe.quicraft.core.routing.Lane;

/**
 * Record header with an opaque body. Sequence/dependency longs are unsigned wire u64 values.
 *
 * <p>Bundle/state metadata is local, not part of the wire header. The sender may attach it;
 * the receiver ignores it at admission and obtains it only from its eligible decoder.</p>
 */
public final class Envelope {
    /** Record kinds defined by the wire protocol, not Minecraft packet kinds. */
    public enum Kind {
        GAME,
        END
    }

    /** Local decoded packet classification; these values are not wire-header fields. */
    public enum Bundle {
        NONE,
        OPEN,
        MEMBER,
        CLOSE
    }
    private final Lane lane;
    private final Kind kind;
    private final long sequence;
    private final long[] dependencies;
    private final int bodyLength;
    private final Object body;
    private final Bundle bundle;
    private final boolean stateChange;

    /** Creates an undecoded wire envelope without requiring packet classification. */
    public Envelope(Lane lane, Kind kind, long sequence, long[] dependencies,
                    int bodyLength, Object body) {
        this(lane, kind, sequence, dependencies, bodyLength, body, Bundle.NONE, false);
    }

    /** Creates a locally classified envelope, for sender output or a decoded receiver result. */
    public Envelope(Lane lane, Kind kind, long sequence, long[] dependencies,
                    int bodyLength, Object body, Bundle bundle, boolean stateChange) {
        if (lane == null || kind == null || bundle == null || dependencies == null
                || dependencies.length != 3) {
            throw new OrderingException("invalid header");
        }
        if (bodyLength < 0 || bodyLength > 2097154) {
            throw new OrderingException("body size");
        }
        if (sequence == 0) {
            throw new OrderingException("zero sequence");
        }
        if (kind == Kind.END && (lane != Lane.CONTROL || bodyLength != 0 || bundle != Bundle.NONE)) {
            throw new OrderingException("invalid END");
        }
        if (bundle != Bundle.NONE && (lane != Lane.CONTROL || stateChange)) {
            throw new OrderingException("invalid bundle member");
        }
        this.lane = lane;
        this.kind = kind;
        this.sequence = sequence;
        this.dependencies = dependencies.clone();
        this.bodyLength = bodyLength;
        this.body = body;
        this.bundle = bundle;
        this.stateChange = stateChange;
    }

    /** Returns the transport lane on which this record was assigned or received. */
    public Lane lane() {
        return lane;
    }

    /** Returns GAME or the terminal END kind. */
    public Kind kind() {
        return kind;
    }

    /** Returns the unsigned per-lane sequence as a long bit pattern. */
    public long sequence() {
        return sequence;
    }

    /** Returns the unsigned dependency watermark for one lane. */
    public long dependency(Lane lane) {
        return dependencies[lane.ordinal()];
    }

    /** Returns a defensive copy of the dependency vector in lane order. */
    public long[] dependencies() {
        return dependencies.clone();
    }

    /** Returns the original framed wire body size, excluding the record header. */
    public int bodyLength() {
        return bodyLength;
    }

    /** Returns the opaque wire body, or the decoded body supplied by the eligible decoder. */
    public Object body() {
        return body;
    }

    /** Returns local bundle metadata; receivers must not trust admission-time classification. */
    public Bundle bundle() {
        return bundle;
    }

    /** True for a locally identified activation, compression change or state transition. */
    public boolean stateChange() {
        return stateChange;
    }

    /** Returns header, body and minimal VarInt record-length-prefix bytes. */
    public int wireBytes() {
        int length = 33 + bodyLength;
        return length + (length < 128 ? 1 : length < 16384 ? 2 : length < 2097152 ? 3 : 4);
    }
}
