// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds direction-local /3 record headers at the sole logical send point (docs/protocol.md
 * 13.4, 13.9, 13.15, 13.18, 13.19). One instance serves one sending direction. The caller
 * serializes calls in the game's logical send order; this class holds no I/O and no locks.
 *
 * <p>Every dependency produced here is a componentwise maximum of frontiers that were already
 * assigned, so every edge points backward (13.9). Only nonzero cross-stream entries are returned;
 * the own-stream predecessor is implicit.
 */
public final class StreamSequencer {
    /** Route scope of one assigned record (13.5, 13.9). */
    public enum Fence {
        /** Ordinary CONTROL: waits for the latest global frontier G only (13.9). */
        ORDINARY_CONTROL,
        /** CONTROL fence: waits for previous CONTROL and REALTIME, then becomes C (13.9). */
        CONTROL_FENCE,
        /** Global fence: waits for every previous record, then becomes G (13.9). */
        GLOBAL,
        /** View gate: waits for every previous record, then becomes V and C (13.9). */
        VIEW,
        /** REALTIME: waits for the latest control fence C (13.9). */
        REALTIME,
        /** Ordinary CHUNK[i]: waits for the latest view gate V only (13.9). */
        CHUNK,
        /**
         * ACK stream, trailing (13.21): waits for the latest record of every stream and advances no frontier,
         * so no later record except an {@link #CHUNK_ACK} one, a global or a view gate waits for it.
         */
        ACK_TRAILING,
        /** CHUNK[i] that must also not overtake the latest ACK-stream record (13.21 {@code ack} dependency). */
        CHUNK_ACK,
        /**
         * A chunk install (45) with the {@code ack} dependency. Resolved by the send driver to CHUNK for a
         * first install and to CHUNK_ACK otherwise; the sequencer itself refuses it (13.21).
         */
        CHUNK_INSTALL,
        /** A chunk forget (37) with the {@code ack} dependency; the driver resolves it to CHUNK_ACK (13.21). */
        CHUNK_FORGET
    }

    /** Maximum packets enclosed by one bundle, excluding delimiters (13.15). */
    public static final int MAX_BUNDLE_MEMBERS = 126;
    /** Clientbound bundle receive slot B = 2 MiB + 128 bytes; a bundle's encoded size must fit (13.12). */
    public static final long MAX_BUNDLE_ENCODED_BYTES = 2L * 1024 * 1024 + 128;
    /** Maximum total decoding scratch of one bundle, 8 MiB or stricter game limits (13.15). */
    public static final long MAX_BUNDLE_SCRATCH_BYTES = 8L * 1024 * 1024;
    /** Largest sequence or epoch value: a signed-31-bit VarInt (13.4). Exhaustion closes, never wraps. */
    public static final long MAX_SEQUENCE = Integer.MAX_VALUE;

    private static final int CONTROL = LogicalStream.CONTROL;
    private static final int REALTIME = LogicalStream.REALTIME;
    private static final int FIRST_CHUNK = 2;
    private static final List<Record.Dependency> NO_DEPENDENCIES = Collections.emptyList();

    private final int streams;
    private final long[] assigned;
    private final boolean serverbound;
    private long epoch;
    private long globalFrontier;
    private long viewFrontier;
    private long controlFence;
    /** Sequence of the latest ACK-stream record, or 0 (13.21). */
    private long lastAck;

    /** Creates a clientbound sending direction for the negotiated shard count (13.9). */
    public StreamSequencer(int shardCount) {
        this(shardCount, false);
    }

    /** Creates one sending direction; serverbound directions can never change epoch (13.18). */
    public StreamSequencer(int shardCount, boolean serverbound) {
        LogicalStream.validateShardCount(shardCount);
        this.streams = shardCount + 3;
        this.assigned = new long[streams];
        this.serverbound = serverbound;
    }

