// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import rs.sudoe.quicraft.core.multistream.AckJoinTracker;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LogicalStream;
import rs.sudoe.quicraft.core.multistream.MultistreamSession;
import rs.sudoe.quicraft.core.multistream.NStreamSession;
import rs.sudoe.quicraft.core.multistream.OutboundPacket;
import rs.sudoe.quicraft.core.multistream.RecordCodec;
import rs.sudoe.quicraft.core.multistream.SentPacket;
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
    /**
     * Everything else defaults to global, which is what a one-record check needs (13.6). Packet 8 is
     * an explicit CONTROL route and packet 9 a REALTIME one, so covered-bundle members have two
     * different scopes to combine (13.15).
     */
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
                + "\"serverboundLimits\":{" + limits + "},\"routes\":["
                + clientboundRoute(8, "control", "control") + ","
                + clientboundRoute(9, "realtime", "lane") + "]}";
    }

    /**
     * One clientbound schema-2 route (13.7). The two routes give the covered-bundle test a CONTROL
     * member and a REALTIME member, so the strongest-member rule has something to promote (13.15).
     */
    private static String clientboundRoute(int packetId, String lane, String dependency) {
        return "{\"state\":\"play\",\"direction\":\"clientbound\",\"packet_id\":" + packetId
                + ",\"lane\":\"" + lane + "\",\"dependency\":\"" + dependency
                + "\",\"position\":null,\"epoch_change\":false}";
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

            assertEquals(NStreamSession.CLOSE_ORDERING, rig.clientGame.awaitClose(10),
                    "the local side fails with 0x11");
            assertEquals(NStreamSession.CLOSE_ORDERING, rig.serverGame.awaitClose(10),
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

    /**
     * The causal facade over the production session: a scope and its update are the session's own
     * handles, an unresolved scope cannot complete, another connection's handles are rejected, and
     * an update resolves exactly once (U1/U2, 13.19).
     */
    @Test
    void theCausalScopeFacadeTracksOwnershipAndResolutionThroughTheProductionSession()
            throws Exception {
        try (Rig rig = new Rig()) {
            rig.activateServer(NO_LEFTOVER);
            rig.activateClient(NO_LEFTOVER);

            MultistreamSession.CausalScope scope = rig.clientLanes.openScope();
            assertNotNull(scope, "the session allocated the scope");
            MultistreamSession.UpdateToken token = rig.clientLanes.registerUpdate(scope);
            assertThrows(IllegalArgumentException.class, () -> rig.clientLanes.completeScope(scope),
                    "13.19: a scope with an unresolved update cannot complete");

            assertThrows(AckJoinTracker.ForeignHandleException.class,
                    () -> rig.serverLanes.registerUpdate(scope),
                    "13.19: a scope handle belongs to the session that issued it");
            assertThrows(AckJoinTracker.ForeignHandleException.class,
                    () -> rig.serverLanes.sendCovered(token, ProtocolState.PLAY, 8,
                            frame(8, "foreign"), null),
                    "13.19: a token handle belongs to the session that issued it");

            byte[] frame = frame(8, "covered");
            SentPacket handle = rig.clientLanes.sendCovered(token, ProtocolState.PLAY, 8, frame, null);
            assertTrue(handle.isAssigned(), "the covered packet took a sequence");
            assertEquals(Lane.CONTROL, handle.lane(), "the default route is a CONTROL fence");
            rig.clientLanes.completeScope(scope);

            List<Object> received = rig.serverGame.await(2);
            assertEquals(2, received.size(), "the covered packet and its acknowledgement: " + received);
            assertArrayEquals(frame, ((Packet) received.get(0)).frame,
                    "the covered packet reached the peer");
            assertEquals(Lane.CONTROL, ((GameLanes.Ack) received.get(1)).lane());

            assertThrows(IllegalArgumentException.class,
                    () -> rig.clientLanes.sendCovered(token, ProtocolState.PLAY, 8, frame, null),
                    "13.19: an assigned token is consumed and cannot resolve again");

            MultistreamSession.CausalScope other = rig.clientLanes.openScope();
            MultistreamSession.UpdateToken suppressed = rig.clientLanes.registerUpdate(other);
            rig.clientLanes.suppressUpdate(suppressed);
            assertThrows(IllegalArgumentException.class,
                    () -> rig.clientLanes.sendCovered(suppressed, ProtocolState.PLAY, 8, frame, null),
                    "13.19: a suppressed update has no assignment");
            rig.clientLanes.completeScope(other);
        }
    }

    /**
     * A covered bundle from the production facade is one atomic session transaction (U2b, 13.19).
     * Its members are a CONTROL route and a REALTIME route; the strongest-member rule promotes the
     * run to GLOBAL, so the LANE member never rides its own stream and the peer receives the whole
     * run as one delivery whose acknowledgement names the closer (13.15).
     */
    @Test
    void aCoveredBundleIsOneAtomicPromotedDeliveryThroughTheProductionFacade() throws Exception {
        try (Rig rig = new Rig()) {
            rig.activateServer(NO_LEFTOVER);
            rig.activateClient(NO_LEFTOVER);
            // The client's codec is the platform's receiver: a delimiter packet opens and closes a
            // bundle, and ordinary packets between them are members (13.15).
            rig.clientCodec.bundleOpenId = 70;
            rig.clientCodec.bundleCloseId = 72;

            MultistreamSession.CausalScope controlScope = rig.serverLanes.openScope();
            MultistreamSession.UpdateToken controlToken = rig.serverLanes.registerUpdate(controlScope);
            MultistreamSession.CausalScope realtimeScope = rig.serverLanes.openScope();
            MultistreamSession.UpdateToken realtimeToken = rig.serverLanes.registerUpdate(realtimeScope);

            List<OutboundPacket> members = new ArrayList<OutboundPacket>();
            members.add(new OutboundPacket(frame(8, "control member")));
            members.add(new OutboundPacket(frame(9, "realtime member")));
            List<List<MultistreamSession.UpdateToken>> coverage =
                    new ArrayList<List<MultistreamSession.UpdateToken>>();
            coverage.add(Collections.singletonList(controlToken));
            coverage.add(Collections.singletonList(realtimeToken));

            List<SentPacket> handles = rig.serverLanes.sendCoveredBundle(
                    new OutboundPacket(frame(70, "")), members,
                    new OutboundPacket(frame(72, "")), coverage);

            assertEquals(4, handles.size(), "opener, both members and the closer");
            for (SentPacket handle : handles) {
                assertTrue(handle.isAssigned(), "every record of the run took a sequence");
                assertEquals(LogicalStream.CONTROL, handle.logicalStream(),
                        "13.15: the REALTIME member promotes the whole bundle to GLOBAL on CONTROL");
            }
            rig.serverLanes.completeScope(controlScope);
            rig.serverLanes.completeScope(realtimeScope);

            List<Object> received = rig.clientGame.await(5);
            assertEquals(5, received.size(),
                    "the opener, both members, the closer and one bundle acknowledgement: " + received);
            assertArrayEquals(frame(70, ""), ((Packet) received.get(0)).frame);
            assertArrayEquals(frame(8, "control member"), ((Packet) received.get(1)).frame);
            assertArrayEquals(frame(9, "realtime member"), ((Packet) received.get(2)).frame);
            assertArrayEquals(frame(72, ""), ((Packet) received.get(3)).frame);
            GameLanes.Ack ack = (GameLanes.Ack) received.get(4);
            assertTrue(ack.bundle(), "13.15: the run is one delivery, never per-child packets");
            assertEquals(handles.get(3).sequence(), ack.sequence(),
                    "the acknowledged frontier is the closer");
        }
    }

    /**
     * A rejected covered bundle assigns and writes nothing through the production session: the
     * foreign token on the second member rejects the call, the live token on the first member
     * survives, and only a later valid covered send reaches the peer (U2b, 13.19).
     */
    @Test
    void aRejectedCoveredBundleThroughTheProductionFacadeWritesNothing() throws Exception {
        try (Rig rig = new Rig()) {
            rig.activateServer(NO_LEFTOVER);
            rig.activateClient(NO_LEFTOVER);

            MultistreamSession.CausalScope localScope = rig.serverLanes.openScope();
            MultistreamSession.UpdateToken live = rig.serverLanes.registerUpdate(localScope);
            MultistreamSession.CausalScope foreignScope = rig.clientLanes.openScope();
            MultistreamSession.UpdateToken foreign = rig.clientLanes.registerUpdate(foreignScope);

            List<OutboundPacket> members = new ArrayList<OutboundPacket>();
            members.add(new OutboundPacket(frame(8, "first member")));
            members.add(new OutboundPacket(frame(9, "second member")));
            List<List<MultistreamSession.UpdateToken>> coverage =
                    new ArrayList<List<MultistreamSession.UpdateToken>>();
            coverage.add(Collections.singletonList(live));
            coverage.add(Collections.singletonList(foreign));

            assertThrows(AckJoinTracker.ForeignHandleException.class,
                    () -> rig.serverLanes.sendCoveredBundle(new OutboundPacket(frame(70, "")),
                            members, new OutboundPacket(frame(72, "")), coverage),
                    "13.19: one foreign token rejects the whole atomic run");

            byte[] later = frame(8, "later");
            assertTrue(rig.serverLanes.sendCovered(live, ProtocolState.PLAY, 8, later, null).isAssigned(),
                    "13.19: the live token survived the rejected bundle untouched");
            List<Object> received = rig.clientGame.await(2);
            assertEquals(2, received.size(),
                    "only the later covered packet and its acknowledgement: " + received);
            assertArrayEquals(later, ((Packet) received.get(0)).frame,
                    "nothing from the rejected bundle reached the wire");
            assertEquals(Lane.CONTROL, ((GameLanes.Ack) received.get(1)).lane());
        }
    }

    /**
     * Coverage sealing over the production session: the pending ack is created through the existing
     * ACK_JOIN passthrough, the facade registers and seals the covering scope, and a later scope is
     * refused (U1, 13.19).
     */
    @Test
    void sealingCoverageThroughTheProductionFacadeRejectsALaterScope() throws Exception {
        try (Rig rig = new Rig()) {
            rig.activateServer(NO_LEFTOVER);
            rig.activateClient(NO_LEFTOVER);

            Method session = GameLanes.class.getDeclaredMethod("session");
            session.setAccessible(true);
            GameLanes.Session sink = (GameLanes.Session) session.invoke(rig.clientLanes);
            assertTrue(sink.createAck(1L, new byte[] {1, 2, 3}, 3),
                    "the session took the pending ack");

            MultistreamSession.CausalScope scope = rig.clientLanes.openScope();
            MultistreamSession.UpdateToken token = rig.clientLanes.registerUpdate(scope);
            rig.clientLanes.sendCovered(token, ProtocolState.PLAY, 8, frame(8, "covered"), null);
            rig.clientLanes.completeScope(scope);
            rig.clientLanes.coverScope(1L, scope);
            rig.clientLanes.sealAckCoverage(1L);
            rig.clientLanes.sealAckCoverage(1L);

            MultistreamSession.CausalScope late = rig.clientLanes.openScope();
            rig.clientLanes.completeScope(late);
            IllegalArgumentException sealed = assertThrows(IllegalArgumentException.class,
                    () -> rig.clientLanes.coverScope(1L, late),
                    "13.19: sealed membership takes no later covering scope");
            assertEquals("ack coverage sealed", sealed.getMessage());
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
            serverSide.activate(serverLanes, PROTOCOL, false, leftover);
            awaitSession(serverSide);
        }

        void activateClient(ByteBuffer leftover) throws Exception {
            client.detachForActivation(ignored -> { });
            client.activate(clientLanes, false, leftover);
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

    /** A platform codec: the test's packet codec, with the knobs the tests need. */
    static final class Codec implements GameLanes.Codec {
        final Map<Object, GameLanes.Route> routes = new HashMap<>();
        volatile boolean chargeBeyondBudget;
        /**
         * The packet ids that act as bundle delimiters, or -1 when this codec marks none. A real
         * platform codec knows its delimiter packet types; between them every packet is a member.
         */
        volatile int bundleOpenId = -1;
        volatile int bundleCloseId = -1;
        private boolean insideBundle;
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
            int id = packetId(body);
            Envelope.Bundle bundle = Envelope.Bundle.NONE;
            if (bundleOpenId >= 0) {
                if (id == bundleOpenId) {
                    insideBundle = true;
                    bundle = Envelope.Bundle.OPEN;
                } else if (id == bundleCloseId) {
                    insideBundle = false;
                    bundle = Envelope.Bundle.CLOSE;
                } else if (insideBundle) {
                    bundle = Envelope.Bundle.MEMBER;
                }
            }
            return new LaneGame.Decoded(new Packet(body), ProtocolState.PLAY, id, bundle, false);
        }

        /** The packet id of one complete GAME frame this codec built: length VarInt, then id VarInt. */
        private static int packetId(byte[] body) {
            int[] at = {0};
            if (varInt(body, at) < 0) {
                return 0;
            }
            int id = varInt(body, at);
            return id < 0 ? 0 : id;
        }

        private static int varInt(byte[] bytes, int[] at) {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                if (at[0] >= bytes.length) {
                    return -1;
                }
                int read = bytes[at[0]++] & 0xff;
                value |= (read & 0x7f) << shift;
                if ((read & 0x80) == 0) {
                    return value;
                }
            }
            return -1;
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
