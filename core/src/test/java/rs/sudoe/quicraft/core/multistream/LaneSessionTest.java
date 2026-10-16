// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
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
            rig.clientGame.applyOnOtherThread = true;
            rig.serverGame.applyOnOtherThread = true;
            rig.activateBoth();
            rig.server.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "spawn"));
            rig.server.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "move"));
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "block"));
            rig.client.send(PLAY, LaneRig.MOVE, LaneRig.frame(LaneRig.MOVE, "c-move"));
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 3
                    && rig.serverGame.packetCount() == 1, 10000),
                    "client=" + rig.clientGame.packetCount() + rig.clientGame.deliveries.toString()
                            + " " + rig.clientGame.closed.getNow(null) + " server=" + rig.serverGame.packetCount()
                            + " " + rig.serverGame.closed.getNow(null) + " " + rig.serverGame.closeCause);
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

    /**
     * 13.13: the serverbound connection window equals the three pinned 8 KiB stream windows (24 KiB),
     * so a record far larger than that must still arrive intact. The window replenishes as the server
     * reads; a connection window that never grew past its first 24 KiB would deadlock here.
     */
    @Test
    void aServerbound100KiBControlRecordArrivesIntactThroughThe24KiBConnectionWindow() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            byte[] payload = new byte[100 * 1024];
            Arrays.fill(payload, (byte) 'a');
            rig.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, payload));
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() >= 1, 20000),
                    "the 100 KiB serverbound record arrived: " + rig.serverGame.closed.getNow(null) + " " + rig.serverGame.closeCause);
            List<String> labels = rig.serverGame.labels();
            assertEquals(payload.length, labels.get(0).length(), "the whole record arrived");
            assertTrue(labels.get(0).chars().allMatch(c -> c == 'a'), "the record arrived intact");
        }
    }

    @Test
    void theServerOpensStreamOneWithTheWorldPreambleAndTheClientStreamFourWithTheRealtimePreamble() throws Exception {
        LaneRig.Config serverOnly = new LaneRig.Config();
        serverOnly.clientSession = false;
        try (LaneRig rig = LaneRig.start(serverOnly)) {
            rig.activate(rig.server, null);
            QuicStreamChannel one = rig.clientRawStreams.poll(10, TimeUnit.SECONDS);
            assertEquals(1, one.streamId());
            assertTrue(LaneRig.await(() -> rig.clientRawBytes.get(0).snapshot().length >= 2, 10000));
            assertArrayEquals(new byte[] {2, 2}, Arrays.copyOf(rig.clientRawBytes.get(0).snapshot(), 2));
        }
        LaneRig.Config clientOnly = new LaneRig.Config();
        clientOnly.serverSession = false;
        try (LaneRig rig = LaneRig.start(clientOnly)) {
            rig.activateClient();
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
            rig.activate(rig.server, null);
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
            rig.activateClient();
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
            rig.activateClient();
            closedWith(rig.serverGame, 0x11, false);
            closedWith(rig.clientGame, 0x11, true);
            assertEquals(0, rig.serverGame.deliveries.size());
        }
    }

    @Test
    void aClientHoldsEarlyStreamOneBytesUndeliveredUntilItsOwnActivation() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activate(rig.server, null);
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "early"));
            sleep(400);
            assertEquals(0, rig.clientGame.packetCount(), "nothing is delivered before the client's activation");
            assertFalse(rig.clientGame.closed.isDone());
            rig.activateClient();
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 1, 10000));
            assertEquals("early", rig.clientGame.labels().get(0));
        }
    }

    @Test
    void aControlRecordReadBeforeTheClientsActivationIsDeliveredFromTheLeftover() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activate(rig.server, null);
            byte[] frame = LaneRig.frame(LaneRig.SPAWN, "early-control");
            // 12.12: the server may start records right after queuing SERVER_SWITCH, so this record
            // can reach the client while stream 0 still belongs to the code that precedes activation.
            rig.server.send(PLAY, LaneRig.SPAWN, frame);
            int wire = 1 + RecordCodec.HEADER_LENGTH + frame.length;
            assertTrue(LaneRig.await(() -> rig.earlyControlRetained() >= wire, 10000),
                    "the pre-activation owner read the record: " + rig.earlyControlRetained());
            assertEquals(0, rig.clientGame.packetCount(), "nothing is delivered before the client's activation");
            assertFalse(rig.clientGame.closed.isDone());
            // Activation hands what that owner read over as the leftover, so the record is parsed
            // rather than lost: a lost CONTROL record would hold the whole lane set behind its
            // fence (13.5) until the 13.12 blocked-head deadline.
            rig.activateClient();
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 1, 10000),
                    "the record is parsed from the activation leftover: " + rig.clientGame.deliveries);
            assertEquals("early-control", rig.clientGame.labels().get(0));
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "after"));
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 2, 10000),
                    "the fence is satisfied and the next record follows");
            assertFalse(rig.clientGame.closed.isDone());
            assertFalse(rig.serverGame.closed.isDone());
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
            rig.activateClient();
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
            rig.activate(rig.server, null);
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
            rig.activateClient();
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
            assertTrue(LaneRig.await(() -> node.reserved() == baseline, 10000),
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

    @Test
    void anActivationLeftoverIsAdmittedEvenWhenTheNodeBudgetIsExhausted() throws Exception {
        // The idle value of an activated session with nothing to carry, as the reference.
        ReceiveBudget reference = ReceiveBudget.bounded(512 * 1024);
        LaneRig.Config referenceConfig = new LaneRig.Config();
        referenceConfig.nodeBudget = reference;
        long idle;
        try (LaneRig rig = LaneRig.start(referenceConfig)) {
            rig.activate(rig.server, null);
            rig.activateClient();
            assertTrue(LaneRig.await(() -> reference.reserved() > 0, 10000));
            sleep(500);
            idle = reference.reserved();
        }
        StrictBudget node = new StrictBudget(512 * 1024);
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        final long expectedIdle = idle;
        try (LaneRig rig = LaneRig.start(config)) {
            long held = node.available();
            assertTrue(node.tryReserve(held));
            assertEquals(0, node.available());
            byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.SPAWN, "leftover"));
            rig.activate(rig.server, java.nio.ByteBuffer.wrap(record));
            rig.activateClient();
            sleep(300);
            assertFalse(rig.serverGame.closed.isDone(), "bytes that already arrived are never refused locally");
            assertTrue(node.reserved() >= held + record.length, "the leftover is carried as an overdraft: " + node.reserved());
            node.release(held);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == 1, 10000));
            assertTrue(LaneRig.await(() -> node.reserved() == expectedIdle, 10000),
                    "accounting returns to the idle value " + expectedIdle + ": " + node.reserved() + " " + rig.serverGame.closed.getNow(null) + " " + rig.serverGame.closeCause);
            assertFalse(rig.serverGame.closed.isDone(), () -> String.valueOf(rig.serverGame.closed.getNow(null)));
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000), "closing releases every charge: " + node.reserved());
        assertEquals(0, node.releasesWithoutReservation);
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
        public boolean forceReserve(long bytes) {
            delegate.forceReserve(bytes);
            reserved.addAndGet(bytes);
            return true;
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
            rig.activate(rig.server, null);
            // 13.12/13.13: the bytes a read can carry are reserved before the read, so no legal
            // record's chunk can be refused at the node level.
            assertTrue(LaneRig.await(() -> node.available() <= capacity - LaneSession.READ_CHUNK_MIN, 10000),
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
            rig.activate(rig.server, null);
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
        rig.activate(rig.server, null);
        LaneLoopback.write(rig.clientRawStreams.poll(10, TimeUnit.SECONDS), (byte) 2, (byte) 2);
        QuicStreamChannel four = rig.net.clientStream();
        LaneLoopback.write(four, (byte) 2, (byte) 1);
        return four;
    }

    /**
     * Fills {@code node} the way a peer's data does: {@code count} whole records written raw into a
     * (session-less client) rig's REALTIME lane, where the receiver holds them because its game
     * never applies them. Nothing here goes through a sender, so the charge is exactly the retained
     * records, whatever the node budget does on the send side of other connections.
     */
    private static LaneRig holdRawRecords(ReceiveBudget node, int frameBytes, int count) throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        config.clientSession = false;
        config.serverTiming = new LaneSession.Timing(System::nanoTime, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        LaneRig rig = LaneRig.start(config);
        rig.serverGame.autoApply = false;
        openRawServerLanes(rig);
        for (int i = 1; i <= count; i++) {
            // CONTROL records have no unprovided auxiliary predecessor, and each carries the
            // previous CONTROL sequence as its own dependency.
            LaneLoopback.write(rig.clientControl,
                    RecordCodec.encodeGame(i, i - 1, 0, 0, LaneRig.frame(LaneRig.SPAWN, frameBytes)));
        }
        return rig;
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
            rig.activate(rig.server, null);
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
            rig.activate(rig.server, null);
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
        // The END linger must outlast a loopback retransmit, so one dropped datagram cannot make
        // this test see no END at all (production lingers for 10 s, 13.14).
        config.clientTiming = new LaneSession.Timing(System::nanoTime, LaneSession.OPEN_TIMEOUT_NANOS,
                TimeUnit.SECONDS.toNanos(3), TimeUnit.MILLISECONDS.toNanos(20));
        try (LaneRig rig = LaneRig.start(config)) {
            LegacyControlOwner bridge = new LegacyControlOwner();
            installOnLoop(rig.clientControl, bridge);
            rig.activateClient();
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
            // Installed on the peer's loop and awaited, so both collectors are complete before the
            // END is written: an off-loop pipeline change adds a handler's context at once but runs
            // its handlerAdded() later, on that loop.
            installOnLoop(rig.serverControl, finWatch(fin));
            installOnLoop(rig.serverControl, controlBytes);
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

    /** Adds a handler to a channel's pipeline on that channel's loop, then waits for it. */
    private static void installOnLoop(QuicStreamChannel channel,
            io.netty.channel.ChannelHandler handler) throws Exception {
        channel.eventLoop().submit(() -> channel.pipeline().addLast(handler)).get(10, TimeUnit.SECONDS);
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
            rig.activateClient();
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
            rig.activateClient();
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

    // --- Malformed records and frames (13.4).

    @Test
    void aPeerRecordWithAMismatchedInnerFrameLengthClosesBothSidesWith0x11() throws Exception {
        LaneRig.Config config = new LaneRig.Config();
        config.clientSession = false;
        try (LaneRig rig = LaneRig.start(config)) {
            QuicStreamChannel four = openRawServerLanes(rig);
            // record_length 35, kind GAME, sequence 1, no dependencies, and a body whose inner
            // frame length claims 5 bytes while one content byte follows (13.4).
            LaneLoopback.write(four, record(new byte[] {0x05, 0x01}));
            closedWith(rig.serverGame, 0x11, false);
            sawClose(rig.net.clientSawClose, 0x11);
            assertEquals(0, rig.serverGame.deliveries.size());
        }
    }

    @Test
    void aSubmittedFrameThatIsNotAGameBodyClosesBothSidesWith0x11() throws Exception {
        try (LaneRig rig = LaneRig.start()) {
            rig.activateBoth();
            // An adapter that relays what its own peer sent (a proxy) submits frames it did not
            // build itself, so a frame that is not one 13.4 inner frame is peer input: the sender
            // closes 0x11, exactly like a bundle part that fails the same check (13.15).
            rig.server.send(PLAY, LaneRig.SPAWN, new byte[] {0x05, 0x01});
            closedWith(rig.serverGame, 0x11, false);
            closedWith(rig.clientGame, 0x11, true);
            sawClose(rig.net.clientSawClose, 0x11);
        }
    }

    /**
     * A hand-built GAME record with sequence 1 and no dependencies (13.4): a one-byte
     * record_length prefix, kind, sequence, three zero dependencies and {@code body}. Hand-built
     * because {@link RecordCodec#encodeGame} validates the body and refuses to encode this one.
     */
    private static byte[] record(byte[] body) {
        int recordLength = RecordCodec.HEADER_LENGTH + body.length;
        byte[] out = new byte[1 + recordLength];
        out[0] = (byte) recordLength;
        out[1] = RecordCodec.KIND_GAME;
        out[9] = 1; // sequence 1: the last byte of the big-endian u64 at offsets 2..9
        System.arraycopy(body, 0, out, 1 + RecordCodec.HEADER_LENGTH, body.length);
        return out;
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
    void aLaneThatResumedIsNotClosedByTheWaitItRecoveredFrom() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(6L << 20);
        AtomicLong clock = new AtomicLong(5_000_000_000L);
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
            starved.serverTiming = new LaneSession.Timing(clock::get, LaneSession.OPEN_TIMEOUT_NANOS,
                    LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
            try (LaneRig rigB = LaneRig.start(starved)) {
                QuicStreamChannel four = openRawServerLanes(rigB);
                // A whole record with a complete length prefix arrives, but its node record grant
                // cannot be taken: the lane holds the bytes and waits.
                byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.MOVE, 200));
                LaneLoopback.write(four, record);
                sleep(300);
                assertTrue(snapshotIs(rigB.server, 0, 0), "nothing is admitted while the grant is refused");

                // The holder gives its bytes back, so the lane resumes and delivers from the bytes it
                // was already holding: the wait it recovered from is over.
                for (int i = 0; i < 3; i++) {
                    final int index = i;
                    assertTrue(LaneRig.await(() -> rigA.clientGame.deliveries.size() > index, 10000),
                            "the holder delivered record " + index);
                    rigA.clientGame.apply(rigA.clientGame.deliveries.get(index));
                }
                assertTrue(LaneRig.await(() -> rigB.serverGame.packetCount() == 1, 10000),
                        "the lane resumes and delivers its record");

                // Past the deadline that wait would have hit: a lane that resumed is healthy, so
                // nothing may close it (13.12).
                clock.addAndGet(ReceiverScheduler.TIMEOUT_NANOS + 1_000_000_000L);
                sleep(300);
                assertFalse(rigB.serverGame.closed.isDone(),
                        "a resumed lane is not closed by the wait it recovered from: "
                                + rigB.serverGame.closed.getNow(null));
                assertFalse(rigA.clientGame.closed.isDone(), "the holder is never evicted");
            }
        } finally {
            rigA.close();
        }
    }

    @Test
    void localParserRefusalBeyondTheDeadlineRecoversWithoutPeerMisconduct() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(6L << 20);
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        LaneRig.Config starved = new LaneRig.Config();
        starved.nodeBudget = node;
        starved.clientSession = false;
        starved.serverTiming = new LaneSession.Timing(clock::get, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        try (LaneRig rigB = LaneRig.start(starved)) {
            QuicStreamChannel four = openRawServerLanes(rigB);

            // Another connection of the same node holds space, so the record grant cannot be taken.
            LaneRig holder = holdRawRecords(node, 1_500_000, 3);
            try {
                assertTrue(LaneRig.await(() -> node.available() < (6L << 20) - 4_000_000L, 30000),
                        "the holder charged the node budget: " + node.available());
                // A local allocation refusal is not evidence of peer inactivity.
                LaneLoopback.write(four, RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.MOVE, 2_000_000)));
                sleep(500);
                assertEquals(0, rigB.serverGame.packetCount(), "no record is delivered while allocation is refused");
                assertFalse(rigB.serverGame.closed.isDone(), "waiting is not a close by itself");
                clock.addAndGet(ReceiverScheduler.TIMEOUT_NANOS + 1_000_000_000L);
                rigB.server.eventLoop().submit(rigB.server::tick).get(10, TimeUnit.SECONDS);
                assertFalse(rigB.serverGame.closed.isDone(), "local parser contention must pause the deadline");
                assertFalse(holder.serverGame.closed.isDone(), "the holder is never evicted");
            } finally {
                holder.close();
            }
            assertTrue(LaneRig.await(() -> rigB.serverGame.packetCount() == 1, 30000),
                    "the legal record resumes after local pressure ends");
            assertFalse(rigB.serverGame.closed.isDone());
        }
    }

    private static LaneRig.Config clockedRawServer(ReceiveBudget node, AtomicLong clock) {
        LaneRig.Config config = rawServerConfig(node);
        config.serverTiming = new LaneSession.Timing(clock::get, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        return config;
    }

    @Test
    void aWaitThatClearedLeavesThePeersStallToItsOwnDeadline() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(512 * 1024);
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        try (LaneRig rig = LaneRig.start(clockedRawServer(node, clock))) {
            openRawServerLanes(rig);
            long held = node.available();
            assertTrue(node.tryReserve(held));
            byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.SPAWN, 200_000));
            LaneLoopback.write(rig.clientControl, Arrays.copyOfRange(record, 0, 100));
            sleep(300);
            clock.addAndGet(5_000_000_000L);
            sleep(100);
            assertFalse(rig.serverGame.closed.isDone(), "the local wait pauses the deadline");
            node.release(held);
            assertTrue(LaneRig.await(() -> snapshotIs(rig.server, 0, 1) && node.available() < 330_000, 10000),
                    "the allocation was granted once the node had room: " + node.available());
            // The peer now stalls with the rest of the record unsent: its own deadline runs again,
            // from the moment the local wait ended.
            clock.addAndGet(9_000_000_000L);
            sleep(200);
            assertFalse(rig.serverGame.closed.isDone(), "not yet 10 seconds after the wait ended");
            clock.addAndGet(2_000_000_000L);
            closedWith(rig.serverGame, 0x11, false);
        }
    }

    @Test
    void peersThatStallInsideChargedRecordsAreClosedWhileTheNodeIsFullAndHonestConnectionsRecover() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(6L << 20);
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        try (LaneRig attacker = LaneRig.start(clockedRawServer(node, clock));
                LaneRig honest = LaneRig.start(clockedRawServer(node, clock))) {
            openRawServerLanes(attacker);
            openRawServerLanes(honest);
            byte[] big = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.SPAWN, 1_500_000));
            LaneLoopback.write(attacker.clientControl, Arrays.copyOfRange(big, 0, 200));
            assertTrue(LaneRig.await(() -> snapshotIs(attacker.server, 0, 1), 10000), "the record is charged");
            sleep(300);
            // The node fills up (other connections), then the attacker sends one more fragment and
            // stalls: its next read must not depend on fresh node bytes.
            long held = node.available();
            assertTrue(node.tryReserve(held));
            LaneLoopback.write(attacker.clientControl, Arrays.copyOfRange(big, 200, 400));
            sleep(300);
            byte[] ok = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.SPAWN, 500_000));
            LaneLoopback.write(honest.clientControl, ok);
            sleep(300);
            assertEquals(0, honest.serverGame.packetCount(), "the honest record waits for node room");
            clock.addAndGet(ReceiverScheduler.TIMEOUT_NANOS + 1_000_000_000L);
            closedWith(attacker.serverGame, 0x11, false);
            assertTrue(LaneRig.await(() -> honest.serverGame.packetCount() == 1, 30000),
                    "the honest connection recovers when the attacker's charge is released: "
                            + honest.serverGame.closed.getNow(null));
            assertFalse(honest.serverGame.closed.isDone());
            node.release(held);
        }
    }

    @Test
    void aConnectionWaitingOnItsNodeForLongerThanTheCapClosesLocallyNotWith0x11() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(512 * 1024);
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        try (LaneRig rig = LaneRig.start(clockedRawServer(node, clock))) {
            openRawServerLanes(rig);
            long held = node.available();
            assertTrue(node.tryReserve(held));
            byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.SPAWN, 200_000));
            LaneLoopback.write(rig.clientControl, Arrays.copyOfRange(record, 0, 100));
            sleep(300);
            clock.addAndGet(20_000_000_000L);
            sleep(300);
            assertFalse(rig.serverGame.closed.isDone(), "within the cap the wait only pauses the deadline");
            clock.addAndGet(11_000_000_000L);
            closedWith(rig.serverGame, LaneSession.CLOSE_INTERNAL_ERROR, false);
            node.release(held);
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000), "closing returns every charge");
    }

    @Test
    void anActivationLeftoverBeyondTheOverdraftBoundIsALocalFailure() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(1 << 20);
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.activate(rig.server, java.nio.ByteBuffer.wrap(new byte[64 * 1024 + 1]));
            closedWith(rig.serverGame, LaneSession.CLOSE_INTERNAL_ERROR, false);
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000), "nothing stays charged");
    }

    @Test
    void locallyWithheldPreamblesCanOpenAfterTheOpenDeadline() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(512 * 1024);
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        LaneRig.Config config = rawServerConfig(node);
        config.serverTiming = new LaneSession.Timing(clock::get, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        try (LaneRig rig = LaneRig.start(config)) {
            long held = node.available();
            assertTrue(node.tryReserve(held));
            openRawServerLanes(rig);
            rig.server.eventLoop().submit(rig.server::tick).get(10, TimeUnit.SECONDS);
            clock.addAndGet(LaneSession.OPEN_TIMEOUT_NANOS + TimeUnit.SECONDS.toNanos(1));
            rig.server.eventLoop().submit(rig.server::tick).get(10, TimeUnit.SECONDS);
            assertFalse(rig.serverGame.closed.isDone(), "locally withheld preambles are not late peer streams");
            node.release(held);
            assertTrue(LaneRig.await(() -> node.reserved() == 3 * LaneSession.READ_CHUNK_MIN, 10000));
            clock.addAndGet(LaneSession.OPEN_TIMEOUT_NANOS + TimeUnit.SECONDS.toNanos(1));
            rig.server.eventLoop().submit(rig.server::tick).get(10, TimeUnit.SECONDS);
            assertFalse(rig.serverGame.closed.isDone(), "both preambles opened after recovery");
        }
        assertEquals(0, node.reserved());
    }

    @Test
    void aSplitPrefixLocallyBlockedBeyondThePartialDeadlineRecovers() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(512 * 1024);
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        LaneRig.Config config = rawServerConfig(node);
        config.serverTiming = new LaneSession.Timing(clock::get, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, TimeUnit.MILLISECONDS.toNanos(10));
        try (LaneRig rig = LaneRig.start(config)) {
            openRawServerLanes(rig);
            byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.SPAWN, 200_000));
            LaneLoopback.write(rig.clientControl, record[0]);
            assertTrue(LaneRig.await(() -> snapshotIs(rig.server, 0, 1), 10000));
            long held = node.available();
            assertTrue(node.tryReserve(held));
            LaneLoopback.write(rig.clientControl, Arrays.copyOfRange(record, 1, record.length));
            sleep(300);
            assertEquals(0, rig.serverGame.packetCount());
            clock.addAndGet(ReceiverScheduler.TIMEOUT_NANOS + TimeUnit.SECONDS.toNanos(1));
            rig.server.eventLoop().submit(rig.server::tick).get(10, TimeUnit.SECONDS);
            assertFalse(rig.serverGame.closed.isDone(), "a partial's local wait must not expire");
            node.release(held);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == 1, 10000));
            assertFalse(rig.serverGame.closed.isDone());
        }
        assertEquals(0, node.reserved());
    }

    @Test
    void aRecordWhoseGrantWasTakenBeforeTheNodeFilledUpIsStillAdmitted() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(6L << 20);
        LaneRig rigB = LaneRig.start(rawServerConfig(node));
        try {
            QuicStreamChannel four = openRawServerLanes(rigB);
            byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.MOVE, 2_000_000));
            // The length is known but the body is incomplete: the exact parser allocation is
            // charged before another read, rather than speculatively charging MAX_RECORD_WIRE.
            LaneLoopback.write(four, java.util.Arrays.copyOfRange(record, 0, 3));
            assertTrue(LaneRig.await(() -> snapshotIs(rigB.server, 0, 1), 10000), "the record started");

            // A second connection of the same node holds the remaining space (13.12); what it takes
            // is what its peer's records keep, never a send-side charge of its own.
            LaneRig holder = holdRawRecords(node, 1_900_000, 2);
            try {
                assertTrue(LaneRig.await(() -> node.available() < 800_000L, 8000),
                        "the node is nearly full: " + node.available());
                // The rest arrives with almost no node space left: its already charged exact
                // parser allocation must carry it, never 0x11.
                LaneLoopback.write(four, java.util.Arrays.copyOfRange(record, 3, record.length));
                assertTrue(LaneRig.await(() -> rigB.serverGame.packetCount() == 1, 30000),
                        "a legal record is admitted from its own grant");
                assertFalse(rigB.serverGame.closed.isDone(), "node contention is not fatal for a legal record");
                assertFalse(holder.serverGame.closed.isDone(), "the holder is never evicted");
            } finally {
                holder.close();
            }
        } finally {
            rigB.close();
        }
    }

    @Test
    void stalledSendQueuesOnTwoConnectionsShareTheNodeBudgetAndReleaseItOnClose() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(1L << 20);
        LaneRig.Config firstConfig = new LaneRig.Config();
        firstConfig.nodeBudget = node;
        LaneRig.Config secondConfig = new LaneRig.Config();
        secondConfig.nodeBudget = node;
        LaneRig first = LaneRig.start(firstConfig);
        LaneRig second = LaneRig.start(secondConfig);
        try {
            first.activateBoth();
            second.activateBoth();
            first.client.holdWrites(Lane.CONTROL);
            second.client.holdWrites(Lane.CONTROL);
            assertTrue(LaneRig.await(() -> node.reserved() > 0, 10000));
            long baseline = node.reserved();

            first.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, 700_000));
            long queuedRecordBytes = RecordCodec.encodeGame(1, 0, 0, 0,
                    LaneRig.frame(LaneRig.SPAWN, 700_000)).length;
            assertTrue(LaneRig.await(() -> node.reserved() >= baseline + queuedRecordBytes, 10000),
                    "the stalled send stays charged at its record wire size: baseline=" + baseline
                            + ", expected=" + queuedRecordBytes + ", actual=" + node.reserved()
                            + ", closed=" + first.clientGame.closed.getNow(null));

            second.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, 700_000));
            closedWith(second.clientGame, 0x11, false);
            assertTrue(node.reserved() > baseline,
                    "the first connection's stalled send remains reserved after the second is refused");
        } finally {
            first.close();
            second.close();
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000),
                "both lane sessions release receive and send reservations on close: " + node.reserved());
    }

    @Test
    void anImmediateSendWriteFailureReleasesItsNodeChargeExactlyOnce() throws Exception {
        assertSendWriteFailureReleasesExactlyOnce(0);
    }

    @Test
    void aFailureAfterPartialDrainReleasesItsRemainingNodeChargeExactlyOnce() throws Exception {
        assertSendWriteFailureReleasesExactlyOnce(1);
    }

    private static void assertSendWriteFailureReleasesExactlyOnce(int successfulWrites) throws Exception {
        StrictBudget node = new StrictBudget(4L << 20);
        LaneRig first = LaneRig.start(configWith(node));
        LaneRig second = LaneRig.start(configWith(node));
        try {
            first.activateBoth();
            second.activateBoth();
            long otherReservation = holdControlSend(second, node, 64);

            first.client.holdWrites(Lane.CONTROL);
            first.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, 200_000));
            assertTrue(LaneRig.await(() -> node.reserved() > otherReservation, 10000),
                    "the failing connection first reserves its stalled send");
            QuicStreamChannel control = LaneRig.channelOf(first.client, Lane.CONTROL);
            installOnLoop(control, failWritesAfter(successfulWrites));

            first.client.releaseWrites(Lane.CONTROL);
            closedWith(first.clientGame, 0x11, false);
            // A repeated close must not revisit charges already settled by the inline write failure.
            first.client.abort(0x11);

            assertOtherSendIntact(second, node, otherReservation,
                    "the second connection's reservation survives the failed connection's shutdown");
            assertEquals(0, node.releasesWithoutReservation,
                    "no send byte is released twice after " + successfulWrites + " successful writes");
        } finally {
            first.close();
            second.close();
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000),
                "both connections release every reservation: " + node.reserved());
        assertEquals(0, node.releasesWithoutReservation);
    }

    @Test
    void anOversizedBundleReservationIsAtomicAndPreservesTheOtherConnection() throws Exception {
        StrictBudget node = new StrictBudget(1L << 20);
        LaneRig first = LaneRig.start(configWith(node));
        LaneRig second = LaneRig.start(configWith(node));
        try {
            first.activateBoth();
            second.activateBoth();
            long otherReservation = holdControlSend(second, node, 150_000);

            List<SentPacket> bundle = first.client.sendBundle(
                    new OutboundPacket(LaneRig.frame(LaneRig.BUNDLE_OPEN, "open")),
                    Arrays.asList(new OutboundPacket(LaneRig.frame(LaneRig.SPAWN, 480_000)),
                            new OutboundPacket(LaneRig.frame(LaneRig.SPAWN, 480_000))),
                    new OutboundPacket(LaneRig.frame(LaneRig.BUNDLE_CLOSE, "close")));
            closedWith(first.clientGame, 0x11, false);

            for (SentPacket packet : bundle) {
                assertFalse(packet.isAssigned(), "a bundle refused by the node consumes no sequence");
            }
            assertOtherSendIntact(second, node, otherReservation,
                    "the refused bundle leaves no partial charge and preserves the other connection");
            assertEquals(0, node.releasesWithoutReservation);
        } finally {
            first.close();
            second.close();
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000));
        assertEquals(0, node.releasesWithoutReservation);
    }

    @Test
    void aSuccessfulDrainThenRepeatedClosePreservesTheOtherConnectionReservation() throws Exception {
        StrictBudget node = new StrictBudget(4L << 20);
        LaneRig first = LaneRig.start(configWith(node));
        LaneRig second = LaneRig.start(configWith(node));
        try {
            first.activateBoth();
            second.activateBoth();
            long otherReservation = holdControlSend(second, node, 64);
            first.client.holdWrites(Lane.CONTROL);
            first.client.send(PLAY, LaneRig.SPAWN, LaneRig.frame(LaneRig.SPAWN, "drain"));
            assertTrue(LaneRig.await(() -> node.reserved() > otherReservation, 10000));

            first.client.releaseWrites(Lane.CONTROL);
            assertTrue(LaneRig.await(() -> first.serverGame.packetCount() == 1, 10000));
            assertTrue(LaneRig.await(() -> {
                try {
                    return LaneRig.snapshot(first.client)[3] == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 10000), "the send queue drained before close");
            first.client.abort(0x11);
            first.client.abort(0x11);

            assertOtherSendIntact(second, node, otherReservation,
                    "drain and repeated close leave the second connection's reservation intact");
            assertEquals(0, node.releasesWithoutReservation);
        } finally {
            first.close();
            second.close();
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000));
        assertEquals(0, node.releasesWithoutReservation);
    }

    private static LaneRig.Config configWith(ReceiveBudget node) {
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        return config;
    }

    private static long holdControlSend(LaneRig rig, ReceiveBudget node, int payloadBytes) throws Exception {
        rig.client.holdWrites(Lane.CONTROL);
        long before = node.reserved();
        byte[] frame = LaneRig.frame(LaneRig.SPAWN, payloadBytes);
        rig.client.send(PLAY, LaneRig.SPAWN, frame);
        long wireBytes = RecordCodec.encodeGame(1, 0, 0, 0, frame).length;
        assertTrue(LaneRig.await(() -> node.reserved() >= before + wireBytes, 10000),
                "the second connection holds a charged send record");
        return wireBytes;
    }

    private static void assertOtherSendIntact(LaneRig other, ReceiveBudget node, long wireBytes, String message)
            throws Exception {
        assertTrue(LaneRig.await(() -> {
            try {
                return LaneRig.snapshot(other.client)[3] == wireBytes && node.reserved() >= wireBytes;
            } catch (Exception e) {
                return false;
            }
        }, 10000), message + ": pending=" + java.util.Arrays.toString(LaneRig.snapshot(other.client))
                + ", node=" + node.reserved() + ", expected send bytes=" + wireBytes);
    }

    private static ChannelOutboundHandlerAdapter failWritesAfter(int successfulWrites) {
        return new ChannelOutboundHandlerAdapter() {
            private int writes;

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (++writes <= successfulWrites) {
                    ctx.write(msg, promise);
                    return;
                }
                io.netty.util.ReferenceCountUtil.release(msg);
                promise.tryFailure(new java.io.IOException("injected immediate lane write failure"));
            }
        };
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
    void anOversizedTransportCompletionIsALocalInvariantFailureNotPeerMisconduct() throws Exception {
        // Room for the three lanes' idle grants and 1024 bytes more. A synthetic oversized
        // completion cannot charge its excess, so it fails closed and returns every grant.
        ReceiveBudget node = ReceiveBudget.bounded(3 * LaneSession.READ_CHUNK_MIN + 1024);
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        config.clientSession = false;
        try (LaneRig rig = LaneRig.start(config)) {
            openRawServerLanes(rig);
            assertTrue(LaneRig.await(() -> node.reserved() == LaneSession.READ_CHUNK_MIN * 3, 10000),
                    "the lane holds its read grant before the read: " + node.reserved());
            feedChunk(rig.server, Lane.CONTROL, LaneSession.READ_CHUNK_MIN + 1025);
            closedWith(rig.serverGame, LaneSession.CLOSE_INTERNAL_ERROR, false);
            assertTrue(rig.serverGame.closeCause instanceof IllegalStateException);
            sawClose(rig.net.clientSawClose, LaneSession.CLOSE_INTERNAL_ERROR);
            assertTrue(LaneRig.await(() -> node.reserved() == 0, 10000),
                    "closing returns every charge, the read grant included: " + node.reserved());
        }
    }

    @Test
    void aThrowingNodeReleaseStillFreesTheRestOfALanesChunks() throws Exception {
        ThrowingReleaseBudget node = new ThrowingReleaseBudget();
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        config.deferClientGame = true;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.activateBoth();
            // No game is bound, so the lane keeps what it reads: the chunk it would parse from and
            // the ones queued behind it. A transport read is capped at READ_CHUNK and stops while a
            // chunk is held, so the chunks the lane holds are fed to it directly.
            QuicStreamChannel control = LaneRig.channelOf(rig.client, Lane.CONTROL);
            rig.client.eventLoop().submit(() -> {
                for (int i = 0; i < 3; i++) {
                    control.pipeline().fireChannelRead(control.alloc().buffer(1000).writeZero(1000));
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
            List<ByteBuf> held = rig.client.eventLoop().submit(() -> rig.client.heldChunks(Lane.CONTROL))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(3, held.size(), "the lane holds the chunk it parses from and the two behind it");
            for (ByteBuf chunk : held) {
                assertEquals(1, chunk.refCnt());
            }
            node.failTheNextRelease();
            rig.client.abort(0x11);
            sawClose(rig.net.serverSawClose, 0x11);
            for (ByteBuf chunk : held) {
                assertEquals(0, chunk.refCnt(), "a held chunk is freed even when a node release throws");
            }
            assertEquals(1, node.failed);
            assertEquals(node.refusedBytes, node.reserved(),
                    "only the release that failed stays charged: " + node.reserved());
        }
    }

    /**
     * Feeds a lane's handler one chunk of {@code bytes} directly, on the session's event loop: the
     * lane's allocator caps a transport read at {@link LaneSession#READ_CHUNK}, so the oversized
     * chunk 13.12's guard is about is only reachable by handing the handler the bytes itself.
     */
    private static void feedChunk(LaneSession session, Lane lane, int bytes) throws Exception {
        QuicStreamChannel channel = LaneRig.channelOf(session, lane);
        session.eventLoop().submit(() -> {
            channel.pipeline().fireChannelRead(channel.alloc().buffer(bytes).writeZero(bytes));
            return null;
        }).get(10, TimeUnit.SECONDS);
    }

    /** A budget whose next release throws before delegating; the bytes it refused stay charged. */
    private static final class ThrowingReleaseBudget extends ReceiveBudget {
        private final ReceiveBudget delegate = ReceiveBudget.bounded(16L << 20);
        private volatile boolean failNext;
        volatile int failed;
        volatile long refusedBytes;

        void failTheNextRelease() {
            failNext = true;
        }

        @Override
        public boolean tryReserve(long bytes) {
            return delegate.tryReserve(bytes);
        }

        @Override
        public void release(long bytes) {
            if (failNext) {
                failNext = false;
                failed++;
                refusedBytes = bytes;
                throw new IllegalStateException("release " + bytes + " fails");
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
    void readChunksAndParserBuffersAreChargedToTheNodeBudgetUntilTheyAreConsumed() throws Exception {
        // Enough space for reads and parser allocations, charged only as they are needed.
        ReceiveBudget node = ReceiveBudget.bounded(3L << 20);
        LaneRig.Config config = new LaneRig.Config();
        config.nodeBudget = node;
        config.deferClientGame = true;
        try (LaneRig rig = LaneRig.start(config)) {
            rig.activate(rig.server, null);
            rig.activateClient();
            rig.server.send(PLAY, LaneRig.BLOCK, LaneRig.frame(LaneRig.BLOCK, "buffered"));
            assertTrue(LaneRig.await(() -> node.reserved() > 0, 10000),
                    "the unparsed chunk is charged while it is held: " + node.reserved()
                            + " client=" + rig.clientGame.closed.getNow(null)
                            + " server=" + rig.serverGame.closed.getNow(null) + " " + rig.serverGame.closeCause);
            sleep(300);
            assertEquals(0, rig.clientGame.packetCount(), "nothing is delivered before the game is bound");
            assertEquals(0, LaneRig.snapshot(rig.client)[0], "nothing is parsed before the game is bound");
            rig.client.bindGame(rig.clientGame);
            assertTrue(LaneRig.await(() -> rig.clientGame.packetCount() == 1, 10000));
            assertEquals("buffered", rig.clientGame.labels().get(0));
            // Only the six idle grants remain after both sessions release their chunks/records.
            assertTrue(LaneRig.await(() -> node.reserved() == LaneSession.READ_CHUNK_MIN * 6, 10000),
                    "consumed chunks and applied records release every charge: " + node.reserved());
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    // --- Adaptive, bounded read grants (13.12).
    @Test
    void adaptiveReadsWithholdOnRefusalGrowUnderLoadAndReturnToTheIdleMinimum() throws Exception {
        ReceiveBudget node = ReceiveBudget.bounded(512 * 1024);
        try (LaneRig rig = LaneRig.start(rawServerConfig(node))) {
            openRawServerLanes(rig);
            QuicStreamChannel control = LaneRig.channelOf(rig.server, Lane.CONTROL);
            java.util.concurrent.atomic.AtomicInteger bytes = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
            ChannelInboundHandlerAdapter capture = new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    int size = ((ByteBuf) msg).readableBytes();
                    int bound = ctx.channel().config().getRecvByteBufAllocator().newHandle().guess();
                    assertTrue(size <= bound, "transport delivered " + size + " against grant " + bound);
                    bytes.addAndGet(size);
                    maximum.accumulateAndGet(size, Math::max);
                    ctx.fireChannelRead(msg);
                }
            };
            control.eventLoop().submit(() -> control.pipeline().addFirst(capture)).get(10, TimeUnit.SECONDS);
            assertTrue(LaneRig.await(() -> node.reserved() == 3 * LaneSession.READ_CHUNK_MIN, 5000));
            long held = node.available();
            assertTrue(node.tryReserve(held));
            byte[] record = RecordCodec.encodeGame(1, 0, 0, 0, LaneRig.frame(LaneRig.SPAWN, 200_000));
            LaneLoopback.write(rig.clientControl, record);
            assertTrue(LaneRig.await(() -> bytes.get() > 0, 5000));
            sleep(300);
            assertTrue(bytes.get() <= LaneSession.READ_CHUNK_MIN, "refusal withholds the next read");
            assertFalse(rig.serverGame.closed.isDone(), "refusal is non-fatal");
            assertEquals(0, rig.serverGame.packetCount());
            node.release(held);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == 1, 10000),
                    "resume: bytes=" + bytes.get() + " node=" + node.reserved()
                            + " closed=" + rig.serverGame.closed.getNow(null) + " cause=" + rig.serverGame.closeCause);
            for (int i = 2; i <= 8; i++) {
                LaneLoopback.write(rig.clientControl, RecordCodec.encodeGame(i, i - 1, 0, 0,
                        LaneRig.frame(LaneRig.SPAWN, 200_000)));
                final int expected = i;
                assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == expected, 10000));
                assertTrue(node.reserved() <= 512 * 1024);
            }
            assertTrue(LaneRig.await(() -> node.reserved() == 3 * LaneSession.READ_CHUNK_MIN, 5000));
            // UDP packet batching is timing-dependent on loopback. Inject exact, valid full-read
            // completions on the loop to exercise all growth steps deterministically as well.
            rig.server.eventLoop().submit(() -> {
                for (int sequence = 9; sequence <= 11; sequence++) {
                    int size = control.config().getRecvByteBufAllocator().newHandle().guess();
                    byte[] full = RecordCodec.encodeGame(sequence, sequence - 1, 0, 0,
                            LaneRig.frame(LaneRig.SPAWN, size - 38));
                    assertEquals(size, full.length);
                    control.pipeline().fireChannelRead(control.alloc().buffer(size).writeBytes(full));
                }
            }).get(10, TimeUnit.SECONDS);
            assertTrue(LaneRig.await(() -> rig.serverGame.packetCount() == 11, 5000));
            assertEquals(LaneSession.READ_CHUNK, maximum.get(), "full reads grow to 16 KiB");
            assertTrue(LaneRig.await(() -> node.reserved() == 3 * LaneSession.READ_CHUNK_MIN, 5000),
                    "idle pending reads shrink safely back to 4 KiB");
            assertFalse(rig.serverGame.closed.isDone());
        }
        assertTrue(LaneRig.await(() -> node.reserved() == 0, 5000));
    }

    @Test
    void dataOnUnusedServerboundAuxiliaryLanesIsWrongLane0x11() throws Exception {
        for (Lane lane : new Lane[] {Lane.REALTIME, Lane.WORLD}) {
            LaneRig.Config config = new LaneRig.Config();
            config.clientSession = false;
            config.serverTable = rs.sudoe.quicraft.core.routing.RoutingTableLoader.loadResource(775);
            try (LaneRig rig = LaneRig.start(config)) {
                rig.activate(rig.server, null);
                QuicStreamChannel world = rig.clientRawStreams.poll(10, TimeUnit.SECONDS);
                LaneLoopback.write(world, (byte) 2, (byte) 2);
                QuicStreamChannel realtime = rig.net.clientStream();
                LaneLoopback.write(realtime, (byte) 2, (byte) 1);
                QuicStreamChannel target = lane == Lane.REALTIME ? realtime : world;
                assertNotNull(target);
                LaneLoopback.write(target, (byte) 0x80);
                closedWith(rig.serverGame, 0x11, false);
                sawClose(rig.net.clientSawClose, 0x11);
            }
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

    // --- The shared loop ticker (13.12, 13.14).
    @Test
    void theSharedTickPeriodGrowsBackWhenTheFastestSessionLeaves() throws Exception {
        long fast = TimeUnit.MILLISECONDS.toNanos(5);
        long slow = TimeUnit.MILLISECONDS.toNanos(200);
        LaneRig.Config config = new LaneRig.Config();
        config.serverSession = false;
        config.clientTiming = new LaneSession.Timing(System::nanoTime, LaneSession.OPEN_TIMEOUT_NANOS,
                LaneSession.END_LINGER_NANOS, fast);
        try (LaneRig rig = LaneRig.start(config)) {
            rig.activateClient();
            io.netty.channel.EventLoop loop = rig.client.eventLoop();
            QuicChannel secondConnection = QuicChannel.newBootstrap(rig.net.clientUdp)
                    .handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new io.netty.channel.ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel stream) {
                            stream.config().setAutoRead(false);
                        }
                    })
                    .remoteAddress(rig.net.serverUdp.localAddress())
                    .connect().get(10, TimeUnit.SECONDS);
            try {
                // One listener's loop serves many connections, so its single ticker must run at the
                // fastest period any session on that loop asks for. This second session shares the
                // first one's event loop (the same UDP channel), with a slower period.
                QuicStreamChannel control = secondConnection.createStream(QuicStreamType.BIDIRECTIONAL,
                        new ChannelInboundHandlerAdapter()).get(10, TimeUnit.SECONDS);
                LaneSession second = new LaneSession(secondConnection, LaneSession.Role.CLIENT, control,
                        LaneRig.TABLE, new LaneRig.TestGame(), ReceiveBudget.unlimited(),
                        new LaneSession.Timing(System::nanoTime, LaneSession.OPEN_TIMEOUT_NANOS,
                                LaneSession.END_LINGER_NANOS, slow));
                assertEquals(loop, second.eventLoop(), "both sessions share one event loop");
                second.activate(null);
                loop.submit(() -> null).get(10, TimeUnit.SECONDS);
                assertEquals(fast, tickPeriod(loop), "the loop ticks at the fastest session's period");
                // The faster session leaves: the loop's period must grow back to the slower one's.
                rig.client.abort(0x11);
                assertEquals(slow, tickPeriod(loop),
                        "the period is recomputed when a session is removed");
            } finally {
                secondConnection.close(true, 0, Unpooled.EMPTY_BUFFER).awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
        }
    }

    /** The shared tick period of a loop, read on that loop. */
    private static long tickPeriod(io.netty.channel.EventLoop loop) throws Exception {
        return loop.submit(() -> LaneSession.tickPeriodNanos(loop)).get(10, TimeUnit.SECONDS);
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
