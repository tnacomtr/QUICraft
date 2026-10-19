// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.AckJoinTracker;
import rs.sudoe.quicraft.core.multistream.MultistreamSession.CausalScope;
import rs.sudoe.quicraft.core.multistream.MultistreamSession.UpdateToken;

class CausalProducerTerminalTest {
    private static final long TIMEOUT_SECONDS = 10;

    private static void await(CountDownLatch latch, String description) throws InterruptedException {
        assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), description + " within timeout");
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(thread.isAlive(), thread.getName() + " finished within timeout");
    }

    private static void hookStyleOpenThenVanilla(CausalSendContext context, Runnable vanillaBody) {
        try {
            context.openProducerScope("hook");
        } catch (Throwable ignored) {
            // Match a hook's project-code fail-safe boundary: continue with vanilla behavior.
        }
        vanillaBody.run();
    }

    @SuppressWarnings("unchecked")
    private static Map<Thread, Deque<CausalSendContext.ProducerScope>> producerFrames(CausalSendContext context)
            throws ReflectiveOperationException {
        Field field = CausalSendContext.class.getDeclaredField("producerFrames");
        field.setAccessible(true);
        return (Map<Thread, Deque<CausalSendContext.ProducerScope>>) field.get(context);
    }

    private static final class PausingDeque extends ArrayDeque<CausalSendContext.ProducerScope> {
        private final CountDownLatch iteratorEntered;
        private final CountDownLatch releaseIterator;

        private PausingDeque(Deque<CausalSendContext.ProducerScope> original, CountDownLatch entered,
                CountDownLatch release) {
            super(original);
            iteratorEntered = entered;
            releaseIterator = release;
        }

        @Override
        public Iterator<CausalSendContext.ProducerScope> descendingIterator() {
            Iterator<CausalSendContext.ProducerScope> delegate = super.descendingIterator();
            return new Iterator<>() {
                private boolean paused;

                private void pauseOnce() {
                    if (!paused) {
                        paused = true;
                        iteratorEntered.countDown();
                        try {
                            if (!releaseIterator.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                throw new AssertionError("snapshot iterator release timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("snapshot iterator interrupted", e);
                        }
                    }
                }

                @Override
                public boolean hasNext() {
                    pauseOnce();
                    return delegate.hasNext();
                }

                @Override
                public CausalSendContext.ProducerScope next() {
                    pauseOnce();
                    return delegate.next();
                }
            };
        }
    }

    @Test
    void terminalEmptyOpenIsRejected() {
        CausalSendContext context = new CausalSendContext("terminal-empty");
        context.teardown();
        assertThrows(IllegalStateException.class, () -> context.openProducerScope("empty"));
        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
    }

    @Test
    void terminalNonemptyOpenIsRejected() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        UpdateToken token = tracker.registerUpdate(scope);
        CausalSendContext context = new CausalSendContext("terminal-nonempty");
        context.teardown();
        assertThrows(IllegalStateException.class,
                () -> context.openProducerScope("nonempty", List.of(scope), List.of(token)));
        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
    }

    @Test
    void argumentCopyFinishingAfterTeardownCannotPublishAFrame() throws Exception {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        UpdateToken token = tracker.registerUpdate(scope);
        CausalSendContext context = new CausalSendContext("copy-race");
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch releaseCopy = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread opener = new Thread(() -> {
            List<CausalScope> blockingScopes = new AbstractList<>() {
                @Override
                public CausalScope get(int index) {
                    copying.countDown();
                    try {
                        if (!releaseCopy.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new AssertionError("argument-copy release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    return scope;
                }

                @Override
                public int size() {
                    return 1;
                }
            };
            try {
                context.openProducerScope("copy-race", blockingScopes, List.of(token));
                result.set(new AssertionError("terminal open unexpectedly succeeded"));
            } catch (Throwable failure) {
                result.set(failure);
            }
        }, "producer-copy-opener");
        opener.start();
        try {
            await(copying, "argument copying began");
            context.teardown();
            releaseCopy.countDown();
            join(opener);
            assertInstanceOf(IllegalStateException.class, result.get());
            assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
        } finally {
            releaseCopy.countDown();
            if (opener.isAlive()) {
                join(opener);
            }
        }
    }

    @Test
    void terminalSnapshotIsEmptyButPreviouslyCopiedSnapshotRemainsImmutable() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        UpdateToken token = tracker.registerUpdate(scope);
        CausalSendContext context = new CausalSendContext("snapshot-terminal");
        CausalSendContext.ProducerScope frame = context.openProducerScope("real-frame", List.of(scope), List.of(token));
        CausalSendEnvelope.ProducerSnapshot before = context.currentProducerSnapshot();
        context.teardown();
        assertEquals(List.of(scope), before.scopes());
        assertEquals(List.of(token), before.tokens());
        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
        frame.close();
    }

    @Test
    void snapshotAndTeardownShareAnAtomicBoundary() throws Exception {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        UpdateToken token = tracker.registerUpdate(scope);
        CausalSendContext context = new CausalSendContext("snapshot-race");
        CountDownLatch snapshotPaused = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        CountDownLatch teardownStarted = new CountDownLatch(1);
        AtomicReference<CausalSendEnvelope.ProducerSnapshot> snapshot = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> snapshotThreadRef = new AtomicReference<>();
        Thread snapshotThread = new Thread(() -> {
            try {
                context.openProducerScope("real-frame", List.of(scope), List.of(token));
                Map<Thread, Deque<CausalSendContext.ProducerScope>> frames = producerFrames(context);
                Deque<CausalSendContext.ProducerScope> realFrames = frames.get(Thread.currentThread());
                assertTrue(realFrames != null && realFrames.size() == 1, "one real published frame is present");
                frames.put(Thread.currentThread(), new PausingDeque(realFrames, snapshotPaused, releaseSnapshot));
                snapshot.set(context.currentProducerSnapshot());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "producer-snapshot");
        snapshotThreadRef.set(snapshotThread);
        Thread teardownThread = new Thread(() -> {
            teardownStarted.countDown();
            context.teardown();
        }, "producer-teardown");
        snapshotThread.start();
        try {
            await(snapshotPaused, "snapshot paused while copying actual frame refs");
            teardownThread.start();
            await(teardownStarted, "teardown thread started");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            Thread.State observed;
            do {
                observed = teardownThread.getState();
                if (observed == Thread.State.BLOCKED || !teardownThread.isAlive()) {
                    break;
                }
                Thread.onSpinWait();
            } while (System.nanoTime() < deadline);
            assertSame(Thread.State.BLOCKED, observed,
                    "teardown must wait for the paused snapshot's producer-monitor copy");
        } finally {
            releaseSnapshot.countDown();
            join(snapshotThread);
            if (teardownThread.isAlive()) {
                join(teardownThread);
            }
        }
        if (failure.get() != null) {
            throw new AssertionError("snapshot worker failed", failure.get());
        }
        assertEquals(List.of(scope), snapshot.get().scopes());
        assertEquals(List.of(token), snapshot.get().tokens());
        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
        assertFalse(snapshotThreadRef.get().isAlive());
    }

    @Test
    void repeatedTeardownAndScopeCloseAreIdempotent() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        CausalSendContext context = new CausalSendContext("idempotent");
        CausalSendContext.ProducerScope frame = context.openProducerScope("real-frame", List.of(scope), List.of());
        frame.close();
        frame.close();
        context.teardown();
        context.teardown();
        frame.close();
        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
        assertThrows(IllegalStateException.class, () -> context.openProducerScope("after-close"));
    }

    @Test
    void nestedScopesAndThreadIsolationRemainUnchanged() throws Exception {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope outer = tracker.openScope();
        CausalScope inner = tracker.openScope();
        CausalSendContext context = new CausalSendContext("nesting");
        AtomicReference<CausalSendEnvelope.ProducerSnapshot> otherThread = new AtomicReference<>();
        try (CausalSendContext.ProducerScope outerFrame = context.openProducerScope("outer", List.of(outer), List.of())) {
            assertEquals("outer", outerFrame.name());
            try (CausalSendContext.ProducerScope innerFrame = context.openProducerScope("inner", List.of(inner), List.of())) {
                assertEquals("inner", innerFrame.name());
                assertEquals(List.of(outer, inner), context.currentProducerSnapshot().scopes());
                Thread other = new Thread(() -> otherThread.set(context.currentProducerSnapshot()), "producer-isolation");
                other.start();
                join(other);
                assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, otherThread.get());
            }
            assertEquals(List.of(outer), context.currentProducerSnapshot().scopes());
        }
        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
    }

    @Test
    void failSafeBoundaryRunsVanillaBodyOnceWhenTerminalOpenIsRejected() {
        CausalSendContext context = new CausalSendContext("hook-failsafe");
        context.teardown();
        AtomicInteger vanillaCalls = new AtomicInteger();
        hookStyleOpenThenVanilla(context, vanillaCalls::incrementAndGet);
        assertEquals(1, vanillaCalls.get(), "the fail-safe path executes the vanilla body exactly once");
    }
}