    /**
     * Assigns an ordinary record on {@code stream} with the scope {@code fence} (13.9). Semantic
     * predecessors, such as the preceding BATCH_JOIN that a chunk_batch_start waits for (13.19),
     * are merged as componentwise maxima; each must already be assigned.
     *
     * @param interaction true only for serverbound ORDINARY_CONTROL interaction records (13.9)
     */
    public Record assign(int stream, Fence fence, byte[] payload, boolean interaction,
            Record.Dependency... semantic) {
        Record result = place(stream, fence, payload, interaction, 0, semantic);
        applyFrontier(fence, result.sequence());
        return result;
    }

    /**
     * Assigns a level-creating global fence that advances the direction's epoch (13.18). The
     * record carries the old epoch; the sender then advances to the new epoch immediately, so every
     * later record carries it and depends on this fence through G.
     *
     * @throws IllegalArgumentException for serverbound directions (13.18)
     */
    public Record assignEpochTransition(byte[] payload, Record.Dependency... semantic) {
        if (serverbound) {
            throw new IllegalArgumentException("serverbound epoch");
        }
        if (epoch >= MAX_SEQUENCE) {
            throw new IllegalStateException("epoch exhausted");
        }
        Record result = place(CONTROL, Fence.GLOBAL, payload, false, 0, semantic);
        applyFrontier(Fence.GLOBAL, result.sequence());
        epoch++;
        return result;
    }

    /**
     * Assigns the kind 1 END (13.4, 13.14): an empty CONTROL record with the global scope, so it waits for
     * every record assigned before it on every stream. Nothing may be assigned after it.
     */
    public Record assignEnd() {
        Record result = place(CONTROL, Fence.GLOBAL, new byte[0], false, RecordCodec.KIND_END, null);
        applyFrontier(Fence.GLOBAL, result.sequence());
        return result;
    }

    /**
     * Assigns an ACK_JOIN (kind 2) or BATCH_JOIN (kind 3) on CHUNK[0] (13.19). The join waits for
     * the latest view gate, the CONTROL frontier and the explicit covered frontiers the caller
     * supplies. A join never becomes a global, view or control frontier.
     */
    public Record assignJoin(int kind, byte[] payload, Record.Dependency... covered) {
        if (kind != 2 && kind != 3) {
            throw new IllegalArgumentException("join kind");
        }
        return place(FIRST_CHUNK, Fence.CHUNK, payload, false, kind, covered);
    }

    /**
     * Assigns one bundle with its decoding scratch equal to its encoded size, the conservative
     * default when the caller has no separate decoded measure. See
     * {@link #assignBundle(byte[], List, Fence[], byte[], long, Record.Dependency...)}.
     */
    public List<Record> assignBundle(byte[] opening, List<byte[]> members, Fence[] memberScopes,
            byte[] closing, Record.Dependency... external) {
        long encoded = (long) opening.length + closing.length;
        if (members != null) {
            for (byte[] member : members) {
                if (member != null) {
                    encoded += member.length;
                }
            }
        }
        return assignBundle(opening, members, memberScopes, closing, encoded, external);
    }

