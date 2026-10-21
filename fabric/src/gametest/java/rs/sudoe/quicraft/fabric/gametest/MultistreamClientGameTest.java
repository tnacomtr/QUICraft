// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.AckPacket;
import rs.sudoe.quicraft.fabric.client.LaneCodec;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;

/**
 * The client side of {@code quicraft/3} that needs the real game but no multi-stream server:
 * the setting (off by default, toggled from the settings screen), every hook the join relies on
 * actually applied, the game's own codec handlers driven one frame at a time by {@link LaneCodec},
 * and an ordinary {@code /2} join still working with multistream on. The full {@code /3} join comes
 * with the server side.
 */
public class MultistreamClientGameTest implements FabricClientGameTest {
    private static final int PROTOCOL = 4242;
    private static final RoutingTable TABLE = RoutingTableLoader.parse(
            "{\"schema\":1,\"protocol\":4242,\"default\":\"control\",\"routes\":[]}");

    @Override
    public void runTest(ClientGameTestContext context) {
        Hooks.setFaultInjection(false);
        QuicraftClient client = QuicraftClient.get();
        check(!client.multistream(), "12.1: multistream is off by default");
        settingToggle(context, client);
        hooksApplied();
        codecRoundTrip(false);
        codecRoundTrip(true);
        try {
            terminalPacketsSwapTheCodecHandlers();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        aWrongSizedFrameIsRefused();
        v2JoinStillWorksWithMultistreamOn(context, client);
        aFaultInAnyClientHookNeverStopsAJoin(context, client);
    }

    /** The settings screen's multistream button flips the setting and saves it. */
    private static void settingToggle(ClientGameTestContext context, QuicraftClient client) {
        client.setMode(Mode.AUTO);
        context.setScreen(() -> new JoinMultiplayerScreen(new TitleScreen()));
        context.waitForScreen(JoinMultiplayerScreen.class);
        context.clickScreenButton("quicraft.settings.button");
        context.waitForScreen(rs.sudoe.quicraft.fabric.client.QuicraftSettingsScreen.class);
        context.clickScreenButton("quicraft.settings.multistream");
        check(client.multistream(), "the button turns multistream on");
        context.clickScreenButton("quicraft.settings.multistream");
        check(!client.multistream(), "and off again");
        context.clickScreenButton("gui.done");
        context.waitForScreen(JoinMultiplayerScreen.class);
        context.setScreen(() -> null);
    }

    /**
     * Mixin renames an injected handler to {@code handler$<hash>$quicraft$<name>}; a hook that did
     * not apply (the injectors are not required, so a missing target would be silent) is not there.
     */
    private static void hooksApplied() {
        has(ClientHandshakePacketListenerImpl.class, "quicraft$hello");
        has(ClientHandshakePacketListenerImpl.class, "quicraft$secret");
        has(ClientHandshakePacketListenerImpl.class, "quicraft$authenticated");
        has(ClientHandshakePacketListenerImpl.class, "quicraft$loginFinished");
        has(ClientHandshakePacketListenerImpl.class, "quicraft$reportFallback");
        has(ClientCommonPacketListenerImpl.class, "quicraft$retryOverTcp");
        has(Connection.class, "quicraft$boundary");
        has(net.minecraft.client.multiplayer.ClientPacketListener.class, "quicraft$joined");
        check(Arrays.asList(PacketDecoder.class.getInterfaces()).stream()
                .anyMatch(i -> i.getSimpleName().equals("PacketDecoderAccessor")), "PacketDecoder accessor applied");
        check(Arrays.asList(PacketEncoder.class.getInterfaces()).stream()
                .anyMatch(i -> i.getSimpleName().equals("PacketEncoderAccessor")), "PacketEncoder accessor applied");
        check(Arrays.asList(CompressionDecoder.class.getInterfaces()).stream()
                .anyMatch(i -> i.getSimpleName().equals("CompressionDecoderAccessor")), "decompress accessor applied");
        check(Arrays.asList(CompressionEncoder.class.getInterfaces()).stream()
                .anyMatch(i -> i.getSimpleName().equals("CompressionEncoderAccessor")), "compress accessor applied");
    }

    private static void has(Class<?> target, String name) {
        boolean found = false;
        for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
            if (method.getName().contains(name)) {
                found = true;
                break;
            }
        }
        check(found, target.getSimpleName() + " has the injected " + name);
    }

