// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import rs.sudoe.quicraft.core.multistream.ShardHash;

/**
 * Runs the parity cases (docs/protocol.md 13.21; security reviews r2/r3). For each case it
 * derives the legal delivery orders from production code (ParityPlanner), then delivers the
 * packets to the real client once per order and per tick mode and compares full snapshots.
 *
 * <p>Per case and tick mode the TCP order runs twice first: the two snapshots must be equal before
 * any reordered run is compared, which guards against a wobbly fixture and a leaky reset. A case
 * may also name one deliberately illegal order whose snapshot must differ from TCP, which proves
 * the snapshot can see the hazard the case guards against.
 */
public final class ParityRunner {
    /** Ticks the client runs after the last packet, before the snapshot. */
    private static final int SETTLE_TICKS = 5;
    private static final int EXHAUSTIVE_CAP = 24;
    private static final int RANDOM_EXTRAS = 8;

    /** What the shipped rules are expected to allow for a case. */
    enum Expect {
        /** Only the TCP order (a GLOBAL-fenced or fully chained sequence). */
        TCP_ONLY,
        /** More than the TCP order (a control case proving the harness explores reorders). */
        REORDERABLE
    }

    abstract static class ParityCase {
        abstract String name();

        abstract Expect expect();

        /** The packets in the order TCP would deliver them. */
        abstract List<ParityPlanner.Item> items(ParityFixture f);

        /** Fixture work on the client thread after the reset (blocks, predictions, player). */
        abstract void setup(Minecraft client, ParityFixture f);

        abstract List<BlockPos> watchBlocks(ParityFixture f);

        List<int[]> watchChunks(ParityFixture f) {
            return f.standardChunks();
        }

        /** Chunk positions the sender believes the client holds when the sequence starts. */
        List<int[]> heldChunks(ParityFixture f) {
            return f.standardChunks();
        }

        /** False for a case with so many records that one tick per packet would take minutes. */
        boolean spread() {
            return true;
        }

        /** Structural assertions on the derived plan (a dependency that must exist). */
        void checkPlan(ParityPlanner plan, List<String> problems) {}

        /** An order the rules forbid, whose snapshot must differ from TCP's; null when none. */
        int[] illegalOrder(int items) {
            return null;
        }
    }

    private ParityRunner() {}

