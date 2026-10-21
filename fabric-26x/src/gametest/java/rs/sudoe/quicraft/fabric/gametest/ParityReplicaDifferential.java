// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import rs.sudoe.quicraft.core.multistream.HeldChunkSet;
import rs.sudoe.quicraft.core.multistream.OutboundPacket;
import rs.sudoe.quicraft.core.multistream.Record;
import rs.sudoe.quicraft.core.multistream.RecordCodec;
import rs.sudoe.quicraft.core.multistream.SessionContext;
import rs.sudoe.quicraft.core.multistream.SessionSendDriver;
import rs.sudoe.quicraft.core.multistream.StreamSequencer;
import rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.OutboundPool;
import rs.sudoe.quicraft.core.resource.PressureRanking;

/**
 * Drift guard for {@link ParitySendReplica}: feeds the same packet scripts to the real
 * {@link SessionSendDriver} and to the replica (the way {@link ParityPlanner} uses it) and requires
 * the same stream, sequence, epoch and dependencies for every record. The real driver's records are
 * read back from what it writes ({@code poll}, decoded with {@link RecordCodec}), so nothing private
 * is touched. It runs at the start of every parity run, in the default gate.
 *
 * <p>Not modelled by the replica, and so not covered here: bundles. A chunk install or forget inside
 * a bundle makes the driver mark the held set unknown (SessionSendDriver.noteBundleScopes); the
 * replica has no bundle path, and the parity cases deliver no bundles.
 */
final class ParityReplicaDifferential {
    private static final int SHARDS = 4;

    private enum Kind { PACKET, EPOCH }

    private record Op(Kind kind, Fence fence, int x, int z) {}

    private ParityReplicaDifferential() {}

    private static Op p(Fence fence, int x, int z) {
        return new Op(Kind.PACKET, fence, x, z);
    }

    private static Op epoch() {
        return new Op(Kind.EPOCH, Fence.GLOBAL, 0, 0);
    }

