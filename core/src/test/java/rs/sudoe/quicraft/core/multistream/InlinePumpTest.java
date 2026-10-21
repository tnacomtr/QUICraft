// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.sudoe.quicraft.core.routing.ProtocolState;

/**
 * The /3 inline receive pump (-Dquicraft.v3.inlinePump) over loopback QUIC: ordered delivery in both directions
 * with bursts longer than MAX_STEPS, no nested receive on the loop from either entry (pump and tick), replies sent
 * from inside a delivery, and a throwing delivery failing the session closed. Each test pins the flag explicitly
 * through {@link NRig.Config}.
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
     * Forced re-entrancy from the pump entry: the first receive step after arming issues a synchronous read-complete
     * on the client's control stream while that step is on the stack (inside the pump round or a queued pump). The
     * nested call must defer to a later pump, so the depth stays 1 and every record still arrives in order. The
     * guards in pumpInline and pumpNow cover each other: removing either alone still passes, removing both fails
     * this test (depth 4 when measured).
     */
    @Test
    void readCompleteFromInsideAPumpRoundNeverNestsTheReceive() throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = true;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            // Held, so the periodic tick cannot take the armed hook: the first receive step after release is a pump's.
            NRig.onLoop(rig.client, () -> {
                rig.client.holdPumpForTests();
                return null;
            });
            AtomicInteger nested = new AtomicInteger();
            rig.clientDepth.arm(nestedReadComplete(rig, nested));
            List<String> toClient = new ArrayList<String>();
            List<String> toServer = new ArrayList<String>();
            for (int i = 0; i < 60; i++) {
                send(rig.server, "c" + i, toClient);
                send(rig.client, "s" + i, toServer);
            }
            NRig.onLoop(rig.client, () -> {
                rig.client.releasePumpForTests();
                return null;
            });
            assertTrue(SessionRigSupport.await(() -> labels(rig.clientGame).size() == 60, WAIT_MILLIS),
                    () -> "records to the client: " + labels(rig.clientGame));
            assertTrue(SessionRigSupport.await(() -> labels(rig.serverGame).size() == 60, WAIT_MILLIS),
                    () -> "records to the server: " + labels(rig.serverGame));
            assertEquals(1, nested.get(), "the read-complete was issued from inside a receive step");
            assertEquals(1, rig.clientDepth.maxDepth(), "the nested read-complete did not nest a receive");
            assertEquals(toClient, labels(rig.clientGame), "the deferred pump delivered client order");
            assertEquals(toServer, labels(rig.serverGame), "the deferred pump delivered server order");
        }
    }

    /**
     * Forced re-entrancy from the tick entry: the tick's own receive step issues the synchronous read-complete. The
     * tick holds the pump's guard, so the nested call must defer. Removing the tick's guard makes this test fail,
     * because the nested pump then runs a receive step inside the tick's step (depth 2).
     */
    @Test
    void readCompleteFromInsideTheTickNeverNestsTheReceive() throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = true;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            List<String> toClient = new ArrayList<String>();
            List<String> toServer = new ArrayList<String>();
            for (int i = 0; i < 20; i++) {
                send(rig.server, "c" + i, toClient);
                send(rig.client, "s" + i, toServer);
            }
            assertTrue(SessionRigSupport.await(() -> labels(rig.clientGame).size() == 20, WAIT_MILLIS),
                    () -> "records to the client: " + labels(rig.clientGame));
            assertTrue(SessionRigSupport.await(() -> labels(rig.serverGame).size() == 20, WAIT_MILLIS),
                    () -> "records to the server: " + labels(rig.serverGame));
            AtomicInteger nested = new AtomicInteger();
            int before = rig.clientDepth.entries();
            // Arm and tick in one loop task, so the periodic tick cannot run in between and take the hook.
            NRig.onLoop(rig.client, () -> {
                rig.clientDepth.arm(nestedReadComplete(rig, nested));
                rig.client.tick();
                return null;
            });
            assertTrue(rig.clientDepth.entries() > before, "the tick entered a receive step");
            assertEquals(1, nested.get(), "the tick's receive step issued the read-complete");
            assertEquals(1, rig.clientDepth.maxDepth(), "the tick's nested read-complete did not nest a receive");
            // The deferred pump still delivers what follows.
            for (int i = 20; i < 40; i++) {
                send(rig.server, "c" + i, toClient);
                send(rig.client, "s" + i, toServer);
            }
            assertTrue(SessionRigSupport.await(() -> labels(rig.clientGame).size() == 40, WAIT_MILLIS),
                    () -> "records after the tick to the client: " + labels(rig.clientGame));
            assertTrue(SessionRigSupport.await(() -> labels(rig.serverGame).size() == 40, WAIT_MILLIS),
                    () -> "records after the tick to the server: " + labels(rig.serverGame));
            assertEquals(toClient, labels(rig.clientGame), "client order after the tick");
            assertEquals(toServer, labels(rig.serverGame), "server order after the tick");
        }
    }

    /**
     * One pump round must not drain a burst of more than MAX_STEPS. The client's pump is held, the whole burst is sent
     * and buffered on the client's receiver, then one release starts the round. The first step queues a task on the
     * client's loop. The round yields after 64 steps with its continuation queued behind that task, so the task runs
     * between round 1 and round 2 and sees fewer than BURST delivered. Without MAX_STEPS yielding, one round would
     * deliver the whole buffered burst before the queued task runs, and the test would fail.
     */
    @Test
    void aBurstLongerThanOneRoundYieldsToQueuedWork() throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = true;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            List<String> events = Collections.synchronizedList(new ArrayList<String>());
            NRig.onLoop(rig.client, () -> {
                rig.client.holdPumpForTests();
                rig.client.configurePathProbe(events::add);
                return null;
            });
            // Held: no receive step runs, so the first step after release is the round's own first step.
            rig.clientDepth.arm(() -> rig.client.eventLoop().execute(
                    () -> events.add("queued@" + labels(rig.clientGame).size())));
            List<String> toClient = new ArrayList<String>();
            for (int i = 0; i < BURST; i++) {
                send(rig.server, "c" + i, toClient);
            }
            int buffered = awaitBufferedStable(rig.client, 0);
            assertTrue(buffered > 0, "the burst is buffered on the client's receiver: " + buffered);
            NRig.onLoop(rig.client, () -> {
                rig.client.releasePumpForTests();
                return null;
            });
            assertTrue(SessionRigSupport.await(() -> labels(rig.clientGame).size() == BURST, WAIT_MILLIS),
                    () -> "burst to the client stalled at " + labels(rig.clientGame).size() + " of " + BURST);
            assertEquals(toClient, labels(rig.clientGame), "burst order");
            int rounds = countOf(events, "pump.round");
            assertTrue(rounds >= 2, "the burst took at least two pump rounds: " + events);
            assertEquals(1, countOf(events, "queued@"), "the queued task ran once: " + events);
            int queued = 0;
            while (!events.get(queued).startsWith("queued@")) {
                queued++;
            }
            int firstRound = indexFrom(events, "pump.round", 0);
            int secondRound = indexFrom(events, "pump.round", firstRound + 1);
            assertTrue(firstRound < queued && queued < secondRound,
                    "the queued task ran between the first and second round: " + events);
            int seen = Integer.parseInt(events.get(queued).substring("queued@".length()));
            assertTrue(seen < BURST, "the queued task ran before the burst completed (saw " + seen + ")");
        }
    }

    /** Waits until the buffered byte count of {@code stream} is non-zero and unchanged for a run of polls. */
    private static int awaitBufferedStable(NStreamSession session, int stream) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        int last = -1;
        int stableRuns = 0;
        while (System.nanoTime() < deadline) {
            int now = NRig.onLoop(session, () -> session.bufferedForTests(stream));
            stableRuns = now > 0 && now == last ? stableRuns + 1 : 0;
            if (stableRuns >= 20) {
                return now;
            }
            last = now;
            Thread.sleep(5);
        }
        return last;
    }

    private static int countOf(List<String> events, String prefix) {
        int n = 0;
        for (String event : events) {
            if (event.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /**
     * The client answers "s0" from inside its own delivery. The path probe shows the delivery ran inside the inline
     * pump round, and that the reply was drained by that round's inline drain, before any queued drain task. The
     * reply then reaches the server, and the server's CONTROL stream keeps its order.
     */
    @Test
    void replySentFromInsideADeliveryArrivesInOrder() throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = true;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            List<String> events = Collections.synchronizedList(new ArrayList<String>());
            NRig.onLoop(rig.client, () -> {
                rig.client.configurePathProbe(events::add);
                return null;
            });
            AtomicBoolean replied = new AtomicBoolean();
            rig.clientGame.onDeliver = () -> {
                List<NRig.Got> units = rig.clientGame.units();
                NRig.Got last = units.get(units.size() - 1);
                if (!replied.get() && last.packets.size() == 1 && "s0".equals(((NRig.Pkt) last.packets.get(0)).label)) {
                    replied.set(true);
                    events.add("reply");
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
            assertTrue(SessionRigSupport.await(() -> afterReplyInlineEnded(events), WAIT_MILLIS),
                    () -> "the inline round ended after the reply: " + events);
            int reply = events.indexOf("reply");
            assertTrue(reply >= 0, "the reply was recorded: " + events);
            int begin = events.subList(0, reply).lastIndexOf("inline.begin");
            int end = indexFrom(events, "inline.end", reply);
            assertTrue(begin >= 0 && end > reply, "the delivery ran inside an inline pump round: " + events);
            assertFalse(events.subList(begin, reply).contains("inline.end"),
                    "no inline round had ended before the reply: " + events);
            int firstDrain = -1;
            for (int i = reply + 1; i < events.size() && firstDrain < 0; i++) {
                if (events.get(i).startsWith("drain.")) {
                    firstDrain = i;
                }
            }
            assertTrue(firstDrain > reply && "drain.inline".equals(events.get(firstDrain)) && firstDrain < end,
                    "the reply was drained inline, before any queued drain task: " + events);
            assertEquals(toClient, labels(rig.clientGame), "the client's deliveries keep their order");
            assertEquals(Collections.singletonList("reply"), labels(rig.serverGame),
                    "the server got exactly the one reply");
            assertFalse(rig.clientGame.closed.isDone());
            assertFalse(rig.serverGame.closed.isDone());
        }
    }

    /**
     * A delivery that throws inside the inline stack fails the session closed: the close carries the cause, the peer
     * sees the same code, and nothing after it is delivered. The recorders at the tail of every server stream pipeline
     * and of the server connection pipeline must not see the delivery exception (or a wrapper of it): the pump's own
     * guard absorbs it. Other errors from the close path itself are not asserted here.
     */
    @Test
    void throwingDeliveryInsideTheInlineStackFailsClosed() throws Exception {
        NRig.Config config = new NRig.Config();
        config.inlinePump = true;
        try (NRig rig = NRig.start(config)) {
            rig.activateBoth();
            List<Throwable> reached = Collections.synchronizedList(new ArrayList<Throwable>());
            for (int i = 0; i < config.shards + 3; i++) {
                QuicStreamChannel stream = NRig.channel(rig.server, i);
                stream.eventLoop().submit(() -> stream.pipeline().addLast(recorder(reached))).get(10, TimeUnit.SECONDS);
            }
            rig.serverConnection.eventLoop().submit(
                    () -> rig.serverConnection.pipeline().addLast(recorder(reached))).get(10, TimeUnit.SECONDS);
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
            for (Throwable t : reached) {
                assertFalse(t == failure || t.getCause() == failure,
                        "the delivery exception reached the Netty pipeline: " + t);
            }
        }
    }

    /** Arms a one-shot synchronous read-complete on the client's control stream, counting it in {@code nested}. */
    private static Runnable nestedReadComplete(NRig rig, AtomicInteger nested) {
        return () -> {
            nested.incrementAndGet();
            rig.client.channelOf(0).pipeline().fireChannelReadComplete();
        };
    }

    /** A tail handler that records exceptions and does not handle them further. */
    private static ChannelInboundHandlerAdapter recorder(List<Throwable> reached) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                reached.add(cause);
            }
        };
    }

    /** True once the reply's inline round has closed (inline.end after the reply) on the client's loop. */
    private static boolean afterReplyInlineEnded(List<String> events) {
        int reply = events.indexOf("reply");
        return reply >= 0 && indexFrom(events, "inline.end", reply) >= 0;
    }

    /** The first index at or after {@code from} holding {@code value}, or -1. */
    private static int indexFrom(List<String> events, String value, int from) {
        for (int i = Math.max(0, from); i < events.size(); i++) {
            if (value.equals(events.get(i))) {
                return i;
            }
        }
        return -1;
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
