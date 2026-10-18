// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.sudoe.quicraft.core.CloseReason;
import rs.sudoe.quicraft.core.resource.ResourcePools.Pool;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.transport.LaneLoopback;

/**
 * The N+2 session end to end over loopback QUIC (docs/protocol.md 13.3-13.20): stream layout and shard mapping
 * for every N, fail-closed routing, the REALTIME slice, stream faults, close reasons, END, shedding and the
 * outbound charge's release point.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NStreamSessionLoopbackTest {
    private static final int CLOSE_ORDERING = 0x11;
    private static final int CLOSE_RESOURCE = 0x12;

    private static SessionRigSupport.Closed closed(NRig.Game game) throws Exception {
        return game.closed.get(15, TimeUnit.SECONDS);
    }

    private static int shardStream(int x, int z, int n) {
        return 2 + ShardHash.shardOf(x, z, 0, n);
    }

    @Test
    void nStreamsRoundTripForEveryNegotiatedN() throws Exception {
        for (int n = 1; n <= LogicalStream.MAX_CHUNKS; n++) {
            try (NRig rig = NRig.start(n)) {
                rig.activateBoth();
                SessionContext context = SessionContext.of(n);
                for (int i = 0; i < n + 2; i++) {
                    assertEquals(context.quicIdOf(i), NRig.channel(rig.server, i).streamId(), "server stream " + i);
                    assertEquals(context.quicIdOf(i), NRig.channel(rig.client, i).streamId(), "client stream " + i);
                }
                rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "c"));
                rig.server.send(ProtocolState.PLAY, NRig.REALTIME_ID, NRig.frame(NRig.REALTIME_ID, "r"));
                Set<Integer> expectedShards = new HashSet<Integer>();
                int chunks = 0;
                for (int x = -4; x < 4; x++) {
                    for (int z = -2; z < 2; z++) {
                        rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(x, z, x + "," + z),
                                NRig.chunkUncompressed(x, z));
                        expectedShards.add(shardStream(x, z, n));
                        chunks++;
                    }
                }
                rig.client.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "sc"));
                rig.client.send(ProtocolState.PLAY, NRig.REALTIME_ID, NRig.frame(NRig.REALTIME_ID, "sr"));
                int total = chunks + 2;
                assertTrue(SessionRigSupport.await(() -> rig.clientGame.packets().size() == total, 15_000),
                        "n=" + n + " client got " + rig.clientGame.packets().size() + " of " + total);
                assertTrue(SessionRigSupport.await(() -> rig.serverGame.packets().size() == 2, 15_000), "n=" + n);
                Set<Integer> usedShards = new HashSet<Integer>();
                for (NRig.Got got : rig.clientGame.units()) {
                    NRig.Pkt pkt = (NRig.Pkt) got.packets.get(0);
                    int expected = pkt.id == NRig.CHUNK_ID ? shardStream(pkt.x, pkt.z, n)
                            : pkt.id == NRig.REALTIME_ID ? LogicalStream.REALTIME : LogicalStream.CONTROL;
                    assertEquals(expected, got.stream, "n=" + n + " " + got);
                    if (pkt.id == NRig.CHUNK_ID) {
                        usedShards.add(got.stream);
                    }
                }
                assertEquals(expectedShards, usedShards, "n=" + n);
                for (NRig.Got got : rig.serverGame.units()) {
                    NRig.Pkt pkt = (NRig.Pkt) got.packets.get(0);
                    assertEquals(pkt.id == NRig.REALTIME_ID ? LogicalStream.REALTIME : LogicalStream.CONTROL,
                            got.stream, "n=" + n + " " + got);
                }
                assertFalse(rig.clientGame.closed.isDone(), "n=" + n);
                assertFalse(rig.serverGame.closed.isDone(), "n=" + n);
            }
        }
    }

    /**
     * Every causal-scope operation reaches the send driver through the session (U2, 13.19): scopes
     * and updates are allocated, covered send assigns and consumes the token, reuse is rejected
     * without a sequence, suppression and completion are enforced, and the ack-membership calls are
     * delegated (an unknown ack is rejected by the driver, not by the session default).
     */
    @Test
    void causalScopeOperationsDelegateThroughTheSession() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            MultistreamSession.CausalScope scope = rig.server.openScope();
            assertNotNull(scope, "the session opens scopes through the driver");
            MultistreamSession.UpdateToken token = rig.server.registerUpdate(scope);
            assertNotNull(token, "the session registers updates through the driver");
            assertEquals(1, rig.server.sendDriver().trackerRetainedTokens());

            SentPacket covered = rig.server.sendCovered(token, ProtocolState.PLAY, NRig.CONTROL_ID,
                    NRig.frame(NRig.CONTROL_ID, "u"), NRig.frame(NRig.CONTROL_ID, "u"));
            assertTrue(covered.isAssigned(), "the session routes sendCovered to the driver");
            assertEquals(0, rig.server.sendDriver().trackerRetainedTokens());

            assertThrows(IllegalArgumentException.class, () -> rig.server.sendCovered(token, ProtocolState.PLAY,
                    NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "again"), NRig.frame(NRig.CONTROL_ID, "again")));
            SentPacket next = rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "n"));
            assertEquals(covered.sequence() + 1, next.sequence(), "the rejected token took no sequence");

            MultistreamSession.UpdateToken suppressed = rig.server.registerUpdate(scope);
            rig.server.suppressUpdate(suppressed);
            assertThrows(IllegalArgumentException.class, () -> rig.server.sendCovered(suppressed, ProtocolState.PLAY,
                    NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "s"), NRig.frame(NRig.CONTROL_ID, "s")),
                    "suppressUpdate reached the driver");
            rig.server.completeScope(scope);
            assertThrows(IllegalArgumentException.class, () -> rig.server.registerUpdate(scope),
                    "completeScope reached the driver");
            assertThrows(IllegalArgumentException.class, () -> rig.server.coverScope(999, scope),
                    "coverScope reached the driver (unknown ack)");
            assertThrows(IllegalArgumentException.class, () -> rig.server.sealAckCoverage(999),
                    "sealAckCoverage reached the driver (unknown ack)");

            assertTrue(SessionRigSupport.await(() -> rig.clientGame.count(NRig.CONTROL_ID) >= 2, 15_000),
                    "the covered update and the later CONTROL record arrived");
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    @Test
    void chunkRouteWithoutPositionFailsClosed() throws Exception {
        byte[][] uncompressed = {null, {(byte) NRig.CHUNK_ID, 0, 0, 0, 1}};
        for (byte[] bytes : uncompressed) {
            try (NRig rig = NRig.start(2)) {
                rig.activateBoth();
                SentPacket handle = rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(1, 1, "x"),
                        bytes);
                assertFalse(handle.isAssigned());
                assertEquals(CLOSE_ORDERING, closed(rig.serverGame).code);
                assertEquals(CLOSE_ORDERING, (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS));
                assertEquals(0, rig.clientGame.count(NRig.CHUNK_ID));
            }
        }
    }

    @Test
    void joinRouteThroughPlainSendFailsClosed() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SentPacket handle = rig.server.send(ProtocolState.PLAY, NRig.JOIN_ID, NRig.frame(NRig.JOIN_ID, "j"),
                    new byte[] {(byte) NRig.JOIN_ID});
            assertFalse(handle.isAssigned());
            assertEquals(CLOSE_ORDERING, closed(rig.serverGame).code);
            assertEquals(CLOSE_ORDERING, (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS));
            assertEquals(0, rig.clientGame.count(NRig.JOIN_ID));
        }
    }

    @Test
    void realtimeUsesItsSliceWhileAChunkWaits() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources res = rig.config.serverResources;
            Object other = new Object();
            InetAddress otherIp = InetAddress.getByName("10.0.0.1");
            long free = res.node().limit(Pool.OUTBOUND) - res.node().reserved(Pool.OUTBOUND);
            long ticket = res.outbound().reserveProtectedTicket(other, otherIp, null, free - 1024);
            assertTrue(res.outbound().takeGrant(ticket), "the filler is granted");
            rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(3, 4, repeat('k', 4096)),
                    NRig.chunkUncompressed(3, 4));
            rig.server.send(ProtocolState.PLAY, NRig.REALTIME_ID, NRig.frame(NRig.REALTIME_ID, "move"));
            assertTrue(SessionRigSupport.await(() -> rig.clientGame.count(NRig.REALTIME_ID) == 1, 10_000));
            Thread.sleep(200);
            assertEquals(0, rig.clientGame.count(NRig.CHUNK_ID), "the chunk waits for pool bytes");
            res.outbound().cancel(other);
            res.wakeWaiters();
            assertTrue(SessionRigSupport.await(() -> rig.clientGame.count(NRig.CHUNK_ID) == 1, 10_000));
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    /**
     * The N+2 session's idle STOP_SENDING probe depends on two package-private members of the pinned
     * Netty: {@code Quiche.QUICHE_ERR_STREAM_STOPPED} and {@code QuicheQuicChannel.streamCapacity(long)}.
     * Both are resolved reflectively at runtime, so a Netty upgrade or a relocation that drops either
     * would leave the probe silently inert; this pins them against the Netty the build actually uses.
     */
    @Test
    void theStopSendingProbeResolvesAgainstThePinnedNetty() throws Exception {
        assertTrue(NStreamSession.stopSendingErrCode() != Integer.MIN_VALUE,
                "the pinned Netty must expose QUICHE_ERR_STREAM_STOPPED");
        assertTrue(NStreamSession.stopSendingErrCode() < 0,
                "QUICHE_ERR_STREAM_STOPPED is a negative quiche error: " + NStreamSession.stopSendingErrCode());
        Class<?> implementation = Class.forName(
                QuicStreamChannel.class.getName().replace("QuicStreamChannel", "QuicheQuicChannel"));
        assertNotNull(NStreamSession.findStreamCapacity(implementation),
                "the pinned Netty must expose the stream send capacity of " + implementation.getName());
    }

    private enum Fault { RESET, FIN, STOP }

    @Test
    void everyStreamResetFinStopAndMissingPreambleClosesSession() throws Exception {
        int n = 2;
        for (int index = 0; index < n + 2; index++) {
            for (Fault fault : Fault.values()) {
                try (NRig rig = NRig.start(n)) {
                    rig.activateBoth();
                    // CONTROL and REALTIME are tested in the client-to-server direction, CHUNK server to client.
                    boolean chunk = index >= 2;
                    NStreamSession writer = chunk ? rig.server : rig.client;
                    NStreamSession reader = chunk ? rig.client : rig.server;
                    QuicStreamChannel w = NRig.channel(writer, index);
                    QuicStreamChannel r = NRig.channel(reader, index);
                    if (fault == Fault.RESET) {
                        w.shutdownOutput(7);
                    } else if (fault == Fault.FIN) {
                        w.shutdownOutput();
                    } else {
                        r.shutdownInput(7);
                    }
                    // Traffic on the faulted stream, so a writer that only learns of it by writing does.
                    for (int i = 0; i < 4; i++) {
                        if (chunk) {
                            int x = 0;
                            while (shardStream(x, 0, n) != index) {
                                x++;
                            }
                            writer.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(x, 0, "f"),
                                    NRig.chunkUncompressed(x, 0));
                        } else {
                            int id = index == LogicalStream.CONTROL ? NRig.CONTROL_ID : NRig.REALTIME_ID;
                            writer.send(ProtocolState.PLAY, id, NRig.frame(id, "f"));
                        }
                        Thread.sleep(20);
                    }
                    String what = fault + " on logical stream " + index;
                    assertEquals(CLOSE_ORDERING, closed(rig.serverGame).code, what);
                    assertEquals(CLOSE_ORDERING, closed(rig.clientGame).code, what);
                }
            }
        }
        // A wrong preamble on the client's REALTIME.
        NRig.Config config = new NRig.Config();
        config.clientSession = false;
        try (NRig rig = NRig.start(config)) {
            rig.activateServer();
            QuicStreamChannel realtime = rig.net.clientStream();
            assertEquals(4, realtime.streamId());
            LaneLoopback.write(realtime, (byte) 2, (byte) 2);
            assertEquals(CLOSE_ORDERING, closed(rig.serverGame).code);
            assertEquals(CLOSE_ORDERING, (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS));
        }
        // REALTIME never opened: the server's open timeout.
        config = new NRig.Config();
        config.clientSession = false;
        config.serverTiming = NRig.timing(50, 300);
        try (NRig rig = NRig.start(config)) {
            rig.activateServer();
            assertEquals(CLOSE_ORDERING, closed(rig.serverGame).code);
            assertEquals(CLOSE_ORDERING, (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS));
        }
        // No CHUNK stream ever opened: the client's open timeout.
        config = new NRig.Config();
        config.serverSession = false;
        config.clientTiming = NRig.timing(50, 300);
        try (NRig rig = NRig.start(config)) {
            rig.activateClient();
            assertEquals(CLOSE_ORDERING, closed(rig.clientGame).code);
            assertEquals(CLOSE_ORDERING, (int) rig.net.serverSawClose.get(15, TimeUnit.SECONDS));
        }
    }

    /**
     * The peer stops reading a lane with nothing in flight. Netty reports no event for the incoming
     * STOP_SENDING and the writer never touches the lane again, so only the periodic send-capacity
     * probe (13.14) can notice: the writer closes 0x11 by its own decision, carrying the probe's
     * cause, and without an attempted write.
     */
    @Test
    void aStopSendingOnAnIdleLaneClosesBothSidesWith0x11WithoutAWrite() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            // Attachment is not setup: wait until both sides have read every peer preamble, so the
            // lanes are provably idle and quiet before the STOP.
            rig.awaitPeerPreambles();
            AtomicInteger realtimeWrites = new AtomicInteger();
            NRig.onLoop(rig.server, () -> {
                rig.server.channelOf(LogicalStream.REALTIME).pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        realtimeWrites.incrementAndGet();
                        ctx.write(msg, promise);
                    }
                });
                return null;
            });
            NRig.channel(rig.client, LogicalStream.REALTIME).shutdownInput(0x43);
            SessionRigSupport.Closed server = closed(rig.serverGame);
            assertEquals(CLOSE_ORDERING, server.code, server.toString());
            assertFalse(server.byPeer, "the probe closes locally, with no write to learn it from: " + server);
            assertNotNull(server.cause, "the local close carries the probe's cause: " + server);
            assertTrue(String.valueOf(server.cause.getMessage()).contains("the peer stopped reading a stream"),
                    "the close came from the idle STOP_SENDING probe: " + server.cause);
            SessionRigSupport.Closed client = closed(rig.clientGame);
            assertEquals(CLOSE_ORDERING, client.code, client.toString());
            assertTrue(client.byPeer, "the client only sees the writer's close: " + client);
            assertEquals(0, realtimeWrites.get(), "the probe closed without an attempted REALTIME write");
            assertEquals(0, rig.clientGame.count(NRig.REALTIME_ID), "no REALTIME record was delivered either");
        }
    }

    /** The server session registers its connection with the inbound ranking its excess pool picks victims by. */
    @Test
    void serverSessionJoinsTheInboundRanking() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            assertEquals(1, rig.config.serverResources.inboundRanking().exactIpCount());
            assertEquals(1, rig.config.serverResources.outboundRanking().exactIpCount());
        }
    }

    @Test
    void resourceReasonReachesPeer() throws Exception {
        // Generic: the client's decoded output can never fit its execution workspace.
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            rig.clientGame.decodeReserveBytes = 1L << 40;
            rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "big"));
            assertEquals(CLOSE_RESOURCE, closed(rig.clientGame).code);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.serverSawClose.get(15, TimeUnit.SECONDS));
            assertArrayEquals("resource".getBytes(StandardCharsets.US_ASCII),
                    rig.net.serverSawReason.get(15, TimeUnit.SECONDS));
        }
        // Classified: a chunk waits past its deadline for a CONTROL prerequisite the client never saw.
        AtomicLong clientClock = new AtomicLong(System.nanoTime());
        LongSupplier clientClockSource = clientClock::get;
        NRig.Config config = new NRig.Config();
        config.clientResources = NRig.resources(clientClockSource);
        config.clientTiming = new NStreamSession.Timing(clientClockSource, TimeUnit.SECONDS.toNanos(3600),
                TimeUnit.SECONDS.toNanos(10), TimeUnit.SECONDS.toNanos(3600));
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            // The VIEW record is written (so the chunk behind it is ready) but its bytes never leave.
            Captor captor = new Captor();
            NRig.onLoop(rig.server, () -> rig.server.channelOf(LogicalStream.CONTROL).pipeline().addFirst(captor));
            SentPacket view = rig.server.send(ProtocolState.PLAY, NRig.VIEW_ID, NRig.frame(NRig.VIEW_ID, "view"));
            assertTrue(view.isAssigned());
            rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(0, 0, "after view"),
                    NRig.chunkUncompressed(0, 0), view);
            assertTrue(SessionRigSupport.await(() -> captor.size() > 0, 10_000), "the VIEW record was written");
            for (int i = 0; i < 50 && !rig.clientGame.closed.isDone(); i++) {
                Thread.sleep(100);
                clientClock.addAndGet(TimeUnit.SECONDS.toNanos(11));
                NRig.onLoop(rig.client, () -> {
                    rig.client.tick();
                    return null;
                });
            }
            assertEquals(CLOSE_RESOURCE, closed(rig.clientGame).code);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.serverSawClose.get(15, TimeUnit.SECONDS));
            assertArrayEquals(CloseReason.UNSEEN_PREREQUISITE, rig.net.serverSawReason.get(15, TimeUnit.SECONDS));
            assertEquals(0, rig.clientGame.count(NRig.CHUNK_ID));
        }
        // An owner's abort with 0x12 has no classified phrase, so it carries the generic one.
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            rig.server.abort(CLOSE_RESOURCE);
            assertEquals(CLOSE_RESOURCE, closed(rig.serverGame).code);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS));
            assertArrayEquals("resource".getBytes(StandardCharsets.US_ASCII),
                    rig.net.clientSawReason.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void endDrainsAllShardsAndClosesNormally() throws Exception {
        int n = 4;
        try (NRig rig = NRig.start(n)) {
            rig.activateBoth();
            int chunks = 0;
            for (int x = 0; x < 8; x++) {
                for (int z = 0; z < 5; z++) {
                    rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(x, z, repeat('e', 1024)),
                            NRig.chunkUncompressed(x, z));
                    chunks++;
                }
            }
            rig.server.end();
            SessionRigSupport.Closed client = closed(rig.clientGame);
            assertEquals(0, client.code);
            assertEquals(chunks, rig.clientGame.count(NRig.CHUNK_ID), "every shard drained before END");
            assertEquals(0, closed(rig.serverGame).code);
            assertEquals(0, (int) rig.net.serverSawClose.get(15, TimeUnit.SECONDS));
        }
    }

    /**
     * The peer never answers END and never closes. The sender's streams stay open, so the only exit
     * is the linger deadline: after a shortened linger it closes 0x0 on its own, without waiting for
     * the peer (13.14).
     */
    @Test
    void theEndSenderClosesAfterTheLingerWhenThePeerNeverDoes() throws Exception {
        NRig.Config config = new NRig.Config();
        config.serverSession = false; // a raw peer: it reads the client's streams but never answers
        config.clientTiming = new NStreamSession.Timing(NRig.SYSTEM_CLOCK, TimeUnit.SECONDS.toNanos(3600),
                TimeUnit.MILLISECONDS.toNanos(400), TimeUnit.MILLISECONDS.toNanos(20));
        try (NRig rig = NRig.start(config)) {
            rig.activateClient();
            long start = System.nanoTime();
            rig.client.end();
            SessionRigSupport.Closed client = closed(rig.clientGame);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(0, client.code, client.toString());
            assertFalse(client.byPeer, "the sender closes on its own linger: " + client);
            assertTrue(elapsedMillis >= 350, "waited for the peer: closed after " + elapsedMillis + " ms");
            assertTrue(elapsedMillis < 5000, "the configured linger ended the wait: " + elapsedMillis + " ms");
            assertEquals(0, (int) rig.net.serverSawClose.get(15, TimeUnit.SECONDS));
        }
    }

    /** END is terminal: every later submission is refused on the caller's thread and nothing follows END (13.14). */
    @Test
    void sendsAfterEndAreRefusedAndNothingFollowsEnd() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            assertTrue(rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "before"))
                    .isAssigned());
            rig.server.end();
            assertFalse(rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "after"))
                    .isAssigned());
            assertFalse(rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(1, 1, "after"),
                    NRig.chunkUncompressed(1, 1)).isAssigned());
            assertFalse(rig.server.send(ProtocolState.PLAY, NRig.REALTIME_ID, NRig.frame(NRig.REALTIME_ID, "after"))
                    .isAssigned());
            assertTrue(rig.server.sendBundle(new OutboundPacket(NRig.frame(NRig.BUNDLE_OPEN, "")),
                    java.util.Collections.singletonList(new OutboundPacket(NRig.frame(NRig.CONTROL_ID, "after"))),
                    new OutboundPacket(NRig.frame(NRig.BUNDLE_CLOSE, ""))).isEmpty());
            assertFalse(rig.server.createAck(1, new byte[] {1}, 8));
            assertFalse(rig.server.beginBatch(1, new OutboundPacket(NRig.frame(NRig.VIEW_ID, "after"))).isAssigned());
            assertEquals(0, closed(rig.clientGame).code);
            assertEquals(0, closed(rig.serverGame).code);
            for (NRig.Pkt pkt : rig.clientGame.packets()) {
                assertEquals("before", pkt.label, "only the record before END is delivered: " + pkt);
            }
            assertEquals(1, rig.clientGame.packets().size());
        }
    }

    /**
     * A caller-thread refusal lands between the loop's poll of a slice and its write. The refusal only stops new
     * submissions: the polled slice keeps its charge until it is written, the close and the release run on the
     * loop afterwards, and the pool's accounting for the connection returns to zero.
     */
    @Test
    void callerRefusalBetweenPollAndWriteKeepsTheSliceCharged() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources res = rig.config.serverResources;
            java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
            AtomicLong atPoll = new AtomicLong(-1);
            AtomicLong afterRefusal = new AtomicLong(-1);
            rig.server.sink().beforeWriteForTest(() -> {
                if (!fired.compareAndSet(false, true)) {
                    return;
                }
                atPoll.set(res.outbound().reserved(rig.server));
                Thread caller = new Thread(() -> rig.server.send(ProtocolState.PLAY, NRig.JOIN_ID,
                        NRig.frame(NRig.JOIN_ID, "j"), new byte[] {(byte) NRig.JOIN_ID}));
                caller.start();
                try {
                    caller.join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                afterRefusal.set(res.outbound().reserved(rig.server));
            });
            rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, repeat('p', 4096)));
            assertEquals(CLOSE_ORDERING, closed(rig.serverGame).code);
            assertTrue(fired.get(), "the hook ran between a poll and its write");
            assertTrue(atPoll.get() > 4096, "the polled slice was charged: " + atPoll.get());
            assertEquals(atPoll.get(), afterRefusal.get(), "nothing was released between the poll and the write");
            assertTrue(SessionRigSupport.await(() -> res.outbound().reserved(rig.server) == 0, 10_000),
                    "the pool's accounting returns to zero: " + res.outbound().reserved(rig.server));
            assertEquals(0, res.outbound().reserved(rig.server));
        }
    }

    /** Copies every outbound byte of a stream and passes the write on unchanged. */
    private static final class Tap extends ChannelOutboundHandlerAdapter {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof io.netty.buffer.ByteBuf) {
                io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) msg;
                byte[] copy = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), copy);
                synchronized (bytes) {
                    bytes.write(copy, 0, copy.length);
                }
            }
            ctx.write(msg, promise);
        }

        /** Every complete record written so far, in order. */
        List<Record> records(int stream) throws MultistreamProtocolException {
            byte[] all;
            synchronized (bytes) {
                all = bytes.toByteArray();
            }
            List<Record> out = new ArrayList<Record>();
            int at = 0;
            while (at < all.length) {
                int length = 0;
                int shift = 0;
                int prefix = at;
                int b;
                do {
                    b = all[prefix++] & 0xff;
                    length |= (b & 0x7f) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0);
                byte[] one = java.util.Arrays.copyOfRange(all, at, prefix + length);
                out.add(RecordCodec.decodeRecord(one, stream));
                at = prefix + length;
            }
            return out;
        }
    }

    private static List<OutboundPacket> members(byte[]... frames) {
        List<OutboundPacket> out = new ArrayList<OutboundPacket>();
        for (byte[] frame : frames) {
            out.add(new OutboundPacket(frame));
        }
        return out;
    }

    private static List<SentPacket> bundle(NStreamSession session, byte[]... memberFrames) {
        return session.sendBundle(new OutboundPacket(NRig.frame(NRig.BUNDLE_OPEN, "")), members(memberFrames),
                new OutboundPacket(NRig.frame(NRig.BUNDLE_CLOSE, "")));
    }

    /** The delivered bundle unit whose members include {@code label}, or null. */
    private static NRig.Got bundleUnit(NRig.Game game, String label) {
        for (NRig.Got got : game.units()) {
            if (got.packets.size() > 1) {
                for (Object p : got.packets) {
                    if (label.equals(((NRig.Pkt) p).label)) {
                        return got;
                    }
                }
            }
        }
        return null;
    }

    /**
     * A bundle with CHUNK and REALTIME members needs no position: it rides CONTROL as one unit, is promoted to
     * global (its opener waits for the latest record on every stream), and the client delivers it whole (13.15).
     */
    @Test
    void bundleWithChunkAndRealtimeMembersRoundTripsAsGlobal() throws Exception {
        int n = 2;
        try (NRig rig = NRig.start(n)) {
            rig.activateBoth();
            Tap tap = new Tap();
            NRig.onLoop(rig.server, () -> rig.server.channelOf(LogicalStream.CONTROL).pipeline().addFirst(tap));
            SentPacket chunk = rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(1, 1, "plain"),
                    NRig.chunkUncompressed(1, 1));
            assertTrue(chunk.isAssigned());
            List<SentPacket> handles = bundle(rig.server, NRig.frame(NRig.CONTROL_ID, "m"),
                    NRig.chunkFrame(7, 7, "cm"), NRig.frame(NRig.REALTIME_ID, "rm"));
            assertEquals(5, handles.size(), "the bundle was accepted");
            for (SentPacket handle : handles) {
                assertEquals(LogicalStream.CONTROL, handle.logicalStream());
            }
            assertTrue(SessionRigSupport.await(() -> bundleUnit(rig.clientGame, "cm") != null, 15_000),
                    () -> "bundle delivered: " + rig.clientGame.units() + " " + rig.clientGame.closed.getNow(null));
            NRig.Got got = bundleUnit(rig.clientGame, "cm");
            assertEquals(LogicalStream.CONTROL, got.stream);
            assertEquals(5, got.packets.size());
            assertFalse(rig.clientGame.closed.isDone());
            assertFalse(rig.serverGame.closed.isDone());
            Record opener = null;
            for (Record record : tap.records(LogicalStream.CONTROL)) {
                if (record.kind() == RecordCodec.KIND_BUNDLE_OPEN) {
                    opener = record;
                }
            }
            assertTrue(opener != null, "the opener was written on CONTROL");
            long onShard = 0;
            for (Record.Dependency dep : opener.dependencies()) {
                if (dep.streamIndex() == chunk.logicalStream()) {
                    onShard = dep.sequence();
                }
            }
            assertEquals(chunk.sequence(), onShard, "a global bundle waits for the earlier CHUNK record");
        }
    }

    /** INTERACTION is serverbound only in a reviewed table, so a test table adds it clientbound (13.15). */
    @Test
    void bundleWithInteractionMemberRoundTrips() throws Exception {
        NRig.Config config = new NRig.Config();
        config.table = rs.sudoe.quicraft.core.routing.RoutingTables.withRoute(NRig.TABLE,
                new rs.sudoe.quicraft.core.routing.RouteKey(NRig.TABLE.protocolNumber(), ProtocolState.PLAY,
                        rs.sudoe.quicraft.core.routing.Direction.CLIENTBOUND, NRig.INTERACTION_ID),
                new rs.sudoe.quicraft.core.routing.RouteV2(rs.sudoe.quicraft.core.routing.RouteV2.StreamLane.CONTROL,
                        rs.sudoe.quicraft.core.routing.Dependency.INTERACTION, null, null, false, null, null, null));
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            List<SentPacket> handles = bundle(rig.server, NRig.frame(NRig.INTERACTION_ID, "im"),
                    NRig.frame(NRig.CONTROL_ID, "cm"));
            assertEquals(4, handles.size(), "the bundle was accepted");
            assertTrue(SessionRigSupport.await(() -> bundleUnit(rig.clientGame, "im") != null, 15_000),
                    () -> "bundle delivered: " + rig.clientGame.units() + " " + rig.clientGame.closed.getNow(null));
            assertEquals(LogicalStream.CONTROL, bundleUnit(rig.clientGame, "im").stream);
            assertFalse(rig.clientGame.closed.isDone());
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    /** A join member may never be bundled: the sender refuses the bundle and closes 0x11 without writing it. */
    @Test
    void bundleWithJoinMemberClosesOrdering() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            assertTrue(bundle(rig.server, NRig.frame(NRig.CONTROL_ID, "m"), NRig.frame(NRig.JOIN_ID, "j")).isEmpty());
            assertEquals(CLOSE_ORDERING, closed(rig.serverGame).code);
            assertEquals(CLOSE_ORDERING, (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS));
            assertEquals(null, bundleUnit(rig.clientGame, "m"));
        }
    }

    /**
     * A covered bundle is one CONTROL run carrying the game's members, and every distinct token is
     * consumed once by the same atomic transaction (U2b, 13.19). The scope is still the caller's to
     * complete.
     */
    @Test
    void coveredBundleRoundTripsAndConsumesEveryTokenOnce() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            MultistreamSession.CausalScope scope = rig.server.openScope();
            MultistreamSession.UpdateToken first = rig.server.registerUpdate(scope);
            MultistreamSession.UpdateToken repeated = rig.server.registerUpdate(scope);
            List<List<MultistreamSession.UpdateToken>> coverage =
                    new ArrayList<List<MultistreamSession.UpdateToken>>();
            coverage.add(Arrays.asList(first));
            coverage.add(Arrays.asList(repeated, first));
            coverage.add(Collections.<MultistreamSession.UpdateToken>emptyList());

            List<SentPacket> handles = rig.server.sendCoveredBundle(
                    new OutboundPacket(NRig.frame(NRig.BUNDLE_OPEN, "")),
                    members(NRig.frame(NRig.CONTROL_ID, "a"), NRig.frame(NRig.CONTROL_ID, "b"),
                            NRig.frame(NRig.CONTROL_ID, "c")),
                    new OutboundPacket(NRig.frame(NRig.BUNDLE_CLOSE, "")), coverage);

            assertEquals(5, handles.size(), "opener, three members and closer");
            for (SentPacket handle : handles) {
                assertEquals(LogicalStream.CONTROL, handle.logicalStream());
            }
            assertEquals(0, rig.server.sendDriver().trackerRetainedTokens(),
                    "each distinct token is consumed exactly once");
            assertTrue(SessionRigSupport.await(() -> bundleUnit(rig.clientGame, "b") != null, 15_000),
                    () -> "covered bundle delivered: " + rig.clientGame.units());
            NRig.Got got = bundleUnit(rig.clientGame, "b");
            assertEquals(5, got.packets.size(), "the client receives the whole unit");
            assertEquals(LogicalStream.CONTROL, got.stream);
            rig.server.completeScope(scope);
            assertFalse(rig.clientGame.closed.isDone());
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    /** A promoted covered bundle keeps its CHUNK member atomic on CONTROL and consumes the tokens. */
    @Test
    void coveredBundleGlobalPromotionRoundTrips() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            MultistreamSession.CausalScope scope = rig.server.openScope();
            MultistreamSession.UpdateToken token = rig.server.registerUpdate(scope);
            List<SentPacket> handles = rig.server.sendCoveredBundle(
                    new OutboundPacket(NRig.frame(NRig.BUNDLE_OPEN, "")),
                    members(NRig.frame(NRig.CONTROL_ID, "cm"), NRig.chunkFrame(7, 7, "km")),
                    new OutboundPacket(NRig.frame(NRig.BUNDLE_CLOSE, "")),
                    Arrays.asList(Arrays.asList(token), Collections.<MultistreamSession.UpdateToken>emptyList()));
            assertEquals(4, handles.size());
            assertEquals(0, rig.server.sendDriver().trackerRetainedTokens());
            assertTrue(SessionRigSupport.await(() -> bundleUnit(rig.clientGame, "km") != null, 15_000),
                    () -> "promoted covered bundle delivered: " + rig.clientGame.units());
            NRig.Got got = bundleUnit(rig.clientGame, "km");
            assertEquals(LogicalStream.CONTROL, got.stream, "a chunk member never splits the atomic run");
            assertEquals(4, got.packets.size());
            rig.server.completeScope(scope);
            assertFalse(rig.clientGame.closed.isDone());
        }
    }

    /**
     * A suppressed token at the first, middle or last member rejects the call before any assignment:
     * the session stays open, the other live token stays live and a later send still sequences.
     */
    @Test
    void coveredBundleRejectsBadTokenAtEveryMemberPosition() throws Exception {
        for (int position = 0; position < 3; position++) {
            try (NRig rig = NRig.start(2)) {
                rig.activateBoth();
                MultistreamSession.CausalScope liveScope = rig.server.openScope();
                MultistreamSession.UpdateToken live = rig.server.registerUpdate(liveScope);
                MultistreamSession.UpdateToken bad = rig.server.registerUpdate(rig.server.openScope());
                rig.server.suppressUpdate(bad);
                List<List<MultistreamSession.UpdateToken>> coverage =
                        new ArrayList<List<MultistreamSession.UpdateToken>>();
                for (int i = 0; i < 3; i++) {
                    coverage.add(new ArrayList<MultistreamSession.UpdateToken>());
                }
                coverage.get(position).add(bad);
                coverage.get((position + 1) % 3).add(live);
                long before = rig.server.sendDriver().assignedSequence(LogicalStream.CONTROL);
                int queuedBefore = rig.server.sendDriver().queuedRecords();

                assertThrows(IllegalArgumentException.class, () -> rig.server.sendCoveredBundle(
                        new OutboundPacket(NRig.frame(NRig.BUNDLE_OPEN, "")),
                        members(NRig.frame(NRig.CONTROL_ID, "a"), NRig.frame(NRig.CONTROL_ID, "b"),
                                NRig.frame(NRig.CONTROL_ID, "c")),
                        new OutboundPacket(NRig.frame(NRig.BUNDLE_CLOSE, "")), coverage),
                        "suppressed token at member " + position);

                assertEquals(before, rig.server.sendDriver().assignedSequence(LogicalStream.CONTROL),
                        "position " + position + " took no sequence");
                assertEquals(queuedBefore, rig.server.sendDriver().queuedRecords(),
                        "position " + position + " queued nothing");
                assertEquals(1, rig.server.sendDriver().trackerRetainedTokens(),
                        "the live token survived");
                assertFalse(rig.serverGame.closed.isDone(), "caller misuse keeps the session open");
                assertTrue(rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID,
                        NRig.frame(NRig.CONTROL_ID, "later")).isAssigned(),
                        "the session still accepts a valid send");
                assertFalse(rig.clientGame.closed.isDone());
            }
        }
    }

    /** An unreadable member frame or a join route at any position fails closed 0x11, writing no unit. */
    @Test
    void coveredBundleUnreadableFrameOrJoinRouteAtEveryPositionFailsClosed() throws Exception {
        for (int position = 0; position < 3; position++) {
            for (int kind = 0; kind < 2; kind++) {
                final int at = position;
                final int which = kind;
                try (NRig rig = NRig.start(2)) {
                    rig.activateBoth();
                    MultistreamSession.UpdateToken token = rig.server.registerUpdate(rig.server.openScope());
                    List<OutboundPacket> members = members(NRig.frame(NRig.CONTROL_ID, "a"),
                            NRig.frame(NRig.CONTROL_ID, "b"), NRig.frame(NRig.CONTROL_ID, "c"));
                    members.set(at, which == 0
                            ? new OutboundPacket(new byte[] {0})
                            : new OutboundPacket(NRig.frame(NRig.JOIN_ID, "join")));
                    List<List<MultistreamSession.UpdateToken>> coverage =
                            new ArrayList<List<MultistreamSession.UpdateToken>>();
                    for (int i = 0; i < 3; i++) {
                        coverage.add(new ArrayList<MultistreamSession.UpdateToken>());
                    }
                    coverage.get(at).add(token);

                    assertTrue(rig.server.sendCoveredBundle(
                            new OutboundPacket(NRig.frame(NRig.BUNDLE_OPEN, "")), members,
                            new OutboundPacket(NRig.frame(NRig.BUNDLE_CLOSE, "")), coverage).isEmpty(),
                            "member " + at + " kind " + which + " was refused");
                    assertEquals(CLOSE_ORDERING, closed(rig.serverGame).code);
                    assertEquals(CLOSE_ORDERING, (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS));
                    assertEquals(null, bundleUnit(rig.clientGame, "a"));
                }
            }
        }
    }

    @Test
    void inboundShedReleasesHeldExcessBeforeConfirmingClosure() throws Exception {
        inboundShed(false);
    }

    @Test
    void inboundCleanupFinishesWhenAUnitReleaseThrows() throws Exception {
        inboundShed(true);
    }

    private void inboundShed(boolean brokenReservation) throws Exception {
        NRig.Config config = new NRig.Config();
        config.compressed = true;
        config.serverResources = new SessionResources(new rs.sudoe.quicraft.core.resource.NodePools(64L << 20,
                rs.sudoe.quicraft.core.resource.NodePools.MIN_EXCESS, NRig.OUTBOUND_BYTES, 10, false,
                rs.sudoe.quicraft.core.resource.NodePools.MIN_EXCESS), NRig.OUTBOUND_BYTES, NRig.OUTBOUND_BYTES,
                NRig.OUTBOUND_BYTES, 16L << 20, 16L << 20, SessionResources.defaultProfile(), NRig.SYSTEM_CLOCK,
                SessionResources.Accounting.CUMULATIVE);
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            SessionResources res = config.serverResources;
            rig.serverGame.autoApply = false;
            byte[] body = new byte[16000];
            body[0] = (byte) NRig.CONTROL_ID;
            rig.client.send(ProtocolState.PLAY, NRig.CONTROL_ID, SessionRigSupport.frame(0, body));
            assertTrue(SessionRigSupport.await(() -> rig.serverGame.count(NRig.CONTROL_ID) == 1, 10_000));
            assertTrue(res.inboundExcess().reserved(rig.server) > 0, "the inbound unit owns excess");
            assertTrue(res.node().reserved(Pool.INFLATER) > 0, "the victim owns inflater state");
            assertTrue(res.outbound().reserved(rig.server) > 0, "the victim owns outbound headroom");
            java.lang.reflect.Field driverField = NStreamSession.class.getDeclaredField("serverDriver");
            driverField.setAccessible(true);
            ServerReceiveDriver driver = (ServerReceiveDriver) driverField.get(rig.server);
            assertTrue(driver.receiver().slots().workspaceInUse() > 0, "the executing unit owns workspace");
            Object other = new Object();
            long free = res.node().limit(Pool.INBOUND_EXCESS) - res.node().reserved(Pool.INBOUND_EXCESS);
            // Consume the free node budget, then ask for the victim's retained bytes.
            assertTrue(res.inboundExcess().reserve(other, InetAddress.getByName("10.0.0.2"), null,
                    free, false));
            // Only the session has peer-attributable ranked pressure; the filler is unranked.
            Object requester = new Object();
            assertFalse(res.inboundExcess().reserve(requester, InetAddress.getByName("10.0.0.3"), null,
                    1, false));
            java.util.concurrent.atomic.AtomicBoolean cleanAtConfirm = new java.util.concurrent.atomic.AtomicBoolean();
            java.lang.reflect.Method shed = NStreamSession.class.getDeclaredMethod("shed", Runnable.class);
            shed.setAccessible(true);
            res.register(rig.server, confirm -> {
                try {
                    // Fault injection: simulate an independently lost reservation. Unit.close now throws,
                    // but the inflater, workspace and rankings must still be released.
                    if (brokenReservation) res.inboundExcess().confirmClosed(rig.server);
                    shed.invoke(rig.server, (Runnable) () -> {
                        cleanAtConfirm.set(res.inboundExcess().reserved(rig.server) == 0
                                && res.node().reserved(Pool.INFLATER) == 0
                                && res.outbound().reserved(rig.server) == 0
                                && driver.receiver().slots().workspaceInUse() == 0);
                        confirm.run();
                    });
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            res.dispatchShed();
            assertEquals(CLOSE_RESOURCE, closed(rig.serverGame).code);
            NRig.onLoop(rig.server, () -> null); // the callback follows notification on the same task
            assertTrue(cleanAtConfirm.get(), "all owners release before the shed confirmation");
            assertEquals(0, res.inboundExcess().reserved(rig.server));
            assertEquals(0, res.node().reserved(Pool.INFLATER));
            assertEquals(0, res.outbound().reserved(rig.server));
            assertEquals(0, driver.receiver().slots().workspaceInUse());
            assertThrows(IllegalStateException.class,
                    () -> res.inboundRanking().setRetained(rig.server, 0, System.nanoTime()));
            assertThrows(IllegalStateException.class,
                    () -> res.outboundRanking().setRetained(rig.server, 0, System.nanoTime()));
            res.inboundExcess().cancel(requester);
            long granted = res.inboundExcess().reserved(requester);
            if (granted > 0) res.inboundExcess().release(requester, granted);
            long held = res.inboundExcess().reserved(other);
            if (held > 0) res.inboundExcess().release(other, held);
        }
    }

    @Test
    void shedVictimClosesWith0x12() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources res = rig.config.serverResources;
            long baseline = res.outbound().reserved(rig.server);
            for (int i = 2; i < 4; i++) {
                rig.server.holdWrites(i, true);
            }
            rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(1, 1, repeat('s', 65536)),
                    NRig.chunkUncompressed(1, 1));
            assertTrue(SessionRigSupport.await(() -> res.outbound().reserved(rig.server) > baseline + 65536, 10_000),
                    "the held chunk is charged");
            Object other = new Object();
            long free = res.node().limit(Pool.OUTBOUND) - res.node().reserved(Pool.OUTBOUND);
            long ticket = res.outbound().reserveTicket(other, InetAddress.getByName("10.0.0.2"), null,
                    free + 32768);
            assertFalse(res.outbound().takeGrant(ticket), "the request needs a victim");
            res.dispatchShed();
            assertEquals(CLOSE_RESOURCE, closed(rig.serverGame).code);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS));
            assertArrayEquals("resource".getBytes(StandardCharsets.US_ASCII),
                    rig.net.clientSawReason.get(15, TimeUnit.SECONDS));
            assertTrue(SessionRigSupport.await(() -> res.outbound().takeGrant(ticket), 10_000),
                    "the victim's bytes go to the requester once its close is confirmed");
            assertEquals(0, res.outbound().reserved(rig.server));
        }
    }

    @Test
    void outboundChargeHeldUntilWriteCompletes() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources res = rig.config.serverResources;
            long baseline = res.outbound().reserved(rig.server);
            int stream = shardStream(5, 5, 2);
            Captor captor = new Captor();
            NRig.onLoop(rig.server, () -> rig.server.channelOf(stream).pipeline().addFirst(captor));
            rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(5, 5, repeat('w', 16384)),
                    NRig.chunkUncompressed(5, 5));
            assertTrue(SessionRigSupport.await(() -> captor.size() > 0, 10_000), "the chunk's write reached the channel");
            Thread.sleep(200);
            assertTrue(res.outbound().reserved(rig.server) > baseline + 16384,
                    "the charge stays while the write is pending: " + res.outbound().reserved(rig.server));
            NRig.onLoop(rig.server, () -> {
                captor.release();
                return null;
            });
            assertTrue(SessionRigSupport.await(() -> res.outbound().reserved(rig.server) == baseline, 10_000),
                    "the charge is released once the write completes");
            assertTrue(SessionRigSupport.await(() -> rig.clientGame.count(NRig.CHUNK_ID) == 1, 10_000));
        }
    }

    /** Holds every outbound write until {@link #release}; after that it passes writes through. */
    static final class Captor extends ChannelOutboundHandlerAdapter {
        private final List<Object[]> held = new ArrayList<Object[]>();
        private ChannelHandlerContext ctx;
        private boolean open;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (open) {
                ctx.write(msg, promise);
                return;
            }
            synchronized (held) {
                held.add(new Object[] {msg, promise});
            }
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (open) {
                ctx.flush();
            }
        }

        int size() {
            synchronized (held) {
                return held.size();
            }
        }

        void release() {
            open = true;
            synchronized (held) {
                for (Object[] w : held) {
                    ctx.write(w[0], (ChannelPromise) w[1]);
                }
                held.clear();
            }
            ctx.flush();
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }
}
