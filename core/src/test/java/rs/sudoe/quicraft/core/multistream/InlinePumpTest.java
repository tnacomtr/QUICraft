// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The /3 inline receive pump (-Dquicraft.v3.inlinePump) over loopback QUIC: ordered delivery in both directions
 * with bursts longer than MAX_STEPS, no nested receive on the loop, replies sent from inside a delivery, and a
 * throwing delivery failing the session closed. Each test pins the flag explicitly through {@link NRig.Config}.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class InlinePumpTest {
    /** Longer than NStreamSession.MAX_STEPS (64), so one burst needs more than one pump round. */
    private static final int BURST = 200;
    private static final long WAIT_MILLIS = 15_000;

    /** Records every burst both ways, then checks that each side got every record in send order. */
    private static void burstBothWays(boolean inlinePump) throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = inlinePump;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            List<String> toClient = new ArrayList<String>();
            List<String> toServer = new ArrayList<String>();
            // Many small records first: each direction, one record at a time.
            for (int i = 0; i < 5; i++) {
                send(rig.server, "c" + i, toClient);
                send(rig.client, "s" + i, toServer);
            }
            assertTrue(SessionRigSupport.await(() -> labels(rig.clientGame).size() == 5, WAIT_MILLIS),
                    () -> "small records to the client: " + labels(rig.clientGame));
            // Then one burst longer than MAX_STEPS in each direction.
            for (int i = 5; i < BURST; i++) {
                send(rig.server, "c" + i, toClient);
            }
            for (int i = 5; i < BURST; i++) {
                send(rig.client, "s" + i, toServer);
            }
            assertTrue(SessionRigSupport.await(() -> labels(rig.clientGame).size() == BURST, WAIT_MILLIS),
                    () -> "burst to the client stalled at " + labels(rig.clientGame).size() + " of " + BURST);
            assertTrue(SessionRigSupport.await(() -> labels(rig.serverGame).size() == BURST, WAIT_MILLIS),
                    () -> "burst to the server stalled at " + labels(rig.serverGame).size() + " of " + BURST);
            assertEquals(toClient, labels(rig.clientGame), "client order, inline=" + inlinePump);
            assertEquals(toServer, labels(rig.serverGame), "server order, inline=" + inlinePump);
            assertFalse(rig.clientGame.closed.isDone(), "client stays open");
            assertFalse(rig.serverGame.closed.isDone(), "server stays open");
        }
    }

    @Test
    void burstsDeliverInOrderBothWaysWithInlinePump() throws Exception {
        burstBothWays(true);
    }

    @Test
    void burstsDeliverInOrderBothWaysWithInlinePumpOff() throws Exception {
        burstBothWays(false);
    }

    /**
     * A read that completes synchronously inside the pump, and the tick's own receive step, never nest: the
     * receive observer on each session sees a maximum depth of 1, and the tick does enter a receive step.
     */
    @Test
    void receiveNeverNestsFromThePumpOrFromTick() throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = true;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            List<String> toClient = new ArrayList<String>();
            List<String> toServer = new ArrayList<String>();
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 40; i++) {
                    send(rig.server, "c" + round + "-" + i, toClient);
                    send(rig.client, "s" + round + "-" + i, toServer);
                }
                int expected = toClient.size();
                assertTrue(SessionRigSupport.await(() -> labels(rig.clientGame).size() == expected, WAIT_MILLIS),
                        "round " + round + " to the client");
                assertTrue(SessionRigSupport.await(() -> labels(rig.serverGame).size() == toServer.size(),
                        WAIT_MILLIS), "round " + round + " to the server");
                int before = rig.clientDepth.entries();
                NRig.onLoop(rig.client, () -> {
                    rig.client.tick();
                    return null;
                });
                NRig.onLoop(rig.server, () -> {
                    rig.server.tick();
                    return null;
                });
                assertTrue(rig.clientDepth.entries() > before, "the client's tick entered a receive step");
            }
            assertEquals(1, rig.serverDepth.maxDepth(), "server receive depth never nests");
            assertEquals(1, rig.clientDepth.maxDepth(), "client receive depth never nests");
            assertEquals(toClient, labels(rig.clientGame), "client order");
            assertEquals(toServer, labels(rig.serverGame), "server order");
        }
    }

    /**
     * The client answers "s0" from inside its own delivery. The reply is sent on the loop while the inline pump is
     * still on the stack, reaches the server, and the server's CONTROL stream keeps its order.
     */
    @Test
    void replySentFromInsideADeliveryArrivesInOrder() throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = true;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            AtomicBoolean replied = new AtomicBoolean();
            rig.clientGame.onDeliver = () -> {
                List<NRig.Got> units = rig.clientGame.units();
                NRig.Got last = units.get(units.size() - 1);
                if (!replied.get() && last.packets.size() == 1 && "s0".equals(((NRig.Pkt) last.packets.get(0)).label)) {
                    replied.set(true);
                    rig.client.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, "reply"));
                }
            };
            List<String> toClient = new ArrayList<String>();
            send(rig.server, "s0", toClient);
            send(rig.server, "s1", toClient);
            send(rig.server, "s2", toClient);
            assertTrue(SessionRigSupport.await(() -> labels(rig.serverGame).size() == 1, WAIT_MILLIS),
                    () -> "the reply reached the server: " + labels(rig.serverGame));
            assertTrue(replied.get(), "the client replied from inside a delivery");
            assertTrue(SessionRigSupport.await(() -> labels(rig.clientGame).size() == 3, WAIT_MILLIS),
                    () -> "the client delivered s0..s2: " + labels(rig.clientGame));
            assertEquals(toClient, labels(rig.clientGame), "the client's deliveries keep their order");
            assertEquals(Collections.singletonList("reply"), labels(rig.serverGame),
                    "the server got exactly the one reply");
            assertFalse(rig.clientGame.closed.isDone());
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    /**
     * A delivery that throws inside the inline stack fails the session closed: the close carries the cause, the
     * peer sees the same code, nothing escapes to Netty as an unhandled exception, and nothing after it is delivered.
     */
    @Test
    void throwingDeliveryInsideTheInlineStackFailsClosed() throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = true;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            RuntimeException failure = new IllegalStateException("game delivery exploded");
            rig.serverGame.failureLabel = "x0";
            rig.serverGame.deliverFailure = failure;
            List<String> unused = new ArrayList<String>();
            send(rig.client, "x0", unused);
            send(rig.client, "x1", unused);
            send(rig.client, "x2", unused);
            SessionRigSupport.Closed closed = rig.serverGame.closed.get(15, TimeUnit.SECONDS);
            assertSame(failure, closed.cause, "the close carries the delivery's exception");
            int code = (int) rig.net.clientSawClose.get(15, TimeUnit.SECONDS);
            assertEquals(closed.code, code, "the peer sees the same close code");
            List<String> delivered = labels(rig.serverGame);
            assertFalse(delivered.contains("x1") || delivered.contains("x2"),
                    "nothing after the failing delivery was delivered: " + delivered);
        }
    }

    private static void send(NStreamSession from, String label, List<String> expected) {
        from.send(ProtocolState.PLAY, NRig.CONTROL_ID, NRig.frame(NRig.CONTROL_ID, label));
        expected.add(label);
    }

    /** The CONTROL labels a game has delivered, in delivery order. */
    private static List<String> labels(NRig.Game game) {
        List<String> out = new ArrayList<String>();
        for (NRig.Pkt pkt : game.packets()) {
            if (pkt.id == NRig.CONTROL_ID) {
                out.add(pkt.label);
            }
        }
        return out;
    }
}
