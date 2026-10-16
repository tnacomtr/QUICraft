// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.util.Crypt;
import rs.sudoe.quicraft.bridge.BoundaryCipherDecoder;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.connect.ConnectionRace;
import rs.sudoe.quicraft.core.connect.FailureCache;
import rs.sudoe.quicraft.core.connect.V3Plan;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LaneSession;
import rs.sudoe.quicraft.core.multistream.RecordCodec;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.core.connect.TransportSelection;
import rs.sudoe.quicraft.fabric.Hooks;

/**
 * The client's {@code quicraft/3} join, played against a scripted server: the real game pipeline
 * (vanilla handlers, the boundary-aware cipher decoder, the bridge channel), the real
 * {@link V3Client}, hooks and {@code V3Plan}, and core's real {@link BindingSession} on both ends. Only
 * the QUIC connection is fake, so the lanes themselves (a {@code LaneSession} needs a QUIC channel)
 * stop at {@code activate}: the activation leftover is checked, not the records after it.
 *
 * <p>It proves the order of docs/protocol.md 12.10 to 12.13 on the client against vanilla's own
 * cipher and framing: encrypted frames, the switch packets, ciphertext and record bytes arriving
 * in one read or in 7-byte pieces, and what each failure leaves behind (a retry over TCP with the
 * user's wording, a recorded failure, no pin). The scripted server has no Minecraft login; the hooks
 * the login mixins call are called directly (their application is checked in
 * {@code MultistreamClientGameTest}).</p>
 */
public class MultistreamCutoverGameTest implements FabricClientGameTest {
    private static final String HOST = "mc.example.com";
    private static final int PORT = 25565;

    @Override
    public void runTest(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        // The protocol number only has to name a shipped routing table: a game version without one
        // (1.21.11 today) still exercises its own pipeline against the 26.x placeholder's number.
        int actual = SharedConstants.getCurrentVersion().protocolVersion();
        int protocol = RoutingRegistry.forProtocol(actual).isPresent() ? actual : 775;
        Optional<RoutingTable> table = RoutingRegistry.forProtocol(protocol);
        if (table.isEmpty()) {
            rs.sudoe.quicraft.fabric.QuicraftFabric.LOG.info(
                    "QUICraft: no routing table for protocol {}, the cutover harness is skipped", protocol);
            return;
        }
        try {
            onlineExchange(protocol, table.get(), Integer.MAX_VALUE);
            onlineExchange(protocol, table.get(), 7);
            offlineExchange(protocol, table.get());
            aTamperedFingerprintFallsBackToTcp(protocol, table.get());
            helloWithoutAuthenticationFallsBack(protocol, table.get());
            encryptionWithoutAuthenticationFallsBack(protocol, table.get());
            quicOnlyShowsTheFailureInsteadOfFallingBack(protocol, table.get());
            aClosedConnectionBeforeActivationFallsBack(protocol, table.get());
            aServerDisconnectIsNotAMultistreamFailure(protocol, table.get());
            heldPacketsStayHeldUntilActivation(protocol, table.get());
            aProtocolErrorBeforeActivationFallsBackToTcp(protocol, table.get(), false);
            aProtocolErrorBeforeActivationFallsBackToTcp(protocol, table.get(), true);
            aHookFailureAfterActivationDisconnectsAndTheNextJoinUsesTcp(protocol, table.get());
            anOversizedBindingPayloadOutsideV3IsDiscardedAsVanillaDoes(table.get());
            outboundPacketsStillPassTheGamesHandlersAndTheProtocolSwitchesSurvive(protocol, table.get());
            writesWaitWhileTheLanesAreFullAndGoOnInOrder(protocol, table.get());
            acknowledgementsFollowAcceptanceNotTheMainThread(protocol, table.get());
            aSilentConnectionTimesOutLikeVanillasDoes(protocol, table.get());
            offlineTestModeNeverAcceptsAChangedPin(protocol, table.get());
            theSharedSecretIsWipedOnEveryTerminalPath(protocol, table.get());
            aCancelDuringBindingEndsTheSession(protocol, table.get());
            aFailedPipelineSetupFallsBackToTcp(protocol, table.get(), false);
            aFailedPipelineSetupFallsBackToTcp(protocol, table.get(), true);
            aChangedCertificateIsReboundAndTheNewPinIsNoticed(protocol, table.get());
            aReboundThatGetsNoOfferFailsAfterTheBoundedWait(protocol, table.get());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    // --- Scenarios.

    /** 12.10 to 12.13 with a real cipher: the join reaches ACTIVE and the pin is recorded. */
    private static void onlineExchange(int protocol, RoutingTable table, int chunk) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(chunk);

            check(rig.awaitState(V3Client.State.ACTIVE), "the exchange activated, state " + rig.client.state());
            check(rig.fake.activated, "core's activate() ran");
            check(Arrays.equals(rig.server.recordsAfterSwitch, rig.fake.activationLeftover),
                    "the record bytes right behind SERVER_SWITCH reached activate() untouched (12.13), got "
                            + Arrays.toString(rig.fake.activationLeftover) + " wanted "
                            + Arrays.toString(rig.server.recordsAfterSwitch));
            check(rig.channel.pipeline().get("decrypt") == null, "the boundary decoder removed itself");
            check(rig.awaitCondition(() -> rig.channel.pipeline().get("encrypt") == null),
                    "the encrypt handler was removed after CLIENT_SWITCH");
            check(rig.connection.isEncrypted(), "12.13: the game still knows the login was encrypted");
            check(rig.channel.pipeline().get(V3Handler.TAIL_NAME) != null, "the lanes' outbound handler is installed");
            check(rig.plan.isActivated(), "the plan recorded the activation");
            check(rig.files.pins().pin(HOST, PORT, Collections.emptySet()).isPresent(),
                    "12.15: an authenticated pin was written");
            check(Transports.v3State(rig.connection) == V3Client.State.ACTIVE, "F3 would say /3");
            check(rig.fake.abortCode < 0, "nothing was aborted");
            check(!Transports.takeRetryOverTcp(rig.connection), "no TCP retry");
            check(V3Notices.isEmpty(), "nothing to tell the player");
        }
    }

