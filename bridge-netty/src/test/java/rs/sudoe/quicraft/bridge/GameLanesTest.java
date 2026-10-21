// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.AckJoinTracker;
import rs.sudoe.quicraft.core.multistream.Envelope;
import rs.sudoe.quicraft.core.multistream.LaneGame;
import rs.sudoe.quicraft.core.multistream.LogicalStream;
import rs.sudoe.quicraft.core.multistream.MultistreamSession;
import rs.sudoe.quicraft.core.multistream.NStreamSession;
import rs.sudoe.quicraft.core.multistream.OutboundPacket;
import rs.sudoe.quicraft.core.multistream.Record;
import rs.sudoe.quicraft.core.multistream.SentPacket;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The game side of a {@code /3} connection (docs/protocol.md 13.6, 13.10, 13.12, 13.14) with a
 * fake codec and a fake session: no Minecraft classes, no QUIC connection.
 */
class GameLanesTest {
    private static EventLoopGroup loop;

    @BeforeAll
    static void setUp() {
        loop = new NioEventLoopGroup(1);
    }

    @AfterAll
    static void tearDown() {
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void deliverFiresThePacketsThenTheAckInOrder() {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.CONFIGURATION, null);

        lanes.deliver(Lane.CONTROL, 5, Arrays.asList("spawn", "move"), false);

        assertSame("spawn", channel.readInbound());
        assertSame("move", channel.readInbound());
        GameLanes.Ack ack = channel.readInbound();
        assertEquals(Lane.CONTROL, ack.lane());
        assertEquals(5, ack.sequence(), "13.10: the final sequence is what the game acknowledges");
        assertFalse(ack.bundle());
    }

    @Test
    void decodedSizeTravelsWithItsDeliveryAcrossLanesAndSequenceGaps() {
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = GameLanes.forTest(channel, new FakeCodec(), new FakeSession(),
                ProtocolState.PLAY, null);
        lanes.deliver(Lane.REALTIME, 7, Collections.<Object>singletonList("first"), false, 123);
        lanes.deliver(Lane.CONTROL, 2, Collections.<Object>singletonList("second"), false, 456);
        assertEquals("first", channel.readInbound());
        GameLanes.Ack first = channel.readInbound();
        assertEquals(Lane.REALTIME, first.lane());
        assertEquals(7, first.sequence());
        assertEquals(123, first.decodedBytes());
        assertEquals("second", channel.readInbound());
        GameLanes.Ack second = channel.readInbound();
        assertEquals(Lane.CONTROL, second.lane());
        assertEquals(2, second.sequence());
        assertEquals(456, second.decodedBytes());
        channel.finishAndReleaseAll();
    }

