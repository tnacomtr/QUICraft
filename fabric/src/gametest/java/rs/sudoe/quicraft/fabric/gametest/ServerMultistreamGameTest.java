// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.ServerQuic;

/**
 * The dedicated server's {@code /3} join end to end, without the client adapter (docs/protocol.md
 * 12 and 13): a raw {@code quicraft/3} client logs in, runs the binding exchange in the
 * configuration stage, switches to the three lanes and receives the player's play packets. The
 * server runs offline with the explicit test profile of 12.19. Skipped on a native that cannot do
 * {@code /3}.
 */
public class ServerMultistreamGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        RoutingTable table = MultistreamSwitch.tableFor(SharedConstants.getProtocolVersion());
        if (!QuicSupport.hasKeyingMaterialExport() || !QuicSupport.hasMaxFlowControlWindows()) {
            // 12.2/12.3/13.13: this native cannot do /3, so the server stays on quicraft/2.
            QuicraftFabricTestLog.info("skipping the offline /3 join: this native cannot do /3 (12.2, 12.3, 13.13)");
            return;
        }
        if (!MultistreamSwitch.serves(table)) {
            // 13.7: a schema-1 resource cannot drive /3; U9a-2b lands the reviewed table.
            QuicraftFabricTestLog.info("skipping the offline /3 join: no reviewed schema-2 routing table for protocol "
                    + SharedConstants.getProtocolVersion() + " (13.7); the server stays on quicraft/2");
            return;
        }
        Hooks.setFaultInjection(false);
        try {
            run(context, table);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void run(ClientGameTestContext context, RoutingTable table) throws Exception {
        Path settings = FabricLoader.getInstance().getConfigDir().resolve("quicraft").resolve("server.properties");
        byte[] before = Files.exists(settings) ? Files.readAllBytes(settings) : null;
        Files.createDirectories(settings.getParent());
        Files.write(settings, "multistream=true\n".getBytes(StandardCharsets.UTF_8));
        System.setProperty("quicraft.multistream.offlineTest", "true");
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            Advertisement ad = server.computeOnServer(s -> ServerQuic.advertisement());
            check(ad != null && "quicraft/3".equals(ad.alpn()), "the server advertises quicraft/3: " + ad);
            RawV3Client.Result result;
            try (RawV3Client.Join join = RawV3Client.start(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    ad.port()), ad.fingerprint(), table, 5, false)) {
                // The harness steps the game's ticks only while the test waits for them.
                for (int tick = 0; tick < 1200 && !join.done.isDone(); tick++) {
                    context.waitTick();
                }
                result = join.result;
            }
            QuicraftFabricTestLog.info("offline /3 join: " + result);
            check(result.failure == null, "the raw join ran without error: " + result);
            check(result.activated, "binding activated the lanes: " + result);
            check(result.finishedConfiguration, "the configuration stage finished over records: " + result);
            check(result.playPackets >= 5, "the server sent play packets over the lanes: " + result);
            // 13.2: N is the exchange's, not a constant. The raw client binds the N it negotiated,
            // and the server's OFFER must carry that same N.
            check(result.negotiatedN > 1, "the preamble negotiated an N above 1: " + result);
            check(result.boundN == result.negotiatedN, "the raw client bound the negotiated N: " + result);
            check(result.serverOfferN == result.negotiatedN,
                    "the server's OFFER carried the negotiated N: " + result);
            // 12.7/13.2/12.14: the server's own N validation runs before the proof's MAC. The raw
            // client binds the negotiated N and accepts the OFFER; its one CLIENT_PROOF carries N=1
            // with the tag the machine computed for the negotiated N, so the server fails the
            // exchange on wrong-N metadata. Offline has no cipher, so this case asserts the rejection
            // itself; the online case asserts cipher retention too. This case runs first: the
            // client-side rejection below leaves a join pending on the server until its deadline,
            // which must not fall inside this armed observer window.
            ServerMultistreamOnlineGameTest.armBindingObserver();
            try {
                RawV3Client.Result wrongProof;
                try (RawV3Client.Join join = RawV3Client.start(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        ad.port()), ad.fingerprint(), table, 5, false, false, 0, true)) {
                    for (int tick = 0; tick < 1200 && !join.done.isDone(); tick++) {
                        context.waitTick();
                    }
                    wrongProof = join.result;
                    for (int tick = 0; tick < 300
                            && ServerMultistreamOnlineGameTest.bindingObserverEvidence(wrongProof.clientPort) == null; tick++) {
                        context.waitTick();
                    }
                }
                QuicraftFabricTestLog.info("offline /3 wrong-proof join: " + wrongProof);
                // The server picks its preferred 3 inside the client's 1..6 offer, so a real /3
                // exchange negotiates N=3 here; the case is meaningless if it ever ran on N=1.
                check(wrongProof.negotiatedN == 3, "the transport negotiated N=3: " + wrongProof);
                check(wrongProof.boundN == 3, "the client bound the negotiated N=3: " + wrongProof);
                check(wrongProof.serverOfferN == 3, "the server's OFFER carried N=3: " + wrongProof);
                check(wrongProof.rewrittenProofs == 1,
                        "exactly one CLIENT_PROOF was rewritten and submitted, got " + wrongProof.rewrittenProofs);
                check(!wrongProof.activated, "no activation on the client: " + wrongProof);
                check(!wrongProof.switchSent && !wrongProof.switchVerified, "no switch ran: " + wrongProof);

                check(ServerMultistreamOnlineGameTest.bindingObserverInvocations(wrongProof.clientPort) == 1,
                        "the server's V3Join.fail ran exactly once for the armed attempt, saw "
                                + ServerMultistreamOnlineGameTest.bindingObserverInvocations(wrongProof.clientPort));
                check(!ServerMultistreamOnlineGameTest.bindingObserverDivergentChannel(wrongProof.clientPort),
                        "the observed failure came from a single connection");
                ServerMultistreamOnlineGameTest.BindingFailureEvidence observed =
                        ServerMultistreamOnlineGameTest.bindingObserverEvidence(wrongProof.clientPort);
                check(observed != null, "the server-side observer captured the armed failure");
                check(observed.reflectionError == null, "the observer's reflection ran: " + observed);
                check(observed.onChannelEventLoop, "the failure ran on the channel's event loop: " + observed);
                check(observed.remotePort == wrongProof.clientPort && wrongProof.clientPort > 0,
                        "the observed failure is this attempt's connection: " + observed + " vs client port "
                                + wrongProof.clientPort);
                check(observed.reason != null && observed.reason.contains("WRONG_SHARD_COUNT"),
                        "the server rejected the wrong N itself: " + observed);
                check(observed.reason != null && !observed.reason.contains("deadline"),
                        "the rejection is not the activation deadline: " + observed);
                QuicraftFabricTestLog.info("offline /3 wrong-proof server evidence: " + observed);
            } finally {
                ServerMultistreamOnlineGameTest.clearBindingObserver();
            }
            // The reciprocal direction, last: here the client rejects the server's OFFER locally and
            // no proof ever reaches the server. Offline there is no cipher to retain, so this case
            // checks only that the exchange failed and nothing activated.
            RawV3Client.Result mismatch;
            try (RawV3Client.Join join = RawV3Client.start(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    ad.port()), ad.fingerprint(), table, 5, false, false, 1)) {
                for (int tick = 0; tick < 1200 && !join.done.isDone(); tick++) {
                    context.waitTick();
                }
                mismatch = join.result;
            }
            QuicraftFabricTestLog.info("offline /3 wrong-N join: " + mismatch);
            check(mismatch.failure != null, "the wrong-N binding failed on the client: " + mismatch);
            check(!mismatch.activated, "no activation on the client: " + mismatch);
            check(!mismatch.switchSent && !mismatch.switchVerified, "no switch ran: " + mismatch);
            check(mismatch.boundN == 1, "the raw client bound N=1 on purpose: " + mismatch);
            check(mismatch.negotiatedN > 1, "the transport still negotiated N>1: " + mismatch);
            check(mismatch.serverOfferN == mismatch.negotiatedN,
                    "the server's OFFER carried its own negotiated N: " + mismatch);
            // The world builder leaves the client on its create-world screen when no game client joins.
            context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        } finally {
            System.clearProperty("quicraft.multistream.offlineTest");
            if (before != null) {
                Files.write(settings, before);
            } else {
                Files.deleteIfExists(settings);
            }
        }
    }
}