    public static void run(ClientGameTestContext context) {
        Set<String> known = csv(System.getProperty("quicraft.gametest.parity.knownFailures"));
        Set<String> only = csv(System.getProperty("quicraft.gametest.parity.only"));
        List<String> failures = new ArrayList<>();
        try (ParityFixture fixture = ParityFixture.open(context)) {
            for (ParityCase c : ParityCases.all()) {
                if (!only.isEmpty() && !only.contains(c.name())) {
                    continue;
                }
                List<String> problems = new ArrayList<>();
                try {
                    runCase(fixture, c, problems);
                } catch (Throwable t) {
                    problems.add("case threw " + t);
                    log(c, "threw: " + t);
                }
                if (problems.isEmpty()) {
                    log(c, known.contains(c.name()) ? "PASS (listed as a known failure, now passes)" : "PASS");
                } else if (known.contains(c.name())) {
                    log(c, "KNOWN FAILURE: " + String.join("\n", problems));
                } else {
                    failures.add(c.name() + ": " + String.join("\n", problems));
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("parity failures:\n" + String.join("\n\n", failures));
        }
    }

    private static void runCase(ParityFixture f, ParityCase c, List<String> problems) {
        List<ParityPlanner.Item> items = c.items(f);
        ParityPlanner plan = ParityPlanner.plan(items, c.heldChunks(f));
        c.checkPlan(plan, problems);
        List<int[]> orders = plan.orders(EXHAUSTIVE_CAP, RANDOM_EXTRAS);
        log(c, "records:\n" + plan.describe() + "legal orders: " + orders.size()
                + (plan.exhaustive(EXHAUSTIVE_CAP) ? " (exhaustive)" : " (sampled)"));
        int n = items.size();
        int[] tcp = new int[n];
        for (int i = 0; i < n; i++) {
            tcp[i] = i;
        }
        if (!Arrays.equals(orders.get(0), tcp)) {
            problems.add("the TCP order is not first among the legal orders: " + Arrays.toString(orders.get(0)));
        }
        boolean tcpOnly = orders.size() == 1;
        if (c.expect() == Expect.TCP_ONLY && !tcpOnly) {
            problems.add("expected the shipped routes to allow only the TCP order, but they allow " + orders.size()
                    + " orders; a route was relaxed, so the parity runs below now cover real reorders");
        }
        if (c.expect() == Expect.REORDERABLE && tcpOnly) {
            problems.add("expected reorderable records, but the shipped routes allow only the TCP order, so this"
                    + " control case no longer explores anything");
        }
        for (boolean spread : c.spread() ? new boolean[] {false, true} : new boolean[] {false}) {
            String mode = spread ? "tick-spread" : "same-tick";
            String reference = once(f, c, items, tcp, spread);
            String second = once(f, c, items, tcp, spread);
            if (!reference.equals(second)) {
                problems.add(mode + ": the TCP order gave two different snapshots (unstable fixture or leaky reset): "
                        + firstDiff(reference, second));
                continue;
            }
            for (int[] order : orders) {
                if (Arrays.equals(order, tcp)) {
                    continue;
                }
                String got = once(f, c, items, order, spread);
                if (!got.equals(reference)) {
                    problems.add(mode + " order " + labels(items, order) + " differs from TCP: "
                            + firstDiff(reference, got));
                }
            }
            int[] illegal = c.illegalOrder(n);
            if (illegal != null) {
                String got = once(f, c, items, illegal, spread);
                if (got.equals(reference)) {
                    problems.add(mode + ": the deliberately illegal order " + labels(items, illegal)
                            + " gave the same snapshot as TCP, so this case cannot see its hazard; snapshot was:\n" + got);
                } else {
                    log(c, mode + " sensitivity ok: illegal order " + labels(items, illegal) + " differs: "
                            + firstDiff(reference, got));
                }
            }
        }
    }

    private static String once(ParityFixture f, ParityCase c, List<ParityPlanner.Item> items, int[] order,
            boolean spread) {
        f.reset();
        f.context.runOnClient(client -> c.setup(client, f));
        byte[][] frames = new byte[order.length][];
        for (int i = 0; i < order.length; i++) {
            frames[i] = items.get(order[i]).frame();
        }
        String early;
        if (spread) {
            for (byte[] frame : frames) {
                f.deliver(frame);
                f.context.waitTick();
            }
            early = "";
        } else {
            early = f.deliverAndSnapshot(frames, c.watchBlocks(f), c.watchChunks(f));
        }
        f.context.waitTicks(SETTLE_TICKS);
        return "[right after delivery]\n" + early + "[after " + SETTLE_TICKS + " ticks]\n"
                + f.snapshot(c.watchBlocks(f), c.watchChunks(f));
    }

    private static String labels(List<ParityPlanner.Item> items, int[] order) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < order.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(items.get(order[i]).label());
        }
        return sb.append(']').toString();
    }

    /** The first line that differs, with both values. */
    static String firstDiff(String a, String b) {
        String[] x = a.split("\n");
        String[] y = b.split("\n");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String l = i < x.length ? x[i] : "<absent>";
            String r = i < y.length ? y[i] : "<absent>";
            if (!l.equals(r)) {
                return "first diverging field: TCP `" + l + "` vs reordered `" + r + "`";
            }
        }
        return "no difference";
    }

    private static Set<String> csv(String value) {
        Set<String> out = new HashSet<>();
        if (value != null) {
            for (String s : value.split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    static void log(ParityCase c, String message) {
        QuicraftFabricTestLog.info("[QUICraft parity] " + c.name() + ": " + message);
    }

    /** A chunk next to the player's whose shard differs from {@code like}'s, outside the standard 3x3. */
    static int[] chunkInOtherShard(ParityFixture f, int likeX, int likeZ) {
        int cx = f.base.getX() >> 4;
        int cz = f.base.getZ() >> 4;
        int want = ShardHash.shardOf(likeX, likeZ, 0, ParityPlanner.SHARDS);
        for (int k = 0; k < 3; k++) {
            for (int[] d : new int[][] {{2 + k, 0}, {0, 2 + k}, {-2 - k, 0}, {0, -2 - k}, {2 + k, 2 + k}}) {
                if (ShardHash.shardOf(cx + d[0], cz + d[1], 0, ParityPlanner.SHARDS) != want) {
                    return new int[] {cx + d[0], cz + d[1]};
                }
            }
        }
        throw new AssertionError("no chunk in another shard near the spawn");
    }
}