    /** A client pipeline in configuration, exactly as vanilla builds it. */
    private static EmbeddedChannel clientPipeline(int compressionThreshold) {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();
        Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false, null);
        pipeline.replace("inbound_config", "decoder", new PacketDecoder<>(ConfigurationProtocols.CLIENTBOUND));
        pipeline.replace("encoder", "encoder", new PacketEncoder<>(ConfigurationProtocols.SERVERBOUND));
        compression(pipeline, compressionThreshold);
        return channel;
    }

    /** The server's end: clientbound packets are encoded, serverbound ones decoded. */
    private static EmbeddedChannel serverPipeline(int compressionThreshold) {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();
        Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);
        pipeline.replace("decoder", "decoder", new PacketDecoder<>(ConfigurationProtocols.SERVERBOUND));
        pipeline.replace("outbound_config", "encoder", new PacketEncoder<>(ConfigurationProtocols.CLIENTBOUND));
        compression(pipeline, compressionThreshold);
        return channel;
    }

    private static void compression(ChannelPipeline pipeline, int threshold) {
        if (threshold >= 0) {
            pipeline.addAfter("splitter", "decompress", new CompressionDecoder(threshold, false));
            pipeline.addAfter("prepender", "compress", new CompressionEncoder(threshold));
        }
    }

    /** What the lane codec's receive budget was asked to reserve. */
    private static final class Reservations implements LaneGame.Reservation {
        final List<Long> retained = new ArrayList<>();
        final List<Integer> uncompressed = new ArrayList<>();

        @Override
        public void reserve(long additionalRetainedBytes, int totalUncompressedBytes) {
            retained.add(additionalRetainedBytes);
            uncompressed.add(totalUncompressedBytes);
        }
    }

    /**
     * A frame the server's vanilla encoder produced decodes in the client's vanilla decoder through
     * {@link LaneCodec}, and the reverse; with compression off, a packet under the threshold, and a
     * packet over it (the inflate size is charged to the budget first, 13.12).
     */
    private static void codecRoundTrip(boolean compressed) {
        int threshold = compressed ? 64 : -1;
        EmbeddedChannel client = clientPipeline(threshold);
        EmbeddedChannel server = serverPipeline(threshold);
        try {
            LaneCodec clientCodec = new LaneCodec(client, TABLE);
            LaneCodec serverCodec = new LaneCodec(server, TABLE);

            // Server to client: a small packet, then (compressed) a large one.
            Reservations reservations = new Reservations();
            ClientboundKeepAlivePacket small = new ClientboundKeepAlivePacket(0x1234_5678_9abcL);
            LaneGame.Decoded decoded = clientCodec.decode(frameOf(serverCodec, small), ProtocolState.CONFIGURATION,
                    reservations);
            check(decoded.packet() instanceof ClientboundKeepAlivePacket k && k.getId() == 0x1234_5678_9abcL,
                    "keep-alive decoded: " + decoded.packet());
            check(decoded.state() == ProtocolState.CONFIGURATION, "decoded in configuration: " + decoded.state());
            check(decoded.bundle() == Envelope.Bundle.NONE && !decoded.stateChange(), "an ordinary packet");
            // Two charges, both before decoding: the decode storage, then the bounded routing prefix (13.12).
            check(reservations.retained.size() == 2 && reservations.retained.get(1) <= 64
                    && reservations.retained.get(1) == decoded.retainedBytes(),
                    "the budget was charged before decoding: " + reservations.retained);

            if (compressed) {
                String big = "x".repeat(2000);
                ByteBuf frame = frameOf(serverCodec, new ClientboundDisconnectPacket(Component.literal(big)));
                int frameLength = frame.readableBytes();
                Reservations bigReservation = new Reservations();
                LaneGame.Decoded bigDecoded = clientCodec.decode(frame, ProtocolState.CONFIGURATION, bigReservation);
                check(bigDecoded.packet() instanceof ClientboundDisconnectPacket d
                        && d.reason().getString().equals(big), "the inflated packet decoded");
                check(bigReservation.uncompressed.get(0) > 2000 && frameLength < 500,
                        "charged the inflate size, not the wire size: " + bigReservation.uncompressed + " vs "
                                + frameLength);
            }

            // Client to server: the route and the frame, decoded by the server's vanilla decoder.
            ServerboundKeepAlivePacket up = new ServerboundKeepAlivePacket(42L);
            rs.sudoe.quicraft.bridge.GameLanes.Route route = clientCodec.routeOf(up);
            check(route.state() == ProtocolState.CONFIGURATION, "sent in configuration: " + route.state());
            check(route.lane() == Lane.CONTROL, "everything defaults to CONTROL (13.6)");
            ByteBuf out = Unpooled.buffer();
            clientCodec.encode(up, out);
            LaneGame.Decoded received = serverCodec.decode(out, ProtocolState.CONFIGURATION, new Reservations());
            check(received.packet() instanceof ServerboundKeepAlivePacket k && k.getId() == 42L,
                    "the server's vanilla decoder read our frame: " + received.packet());
            check(received.packetId() == route.packetId(), "the id the route announced is the id on the wire");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    /** Encodes a clientbound packet with the server pipeline's vanilla handlers. */
    private static ByteBuf frameOf(LaneCodec serverCodec, net.minecraft.network.protocol.Packet<?> packet) {
        serverCodec.routeOf(packet);
        ByteBuf out = Unpooled.buffer();
        serverCodec.encode(packet, out);
        return out;
    }

    /** A terminal packet makes vanilla swap its handler out; decoding and encoding through the codec does the same. */
    private static void terminalPacketsSwapTheCodecHandlers() throws Exception {
        EmbeddedChannel client = clientPipeline(-1);
        EmbeddedChannel server = serverPipeline(-1);
        try {
            LaneCodec clientCodec = new LaneCodec(client, TABLE);
            LaneCodec serverCodec = new LaneCodec(server, TABLE);

            ByteBuf finish = frameOf(serverCodec, ClientboundFinishConfigurationPacket.INSTANCE);
            LaneGame.Decoded decoded = clientCodec.decode(finish, ProtocolState.CONFIGURATION, new Reservations());
            check(decoded.stateChange(), "13.5: Finish Configuration is a state change");
            check(client.pipeline().get("decoder") == null && client.pipeline().get("inbound_config") != null,
                    "the decoder was swapped for inbound_config, as vanilla does");

            clientCodec.routeOf(ServerboundFinishConfigurationPacket.INSTANCE);
            check(client.pipeline().get("encoder") == null && client.pipeline().get("outbound_config") != null,
                    "the encoder was swapped for outbound_config");
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    private static void aWrongSizedFrameIsRefused() {
        EmbeddedChannel client = clientPipeline(-1);
        try {
            LaneCodec codec = new LaneCodec(client, TABLE);
            ByteBuf frame = Unpooled.buffer();
            VarInt.write(frame, 50); // claims 50 bytes, carries 2
            frame.writeByte(0).writeByte(0);
            boolean refused = false;
            try {
                codec.decode(frame, ProtocolState.CONFIGURATION, new Reservations());
            } catch (Exception expected) {
                refused = true;
            }
            check(refused, "13.4: a body must be exactly one frame");
        } finally {
            client.finishAndReleaseAll();
        }
    }

    /**
     * With multistream on, a server that advertises {@code quicraft/2} is joined exactly as before:
     * over {@code /2} QUIC, no multi-stream state on the connection.
     */
    private static void v2JoinStillWorksWithMultistreamOn(ClientGameTestContext context, QuicraftClient client) {
        client.setMode(Mode.AUTO);
        client.setMultistream(true);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            try (var connection = server.connect()) {
                GameTests.waitForChunks(connection);
                check(GameTests.connectedOverQuic(context), "joined over QUIC with multistream on");
                List<String> lines = GameTests.debugServerLines(context);
                check(lines.contains("QUICraft: QUIC (auto)"), "a /2 connection says QUIC, not /3: " + lines);
                anAcknowledgementRunsOnTheRenderThreadBehindThePackets(context);
            }
        } finally {
            client.setMultistream(false);
        }
    }

    /**
     * CLAUDE.md fault injection, with multistream on: whichever client hook breaks, the join still
     * reaches play (over TCP or {@code /2}, whichever the broken hook leaves).
     */
    private static void aFaultInAnyClientHookNeverStopsAJoin(ClientGameTestContext context, QuicraftClient client) {
        client.setMode(Mode.AUTO);
        client.setMultistream(true);
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            for (String hook : new String[] {"connect", "multistream state", "early inputs", "status query",
                    "quic connect", "connect outcome"}) {
                int port = server.computeOnServer(s -> s.getPort());
                client.connector().advertisements().record(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        Optional.empty());
                try {
                    client.connector().advertisements().record(new InetSocketAddress(InetAddress.getByName("::1"), port),
                            Optional.empty());
                } catch (java.net.UnknownHostException e) {
                    throw new AssertionError(e);
                }
                Hooks.setFaultInjection(hook);
                try (var connection = server.connect()) {
                    GameTests.waitForChunks(connection);
                    check(context.computeOnClient(c -> c.getConnection() != null), "joined with " + hook + " broken");
                    check(Hooks.wasFaultInjected(hook), "fault injection actually fired for " + hook);
                } finally {
                    Hooks.setFaultInjection(false);
                }
            }
        } finally {
            Hooks.setFaultInjection(false);
            client.setMultistream(false);
        }
    }

    /**
     * The lanes acknowledge a delivery through the game's own packet queue (13.10): an
     * {@link AckPacket} scheduled from a network thread runs on the render thread, after the packets
     * queued before it, and the live listener accepts it.
     */
    private static void anAcknowledgementRunsOnTheRenderThreadBehindThePackets(ClientGameTestContext context) {
        Thread render = context.computeOnClient(c -> Thread.currentThread());
        java.util.List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicReference<Thread> ranOn = new java.util.concurrent.atomic.AtomicReference<>();
        context.runOnClient(c -> {
            var listener = c.getConnection().getConnection().getPacketListener();
            check(listener != null, "the play listener exists");
            Thread network = new Thread(() -> {
                var processor = c.packetProcessor();
                processor.scheduleIfPossible(listener, new AckPacket(() -> order.add("first")));
                processor.scheduleIfPossible(listener, new AckPacket(() -> {
                    order.add("second");
                    ranOn.set(Thread.currentThread());
                }));
            });
            network.start();
            try {
                network.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        context.waitTicks(5);
        check(order.equals(java.util.List.of("first", "second")), "run in order: " + order);
        check(ranOn.get() == render, "on the render thread: " + ranOn.get());
    }
}