    /**
     * Assigns one bundle as one CONTROL run (13.15): an opening delimiter, every member, then a
     * closing delimiter, consecutively on CONTROL with no other logical send in between. The scope
     * is {@link #bundleScope(Fence...)} of the member scopes. External prerequisites attach to the
     * opening record only. Members and the closing record carry no explicit dependency beyond their
     * implicit CONTROL predecessor. The scope frontier moves to the closing record, never to a
     * partially staged member.
     *
     * @param memberScopes one scope per member, in order
     * @param scratchBytes total decoding scratch the bundle needs on the receiver
     * @throws IllegalArgumentException on more than {@link #MAX_BUNDLE_MEMBERS} members, a total /3
     *         wire size with all record headers above {@link #MAX_BUNDLE_ENCODED_BYTES}, scratch above
     *         {@link #MAX_BUNDLE_SCRATCH_BYTES},
     *         or a REALTIME member
     */
    public List<Record> assignBundle(byte[] opening, List<byte[]> members, Fence[] memberScopes,
            byte[] closing, long scratchBytes, Record.Dependency... external) {
        if (opening == null || closing == null || members == null || memberScopes == null) {
            throw new IllegalArgumentException("bundle");
        }
        if (members.size() > MAX_BUNDLE_MEMBERS || members.size() != memberScopes.length) {
            throw new IllegalArgumentException("bundle members");
        }
        for (byte[] member : members) {
            if (member == null) {
                throw new IllegalArgumentException("bundle member");
            }
        }
        if (scratchBytes < 0 || scratchBytes > MAX_BUNDLE_SCRATCH_BYTES) {
            throw new IllegalArgumentException("bundle scratch");
        }
        Fence scope = bundleScope(memberScopes);
        if (assigned[CONTROL] + members.size() + 2 > MAX_SEQUENCE) {
            throw new IllegalStateException("sequence exhausted");
        }
        Record openingPlan = plan(CONTROL, scope, opening, false, 0, external, false);
        long unitBytes = unitBytesOf(openingPlan, opening, members, closing);
        if (unitBytes > MAX_BUNDLE_ENCODED_BYTES) {
            throw new IllegalArgumentException("bundle encoded bytes");
        }
        long openingSequence = openingPlan.sequence();
        int unitRecords = members.size() + 2;
        Record opener = new Record(CONTROL, openingSequence, epoch, openingPlan.dependencies(),
                RecordCodec.KIND_BUNDLE_OPEN, openerBody(unitRecords, unitBytes, opening));
        assigned[CONTROL] = openingSequence;
        List<Record> result = new ArrayList<Record>(members.size() + 2);
        result.add(opener);
        for (byte[] member : members) {
            result.add(place(CONTROL, Fence.ORDINARY_CONTROL, member, false, 0, null, true));
        }
        Record close = place(CONTROL, Fence.ORDINARY_CONTROL, closing, false, 0, null, true);
        result.add(close);
        applyFrontier(scope, close.sequence());
        return Collections.unmodifiableList(result);
    }

    /**
     * The exact unit wire total {@link #assignBundle} would check for this bundle now, computed without assigning
     * anything, so the sequencer state is unchanged. Refuses the same shapes as {@code assignBundle}: null parts,
     * a member count over {@link #MAX_BUNDLE_MEMBERS} and a unit over {@link #MAX_BUNDLE_ENCODED_BYTES}.
     *
     * @throws IllegalArgumentException for any of those shapes, or a REALTIME or null member scope
     */
    long bundleWireBytes(byte[] opening, List<byte[]> members, Fence[] memberScopes, byte[] closing,
            Record.Dependency... external) {
        if (opening == null || closing == null || members == null || memberScopes == null) {
            throw new IllegalArgumentException("bundle");
        }
        if (members.size() > MAX_BUNDLE_MEMBERS || members.size() != memberScopes.length) {
            throw new IllegalArgumentException("bundle members");
        }
        for (byte[] member : members) {
            if (member == null) {
                throw new IllegalArgumentException("bundle member");
            }
        }
        Fence scope = bundleScope(memberScopes);
        long unitBytes = unitBytesOf(plan(CONTROL, scope, opening, false, 0, external, false), opening, members, closing);
        if (unitBytes > MAX_BUNDLE_ENCODED_BYTES) {
            throw new IllegalArgumentException("bundle encoded bytes");
        }
        return unitBytes;
    }

    /**
     * The unit wire total of 13.4 for an opener planned at its sequence: members and the closer take the next
     * CONTROL sequences, and the opener's own size is solved as a fixed point in {@link #unitWireBytes}.
     */
    private long unitBytesOf(Record openingPlan, byte[] opening, List<byte[]> members, byte[] closing) {
        long tail = 0;
        long sequence = openingPlan.sequence();
        for (byte[] member : members) {
            tail += compactWireSize(++sequence, epoch, NO_DEPENDENCIES, member.length);
        }
        tail += compactWireSize(++sequence, epoch, NO_DEPENDENCIES, closing.length);
        return unitWireBytes(openingPlan.sequence(), epoch, openingPlan.dependencies(), members.size() + 2,
                opening.length, tail);
    }