    private static List<Op> installs(int count, int xBase) {
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ops.add(p(Fence.CHUNK_INSTALL, xBase + i, 7));
        }
        return ops;
    }

    /** Runs every script; returns the problems (empty when the replica matches the driver). */
    static List<String> check() {
        Map<String, List<Op>> scripts = new java.util.LinkedHashMap<>();
        scripts.put("first install, then ack, then more installs", List.of(
                p(Fence.CHUNK_INSTALL, 1, 1), p(Fence.ACK_TRAILING, 0, 0), p(Fence.CHUNK_INSTALL, 2, 5),
                p(Fence.REALTIME, 0, 0), p(Fence.ORDINARY_CONTROL, 0, 0), p(Fence.CONTROL_FENCE, 0, 0),
                p(Fence.VIEW, 0, 0), p(Fence.CHUNK, 3, 3), p(Fence.GLOBAL, 0, 0), p(Fence.CHUNK_INSTALL, 9, 9)));
        scripts.put("re-install of a held chunk", List.of(
                p(Fence.CHUNK_INSTALL, 1, 1), p(Fence.ACK_TRAILING, 0, 0), p(Fence.CHUNK_INSTALL, 1, 1),
                p(Fence.CHUNK_INSTALL, 4, 4), p(Fence.ACK_TRAILING, 0, 0), p(Fence.CHUNK_INSTALL, 4, 4)));
        scripts.put("forget and reinstall", List.of(
                p(Fence.CHUNK_INSTALL, 1, 1), p(Fence.ACK_TRAILING, 0, 0), p(Fence.CHUNK_FORGET, 1, 1),
                p(Fence.CHUNK_INSTALL, 1, 1), p(Fence.CHUNK_FORGET, 8, 8), p(Fence.CHUNK_INSTALL, 8, 8),
                p(Fence.CHUNK_INSTALL, 5, 5)));
        List<Op> ring = new ArrayList<>();
        ring.add(p(Fence.CHUNK_INSTALL, 1, 1));
        ring.add(p(Fence.ACK_TRAILING, 0, 0));
        for (int i = 0; i < 257; i++) {
            ring.add(p(Fence.CHUNK_FORGET, 100 + i, 3));
        }
        ring.add(p(Fence.CHUNK_INSTALL, 100, 3));
        ring.add(p(Fence.CHUNK_INSTALL, 600, 600));
        scripts.put("257 distinct forgets (ring overflow), then installs", ring);
        List<Op> held = new ArrayList<>();
        held.add(p(Fence.ACK_TRAILING, 0, 0));
        held.addAll(installs(HeldChunkSet.HELD_CAPACITY + 3, 1000));
        held.add(p(Fence.CHUNK_INSTALL, 1000, 7));
        held.add(p(Fence.CHUNK_INSTALL, 90000, 7));
        scripts.put("held-set overflow (" + (HeldChunkSet.HELD_CAPACITY + 3) + " installs)", held);
        scripts.put("epoch transition with chunks held", List.of(
                p(Fence.CHUNK_INSTALL, 1, 1), p(Fence.ACK_TRAILING, 0, 0), epoch(), p(Fence.ACK_TRAILING, 0, 0),
                p(Fence.CHUNK_INSTALL, 1, 1), p(Fence.CHUNK_INSTALL, 6, 6), p(Fence.REALTIME, 0, 0),
                p(Fence.CHUNK_FORGET, 6, 6), p(Fence.CHUNK_INSTALL, 6, 6)));
        scripts.put("epoch transition with nothing held (login-like)", List.of(
                epoch(), p(Fence.ACK_TRAILING, 0, 0), p(Fence.CHUNK_INSTALL, 6, 6), p(Fence.CHUNK_INSTALL, 7, 7),
                p(Fence.CHUNK_INSTALL, 6, 6)));
        scripts.put("epoch transition after every held chunk was forgotten", List.of(
                p(Fence.CHUNK_INSTALL, 1, 1), p(Fence.CHUNK_FORGET, 1, 1), epoch(), p(Fence.ACK_TRAILING, 0, 0),
                p(Fence.CHUNK_INSTALL, 1, 1), p(Fence.CHUNK_INSTALL, 2, 2)));
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<Op>> script : scripts.entrySet()) {
            try {
                compare(script.getKey(), script.getValue(), problems);
            } catch (Throwable t) {
                problems.add(script.getKey() + ": threw " + t);
            }
        }
        return problems;
    }

    private static byte[] frame(int index) {
        // A one-byte VarInt length, then that many bytes: a well-formed GAME frame whose content
        // varies per packet.
        byte[] frame = new byte[4];
        frame[0] = 3;
        frame[1] = (byte) index;
        frame[2] = (byte) (index >>> 8);
        frame[3] = (byte) (index >>> 16);
        return frame;
    }

    private static void compare(String name, List<Op> ops, List<String> problems) throws Exception {
        List<Record> expected = replica(ops);
        Map<Long, Record> actual = real(ops);
        for (Record e : expected) {
            Record a = actual.get(key(e.streamIndex(), e.sequence()));
            if (a == null) {
                problems.add(name + ": the real driver wrote no record " + e.streamIndex() + "#" + e.sequence());
                return;
            }
            if (a.epoch() != e.epoch() || !deps(a).equals(deps(e))) {
                problems.add(name + ": record " + e.streamIndex() + "#" + e.sequence() + " differs: driver epoch "
                        + a.epoch() + " deps " + deps(a) + " vs replica epoch " + e.epoch() + " deps " + deps(e));
                return;
            }
            // Each op's frame is unique, so equal payloads prove the same packet took this slot on both paths.
            if (!Arrays.equals(a.gameFrame(), e.gameFrame())) {
                problems.add(name + ": record " + e.streamIndex() + "#" + e.sequence() + " carries driver payload "
                        + Arrays.toString(a.gameFrame()) + " vs replica payload " + Arrays.toString(e.gameFrame()));
                return;
            }
        }
        if (actual.size() != expected.size()) {
            problems.add(name + ": the driver wrote " + actual.size() + " records, the replica assigned "
                    + expected.size());
        }
    }

    private static long key(int stream, long sequence) {
        return ((long) stream << 40) | sequence;
    }

    private static List<String> deps(Record r) {
        List<String> out = new ArrayList<>();
        for (Record.Dependency d : r.dependencies()) {
            out.add(d.streamIndex() + ":" + d.sequence());
        }
        return out;
    }

    /** The planner's path: ParitySendReplica plus a real StreamSequencer. */
    private static List<Record> replica(List<Op> ops) {
        StreamSequencer sequencer = new StreamSequencer(SHARDS);
        HeldChunkSet held = new HeldChunkSet();
        // HeldChunkSet.heldCount() is package-private; count held positions the way the planner does.
        Set<Long> positions = new HashSet<>();
        List<Record> out = new ArrayList<>();
        int index = 0;
        for (Op op : ops) {
            byte[] frame = frame(index++);
            if (op.kind() == Kind.EPOCH) {
                out.add(sequencer.assignEpochTransition(frame));
                ParitySendReplica.afterEpochTransition(held, !positions.isEmpty());
                positions.clear();
                continue;
            }
            long position = ((long) op.x() << 32) ^ (op.z() & 0xffffffffL);
            if (op.fence() == Fence.CHUNK_INSTALL) {
                positions.add(position);
            } else if (op.fence() == Fence.CHUNK_FORGET) {
                positions.remove(position);
            }
            Fence fence = ParitySendReplica.effectiveFence(held, op.fence(), op.x(), op.z());
            int stream = ParitySendReplica.streamFor(sequencer, SHARDS, fence, op.x(), op.z());
            out.add(sequencer.assign(stream, fence, frame, false));
        }
        return out;
    }

    /** The real driver: submit, poll and release every slice, decode each stream's bytes. */
    private static Map<Long, Record> real(List<Op> ops) throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PressureRanking ranking = new PressureRanking();
        NodePools node = new NodePools(1L << 40, 0, 1L << 40, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1L << 40, 1L << 40, 1L << 40, ranking.view(now::get));
        ServerProfile profile = new ServerProfile(1, 1, 1L << 30, .5, 1_000_000, 1L << 40, 4, 4, 4);
        SessionSendDriver driver = new SessionSendDriver(SessionContext.of(SHARDS), profile, pool, ranking,
                new Object(), InetAddress.getLoopbackAddress(), null, now::get);
        try {
            int index = 0;
            for (Op op : ops) {
                OutboundPacket packet = new OutboundPacket(frame(index++), 4, false);
                if (op.kind() == Kind.EPOCH) {
                    driver.submitEpochTransition(packet);
                } else {
                    driver.submit(packet, op.fence(), op.x(), op.z());
                }
            }
            Map<Integer, RecordCodec.CompactDecoder> decoders = new HashMap<>();
            Map<Long, Record> out = new HashMap<>();
            SessionSendDriver.Write write;
            while ((write = driver.poll(stream -> true)) != null) {
                int stream = write.stream();
                driver.released(write.token());
                RecordCodec.CompactDecoder decoder = decoders.computeIfAbsent(stream, RecordCodec.CompactDecoder::new);
                // The streaming decoder keeps any partial record itself, so each slice is fed once.
                ByteBuffer in = ByteBuffer.wrap(write.bytes());
                Record record;
                while ((record = decoder.next(in)) != null) {
                    if (out.put(key(stream, record.sequence()), record) != null) {
                        throw new IllegalStateException("the driver wrote " + stream + "#" + record.sequence() + " twice");
                    }
                }
            }
            return out;
        } finally {
            driver.close();
        }
    }
}
