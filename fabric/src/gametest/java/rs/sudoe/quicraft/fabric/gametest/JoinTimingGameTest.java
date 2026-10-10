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
 * over TCP and N times over QUIC, alternating, and logs the time from the connect to a rendered
 * world. Loopback, so it shows what the mod costs the client, not what a network costs.
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
        List<Long> tcp = new ArrayList<>();
        List<Long> quic = new ArrayList<>();
        List<Long> tcpLogin = new ArrayList<>();
        List<Long> quicLogin = new ArrayList<>();
        List<Long> tcpChunks = new ArrayList<>();
        List<Long> quicChunks = new ArrayList<>();
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            for (int i = 0; i < runs + 1; i++) {
                for (Mode mode : new Mode[] {Mode.TCP_ONLY, Mode.AUTO}) {
                    client.setMode(mode);
                    JoinClock.reset();
                    long start = System.nanoTime();
                    try (var connection = server.connect()) {
                        GameTests.waitForChunks(connection);
                        long micros = (System.nanoTime() - start) / 1_000;
                        check(GameTests.connectedOverQuic(context) == (mode == Mode.AUTO), "transport for " + mode);
                        context.waitTicks(40); // let the chunk stream finish before reading the clock
                        if (i > 0) { // the first round warms up both paths
                            boolean q = mode == Mode.AUTO;
                            (q ? quic : tcp).add(micros);
                            (q ? quicLogin : tcpLogin).add((JoinClock.loginAt.get() - start) / 1_000);
                            (q ? quicChunks : tcpChunks).add((JoinClock.lastChunkAt.get() - start) / 1_000);
                        }
                    }
                }
            }
        } finally {
            client.setMode(Mode.AUTO);
        }
        QuicraftFabricTestLog.info(String.format(Locale.ROOT, "join to rendered world, %d runs each: TCP median %.1f ms "
                + "(min %.1f, max %.1f); QUIC median %.1f ms (min %.1f, max %.1f)", runs, median(tcp) / 1000.0,
                Collections.min(tcp) / 1000.0, Collections.max(tcp) / 1000.0, median(quic) / 1000.0,
                Collections.min(quic) / 1000.0, Collections.max(quic) / 1000.0));
        QuicraftFabricTestLog.info(String.format(Locale.ROOT, "connect to play login (Netty thread): TCP median %.2f ms, "
                + "QUIC median %.2f ms; connect to last chunk: TCP median %.2f ms, QUIC median %.2f ms (%d chunks)",
                median(tcpLogin) / 1000.0, median(quicLogin) / 1000.0, median(tcpChunks) / 1000.0,
                median(quicChunks) / 1000.0, JoinClock.chunks.get()));
        QuicraftFabricTestLog.info("join timings TCP " + tcp + " QUIC " + quic + " (µs)");
        QuicraftFabricTestLog.info("login timings TCP " + tcpLogin + " QUIC " + quicLogin + " (µs)");
    }

    private static double median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