    /**
     * The bundle scope rule of 13.15: the strongest member scope (GLOBAL &gt; VIEW &gt; CONTROL_FENCE
     * &gt; ORDINARY_CONTROL), never weaker than CONTROL_FENCE, and promoted to GLOBAL by any CHUNK
     * or GLOBAL member. A REALTIME or null member is rejected.
     */
    public static Fence bundleScope(Fence... memberScopes) {
        // Every member is inspected before any result is returned, so an invalid later scope is never skipped.
        boolean promoted = false;
        boolean view = false;
        for (Fence member : memberScopes) {
            if (member == null || member == Fence.REALTIME) {
                throw new IllegalArgumentException("bundle member scope");
            }
            if (member == Fence.CHUNK || member == Fence.GLOBAL || member == Fence.ACK_TRAILING
                    || member == Fence.CHUNK_ACK || member == Fence.CHUNK_INSTALL || member == Fence.CHUNK_FORGET) {
                // 13.21: a bundle holding an ACK is placed whole on CONTROL as a global fence, never split.
                promoted = true;
            } else if (member == Fence.VIEW) {
                view = true;
            }
        }
        if (promoted) {
            return Fence.GLOBAL;
        }
        return view ? Fence.VIEW : Fence.CONTROL_FENCE;
    }

    /** Returns the per-stream last assigned sequence, indexed by logical stream (13.9). */
    public long[] assigned() {
        return assigned.clone();
    }

    /** Returns this direction's current assignment epoch (13.18). */
    public long epoch() {
        return epoch;
    }

    /** Returns the latest global fence sequence G, or 0 (13.9). */
    public long globalFrontier() {
        return globalFrontier;
    }

    /** Returns the latest view gate sequence V, or 0 (13.9). */
    public long viewFrontier() {
        return viewFrontier;
    }

    /** Returns the latest ACK-stream record sequence, or 0 (13.21). */
    public long lastAck() {
        return lastAck;
    }

    /** The logical index of the ACK stream, N+2 (13.3). */
    public int ackIndex() {
        return streams - 1;
    }

    /** Returns the latest control fence sequence C, or 0 (13.9). */
    public long controlFence() {
        return controlFence;
    }

    /**
     * The safe superset of 13.19 for an ACK_JOIN without exact causal coverage: every chunk
     * stream's current frontier plus the current CONTROL frontier. Only nonzero entries are returned.
     */
    public Record.Dependency[] safeSupersetDependencies() {
        List<Record.Dependency> result = new ArrayList<Record.Dependency>();
        if (assigned[CONTROL] > 0) {
            result.add(new Record.Dependency(CONTROL, assigned[CONTROL]));
        }
        for (int stream = FIRST_CHUNK; stream < streams; stream++) {
            if (assigned[stream] > 0) {
                result.add(new Record.Dependency(stream, assigned[stream]));
            }
        }
        return result.toArray(new Record.Dependency[result.size()]);
    }

    /** Places one record at the logical send point; it does not change frontiers or the epoch. */
    private Record place(int stream, Fence fence, byte[] payload, boolean interaction, int kind,
            Record.Dependency[] semantic) {
        return place(stream, fence, payload, interaction, kind, semantic, false);
    }

    private Record place(int stream, Fence fence, byte[] payload, boolean interaction, int kind,
            Record.Dependency[] semantic, boolean unconstrained) {
        Record record = plan(stream, fence, payload, interaction, kind, semantic, unconstrained);
        assigned[stream] = record.sequence();
        return record;
    }

