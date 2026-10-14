// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
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
        // The server's preamble can take more than the spec's 5 ms on a busy CI runner; the
        // rejected-0-RTT path itself is covered by core's EarlyDataTest.
        QuicClient.setGraceFloorForTests(100);
        QuicraftClient.get().setMode(Mode.AUTO);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            join(context, server, true, false, "first join: records the first flight");
            long released = server.computeOnServer(s -> ServerQuic.earlyReleases());
            join(context, server, true, true, "rejoin: 0-RTT", true);
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
            QuicClient.setGraceFloorForTests(0);
        }
    }

    private static void join(ClientGameTestContext context, TestDedicatedServerContext server, boolean quic,
            boolean zeroRtt, String what) {
        join(context, server, quic, zeroRtt, what, false);
    }

    /**
     * Waits up to 100 ticks for the server's TLS session ticket, which the next join needs to
     * send 0-RTT data. It arrives after the handshake, on its own schedule, so a fixed wait raced
     * it on slow CI runners.
     */
    private static void awaitSessionTicket(ClientGameTestContext context, TestDedicatedServerContext server,
            String what) {
        Advertisement ad = server.computeOnServer(s -> ServerQuic.advertisement());
        check(ad != null, what + ": the server advertises QUIC");
        InetSocketAddress v4 = new InetSocketAddress(InetAddress.getLoopbackAddress(), ad.port());
        InetSocketAddress v6 = new InetSocketAddress("::1", ad.port());
        for (int i = 0; i < 100; i++) {
            if (QuicClient.hasSessionTicket(v4, ad.fingerprint()) || QuicClient.hasSessionTicket(v6, ad.fingerprint())) {
                return;
            }
            context.waitTick();
        }
        check(false, what + ": no TLS session ticket after 100 ticks");
    }

    /** {@code play}: the full scripted session (PlaySession), checking for desync after the 0-RTT start. */
    private static void join(ClientGameTestContext context, TestDedicatedServerContext server, boolean quic,
            boolean zeroRtt, String what, boolean play) {
        long start = System.nanoTime();
        try (var connection = server.connect()) {
            GameTests.waitForChunks(connection);
            check(GameTests.connectedOverQuic(context) == quic, what + ": over " + (quic ? "QUIC" : "TCP"));
            boolean used = context.computeOnClient(c -> c.getConnection() != null
                    && Transports.usedZeroRtt(c.getConnection().getConnection()));
            check(used == zeroRtt, what + ": 0-RTT " + (zeroRtt ? "used" : "not used")
                    + " (client: " + QuicClient.lastEarlyOutcome() + ")");
            if (play) {
                PlaySession.run(context, server, connection);
            }
            if (quic) {
                awaitSessionTicket(context, server, what);
            }
            context.waitTicks(10); // the session ticket and the next token arrive
        }
        QuicraftFabricTestLog.info(what + ": " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
}
