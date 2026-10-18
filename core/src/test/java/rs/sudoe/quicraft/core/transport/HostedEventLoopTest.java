// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.util.concurrent.ScheduledFuture;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hosted loop's recurring schedule (docs/protocol.md §11; the N+2 tick of 13.12): every run of
 * a periodic task runs on the platform's thread, cancelling the returned future stops the chain,
 * an exception stops it and fails the future, fixed rate keeps its anchor when a run overruns, and
 * fixed delay counts from the end of the run.
 */
class HostedEventLoopTest {
    private TestHost.Loop host;
    private HostedEventLoop loop;

    @BeforeEach
    void setUp() {
        host = new TestHost.Loop("hosted-periodic-test");
        loop = new HostedEventLoop(host);
    }

    @AfterEach
    void tearDown() {
        host.shutdown();
    }

    @Test
    void aFixedRateTaskRunsRepeatedlyOnTheHostThread() throws Exception {
        List<Boolean> onHost = new CopyOnWriteArrayList<Boolean>();
        CountDownLatch four = new CountDownLatch(4);
        ScheduledFuture<?> future = loop.scheduleAtFixedRate(() -> {
            onHost.add(Boolean.valueOf(host.inEventLoop(Thread.currentThread())));
            four.countDown();
        }, 0, 10, TimeUnit.MILLISECONDS);
        try {
            assertTrue(four.await(5, TimeUnit.SECONDS), "four runs");
            assertEquals(4, onHost.size());
            for (Boolean each : onHost) {
                assertTrue(each.booleanValue(), "a run left the host's thread");
            }
        } finally {
            future.cancel(false);
        }
    }

    /**
     * The chain stops at the cancel and nothing runs afterwards. A short period makes a runaway
     * chain visible: without the cancel it would run about five more times in the wait. One extra
     * run is allowed for one that had already started when the cancel landed.
     */
    @Test
    void cancellingTheFutureStopsTheChain() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch two = new CountDownLatch(2);
        ScheduledFuture<?> future = loop.scheduleAtFixedRate(() -> {
            runs.incrementAndGet();
            two.countDown();
        }, 0, 40, TimeUnit.MILLISECONDS);
        assertTrue(two.await(5, TimeUnit.SECONDS), "two runs");
        assertTrue(future.cancel(false), "cancel of a live chain");
        int atCancel = runs.get();
        Thread.sleep(200);
        assertTrue(runs.get() <= atCancel + 1,
                "a cancelled chain kept running: " + atCancel + " -> " + runs.get());
        assertTrue(future.isCancelled());
    }

    @Test
    void anExceptionStopsTheChainAndFailsTheFuture() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ScheduledFuture<?> future = loop.scheduleAtFixedRate(() -> {
            if (runs.incrementAndGet() == 2) {
                throw new IllegalStateException("boom");
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof IllegalStateException, () -> "cause " + e.getCause());
        assertEquals(2, runs.get(), "the run that threw was the last");
        Thread.sleep(120);
        assertEquals(2, runs.get(), "no run after the exception");
        assertTrue(future.isDone());
    }

    /** A fixed-delay run starts the delay after it ends, so a slow run spaces the next one out. */
    @Test
    void fixedDelayCountsFromTheEndOfTheRun() throws Exception {
        List<Long> starts = new CopyOnWriteArrayList<Long>();
        long slowRunMillis = 40;
        CountDownLatch three = new CountDownLatch(3);
        ScheduledFuture<?> future = loop.scheduleWithFixedDelay(() -> {
            starts.add(Long.valueOf(System.nanoTime()));
            sleep(slowRunMillis);
            three.countDown();
        }, 0, 20, TimeUnit.MILLISECONDS);
        try {
            assertTrue(three.await(10, TimeUnit.SECONDS), "three runs");
            for (int i = 1; i < starts.size(); i++) {
                long gapMillis = TimeUnit.NANOSECONDS.toMillis(starts.get(i) - starts.get(i - 1));
                assertTrue(gapMillis >= slowRunMillis + 20 - 10,
                        "gap " + i + " was " + gapMillis + " ms: the delay must start after the run");
            }
        } finally {
            future.cancel(false);
        }
    }

    /**
     * A fixed-rate run that overruns its period makes the next due at once, while the anchor keeps
     * advancing by the period: four runs of 40 ms at a 30 ms period start within ~90 ms, not the
     * ~160 ms a delay-based chain would take.
     */
    @Test
    void fixedRateKeepsTheAnchorWhenARunOverrunsItsPeriod() throws Exception {
        List<Long> starts = new CopyOnWriteArrayList<Long>();
        CountDownLatch four = new CountDownLatch(4);
        ScheduledFuture<?> future = loop.scheduleAtFixedRate(() -> {
            starts.add(Long.valueOf(System.nanoTime()));
            sleep(40);
            four.countDown();
        }, 0, 30, TimeUnit.MILLISECONDS);
        try {
            assertTrue(four.await(10, TimeUnit.SECONDS), "four runs");
            long totalMillis = TimeUnit.NANOSECONDS.toMillis(starts.get(3) - starts.get(0));
            assertTrue(totalMillis < 135,
                    "four anchored runs took " + totalMillis + " ms; a delay chain needs about 210");
        } finally {
            future.cancel(false);
        }
    }

    @Test
    void anInvalidPeriodOrDelayIsRefused() {
        AtomicBoolean ran = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class,
                () -> loop.scheduleAtFixedRate(() -> ran.set(true), 0, 0, TimeUnit.MILLISECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> loop.scheduleAtFixedRate(() -> ran.set(true), 0, -1, TimeUnit.MILLISECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> loop.scheduleWithFixedDelay(() -> ran.set(true), 0, 0, TimeUnit.MILLISECONDS));
        assertFalse(ran.get(), "a refused schedule runs nothing");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
