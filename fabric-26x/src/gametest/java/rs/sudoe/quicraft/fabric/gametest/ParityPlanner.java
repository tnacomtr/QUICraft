// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import rs.sudoe.quicraft.core.multistream.HeldChunkSet;
import rs.sudoe.quicraft.core.multistream.Record;
import rs.sudoe.quicraft.core.multistream.SendRouteResolver;
import rs.sudoe.quicraft.core.multistream.StreamSequencer;
import rs.sudoe.quicraft.core.multistream.StreamSequencer.Fence;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.extract.ExtractorRegistry;

/**
 * Derives the /3-legal delivery orders of a clientbound packet sequence from production code: the
 * shipped routing table, SendRouteResolver, HeldChunkSet and a real StreamSequencer assign every
 * packet its stream, sequence and dependencies; a delivery order is legal when each record comes
 * after its own-stream predecessor and every listed dependency (the receiver's rule, docs/protocol.md
 * 13.10). The DAG mirrors StreamReceiver.prerequisitesApplied; the receiver itself is not driven here
 * (it needs a route validator per record). Nothing about routing is written down here, so a table change changes the orders.
 */
final class ParityPlanner {
    static final int PROTOCOL = 775;
    static final int SHARDS = 4;

    /** One packet of the sequence, in TCP order. */
    record Item(String label, byte[] frame) {}

    /** One assigned record. */
    record Step(String label, int packetId, Fence fence, int stream, long sequence, int[] preds, boolean ack) {}

    private final List<Step> steps;

    private ParityPlanner(List<Step> steps) {
        this.steps = steps;
    }

    List<Step> steps() {
        return steps;
    }

    static int packetId(byte[] frame) {
        int value = 0;
        for (int i = 0, shift = 0; i < 5; i++, shift += 7) {
            int b = frame[i];
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new AssertionError("bad packet id varint");
    }

    static ParityPlanner plan(List<Item> items) {
        return plan(items, List.of());
    }

    /**
     * @param heldChunks chunk positions the client already holds, as the session's first-view burst
     *     would have installed them; they make a later install of the same position a re-install
     */
    static ParityPlanner plan(List<Item> items, List<int[]> heldChunks) {
        RoutingTable table = RoutingRegistry.forProtocol(PROTOCOL).orElseThrow(
                () -> new AssertionError("no shipped routing table for protocol " + PROTOCOL));
        Map<String, Extractor> extractors = new HashMap<>();
        for (String identifier : table.extractors().values()) {
            extractors.put(identifier, ExtractorRegistry.decoder(identifier));
        }
        StreamSequencer sequencer = new StreamSequencer(SHARDS);
        HeldChunkSet held = new HeldChunkSet();
        for (int[] chunk : heldChunks) {
            held.install(chunk[0], chunk[1]);
        }
        List<Record> records = new ArrayList<>();
        List<Step> raw = new ArrayList<>();
        Map<Long, Integer> byKey = new HashMap<>();
        // heldCount() is package-private in core, so the planner counts held positions itself.
        Set<Long> heldPositions = new LinkedHashSet<>();
        for (int[] chunk : heldChunks) {
            heldPositions.add(((long) chunk[0] << 32) ^ (chunk[1] & 0xffffffffL));
        }
        for (Item item : items) {
            int id = packetId(item.frame());
            rs.sudoe.quicraft.core.routing.RouteV2 route = table.routeV2For(new rs.sudoe.quicraft.core.routing.RouteKey(
                    PROTOCOL, ProtocolState.PLAY, Direction.CLIENTBOUND, id));
            Record rec;
            Fence fence;
            boolean ack = false;
            if (route != null && route.epochChange) {
                // The table's flag, not an id: SessionSendDriver.submitEpochTransition (:645-669).
                rec = sequencer.assignEpochTransition(item.frame());
                fence = Fence.GLOBAL;
                ParitySendReplica.afterEpochTransition(held, !heldPositions.isEmpty());
                heldPositions.clear();
            } else {
                SendRouteResolver.Resolved r = SendRouteResolver.resolve(table::routeV2For, extractors, PROTOCOL,
                        Direction.CLIENTBOUND, ProtocolState.PLAY, id, item.frame());
                if (r.failed()) {
                    throw new AssertionError(item.label() + " (id " + id + ") does not resolve: " + r.failure());
                }
                long position = ((long) r.chunkX() << 32) ^ (r.chunkZ() & 0xffffffffL);
                if (r.fence() == Fence.CHUNK_INSTALL) {
                    heldPositions.add(position);
                } else if (r.fence() == Fence.CHUNK_FORGET) {
                    heldPositions.remove(position);
                }
                fence = ParitySendReplica.effectiveFence(held, r.fence(), r.chunkX(), r.chunkZ());
                int stream = ParitySendReplica.streamFor(sequencer, SHARDS, fence, r.chunkX(), r.chunkZ());
                rec = sequencer.assign(stream, fence, item.frame(), r.interaction());
                ack = fence == Fence.ACK_TRAILING;
            }
            byKey.put(key(rec.streamIndex(), rec.sequence()), records.size());
            records.add(rec);
            raw.add(new Step(item.label(), id, fence, rec.streamIndex(), rec.sequence(), null, ack));
        }
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            Record rec = records.get(i);
            Set<Integer> preds = new LinkedHashSet<>();
            Integer own = byKey.get(key(rec.streamIndex(), rec.sequence() - 1));
            if (own != null) {
                preds.add(own);
            }
            for (Record.Dependency d : rec.dependencies()) {
                Integer j = byKey.get(key(d.streamIndex(), d.sequence()));
                if (j == null) {
                    throw new AssertionError(raw.get(i).label() + " depends on an unassigned record " + d.streamIndex()
                            + ":" + d.sequence());
                }
                if (j != i) {
                    preds.add(j);
                }
            }
            Step s = raw.get(i);
            steps.add(new Step(s.label(), s.packetId(), s.fence(), s.stream(), s.sequence(),
                    preds.stream().mapToInt(Integer::intValue).toArray(), s.ack()));
        }
        return new ParityPlanner(steps);
    }