    /** Builds the record {@link #place} would assign, without moving any sequence or frontier. */
    private Record plan(int stream, Fence fence, byte[] payload, boolean interaction, int kind,
            Record.Dependency[] semantic, boolean unconstrained) {
        checkStream(stream);
        if (fence == null || payload == null) {
            throw new IllegalArgumentException("record");
        }
        Record.Dependency[] extra = semantic == null ? new Record.Dependency[0] : semantic;
        validateRoute(stream, fence, interaction, kind);
        if (assigned[stream] >= MAX_SEQUENCE) {
            throw new IllegalStateException("sequence exhausted");
        }
        long[] deps = unconstrained ? new long[streams] : scopeDependencies(stream, fence, interaction);
        for (Record.Dependency dependency : extra) {
            if (dependency == null) {
                throw new IllegalArgumentException("dependency");
            }
            checkStream(dependency.streamIndex());
            deps[dependency.streamIndex()] = Math.max(deps[dependency.streamIndex()], dependency.sequence());
        }
        for (int i = 0; i < streams; i++) {
            if (deps[i] > assigned[i]) {
                throw new IllegalArgumentException("forward dependency");
            }
        }
        long sequence = assigned[stream] + 1;
        List<Record.Dependency> sparse = new ArrayList<Record.Dependency>();
        for (int i = 0; i < streams; i++) {
            if (i != stream && deps[i] != 0) {
                sparse.add(new Record.Dependency(i, deps[i]));
            }
        }
        return new Record(stream, sequence, epoch, sparse, kind, payload);
    }

    /**
     * Exact /3 wire size of one record (13.4): VarInt(record_length), kind, VarInt(sequence),
     * VarInt(epoch), VarInt(dependency_count), one u8 index plus VarInt per dependency, then the body.
     */
    static long compactWireSize(long sequence, long epoch, List<Record.Dependency> dependencies,
            long bodyLength) {
        long header = 1 + varIntBytes(sequence) + varIntBytes(epoch) + varIntBytes(dependencies.size());
        for (Record.Dependency dependency : dependencies) {
            header += 1 + varIntBytes(dependency.sequence());
        }
        long recordLength = header + bodyLength;
        return varIntBytes(recordLength) + recordLength;
    }

    /**
     * The exact unit wire total of 13.4 as a fixed point: the opener's size depends on the VarInt length
     * of the total it declares, so the total is iterated from below until it is stable. The map is
     * monotone and bounded, so this converges in a few steps.
     */
    private static long unitWireBytes(long openingSequence, long epoch, List<Record.Dependency> dependencies,
            int unitRecords, int openingLength, long tail) {
        long total = tail;
        for (int i = 0; i < 64; i++) {
            long next = tail + compactWireSize(openingSequence, epoch, dependencies,
                    varIntBytes(unitRecords) + varIntBytes(total) + openingLength);
            if (next == total) {
                return total;
            }
            total = next;
        }
        throw new IllegalStateException("bundle unit size");
    }

