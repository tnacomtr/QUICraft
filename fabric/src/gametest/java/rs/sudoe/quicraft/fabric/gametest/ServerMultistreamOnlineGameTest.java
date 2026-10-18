// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import com.sun.net.httpserver.HttpServer;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import rs.sudoe.quicraft.bridge.BoundaryCipherDecoder;
import rs.sudoe.quicraft.bridge.MultistreamSwitch;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.server.ServerQuic;
import rs.sudoe.quicraft.fabric.server.V3Join;

/**
 * The same end-to-end {@code /3} join as {@link ServerMultistreamGameTest}, with the server in online
 * mode (docs/protocol.md 12.4 to 12.13): the raw client does vanilla's RSA and AES/CFB8 login against
 * a mock session server, the binding exchange is keyed with Minecraft's shared secret (mode 1), and
 * Minecraft's cipher is switched off at the boundary of the final switch packets. No offline test
 * flag: a server that authenticates needs none.
 */
public class ServerMultistreamOnlineGameTest implements FabricClientGameTest {
    private static final String PROFILE = "{\"id\":\"00000000000000000000000000000007\",\"name\":\"RawV3\","
            + "\"properties\":[]}";
    private static final String[] HOST_PROPERTIES = {"minecraft.api.session.host", "minecraft.api.services.host",
            "minecraft.api.profiles.host", "minecraft.api.auth.host"};