    /** 12.19: the explicit offline test profile, no cipher: bytes behind the switch come from the splitter. */
    private static void offlineExchange(int protocol, RoutingTable table) throws Exception {
        System.setProperty(V3Client.OFFLINE_TEST_PROPERTY, "true");
        try (Rig rig = new Rig(protocol, table, false, true)) {
            rig.login(false, false);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);

            check(rig.awaitState(V3Client.State.ACTIVE), "the offline exchange activated, state " + rig.client.state());
            check(Arrays.equals(rig.server.recordsAfterSwitch, rig.fake.activationLeftover),
                    "the splitter's cumulation behind SERVER_SWITCH reached activate() untouched, got "
                            + Arrays.toString(rig.fake.activationLeftover));
            check(rig.files.pins().pin(HOST, PORT, Collections.emptySet()).isEmpty(),
                    "12.19: offline mode never writes an authenticated pin");
        } finally {
            System.clearProperty(V3Client.OFFLINE_TEST_PROPERTY);
        }
    }

    /** 12.9: an OFFER for another fingerprint ends in 0x10, a TCP retry, the user's wording and no pin. */
    private static void aTamperedFingerprintFallsBackToTcp(int protocol, RoutingTable table) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.server.fingerprint[0] ^= 1;
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);

            check(rig.awaitState(V3Client.State.FAILED), "the attempt failed, state " + rig.client.state());
            rig.expectFallback("channel binding failed");
        }
    }

    /** 12.7: Hello without authentication ends the attempt before anything is encrypted. */
    private static void helloWithoutAuthenticationFallsBack(int protocol, RoutingTable table) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            check(!V3Hooks.hello(rig.connection, false), "vanilla's handleHello is cancelled");
            check(rig.awaitState(V3Client.State.FAILED), "failed");
            rig.expectFallback("login is not authenticated");
        }
    }

    /** 12.7: a LAN login whose session join failed still encrypts; /3 must refuse it at Login Finished. */
    private static void encryptionWithoutAuthenticationFallsBack(int protocol, RoutingTable table) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, false);
            check(rig.channel.pipeline().get("decrypt") != null
                    && !(rig.channel.pipeline().get("decrypt") instanceof BoundaryCipherDecoder),
                    "no cutover was prepared for an unauthenticated login");
            check(!V3Hooks.loginFinished(rig.connection), "vanilla's handleLoginFinished is cancelled");
            check(rig.awaitState(V3Client.State.FAILED), "failed");
            rig.expectFallback("login is encrypted but not authenticated");
        }
    }

    /** §4: quic-only never falls back silently; the failure is shown instead. */
    private static void quicOnlyShowsTheFailureInsteadOfFallingBack(int protocol, RoutingTable table)
            throws Exception {
        try (Rig rig = new Rig(protocol, table, true, false)) {
            check(!V3Hooks.hello(rig.connection, false), "cancelled");
            check(rig.awaitState(V3Client.State.FAILED), "failed");
            check(rig.awaitCondition(() -> rig.fake.abortCode == 0x10), "QUIC closed with 0x10: " + rig.fake.abortCode);
            check(!Transports.takeRetryOverTcp(rig.connection), "quic-only does not retry over TCP");
            check(V3Notices.isEmpty(), "and does not announce a fallback");
            check("QUIC multi-stream failed (login is not authenticated)".equals(rig.client.failureText()),
                    "the disconnect screen says why: " + rig.client.failureText());
            check("QUIC multi-stream failed (login is not authenticated)".equals(Transports.v3Failure(rig.connection)),
                    "and the text outlives the controller: " + Transports.v3Failure(rig.connection));
            check(rig.plan.isFailed(), "the failure is still recorded");
        }
    }

    /** The peer ends the connection before activation: that is a /3 failure, retried over TCP. */
    private static void aClosedConnectionBeforeActivationFallsBack(int protocol, RoutingTable table) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.fake.closeByPeer();

            check(rig.awaitState(V3Client.State.FAILED), "failed, state " + rig.client.state());
            rig.expectFallback("connection closed before multi-stream was active");
        }
    }

    /** A server that kicks the player (a disconnect packet, then close) is not a /3 failure. */
    private static void aServerDisconnectIsNotAMultistreamFailure(int protocol, RoutingTable table) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            byte[] kick = rig.server.encrypted(rig.server.frame(
                    new ClientboundDisconnectPacket(Component.literal("You are not white-listed"))));
            rig.fake.inject(kick);
            rig.awaitCondition(() -> rig.listener.disconnects > 0);
            rig.fake.closeByPeer();
            Thread.sleep(300);

            check(rig.client.state() == V3Client.State.BINDING, "still " + rig.client.state());
            check(!Transports.takeRetryOverTcp(rig.connection), "the kick shows as the kick, not as a retry");
            check(V3Notices.isEmpty(), "no fallback line");
        }
    }

    /** 12.10: ordinary packets are held after CLIENT_PROOF and fail with the connection if it ends. */
    private static void heldPacketsStayHeldUntilActivation(int protocol, RoutingTable table) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            // OFFER -> CLIENT_PROOF only; the client is quiescent from here.
            rig.deliverServerChunks(Integer.MAX_VALUE);
            rig.awaitCondition(() -> rig.fake.written.size() > 0);
            rig.server.onClientBytes(rig.fake.takeWritten());
            int before = rig.fake.written.size();
            io.netty.channel.ChannelFuture held = rig.channel.writeAndFlush(
                    new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(7L));
            Thread.sleep(200);
            check(!held.isDone(), "12.10: the ordinary packet is held, not written");
            check(rig.fake.written.size() == before, "and nothing reached the wire");

            rig.fake.closeByPeer(); // the attempt ends: the held packet's promise fails with the connection
            check(rig.awaitState(V3Client.State.FAILED), "failed");
            check(rig.awaitCondition(held::isDone) && !held.isSuccess(), "the held packet failed with the connection");
        }
    }

    /**
     * 12.8: outside a /3 exchange a quicraft:binding payload does nothing at all. A join on TCP or
     * /2 (no multi-stream handler in its pipeline) decodes even an over-long one exactly as vanilla
     * does, by discarding its bytes, instead of failing the decode.
     */
    private static void anOversizedBindingPayloadOutsideV3IsDiscardedAsVanillaDoes(RoutingTable table)
            throws Exception {
        EmbeddedChannel server = new EmbeddedChannel();
        EmbeddedChannel client = new EmbeddedChannel();
        try {
            net.minecraft.network.Connection.configureSerialization(server.pipeline(), PacketFlow.SERVERBOUND, false,
                    null);
            server.pipeline().replace("outbound_config", "encoder",
                    new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
            net.minecraft.network.Connection.configureSerialization(client.pipeline(), PacketFlow.CLIENTBOUND, false,
                    null);
            client.pipeline().replace("inbound_config", "decoder",
                    new PacketDecoder<>(ConfigurationProtocols.CLIENTBOUND));
            LaneCodec serverCodec = new LaneCodec(server, table);
            ByteBuf frame = Unpooled.buffer();
            Packet<?> packet = new ClientboundCustomPayloadPacket(
                    new rs.sudoe.quicraft.fabric.net.BindingPayload(new byte[500]));
            serverCodec.routeOf(packet);
            serverCodec.encode(packet, frame);

            LaneGame.Decoded decoded = new LaneCodec(client, table).decode(frame, ProtocolState.CONFIGURATION,
                    (retained, total) -> { });
            check(decoded.packet() instanceof ClientboundCustomPayloadPacket p
                    && p.payload() instanceof net.minecraft.network.protocol.common.custom.DiscardedPayload,
                    "vanilla discards the bytes: " + decoded.packet());
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /**
     * After activation a packet still goes through every handler the game and its mods put in front of
     * the encoder, and the codec swaps (with Fabric API's own handlers removing themselves and
     * coming back with the next protocol) keep working: no duplicate handler name at the next switch.
     */
    private static void outboundPacketsStillPassTheGamesHandlersAndTheProtocolSwitchesSurvive(int protocol,
            RoutingTable table) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);
            check(rig.awaitState(V3Client.State.ACTIVE), "active");
            List<String> sent = java.util.Collections.synchronizedList(new ArrayList<>());
            rig.client.useLaneSend((state, id, frame) -> sent.add(state + ":" + id));
            boolean splitter = rig.channel.pipeline().get("fabric:splitter") != null;
            check(splitter, "Fabric API's splitter is in the pipeline this test is about: "
                    + rig.channel.pipeline().names());

            rig.connection.send(new ServerboundKeepAlivePacket(1L));
            check(rig.awaitCondition(() -> sent.size() == 1), "a packet reached the lanes: " + sent);
            check(sent.get(0).startsWith("CONFIGURATION:"), "in the protocol it was sent in: " + sent);
            List<String> names = rig.channel.pipeline().names();
            check(names.indexOf(V3Handler.SINK_NAME) == names.indexOf("encoder") - 1,
                    "the sink is right behind the encoder: " + names);

            // The terminal packet swaps the encoder out and takes the game's own splitter with it ...
            rig.connection.send(ServerboundFinishConfigurationPacket.INSTANCE);
            check(rig.awaitCondition(() -> sent.size() == 2), "the terminal packet reached the lanes: " + sent);
            check(rig.awaitCondition(() -> rig.channel.pipeline().get("outbound_config") != null),
                    "the encoder swapped itself out: " + rig.channel.pipeline().names());
            check(rig.channel.pipeline().get("fabric:splitter") == null,
                    "and the game's own handler went with it: " + rig.channel.pipeline().names());

            // ... and the next protocol is installed by the game's own call, handlers and all.
            rig.connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
            check(rig.channel.pipeline().get("encoder") instanceof PacketEncoder, "the encoder is back");
            check(rig.channel.pipeline().get("fabric:splitter") != null == splitter, "no duplicate, no loss");
            rig.connection.send(new ServerboundKeepAlivePacket(2L));
            check(rig.awaitCondition(() -> sent.size() == 3), "and packets flow again: " + sent);
            check(rig.fake.abortCode < 0 && rig.client.state() == V3Client.State.ACTIVE, "nothing failed");
        }
    }

    /**
     * 13.10: a delivery is acknowledged once the game's serial handler accepted it, not when the main
     * thread has run it, so a burst completes at network speed even if the main thread never runs.
     * Only a backlog over the bound (256 packets or 1 MiB accepted but not run) holds the
     * acknowledgement, until the main thread has caught up.
     */
    private static void acknowledgementsFollowAcceptanceNotTheMainThread(int protocol, RoutingTable table)
            throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);
            check(rig.awaitState(V3Client.State.ACTIVE), "active");
            List<Runnable> mainThread = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Long> acked = java.util.Collections.synchronizedList(new ArrayList<>());
            rig.client.useAcknowledgement(mainThread::add, (lane, sequence) -> acked.add(sequence));

            // A burst of 100 deliveries; the main thread never runs.
            for (long i = 1; i <= 100; i++) {
                rig.deliver(i, 0);
            }
            check(acked.size() == 100, "every delivery was acknowledged without a main-thread run: " + acked.size());
            check(mainThread.size() == 100, "and one marker per delivery waits behind the packets");

            // Over the bound: the 257th accepted-but-unrun packet is held.
            for (long i = 101; i <= 256; i++) {
                rig.deliver(i, 0);
            }
            check(acked.size() == 256, "up to the bound: " + acked.size());
            rig.deliver(257, 0);
            check(acked.size() == 256, "over it the acknowledgement is held: " + acked.size());

            // The main thread catches up: the held acknowledgement is released.
            List<Runnable> run = new ArrayList<>(mainThread);
            mainThread.clear();
            for (Runnable marker : run) {
                marker.run();
            }
            check(acked.size() == 257 && acked.get(256) == 257L, "released when the backlog drained: " + acked.size());

            // The byte bound holds too: one delivery that decoded 2 MiB.
            rig.client.noteDecodedBytes(2 << 20);
            rig.deliver(258, 0);
            check(acked.size() == 257, "over the byte bound it is held: " + acked.size());
            for (Runnable marker : new ArrayList<>(mainThread)) {
                marker.run();
            }
            check(acked.size() == 258, "and released after the drain: " + acked.size());
        }
    }

    /**
     * 13.12: while the lanes cannot take more, the game's writes wait, in order, and go on when they
     * can; only the caps are fatal.
     */
    private static void writesWaitWhileTheLanesAreFullAndGoOnInOrder(int protocol, RoutingTable table)
            throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);
            check(rig.awaitState(V3Client.State.ACTIVE), "active");
            List<Long> sent = java.util.Collections.synchronizedList(new ArrayList<>());
            rig.client.useLaneSend((state, id, frame) -> sent.add((long) (frame[frame.length - 1] & 0xFF)));

            rig.client.lanes().onWritabilityChanged(false);
            rig.awaitCondition(() -> !rig.client.lanes().isWritable());
            List<io.netty.channel.ChannelFuture> pending = new ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                pending.add(rig.channel.writeAndFlush(new ServerboundKeepAlivePacket(i)));
            }
            Thread.sleep(300);
            check(sent.isEmpty(), "nothing is sent while the lanes are full: " + sent);
            check(pending.stream().noneMatch(io.netty.util.concurrent.Future::isDone), "and no write completed");

            rig.client.lanes().onWritabilityChanged(true);
            check(rig.awaitCondition(() -> sent.size() == 50), "they all went on: " + sent.size());
            check(pending.stream().allMatch(io.netty.util.concurrent.Future::isSuccess), "and completed");
            List<Long> expected = new ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                expected.add((long) i);
            }
            check(sent.equals(expected), "in the order they were written: " + sent);
            check(rig.fake.abortCode < 0 && rig.client.state() == V3Client.State.ACTIVE, "no 0x11, still active");

            // Past the caps it is fatal: 1024 packets.
            rig.client.lanes().onWritabilityChanged(false);
            rig.awaitCondition(() -> !rig.client.lanes().isWritable());
            for (int i = 0; i < 1100; i++) {
                rig.channel.write(new ServerboundKeepAlivePacket(i));
            }
            rig.channel.flush();
            check(rig.awaitCondition(() -> rig.fake.abortCode == 0x11), "the overflow closes with 0x11: "
                    + rig.fake.abortCode);
            check(rig.plan.isFailed(), "and is recorded");
        }
    }

    /**
     * Vanilla's 30 s read timeout cannot see lane traffic, so an active /3 connection watches for
     * silence itself and raises the same TimeoutException (shortened to 400 ms here).
     */
    private static void aSilentConnectionTimesOutLikeVanillasDoes(int protocol, RoutingTable table)
            throws Exception {
        V3Client.readTimeoutMillis = 400;
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);
            check(rig.awaitState(V3Client.State.ACTIVE), "active");
            check(rig.channel.pipeline().get("timeout") == null, "the byte path's timeout handler is gone");
            check(rig.awaitCondition(() -> !rig.channel.isOpen()), "silence closed the connection, as vanilla does");
            check(rig.awaitCondition(() -> rig.plan.isFailed()), "the failure is recorded");
            check(!Transports.takeRetryOverTcp(rig.connection), "after activation nothing is retried");
            rig.expectNextJoinUsesTcp();
        } finally {
            V3Client.readTimeoutMillis = 30_000L;
        }
    }

    /**
     * A decoder failure before activation (here a packet id that does not exist, or an over-long
     * binding payload) is a /3 failure: it must reach the failure path before vanilla closes the
     * connection, so the join is retried over TCP and the player is told.
     */
    private static void aProtocolErrorBeforeActivationFallsBackToTcp(int protocol, RoutingTable table,
            boolean oversizedBinding) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            byte[] frame;
            if (oversizedBinding) {
                frame = rig.server.frame(new ClientboundCustomPayloadPacket(
                        new rs.sudoe.quicraft.fabric.net.BindingPayload(new byte[500])));
            } else {
                frame = new byte[] {3, 0x7e, 1, 2}; // length 3: an unknown packet id and two bytes
            }
            rig.fake.inject(rig.server.encrypted(frame));

            check(rig.awaitState(V3Client.State.FAILED), "failed, state " + rig.client.state());
            rig.expectFallback("protocol error");
        }
    }

    /**
     * After activation a failure ends the connection with a reason and no retry, and the next join
     * goes straight to TCP (12.14: the activation failure is recorded either way).
     */
    private static void aHookFailureAfterActivationDisconnectsAndTheNextJoinUsesTcp(int protocol,
            RoutingTable table) throws Exception {
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);
            check(rig.awaitState(V3Client.State.ACTIVE), "active");

            rig.client.fail(new IllegalStateException("a hook failed"));

            check(rig.awaitState(V3Client.State.FAILED), "failed, state " + rig.client.state());
            check(rig.awaitCondition(() -> rig.fake.abortCode >= 0), "the connection was closed: " + rig.fake.abortCode);
            check(rig.plan.isFailed(), "the failure is recorded");
            check(!Transports.takeRetryOverTcp(rig.connection), "no retry after activation");
            check(V3Notices.isEmpty(), "no fallback line: the join was already in play");
            check(Transports.v3Failure(rig.connection) != null
                    && Transports.v3Failure(rig.connection).startsWith("QUIC multi-stream failed"),
                    "the disconnect has a clear reason: " + Transports.v3Failure(rig.connection));
            rig.expectNextJoinUsesTcp();
        }
    }

    /** A key whose encoding is one array we can look at afterwards. */
    private static final class WatchedKey implements SecretKey {
        private static final long serialVersionUID = 1L;
        final byte[] encoded;

        WatchedKey(byte[] encoded) {
            this.encoded = encoded;
        }

        @Override
        public String getAlgorithm() {
            return "AES";
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return encoded;
        }
    }

    private static boolean zeroed(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 12.6: S exists only as long as the exchange needs it. The temporary copy of the encoding is
     * wiped at once, and the retained one on every way the join can end: success, failure, the
     * player cancelling, the server kicking, the game closing. A capture after the end is refused.
     */
    private static void theSharedSecretIsWipedOnEveryTerminalPath(int protocol, RoutingTable table)
            throws Exception {
        // The temporary: wiped right after the hook, while the retained clone is real.
        try (Rig rig = new Rig(protocol, table, false, false)) {
            byte[] encoded = rig.secret.clone();
            V3Hooks.secret(rig.connection, new WatchedKey(encoded));
            check(zeroed(encoded), "the temporary encoding is wiped");
            byte[] retained = rig.client.retainedSecret();
            check(retained != null && !zeroed(retained), "while the retained one is the real secret");
        }
        // Failure before the exchange starts (an unauthenticated session join).
        try (Rig rig = new Rig(protocol, table, false, false)) {
            V3Hooks.hello(rig.connection, true);
            V3Hooks.secret(rig.connection, rig.key);
            byte[] retained = rig.client.retainedSecret();
            V3Hooks.authenticated(rig.connection, Component.literal("no"));
            rig.connection.setEncryptionKey(Crypt.getCipher(Cipher.DECRYPT_MODE, rig.key),
                    Crypt.getCipher(Cipher.ENCRYPT_MODE, rig.key));
            check(!V3Hooks.loginFinished(rig.connection), "refused");
            check(rig.awaitCondition(() -> zeroed(retained)), "wiped on failure");
            V3Hooks.secret(rig.connection, rig.key);
            check(rig.client.retainedSecret() == null, "a late capture is refused after the end");
        }
        // The player cancels while logging in.
        try (Rig rig = new Rig(protocol, table, false, false)) {
            V3Hooks.secret(rig.connection, rig.key);
            byte[] retained = rig.client.retainedSecret();
            rig.channel.close().sync();
            check(rig.awaitCondition(() -> zeroed(retained)), "wiped when the game closes the channel early");
        }
        // The server kicks the player (a disconnect packet, then the close).
        try (Rig rig = new Rig(protocol, table, false, false)) {
            V3Hooks.secret(rig.connection, rig.key);
            byte[] retained = rig.client.retainedSecret();
            rig.fake.inject(rig.server.frame(new ClientboundDisconnectPacket(Component.literal("bye"))));
            rig.awaitCondition(() -> rig.listener.disconnects > 0);
            rig.fake.closeByPeer();
            check(rig.awaitCondition(() -> zeroed(retained)), "wiped when the server ends the connection");
        }
        // Success: the exchange takes its copy and the retained one is wiped at the start.
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            byte[] retained = rig.client.retainedSecret();
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);
            check(rig.awaitState(V3Client.State.ACTIVE), "active");
            check(zeroed(retained), "wiped by the successful exchange");
            rig.channel.close().sync();
            check(zeroed(retained), "and still after the game closes");
        }
    }

    /**
     * A cancel while the channel binding exchange is running (the Cancel button, a quit) ends the
     * exchange with the channel: the session's self-tick, its quiescence queue and its key material
     * go with it, and none of it comes back as a /3 failure or a TCP join the player did not ask
     * for (12.6, 12.10, 12.14).
     */
    private static void aCancelDuringBindingEndsTheSession(int protocol, RoutingTable table) throws Exception {
        // A cancel after OFFER: ordinary writes were queued in the session by then (12.10), and they
        // must fail with the connection instead of outliving it.
        try (Rig rig = new Rig(protocol, table, false, false)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            check(rig.awaitState(V3Client.State.BINDING), "the exchange started");
            rig.server.start();
            // OFFER -> CLIENT_PROOF only: the client is quiescent from here.
            rig.deliverServerChunks(Integer.MAX_VALUE);
            check(rig.awaitCondition(() -> rig.fake.written.size() > 0), "CLIENT_PROOF went out");
            rig.server.onClientBytes(rig.fake.takeWritten());
            io.netty.channel.ChannelFuture held = rig.channel.writeAndFlush(
                    new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(21L));
            Thread.sleep(200);
            check(!held.isDone(), "12.10: the ordinary write is held by the exchange");
            check(rig.fake.written.size() == 0, "and nothing reached the wire");

            rig.channel.close().sync(); // the player cancels mid-exchange
            check(rig.awaitCondition(held::isDone) && !held.isSuccess(),
                    "the cancel releases what the session still held, done " + held.isDone()
                            + " success " + held.isSuccess());
            check(rig.fake.abortCode < 0, "no QUIC close was asked for: " + rig.fake.abortCode);
            check(!Transports.takeRetryOverTcp(rig.connection), "and no TCP join");
            check(V3Notices.isEmpty(), "and nothing is said to the player");
        }
        // A cancel before OFFER, on a changed pin: the session's own offer wait is 500 ms (12.16),
        // so a session that survived the cancel would fail on its ticker and start a TCP join well
        // inside this test.
        String old = "sha256:" + "44".repeat(32);
        try (Rig rig = new Rig(protocol, table, false, false, old)) {
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            check(rig.awaitState(V3Client.State.BINDING), "the exchange started, state " + rig.client.state());

            rig.channel.close().sync(); // the player cancels
            Thread.sleep(1200); // past the offer wait: a live session would have failed by now
            check(!Transports.takeRetryOverTcp(rig.connection), "a cancel is not a /3 failure: no TCP join");
            check(V3Notices.isEmpty(), "and nothing is said to the player");
            check(rig.fake.abortCode < 0, "no QUIC close was asked for: " + rig.fake.abortCode);
            check(!rig.plan.isFailed(), "and nothing is recorded");
        }
    }

    /**
     * Setting up the join's pipeline fails after the QUIC connection won: nothing is attached to the
     * screen, the QUIC connection is closed, and auto retries over TCP with the notice while quic-only
     * reports the error (docs/protocol.md 12.14, section 4).
     */
    private static void aFailedPipelineSetupFallsBackToTcp(int protocol, RoutingTable table, boolean quicOnly)
            throws Exception {
        try (Rig rig = new Rig(protocol, table, quicOnly, false)) {
            Fake second = rig.fake;
            io.netty.channel.ChannelPromise promise = new io.netty.channel.DefaultChannelPromise(new EmbeddedChannel(), rig.loop);
            int[] tcpJoins = {0};
            Hooks.setFaultInjection("v3 setup");
            boolean[] attached = {false};
            try {
                ClientConnect.useV3(second, rig.plan, rig.loop, new Connection(PacketFlow.CLIENTBOUND), promise,
                        attached, quicOnly ? Mode.QUIC_ONLY : Mode.AUTO, rig.files,
                        new net.minecraft.client.multiplayer.resolver.ServerAddress(HOST, PORT), () -> {
                            tcpJoins[0]++;
                            return new EmbeddedChannel().newSucceededFuture();
                        });
            } finally {
                Hooks.setFaultInjection(false);
            }
            check(rig.awaitCondition(promise::isDone), "the screen's future completes");
            check(!attached[0], "nothing stayed attached");
            check(rig.awaitCondition(() -> !second.open), "the QUIC connection was closed");
            check(rig.plan.isFailed(), "the failure is recorded");
            if (quicOnly) {
                check(!promise.isSuccess() && promise.cause().getMessage().contains("QUIC multi-stream failed"),
                        "quic-only reports the error: " + promise.cause());
                check(tcpJoins[0] == 0 && V3Notices.isEmpty(), "and does not fall back");
            } else {
                check(promise.isSuccess() && tcpJoins[0] == 1, "auto joins over TCP: " + promise);
                List<V3Notices.Notice> notices = V3Notices.drain();
                check(notices.size() == 1 && notices.get(0).literal().contains("fell back to TCP"),
                        "with the notice: " + notices);
            }
        }
    }

    /**
     * 12.16: a changed pin is only ever re-bound by an authenticated login. The explicit offline test
     * flag lets an unencrypted login reach the exchange for a first contact, never for a changed pin:
     * no lanes, the old pin kept, and the decision-table fallback.
     */
    private static void offlineTestModeNeverAcceptsAChangedPin(int protocol, RoutingTable table) throws Exception {
        System.setProperty(V3Client.OFFLINE_TEST_PROPERTY, "true");
        String old = "sha256:" + "33".repeat(32);
        try (Rig rig = new Rig(protocol, table, false, true, old)) {
            check(rig.plan.pinState() == TransportSelection.PinState.CHANGED, "a changed pin");
            check(!V3Hooks.loginFinished(rig.connection), "an unauthenticated login is refused for a changed pin");
            check(rig.awaitState(V3Client.State.FAILED), "failed, state " + rig.client.state());
            rig.expectFallback(V3Plan.REBIND_FAILED);
            check(!rig.fake.activated, "no lanes were activated");
        } finally {
            System.clearProperty(V3Client.OFFLINE_TEST_PROPERTY);
        }
    }

    /**
     * 12.16: the advertised fingerprint differs from the pin. The attempt is a full handshake with no
     * early data, the exchange runs with the new fingerprint, and only then is the pin replaced and
     * the player told once, naming both.
     */
    private static void aChangedCertificateIsReboundAndTheNewPinIsNoticed(int protocol, RoutingTable table)
            throws Exception {
        String old = "sha256:" + "11".repeat(32);
        try (Rig rig = new Rig(protocol, table, false, false, old)) {
            check(rig.plan.pinState() == TransportSelection.PinState.CHANGED, "a changed pin: " + rig.plan.pinState());
            check(rig.platform.bypassResumption, "12.16: a full handshake, no resumption");
            check(rig.files.pins().pin(HOST, PORT, Collections.emptySet()).get().fingerprint().equals(old),
                    "the old pin stays until the exchange passes");
            rig.login(true, true);
            rig.client.onConfigurationStarted();
            rig.awaitState(V3Client.State.BINDING);
            rig.server.start();
            rig.run(Integer.MAX_VALUE);

            check(rig.awaitState(V3Client.State.ACTIVE), "the re-bind activated, state " + rig.client.state());
            String fresh = rig.identity.fingerprint().toString();
            check(rig.files.pins().pin(HOST, PORT, Collections.emptySet()).get().fingerprint().equals(fresh),
                    "the pin was replaced after SERVER_SWITCH");
            check(rig.awaitCondition(() -> !V3Notices.isEmpty()), "the player is told");
            V3Notices.Notice notice = V3Notices.drain().get(0);
            check("quicraft.notice.rebind".equals(notice.key())
                    && V3Client.shortFingerprint(old).equals(notice.args()[0])
                    && V3Client.shortFingerprint(fresh).equals(notice.args()[1]), "naming both: " + notice);
        }
    }

    /** 12.16: a re-bind waits for OFFER only twice the RTT (500 ms at least), then fails like any /3 failure. */
    private static void aReboundThatGetsNoOfferFailsAfterTheBoundedWait(int protocol, RoutingTable table)
            throws Exception {
        String old = "sha256:" + "22".repeat(32);
        try (Rig rig = new Rig(protocol, table, false, false, old)) {
            rig.login(true, true);
            long start = System.nanoTime();
            rig.client.onConfigurationStarted();
            check(rig.awaitState(V3Client.State.FAILED), "failed, state " + rig.client.state());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(millis >= 450 && millis < 3000, "after the bounded wait, not at the 10 s deadline: " + millis);
            rig.expectFallback("channel binding failed");
            check(rig.files.pins().pin(HOST, PORT, Collections.emptySet()).get().fingerprint().equals(old),
                    "the old pin is kept");
        }
    }

    // --- The rig.

    /** Everything one join needs: connector, plan, fake connection, the real pipeline and the scripted server. */
    private static final class Rig implements AutoCloseable {
        final int protocol;
        final RoutingTable table;
        final NioEventLoopGroup group = new NioEventLoopGroup(1);
        final EventLoop loop = group.next();
        final Path dir;
        final ClientV3State files;
        final Fake fake = new Fake(loop);
        final Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        final ServerIdentity identity;
        final byte[] secret = new byte[16];
        final SecretKey key;
        final ListenerProxy listener = new ListenerProxy();
        final FakePlatform platform;
        final ClientConnector connector;
        final String pinned;
        V3Plan plan;
        V3Client client;
        QuicBridgeChannel channel;
        Server server;

        Rig(int protocol, RoutingTable table, boolean quicOnly, boolean offline) throws Exception {
            this(protocol, table, quicOnly, offline, null);
        }

        Rig(int protocol, RoutingTable table, boolean quicOnly, boolean offline, String pinned) throws Exception {
            V3Notices.clear();
            this.pinned = pinned;
            this.protocol = protocol;
            this.table = table;
            new SecureRandom().nextBytes(secret);
            key = new SecretKeySpec(secret, "AES");
            dir = Files.createTempDirectory("quicraft-cutover");
            files = ClientV3State.open(dir, Clock.systemUTC());
            identity = ServerIdentity.generate();
            platform = new FakePlatform(fake, loop);
            if (pinned != null) {
                files.pins().recordPin(HOST, PORT, pinned, Collections.emptySet());
            }

            AdvertisementCache ads = new AdvertisementCache();
            InetSocketAddress server = new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT);
            Advertisement ad = Advertisement.of(Protocol.ALPN_V3, 25570, identity.fingerprint(),
                    Map.of(protocol, "sha256:" + table.digestHex()));
            ads.record(ClientConnector.key(server), Optional.of(ad));
            connector = new ClientConnector(ads, FailureCache.load(dir.resolve("failures.properties")),
                    new ClientConnector.V3Support(files.pins(), files.rebinds(), files.worker(),
                            System::currentTimeMillis, protocol, true, true));
            ClientConnector.Outcome<Object> outcome = connector.connect(server, HOST, PORT,
                    quicOnly ? Mode.QUIC_ONLY : Mode.AUTO, null, platform).get(10, TimeUnit.SECONDS);
            check(outcome.quic() == fake && outcome.v3Plan() != null, "the connector selected /3 and dialed it");
            plan = outcome.v3Plan();

            channel = new QuicBridgeChannel(fake);
            client = new V3Client(connection, channel, loop, fake, plan, table, protocol, quicOnly, files, HOST, PORT,
                    plan.pinState() == TransportSelection.PinState.CHANGED
                            ? V3Client.oldPin(files, HOST, PORT) : CompletableFuture.completedFuture(null));
            ClientConnect.installVanillaPipeline(channel, connection);
            channel.pipeline().addBefore("packet_handler", V3Handler.NAME, new V3Handler(client));
            Transports.quic(connection, fake);
            Transports.v3(connection, client);
            loop.register(channel).sync();
            // The game would be in Login and then Configuration; the harness starts the pipeline in
            // Configuration, with a recording listener.
            // As after the Handshake: no encoder yet, then the game's own calls (with Fabric API's hooks
            // on them, which add handlers of their own next to the codec handlers).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!connection.isConnected() && System.nanoTime() < deadline) {
                Thread.sleep(5); // channelActive reaches the Connection right after the registration
            }
            channel.pipeline().replace("encoder", "outbound_config", new UnconfiguredPipelineHandler.Outbound());
            connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
            connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, listener.proxy());
            this.server = new Server(this, offline);
        }

        /** What the login mixins do, in vanilla's order: Hello, S, the session join, then the key. */
        void login(boolean encrypted, boolean authenticated) throws Exception {
            if (!encrypted) {
                check(V3Hooks.loginFinished(connection), "an offline-test login reaches the exchange");
                return;
            }
            check(V3Hooks.hello(connection, true), "Hello with authentication continues");
            V3Hooks.secret(connection, key);
            V3Hooks.authenticated(connection, authenticated ? null : Component.literal("session join failed"));
            // Vanilla's own method: the mixin at its end installs the boundary-aware decoder.
            connection.setEncryptionKey(Crypt.getCipher(Cipher.DECRYPT_MODE, key),
                    Crypt.getCipher(Cipher.ENCRYPT_MODE, key));
            if (authenticated) {
                check(channel.pipeline().get("decrypt") instanceof BoundaryCipherDecoder,
                        "the mixin swapped vanilla's decryptor for the boundary decoder");
                check(V3Hooks.loginFinished(connection), "an authenticated login continues");
            }
        }

        /** Plays the exchange until the client is settled; the server's bytes arrive in pieces of {@code chunk}. */
        void run(int chunk) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                deliverServerChunks(chunk);
                byte[] fromClient = fake.takeWritten();
                if (fromClient.length > 0) {
                    server.onClientBytes(fromClient);
                }
                V3Client.State state = client.state();
                if (state == V3Client.State.ACTIVE || state == V3Client.State.FAILED) {
                    deliverServerChunks(chunk);
                    return;
                }
                Thread.sleep(5);
            }
        }

        void deliverServerChunks(int chunk) {
            byte[] bytes = server.takeOutbound();
            for (int offset = 0; offset < bytes.length; offset += chunk) {
                fake.inject(Arrays.copyOfRange(bytes, offset, (int) Math.min((long) bytes.length, (long) offset + chunk)));
            }
        }

        /** The recorded activation failure makes the next join to this server use TCP (12.14). */
        void expectNextJoinUsesTcp() throws Exception {
            files.worker().submit(() -> { }).get(10, TimeUnit.SECONDS); // the plan's writes are queued before this
            FakePlatform tcp = new FakePlatform(fake, loop);
            tcp.tcpWins = true;
            ClientConnector.Outcome<Object> next = connector.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), HOST, PORT, Mode.AUTO, null, tcp)
                    .get(10, TimeUnit.SECONDS);
            check(next.tcp() != null && next.quic() == null, "the next join uses TCP");
        }

        /** One delivery as the lanes fire it: a game packet, then the acknowledgement marker (on the loop). */
        void deliver(long sequence, int ignored) throws Exception {
            loop.submit(() -> {
                io.netty.channel.ChannelHandlerContext before = channel.pipeline().context("hackfix");
                before.fireChannelRead(new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(sequence));
                try {
                    java.lang.reflect.Constructor<rs.sudoe.quicraft.bridge.GameLanes.Ack> ctor =
                            rs.sudoe.quicraft.bridge.GameLanes.Ack.class.getDeclaredConstructor(
                                    rs.sudoe.quicraft.core.routing.Lane.class, long.class, boolean.class);
                    ctor.setAccessible(true);
                    before.fireChannelRead(ctor.newInstance(rs.sudoe.quicraft.core.routing.Lane.CONTROL, sequence,
                            false));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }).get(10, TimeUnit.SECONDS);
        }

        boolean awaitState(V3Client.State wanted) throws Exception {
            return awaitCondition(() -> client.state() == wanted);
        }

        boolean awaitCondition(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(5);
            }
            return condition.getAsBoolean();
        }

        /** The user's decision: 0x10, a fresh TCP join, the exact wording, the failure recorded, no pin. */
        void expectFallback(String reason) throws Exception {
            check(awaitCondition(() -> fake.abortCode == 0x10), "QUIC closed with 0x10: " + fake.abortCode);
            check(Transports.takeRetryOverTcp(connection), "a fresh TCP join is requested");
            List<V3Notices.Notice> notices = V3Notices.drain();
            check(notices.size() == 1 && ("QUIC multi-stream failed (" + reason + "), fell back to TCP")
                    .equals(notices.get(0).literal()), "the player is told, in the user's words: " + notices);
            check(plan.isFailed(), "the plan recorded the failure");
            check(client.failureText() == null, "no quic-only failure text");
            check(awaitCondition(() -> files.pins().pin(HOST, PORT, Collections.emptySet())
                            .map(p -> p.fingerprint().equals(pinned)).orElse(pinned == null)),
                    "a failed attempt writes no pin and keeps the old one");
        }

        @Override
        public void close() throws Exception {
            fake.closeByPeer();
            channel.close();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            files.close();
            server.close();
        }
    }

    // --- The scripted server.

    private static final class Server {
        final Rig rig;
        final boolean offline;
        final EmbeddedChannel pipe = new EmbeddedChannel();
        final LaneCodec codec;
        final Cipher encrypt;
        final Cipher decrypt;
        final byte[] fingerprint;
        BindingSession session;
        final ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        final ByteArrayOutputStream inbound = new ByteArrayOutputStream();
        /** The §13 record bytes the server sends right behind SERVER_SWITCH, unencrypted (12.12). */
        byte[] recordsAfterSwitch;
        boolean switched;

        Server(Rig rig, boolean offline) throws Exception {
            this.rig = rig;
            this.offline = offline;
            net.minecraft.network.Connection.configureSerialization(pipe.pipeline(), PacketFlow.SERVERBOUND, false, null);
            pipe.pipeline().replace("decoder", "decoder", new PacketDecoder<>(ConfigurationProtocols.SERVERBOUND));
            pipe.pipeline().replace("outbound_config", "encoder", new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
            // The scripted server takes part in the exchange, so binding payloads decode for it too.
            pipe.pipeline().addLast("binding_gate", new BindingGate());
            codec = new LaneCodec(pipe, rig.table);
            encrypt = offline ? null : Crypt.getCipher(Cipher.ENCRYPT_MODE, rig.key);
            decrypt = offline ? null : Crypt.getCipher(Cipher.DECRYPT_MODE, rig.key);
            fingerprint = rig.plan.fingerprintBytes();
        }

        void start() {
            session = new BindingSession(BindingStateMachine.Role.SERVER, rig.fake::exporterValue,
                    offline ? null : rig.secret, offline ? BindingStateMachine.MODE_OFFLINE : BindingStateMachine.MODE_ONLINE,
                    rig.protocol, fingerprint, rig.plan.routingDigest(), System::nanoTime, 0, new BindingSessionCallbacks(),
                    null, null);
            session.onConfigurationEntered(System.nanoTime());
        }

        /** One clientbound packet as a complete Minecraft frame, in the server's current protocol. */
        byte[] frame(Packet<?> packet) {
            codec.routeOf(packet);
            ByteBuf out = Unpooled.buffer();
            codec.encode(packet, out);
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            out.release();
            return bytes;
        }

        byte[] encrypted(byte[] plain) {
            return offline ? plain : encrypt.update(plain);
        }

        synchronized byte[] takeOutbound() {
            byte[] bytes = outbound.toByteArray();
            outbound.reset();
            return bytes;
        }

        /** The client's bytes: decrypted, split into frames, decoded as serverbound packets. */
        synchronized void onClientBytes(byte[] bytes) throws Exception {
            inbound.write(offline ? bytes : decrypt.update(bytes));
            byte[] all = inbound.toByteArray();
            int offset = 0;
            while (true) {
                int[] length = varInt(all, offset);
                if (length == null || offset + length[1] + length[0] > all.length) {
                    break;
                }
                ByteBuf frame = Unpooled.wrappedBuffer(all, offset, length[1] + length[0]);
                offset += length[1] + length[0];
                LaneGame.Decoded decoded = codec.decode(frame, null, (retained, total) -> { });
                if (decoded.packet() instanceof ServerboundCustomPayloadPacket p
                        && p.payload() instanceof rs.sudoe.quicraft.fabric.net.BindingPayload b) {
                    session.onPayload(b.data());
                }
            }
            inbound.reset();
            inbound.write(all, offset, all.length - offset);
        }

        /** {length, bytesUsed} of the VarInt at {@code offset}, or null if it is incomplete. */
        static int[] varInt(byte[] data, int offset) {
            int value = 0;
            for (int i = 0; i < 3; i++) {
                if (offset + i >= data.length) {
                    return null;
                }
                byte b = data[offset + i];
                value |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return new int[] {value, i + 1};
                }
            }
            return null;
        }

        void close() {
            pipe.finishAndReleaseAll();
        }

        private final class BindingSessionCallbacks implements rs.sudoe.quicraft.core.binding.BindingDriver.Callbacks {
            @Override
            public void send(byte[] payload) {
                byte[] frame = frame(new ClientboundCustomPayloadPacket(
                        new rs.sudoe.quicraft.fabric.net.BindingPayload(payload)));
                synchronized (Server.this) {
                    byte[] wire = encrypted(frame);
                    outbound.write(wire, 0, wire.length);
                }
            }

            @Override
            public void activateInbound() {
            }

            @Override
            public void activateOutbound() {
                // 12.12: SERVER_SWITCH was the last encrypted packet; records follow, unencrypted.
                // CONTROL record 1: a keep-alive in configuration, as the next thing a server would send.
                byte[] keepAlive = frame(new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(99L));
                recordsAfterSwitch = RecordCodec.encodeGame(1L, 0L, 0L, 0L, keepAlive);
                synchronized (Server.this) {
                    outbound.write(recordsAfterSwitch, 0, recordsAfterSwitch.length);
                    switched = true;
                }
            }

            @Override
            public void fail(int code, String reason) {
                throw new AssertionError("the scripted server's own session failed: " + reason);
            }
        }
    }

    /** Stands in for a /3 server pipeline: a binding payload is part of its exchange. */
    private static final class BindingGate extends io.netty.channel.ChannelInboundHandlerAdapter
            implements DecodeContext.Gate {
        @Override
        public boolean expectsBinding() {
            return true;
        }
    }

    // --- Fakes.

    /** A {@link V3Connection} with no QUIC behind it: what the client writes is captured, what the test injects is read. */
    private static final class Fake implements V3Connection {
        final EventLoop loop;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final byte[] exporter = new byte[32];
        volatile Listener listener;
        volatile boolean activated;
        volatile byte[] activationLeftover;
        volatile int abortCode = -1;
        volatile boolean open = true;
        /** Like core's connection after detachForActivation: later stream data is kept for activate(). */
        boolean detached;
        final ByteArrayOutputStream kept = new ByteArrayOutputStream();

        Fake(EventLoop loop) {
            this.loop = loop;
            Arrays.fill(exporter, (byte) 0x5A);
        }

        void inject(byte[] bytes) {
            loop.execute(() -> {
                if (detached) {
                    kept.write(bytes, 0, bytes.length);
                    return;
                }
                Listener l = listener;
                if (l != null) {
                    l.onData(ByteBuffer.wrap(bytes));
                }
            });
        }

        void closeByPeer() {
            loop.execute(() -> {
                Listener l = listener;
                open = false;
                if (l != null) {
                    l.onClosed(null);
                }
            });
        }

        synchronized byte[] takeWritten() {
            byte[] bytes = written.toByteArray();
            written.reset();
            return bytes;
        }

        @Override
        public byte[] exporterValue() {
            return exporter.clone();
        }

        @Override
        public long smoothedRttMillis() {
            return 20;
        }

        @Override
        public void detachForActivation(java.util.function.Consumer<ByteBuffer> leftover) {
            detached = true; // event loop
        }

        @Override
        public void activate(LaneGame game, ByteBuffer controlLeftover) {
            activated = true;
            // Core hands the session the application's leftover followed by what it kept since the detach.
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            if (controlLeftover != null) {
                byte[] copy = new byte[controlLeftover.remaining()];
                controlLeftover.duplicate().get(copy);
                all.write(copy, 0, copy.length);
            }
            byte[] later = kept.toByteArray();
            all.write(later, 0, later.length);
            activationLeftover = all.toByteArray();
        }

        @Override
        public void activate(LaneGame game, int protocolNumber, ByteBuffer controlLeftover) {
            activate(game, controlLeftover);
        }

        @Override
        public LaneSession session() {
            return null;
        }

        @Override
        public void end() {
        }

        @Override
        public void abort(int code) {
            abortCode = code;
            closeByPeer();
        }

        @Override
        public String alpn() {
            return Protocol.ALPN_V3;
        }

        @Override
        public boolean handshakeComplete() {
            return true;
        }

        @Override
        public InetSocketAddress remoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25570);
        }

        @Override
        public InetSocketAddress localAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000);
        }

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public synchronized void write(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            written.write(bytes, 0, bytes.length);
        }

        @Override
        public void flush() {
        }

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public void setAutoRead(boolean autoRead) {
        }

        @Override
        public void read() {
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            closeByPeer();
        }

        @Override
        public CompletableFuture<Map<String, Long>> connectionStats() {
            return CompletableFuture.completedFuture(Map.of());
        }

        @Override
        public void execute(Runnable task) {
            loop.execute(task);
        }

        @Override
        public boolean inEventLoop() {
            return loop.inEventLoop();
        }
    }

    private static final class FakePlatform implements ClientConnector.Platform<Object> {
        final Fake fake;
        final EventLoop loop;
        volatile boolean bypassResumption;
        volatile boolean tcpWins;

        FakePlatform(Fake fake, EventLoop loop) {
            this.fake = fake;
            this.loop = loop;
        }

        @Override
        public CompletableFuture<Optional<Advertisement>> queryStatus(InetSocketAddress server) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target, Fingerprint fingerprint,
                EarlyFlight early) {
            CompletableFuture<QuicByteStream> failed = new CompletableFuture<>();
            failed.completeExceptionally(new UnsupportedOperationException("/2"));
            return failed;
        }

        @Override
        public CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target, Fingerprint fingerprint,
                EarlyFlight early, TransportSelection.Transport alpn, boolean bypassResumption) {
            this.bypassResumption = bypassResumption;
            return CompletableFuture.completedFuture(fake);
        }

        @Override
        public ConnectionRace.Tcp<Object> tcp() {
            return new ConnectionRace.Tcp<Object>() {
                @Override
                public CompletableFuture<Object> connect() {
                    return tcpWins ? CompletableFuture.completedFuture((Object) "tcp")
                            : new CompletableFuture<>(); // otherwise never completes: QUIC wins
                }

                @Override
                public void close(Object connection) {
                }
            };
        }

        @Override
        public ScheduledExecutorService scheduler() {
            return loop;
        }
    }

    /** A configuration listener that records disconnects and ignores everything else. */
    private static final class ListenerProxy {
        volatile int disconnects;

        ClientConfigurationPacketListener proxy() {
            return (ClientConfigurationPacketListener) java.lang.reflect.Proxy.newProxyInstance(
                    ClientConfigurationPacketListener.class.getClassLoader(),
                    new Class<?>[] {ClientConfigurationPacketListener.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "flow":
                                return PacketFlow.CLIENTBOUND;
                            case "protocol":
                                return ConnectionProtocol.CONFIGURATION;
                            case "shouldHandleMessage":
                            case "isAcceptingMessages":
                                return Boolean.TRUE;
                            case "handleDisconnect":
                                disconnects++;
                                return null;
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            case "toString":
                                return "ListenerProxy";
                            default:
                                Class<?> type = method.getReturnType();
                                if (type == boolean.class) {
                                    return Boolean.FALSE;
                                }
                                if (type == int.class || type == long.class) {
                                    return 0;
                                }
                                return null;
                        }
                    });
        }
    }
}
