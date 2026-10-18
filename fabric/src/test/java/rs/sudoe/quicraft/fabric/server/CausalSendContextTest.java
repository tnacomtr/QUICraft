// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The explicit handoff of one envelope through a pending-send task and an event-loop runnable
 * (U3-W2a): each deferred action runs the vanilla action exactly once and sees exactly its own
 * envelope, on whatever thread runs it. No packet lookup and no thread-local is involved.
 */
class CausalSendContextTest {
    private static CausalSendEnvelope envelope(CausalSendContext context) {
        return context.newEnvelope("owner", CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE);
    }

    private static Thread runOnNewThread(Runnable action) throws InterruptedException {
        Thread thread = new Thread(action, "causal-test");
        thread.start();
        thread.join(10_000L);
        assertFalse(thread.isAlive(), "the helper thread finished");
        return thread;
    }

    @Test
    void everyLogicalSendGetsADistinctConnectionLocalId() {
        CausalSendContext first = new CausalSendContext("c1");
        // Two logical sends in a row get distinct identities even if the caller reuses one packet
        // object; the context never receives the packet and so cannot dedupe or look it up.
        CausalSendEnvelope repeatedOne = envelope(first);
        CausalSendEnvelope repeatedTwo = envelope(first);

        assertEquals(1, repeatedOne.sendId());
        assertEquals(2, repeatedTwo.sendId());
        assertNotEquals(repeatedOne.sendId(), repeatedTwo.sendId());

        CausalSendContext other = new CausalSendContext("c2");
        assertEquals(1, envelope(other).sendId(), "ids are connection-local, not global");
    }

    @Test
    void epochIsSnapshottedAtCreationAndNeverRelabelled() {
        CausalSendContext context = new CausalSendContext("connection");
        context.setEpoch(4L);
        CausalSendEnvelope envelope = envelope(context);
        context.setEpoch(5L);

        assertEquals(4L, envelope.epoch(), "a later epoch change does not relabel queued work");
        assertEquals(5L, envelope(context).epoch(), "a new send snapshots the new epoch");
        assertThrows(IllegalArgumentException.class, () -> context.setEpoch(-1L));
    }

    @Test
    void nestedSendsRestoreTheOuterEnvelope() {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendEnvelope outer = envelope(context);
        CausalSendEnvelope inner = envelope(context);

        CausalSendContext.Frame outerFrame = context.enter(outer);
        assertSame(outer, context.currentEnvelope());
        CausalSendContext.Frame innerFrame = context.enter(inner);
        assertSame(inner, context.currentEnvelope());
        context.exit(innerFrame);
        assertSame(outer, context.currentEnvelope());
        context.exit(outerFrame);
        assertNull(context.currentEnvelope());
    }

    @Test
    void aSnapshottedSendIsInvisibleUntilItsOwnDispatchStarts() {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendEnvelope queued = envelope(context);
        CausalSendContext.Frame frame = context.enter(queued, false);

        assertNull(context.currentEnvelope(),
                "a send that has not reached its own sendPacket cannot be read by an unrelated write");
        assertSame(queued, context.currentFrame().envelope(),
                "the snapshotted send is still available to its own pending handoff");

        context.currentFrame().dispatching(true);
        assertSame(queued, context.currentEnvelope(), "the send's own dispatch is visible to its write");
        context.currentFrame().dispatching(false);
        assertNull(context.currentEnvelope());
        context.exit(frame);
        assertNull(context.currentFrame());
    }

    @Test
    void framesArePerProducerThread() throws Exception {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendEnvelope main = envelope(context);
        CausalSendContext.Frame mainFrame = context.enter(main);
        AtomicReference<CausalSendEnvelope> otherThreadInside = new AtomicReference<>();
        AtomicReference<CausalSendEnvelope> otherThreadAfter = new AtomicReference<>();

        runOnNewThread(() -> {
            otherThreadInside.set(context.currentEnvelope());
            CausalSendContext.Frame frame = context.enter(envelope(context));
            context.exit(frame);
            otherThreadAfter.set(context.currentEnvelope());
        });

        assertNull(otherThreadInside.get(), "one producer thread's frame is not visible to another");
        assertNull(otherThreadAfter.get(), "the helper thread's frame is gone after its own exit");
        assertSame(main, context.currentEnvelope(), "the main thread's frame is untouched by the helper");
        context.exit(mainFrame);
        assertNull(context.currentEnvelope());
    }

    @Test
    void pendingTaskRunsOnceWithItsEnvelopeOnTheDrainingThread() throws Exception {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendEnvelope queued = envelope(context);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CausalSendEnvelope> inside = new AtomicReference<>();
        Consumer<String> vanilla = value -> {
            calls.incrementAndGet();
            inside.set(context.currentEnvelope());
            value.length();
        };

        Consumer<String> carrying = context.carryTask(vanilla, queued);
        assertNull(context.currentEnvelope(), "nothing is current before the task runs");
        runOnNewThread(() -> carrying.accept("packet"));

        assertEquals(1, calls.get(), "the vanilla task ran exactly once");
        assertSame(queued, inside.get(), "the task saw the envelope it carried");
        assertNull(context.currentEnvelope(), "the frame is closed after the task");
    }

