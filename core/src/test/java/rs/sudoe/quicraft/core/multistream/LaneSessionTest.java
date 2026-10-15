// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
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
            System.out.println("REPSTART");
            rig.clientGame.applyOnOtherThread = true;
            rig.serverGame.applyOnOtherThread = true;
            rig.activateBoth();
            // Both sessions must have their auxiliary lanes open before lane traffic starts: a
            // record that arrives while a lane is still being set up is refused (13.3).
            assertTrue(LaneRig.await(() -> {
                try {
                    return LaneRig.lanesOpened(rig.client) && LaneRig.lanesOpened(rig.server);
                } catch (Exception e) {
                    return false;
                }
            }, 10000), "both sessions opened their auxiliary lanes");
            rig.server.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "block"));
            rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "c-move"));
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 3
                    && rig.serverGame.packetCount() == 1, 10000),
                    "client=" + rig.clientGame.packetCount() + rig.clientGame.deliveries.toString()
                            + " " + rig.clientGame.closed.getNow(null) + " server=" + rig.serverGame.packetCount()
                            + " " + rig.serverGame.closed.getNow(null));
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
                rig.activate(rig.server, null);
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
            rig.activate(rig.server, null);
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
    void aQueuedRecordCarriesItsNodeChargeUntilTheGameAppliesIt() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(3L << 20);
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.activateBoth();
            rig.clientGame.autoApply = false;
            sleep(300);
            long baseline = node.reserved();
            byte[] frame = LaneRig.frame(LaneRig.BLOCK, "held");
            int wireBytes = RecordCodec.HEADER_LENGTH + frame.length + 1;
            rig.server.send(PLAY, LaneRig.BLOCK, frame);
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 1, 10000));
            sleep(300);
            // The transport chunk is gone; what remains is the queued record's own charge.
            assertTrue(node.reserved() >= baseline + wireBytes,
                    "the queued record stays charged: " + node.reserved() + " against " + baseline);
            assertFalse(rig.clientGame.closed.isDone(),
                    "a delivered record must not underflow the budget: " + rig.clientGame.closed.getNow(null));
            rig.clientGame.apply(rig.clientGame.deliveries.get(0));
            assertTrue(LaneRig.await(() -> node.reserved() % LaneSession.READ_CHUNK == 0, 10000),
                    "applying releases the record charge: " + node.reserved());
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    @Test
    void theActivationLeftoverIsChargedAndReleasedWithTheNodeBudget() throws Exception {
        // A budget that records any release of bytes it never had: an uncharged leftover chunk
        // released by the lane would show up here instead of silently shifting the accounting.
        StrictBudget node = new StrictBudget(3L << 20);
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        config.clientSession = false;
        config.serverTiming = new LaneSession.Timing(() -> 5_000_000_000L, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        try (LaneRig rig = LaneRig.start(config)) {
            // One byte of a two-byte record length: it stays buffered as an incomplete record.
            rig.activate(rig.server, java.nio.ByteBuffer.wrap(new byte[] {(byte) 0x85}));
            assertTrue(LaneRig.await(() -> {
                try {
                    return LaneRig.snapshot(rig.server)[0] == 1;
                } catch (Exception e) {
                    return false;
                }
            }, 10000), "the leftover starts an incomplete record");
            assertTrue(node.reserved() > 0, "the held leftover is charged: " + node.reserved());
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000),
                "closing releases every charge: " + node.reserved());
        assertEquals(0, node.releasesWithoutReservation,
                "no byte was released that was never reserved");
    }

    /** A bounded budget that counts releases of bytes it never reserved. */
    private static final class StrictBudget extends ReceiveBudget {
        private final ReceiveBudget delegate;
        private final AtomicLong reserved = new AtomicLong();
        volatile int releasesWithoutReservation;

        StrictBudget(long capacity) {
            this.delegate = ReceiveBudget.bounded(capacity);
        }

        @Override
        public boolean tryReserve(long bytes) {
            if (delegate.tryReserve(bytes)) {
                reserved.addAndGet(bytes);
                return true;
            }
            return false;
        }

        @Override
        public void release(long bytes) {
            if (reserved.addAndGet(-bytes) < 0) {
                releasesWithoutReservation++;
                reserved.addAndGet(bytes);
                return;
            }
            delegate.release(bytes);
        }

        @Override
        public long reserved() {
            return delegate.reserved();
        }

        @Override
        public long available() {
            return delegate.available();
        }
    }

    @Test
    void anActivatedLaneReservesItsReadGrantBeforeReading() throws Exception {
        long capacity = 3L << 20;
        ReceiveBudget node = ReceiveBudget.bounded(capacity);
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        config.clientSession = false;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.server.activate(null);
            // 13.12/13.13: the bytes a read can carry are reserved before the read, so no legal
            // record's chunk can be refused at the node level.
            assertTrue(LaneRig.await(() -> node.available() <= capacity - LaneSession.READ_CHUNK, 10000),
                    "the read grant is reserved up front: " + node.available());
        }
    }

    @Test
    void theSessionStillClosesWhenReleasingItsNodeBudgetFails() throws Exception {
        // A budget whose releases always fail, as broken or exhausted accounting would. The
        // failure lands inside shutdown, which must still finish and tell the game that the
        // connection ended instead of letting the exception escape.
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = new FailingReleaseBudget(ReceiveBudget.bounded(3L << 20));
        config.clientSession = false;
        LaneRig rig = LaneRig.start(config);
        try {
            rig.activate(rig.server, java.nio.ByteBuffer.wrap(new byte[] {(byte) 0x85}));
            assertTrue(LaneRig.await(() -> rig.serverGame.closed.isDone(), 10000),
                    "a failing release must not swallow the close notification");
        } finally {
            rig.close();
        }
    }

    /** Delegates every reservation but throws on every release, as a broken budget would. */
    private static final class FailingReleaseBudget extends ReceiveBudget {
        private final ReceiveBudget delegate;

        FailingReleaseBudget(ReceiveBudget delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean tryReserve(long bytes) {
            return delegate.tryReserve(bytes);
        }

        @Override
        public void release(long bytes) {
            throw new IllegalStateException("release " + bytes + " fails");
        }

        @Override
        public long reserved() {
            return delegate.reserved();
        }

        @Override
        public long available() {
            return delegate.available();
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

    /** A raw-server rig that shares {@code node} and ticks quickly. */
    private static LaneRig.Config rawServerConfig(ReceiveBudget node) {
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        config.clientSession = false;
        config.serverTiming = new LaneSession.Timing(System::nanoTime, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        return config;
    }

    /** Activates a raw-server rig and answers both preambles, returning the raw REALTIME stream. */
    private static QuicStreamChannel openRawServerLanes(LaneRig rig) throws Exception {
        rig.server.activate(null);
        LaneLoopback.write(rig.clientRawStreams.poll(10, TimeUnit.SECONDS), (byte) 2, (byte) 2);
        QuicStreamChannel four = rig.net.clientStream();
        LaneLoopback.write(four, (byte) 2, (byte) 1);
        return four;
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
    void twoAuxiliaryLanesWithSplitLengthPrefixesCannotOvercommitTheBudget() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.clientSession = false;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.server.activate(null);
            QuicStreamChannel world = rig.clientRawStreams.poll(10, TimeUnit.SECONDS);
            LaneLoopback.write(world, (byte) 2, (byte) 2);
            QuicStreamChannel realtime = rig.net.clientStream();
            LaneLoopback.write(realtime, (byte) 2, (byte) 1);
            byte[] frame = LaneRig.frame(LaneRig.MOVE, RecordCodec.MAX_INNER_CONTENT - 1);
            // Six maximum-size records leave room for one more but not for two.
            java.io.ByteArrayOutputStream six = new java.io.ByteArrayOutputStream();
            for (int i = 1; i <= 6; i++) {
                six.write(RecordCodec.encodeGame(i, 1, i - 1, 0, frame));
            }
            LaneLoopback.write(realtime, six.toByteArray());
            assertTrue(LaneRig.await(() -> snapshotIs(rig.server, 0, 6), 30000));
            byte[] seventh = RecordCodec.encodeGame(7, 1, 6, 0, frame);
            byte[] first = RecordCodec.encodeGame(1, 1, 0, 0, frame);
            // The four-byte length prefixes arrive in halves on both lanes.
            LaneLoopback.write(realtime, Arrays.copyOf(seventh, 2));
            LaneLoopback.write(world, Arrays.copyOf(first, 2));
            assertTrue(LaneRig.await(() -> snapshotIs(rig.server, 0, 8), 10000));
            LaneLoopback.write(realtime, Arrays.copyOfRange(seventh, 2, seventh.length));
            LaneLoopback.write(world, Arrays.copyOfRange(first, 2, first.length));
            sleep(1500);
            assertFalse(rig.serverGame.closed.isDone(),
                    "a legal record must wait for budget, never be fatal for lack of it");
            assertFalse(rig.net.clientSawClose.isDone());
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
    void aStopSendingOnAnIdleLaneClosesBothSidesWith0x11WithoutAWrite() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            // The client stops reading the server's REALTIME lane, and the server never writes on it
            // again: only the periodic send-capacity probe of 13.14 can notice.
            channel(rig.client, Lane.REALTIME).shutdownInput(0x43);
            closedWith(rig.serverGame, 0x11, false);
            closedWith(rig.clientGame, 0x11, true);
        }
    }

    @Test
    void aBridgeSideCloseAfterActivationIsTakenOverByTheControlEndPath() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.serverSession = false;
        config.clientTiming = new LaneSession.Timing(System::nanoTime, LaneSession.OPEN_TIMEOUT_NANOS,
                TimeUnit.MILLISECONDS.toNanos(400), TimeUnit.MILLISECONDS.toNanos(20));
        try (LaneRig rig = LaneRig.start(config)) {
            LegacyControlOwner bridge = new LegacyControlOwner();
            rig.clientControl.pipeline().addLast(bridge);
            rig.client.activate(null);
            // The raw server answers both preambles so the client's two lanes open.
            LaneLoopback.write(rig.serverRawStreams.poll(10, TimeUnit.SECONDS), (byte) 2, (byte) 1);
            QuicChannel server = rig.net.serverConnection.get(10, TimeUnit.SECONDS);
            LaneLoopback.write(server.createStream(QuicStreamType.BIDIRECTIONAL,
                    new ChannelInboundHandlerAdapter()).get(10, TimeUnit.SECONDS), (byte) 2, (byte) 2);
            // The takeover of 13.14 removes the old single-stream owner from stream 0.
            assertNull(rig.clientControl.pipeline().get(LegacyControlOwner.class),
                    "activation takes stream 0 over from the old handler");
            final LaneRig.Bytes controlBytes = new LaneRig.Bytes();
            final java.util.concurrent.atomic.AtomicBoolean fin = new java.util.concurrent.atomic.AtomicBoolean();
            rig.serverControl.pipeline().addLast(finWatch(fin));
            rig.serverControl.pipeline().addLast(controlBytes);
            // The lane path owns the close: END, never the old owner's bare FIN.
            rig.client.end();
            boolean closedPeersStream = LaneRig.await(() -> controlBytes.snapshot().length > 0, 10000);
            LaneRig.Closed clientState = rig.clientGame.closed.isDone()
                    ? rig.clientGame.closed.getNow(null) : null;
            assertTrue(closedPeersStream, "the peer sees the record stream close (client: " + clientState + ")");
            List<RecordCodec.Record> records = new RecordCodec.Decoder(true)
                    .feed(java.nio.ByteBuffer.wrap(controlBytes.snapshot()));
            assertEquals(RecordCodec.KIND_END, records.get(records.size() - 1).kind(),
                    "the taken-over stream 0 closes with END, not a bare FIN");
            assertFalse(fin.get(), "no bare FIN on the taken-over stream 0");
            closedWith(rig.clientGame, 0x0, false);
            sawClose(rig.net.serverSawClose, 0x0);
        }
    }

    private static ChannelInboundHandlerAdapter finWatch(final java.util.concurrent.atomic.AtomicBoolean fin) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof ChannelInputShutdownReadComplete) {
                    fin.set(true);
                }
                ctx.fireUserEventTriggered(evt);
            }
        };
    }

    /** A NettyQuicByteStream-like old stream-0 owner whose close sends a bare FIN. */
    private static final class LegacyControlOwner extends ChannelInboundHandlerAdapter {
        QuicStreamChannel channel;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            channel = (QuicStreamChannel) ctx.channel();
        }

        /** The path 13.14 forbids after activation: a bare FIN instead of an END fence. */
        void closeWithABareFin() {
            channel.shutdownOutput();
        }
    }

    @Test
    void anOldOwnerThatClosesStreamZeroWhileBeingRemovedStillEndsThroughTheControlPath() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            // The old single-stream owner survives the takeover in this test, then closes stream 0
            // from its own handlerRemoved as an old owner would. That close must become the lane END
            // fence (0x0 at the peer), never a bare FIN or a reset (0x11).
            ClosingOwner owner = new ClosingOwner();
            rig.clientControl.pipeline().addLast(owner);
            rig.clientControl.pipeline().remove(owner);
            assertTrue(LaneRig.await(() -> owner.removed, 10000),
                    "the owner closed stream 0 while it was removed");
            closedWith(rig.serverGame, 0x0, false);
            closedWith(rig.clientGame, 0x0, true);
        }
    }

    /** The old single-stream owner: it closes stream 0 while the takeover removes it. */
    private static final class ClosingOwner extends ChannelInboundHandlerAdapter {
        volatile boolean removed;

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            removed = true;
            ctx.close();
        }
    }

    @Test
    void anOldOwnerThatClosesStreamZeroInItsRemovalCallbackEndsWithEndNotFin() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            ClosingOwner owner = new ClosingOwner();
            rig.clientControl.pipeline().addLast(owner);
            // The takeover removes the owner while activating, and its handlerRemoved closes stream
            // 0: the lane must still end with END (0x0 at the peer), never a bare FIN (0x11).
            rig.activateBoth();
            assertTrue(LaneRig.await(() -> owner.removed, 10000), "the takeover removed the old owner");
            closedWith(rig.serverGame, 0x0, false);
            closedWith(rig.clientGame, 0x0, true);
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

    // --- Node receive budget (13.12).

    @Test
    void aConnectionWhoseRecordGrantWasRefusedResumesWhenTheNodeFreesSpace() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(6L << 20);
        // The holder keeps ~4.5 MiB of the node budget reserved while it does not apply.
        LaneRig.Config holder = new LaneRig.Config();
        holder.nodeBudget = node;
        LaneRig rigA = LaneRig.start(holder);
        try {
            rigA.activateBoth();
            rigA.clientGame.autoApply = false;
            for (int i = 0; i < 3; i++) {
                rigA.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, 1_500_000));
            }
            assertTrue(LaneRig.await(() -> node.available() < (6L << 20) - 4_000_000L, 30000),
                    "the holder charged the node budget: " + node.available());

            LaneRig.Config starved = new LaneRig.Config();
            starved.nodeBudget = node;
            starved.clientSession = false;
            try (LaneRig rigB = LaneRig.start(starved)) {
                QuicStreamChannel four = openRawServerLanes(rigB);
                byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.MOVE, 200));
                // The record arrives whole, but its node record grant cannot be taken: the lane must
                // stop before its first byte and wait, never fail.
                LaneLoopback.write(four, record);
                sleep(500);
                assertTrue(snapshotIs(rigB.server, 0, 0), "nothing is admitted while the grant is refused");
                assertFalse(rigB.serverGame.closed.isDone(), "a refused grant is not a close");
                assertFalse(rigB.serverGame.closed.isDone());

                // The holder gives its bytes back, so the starved lane must resume and deliver.
                for (int i = 0; i < 3; i++) {
                    final int index = i;
                    assertTrue(LaneRig.await(() -> rigA.clientGame.deliveries.size() > index, 10000),
                            "the holder delivered record " + index);
                    rigA.clientGame.apply(rigA.clientGame.deliveries.get(index));
                }
                assertTrue(LaneRig.await(() -> rigB.serverGame.packetCount() == 1, 10000),
                        "the lane resumes and delivers its record");
                assertFalse(rigB.serverGame.closed.isDone(), "resuming is not a close");
                assertFalse(rigA.clientGame.closed.isDone(), "the holder is never evicted");
            }
        } finally {
            rigA.close();
        }
    }

    @Test
    void aRecordWhoseGrantWasTakenBeforeTheNodeFilledUpIsStillAdmitted() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(6L << 20);
        LaneRig rigB = LaneRig.start(rawServerConfig(node));
        try {
            QuicStreamChannel four = openRawServerLanes(rigB);
            byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.MOVE, 2_000_000));
            // Only the first byte of the two-byte length prefix: the record boundary already took
            // its node grant, so the node can be filled up while its real size is unknown.
            LaneLoopback.write(four, record[0]);
            assertTrue(LaneRig.await(() -> snapshotIs(rigB.server, 0, 1), 10000), "the record started");

            LaneRig.Config holder = new LaneRig.Config();
            holder.nodeBudget = node;
            LaneRig rigA = LaneRig.start(holder);
            try {
                rigA.activateBoth();
                rigA.clientGame.autoApply = false;
                // rigB's record boundary already holds a MAX_RECORD_WIRE grant, so this fills the rest.
                for (int i = 0; i < 2; i++) {
                    rigA.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, 1_900_000));
                }
                assertTrue(LaneRig.await(() -> node.available() < 800_000L, 8000),
                        "the node is nearly full: " + node.available());
                // The rest of the legal record arrives with almost no node space left: the grant
                // taken at its boundary must carry it, never 0x11.
                LaneLoopback.write(four, java.util.Arrays.copyOfRange(record, 1, record.length));
                assertTrue(LaneRig.await(() -> rigB.serverGame.packetCount() == 1, 30000),
                        "a legal record is admitted from its own grant");
                assertFalse(rigB.serverGame.closed.isDone(), "node contention is not fatal for a legal record");
                assertFalse(rigA.clientGame.closed.isDone(), "the holder is never evicted");
            } finally {
                rigA.close();
            }
        } finally {
            rigB.close();
        }
    }

    @Test
    void aDecodeWhoseGrowthCannotBeReservedWaitsInsteadOfClosing() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(14L << 20);
        LaneRig.Config holder = new LaneRig.Config();
        holder.nodeBudget = node;
        LaneRig rigA = LaneRig.start(holder);
        try {
            rigA.activateBoth();
            rigA.clientGame.autoApply = false;
            for (int i = 0; i < 6; i++) {
                rigA.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, 1_900_000));
            }
            assertTrue(LaneRig.await(() -> node.available() < 4_000_000L, 8000),
                    "the holder charged the node budget: " + node.available());

            LaneRig.Config receiver = new LaneRig.Config();
            receiver.nodeBudget = node;
            try (LaneRig rig = LaneRig.start(receiver)) {
                rig.activateBoth();
                rig.clientGame.decodeReserveBytes = 5_000_000L; // more than the node can carry
                rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "grow"));
                sleep(500);
                assertEquals(0, rig.clientGame.packetCount(), "the decode waits for node space");
                assertFalse(rig.clientGame.closed.isDone(), "a refused decode growth is not a close");

                for (int i = 0; i < 6; i++) {
                    final int index = i;
                    assertTrue(LaneRig.await(() -> rigA.clientGame.deliveries.size() > index, 9000),
                            "the holder delivered record " + index);
                    rigA.clientGame.apply(rigA.clientGame.deliveries.get(index));
                }
                assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 1, 20000),
                        "the withheld decode resumes once the node has room");
                assertFalse(rig.clientGame.closed.isDone(), "resuming is not a close");
            }
        } finally {
            rigA.close();
        }
    }

    @Test
    void readChunksAndParserBuffersAreChargedToTheNodeBudgetUntilTheyAreConsumed() throws Exception {
        // The budget must leave room for one 16 KiB read plus the largest legal record, otherwise
        // no lane may start reading at all (13.12/13.13: never grant credit beyond the budget).
        ReceiveBudget node = ReceiveBudget.bounded(3L << 20);
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        config.deferClientGame = true;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.server.activate(null);
            rig.client.activate(null);
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "buffered"));
            assertTrue(LaneRig.await(() -> node.reserved() > 0, 10000),
                    "the unparsed chunk is charged while it is held: " + node.reserved());
            sleep(300);
            assertEquals(0, rig.clientGame.packetCount(), "nothing is delivered before the game is bound");
            assertEquals(0, LaneRig.snapshot(rig.client)[0], "nothing is parsed before the game is bound");
            rig.client.bindGame(rig.clientGame);
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 1, 10000));
            assertEquals("buffered", rig.clientGame.labels().get(0));
            // Read grants are whole READ_CHUNKs, so an empty remainder means the consumed chunk
            // and the applied record gave every charge back.
            assertTrue(LaneRig.await(() -> node.reserved() % LaneSession.READ_CHUNK == 0, 10000),
                    "consumed chunks and applied records release every charge: " + node.reserved());
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    // --- Deferred game binding (13.12).

    @Test
    void aGameBoundBeforeActivationRunsTheSessionNormally() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.deferClientGame = true;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.client.bindGame(rig.clientGame);
            rig.activateBoth();
            roundTrip(rig);
            assertFalse(rig.clientGame.closed.isDone());
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    @Test
    void bindingTheGameTwiceIsRejected() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.deferClientGame = true;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.client.bindGame(rig.clientGame);
            assertThrows(IllegalStateException.class, () -> rig.client.bindGame(rig.clientGame));
        }
    }

    // --- Bundles (13.15).

    @Test
    void aBundleBeyondTheSenderBoundsConsumesNoSequence() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            long[] before = LaneRig.assigned(rig.client);
            // A member frame the record codec rejects: no sequence may be handed out for any part.
            List<SentPacket> rejected = rig.client.sendBundle(
                    new OutboundPacket(LaneRig.frame(LaneRig.BUNDLE_OPEN, "open")),
                    Arrays.asList(new OutboundPacket(new byte[0])),
                    new OutboundPacket(LaneRig.frame(LaneRig.BUNDLE_CLOSE, "close")));
            closedWith(rig.clientGame, 0x11, false);
            closedWith(rig.serverGame, 0x11, true);
            assertArrayEquals(before, LaneRig.assigned(rig.client),
                    "a rejected bundle leaves the sequencer where it was");
            for (SentPacket packet : rejected) {
                assertFalse(packet.isAssigned(), "an unemittable bundle consumes no sequence");
            }
        }
    }

    @Test
    void theStopSendingProbeResolvesAgainstThePinnedNetty() throws Exception {
        assertTrue(LaneSession.stopSendingErrCode() != Integer.MIN_VALUE,
                "the pinned Netty must expose QUICHE_ERR_STREAM_STOPPED");
        assertTrue(LaneSession.stopSendingErrCode() < 0,
                "QUICHE_ERR_STREAM_STOPPED is a negative quiche error: " + LaneSession.stopSendingErrCode());
        Class<?> implementation = Class.forName(
                io.netty.handler.codec.quic.QuicStreamChannel.class.getName()
                        .replace("QuicStreamChannel", "QuicheQuicChannel"));
        assertNotNull(LaneSession.findStreamCapacity(implementation),
                "the pinned Netty must expose the stream send capacity of " + implementation.getName());
    }

    @Test
    void aBundleOverTheMemberBoundIsRejectedBeforeSequencing() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            roundTrip(rig);
            List<OutboundPacket> members = new ArrayList<>();
            for (int i = 0; i <= SenderSequencer.MAX_BUNDLE_MEMBERS; i++) {
                members.add(new OutboundPacket(LaneRig.frame(LaneRig.MOVE, "m" + i)));
            }
            List<SentPacket> rejected = rig.client.sendBundle(
                    new OutboundPacket(LaneRig.frame(LaneRig.BUNDLE_OPEN, "open")), members,
                    new OutboundPacket(LaneRig.frame(LaneRig.BUNDLE_CLOSE, "close")));
            closedWith(rig.clientGame, 0x11, false);
            closedWith(rig.serverGame, 0x11, true);
            for (SentPacket packet : rejected) {
                assertFalse(packet.isAssigned(), "an oversize bundle consumes no sequence");
            }
        }
    }
}
