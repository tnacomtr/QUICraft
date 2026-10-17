// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LaneSession;
import rs.sudoe.quicraft.core.multistream.RecordCodec;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.core.transport.V3Listener;

/**
 * {@link GameLanes} over a real {@code /3} connection pair, through the production API only: a
 * {@link V3Listener} and an {@link QuicClient#connectV3} dial (docs/protocol.md 13.1, 13.2), a
 * {@link GameLanes} built BEFORE activation and reading its session from the connection as
 * activation creates it, and the activation leftover delivered as the first CONTROL data. Needs
 * QUICraft's patched native, so the class is skipped on an upstream one, like the core {@code /3}
 * tests.
 */
class GameLanesV3LoopbackTest {
    /** Everything defaults to CONTROL, which is what a one-record check needs (13.6). */
    static final RoutingTable TABLE = RoutingTableLoader.parse(tableJson());

    private static String tableJson() {
        String limit = "{\"maxServerboundRecord\":32768,\"maxServerboundRealtimeRecord\":510,"
                + "\"maxDecodedFootprint\":1048576,\"ordinaryReadAllowance\":5619,"
                + "\"decodeFootprintBound\":\"primitive.raw_copies.v1\"}";
        StringBuilder limits = new StringBuilder();
        for (String state : new String[] {"handshaking", "status", "login", "configuration", "play"}) {
            if (limits.length() != 0) limits.append(',');
            limits.append('"').append(state).append("\":").append(limit);
        }
        return "{\"schema\":2,\"protocol\":4242,\"default\":\"global\",\"extractors\":{},"
                + "\"serverboundLimits\":{" + limits + "},\"routes\":[]}";
    }
    static final int PROTOCOL = 4242;
    /** The application had nothing buffered past the switch packet. */
    static final ByteBuffer NO_LEFTOVER = null;

