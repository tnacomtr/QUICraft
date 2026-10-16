// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rs.sudoe.quicraft.core.routing.Lane;

/**
 * Assigns records for one sending direction at the original game packet ordering point.
 *
 * <p>Not thread-safe. The owner supplies monotonic time and calls this sequencer serially.
 * A bundle is buffered completely before assignment; the owner encodes and queues the returned
 * records without interleaving other game records. Any {@link OrderingException} is terminal.</p>
 *
 * @deprecated Single-chain N+2 sequencing of the /3 format is replaced by {@link StreamSequencer},
 *     which keeps per-stream sequences, G/V/C fence frontiers and sparse dependencies (13.5, 13.9).
 *     Kept only for the 3-lane LaneSession; unit U8 removes it.
 */
@Deprecated
public final class SenderSequencer {
    /** Leaves two delimiter slots within the 1024-record receive cap (13.15). */
    public static final int MAX_BUNDLE_MEMBERS = 1022;
    /** Ten-second incomplete-bundle deadline (13.15). */
    public static final long TIMEOUT_NANOS = 10000000000L;
    private final long[] assigned = new long[3];
    private List<Member> members;
    private long bundleStarted;
    private long bundleBytes;
    private boolean ended;

    /** Returns a snapshot of the last-assigned unsigned sequences in lane order. */
    public long[] assigned() {
        return assigned.clone();
    }

    /** Assigns one GAME record, taking maxima with already-assigned semantic predecessors. */
    public Envelope assign(Lane lane, int bytes, Object body, long[] semantic, long now) {
        checkDeadlines(now);
        if (members != null) {
            throw new OrderingException("assignment inside buffered bundle");
        }
        return next(lane, Envelope.Kind.GAME, bytes, body, semantic, Envelope.Bundle.NONE);
    }
    /** Assigns the terminal CONTROL END record as a full fence. */
    public Envelope end(long now) {
        checkDeadlines(now);
        if (members != null) {
            throw new OrderingException("END inside bundle");
        }
        Envelope result = next(Lane.CONTROL, Envelope.Kind.END, 0, null, null, Envelope.Bundle.NONE);
        ended = true;
        return result;
    }
    private Envelope next(Lane lane, Envelope.Kind kind, int bytes, Object body,
                          long[] semantic, Envelope.Bundle marker) {
        if (ended || lane == null) {
            throw new OrderingException("closed sender or invalid lane");
        }
        int i = lane.ordinal();
        if (assigned[i] == -1L) {
            throw new OrderingException("sequence overflow");
        }
        long[] deps = lane == Lane.CONTROL ? assigned.clone() : new long[3];
        deps[0] = assigned[0];
        deps[i] = assigned[i];
        if (semantic != null) {
            if (semantic.length != 3) {
                throw new OrderingException("dependency vector size");
            }
            for (int j = 0; j < 3; j++) {
                if (Long.compareUnsigned(semantic[j], assigned[j]) > 0) {
                    throw new OrderingException("unassigned semantic predecessor");
                }
                if (Long.compareUnsigned(semantic[j], deps[j]) > 0) {
                    deps[j] = semantic[j];
                }
            }
        }
        Envelope result = new Envelope(lane, kind, assigned[i] + 1, deps, bytes, body, marker, false);
        assigned[i]++;
        return result;
    }
    /** Begins buffering a bundle, starting its missing-close deadline. */
    public void beginBundle(long now) {
        checkDeadlines(now);
        if (ended || members != null) {
            throw new OrderingException("nested/closed bundle");
        }
        members = new ArrayList<Member>();
        bundleBytes = 0;
        bundleStarted = now;
    }
    /** Sizes are uncompressed member sizes; bodyLength is the later framed wire size. */
    public void addBundleMember(int uncompressedBytes, int bodyLength, Object body,
                                boolean changesStateCompressionOrActivation, long now) {
        checkDeadlines(now);
        if (members == null || changesStateCompressionOrActivation || uncompressedBytes < 0
                || bodyLength < 0 || bodyLength > 2097154 || members.size() == MAX_BUNDLE_MEMBERS
                || bundleBytes + uncompressedBytes > 8L * 1024 * 1024) {
            throw new OrderingException("invalid/oversized bundle");
        }
        members.add(new Member(bodyLength, body));
        bundleBytes += uncompressedBytes;
    }
    /** Assigns delimiters and members atomically, only after the closing delimiter exists. */
    public List<Envelope> endBundle(int openBytes, Object open, int closeBytes, Object close, long now) {
        return endBundle(openBytes, openBytes, open, closeBytes, closeBytes, close, now);
    }
    /** Explicit uncompressed delimiter sizes when compression changes their framed wire sizes. */
    public List<Envelope> endBundle(int openUncompressed, int openBytes, Object open,
                                    int closeUncompressed, int closeBytes, Object close, long now) {
        checkDeadlines(now);
        if (members == null || openBytes < 0 || closeBytes < 0 || openBytes > 2097154
                || closeBytes > 2097154 || openUncompressed < 0 || closeUncompressed < 0
                || bundleBytes + openUncompressed + closeUncompressed > 8L * 1024 * 1024) {
            throw new OrderingException("invalid bundle close/total size");
        }
        long count = members.size() + 2L;
        if (Long.compareUnsigned(assigned[0], -1L - count) > 0) {
            throw new OrderingException("bundle sequence overflow");
        }
        List<Envelope> result = new ArrayList<Envelope>();
        result.add(next(Lane.CONTROL, Envelope.Kind.GAME, openBytes, open, null, Envelope.Bundle.OPEN));
        for (Member m : members) {
            result.add(next(Lane.CONTROL, Envelope.Kind.GAME, m.bytes, m.body, null, Envelope.Bundle.MEMBER));
        }
        result.add(next(Lane.CONTROL, Envelope.Kind.GAME, closeBytes, close, null, Envelope.Bundle.CLOSE));
        members = null;
        return Collections.unmodifiableList(result);
    }
    /** Throws on an incomplete bundle at or beyond its ten-second deadline. */
    public void checkDeadlines(long now) {
        if (members != null && now - bundleStarted >= TIMEOUT_NANOS) {
            throw new OrderingException("incomplete bundle timeout");
        }
    }
    private static final class Member {
        final int bytes;
        final Object body;

        Member(int bytes, Object body) {
            this.bytes = bytes;
            this.body = body;
        }
    }
}