    private static byte[] openerBody(int unitRecords, long unitBytes, byte[] opening) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        VarInt.write(body, unitRecords);
        VarInt.write(body, (int) unitBytes);
        body.write(opening, 0, opening.length);
        return body.toByteArray();
    }

    private static int varIntBytes(long value) {
        int bytes = 1;
        for (long rest = value; rest >= 0x80; rest >>>= 7) {
            bytes++;
        }
        return bytes;
    }

    /**
     * The scope dependencies of 13.9. Every record starts from the latest global frontier G. The
     * scope then adds the maxima below; the own-stream entry is left at the implicit predecessor.
     */
    private long[] scopeDependencies(int stream, Fence fence, boolean interaction) {
        long[] deps = new long[streams];
        deps[stream] = assigned[stream];
        deps[CONTROL] = Math.max(deps[CONTROL], globalFrontier);
        switch (fence) {
            case GLOBAL:
            case VIEW:
            case ACK_TRAILING:
                // 13.21: the ACK stream is included, so a global or view record waits for earlier ACKs.
                for (int i = 0; i < streams; i++) {
                    deps[i] = Math.max(deps[i], assigned[i]);
                }
                break;
            case CONTROL_FENCE:
                deps[CONTROL] = Math.max(deps[CONTROL], assigned[CONTROL]);
                deps[REALTIME] = Math.max(deps[REALTIME], assigned[REALTIME]);
                afterLastAck(deps);
                break;
            case REALTIME:
                deps[CONTROL] = Math.max(deps[CONTROL], controlFence);
                afterLastAck(deps);
                break;
            case CHUNK:
                // Only an exempted first install (13.21) or a bundle member promoted to global uses plain CHUNK.
                deps[CONTROL] = Math.max(deps[CONTROL], viewFrontier);
                break;
            case CHUNK_ACK:
                deps[CONTROL] = Math.max(deps[CONTROL], viewFrontier);
                afterLastAck(deps);
                break;
            case ORDINARY_CONTROL:
                if (interaction) {
                    deps[REALTIME] = Math.max(deps[REALTIME], assigned[REALTIME]);
                }
                afterLastAck(deps);
                break;
            default:
                throw new IllegalArgumentException("fence");
        }
        return deps;
    }

    /**
     * 13.21: no record assigned after an ACK-stream record executes before it. The ACK already trails every
     * earlier record, so its position relative to every record matches the game's emission order: a pending
     * prediction rollback and collision snap never sees a later player, entity, inventory or block change early.
     * Only an exempted first install (plain CHUNK) skips it.
     */
    private void afterLastAck(long[] deps) {
        if (lastAck > 0) {
            deps[ackIndex()] = Math.max(deps[ackIndex()], lastAck);
        }
    }

    /** Moves the scope frontiers after a fence has been assigned (13.9). */
    private void applyFrontier(Fence fence, long sequence) {
        if (fence == Fence.GLOBAL) {
            globalFrontier = sequence;
        } else if (fence == Fence.VIEW) {
            viewFrontier = sequence;
            controlFence = sequence;
        } else if (fence == Fence.CONTROL_FENCE) {
            controlFence = sequence;
        } else if (fence == Fence.ACK_TRAILING) {
            // 13.21: no global, view or control frontier moves; only the CHUNK ack dependency reads this.
            lastAck = sequence;
        }
    }

    private void validateRoute(int stream, Fence fence, boolean interaction, int kind) {
        if (interaction && (!serverbound || fence != Fence.ORDINARY_CONTROL || stream != CONTROL)) {
            throw new IllegalArgumentException("interaction route");
        }
        // Chunk routes and CHUNK fences exist only clientbound; there is no serverbound chunk scope (13.18).
        if (serverbound && (fence == Fence.CHUNK || fence == Fence.CHUNK_ACK || fence == Fence.ACK_TRAILING
                || stream >= FIRST_CHUNK)) {
            throw new IllegalArgumentException("serverbound chunk route");
        }
        if (kind == RecordCodec.KIND_END) {
            if (fence != Fence.GLOBAL || stream != CONTROL) throw new IllegalArgumentException("end route");
        } else if (kind != 0 && (fence != Fence.CHUNK || stream != FIRST_CHUNK)) {
            throw new IllegalArgumentException("join route");
        }
        switch (fence) {
            case ORDINARY_CONTROL:
            case CONTROL_FENCE:
            case GLOBAL:
            case VIEW:
                if (stream != CONTROL) throw new IllegalArgumentException("control route");
                break;
            case REALTIME:
                if (stream != REALTIME) throw new IllegalArgumentException("realtime route");
                break;
            case CHUNK:
            case CHUNK_ACK:
                // The ACK stream N+2 is never a CHUNK index (13.3).
                if (stream < FIRST_CHUNK || stream >= ackIndex()) throw new IllegalArgumentException("chunk route");
                break;
            case ACK_TRAILING:
                if (stream != ackIndex()) throw new IllegalArgumentException("ack route");
                break;
            default:
                throw new IllegalArgumentException("fence");
        }
    }

    private void checkStream(int stream) {
        if (stream < 0 || stream >= streams) throw new IllegalArgumentException("stream");
    }
}
