// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutorHeartbeatTest {
    private static final long SEC = 1_000_000_000L;

    @Test void fiftyThousandTicksWhilePendingSubmitExactlyOneProbe() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        AtomicInteger submissions = new AtomicInteger();
        Executor held = command -> submissions.incrementAndGet();
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(watch, held, () -> true);

        for (int i = 0; i < 50_000; i++) {
            heartbeat.tick();
        }

        assertEquals(1, submissions.get(), "at most one probe is outstanding");
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertTrue(snapshot.valid);
        assertFalse(snapshot.proven);
    }

    @Test void completedProbeLetsTheNextTickReserveAgain() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        AtomicInteger submissions = new AtomicInteger();
        Executor inline = command -> {
            submissions.incrementAndGet();
            command.run();
        };
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(watch, inline, () -> true);

        heartbeat.tick();
        assertEquals(1, submissions.get());
        assertTrue(watch.sample().proven, "an inline executor completes the probe within the tick");

        now.set(30 * SEC);
        heartbeat.tick();
        assertEquals(2, submissions.get(), "once the probe completed the owner may reserve the next");
        assertTrue(watch.sample().proven);
        assertTrue(watch.sample().valid);
    }

    @Test void aFalseIdentityCheckInvalidatesWithoutProof() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(watch, Runnable::run, () -> false);

        heartbeat.tick();

        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertFalse(snapshot.valid, "an off-executor probe invalidates the watch");
        assertFalse(snapshot.proven);
    }

    @Test void aThrowingIdentityCheckInvalidatesWithoutProof() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(watch, Runnable::run, () -> {
            throw new IllegalStateException("not the game executor");
        });

        assertDoesNotThrow(heartbeat::tick, "an identity failure must not escape the heartbeat");
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertFalse(snapshot.valid);
        assertFalse(snapshot.proven);
    }

    @Test void aRejectedSubmissionInvalidates() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        Executor rejecting = command -> {
            throw new RejectedExecutionException("executor refused the probe");
        };
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(watch, rejecting, () -> true);

        assertDoesNotThrow(heartbeat::tick, "a rejection must not escape the heartbeat");
        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertFalse(snapshot.valid);
        assertFalse(snapshot.proven);
    }

    @Test void anExecutorRunningTheProbeOnAnotherThreadDoesNotDeadlock() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        Executor synchronousOtherThread = command -> {
            Thread worker = new Thread(command, "heartbeat-probe");
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
        };
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(watch, synchronousOtherThread, () -> true);

        assertTimeoutPreemptively(Duration.ofSeconds(10), heartbeat::tick,
                "the executor must be called with the watch monitor released");
        assertTrue(watch.sample().proven);
    }

    @Test void ticksAfterCloseDoNotSubmit() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        Executor queuing = queue::add;
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(watch, queuing, () -> true);

        heartbeat.tick();
        assertEquals(1, queue.size());

        heartbeat.close();
        heartbeat.close();
        heartbeat.tick();

        assertEquals(1, queue.size(), "a closed heartbeat submits nothing");
        assertFalse(watch.sample().valid);
        assertFalse(watch.sample().proven);
    }

    @Test void queuedProbeCompletionCannotProveReadinessAfterClose() {
        AtomicLong now = new AtomicLong();
        ExecutorProgressWatch watch = new ExecutorProgressWatch(now::get);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        Executor queuing = queue::add;
        ExecutorHeartbeat heartbeat = new ExecutorHeartbeat(watch, queuing, () -> true);

        heartbeat.tick();
        assertEquals(1, queue.size());
        heartbeat.close();
        now.set(5 * SEC);
        queue.poll().run();

        ExecutorProgressWatch.Snapshot snapshot = watch.sample();
        assertFalse(snapshot.valid, "an invalid watch is never revived");
        assertFalse(snapshot.proven, "a callback queued before close cannot prove readiness");
        assertEquals(0, snapshot.excludedTotalNanos, "invalidation at the same reading accrued nothing");
    }
}
