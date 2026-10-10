// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.client.Transports;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * 0-RTT rejoins with a real client (docs/protocol.md §8): the rejoin sends its first flight as
 * 0-RTT data and the server acts on it; a first flight that differs makes the client join again
 * over TCP, without an error screen; an attempt abandoned after its 0-RTT data (the server already
 * started that login) still ends in a TCP join; and each 0-RTT stage failing leaves a working join.
 */
public class EarlyDataGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        Faults.set();
        QuicClient.forgetSessions();
        QuicraftClient.get().setMode(Mode.AUTO);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            join(context, server, true, false, "first join: records the first flight");
            long released = server.computeOnServer(s -> ServerQuic.earlyReleases());
            join(context, server, true, true, "rejoin: 0-RTT");
            check(server.computeOnServer(s -> ServerQuic.earlyReleases()) == released + 1,
                    "the server acted on the 0-RTT flight");

            // The game's packets differ from the 0-RTT ones: QUIC closes, the join goes again over TCP.
            Faults.set(Faults.EARLY_COMPARE);
            join(context, server, false, false, "mismatch: joined again over TCP");
            Faults.set();
            join(context, server, true, false, "after a mismatch: QUIC without 0-RTT, recording again");
            join(context, server, true, true, "then 0-RTT again");

            // TCP wins after the 0-RTT flight went out: the server has a login in progress for us.
            Faults.set(Faults.EARLY_ABANDON);
            released = server.computeOnServer(s -> ServerQuic.earlyReleases());
            join(context, server, false, false, "abandoned 0-RTT attempt: joined over TCP");
            check(server.computeOnServer(s -> ServerQuic.earlyReleases()) == released + 1,
                    "the abandoned attempt's flight reached the server");
            Faults.set();
            QuicraftClient.get().connector().forgetFailures();

            // The client-side stages: no 0-RTT data is sent.
            for (String stage : new String[] {Faults.EARLY_SEND, Faults.EARLY_RECORD, Faults.EARLY_TOKEN_ISSUE}) {
                QuicClient.forgetSessions();
                Faults.set(stage);
                join(context, server, true, false, stage + " failing: first join");
                join(context, server, true, false, stage + " failing: rejoin without 0-RTT");
                Faults.set();
            }
            // The server can't check the token: the 0-RTT data waits for the handshake, as without one.
            QuicClient.forgetSessions();
            Faults.set(Faults.EARLY_TOKEN_REDEEM);
            join(context, server, true, false, "early token redeem failing: first join");
            released = server.computeOnServer(s -> ServerQuic.earlyReleases());
            join(context, server, true, true, "early token redeem failing: rejoin sends 0-RTT");
            check(server.computeOnServer(s -> ServerQuic.earlyReleases()) == released,
                    "early token redeem failing: the server held the 0-RTT data for the handshake");
            Faults.set();
        } finally {
            Faults.set();
        }
    }

    private static void join(ClientGameTestContext context, TestDedicatedServerContext server, boolean quic,
            boolean zeroRtt, String what) {
        long start = System.nanoTime();
        try (var connection = server.connect()) {
            GameTests.waitForChunks(connection);
            check(GameTests.connectedOverQuic(context) == quic, what + ": over " + (quic ? "QUIC" : "TCP"));
            boolean used = context.computeOnClient(c -> c.getConnection() != null
                    && Transports.usedZeroRtt(c.getConnection().getConnection()));
            check(used == zeroRtt, what + ": 0-RTT " + (zeroRtt ? "used" : "not used"));
            context.waitTicks(10); // the session ticket and the next token arrive
        }
        QuicraftFabricTestLog.info(what + ": " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
}
