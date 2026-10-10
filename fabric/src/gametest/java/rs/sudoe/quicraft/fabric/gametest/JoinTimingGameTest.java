// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;

/**
 * Opt-in measurement (-Pquicraft.joinTimings=N): the real client joins the same server N times
 * each over TCP, over QUIC with a full handshake and over QUIC resuming the previous session,
 * interleaved, and logs the time from the connect to a rendered world. Loopback, so it shows what the mod costs the client, not what a network costs.
 */
public class JoinTimingGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        int runs = Integer.getInteger("quicraft.gametest.joinTimings", 0);
        if (runs <= 0) {
            return;
        }
        Hooks.setFaultInjection(false);
        QuicraftClient client = QuicraftClient.get();
        String[] kinds = {"TCP", "QUIC full", "QUIC resumed"};
        List<List<Long>> join = lists(kinds.length);
        List<List<Long>> login = lists(kinds.length);
        List<List<Long>> active = lists(kinds.length);
        List<List<Long>> chunks = lists(kinds.length);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            for (int i = 0; i < runs + 1; i++) {
                for (int k = 0; k < kinds.length; k++) {
                    Mode mode = k == 0 ? Mode.TCP_ONLY : Mode.AUTO;
                    client.setMode(mode);
                    if (k == 1) {
                        // Resumed joins reuse the ticket from the join before (the full one).
                        rs.sudoe.quicraft.core.transport.QuicClient.forgetSessions();
                    }
                    if (mode == Mode.AUTO) {
                        // Keep the advertisement fresh (60 s TTL): no status query in the timing.
                        rs.sudoe.quicraft.core.discovery.Advertisement ad =
                                server.computeOnServer(s -> rs.sudoe.quicraft.fabric.server.ServerQuic.advertisement());
                        int port = server.computeOnServer(s -> s.getPort());
                        for (java.net.InetSocketAddress key : new java.net.InetSocketAddress[] {
                                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port),
                                new java.net.InetSocketAddress("::1", port)}) {
                            client.connector().advertisements().record(key, java.util.Optional.of(ad));
                        }
                    }
                    rs.sudoe.quicraft.core.tls.Fingerprint fp =
                            server.computeOnServer(s -> rs.sudoe.quicraft.fabric.server.ServerQuic.advertisement()).fingerprint();
                    int checksBefore = rs.sudoe.quicraft.core.transport.QuicClient.certificateChecks(fp,
                            rs.sudoe.quicraft.core.transport.TransportConfig.DEFAULT);
                    JoinClock.reset();
                    long start = System.nanoTime();
                    try (var connection = server.connect()) {
                        GameTests.waitForChunks(connection);
                        long micros = (System.nanoTime() - start) / 1_000;
                        check(GameTests.connectedOverQuic(context) == (mode == Mode.AUTO), "transport for " + mode);
                        int checks = rs.sudoe.quicraft.core.transport.QuicClient.certificateChecks(fp,
                                rs.sudoe.quicraft.core.transport.TransportConfig.DEFAULT) - checksBefore;
                        check(checks == (k == 1 ? 1 : 0), kinds[k] + ": " + checks + " certificate checks");
                        context.waitTicks(40); // let the chunk stream finish before reading the clock
                        if (i > 0) { // the first round warms up every path
                            join.get(k).add(micros);
                            login.get(k).add((JoinClock.loginAt.get() - start) / 1_000);
                            active.get(k).add((JoinClock.activeAt.get() - start) / 1_000);
                            chunks.get(k).add((JoinClock.lastChunkAt.get() - start) / 1_000);
                        }
                    }
                }
            }
        } finally {
            client.setMode(Mode.AUTO);
        }
        for (int k = 0; k < kinds.length; k++) {
            QuicraftFabricTestLog.info(String.format(Locale.ROOT, "%s, %d runs: connect to Connection active median "
                    + "%.2f ms; to play login (Netty thread) %.2f ms; to last chunk %.2f ms (%d chunks); to rendered "
                    + "world %.1f ms (min %.1f, max %.1f)", kinds[k], runs, median(active.get(k)) / 1000.0,
                    median(login.get(k)) / 1000.0, median(chunks.get(k)) / 1000.0, JoinClock.chunks.get(),
                    median(join.get(k)) / 1000.0, Collections.min(join.get(k)) / 1000.0,
                    Collections.max(join.get(k)) / 1000.0));
            QuicraftFabricTestLog.info(kinds[k] + " timings (µs): active " + active.get(k) + " login " + login.get(k)
                    + " join " + join.get(k));
        }
    }

    private static List<List<Long>> lists(int n) {
        List<List<Long>> lists = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lists.add(new ArrayList<>());
        }
        return lists;
    }

    private static double median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
