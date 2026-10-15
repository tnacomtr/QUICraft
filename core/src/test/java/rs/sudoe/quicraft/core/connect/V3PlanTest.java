// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.binding.BindingSession;
import rs.sudoe.quicraft.core.binding.BindingStateMachine;
import rs.sudoe.quicraft.core.binding.BindingTestSessions;
import rs.sudoe.quicraft.core.connect.TransportSelection.PinState;
import rs.sudoe.quicraft.core.connect.V3Plan.Phase;
import rs.sudoe.quicraft.core.pin.PinKey;
import rs.sudoe.quicraft.core.pin.PinStore;
import rs.sudoe.quicraft.core.pin.RebindState;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * The {@code /3} plan's own fail-safe behaviour (docs/protocol.md 12.14, 12.15, 12.16, 12.19): its
 * reporting methods must always answer, a pin needs a passing binding, and each failure is counted
 * once. The adapter calls them from the connection's event loop, where an exception would leave the
 * join unfinished instead of falling back to TCP.
 */
class V3PlanTest {

    /**
     * 12.15's expiry calls take the keys of the attempts that are running. These tests run at most
     * one attempt and never need another server's pin protected, so the set is empty.
     */
    private static final Set<PinKey> NO_ACTIVE_KEYS = Collections.emptySet();
    private static final InetAddress ADDRESS = address();
    private static final int QUIC_PORT = 25570;
    private static final String HOST = "mc.example.com";
    private static final int LOGICAL_PORT = 25565;
    private static final long FIVE_SECONDS = 5;

