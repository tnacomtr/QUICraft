// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.ResourcePools.Pool;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.time.StallClock;

/** Session-owner stall accounting: cumulative factories, one owner clock, and local resource closes (13.10). */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NStreamSessionStallAccountingTest {
    private static final int CLOSE_ORDERING = 0x11;
    private static final int CLOSE_RESOURCE = 0x12;

    @Test
    void productionAndClientResourcesAreCumulativeWithOneOwnerClock() {
        SessionResources production = SessionResources.production(null);
        assertSame(production.progressWatch(), production.stallClock());
        assertThrows(IllegalStateException.class, production::executorWatch);
        assertThrows(IllegalStateException.class, production::stallPairing);
        NStreamSession.Timing timing = NStreamSession.Timing.defaults(production);
        assertSame(production.clock(), timing.clock, "the default timing uses the resources' owner clock");
        assertEquals(NStreamSession.OPEN_TIMEOUT_NANOS, timing.openTimeoutNanos);
        assertEquals(NStreamSession.END_LINGER_NANOS, timing.endLingerNanos);
        assertEquals(TimeUnit.SECONDS.toNanos(1), timing.tickNanos);

        SessionResources client = SessionResources.client();
        assertSame(client, SessionResources.client(), "the client endpoint keeps one process-wide set");
        assertSame(client.progressWatch(), client.stallClock());
    }

    @Test
    void explicitTimingRejectsAMismatchedClockBeforeAnyCharge() throws Exception {
        LongSupplier owner = new AtomicLong()::get;
        LongSupplier foreign = new AtomicLong()::get;
        NRig.Config config = new NRig.Config();
        config.serverResources = NRig.resources(owner);
        config.serverTiming = new NStreamSession.Timing(foreign, TimeUnit.SECONDS.toNanos(10),
                TimeUnit.SECONDS.toNanos(10), TimeUnit.MILLISECONDS.toNanos(50));
        ExecutionException mismatch = assertThrows(ExecutionException.class, () -> NRig.start(config));
        assertTrue(mismatch.getCause() instanceof IllegalArgumentException, String.valueOf(mismatch.getCause()));
        assertTrue(String.valueOf(mismatch.getCause().getMessage()).contains("owner clock"));
        assertReleased(config.serverResources);

        SessionResources legacy = new SessionResources(new NodePools(64L << 20, 64L << 20, NRig.OUTBOUND_BYTES, 10,
                false, NodePools.MIN_EXCESS), NRig.OUTBOUND_BYTES, NRig.OUTBOUND_BYTES, NRig.OUTBOUND_BYTES,
                16L << 20, 16L << 20, SessionResources.defaultProfile(), NRig.SYSTEM_CLOCK);
        NRig.Config legacyConfig = new NRig.Config();
        legacyConfig.serverResources = legacy;
        legacyConfig.serverTiming = new NStreamSession.Timing(NRig.SYSTEM_CLOCK, TimeUnit.SECONDS.toNanos(10),
                TimeUnit.SECONDS.toNanos(10), TimeUnit.MILLISECONDS.toNanos(50));
        ExecutionException nonCumulative = assertThrows(ExecutionException.class, () -> NRig.start(legacyConfig));
        assertTrue(nonCumulative.getCause() instanceof IllegalArgumentException,
                String.valueOf(nonCumulative.getCause()));
        assertTrue(String.valueOf(nonCumulative.getCause().getMessage()).contains("cumulative"));
        assertReleased(legacy);
    }

    @Test
    void sessionsRejectBoundaryStallReports() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            assertThrows(UnsupportedOperationException.class, () -> rig.server.stallStarted(0L));
            assertThrows(UnsupportedOperationException.class, () -> rig.server.stallEnded(0L));
            assertThrows(UnsupportedOperationException.class, () -> rig.client.stallStarted(0L));
            assertThrows(UnsupportedOperationException.class, () -> rig.client.stallEnded(0L));
        }
    }

    @Test
    void unavailableSourceOnSendClosesLocallyAsResource() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources resources = rig.config.serverResources;
            resources.progressWatch().invalidate();
            assertFalse(rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID,
                    NRig.frame(NRig.CONTROL_ID, "after invalidation")).isAssigned());
            localResourceClose(rig.serverGame);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.clientSawClose.get(10, TimeUnit.SECONDS));
            assertArrayEquals("resource".getBytes(StandardCharsets.US_ASCII),
                    rig.net.clientSawReason.get(10, TimeUnit.SECONDS));
            assertReleased(resources);
        }
    }

    @Test
    void unavailableSourceOnAckAndEndClosesLocallyAsResource() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources resources = rig.config.serverResources;
            resources.progressWatch().invalidate();
            assertFalse(rig.server.createAck(1, new byte[] {1}, 8));
            localResourceClose(rig.serverGame);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.clientSawClose.get(10, TimeUnit.SECONDS));
            assertReleased(resources);
        }
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources resources = rig.config.serverResources;
            resources.progressWatch().invalidate();
            rig.server.end();
            localResourceClose(rig.serverGame);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.clientSawClose.get(10, TimeUnit.SECONDS));
            assertReleased(resources);
        }
    }

    @Test
    void unavailableSourceOnReceiveClosesLocallyAsResource() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources resources = rig.config.clientResources;
            NStreamSessionLoopbackTest.Captor captor = new NStreamSessionLoopbackTest.Captor();
            NRig.onLoop(rig.server, () -> {
                rig.server.channelOf(LogicalStream.CONTROL).pipeline().addFirst(captor);
                return null;
            });
            SentPacket view = rig.server.send(ProtocolState.PLAY, NRig.VIEW_ID,
                    NRig.frame(NRig.VIEW_ID, "held view"));
            assertTrue(view.isAssigned());
            assertTrue(rig.server.send(ProtocolState.PLAY, NRig.CHUNK_ID, NRig.chunkFrame(0, 0, "held chunk"),
                    NRig.chunkUncompressed(0, 0), view).isAssigned());
            assertTrue(SessionRigSupport.await(() -> captor.size() > 0, 10_000), "the VIEW write is held");
            assertTrue(SessionRigSupport.await(() -> rig.server.sendDriver().queuedRecords() <= 1, 10_000),
                    "the held VIEW is the only record still queued");
            resources.progressWatch().invalidate();
            for (int i = 0; i < 100 && !rig.clientGame.closed.isDone(); i++) {
                NRig.onLoop(rig.client, () -> {
                    rig.client.tick();
                    return null;
                });
                Thread.sleep(20);
            }
            localResourceClose(rig.clientGame);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.serverSawClose.get(10, TimeUnit.SECONDS));
            assertEquals(0, rig.clientGame.count(NRig.CHUNK_ID), "the blocked chunk never executes");
            assertReleased(resources);
        }
    }

    @Test
    void unavailableInsideALoopTaskClosesLocallyAsResource() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            SessionResources resources = rig.config.serverResources;
            resources.progressWatch().invalidate();
            NRig.onLoop(rig.server, () -> {
                Method guarded = NStreamSession.class.getDeclaredMethod("guarded", Runnable.class);
                guarded.setAccessible(true);
                guarded.invoke(rig.server, (Runnable) () -> {
                    throw new StallClock.UnavailableException("guarded probe");
                });
                return null;
            });
            localResourceClose(rig.serverGame);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.clientSawClose.get(10, TimeUnit.SECONDS));
            assertReleased(resources);
        }
    }

    @Test
    void unavailableFromGameDecodeIsResourceNotPeerViolation() throws Exception {
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            rig.clientGame.decodeFailure = new StallClock.UnavailableException("client decode probe");
            rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "decode"));
            localResourceClose(rig.clientGame);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.serverSawClose.get(10, TimeUnit.SECONDS));
            assertReleased(rig.config.clientResources);
        }
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            rig.serverGame.decodeFailure = new StallClock.UnavailableException("server decode probe");
            rig.client.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "decode"));
            localResourceClose(rig.serverGame);
            assertEquals(CLOSE_RESOURCE, (int) rig.net.clientSawClose.get(10, TimeUnit.SECONDS));
            assertReleased(rig.config.serverResources);
        }
    }

    @Test
    void sessionPathsUseOnlyTheCumulativeAccessors() throws Exception {
        SessionResources resources = NRig.resources();
        assertThrows(IllegalStateException.class, resources::stallPairing);
        assertThrows(IllegalStateException.class, resources::executorWatch);
        try (NRig rig = NRig.start(2)) {
            rig.activateBoth();
            assertTrue(rig.server.send(ProtocolState.PLAY, NRig.CONTROL_ID,
                    NRig.frame(NRig.CONTROL_ID, "normal")).isAssigned());
            rig.server.end();
            assertEquals(0, rig.clientGame.closed.get(10, TimeUnit.SECONDS).code);
            assertEquals(0, rig.serverGame.closed.get(10, TimeUnit.SECONDS).code);
            assertReleased(rig.config.serverResources);
        }
    }

    private static SessionRigSupport.Closed localResourceClose(NRig.Game game) throws Exception {
        SessionRigSupport.Closed close = game.closed.get(10, TimeUnit.SECONDS);
        assertEquals(CLOSE_RESOURCE, close.code, close.toString());
        assertFalse(close.byPeer, "the close is local, not the peer's: " + close);
        assertTrue(close.cause instanceof StallClock.UnavailableException, "cause: " + close.cause);
        assertTrue(close.code != CLOSE_ORDERING, "never an ordering close");
        return close;
    }

    private static void assertReleased(SessionResources resources) {
        assertEquals(0, resources.node().reserved(Pool.OUTBOUND), "outbound charges released");
        assertEquals(0, resources.node().reserved(Pool.INBOUND_EXCESS), "inbound excess released");
        assertEquals(0, resources.node().reserved(Pool.INFLATER), "inflater released");
    }
}
