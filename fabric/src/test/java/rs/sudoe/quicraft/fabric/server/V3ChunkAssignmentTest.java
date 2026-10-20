// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.multistream.NStreamSession;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.multistream.SessionContext;
import rs.sudoe.quicraft.core.multistream.SessionResources;
import rs.sudoe.quicraft.core.multistream.SessionSendDriver;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.fabric.net.PacketLaneCodec;
import rs.sudoe.quicraft.shaded.io.netty.handler.codec.quic.QuicChannel;
import rs.sudoe.quicraft.shaded.io.netty.handler.codec.quic.QuicStreamChannel;
import rs.sudoe.quicraft.shaded.io.netty.handler.codec.quic.QuicStreamType;

/** Real V3 admission and shaded production session; only the QUIC I/O endpoints are substituted. */
class V3ChunkAssignmentTest {
    @Test
    void uncoveredChunkInstallWithProductionExtractorIsAssignedWithoutBatchMetadata() throws Exception {
        String json;
        try (java.io.InputStream input = RoutingTable.class.getResourceAsStream("protocol-775.json")) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        json = json.replace("\"extractors\":{", "\"extractors\":{\"chunk_xz\":\"mc775.chunk_xz.v1\",")
                .replace("\"direction\":\"clientbound\",\"packet_id\":45,\"lane\":\"control\",\"dependency\":\"global\",\"position\":null",
                        "\"direction\":\"clientbound\",\"packet_id\":45,\"lane\":\"chunk\",\"dependency\":\"lane\",\"position\":\"chunk_xz\"");
        RoutingTable table = RoutingTableLoader.parse(json);
        assertEquals(rs.sudoe.quicraft.core.routing.RouteV2.StreamLane.CHUNK, table.routeV2For(
                new rs.sudoe.quicraft.core.routing.RouteKey(775, ProtocolState.PLAY,
                        rs.sudoe.quicraft.core.routing.Direction.CLIENTBOUND, 45)).lane);
        var wire = new rs.sudoe.quicraft.shaded.io.netty.channel.embedded.EmbeddedChannel();
        QuicChannel quic = (QuicChannel) Proxy.newProxyInstance(QuicChannel.class.getClassLoader(),
                new Class<?>[] {QuicChannel.class}, (p, m, a) -> switch (m.getName()) {
                    case "eventLoop" -> wire.eventLoop();
                    case "pipeline" -> wire.pipeline();
                    default -> null;
                });
        QuicStreamChannel control = (QuicStreamChannel) Proxy.newProxyInstance(
                QuicStreamChannel.class.getClassLoader(), new Class<?>[] {QuicStreamChannel.class},
                (p, m, a) -> switch (m.getName()) {
                    case "streamId" -> 0L;
                    case "type" -> QuicStreamType.BIDIRECTIONAL;
                    default -> null;
                });
        NStreamSession session = new NStreamSession(quic, NStreamSession.Role.SERVER, control, table,
                null, SessionContext.of(2), SessionResources.production(null), false);
        QuicBridgeChannel bridge = new QuicBridgeChannel((QuicByteStream) Proxy.newProxyInstance(
                QuicByteStream.class.getClassLoader(), new Class<?>[] {QuicByteStream.class}, (p, m, a) -> null));
        V3Connection transport = (V3Connection) Proxy.newProxyInstance(V3Connection.class.getClassLoader(),
                new Class<?>[] {V3Connection.class}, (p, m, a) -> m.getName().equals("session") ? session : null);
        CausalSendContext context = new CausalSendContext(new Object());
        CausalSendContext.attach(bridge, context);
        context.bindTransport(transport);
        context.bindSession(transport, session);
        Constructor<V3Join> constructor = V3Join.class.getDeclaredConstructor(QuicBridgeChannel.class,
                V3Connection.class, net.minecraft.server.MinecraftServer.class, byte[].class, ReceiveBudget.class);
        constructor.setAccessible(true);
        V3Join join = constructor.newInstance(bridge, transport, null, new byte[32], ReceiveBudget.unlimited());
        set(join, "causal", context);
        var game = new io.netty.channel.embedded.EmbeddedChannel();
        set(join, "codec", new PacketLaneCodec(game.pipeline()));
        OutboundUnit unit = new OutboundUnit(false, context.newEnvelope(null,
                CausalSendEnvelope.ProducerSnapshot.EMPTY, CausalSendEnvelope.BundleMetadata.NONE));
        unit.add(ProtocolState.PLAY, ByteBuffer.allocate(9).put((byte) 45).putInt(-7).putInt(8).array());
        Method submit = V3Join.class.getDeclaredMethod("submitUnitNow", OutboundUnit.class);
        submit.setAccessible(true);
        try {
            submit.invoke(join, unit);
            Field driverField = NStreamSession.class.getDeclaredField("sendDriver");
            driverField.setAccessible(true);
            SessionSendDriver driver = (SessionSendDriver) driverField.get(session);
            assertEquals(1, driver.queuedRecords());
            assertFalse(driver.isClosed());
            SessionSendDriver.Write write = driver.poll(stream -> true);
            assertEquals(2 + rs.sudoe.quicraft.core.multistream.ShardHash.shardOf(-7, 8, 0, 2), write.stream());
            var record = rs.sudoe.quicraft.core.multistream.RecordCodec.decodeRecord(write.bytes(), write.stream());
            assertEquals(1, record.sequence());
            assertTrue(record.dependencies().isEmpty(), "an uncovered install needs no invented batch frontier");
            driver.released(write.token());
        } finally {
            Method release = NStreamSession.class.getDeclaredMethod("release");
            release.setAccessible(true);
            release.invoke(session);
            wire.finishAndReleaseAll();
            game.finishAndReleaseAll();
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
