// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.transport.LaneLoopback;

/** The lane adapter over real loopback QUIC (docs/protocol.md 13.3, 13.9-13.15). */
class LaneSessionTest {
    private static final ProtocolState PLAY = ProtocolState.PLAY;

    @BeforeAll
    static void native_() {
        assertTrue(QuicSupport.isAvailable(), () -> "QUIC native unavailable: " + QuicSupport.unavailabilityCause());
    }

    private static void closedWith(LaneRig.TestGame game, int code, boolean byPeer) throws Exception {
        LaneRig.Closed closed = game.closed.get(10, TimeUnit.SECONDS);
        assertEquals(code, closed.code, closed.toString());
        assertEquals(byPeer, closed.byPeer, closed.toString());
    }

    private static void sawClose(java.util.concurrent.CompletableFuture<Integer> seen, int code) throws Exception {
        assertEquals(code, seen.get(10, TimeUnit.SECONDS).intValue());
    }

    /** One packet each way on the world lane: both auxiliary streams are open and validated. */
    private static void roundTrip(LaneRig rig) throws Exception {
        int server = rig.serverGame.packetCount();
        int client = rig.clientGame.packetCount();
        rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "warm-s"));
        rig.client.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "warm-c"));
        rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "warm-s2"));
        rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "warm-c2"));
        assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() >= client + 2
                && rig.serverGame.packetCount() >= server + 2, 10000), "round trip");
    }

    private static void assertPending(LaneSession receiver, int atLeast) throws Exception {
        assertTrue(LaneRig.await(() -> {
            try {
                return LaneRig.snapshot(receiver)[0] >= atLeast;
            } catch (Exception e) {
                return false;
            }
        }, 10000), "the held-back records reached the receiver and wait in its scheduler");
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private static QuicStreamChannel channel(LaneSession session, Lane lane) throws Exception {
        return session.eventLoop().submit(() -> session.channelFor(lane)).get(10, TimeUnit.SECONDS);
    }

    // --- Stream setup (13.3).

    @Test
    void gamePacketsOnAllThreeLanesAreDeliveredWithTheGamesAcknowledgementFromAnotherThread() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.clientGame.applyOnOtherThread = true;
            rig.serverGame.applyOnOtherThread = true;
            rig.activateBoth();
            rig.server.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "block"));
            rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "c-move"));
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 3
                    && rig.serverGame.packetCount() == 1, 10000), rig.clientGame.deliveries.toString());
            List<Lane> lanes = new ArrayList<>();
            synchronized (rig.clientGame.deliveries) {
                for (LaneRig.Delivered d : rig.clientGame.deliveries) {
                    lanes.add(d.lane);
                }
            }
            assertTrue(lanes.containsAll(Arrays.asList(Lane.CONTROL, Lane.REALTIME, Lane.WORLD)), lanes.toString());
            assertEquals("spawn", rig.clientGame.labels().get(0));
            // Stream ids of 13.3: REALTIME is stream 4 (client-opened), WORLD stream 1 (server-opened).
            assertEquals(4, channel(rig.client, Lane.REALTIME).streamId());
            assertEquals(1, channel(rig.server, Lane.WORLD).streamId());
            assertEquals(0, channel(rig.client, Lane.CONTROL).streamId());
        }
    }

    @Test
    void theServerOpensStreamOneWithTheWorldPreambleAndTheClientStreamFourWithTheRealtimePreamble() throws Exception {
        LaneRig.Config serverOnly = new LaneRig.Config();
        serverOnly.clientSession = false;
        try (LaneRig rig = LaneRig.start(serverOnly)) {
            rig.server.activate(null);
            QuicStreamChannel one = rig.clientRawStreams.poll(10, TimeUnit.SECONDS);
            assertEquals(1, one.streamId());
            assertTrue(LaneRig.await(() -> rig.clientRawBytes.get(0).snapshot().length >= 2, 10000));
            assertArrayEquals(new byte[] {2, 2}, Arrays.copyOf(rig.clientRawBytes.get(0).snapshot(), 2));
        }
        LaneRig.Config clientOnly = new LaneRig.Config();
        clientOnly.serverSession = false;
        try (LaneRig rig = LaneRig.start(clientOnly)) {
            rig.client.activate(null);
            QuicStreamChannel four = rig.serverRawStreams.poll(10, TimeUnit.SECONDS);
            assertEquals(4, four.streamId());
            assertTrue(LaneRig.await(() -> rig.serverRawBytes.get(0).snapshot().length >= 2, 10000));
            assertArrayEquals(new byte[] {2, 1}, Arrays.copyOf(rig.serverRawBytes.get(0).snapshot(), 2));
        }
    }

    @Test
    void aWrongPreambleTypeOrVersionClosesBothSidesWith0x11() throws Exception {
        for (byte[] preamble : new byte[][] {{2, 2}, {2, 0}, {3, 1}}) {
            LaneRig.Config config = new LaneRig.Config();
            config.clientSession = false;
            try (LaneRig rig = LaneRig.start(config)) {
                rig.server.activate(null);
                QuicStreamChannel four = rig.net.clientStream();
                LaneLoopback.write(four, preamble);
                closedWith(rig.serverGame, 0x11, false);
                sawClose(rig.net.clientSawClose, 0x11);
            }
        }
    }

    @Test
    void anExtraStreamClosesBothSidesWith0x11() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.clientSession = false;
        config.options.serverMaxStreamsBidi = 8;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.server.activate(null);
            QuicStreamChannel four = rig.net.clientStream();
            LaneLoopback.write(four, (byte) 2, (byte) 1);
            assertTrue(LaneRig.await(() -> rig.server.isWritable(), 1000));
            sleep(200);
            assertFalse(rig.serverGame.closed.isDone(), "stream 4 is allowed");
            QuicStreamChannel eight = rig.net.clientStream();
            LaneLoopback.write(eight, (byte) 2, (byte) 1);
            closedWith(rig.serverGame, 0x11, false);
            sawClose(rig.net.clientSawClose, 0x11);
        }
    }

    @Test
    void aUnidirectionalStreamClosesBothSidesWith0x11() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.clientSession = false;
        config.options.serverMaxStreamsUni = 4;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.server.activate(null);
            LaneLoopback.write(rig.net.clientUniStream(), (byte) 2, (byte) 1);
            closedWith(rig.serverGame, 0x11, false);
            sawClose(rig.net.clientSawClose, 0x11);
        }
    }

    @Test
    void aSecondServerOpenedStreamClosesTheClientWith0x11() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.serverSession = false;
        config.options.clientMaxStreamsBidi = 4;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.client.activate(null);
            QuicChannel serverConnection = rig.net.serverConnection.get(10, TimeUnit.SECONDS);
            QuicStreamChannel one = serverConnection.createStream(
                    io.netty.handler.codec.quic.QuicStreamType.BIDIRECTIONAL,
                    new io.netty.channel.ChannelInboundHandlerAdapter()).get(10, TimeUnit.SECONDS);
            LaneLoopback.write(one, (byte) 2, (byte) 2);
            sleep(200);
            assertFalse(rig.clientGame.closed.isDone(), "stream 1 is allowed");
            QuicStreamChannel five = serverConnection.createStream(
                    io.netty.handler.codec.quic.QuicStreamType.BIDIRECTIONAL,
                    new io.netty.channel.ChannelInboundHandlerAdapter()).get(10, TimeUnit.SECONDS);
            LaneLoopback.write(five, (byte) 2, (byte) 2);
            closedWith(rig.clientGame, 0x11, false);
            sawClose(rig.net.serverSawClose, 0x11);
        }
    }

    @Test
    void aSecondOpenOfAnAllowedStreamClosesBothSidesWith0x11() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            QuicStreamChannel four = channel(rig.server, Lane.REALTIME);
            rig.server.eventLoop().submit(() -> rig.server.acceptStream(four)).get(10, TimeUnit.SECONDS);
            closedWith(rig.serverGame, 0x11, false);
            closedWith(rig.clientGame, 0x11, true);
        }
    }

    @Test
    void streamFourBeforeTheServersActivationIsFatalAndNotBuffered() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.client.activate(null);
            closedWith(rig.serverGame, 0x11, false);
            closedWith(rig.clientGame, 0x11, true);
            assertEquals(0, rig.serverGame.deliveries.size());
        }
    }

    @Test
    void aClientHoldsEarlyStreamOneBytesUndeliveredUntilItsOwnActivation() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.server.activate(null);
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "early"));
            sleep(400);
            assertEquals(0, rig.clientGame.packetCount(), "nothing is delivered before the client's activation");
            assertFalse(rig.clientGame.closed.isDone());
            rig.client.activate(null);
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 1, 10000));
            assertEquals("early", rig.clientGame.labels().get(0));
        }
    }

    @Test
    void anAllowedStreamNotOpenedWithinTheDeadlineClosesTheClientWith0x11() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.serverSession = false;
        config.clientTiming = new LaneSession.Timing(System::nanoTime, TimeUnit.MILLISECONDS.toNanos(400),
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(20));
        try (LaneRig rig = LaneRig.start(config)) {
            long start = System.nanoTime();
            rig.client.activate(null);
            closedWith(rig.clientGame, 0x11, false);
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(350), "not before the deadline");
            sawClose(rig.net.serverSawClose, 0x11);
        }
    }

    @Test
    void anAllowedStreamNotOpenedWithinTheDeadlineClosesTheServerWith0x11() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.clientSession = false;
        config.serverTiming = new LaneSession.Timing(System::nanoTime, TimeUnit.MILLISECONDS.toNanos(400),
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(20));
        try (LaneRig rig = LaneRig.start(config)) {
            long start = System.nanoTime();
            rig.server.activate(null);
            closedWith(rig.serverGame, 0x11, false);
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(350), "not before the deadline");
            sawClose(rig.net.clientSawClose, 0x11);
        }
    }

    @Test
    void controlBytesReadBeforeActivationAreParsedAsTheStartOfTheRecordStream() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.SPAWN, "leftover"));
            rig.activate(rig.server, java.nio.ByteBuffer.wrap(record));
            rig.client.activate(null);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == 1, 10000));
            assertEquals("leftover", rig.serverGame.labels().get(0));
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    @Test
    void anIncompleteRecordIsChargedAtItsFirstByteAndTimesOutWith0x11() throws Exception {
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        LaneRig.Config config = new LaneRig.Config();
        config.clientSession = false;
        config.serverTiming = new LaneSession.Timing(clock::get, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        try (LaneRig rig = LaneRig.start(config)) {
            rig.server.activate(null);
            QuicStreamChannel one = rig.clientRawStreams.poll(10, TimeUnit.SECONDS);
            LaneLoopback.write(one, (byte) 2, (byte) 2);
            QuicStreamChannel four = rig.net.clientStream();
            LaneLoopback.write(four, (byte) 2, (byte) 1);
            sleep(300);
            assertEquals(0, LaneRig.snapshot(rig.server)[0]);
            // First byte of a two-byte record_length (133): one slot and one byte are charged.
            LaneLoopback.write(four, (byte) 0x85);
            assertTrue(LaneRig.await(() -> {
                try {
                    return LaneRig.snapshot(rig.server)[0] == 1;
                } catch (Exception e) {
                    return false;
                }
            }, 10000));
            assertEquals(1, LaneRig.snapshot(rig.server)[1]);
            // The length completes: the whole parser buffer (2 prefix bytes + 133) is charged.
            LaneLoopback.write(four, (byte) 0x01);
            assertTrue(LaneRig.await(() -> {
                try {
                    return LaneRig.snapshot(rig.server)[1] == 135;
                } catch (Exception e) {
                    return false;
                }
            }, 10000));
            assertEquals(1, LaneRig.snapshot(rig.server)[0]);
            assertFalse(rig.serverGame.closed.isDone());
            clock.addAndGet(ReceiverScheduler.TIMEOUT_NANOS + 1_000_000_000L);
            closedWith(rig.serverGame, 0x11, false);
            sawClose(rig.net.clientSawClose, 0x11);
        }
    }

    // --- Ordering over real streams (13.9-13.11).

    @Test
    void aWithheldRealtimePredecessorBlocksTheControlRecordThatFollowsIt() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.clientGame.packetCount();
            rig.server.holdWrites(Lane.REALTIME);
            rig.server.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            rig.server.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "destroy"));
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == base + 1, 10000));
            assertPending(rig.client, 1);
            sleep(400);
            assertEquals(base + 1, rig.clientGame.packetCount(), "destroy must not overtake the move");
            rig.server.releaseWrites(Lane.REALTIME);
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == base + 3, 10000));
            assertEquals(Arrays.asList("spawn", "move", "destroy"),
                    rig.clientGame.labels().subList(base, base + 3));
        }
    }

    @Test
    void aWithheldControlSpawnHoldsBackTheMoveAndTheChunkUpdateThatArrivedFirst() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.clientGame.packetCount();
            rig.server.holdWrites(Lane.CONTROL);
            rig.server.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "block"));
            // The move and the block reached the client and wait behind the withheld spawn.
            assertPending(rig.client, 2);
            sleep(500);
            assertEquals(base, rig.clientGame.packetCount(), "nothing may pass its predecessor");
            rig.server.releaseWrites(Lane.CONTROL);
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == base + 3, 10000));
            List<String> got = rig.clientGame.labels().subList(base, base + 3);
            assertEquals("spawn", got.get(0));
            assertTrue(got.containsAll(Arrays.asList("move", "block")), got.toString());
        }
    }

    @Test
    void aWithheldWorldPredecessorBlocksTheControlRecordAndTheOtherDirectionOrdersTheSameWay() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.serverGame.packetCount();
            rig.client.holdWrites(Lane.WORLD);
            rig.client.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "block"));
            rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "unload"));
            assertPending(rig.server, 1);
            sleep(500);
            assertEquals(base, rig.serverGame.packetCount());
            rig.client.releaseWrites(Lane.WORLD);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == base + 2, 10000));
            assertEquals(Arrays.asList("block", "unload"), rig.serverGame.labels().subList(base, base + 2));
        }
    }

    @Test
    void aSemanticPredecessorOnAnotherLaneIsHonouredAcrossLanes() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.clientGame.packetCount();
            rig.server.holdWrites(Lane.REALTIME);
            SentPacket move = rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            // A block update that must not pass the entity move, though they share no lane.
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "block"), move);
            sleep(500);
            assertEquals(base, rig.clientGame.packetCount());
            rig.server.releaseWrites(Lane.REALTIME);
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == base + 2, 10000));
            assertEquals(Arrays.asList("move", "block"), rig.clientGame.labels().subList(base, base + 2));
            assertEquals(Lane.REALTIME, move.lane());
        }
    }

    @Test
    void aBundleStaysOnControlAndIsDeliveredAtomically() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.clientGame.deliveries.size();
            rig.clientGame.autoApply = false;
            rig.server.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            // A MOVE is routed to REALTIME on its own; inside a bundle it travels on CONTROL.
            List<SentPacket> sent = rig.server.sendBundle(
                    new OutboundPacket(LaneRig.frame(LaneRig.BUNDLE_OPEN, "open")),
                    Arrays.asList(new OutboundPacket(LaneRig.frame(LaneRig.MOVE, "m1")),
                            new OutboundPacket(LaneRig.frame(LaneRig.MOVE, "m2")),
                            new OutboundPacket(LaneRig.frame(LaneRig.BLOCK, "m3"))),
                    new OutboundPacket(LaneRig.frame(LaneRig.BUNDLE_CLOSE, "close")));
            rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "after"));
            assertTrue(LaneRig.await(() -> rig.clientGame.deliveries.size() == base + 1, 10000));
            rig.clientGame.apply(rig.clientGame.deliveries.get(base));
            assertTrue(LaneRig.await(() -> rig.clientGame.deliveries.size() == base + 2, 10000));
            LaneRig.Delivered bundle = rig.clientGame.deliveries.get(base + 1);
            assertTrue(bundle.bundle);
            assertEquals(Lane.CONTROL, bundle.lane);
            List<String> labels = new ArrayList<>();
            for (Object p : bundle.packets) {
                labels.add(((LaneRig.Pkt) p).label);
            }
            assertEquals(Arrays.asList("open", "m1", "m2", "m3", "close"), labels);
            for (SentPacket s : sent) {
                assertEquals(Lane.CONTROL, s.lane());
            }
            sleep(300);
            assertEquals(base + 2, rig.clientGame.deliveries.size(), "nothing follows an unapplied bundle");
            rig.clientGame.apply(bundle);
            assertTrue(LaneRig.await(() -> rig.clientGame.deliveries.size() == base + 3, 10000));
            assertEquals("after", rig.clientGame.labels().get(rig.clientGame.labels().size() - 1));
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    @Test
    void aPacketOnAnotherLaneThanItsRoutedOneClosesWith0x11() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        // The client believes a move is a CONTROL packet; the server's table says REALTIME (13.6).
        config.clientTable = RoutingTableLoader.parse(LaneRig.tableJson(0));
        try (LaneRig rig = LaneRig.start(config)) {
            rig.activateBoth();
            rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            closedWith(rig.serverGame, 0x11, false);
            closedWith(rig.clientGame, 0x11, true);
        }
    }

    // --- Resource limits (13.12).

    @Test
    void anAuxiliaryFloodCannotBlockAControlPredecessor() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.serverGame.packetCount();
            int flood = 1000;
            // Serverbound has the small windows; the spawn the flood depends on is withheld.
            rig.client.holdWrites(Lane.CONTROL);
            rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            for (int i = 0; i < flood; i++) {
                rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "m" + i));
            }
            assertTrue(LaneRig.await(() -> {
                try {
                    return LaneRig.snapshot(rig.server)[0] == ReceiverScheduler.MAX_AUXILIARY_RECORDS;
                } catch (Exception e) {
                    return false;
                }
            }, 15000), "the flood fills exactly the auxiliary record budget");
            sleep(300);
            long[] stalled = LaneRig.snapshot(rig.server);
            assertEquals(ReceiverScheduler.MAX_AUXILIARY_RECORDS, stalled[0], "reading stopped at the budget");
            assertEquals(base, rig.serverGame.packetCount());
            rig.client.releaseWrites(Lane.CONTROL);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == base + 1 + flood, 20000),
                    "delivered " + (rig.serverGame.packetCount() - base));
            List<String> labels = rig.serverGame.labels().subList(base, base + 1 + flood);
            assertEquals("spawn", labels.get(0));
            for (int i = 0; i < flood; i++) {
                assertEquals("m" + i, labels.get(1 + i));
            }
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    @Test
    void theUnsentQueueAppliesBackpressureAndDrainsBack() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            rig.client.holdWrites(Lane.CONTROL);
            for (int i = 0; i < 800; i++) {
                rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "c" + i));
            }
            assertTrue(LaneRig.await(() -> !rig.client.isWritable(), 5000));
            assertEquals(Collections.singletonList(false), rig.clientGame.writability);
            rig.client.releaseWrites(Lane.CONTROL);
            assertTrue(LaneRig.await(() -> rig.client.isWritable(), 15000));
            assertEquals(Arrays.asList(false, true), rig.clientGame.writability);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() >= 802, 15000));
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    @Test
    void aProducerPastThePacketCapDisconnectsWith0x11() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            rig.client.holdWrites(Lane.CONTROL);
            for (int i = 0; i < LaneSession.MAX_UNSENT_PACKETS + 1; i++) {
                rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "c" + i));
            }
            closedWith(rig.clientGame, 0x11, false);
            closedWith(rig.serverGame, 0x11, true);
        }
    }

    @Test
    void aProducerPastTheByteCapDisconnectsWith0x11() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            rig.client.holdWrites(Lane.CONTROL);
            for (int i = 0; i < 9; i++) {
                rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, 2_000_000));
            }
            closedWith(rig.clientGame, 0x11, false);
            closedWith(rig.serverGame, 0x11, true);
        }
    }

    @Test
    void aBlockedHeadPastTenSecondsClosesWith0x11() throws Exception {
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        LaneRig.Config config = new LaneRig.Config();
        config.serverTiming = new LaneSession.Timing(clock::get, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        try (LaneRig rig = LaneRig.start(config)) {
            rig.activateBoth();
            roundTrip(rig);
            rig.client.holdWrites(Lane.CONTROL);
            rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            assertTrue(LaneRig.await(() -> {
                try {
                    return LaneRig.snapshot(rig.server)[0] >= 1;
                } catch (Exception e) {
                    return false;
                }
            }, 10000));
            sleep(100);
            assertFalse(rig.serverGame.closed.isDone());
            clock.addAndGet(ReceiverScheduler.TIMEOUT_NANOS + 1_000_000_000L);
            closedWith(rig.serverGame, 0x11, false);
            closedWith(rig.clientGame, 0x11, true);
        }
    }

    /** Encoded REALTIME records numbered from..to, each depending on a CONTROL record that never comes. */
    private static byte[] blockedRealtimeRecords(int from, int to, byte[] frame) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = from; i <= to; i++) {
            out.write(RecordCodec.encodeGame(i, 1, i - 1, 0, frame));
        }
        return out.toByteArray();
    }

    private static boolean snapshotIs(LaneSession session, int index, long value) {
        try {
            return LaneRig.snapshot(session)[index] == value;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void aSplitRecordStillCompletesWhenTheAuxiliaryRecordBudgetIsFull() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.clientSession = false;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.server.activate(null);
            LaneLoopback.write(rig.clientRawStreams.poll(10, TimeUnit.SECONDS), (byte) 2, (byte) 2);
            QuicStreamChannel four = rig.net.clientStream();
            byte[] frame = LaneRig.frame(LaneRig.MOVE, "r");
            byte[] first = blockedRealtimeRecords(1, 959, frame);
            byte[] head = new byte[2 + first.length];
            head[0] = 2;
            head[1] = 1;
            System.arraycopy(first, 0, head, 2, first.length);
            LaneLoopback.write(four, head);
            assertTrue(LaneRig.await(() -> snapshotIs(rig.server, 0, 959), 15000));
            byte[] last = blockedRealtimeRecords(960, 960, frame);
            LaneLoopback.write(four, Arrays.copyOf(last, 3));
            assertTrue(LaneRig.await(() -> snapshotIs(rig.server, 0, 960) && snapshotIs(rig.server, 4, 1), 10000));
            LaneLoopback.write(four, Arrays.copyOfRange(last, 3, last.length));
            assertTrue(LaneRig.await(() -> snapshotIs(rig.server, 4, 0), 5000),
                    "the rest of a record in progress is read without new credit");
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    @Test
    void aMaximumSizeRecordWaitsForAuxiliaryBudgetInsteadOfBeingFatal() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.serverGame.packetCount();
            int max = RecordCodec.MAX_INNER_CONTENT - 1;
            rig.client.holdWrites(Lane.CONTROL);
            rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            for (int i = 0; i < 7; i++) {
                rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, max));
            }
            assertTrue(LaneRig.await(() -> snapshotIs(rig.server, 0, 7), 30000));
            rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, max));
            sleep(1000);
            assertEquals(7, LaneRig.snapshot(rig.server)[0], "the eighth waits for budget");
            assertFalse(rig.serverGame.closed.isDone(), "a legal record is never fatal for lack of budget");
            rig.client.releaseWrites(Lane.CONTROL);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == base + 9, 60000),
                    "delivered " + (rig.serverGame.packetCount() - base));
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    @Test
    void fourMegabytesOfRecordsReachTheServerThroughItsGrowthOffWindows() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.serverGame.packetCount();
            int max = RecordCodec.MAX_INNER_CONTENT - 1;
            long start = System.nanoTime();
            rig.client.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, max));
            rig.client.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, max));
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == base + 2, 30000));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 10000, "took " + millis + " ms");
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    // --- Resets, FIN and END (13.14).

    private void resetCloses(Lane lane, boolean fromClient) throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            LaneSession resetting = fromClient ? rig.client : rig.server;
            channel(resetting, lane).shutdownOutput(0x42);
            LaneRig.TestGame receiver = fromClient ? rig.serverGame : rig.clientGame;
            LaneRig.TestGame sender = fromClient ? rig.clientGame : rig.serverGame;
            closedWith(receiver, 0x11, false);
            closedWith(sender, 0x11, true);
        }
    }

    @Test
    void aResetOfTheRealtimeLaneClosesBothSidesWith0x11() throws Exception {
        resetCloses(Lane.REALTIME, true);
    }

    @Test
    void aResetOfTheWorldLaneClosesBothSidesWith0x11() throws Exception {
        resetCloses(Lane.WORLD, false);
    }

    @Test
    void aResetOfTheControlLaneClosesBothSidesWith0x11() throws Exception {
        resetCloses(Lane.CONTROL, true);
    }

    @Test
    void aStopSendingOnAnAuxiliaryLaneClosesBothSidesWith0x11OnTheNextWrite() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            channel(rig.client, Lane.REALTIME).shutdownInput(0x43);
            sleep(300);
            rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "after-stop"));
            closedWith(rig.serverGame, 0x11, false);
            closedWith(rig.clientGame, 0x11, true);
        }
    }

    @Test
    void anUnexpectedFinOnAnyLaneClosesBothSidesWith0x11() throws Exception {
        for (Lane lane : Lane.values()) {
            try (LaneRig rig = LaneRig.start()) {
                rig.activateBoth();
                roundTrip(rig);
                channel(rig.server, lane).shutdownOutput();
                closedWith(rig.clientGame, 0x11, false);
                closedWith(rig.serverGame, 0x11, true);
            }
        }
    }

    @Test
    void endAfterQueuedRecordsClosesBothSidesWith0x0() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.serverGame.packetCount();
            rig.client.holdWrites(Lane.REALTIME);
            rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            rig.client.end();
            assertPending(rig.server, 1);
            sleep(500);
            assertFalse(rig.serverGame.closed.isDone(), "END is a full fence behind the withheld move");
            rig.client.releaseWrites(Lane.REALTIME);
            closedWith(rig.serverGame, 0x0, false);
            closedWith(rig.clientGame, 0x0, true);
            assertEquals(Arrays.asList("spawn", "move"), rig.serverGame.labels().subList(base, base + 2));
            // Sends after END are dropped without harm.
            SentPacket late = rig.client.eventLoop().submit(
                    () -> rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "late"))).get();
            assertFalse(late.isAssigned());
        }
    }

    @Test
    void theEndSenderClosesAfterTheLingerWhenThePeerNeverDoes() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.serverSession = false;
        config.clientTiming = new LaneSession.Timing(System::nanoTime, LaneSession.OPEN_TIMEOUT_NANOS,
                TimeUnit.MILLISECONDS.toNanos(400), TimeUnit.MILLISECONDS.toNanos(20));
        try (LaneRig rig = LaneRig.start(config)) {
            rig.client.activate(null);
            long start = System.nanoTime();
            rig.client.end();
            closedWith(rig.clientGame, 0x0, false);
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(350), "waited for the peer");
            sawClose(rig.net.serverSawClose, 0x0);
        }
    }

    @Test
    void anEndWrittenOnlyAfterAReleaseStillClosesAfterTheLinger() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.serverSession = false;
        config.clientTiming = new LaneSession.Timing(System::nanoTime, LaneSession.OPEN_TIMEOUT_NANOS,
                TimeUnit.MILLISECONDS.toNanos(400), TimeUnit.MILLISECONDS.toNanos(20));
        try (LaneRig rig = LaneRig.start(config)) {
            rig.client.activate(null);
            // A raw server opens both of its directions, so no open deadline keeps the tick alive.
            LaneLoopback.write(rig.serverRawStreams.poll(10, TimeUnit.SECONDS), (byte) 2, (byte) 1);
            QuicChannel serverConnection = rig.net.serverConnection.get(10, TimeUnit.SECONDS);
            LaneLoopback.write(serverConnection.createStream(
                    io.netty.handler.codec.quic.QuicStreamType.BIDIRECTIONAL,
                    new io.netty.channel.ChannelInboundHandlerAdapter()).get(10, TimeUnit.SECONDS),
                    (byte) 2, (byte) 2);
            sleep(500);
            rig.client.holdWrites(Lane.CONTROL);
            rig.client.end();
            sleep(700);
            assertFalse(rig.clientGame.closed.isDone(), "the linger starts when END is written");
            long start = System.nanoTime();
            rig.client.releaseWrites(Lane.CONTROL);
            closedWith(rig.clientGame, 0x0, false);
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(350));
        }
    }

    @Test
    void endBypassesTheUnsentCapSoAFullQueueStillClosesGracefully() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            int base = rig.serverGame.packetCount();
            rig.client.holdWrites(Lane.CONTROL);
            for (int i = 0; i < LaneSession.MAX_UNSENT_PACKETS; i++) {
                rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "c" + i));
            }
            rig.client.end();
            sleep(300);
            assertFalse(rig.clientGame.closed.isDone(), "END at the cap is not an overflow");
            rig.client.releaseWrites(Lane.CONTROL);
            closedWith(rig.serverGame, 0x0, false);
            closedWith(rig.clientGame, 0x0, true);
            assertEquals(LaneSession.MAX_UNSENT_PACKETS, rig.serverGame.packetCount() - base);
        }
    }
}