    private static long key(int stream, long sequence) {
        return ((long) stream << 40) | sequence;
    }

    /** True when record {@code later} cannot be applied before record {@code earlier} (transitively). */
    boolean mustFollow(int later, int earlier) {
        boolean[] seen = new boolean[steps.size()];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(later);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            for (int p : steps.get(i).preds()) {
                if (p == earlier) {
                    return true;
                }
                if (!seen[p]) {
                    seen[p] = true;
                    queue.add(p);
                }
            }
        }
        return false;
    }

    /** True when {@link #orders} lists every legal order. */
    boolean exhaustive(int cap) {
        return enumerate(cap + 1).size() <= cap;
    }

    /** True when the shipped rules allow exactly the TCP order. */
    boolean tcpOnly() {
        return enumerate(2).size() == 1;
    }

    /**
     * Legal orders as index permutations: every order when there are at most {@code cap}, otherwise
     * the TCP order, extreme strategies (ACK as early or as late as legal, reversed-priority) and
     * seeded random orders. The TCP order is always first.
     */
    List<int[]> orders(int cap, int randomExtras) {
        List<int[]> all = enumerate(cap + 1);
        if (all.size() <= cap) {
            return all;
        }
        List<int[]> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int mode = 0; mode < 6; mode++) {
            add(result, seen, greedy(mode, null));
        }
        Random random = new Random(0x51C0DEL);
        for (int i = 0; i < randomExtras; i++) {
            add(result, seen, greedy(6, random));
        }
        return result;
    }

    private static void add(List<int[]> out, Set<String> seen, int[] order) {
        if (seen.add(Arrays.toString(order))) {
            out.add(order);
        }
    }

    private List<int[]> enumerate(int limit) {
        List<int[]> out = new ArrayList<>();
        int n = steps.size();
        int[] order = new int[n];
        boolean[] done = new boolean[n];
        dfs(0, order, done, out, limit);
        return out;
    }

    private boolean ready(int i, boolean[] done) {
        if (done[i]) {
            return false;
        }
        for (int p : steps.get(i).preds()) {
            if (!done[p]) {
                return false;
            }
        }
        return true;
    }

    private void dfs(int depth, int[] order, boolean[] done, List<int[]> out, int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (depth == steps.size()) {
            out.add(order.clone());
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (ready(i, done)) {
                done[i] = true;
                order[depth] = i;
                dfs(depth + 1, order, done, out, limit);
                done[i] = false;
            }
        }
    }

    /**
     * Greedy topological order. Mode 0 TCP (lowest index), 1 ACK as late as legal, 2 ACK as early as
     * legal, 3 highest index first, 4 highest stream first, 5 lowest stream first then index,
     * 6 random.
     */
    private int[] greedy(int mode, Random random) {
        int n = steps.size();
        int[] order = new int[n];
        boolean[] done = new boolean[n];
        for (int depth = 0; depth < n; depth++) {
            int best = -1;
            long bestScore = Long.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                if (!ready(i, done)) {
                    continue;
                }
                Step s = steps.get(i);
                long score;
                switch (mode) {
                    case 0: score = -i; break;
                    case 1: score = (s.ack() ? -1_000_000L : 0) - i; break;
                    case 2: score = (s.ack() ? 1_000_000L : 0) - i; break;
                    case 3: score = i; break;
                    case 4: score = s.stream() * 10_000L - i; break;
                    case 5: score = -s.stream() * 10_000L - i; break;
                    default: score = random.nextInt(1 << 20); break;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            done[best] = true;
            order[depth] = best;
        }
        return order;
    }

    /** One line per record: label, id, fence, stream:sequence, predecessors. */
    String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            sb.append(i).append(' ').append(s.label()).append(" id=").append(s.packetId()).append(' ')
                    .append(s.fence()).append(" s").append(s.stream()).append('#').append(s.sequence())
                    .append(" after=").append(Arrays.toString(s.preds())).append('\n');
        }
        return sb.toString();
    }
}