    @Override
    public void runTest(ClientGameTestContext context) {
        RoutingTable table = MultistreamSwitch.tableFor(SharedConstants.getProtocolVersion());
        if (!QuicSupport.hasKeyingMaterialExport() || !QuicSupport.hasMaxFlowControlWindows()) {
            // 12.2/12.3/13.13: this native cannot do /3, so the server stays on quicraft/2.
            QuicraftFabricTestLog.info("skipping the online-mode /3 join: this native cannot do /3 (12.2, 12.3, 13.13)");
            return;
        }
        if (!MultistreamSwitch.serves(table)) {
            // 13.7: a schema-1 resource cannot drive /3; U9a-2b lands the reviewed table.
            QuicraftFabricTestLog.info("skipping the online-mode /3 join: no reviewed schema-2 routing table for protocol "
                    + SharedConstants.getProtocolVersion() + " (13.7); the server stays on quicraft/2");
            return;
        }
        try {
            run(context, table);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void run(ClientGameTestContext context, RoutingTable table) throws Exception {
        Hooks.setFaultInjection(false);
        Path settings = FabricLoader.getInstance().getConfigDir().resolve("quicraft").resolve("server.properties");
        byte[] before = Files.exists(settings) ? Files.readAllBytes(settings) : null;
        Files.createDirectories(settings.getParent());
        Files.write(settings, "multistream=true\n".getBytes(StandardCharsets.UTF_8));
        // The mock session server: every hasJoined succeeds. The server reads the host properties when
        // it starts, so they are set before it does.
        HttpServer sessions = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        sessions.createContext("/", exchange -> {
            byte[] body = PROFILE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        sessions.start();
        String[] previous = new String[HOST_PROPERTIES.length];
        for (int i = 0; i < HOST_PROPERTIES.length; i++) {
            previous[i] = System.getProperty(HOST_PROPERTIES[i]);
            System.setProperty(HOST_PROPERTIES[i], "http://127.0.0.1:" + sessions.getAddress().getPort());
        }
        System.clearProperty("quicraft.multistream.offlineTest");
        try {
            Properties p = new Properties();
            p.setProperty("server-port", Integer.toString(GameTests.freePort()));
            p.setProperty("online-mode", "true");
            p.setProperty("level-type", "minecraft:flat");
            p.setProperty("generate-structures", "false");
            p.setProperty("spawn-protection", "0");
            p.setProperty("view-distance", "4");
            p.setProperty("simulation-distance", "4");
            try (TestDedicatedServerContext server = context.worldBuilder().createServer(p)) {
                Advertisement ad = server.computeOnServer(s -> ServerQuic.advertisement());
                check(ad != null && "quicraft/3".equals(ad.alpn()), "the server advertises quicraft/3: " + ad);
                RawV3Client.Result result;
                try (RawV3Client.Join join = RawV3Client.start(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), ad.port()), ad.fingerprint(),
                        table, 5, true)) {
                    for (int tick = 0; tick < 1200 && !join.done.isDone(); tick++) {
                        context.waitTick();
                    }
                    result = join.result;
                }
                QuicraftFabricTestLog.info("online /3 join: " + result);
                check(result.failure == null, "the raw join ran without error: " + result);
                check(result.events.contains("login encryption on"), "Minecraft's login encryption ran: " + result);
                check(result.activated, "binding activated the lanes: " + result);
                check(result.finishedConfiguration, "the configuration stage finished over records: " + result);
                check(result.playPackets >= 5, "the server sent play packets over the lanes: " + result);
                // 13.2: N comes from the preamble exchange, not from a constant. The raw client binds
                // the N it negotiated, and the server's OFFER must carry that same N.
                check(result.negotiatedN > 1, "the preamble negotiated an N above 1: " + result);
                check(result.boundN == result.negotiatedN, "the raw client bound the negotiated N: " + result);
                check(result.serverOfferN == result.negotiatedN,
                        "the server's OFFER carried the negotiated N: " + result);
                check(result.switchSent && result.switchVerified, "both switch directions ran: " + result);
                check(!result.outboundCipherRetained && !result.inboundCipherRetained,
                        "activation dropped Minecraft's cipher on the client: " + result);
                // 12.7/13.2/12.14: a peer that binds a different N must fail the exchange, and that
                // failure must leave the server's cipher in place. The raw client binds 1 on purpose
                // while the transport negotiated the real N; the server's own fail-closed deadline
                // then runs, and the gametest-only observer verifies the server's pipeline at the
                // head of that failure, before it tears anything down.
                armBindingObserver();
                try {
                    RawV3Client.Result mismatch;
                    try (RawV3Client.Join join = RawV3Client.start(new InetSocketAddress(
                            InetAddress.getLoopbackAddress(), ad.port()), ad.fingerprint(),
                            table, 5, true, false, 1)) {
                        for (int tick = 0; tick < 1200 && !join.done.isDone(); tick++) {
                            context.waitTick();
                        }
                        mismatch = join.result;
                        // Keep the raw client connected: the server's 10 second activation deadline
                        // (12.14) is what ends this join, and the observer must see that failure.
                        for (int tick = 0; tick < 900 && bindingObserverEvidence() == null; tick++) {
                            context.waitTick();
                        }
                    }
                    QuicraftFabricTestLog.info("online /3 wrong-N join: " + mismatch);
                    check(mismatch.failure != null, "the wrong-N binding failed on the client: " + mismatch);
                    check(!mismatch.activated, "no activation on the client: " + mismatch);
                    check(mismatch.boundN == 1, "the raw client bound N=1 on purpose: " + mismatch);
                    check(mismatch.negotiatedN > 1, "the transport still negotiated N>1: " + mismatch);
                    check(mismatch.serverOfferN == mismatch.negotiatedN,
                            "the server's OFFER carried its own negotiated N: " + mismatch);
                    check(!mismatch.switchSent && !mismatch.switchVerified, "no switch ran: " + mismatch);
                    check(mismatch.outboundCipherRetained && mismatch.inboundCipherRetained,
                            "the failure left Minecraft's cipher on the client: " + mismatch);

                    check(bindingObserverInvocations() == 1,
                            "the server's V3Join.fail ran exactly once for the armed attempt, saw "
                                    + bindingObserverInvocations());
                    check(!bindingObserverDivergentChannel(),
                            "the observed failure came from a single connection");
                    BindingFailureEvidence observed = bindingObserverEvidence();
                    check(observed != null, "the server-side observer captured the armed failure");
                    check(observed.reflectionError == null, "the observer's reflection ran: " + observed);
                    check(observed.onChannelEventLoop, "the failure ran on the channel's event loop: " + observed);
                    check(observed.remotePort == mismatch.clientPort && mismatch.clientPort > 0,
                            "the observed failure is this attempt's connection: " + observed + " vs client port "
                                    + mismatch.clientPort);
                    check(observed.encryptPresent, "the server still had its encrypt handler: " + observed);
                    check(observed.decryptIsCutoverBoundary,
                            "the server still had the cutover decoder installed: " + observed);
                    check(!observed.decoderStopRequested && !observed.decoderFinished,
                            "the server's boundary decoder neither stopped nor finished: " + observed);
                    QuicraftFabricTestLog.info("online /3 wrong-N server evidence: " + observed);
                } finally {
                    clearBindingObserver();
                }
                context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
            }
        } finally {
            for (int i = 0; i < HOST_PROPERTIES.length; i++) {
                if (previous[i] == null) {
                    System.clearProperty(HOST_PROPERTIES[i]);
                } else {
                    System.setProperty(HOST_PROPERTIES[i], previous[i]);
                }
            }
            sessions.stop(0);
            if (before != null) {
                Files.write(settings, before);
            } else {
                Files.deleteIfExists(settings);
            }
        }
    }

    // --- Server-side observation of the armed binding failure.

    /** Bounded, immutable: flags, identities, one port and a truncated reason. Never cipher state. */
    static final class BindingFailureEvidence {
        final String reason;
        final int channelIdentity;
        final int connectionIdentity;
        final int remotePort;
        final boolean onChannelEventLoop;
        final boolean encryptPresent;
        final boolean decryptIsCutoverBoundary;
        final boolean decoderStopRequested;
        final boolean decoderFinished;
        final String reflectionError;

        BindingFailureEvidence(String reason, int channelIdentity, int connectionIdentity, int remotePort,
                boolean onChannelEventLoop, boolean encryptPresent, boolean decryptIsCutoverBoundary,
                boolean decoderStopRequested, boolean decoderFinished, String reflectionError) {
            this.reason = reason == null || reason.length() <= 200 ? reason : reason.substring(0, 200) + "...";
            this.channelIdentity = channelIdentity;
            this.connectionIdentity = connectionIdentity;
            this.remotePort = remotePort;
            this.onChannelEventLoop = onChannelEventLoop;
            this.encryptPresent = encryptPresent;
            this.decryptIsCutoverBoundary = decryptIsCutoverBoundary;
            this.decoderStopRequested = decoderStopRequested;
            this.decoderFinished = decoderFinished;
            this.reflectionError = reflectionError;
        }

        @Override
        public String toString() {
            return "reason=" + reason + " channel=" + Integer.toHexString(channelIdentity) + " connection="
                    + Integer.toHexString(connectionIdentity) + " remotePort=" + remotePort + " onChannelEventLoop="
                    + onChannelEventLoop + " encryptPresent=" + encryptPresent + " decryptIsCutoverBoundary="
                    + decryptIsCutoverBoundary + " decoderStopRequested=" + decoderStopRequested
                    + " decoderFinished=" + decoderFinished + " reflectionError=" + reflectionError;
        }
    }

    private static final Object OBSERVER_LOCK = new Object();
    private static boolean observerArmed;
    private static BindingFailureEvidence observerEvidence;
    private static int observerInvocations;
    private static boolean observerDivergentChannel;

    /**
     * Called by the gametest-only {@code V3JoinBindingObserverMixin} at the head of the production
     * server's {@code V3Join.fail(String, Throwable)}, on whatever thread runs that failure (the
     * armed path here runs on the channel's event loop). It verifies the real pipeline synchronously,
     * before the failure tears anything down, and keeps only bounded values.
     */
    public static void bindingFailed(V3Join join, String reason) {
        BindingFailureEvidence captured = captureBindingFailure(join, reason);
        synchronized (OBSERVER_LOCK) {
            if (!observerArmed) {
                return;
            }
            observerInvocations++;
            if (observerEvidence == null) {
                observerEvidence = captured;
            } else if (observerEvidence.channelIdentity != captured.channelIdentity
                    || observerEvidence.connectionIdentity != captured.connectionIdentity) {
                observerDivergentChannel = true;
            }
        }
    }

    static void armBindingObserver() {
        synchronized (OBSERVER_LOCK) {
            observerArmed = true;
            observerEvidence = null;
            observerInvocations = 0;
            observerDivergentChannel = false;
        }
    }

    static void clearBindingObserver() {
        synchronized (OBSERVER_LOCK) {
            observerArmed = false;
            observerEvidence = null;
            observerInvocations = 0;
            observerDivergentChannel = false;
        }
    }

    static BindingFailureEvidence bindingObserverEvidence() {
        synchronized (OBSERVER_LOCK) {
            return observerEvidence;
        }
    }

    static int bindingObserverInvocations() {
        synchronized (OBSERVER_LOCK) {
            return observerInvocations;
        }
    }

    static boolean bindingObserverDivergentChannel() {
        synchronized (OBSERVER_LOCK) {
            return observerDivergentChannel;
        }
    }

    private static BindingFailureEvidence captureBindingFailure(V3Join join, String reason) {
        int channelIdentity = 0;
        int connectionIdentity = 0;
        int remotePort = -1;
        boolean onChannelEventLoop = false;
        boolean encryptPresent = false;
        boolean decryptIsCutoverBoundary = false;
        boolean decoderStopRequested = false;
        boolean decoderFinished = false;
        String reflectionError = null;
        try {
            Object channel = privateField(V3Join.class, "channel").get(join);
            Object connection = privateField(V3Join.class, "connection").get(join);
            if (channel == null || connection == null) {
                throw new IllegalStateException("the join has no channel or connection");
            }
            channelIdentity = System.identityHashCode(channel);
            connectionIdentity = System.identityHashCode(connection);
            if (!(channel instanceof QuicBridgeChannel)) {
                throw new IllegalStateException("unexpected channel type " + channel.getClass().getName());
            }
            QuicBridgeChannel bridge = (QuicBridgeChannel) channel;
            onChannelEventLoop = bridge.eventLoop().inEventLoop();
            ChannelPipeline pipeline = bridge.pipeline();
            encryptPresent = pipeline.get("encrypt") != null;
            Object decrypt = pipeline.get("decrypt");
            decryptIsCutoverBoundary = decrypt instanceof BoundaryCipherDecoder;
            if (decryptIsCutoverBoundary) {
                decoderStopRequested = privateField(BoundaryCipherDecoder.class, "stopRequested")
                        .getBoolean(decrypt);
                decoderFinished = privateField(BoundaryCipherDecoder.class, "finished").getBoolean(decrypt);
            }
            SocketAddress remote = bridge.remoteAddress();
            remotePort = remote instanceof InetSocketAddress ? ((InetSocketAddress) remote).getPort() : -1;
        } catch (Throwable t) {
            reflectionError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
        return new BindingFailureEvidence(reason, channelIdentity, connectionIdentity, remotePort,
                onChannelEventLoop, encryptPresent, decryptIsCutoverBoundary, decoderStopRequested, decoderFinished,
                reflectionError);
    }

    private static Field privateField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