    @Test
    void pendingReplayAndReorderingKeepEachEnvelopesOwnIdentity() throws Exception {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendEnvelope first = envelope(context);
        CausalSendEnvelope second = envelope(context);
        List<String> events = new ArrayList<>();
        List<CausalSendEnvelope> observed = new ArrayList<>();
        Deque<Consumer<String>> pending = new ConcurrentLinkedDeque<>();

        pending.add(context.carryTask(value -> {
            events.add(value);
            observed.add(context.currentEnvelope());
        }, first));
        pending.add(context.carryTask(value -> {
            events.add(value);
            observed.add(context.currentEnvelope());
        }, second));

        // Drain in reverse on another thread: the envelope travels with its task, not with
        // execution order or a packet identity.
        runOnNewThread(() -> {
            Consumer<String> secondTask = pending.pollLast();
            Consumer<String> firstTask = pending.poll();
            secondTask.accept("second");
            firstTask.accept("first");
        });

        assertEquals(List.of("second", "first"), events);
        assertSame(second, observed.get(0));
        assertSame(first, observed.get(1));
        assertNull(context.currentEnvelope());
    }

    @Test
    void dispatchRunnableRunsOnceWithItsEnvelopeAndRestoresTheOuterSend() {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendEnvelope outer = envelope(context);
        CausalSendEnvelope dispatched = envelope(context);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CausalSendEnvelope> inside = new AtomicReference<>();
        Runnable carrying = context.carryRunnable(() -> {
            calls.incrementAndGet();
            inside.set(context.currentEnvelope());
        }, dispatched);

        CausalSendContext.Frame frame = context.enter(outer);
        carrying.run();
        assertSame(outer, context.currentEnvelope(), "the nested dispatch restored the outer send");
        context.exit(frame);

        assertEquals(1, calls.get());
        assertSame(dispatched, inside.get());
        assertNull(context.currentEnvelope());
    }

    @Test
    void theWriteSideReadsTheEnvelopeFromTheChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendContext.attach(channel, context);
        CausalSendEnvelope dispatched = envelope(context);
        AtomicReference<CausalSendEnvelope> seenAtWrite = new AtomicReference<>();

        assertNull(CausalSendContext.currentEnvelope(channel), "no envelope outside a carrying action");
        context.carryRunnable(() -> seenAtWrite.set(CausalSendContext.currentEnvelope(channel)), dispatched).run();

        assertSame(dispatched, seenAtWrite.get());
        assertNull(CausalSendContext.currentEnvelope(channel));
        channel.finishAndReleaseAll();
    }

    @Test
    void teardownDropsFramesAndStillRunsTheVanillaActionOnce() throws Exception {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendContext.Frame frame = context.enter(envelope(context));
        CausalSendEnvelope carried = envelope(context);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CausalSendEnvelope> inside = new AtomicReference<>();
        Runnable carrying = context.carryRunnable(() -> {
            calls.incrementAndGet();
            inside.set(context.currentEnvelope());
        }, carried);

        context.teardown();
        context.teardown();
        assertNull(context.currentEnvelope(), "teardown keeps no frame");
        context.exit(frame);
        runOnNewThread(carrying);

        assertEquals(1, calls.get(), "the vanilla action still ran exactly once");
        assertSame(carried, inside.get(), "a send that starts after teardown is still its own envelope");
        assertNull(context.currentEnvelope());
    }

    @Test
    void foreignEnvelopeFallsBackToTheVanillaTaskOnce() {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendEnvelope foreign = envelope(new CausalSendContext("another"));
        AtomicInteger calls = new AtomicInteger();
        Consumer<String> vanilla = value -> calls.incrementAndGet();

        Consumer<String> carrying = context.carryTask(vanilla, foreign);
        assertSame(vanilla, carrying, "a foreign envelope is not wrapped");
        carrying.accept("packet");
        assertEquals(1, calls.get());
        assertNull(context.currentEnvelope());
        assertThrows(IllegalArgumentException.class, () -> context.enter(foreign));
    }

    @Test
    void aProjectFailureStillRunsTheVanillaTaskExactlyOnce() {
        FailingContext context = new FailingContext("connection");
        AtomicInteger calls = new AtomicInteger();
        Consumer<String> vanilla = value -> calls.incrementAndGet();

        context.failEnter = true;
        Consumer<String> carrying = context.carryTask(vanilla, envelope(context));
        carrying.accept("packet");
        assertEquals(1, calls.get(), "a failed envelope push does not skip the vanilla task");
        assertNull(context.currentEnvelope());

        context.failEnter = false;
        context.failExit = true;
        context.carryRunnable(calls::incrementAndGet, envelope(context)).run();
        assertEquals(2, calls.get(), "a failed frame pop does not repeat or skip the vanilla action");
        assertNull(context.currentEnvelope());
    }

    @Test
    void theConnectionMixinIsRegistered() throws Exception {
        try (InputStream in = CausalSendContextTest.class.getResourceAsStream("/quicraft.mixins.json")) {
            assertTrue(in != null, "quicraft.mixins.json is on the test classpath");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"CausalConnectionMixin\""),
                    "the connection mixin is listed in the mixin configuration");
        }
    }

    /** A context whose frame bookkeeping can fail, to prove the wrappers still run vanilla once. */
    private static final class FailingContext extends CausalSendContext {
        boolean failEnter;
        boolean failExit;

        FailingContext(Object connection) {
            super(connection);
        }

        @Override
        public Frame enter(CausalSendEnvelope envelope) {
            if (failEnter) {
                throw new IllegalStateException("injected frame failure");
            }
            return super.enter(envelope);
        }

        @Override
        public void exit(CausalSendContext.Frame frame) {
            super.exit(frame);
            if (failExit) {
                throw new IllegalStateException("injected frame failure");
            }
        }
    }
}