    @Test
    void deliverFromAnotherThreadKeepsTheDeliveryOrder() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannel channel = new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        OrderCollector collector = new OrderCollector();
        channel.pipeline().addLast(collector);
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.PLAY, null);

        // The test thread is not the game channel's event loop.
        lanes.deliver(Lane.CONTROL, 1, Collections.singletonList("first"), false);
        lanes.deliver(Lane.WORLD, 2, Collections.singletonList("second"), false);
        collector.await(4);

        assertEquals(Arrays.asList("first", "ack:CONTROL:1", "second", "ack:WORLD:2"), collector.seen,
                "every delivery is fired whole, in arrival order, on the game's own loop");
        assertFalse(collector.offLoop.get(), "never off the game channel's event loop");
        channel.close().sync();
    }

    @Test
    void writabilityChangesReachTheGamePipelineAndIsWritable() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannel channel = new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        OrderCollector collector = new OrderCollector();
        channel.pipeline().addLast(collector);
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.PLAY, null);

        lanes.onWritabilityChanged(false);
        collector.await(1);

        assertFalse(lanes.isWritable(), "13.12: the game stops submitting while this is false");
        assertEquals(1, collector.writabilityChanges.get());
        lanes.onWritabilityChanged(true);
        assertTrue(lanes.isWritable());
        channel.close().sync();
    }

    @Test
    void anOrderingFailureClosesTheGameChannelWithTheCodeInTheCause() {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                seen.set(cause);
            }
        });
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.PLAY, null);

        lanes.onClosed(NStreamSession.CLOSE_ORDERING, false, null);

        assertTrue(lanes.isClosed());
        assertFalse(channel.isOpen(), "13.14: the game's channel goes away with the connection");
        GameLanes.LaneClosedException cause = (GameLanes.LaneClosedException) seen.get();
        assertNotNull(cause);
        assertEquals(NStreamSession.CLOSE_ORDERING, cause.code());
        assertTrue(cause.getMessage().contains("0x11"),
                "13.14: the cause names the application error code: " + cause.getMessage());
    }

    @Test
    void aNormalEndClosesTheChannelWithoutAnError() {
        FakeCodec codec = new FakeCodec();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = GameLanes.forTest(channel, codec, new FakeSession(), ProtocolState.PLAY, null);

        lanes.onClosed(NStreamSession.CLOSE_NORMAL, true, null);

        assertFalse(channel.isOpen());
        channel.checkException();
    }

    @Test
    void decodeHandsTheFrameAndTheActivationStateToThePlatform() throws Exception {
        FakeCodec codec = new FakeCodec();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = GameLanes.forTest(channel, codec, new FakeSession(), ProtocolState.CONFIGURATION, null);

        LaneGame.Decoded decoded = lanes.decode(Lane.CONTROL, 3, new byte[] {4, 5, 6}, false,
                (extra, total) -> {});

        assertEquals(ProtocolState.CONFIGURATION, codec.decodeStates.get(0));
        assertArrayEquals(new byte[] {4, 5, 6}, codec.decodedFrames.get(0));
        assertEquals(1, codec.frameRefCountDuringDecode, "the frame is alive while it is decoded");
        assertEquals(0, codec.seenFrame.refCnt(), "and released once the codec is done with it");
        assertEquals(9, decoded.packetId());
        assertEquals(ProtocolState.PLAY, decoded.state());
    }

    @Test
    void appliedReachesTheSession() {
        FakeSession session = new FakeSession();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = GameLanes.forTest(channel, new FakeCodec(), session, ProtocolState.PLAY, null);

        lanes.applied(Lane.WORLD, 11);

        assertEquals(Collections.singletonList(Lane.WORLD + ":11"), session.applied);
    }

    @Test
    void outboundPacketsGoThroughTheSessionAndNeverToStreamZero() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannelHandOverTest.FakeStream stream = new QuicBridgeChannelHandOverTest.FakeStream();
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        loop.register(channel).sync();
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.PLAY, null);
        lanes.installOutbound();
        session.routed = Lane.REALTIME;
        session.assign = Lane.REALTIME;
        codec.routes.put("move", new GameLanes.Route(ProtocolState.PLAY, 42, Lane.REALTIME));

        AtomicReference<ChannelFuture> write = new AtomicReference<>();
        channel.eventLoop().submit(() -> write.set(channel.writeAndFlush("move"))).sync();

        assertTrue(write.get().isSuccess());
        assertEquals(1, session.sent.size());
        assertEquals(ProtocolState.PLAY + ":42", session.sent.get(0));
        assertArrayEquals("frame:move".getBytes(StandardCharsets.US_ASCII), session.frames.get(0),
                "the platform's own encoder produced the frame");
        assertEquals(0, stream.writtenBytes().length,
                "nothing may reach stream 0 once the session owns it (13.4)");
        channel.close().sync();
    }

    @Test
    void aLaneTheRoutingTableDisagreesWithIsRefusedBeforeTheSessionGetsIt() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannelHandOverTest.FakeStream stream = new QuicBridgeChannelHandOverTest.FakeStream();
        QuicBridgeChannel channel = new QuicBridgeChannel(stream);
        loop.register(channel).sync();
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.PLAY, null);
        lanes.installOutbound();
        // The platform says CONTROL, the session's routing table assigns WORLD.
        session.routed = Lane.WORLD;
        codec.routes.put("spawn", new GameLanes.Route(ProtocolState.PLAY, 7, Lane.CONTROL));
        AtomicReference<Throwable> seen = new AtomicReference<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                seen.set(cause);
            }
        });

        AtomicReference<ChannelFuture> write = new AtomicReference<>();
        channel.eventLoop().submit(() -> write.set(channel.writeAndFlush("spawn"))).sync();

        assertFalse(write.get().isSuccess(), "13.6: a wrong-lane packet is never put on the wire");
        assertTrue(seen.get() instanceof GameLanes.LaneMismatchException, String.valueOf(seen.get()));
        assertEquals(Collections.singletonList(NStreamSession.CLOSE_ORDERING), session.aborted,
                "13.6/13.14: a routing disagreement is an ordering failure");
        assertTrue(session.sent.isEmpty(),
                "13.6: the refusal happens before the session is handed the packet, so it could not "
                        + "have reached the wire");
        assertEquals(0, stream.writtenBytes().length);
        channel.close().sync();
    }

    @Test
    void aWrongLaneIsStillCaughtWhenTheWriteComesFromAnotherThread() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannel channel =
                new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.PLAY, null);
        lanes.installOutbound();
        session.routed = Lane.WORLD;
        codec.routes.put("spawn", new GameLanes.Route(ProtocolState.PLAY, 7, Lane.CONTROL));

        // The test thread is not the game channel's event loop: the write is marshalled there, so
        // the check still runs instead of being skipped.
        ChannelFuture write = channel.writeAndFlush("spawn");
        write.awaitUninterruptibly(5, TimeUnit.SECONDS);

        assertFalse(write.isSuccess());
        assertTrue(session.sent.isEmpty());
        assertEquals(Collections.singletonList(NStreamSession.CLOSE_ORDERING), session.aborted);
        channel.close().sync();
    }

    @Test
    void theReservationReachesTheCodecBeforeItAllocates() throws Exception {
        FakeCodec codec = new FakeCodec();
        EmbeddedChannel channel = new EmbeddedChannel();
        GameLanes lanes = GameLanes.forTest(channel, codec, new FakeSession(), ProtocolState.PLAY, null);
        AtomicReference<String> charged = new AtomicReference<>();
        // 13.12: a codec charges the receive budget before it allocates decompression output, so the
        // reservation LaneGame hands decode must arrive inside the codec's own call.
        LaneGame.Reservation reservation = (extra, total) -> charged.set(extra + "/" + total);

        lanes.decode(Lane.CONTROL, 4, new byte[] {1}, false, reservation);

        assertSame(reservation, codec.seenReservation);
        assertEquals("128/256", charged.get(), "and the codec really charged it (13.12)");
    }

    @Test
    void anOffLoopDeliveryIsNotOvertakenByAnOnLoopOne() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannel channel =
                new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        OrderCollector collector = new OrderCollector();
        channel.pipeline().addLast(collector);
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.PLAY, null);

        CountDownLatch inLoop = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> loopFailure = new AtomicReference<>();
        channel.eventLoop().execute(() -> {
            inLoop.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                // A delivery that arrives on the loop while another thread's delivery is still
                // waiting must not overtake it (13.10).
                lanes.deliver(Lane.CONTROL, 2, Collections.singletonList("second"), false);
            } catch (Throwable t) {
                loopFailure.set(t);
            }
        });
        assertTrue(inLoop.await(5, TimeUnit.SECONDS), "the loop task is running");

        lanes.deliver(Lane.CONTROL, 1, Collections.singletonList("first"), false);
        release.countDown();
        collector.await(4);
        assertEquals(4, collector.seen.size(), String.valueOf(loopFailure.get()));
        assertEquals(Arrays.asList("first", "ack:CONTROL:1", "second", "ack:CONTROL:2"), collector.seen,
                "one queue, fired in arrival order, whatever thread each delivery came from");
        channel.close().sync();
    }

    @Test
    void anUnroutedPacketFailsClosed() throws Exception {
        FakeCodec codec = new FakeCodec();
        FakeSession session = new FakeSession();
        QuicBridgeChannel channel = new QuicBridgeChannel(new QuicBridgeChannelHandOverTest.FakeStream());
        loop.register(channel).sync();
        GameLanes lanes = GameLanes.forTest(channel, codec, session, ProtocolState.PLAY, null);
        lanes.installOutbound();
        AtomicReference<Throwable> seen = new AtomicReference<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                seen.set(cause);
            }
        });

        AtomicReference<ChannelFuture> write = new AtomicReference<>();
        channel.eventLoop().submit(() -> write.set(channel.writeAndFlush("unknown"))).sync();

        assertFalse(write.get().isSuccess());
        assertNotNull(seen.get());
        assertEquals(Collections.singletonList(NStreamSession.CLOSE_INTERNAL_ERROR), session.aborted);
        channel.close().sync();
    }

    @Test
    void theCausalFacadeDelegatesToTheConnectionSession() {
        CausalSession session = new CausalSession();
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), session,
                ProtocolState.PLAY, null);

        MultistreamSession.CausalScope scope = lanes.openScope();
        assertNotNull(scope);
        assertEquals(1, session.openScopes, "the scope comes from the session, never from a new tracker");
        MultistreamSession.UpdateToken token = lanes.registerUpdate(scope);
        assertTrue(session.tracker.validateScopeToken(token), "a live token of the session's own tracker");

        byte[] frame = new byte[] {1, 2, 3};
        assertNull(lanes.sendCovered(token, ProtocolState.PLAY, 8, frame, frame),
                "the session's own answer is what the facade returns");
        assertEquals(Collections.singletonList(ProtocolState.PLAY + ":8"), session.covered);
        assertArrayEquals(frame, session.coveredFrames.get(0), "the frame reaches the session unchanged");
        assertArrayEquals(frame, session.coveredUncompressed.get(0),
                "the uncompressed bytes reach the session unchanged");
        lanes.completeScope(scope);

        MultistreamSession.CausalScope second = lanes.openScope();
        MultistreamSession.UpdateToken suppressed = lanes.registerUpdate(second);
        lanes.suppressUpdate(suppressed);
        lanes.completeScope(second);
        assertEquals(2, session.openScopes, "each facade call reached the one session");
    }

    @Test
    void causalHandlesAreBoundToTheSessionThatIssuedThem() {
        CausalSession first = new CausalSession();
        CausalSession second = new CausalSession();
        GameLanes firstLanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), first,
                ProtocolState.PLAY, null);
        GameLanes secondLanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), second,
                ProtocolState.PLAY, null);

        MultistreamSession.CausalScope scope = firstLanes.openScope();
        assertThrows(AckJoinTracker.ForeignHandleException.class,
                () -> secondLanes.registerUpdate(scope), "a scope handle from another connection");
        MultistreamSession.UpdateToken token = firstLanes.registerUpdate(scope);
        assertThrows(AckJoinTracker.ForeignHandleException.class,
                () -> secondLanes.sendCovered(token, ProtocolState.PLAY, 8, new byte[] {1}, null),
                "a token handle from another connection");
        assertThrows(AckJoinTracker.ForeignHandleException.class,
                () -> secondLanes.completeScope(scope));
        long ack = second.createAckForTest();
        assertThrows(AckJoinTracker.ForeignHandleException.class,
                () -> secondLanes.coverScope(ack, scope));

        assertTrue(first.tracker.validateScopeToken(token), "the rejected calls left the token live");
        assertTrue(second.coveredFrames.isEmpty(), "nothing reached the other session's send path");
    }

    @Test
    void aResolvedOrSuppressedUpdateCannotResolveAgain() {
        CausalSession session = new CausalSession();
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), session,
                ProtocolState.PLAY, null);
        MultistreamSession.CausalScope scope = lanes.openScope();

        MultistreamSession.UpdateToken assigned = lanes.registerUpdate(scope);
        lanes.sendCovered(assigned, ProtocolState.PLAY, 8, new byte[] {1}, null);
        assertThrows(IllegalArgumentException.class,
                () -> lanes.sendCovered(assigned, ProtocolState.PLAY, 8, new byte[] {1}, null),
                "an assigned token is consumed");
        assertThrows(IllegalArgumentException.class, () -> lanes.suppressUpdate(assigned),
                "an assigned token can never be suppressed afterwards");

        MultistreamSession.UpdateToken suppressed = lanes.registerUpdate(scope);
        lanes.suppressUpdate(suppressed);
        assertThrows(IllegalArgumentException.class, () -> lanes.suppressUpdate(suppressed),
                "suppression is final");
        assertThrows(IllegalArgumentException.class,
                () -> lanes.sendCovered(suppressed, ProtocolState.PLAY, 8, new byte[] {1}, null),
                "a suppressed token has no assignment");
        assertEquals(1, session.covered.size(), "only the one covered send reached the session");
    }

    @Test
    void aScopeWithUnresolvedUpdatesCannotComplete() {
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(),
                new CausalSession(), ProtocolState.PLAY, null);
        MultistreamSession.CausalScope scope = lanes.openScope();
        MultistreamSession.UpdateToken first = lanes.registerUpdate(scope);
        MultistreamSession.UpdateToken second = lanes.registerUpdate(scope);

        assertThrows(IllegalArgumentException.class, () -> lanes.completeScope(scope),
                "the first token is still unresolved");
        lanes.suppressUpdate(first);
        assertThrows(IllegalArgumentException.class, () -> lanes.completeScope(scope),
                "the second token is still unresolved");
        lanes.suppressUpdate(second);
        lanes.completeScope(scope);
        assertThrows(IllegalArgumentException.class, () -> lanes.registerUpdate(scope),
                "a completed scope takes no new update");
    }

    @Test
    void sealingCoverageRejectsLaterScopesAndStaysSealed() {
        CausalSession session = new CausalSession();
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), session,
                ProtocolState.PLAY, null);
        long ack = session.createAckForTest();
        MultistreamSession.CausalScope scope = lanes.openScope();

        lanes.coverScope(ack, scope);
        lanes.sealAckCoverage(ack);
        lanes.sealAckCoverage(ack);
        MultistreamSession.CausalScope late = lanes.openScope();
        assertThrows(AckJoinTracker.SealedCoverageException.class, () -> lanes.coverScope(ack, late),
                "a sealed membership takes no later covering scope");
    }

    @Test
    void theAckLifecycleFacadeDelegatesToTheConnectionSession() {
        AckSession session = new AckSession();
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), session,
                ProtocolState.PLAY, null);
        byte[] payload = new byte[] {1, 2, 3};

        assertTrue(lanes.createAck(1L, payload, payload.length),
                "the session's own answer is what the facade returns");
        assertArrayEquals(payload, session.ackPayloads.get(1L),
                "the payload reaches the one session unchanged");
        SentPacket assigned = sentPacketForTest();
        lanes.coverAssigned(1L, assigned);
        assertSame(assigned, session.coveredHandles.get(0),
                "the handle reaches the one session unchanged");
        lanes.markInexact(1L);

        MultistreamSession.CausalScope scope = lanes.openScope();
        lanes.completeScope(scope);
        lanes.coverScope(1L, scope);
        lanes.sealAckCoverage(1L);
        assertSame(session.resolvedHandle, lanes.takeAck(1L),
                "the session's own resolved handle is what the facade returns");
        assertEquals(0, session.tracker.pendingCount(), "the one tracker resolved and released the ack");
        assertEquals(1, session.resolvedAcks.size());
        assertEquals(LogicalStream.CONTROL, session.resolvedAcks.get(0)[0].streamIndex());
        assertEquals(9, session.resolvedAcks.get(0)[0].sequence(),
                "markInexact reached the same tracker, so the safe superset was used");

        lanes.expireAcks();
        assertEquals(Arrays.asList("createAck:1:3", "coverAssigned:1", "markInexact:1",
                "takeAck:1", "expireAcks"), session.ackCalls,
                "every facade call reached the one session, in order");
    }

    @Test
    void ackIdsAreOwnedByTheSessionThatAcceptedThem() {
        AckSession first = new AckSession();
        AckSession second = new AckSession();
        GameLanes firstLanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), first,
                ProtocolState.PLAY, null);
        GameLanes secondLanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), second,
                ProtocolState.PLAY, null);
        byte[] payload = new byte[] {1};

        assertTrue(firstLanes.createAck(7L, payload, 1));
        assertThrows(IllegalArgumentException.class,
                () -> secondLanes.coverAssigned(7L, sentPacketForTest()),
                "an ack id only exists in the session that accepted it");
        assertThrows(IllegalArgumentException.class, () -> secondLanes.markInexact(7L));
        assertThrows(IllegalArgumentException.class, () -> secondLanes.sealAckCoverage(7L));
        assertNull(secondLanes.takeAck(7L), "a foreign id never resolves another session's ack");

        assertTrue(secondLanes.createAck(7L, payload, 1),
                "ack ids are per session, so the same number is a different ack");
        secondLanes.sealAckCoverage(7L);
        assertNotNull(secondLanes.takeAck(7L), "the second session resolved its own ack 7");
        assertNull(secondLanes.takeAck(7L), "an ack resolves once");
        assertEquals(1, first.tracker.pendingCount(),
                "resolving the other session's ack 7 left the first one pending");
        assertNull(firstLanes.takeAck(7L), "the first ack 7 is still unsealed");

        firstLanes.sealAckCoverage(7L);
        assertNotNull(firstLanes.takeAck(7L), "the first session still owns its own ack 7");
    }

    @Test
    void anAckResolvesOnlyAfterItsCoverageIsSealedAndComplete() {
        AckSession session = new AckSession();
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), session,
                ProtocolState.PLAY, null);
        byte[] payload = new byte[] {1};

        assertTrue(lanes.createAck(1L, payload, 1));
        assertNull(lanes.takeAck(1L), "an unsealed ack never resolves");

        MultistreamSession.CausalScope scope = lanes.openScope();
        MultistreamSession.UpdateToken token = lanes.registerUpdate(scope);
        lanes.sendCovered(token, ProtocolState.PLAY, 8, new byte[] {8}, null);
        lanes.coverScope(1L, scope);
        lanes.sealAckCoverage(1L);
        assertNull(lanes.takeAck(1L), "a covering scope that is still live keeps the ack pending");

        lanes.completeScope(scope);
        assertSame(session.resolvedHandle, lanes.takeAck(1L),
                "a sealed ack with every covering scope complete resolves");
        assertEquals(0, session.tracker.pendingCount());
        assertEquals(1, session.resolvedAcks.get(0).length);
        assertEquals(LogicalStream.CONTROL, session.resolvedAcks.get(0)[0].streamIndex());
        assertEquals(1, session.resolvedAcks.get(0)[0].sequence(),
                "the completed scope's assigned frontier is the ack's dependency");
    }

    @Test
    void expireAcksDropsAPendingAckAtItsDeadline() {
        AckSession session = new AckSession();
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), session,
                ProtocolState.PLAY, null);

        assertTrue(lanes.createAck(2L, new byte[] {1}, 1));
        assertEquals(1, session.tracker.pendingCount());

        lanes.expireAcks();

        assertEquals(0, session.tracker.pendingCount(), "the session dropped the unsealed ack");
        assertEquals(1, session.tracker.droppedCount(), "and counted the drop");
        assertNull(lanes.takeAck(2L), "a dropped ack never resolves");
        assertEquals(Arrays.asList("createAck:2:1", "expireAcks", "takeAck:2"), session.ackCalls);
    }

    @Test
    void theFacadeOfALegacySessionFailsClosedOnEveryAckLifecycleMethod() {
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), new FakeSession(),
                ProtocolState.PLAY, null);
        assertThrows(UnsupportedOperationException.class,
                () -> lanes.createAck(1L, new byte[] {1}, 1));
        assertThrows(UnsupportedOperationException.class,
                () -> lanes.coverAssigned(1L, sentPacketForTest()));
        assertThrows(UnsupportedOperationException.class, () -> lanes.markInexact(1L));
        assertThrows(UnsupportedOperationException.class, () -> lanes.takeAck(1L));
        assertThrows(UnsupportedOperationException.class, lanes::expireAcks);
    }

    @Test
    void aCoveredBundleIsOneAtomicSessionTransaction() {
        CausalSession session = new CausalSession();
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), session,
                ProtocolState.PLAY, null);
        MultistreamSession.CausalScope coveredScope = lanes.openScope();
        MultistreamSession.UpdateToken covered = lanes.registerUpdate(coveredScope);
        MultistreamSession.CausalScope siblingScope = lanes.openScope();
        MultistreamSession.UpdateToken sibling = lanes.registerUpdate(siblingScope);
        MultistreamSession.CausalScope droppedScope = lanes.openScope();
        MultistreamSession.UpdateToken suppressed = lanes.registerUpdate(droppedScope);
        lanes.suppressUpdate(suppressed);

        List<OutboundPacket> members = Arrays.asList(
                new OutboundPacket(new byte[] {2}), new OutboundPacket(new byte[] {3}));
        List<List<MultistreamSession.UpdateToken>> rejected =
                new ArrayList<List<MultistreamSession.UpdateToken>>();
        rejected.add(Collections.singletonList(covered));
        rejected.add(Collections.singletonList(suppressed));
        assertThrows(IllegalArgumentException.class,
                () -> lanes.sendCoveredBundle(new OutboundPacket(new byte[] {1}), members,
                        new OutboundPacket(new byte[] {4}), rejected));
        assertTrue(session.bundles.isEmpty(), "a rejected bundle never reaches the session");
        assertTrue(session.tracker.validateScopeToken(covered),
                "the first token survived the rejection, so the call was all or nothing");
        assertTrue(session.tracker.validateScopeToken(sibling));

        List<List<MultistreamSession.UpdateToken>> accepted =
                new ArrayList<List<MultistreamSession.UpdateToken>>();
        accepted.add(Collections.singletonList(covered));
        accepted.add(Collections.singletonList(sibling));
        assertSame(session.bundleResult, lanes.sendCoveredBundle(new OutboundPacket(new byte[] {1}),
                members, new OutboundPacket(new byte[] {4}), accepted),
                "the session's own result is what the facade returns");
        assertEquals(1, session.bundles.size(), "one atomic session call, never per-child sends");
        assertEquals(Arrays.asList("1", "1"), session.bundles.get(0),
                "the whole coverage shape reached the one call");
        assertFalse(session.tracker.validateScopeToken(covered), "the bundle consumed its token");
        assertFalse(session.tracker.validateScopeToken(sibling));
    }

    /**
     * The additive causal methods fail closed on a session that does not implement them, so a
     * producer can never believe a scope was tracked by a session that cannot track one.
     */
    @Test
    void theLegacySessionFailsClosedOnEveryCausalScopeMethod() {
        GameLanes.Session session = new FakeSession();
        ProtocolState state = ProtocolState.PLAY;
        assertThrows(UnsupportedOperationException.class, session::openScope);
        assertThrows(UnsupportedOperationException.class, () -> session.registerUpdate(null));
        assertThrows(UnsupportedOperationException.class,
                () -> session.sendCovered(null, state, 7, new byte[] {1}, null));
        assertThrows(UnsupportedOperationException.class,
                () -> session.sendCoveredBundle(null, null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> session.suppressUpdate(null));
        assertThrows(UnsupportedOperationException.class, () -> session.completeScope(null));
        assertThrows(UnsupportedOperationException.class, () -> session.coverScope(1L, null));
        assertThrows(UnsupportedOperationException.class, () -> session.sealAckCoverage(1L));
        assertTrue(((FakeSession) session).sent.isEmpty(), "nothing reached the legacy send path");
    }

    @Test
    void theFacadeOfALegacySessionFailsClosedOnEveryCausalScopeMethod() {
        GameLanes lanes = GameLanes.forTest(new EmbeddedChannel(), new FakeCodec(), new FakeSession(),
                ProtocolState.PLAY, null);
        ProtocolState state = ProtocolState.PLAY;
        assertThrows(UnsupportedOperationException.class, lanes::openScope);
        assertThrows(UnsupportedOperationException.class, () -> lanes.registerUpdate(null));
        assertThrows(UnsupportedOperationException.class,
                () -> lanes.sendCovered(null, state, 7, new byte[] {1}, null));
        assertThrows(UnsupportedOperationException.class,
                () -> lanes.sendCoveredBundle(null, null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> lanes.suppressUpdate(null));
        assertThrows(UnsupportedOperationException.class, () -> lanes.completeScope(null));
        assertThrows(UnsupportedOperationException.class, () -> lanes.coverScope(1L, null));
        assertThrows(UnsupportedOperationException.class, () -> lanes.sealAckCoverage(1L));
    }

    /**
     * One standing {@link SentPacket}: the constructor and assignment are core-internal, so the
     * bridge test obtains the opaque handle the facade passes around through the one seam that
     * exists. A real producer gets its handles from the session's send operations.
     */
    static SentPacket sentPacketForTest() {
        try {
            Constructor<SentPacket> constructor = SentPacket.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SentPacket construction moved", e);
        }
    }

    /** A codec the test drives: what a frame decodes to and how a packet routes are the test's. */
    static final class FakeCodec implements GameLanes.Codec {
        final Map<Object, GameLanes.Route> routes = new HashMap<>();
        final List<byte[]> decodedFrames = new ArrayList<>();
        final List<ProtocolState> decodeStates = new ArrayList<>();
        int frameRefCountDuringDecode = -1;
        ByteBuf seenFrame;
        LaneGame.Reservation seenReservation;
        LaneGame.Decoded next;

        @Override
        public LaneGame.Decoded decode(ByteBuf frame, ProtocolState state,
                LaneGame.Reservation reservation) {
            byte[] bytes = new byte[frame.readableBytes()];
            frame.getBytes(frame.readerIndex(), bytes);
            decodedFrames.add(bytes);
            decodeStates.add(state);
            frameRefCountDuringDecode = frame.refCnt();
            seenFrame = frame;
            seenReservation = reservation;
            // As a platform codec must: charge the budget before allocating output (13.12).
            reservation.reserve(128, 256);
            if (next != null) {
                return next;
            }
            return new LaneGame.Decoded("decoded:" + new String(bytes, StandardCharsets.US_ASCII),
                    ProtocolState.PLAY, 9, Envelope.Bundle.NONE, false);
        }

        @Override
        public void encode(Object packet, ByteBuf out) {
            out.writeBytes(("frame:" + packet).getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public GameLanes.Route routeOf(Object packet) {
            return routes.get(packet);
        }
    }

    /** The session as the test sees it: what it routes, what it was sent, what it was told. */
    static final class FakeSession implements GameLanes.Session {
        final List<String> sent = new ArrayList<>();
        final List<byte[]> frames = new ArrayList<>();
        final List<String> applied = new ArrayList<>();
        final List<Integer> aborted = new ArrayList<>();
        /** What the routing table answers for the packets this test sends. */
        volatile Lane routed = Lane.CONTROL;
        /** What a send would report as the assigned lane. */
        volatile Lane assign;

        @Override
        public Lane outboundLane(ProtocolState state, int packetId) {
            return routed;
        }

        @Override
        public Lane send(ProtocolState state, int packetId, byte[] frame) {
            sent.add(state + ":" + packetId);
            frames.add(frame.clone());
            return assign != null ? assign : routed;
        }

        @Override
        public void applied(Lane lane, long sequence) {
            applied.add(lane + ":" + sequence);
        }

        @Override
        public void abort(int code) {
            aborted.add(Integer.valueOf(code));
        }
    }

    /**
     * A {@link GameLanes.Session} whose causal operations delegate to a real {@link AckJoinTracker}:
     * the handles are the production handle type and carry the tracker's own owner identity, so the
     * ownership and resolution rules exercised here are the real ones. Its single-send path is not
     * used. The production session's atomic bundle transaction and effective GLOBAL promotion are
     * exercised end to end in {@link GameLanesV3LoopbackTest}.
     */
    static class CausalSession implements GameLanes.Session {
        final AckJoinTracker tracker = new AckJoinTracker(LogicalStream.MAX_CHUNKS + 2);
        final List<String> covered = new ArrayList<>();
        final List<byte[]> coveredFrames = new ArrayList<>();
        final List<byte[]> coveredUncompressed = new ArrayList<>();
        /** The coverage shape of every bundle call that reached this session, in order. */
        final List<List<String>> bundles = new ArrayList<>();
        final List<SentPacket> bundleResult = new ArrayList<>();
        int openScopes;
        long sequences;
        private long nextAck = 1;

        long createAckForTest() {
            long id = nextAck++;
            assertTrue(tracker.create(id, 1, System.nanoTime()));
            return id;
        }

        @Override
        public Lane outboundLane(ProtocolState state, int packetId) {
            return Lane.CONTROL;
        }

        @Override
        public Lane send(ProtocolState state, int packetId, byte[] frame) {
            throw new UnsupportedOperationException("single send is not part of the causal wiring test");
        }

        @Override
        public void applied(Lane lane, long sequence) {
            throw new UnsupportedOperationException("applied is not part of the causal wiring test");
        }

        @Override
        public void abort(int code) {
            throw new UnsupportedOperationException("abort is not part of the causal wiring test");
        }

        @Override
        public MultistreamSession.CausalScope openScope() {
            openScopes++;
            return tracker.openScope();
        }

        @Override
        public MultistreamSession.UpdateToken registerUpdate(MultistreamSession.CausalScope scope) {
            return tracker.registerUpdate(scope);
        }

        @Override
        public SentPacket sendCovered(MultistreamSession.UpdateToken token, ProtocolState state,
                int packetId, byte[] frame, byte[] uncompressedPacket) {
            if (!tracker.validateScopeToken(token)) {
                throw new IllegalArgumentException(
                        "unknown, resolved, suppressed or retired update token");
            }
            covered.add(state + ":" + packetId);
            coveredFrames.add(frame == null ? null : frame.clone());
            coveredUncompressed.add(uncompressedPacket == null ? null : uncompressedPacket.clone());
            tracker.assignScopeToken(token, LogicalStream.CONTROL, ++sequences);
            return null;
        }

        @Override
        public List<SentPacket> sendCoveredBundle(OutboundPacket open, List<OutboundPacket> members,
                OutboundPacket close, List<List<MultistreamSession.UpdateToken>> memberCoverage) {
            if (open == null || members == null || close == null || memberCoverage == null
                    || memberCoverage.size() != members.size()) {
                throw new IllegalArgumentException("bundle coverage");
            }
            // Validate every token before consuming any: one bad token rejects the whole call (U2b).
            for (List<MultistreamSession.UpdateToken> tokens : memberCoverage) {
                if (tokens == null) {
                    throw new IllegalArgumentException("bundle coverage");
                }
                for (MultistreamSession.UpdateToken token : tokens) {
                    if (token == null || !tracker.validateScopeToken(token)) {
                        throw new IllegalArgumentException(
                                "unknown, resolved, suppressed or retired update token");
                    }
                }
            }
            List<String> shape = new ArrayList<>();
            Set<MultistreamSession.UpdateToken> distinct =
                    new LinkedHashSet<MultistreamSession.UpdateToken>();
            for (List<MultistreamSession.UpdateToken> tokens : memberCoverage) {
                shape.add(String.valueOf(tokens.size()));
                distinct.addAll(tokens);
            }
            bundles.add(shape);
            for (MultistreamSession.UpdateToken token : distinct) {
                tracker.assignScopeToken(token, LogicalStream.CONTROL, ++sequences);
            }
            return bundleResult;
        }

        @Override
        public void suppressUpdate(MultistreamSession.UpdateToken token) {
            tracker.suppressScopeToken(token);
        }

        @Override
        public void completeScope(MultistreamSession.CausalScope scope) {
            tracker.completeScope(scope);
        }

        @Override
        public void coverScope(long ackId, MultistreamSession.CausalScope scope) {
            tracker.coverScope(ackId, scope);
        }

        @Override
        public void sealAckCoverage(long ackId) {
            tracker.sealAckCoverage(ackId);
        }
    }

    /**
     * The ACK lifecycle over the same real {@link AckJoinTracker} as {@link CausalSession}, so the
     * facade's ownership, sealing and deadline rules are the tracker's own. The {@link SentPacket}
     * values are sentinels: assignment is core-internal, so this fake records the handle it was
     * given and merges a fixed frontier for it instead of reading an assigned sequence.
     */
    static final class AckSession extends CausalSession {
        final List<String> ackCalls = new ArrayList<>();
        final Map<Long, byte[]> ackPayloads = new HashMap<>();
        final List<SentPacket> coveredHandles = new ArrayList<>();
        final List<Record.Dependency[]> resolvedAcks = new ArrayList<>();
        final SentPacket resolvedHandle = sentPacketForTest();
        final Record.Dependency[] safeSuperset =
                { new Record.Dependency(LogicalStream.CONTROL, 9) };
        long now = System.nanoTime();
        long coveredSequence = 4;

        @Override
        public boolean createAck(long id, byte[] payload, int chargedBytes) {
            ackCalls.add("createAck:" + id + ":" + chargedBytes);
            if (payload == null) {
                throw new IllegalArgumentException("payload");
            }
            ackPayloads.put(id, payload.clone());
            return tracker.create(id, chargedBytes, now);
        }

        @Override
        public void coverAssigned(long ackId, SentPacket covered) {
            ackCalls.add("coverAssigned:" + ackId);
            coveredHandles.add(covered);
            tracker.coverAssigned(ackId, LogicalStream.CONTROL, coveredSequence++);
        }

        @Override
        public void markInexact(long ackId) {
            ackCalls.add("markInexact:" + ackId);
            tracker.markInexact(ackId);
        }

        @Override
        public SentPacket takeAck(long ackId) {
            ackCalls.add("takeAck:" + ackId);
            AckJoinTracker.Ready ready = tracker.take(ackId, now, 0, safeSuperset);
            if (ready == null) {
                return null;
            }
            resolvedAcks.add(ready.dependencies());
            return resolvedHandle;
        }

        @Override
        public void expireAcks() {
            ackCalls.add("expireAcks");
            now += AckJoinTracker.DEADLINE_NANOS + 1;
            for (AckJoinTracker.Ready ready : tracker.expire(now, 0, safeSuperset)) {
                resolvedAcks.add(ready.dependencies());
            }
        }
    }

    /** Collects what the pipeline sees, so a test can wait for events from another thread. */
    static final class OrderCollector extends ChannelInboundHandlerAdapter {
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        final java.util.concurrent.atomic.AtomicInteger writabilityChanges =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean offLoop =
                new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            offLoop.set(offLoop.get() || !ctx.channel().eventLoop().inEventLoop());
            synchronized (seen) {
                if (msg instanceof GameLanes.Ack) {
                    GameLanes.Ack ack = (GameLanes.Ack) msg;
                    seen.add("ack:" + ack.lane() + ":" + ack.sequence());
                } else {
                    seen.add(String.valueOf(msg));
                }
                seen.notifyAll();
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            writabilityChanges.incrementAndGet();
            synchronized (seen) {
                seen.notifyAll();
            }
        }

        void await(int count) throws InterruptedException {
            await(count, 5);
        }

        void await(int count, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            synchronized (seen) {
                while (seen.size() < count && writabilityChanges.get() == 0
                        && System.nanoTime() < deadline) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    TimeUnit.NANOSECONDS.timedWait(seen, left);
                }
            }
        }
    }

    /**
     * U8f: the additive game-facing methods fail closed on a session that does not implement them, so a
     * join or batch route can never be sent through the legacy session silently.
     */
    @Test
    void theLegacySessionFailsClosedOnEveryJoinAndBatchMethod() {
        GameLanes.Session session = new FakeSession();
        ProtocolState state = ProtocolState.PLAY;
        byte[] frame = new byte[] {1};
        assertThrows(UnsupportedOperationException.class,
                () -> session.send(state, 7, frame, frame, new SentPacket[0]), "send with uncompressed bytes");
        assertThrows(UnsupportedOperationException.class, () -> session.createAck(1L, frame, 1));
        assertThrows(UnsupportedOperationException.class, () -> session.coverAssigned(1L, null));
        assertThrows(UnsupportedOperationException.class, () -> session.coverPending(1L, 2L));
        assertThrows(UnsupportedOperationException.class, () -> session.assignedPending(2L, null));
        assertThrows(UnsupportedOperationException.class, () -> session.suppressPending(2L));
        assertThrows(UnsupportedOperationException.class, () -> session.markInexact(1L));
        assertThrows(UnsupportedOperationException.class, () -> session.takeAck(1L));
        assertThrows(UnsupportedOperationException.class, session::expireAcks);
        assertThrows(UnsupportedOperationException.class, () -> session.beginBatch(3L, null));
        assertThrows(UnsupportedOperationException.class, () -> session.batchInstall(3L, null, 0, 0));
        assertThrows(UnsupportedOperationException.class, () -> session.finishBatch(3L, null));
        assertTrue(((FakeSession) session).sent.isEmpty(), "nothing reached the legacy send path");
    }
}