    /** Refuses every task, like an executor that is shutting down. */
    static final class RejectingExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("the worker is shutting down");
        }
    }

    /** Runs every task inline, so a test observes the worker's state calls synchronously. */
    static final Executor INLINE = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    @AfterEach
    void noFaults() {
        Faults.set();
    }

    private static InetAddress address() {
        try {
            return InetAddress.getByName("192.0.2.10");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Fingerprint fingerprint(char digit) {
        StringBuilder text = new StringBuilder("sha256:");
        for (int i = 0; i < 64; i++) {
            text.append(digit);
        }
        return Fingerprint.parse(text.toString());
    }

    private static final class Plan {
        final AtomicLong clock = new AtomicLong(0);
        final FailureCache failures = FailureCache.load(null, clock::get);
        final PinStore pins;
        final RebindState rebinds;
        final Fingerprint fingerprint = fingerprint('c');
        final V3Plan plan;

        Plan(Executor worker, PinState pinState, RebindState.Attempt attempt, PinStore pins,
                RebindState rebinds) {
            this.pins = pins;
            this.rebinds = rebinds;
            this.plan = new V3Plan(pinState, attempt, fingerprint, new byte[32], pins, failures,
                    rebinds, worker, ADDRESS, QUIC_PORT, HOST, LOGICAL_PORT);
        }

        /** A plan for a name with no pin, so it holds no re-bind attempt. */
        static Plan joining(Executor worker) {
            return new Plan(worker, PinState.NONE, null, PinStore.load(null), RebindState.load(null));
        }

        /** A plan for a changed pin, holding the one re-bind attempt 12.16 admits. */
        static Plan rebinding(Executor worker) {
            PinStore pins = PinStore.load(null);
            RebindState rebinds = RebindState.load(null);
            RebindState.Attempt attempt = rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, 0);
            return new Plan(worker, PinState.CHANGED, attempt, pins, rebinds);
        }
    }

    @Test
    void failedStillAnswersWhenTheWorkerRefusesTheTask() {
        V3Plan plan = Plan.joining(new RejectingExecutor()).plan;
        assertTrue(plan.failed(Phase.JOINING, V3Plan.BINDING_FAILED),
                "the retry decision is policy, so a refusing worker must not hide it");
        assertEquals("QUIC multi-stream failed (" + V3Plan.BINDING_FAILED + "), fell back to TCP",
                plan.fallbackMessage());
        assertTrue(plan.isFailed());
    }

    @Test
    void activatedCompletesFalseWhenTheWorkerRefusesTheTask() throws Exception {
        V3Plan plan = Plan.joining(new RejectingExecutor()).plan;
        // False means "not a passed activation": the caller closes 0x10 (12.15) instead of leaving
        // the join hanging on an exception.
        assertFalse(plan.activated(BindingTestSessions.activatedClient(BindingStateMachine.MODE_ONLINE))
                .get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());
    }

    @Test
    void activatedReturnsTheSameFutureOnEveryCall() throws Exception {
        V3Plan plan = Plan.joining(INLINE).plan;
        BindingSession session = BindingTestSessions.activatedClient(BindingStateMachine.MODE_ONLINE);
        assertSame(plan.activated(session), plan.activated(session),
                "a second report must not write the pin again");
        assertTrue(plan.activated(session).get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());
    }

    @Test
    void aPinNeedsAPassingBindingAndOnlineMode() throws Exception {
        // 12.15: only an online-authenticated binding that passed may write a pin.
        Plan offline = Plan.joining(INLINE);
        BindingSession offlineSession = BindingTestSessions.activatedClient(BindingStateMachine.MODE_OFFLINE);
        assertTrue(offline.plan.activated(offlineSession).get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());
        assertFalse(offline.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).isPresent(), "offline mode never writes a pin");

        // A session that never activated is not a passing binding either.
        Plan idle = Plan.joining(INLINE);
        assertFalse(idle.plan.activated(BindingTestSessions.idleClient(BindingStateMachine.MODE_ONLINE))
                .get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());
        assertFalse(idle.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).isPresent(), "no pin without activation");

        // The online case does write it.
        Plan online = Plan.joining(INLINE);
        assertTrue(online.plan.activated(BindingTestSessions.activatedClient(BindingStateMachine.MODE_ONLINE))
                .get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());
        assertEquals(online.fingerprint.toString(), online.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).get().fingerprint());
    }

    @Test
    void anOfflineActivationKeepsAChangedPinBackoff() throws Exception {
        // 12.16/12.19: an offline binding cannot authenticate the endpoint, so it must not replace a
        // changed pin, and the re-bind window stays up.
        Plan plan = Plan.rebinding(INLINE);
        plan.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        assertTrue(plan.plan.activated(BindingTestSessions.activatedClient(BindingStateMachine.MODE_OFFLINE))
                .get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());

        assertEquals(fingerprint('d').toString(), plan.pins.pin(HOST, LOGICAL_PORT, NO_ACTIVE_KEYS).get().fingerprint(),
                "the old pin stays");
        assertNull(plan.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, plan.clock.get()),
                "and a new re-bind is refused for the window's length");
    }

    @Test
    void aPinWriteFailureIsRecordedOnce() throws Exception {
        Plan plan = Plan.rebinding(INLINE);
        plan.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        Faults.set(Faults.PIN_WRITE);
        assertFalse(plan.plan.activated(BindingTestSessions.activatedClient(BindingStateMachine.MODE_ONLINE))
                .get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue(),
                "12.15: a failed pin write must be reported so the caller closes with 0x10");
        Faults.set();
        assertTrue(plan.failures.isActivationBlocked(ADDRESS, QUIC_PORT, plan.fingerprint, plan.clock.get()));

        // 12.15 with 12.14: the adapter still gets the retry decision and the message for the failure
        // it just saw.
        assertTrue(plan.plan.failed(Phase.JOINING, V3Plan.PIN_WRITE_FAILED));
        assertEquals("QUIC multi-stream failed (" + V3Plan.PIN_WRITE_FAILED + "), fell back to TCP",
                plan.plan.fallbackMessage());

        // ...and the failure is counted exactly once: recording it twice would double the window.
        long oneMinute = TimeUnit.MINUTES.toMillis(1);
        assertTrue(plan.failures.isActivationBlocked(ADDRESS, QUIC_PORT, plan.fingerprint,
                plan.clock.get() + oneMinute - 1), "the first window is up");
        assertFalse(plan.failures.isActivationBlocked(ADDRESS, QUIC_PORT, plan.fingerprint,
                plan.clock.get() + oneMinute + 1), "and it is one window, not two");
    }

    @Test
    void failedRetriesOnlyOnItsFirstJoiningCall() {
        V3Plan plan = Plan.joining(INLINE).plan;
        assertTrue(plan.failed(Phase.JOINING, V3Plan.BINDING_FAILED), "the first join failure retries");
        assertFalse(plan.failed(Phase.JOINING, "a second report"),
                "a second report must not start a second TCP join");
        assertEquals(V3Plan.BINDING_FAILED, plan.fallbackReason(), "and it keeps the first cause");
    }

    @Test
    void aFailureAfterActivationDoesNotRetry() throws Exception {
        Plan plan = Plan.rebinding(INLINE);
        plan.pins.recordPin(HOST, LOGICAL_PORT, fingerprint('d').toString(), NO_ACTIVE_KEYS);
        assertTrue(plan.plan.activated(BindingTestSessions.activatedClient(BindingStateMachine.MODE_ONLINE))
                .get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());
        assertTrue(plan.plan.isActivated());

        assertFalse(plan.plan.failed(Phase.PLAY, "lane ordering failure"),
                "after activation there is no same-attempt retry");
        assertFalse(plan.plan.isActivated());
        assertEquals("QUIC multi-stream failed (lane ordering failure), fell back to TCP",
                plan.plan.fallbackMessage());
        assertTrue(plan.failures.isActivationBlocked(ADDRESS, QUIC_PORT, plan.fingerprint, plan.clock.get()),
                "so the next join goes straight to TCP during the backoff");
        assertFalse(plan.plan.failed(Phase.JOINING, "later still"),
                "and no later call can start a join either");
    }

    @Test
    void theDefaultCauseIsTheInternalErrorOne() {
        V3Plan plan = Plan.joining(INLINE).plan;
        assertNull(plan.fallbackMessage(), "nothing failed yet");
        assertNull(plan.fallbackReason());
        assertFalse(plan.isFailed());
        assertTrue(plan.failed(Phase.JOINING));
        assertEquals("QUIC multi-stream failed (internal error), fell back to TCP", plan.fallbackMessage());
    }

    @Test
    void theWorkerRunsItsTasksOneAtATimeInSubmissionOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Executor worker = new ClientConnector.V3Support(PinStore.load(null), RebindState.load(null),
                    pool, System::currentTimeMillis, 12345, true, true).worker();
            final List<String> log = Collections.synchronizedList(new ArrayList<String>());
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    log.add("start-a");
                    sleep(100);
                    log.add("end-a");
                }
            });
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    log.add("start-b");
                    log.add("end-b");
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FIVE_SECONDS);
            while (log.size() < 4 && System.nanoTime() < deadline) {
                sleep(10);
            }
            assertEquals(Arrays.asList("start-a", "end-a", "start-b", "end-b"), log,
                    "the worker's state writes must never interleave or overtake each other");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Queues every task until the test releases it, so a test can act while the worker is busy. */
    static final class QueuedExecutor implements Executor {
        private final List<Runnable> queued = new ArrayList<Runnable>();

        @Override
        public synchronized void execute(Runnable command) {
            queued.add(command);
        }

        synchronized void runAll() {
            for (int guard = 0; guard < 64 && !queued.isEmpty(); guard++) {
                queued.remove(0).run();
            }
        }
    }

    /** Refuses the first task and runs every later one, like a worker that is still shutting down. */
    static final class RejectFirstExecutor implements Executor {
        private int tasks;

        @Override
        public void execute(Runnable command) {
            if (tasks++ == 0) {
                throw new RejectedExecutionException("the worker is shutting down");
            }
            command.run();
        }
    }

    @Test
    void activatedRecordsTheFailureStateWhenTheWorkerRefusesTheTask() throws Exception {
        Plan plan = Plan.joining(new RejectFirstExecutor());
        // The pin write is refused, so 12.15's write failure must be recorded by activated() itself.
        assertFalse(plan.plan.activated(BindingTestSessions.activatedClient(BindingStateMachine.MODE_ONLINE))
                .get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());
        assertTrue(plan.failures.isActivationBlocked(ADDRESS, QUIC_PORT, plan.fingerprint, plan.clock.get()),
                "12.15's write failure is recorded even when the worker refuses the pin write");
    }

    @Test
    void isActivatedWaitsForTheActivationFuture() throws Exception {
        QueuedExecutor gate = new QueuedExecutor();
        Plan plan = Plan.joining(gate);
        CompletableFuture<Boolean> pending = plan.plan.activated(
                BindingTestSessions.activatedClient(BindingStateMachine.MODE_ONLINE));

        assertFalse(pending.isDone());
        assertFalse(plan.plan.isActivated(), "an unanswered activation is not an activation");
        gate.runAll();
        assertTrue(pending.get(FIVE_SECONDS, TimeUnit.SECONDS).booleanValue());
        assertTrue(plan.plan.isActivated(), "and once it is answered true, it is");
    }

    @Test
    void aRefusedDrainKeepsTheCommandsQueuedBehindIt() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        Executor delegate = new Executor() {
            @Override
            public void execute(Runnable command) {
                if (calls.incrementAndGet() == 1) {
                    started.countDown();
                    await(release);
                    throw new RejectedExecutionException("the worker is shutting down");
                }
                command.run();
            }
        };
        final Executor worker = new ClientConnector.V3Support(PinStore.load(null),
                RebindState.load(null), delegate, System::currentTimeMillis, 12345, true, true).worker();
        Thread first = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    worker.execute(new Runnable() {
                        @Override
                        public void run() {
                            log.add("first");
                        }
                    });
                } catch (RejectedExecutionException expected) {
                    // The refusal is the point of this test.
                }
            }
        });
        first.start();
        assertTrue(started.await(FIVE_SECONDS, TimeUnit.SECONDS));
        // This command is queued behind the run that is about to be refused.
        worker.execute(new Runnable() {
            @Override
            public void run() {
                log.add("second");
            }
        });
        release.countDown();
        first.join(TimeUnit.SECONDS.toMillis(FIVE_SECONDS));

        assertEquals(Arrays.asList(), log, "the refused command is dropped, the queued one is not");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                log.add("third");
            }
        });
        assertEquals(Arrays.asList("second", "third"), log,
                "the next execute restarts the drain for the command that stayed queued");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(FIVE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