    static ServerIdentity identity;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(QuicSupport.isAvailable() && QuicSupport.hasMaxFlowControlWindows()
                        && QuicSupport.hasKeyingMaterialExport(),
                "/3 needs QUICraft's patched native (12.2, 12.3, 13.13)");
        identity = ServerIdentity.generate();
    }

    @Test
    void theActivationLeftoverArrivesAsTheFirstControlDataThroughTheProductionPath() throws Exception {
        try (Rig rig = new Rig()) {
            // What the application hands to activate: the start of the record stream, which here is
            // one complete CONTROL record (13.2, 13.4). It is the peer direction's first record.
            byte[] frame = frame(7, "spawn");
            rig.activateServer(ByteBuffer.wrap(RecordCodec.encodeRecord(
                    new rs.sudoe.quicraft.core.multistream.Record(0, 1L, 0L,
                            Collections.emptyList(), RecordCodec.KIND_GAME, frame))));
            rig.activateClient(NO_LEFTOVER);

            List<Object> first = rig.serverGame.await(2);
            assertEquals(2, first.size(), "one packet and its acknowledgement: " + first);
            Packet packet = (Packet) first.get(0);
            assertArrayEquals(frame, packet.frame, "the leftover record's body, decoded by the platform");
            GameLanes.Ack ack = (GameLanes.Ack) first.get(1);
            assertEquals(Lane.CONTROL, ack.lane(), "13.6: the leftover is the first CONTROL data");
            assertEquals(1L, ack.sequence());
            // The game applied it, so it acknowledges through the object the session created (13.10).
            rig.serverLanes.applied(ack.lane(), ack.sequence());

            assertNotNull(rig.serverSide.session(), "and it is the session activation created");
            assertFalse(rig.serverLanes.isClosed(), "the connection is still up after the exchange");
            assertFalse(rig.clientLanes.isClosed());
        }
    }

    @Test
    void anOutboundPacketGoesThroughTheSessionToThePeer() throws Exception {
        try (Rig rig = new Rig()) {
            rig.activateServer(NO_LEFTOVER);
            rig.activateClient(NO_LEFTOVER);

            rig.clientLanes.installOutbound();
            rig.clientCodec.routes.put("move", new GameLanes.Route(ProtocolState.PLAY, 8, Lane.CONTROL));
            ChannelFuture write = rig.clientChannel.writeAndFlush("move");
            assertTrue(write.isSuccess(), "the session took the packet");

            List<Object> received = rig.serverGame.await(2);
            assertEquals(2, received.size(), "the peer received the packet and its ack: " + received);
            assertArrayEquals(frame(8, "move"), ((Packet) received.get(0)).frame,
                    "the platform's own encoding reached the peer");
            assertEquals(Lane.CONTROL, ((GameLanes.Ack) received.get(1)).lane());
        }
    }

    /**
     * The production sink passes uncompressed bytes to the N+2 session. A global route needs no
     * extractor, so the peer still gets the encoded frame.
     */
    @Test
    void theUncompressedSendOverloadDelegatesToTheNStreamSession() throws Exception {
        try (Rig rig = new Rig()) {
            rig.activateServer(NO_LEFTOVER);
            rig.activateClient(NO_LEFTOVER);

            Method session = GameLanes.class.getDeclaredMethod("session");
            session.setAccessible(true);
            GameLanes.Session sink = (GameLanes.Session) session.invoke(rig.clientLanes);
            byte[] frame = frame(8, "move");
            sink.send(ProtocolState.PLAY, 8, frame, new byte[] {9, 9, 9});

            List<Object> received = rig.serverGame.await(2);
            assertEquals(2, received.size(), "the peer received the packet and its ack: " + received);
            assertArrayEquals(frame, ((Packet) received.get(0)).frame,
                    "the global route sent the frame without extracting a position");
            assertEquals(Lane.CONTROL, ((GameLanes.Ack) received.get(1)).lane());
        }
    }

    @Test
    void aWrongLanePacketIsRefusedBeforeItReachesTheWireAndThePeerSeesTheOrderingFailure()
            throws Exception {
        try (Rig rig = new Rig()) {
            rig.activateServer(NO_LEFTOVER);
            rig.activateClient(NO_LEFTOVER);
            rig.clientLanes.installOutbound();
            // The game believes WORLD; the routing table answers CONTROL for this id (13.6).
            rig.clientCodec.routes.put("spawn", new GameLanes.Route(ProtocolState.PLAY, 7, Lane.WORLD));

            ChannelFuture write = rig.clientChannel.writeAndFlush("spawn");
            assertFalse(write.isSuccess(), "13.6: the wrong-lane packet is never handed to the session");

            assertEquals(LaneSession.CLOSE_ORDERING, rig.clientGame.awaitClose(10),
                    "the local side fails with 0x11");
            assertEquals(LaneSession.CLOSE_ORDERING, rig.serverGame.awaitClose(10),
                    "and the peer sees the whole connection close with 0x11 (13.14)");
            assertTrue(rig.serverGame.seen.isEmpty(),
                    "13.6: nothing the peer decoded can be the wrong-lane packet: " + rig.serverGame.seen);
        }
    }

    @Test
    void anOverBudgetReservationIsRefusedBeforeTheCodecAllocates() throws Exception {
        try (Rig rig = new Rig()) {
            rig.activateServer(NO_LEFTOVER);
            rig.activateClient(NO_LEFTOVER);
            rig.serverCodec.chargeBeyondBudget = true;

            rig.client.session().send(ProtocolState.PLAY, 7, frame(7, "big"));

            Throwable rejected = awaitRejected(rig.serverCodec);
            assertNotNull(rejected, "13.12: the reservation itself refuses an over-budget packet");
            assertTrue(rejected.getMessage().contains("workspace"), rejected.getMessage());
            assertFalse(rig.serverCodec.allocated.get(),
                    "13.12: the refusal happens before the codec allocates decompression output");
            assertEquals(0x12, rig.serverGame.awaitClose(10),
                    "an execution workspace refusal closes the N+2 session with 0x12");
            assertTrue(rig.serverGame.seen.isEmpty());
        }
    }

    /** One listener, one dial and one {@link GameLanes} per side, all through the production API. */
    static final class Rig implements AutoCloseable {
        final QuicServer server;
        final V3Connection client;
        final V3Connection serverSide;
        final EmbeddedChannel clientChannel = new EmbeddedChannel();
        final EmbeddedChannel serverChannel = new EmbeddedChannel();
        final Codec clientCodec = new Codec();
        final Codec serverCodec = new Codec();
        final Collector clientGame = new Collector();
        final Collector serverGame = new Collector();
        final GameLanes clientLanes;
        final GameLanes serverLanes;

        Rig() throws Exception {
            CompletableFuture<V3Connection> accepted = new CompletableFuture<V3Connection>();
            server = QuicServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), identity,
                    TransportConfig.DEFAULT, new QuicServer.StreamAcceptor() {
                        @Override
                        public void accept(QuicByteStream stream) {
                            accepted.complete((V3Connection) stream);
                        }
                    }, new V3Listener() {
                        @Override
                        public RoutingTable tableFor(int protocolNumber) {
                            return protocolNumber == PROTOCOL ? TABLE : null;
                        }
                    });
            client = QuicClient.connectV3(server.localAddress(), identity.fingerprint(), TABLE, null)
                    .get(10, TimeUnit.SECONDS);
            serverSide = accepted.get(10, TimeUnit.SECONDS);
            clientChannel.pipeline().addLast(clientGame);
            serverChannel.pipeline().addLast(serverGame);
            // Built before activation, with the session read from the connection as activation makes
            // it: there is no construction cycle, and the activation leftover is already delivered
            // through this object.
            clientLanes = new GameLanes(clientChannel, clientCodec, client::session,
                    ProtocolState.CONFIGURATION, null);
            serverLanes = new GameLanes(serverChannel, serverCodec, serverSide::session,
                    ProtocolState.CONFIGURATION, null);
        }

        /** 13.3: the server activates first, so the client's stream 4 never precedes it. */
        void activateServer(ByteBuffer leftover) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!serverSide.handshakeComplete() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(serverSide.handshakeComplete(), "12.7: the server must see its own handshake");
            serverSide.detachForActivation(ignored -> { });
            serverSide.activate(serverLanes, PROTOCOL, leftover);
            awaitSession(serverSide);
        }

        void activateClient(ByteBuffer leftover) throws Exception {
            client.detachForActivation(ignored -> { });
            client.activate(clientLanes, leftover);
            awaitSession(client);
        }

        private static void awaitSession(V3Connection connection) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (connection.session() == null && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertNotNull(connection.session(), "activation created the session");
        }

        @Override
        public void close() {
            server.close();
            clientChannel.finishAndReleaseAll();
            serverChannel.finishAndReleaseAll();
        }
    }

    /** One decoded packet, as the platform's own codec would build it. */
    static final class Packet {
        final byte[] frame;

        Packet(byte[] frame) {
            this.frame = frame;
        }
    }

    /** A platform codec: the test's packet codec, with the knobs the three tests need. */
    static final class Codec implements GameLanes.Codec {
        final Map<Object, GameLanes.Route> routes = new HashMap<>();
        volatile boolean chargeBeyondBudget;
        final AtomicBoolean allocated = new AtomicBoolean();
        final AtomicReference<Throwable> rejected = new AtomicReference<>();

        @Override
        public LaneGame.Decoded decode(ByteBuf frame, ProtocolState state,
                LaneGame.Reservation reservation) {
            if (chargeBeyondBudget) {
                try {
                    // 13.12: charge before allocating. This exceeds the schema-2 table's
                    // execution workspace, so the reservation refuses before output allocation.
                    reservation.reserve(17L * 1024 * 1024, 8 * 1024 * 1024);
                } catch (RuntimeException refused) {
                    rejected.set(refused);
                    throw refused;
                }
            } else {
                reservation.reserve(frame.readableBytes(), frame.readableBytes());
            }
            allocated.set(true);
            byte[] body = new byte[frame.readableBytes()];
            frame.getBytes(frame.readerIndex(), body);
            return new LaneGame.Decoded(new Packet(body), ProtocolState.PLAY, 0, Envelope.Bundle.NONE,
                    false);
        }

        @Override
        public LaneGame.Decoded decodeInflated(ByteBuf content, ProtocolState state,
                LaneGame.Reservation reservation) {
            ByteBuf frame = io.netty.buffer.Unpooled.buffer(content.readableBytes() + 5);
            try {
                int size = content.readableBytes();
                while ((size & ~0x7f) != 0) {
                    frame.writeByte((size & 0x7f) | 0x80);
                    size >>>= 7;
                }
                frame.writeByte(size);
                frame.writeBytes(content, content.readerIndex(), content.readableBytes());
                return decode(frame, state, reservation);
            } finally {
                frame.release();
            }
        }

        @Override
        public void encode(Object packet, ByteBuf out) {
            // A real Minecraft frame: 13.4 lets the receiver validate the inner length against the
            // body, so the test codec writes one the way a game's encoder would.
            out.writeBytes(frame(8, String.valueOf(packet)));
        }

        @Override
        public GameLanes.Route routeOf(Object packet) {
            return routes.get(packet);
        }
    }

    /** Collects what reaches the game channel, and the code the session closed it with. */
    static final class Collector extends ChannelInboundHandlerAdapter {
        final List<Object> seen = Collections.synchronizedList(new ArrayList<Object>());
        final CompletableFuture<Integer> closed = new CompletableFuture<Integer>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            synchronized (seen) {
                seen.add(msg);
                seen.notifyAll();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof GameLanes.LaneClosedException) {
                closed.complete(Integer.valueOf(((GameLanes.LaneClosedException) cause).code()));
            }
        }

        List<Object> await(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            synchronized (seen) {
                while (seen.size() < count && System.nanoTime() < deadline) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(seen, left);
                }
                return new ArrayList<Object>(seen);
            }
        }

        int awaitClose(int seconds) {
            try {
                return closed.get(seconds, TimeUnit.SECONDS).intValue();
            } catch (Exception e) {
                throw new AssertionError("the game channel saw no close cause: " + e);
            }
        }
    }

    /** Waits for the decode that refused to charge the budget (13.12). */
    static Throwable awaitRejected(Codec codec) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (codec.rejected.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return codec.rejected.get();
    }

    /** One Minecraft-style frame: a VarInt length, then the id and the payload. */
    static byte[] frame(int packetId, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value = 1 + bytes.length;
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        out.write(packetId);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }
}
