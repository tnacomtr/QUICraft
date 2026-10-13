// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * Behavior of the bounded admission state (docs/protocol.md Retry and limits). These tests drive
 * only the package-private API with a fixed or injected clock; no pipeline is involved.
 */
class ConnectionAdmissionTest {
    private static final long WINDOW = 1_000_000_000L;

    private static InetAddress addr(int lastOctet) {
        try {
            return InetAddress.getByName("192.0.2." + lastOctet);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    private static InetAddress other(int lastOctet) {
        try {
            return InetAddress.getByName("198.51.100." + lastOctet);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void constructorRejectsNonPositiveLimitsThresholdAboveGlobalNullClockAndNullAddress() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionAdmission(0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionAdmission(1, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionAdmission(1, 1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionAdmission(1, 1, 1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionAdmission(1, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionAdmission(1, 1, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionAdmission(1, 1, 1, 1, 2, 1));
        assertThrows(
                NullPointerException.class,
                () -> new ConnectionAdmission(1, 1, 1, 1, 1, 1, (LongSupplier) null));

        ConnectionAdmission ok = new ConnectionAdmission(5, 2, 2, 2, 5, 3);
        assertNotNull(ok, "retryThreshold equal to the global budget is accepted");
        assertThrows(NullPointerException.class, () -> ok.allowAttempt(null));
        assertThrows(NullPointerException.class, () -> ok.tryAcquire(null));
    }

    @Test
    void allowsExactlyPerIpAttemptsThenDenies() {
        ConnectionAdmission a = new ConnectionAdmission(10, 3, 5, 5, 10, 10);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.allowAttempt(addr(1)), "the fourth per-IP attempt is denied");
    }

    @Test
    void allowsExactlyGlobalAttemptsAcrossDistinctIps() {
        ConnectionAdmission a = new ConnectionAdmission(3, 5, 5, 5, 3, 10);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(2)));
        assertTrue(a.allowAttempt(addr(3)));
        assertFalse(a.allowAttempt(addr(4)), "the fourth global attempt is denied");
    }

    @Test
    void distinctIpsHaveIndependentPerIpBudgets() {
        ConnectionAdmission a = new ConnectionAdmission(100, 2, 5, 5, 100, 10);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(2)), "another IP keeps its own budget");
        assertTrue(a.allowAttempt(addr(2)));
        assertFalse(a.allowAttempt(addr(2)));
    }

    @Test
    void perIpRefusalConsumesGlobalAttempt() {
        ConnectionAdmission a = new ConnectionAdmission(2, 1, 5, 5, 2, 10);
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.allowAttempt(addr(1)), "per-IP limit denies");
        assertTrue(a.requiresRetry(), "the per-IP refusal consumed the second global attempt");
    }

    @Test
    void trackingRefusalConsumesGlobalAttempt() {
        ConnectionAdmission a = new ConnectionAdmission(5, 5, 5, 5, 3, 2);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(2)));
        assertFalse(a.allowAttempt(addr(3)), "tracking capacity denies a third IP");
        assertTrue(a.requiresRetry(), "the tracking refusal consumed a global attempt");
    }

    @Test
    void globalRefusalAllocatesNoTrackingSlot() {
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 1, 1, 1, 2);
        assertTrue(a.allowAttempt(addr(1)), "first IP consumes the only global attempt and tracks");
        assertFalse(a.allowAttempt(addr(2)), "second IP is globally denied");
        ConnectionAdmission.Lease c = a.tryAcquire(addr(3));
        assertNotNull(c, "a globally denied IP must not consume the second tracking slot");
        c.close();
        c.close();
        assertNotNull(a.tryAcquire(addr(3)), "repeated close calls stay safe");
    }

    @Test
    void globalRefusalRecoversInNextWindow() {
        AtomicLong now = new AtomicLong(0);
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 5, 5, 1, 10, now::get);
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.allowAttempt(addr(2)));
        now.set(WINDOW);
        assertTrue(a.allowAttempt(addr(2)), "the next window admits the previously denied IP");
    }

    @Test
    void requiresRetryTurnsTrueAtExactThresholdAndResetsWithWindow() {
        AtomicLong now = new AtomicLong(0);
        ConnectionAdmission a = new ConnectionAdmission(5, 5, 5, 5, 3, 10, now::get);
        assertFalse(a.requiresRetry());
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.requiresRetry(), "below the threshold");
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.requiresRetry(), "still below the threshold");
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.requiresRetry(), "at the exact threshold");
        now.set(WINDOW);
        assertFalse(a.requiresRetry(), "resets with the window");
    }

    @Test
    void tryAcquireSpendsNoAttempt() {
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 1, 1, 1, 10);
        ConnectionAdmission.Lease lease = a.tryAcquire(addr(1));
        assertNotNull(lease);
        assertFalse(a.requiresRetry(), "tryAcquire must not spend an attempt");
        assertTrue(a.allowAttempt(addr(1)), "the attempt budget is still available");
        assertTrue(a.requiresRetry());
        lease.close();
    }

    @Test
    void openLeaseCapsAreExactGloballyAndPerIp() {
        ConnectionAdmission a = new ConnectionAdmission(10, 10, 2, 1, 10, 10);
        ConnectionAdmission.Lease first = a.tryAcquire(addr(1));
        assertNotNull(first);
        assertNull(a.tryAcquire(addr(1)), "per-IP open cap of one");
        ConnectionAdmission.Lease second = a.tryAcquire(addr(2));
        assertNotNull(second);
        assertNull(a.tryAcquire(addr(3)), "global open cap of two");
        first.close();
        second.close();
    }

    @Test
    void closeIsIdempotentAndFreesCapacityExactlyOnce() {
        ConnectionAdmission a = new ConnectionAdmission(10, 10, 1, 1, 10, 10);
        ConnectionAdmission.Lease lease = a.tryAcquire(addr(1));
        assertNotNull(lease);
        assertNull(a.tryAcquire(addr(2)), "global cap of one is full");
        lease.close();
        lease.close();
        lease.close();
        ConnectionAdmission.Lease next = a.tryAcquire(addr(2));
        assertNotNull(next, "capacity freed exactly once");
        assertNull(a.tryAcquire(addr(3)), "a double close must not free capacity twice");
        next.close();
    }

    @Test
    void trackingCapacityRefusesNewIpWithoutEvictingAttemptsOrLeases() {
        ConnectionAdmission a = new ConnectionAdmission(100, 2, 5, 1, 100, 2);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(2)));
        ConnectionAdmission.Lease lease = a.tryAcquire(addr(2));
        assertNotNull(lease);
        assertFalse(a.allowAttempt(addr(3)), "a third IP exceeds the tracking capacity");
        assertFalse(a.allowAttempt(addr(1)), "the tracked per-IP attempts are preserved");
        assertNull(a.tryAcquire(addr(2)), "the tracked open lease is preserved");
        assertNull(a.tryAcquire(addr(4)), "tracking capacity also bounds new leases");
        lease.close();
    }

    @Test
    void nextWindowPrunesIdleEntriesButRetainsActiveLeases() {
        AtomicLong now = new AtomicLong(0);
        ConnectionAdmission a = new ConnectionAdmission(100, 5, 5, 1, 100, 2, now::get);
        assertTrue(a.allowAttempt(addr(1)), "idle attempt-only entry");
        ConnectionAdmission.Lease lease = a.tryAcquire(addr(2));
        assertNotNull(lease);
        now.set(WINDOW);
        assertTrue(a.allowAttempt(addr(3)), "the idle entry was pruned, freeing a tracking slot");
        assertNull(a.tryAcquire(addr(2)), "the entry holding an open lease is retained");
        lease.close();
    }

    @Test
    void closeAtNewWindowPrunesFormerlySpentIdleEntry() {
        AtomicLong now = new AtomicLong(0);
        ConnectionAdmission a = new ConnectionAdmission(100, 5, 1, 1, 100, 1, now::get);
        ConnectionAdmission.Lease lease = a.tryAcquire(addr(1));
        assertNotNull(lease);
        assertTrue(a.allowAttempt(addr(1)));
        now.set(WINDOW);
        assertFalse(a.allowAttempt(addr(2)), "the entry is retained while its lease is open");
        lease.close();
        assertTrue(a.allowAttempt(addr(2)), "closing after the window pruned the spent entry");
    }

    @Test
    void windowResetsAtExactlyOneSecondNotBefore() {
        AtomicLong now = new AtomicLong(0);
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 5, 5, 1, 10, now::get);
        assertTrue(a.allowAttempt(addr(1)));
        now.set(WINDOW - 1);
        assertFalse(a.allowAttempt(addr(2)), "one nanosecond before the boundary does not reset");
        now.set(WINDOW);
        assertTrue(a.allowAttempt(addr(2)), "exactly one second resets the window");
    }

    @Test
    void negativeClockStartStillMeasuresTheWindow() {
        AtomicLong now = new AtomicLong(-WINDOW);
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 5, 5, 1, 10, now::get);
        assertTrue(a.allowAttempt(addr(1)));
        now.set(-1L);
        assertFalse(a.allowAttempt(addr(2)), "just before the boundary does not reset");
        now.set(0L);
        assertTrue(a.allowAttempt(addr(2)), "the boundary resets even from a negative start");
    }

    @Test
    void wrappingNanoTimeStillResetsWindow() {
        long start = Long.MAX_VALUE - 500_000_000L;
        AtomicLong now = new AtomicLong(start);
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 5, 5, 1, 10, now::get);
        assertTrue(a.allowAttempt(addr(1)));
        now.set(start + (WINDOW - 1));
        assertFalse(a.allowAttempt(addr(2)), "one nanosecond before the wrapped boundary does not reset");
        now.set(start + WINDOW);
        assertTrue(a.allowAttempt(addr(2)), "wrapping long subtraction still resets the window");
    }

    @Test
    void backwardClockDoesNotResetWindow() {
        AtomicLong now = new AtomicLong(5_000_000_000L);
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 5, 5, 1, 10, now::get);
        assertTrue(a.allowAttempt(addr(1)));
        now.set(0L);
        assertFalse(a.allowAttempt(addr(2)), "a backward clock must not reset the window");
        now.set(5_000_000_000L);
        assertFalse(a.allowAttempt(addr(2)), "returning to the window start still does not reset");
        now.set(5_000_000_000L + WINDOW);
        assertTrue(a.allowAttempt(addr(2)), "the window resets once the clock catches up");
    }

    @Test
    void concurrentAttemptsNeverOveradmit() throws Exception {
        final int threads = 16;
        final ConnectionAdmission a = new ConnectionAdmission(4, 100, 100, 100, 4, 100);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final InetAddress ip = addr(i + 1);
            pool.execute(() -> {
                try {
                    start.await();
                    if (a.allowAttempt(ip)) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "workers finished");
        pool.shutdownNow();
        assertEquals(4, allowed.get(), "exactly the global attempt budget is admitted");
    }

    @Test
    void concurrentLeasesNeverOveradmitAndAreHeldUntilObserved() throws Exception {
        final int threads = 12;
        final ConnectionAdmission a = new ConnectionAdmission(100, 100, 3, 100, 100, 100);
        final ConnectionAdmission.Lease[] leases = new ConnectionAdmission.Lease[threads];
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch proceed = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int index = i;
            final InetAddress ip = addr(i + 1);
            pool.execute(() -> {
                ConnectionAdmission.Lease lease = a.tryAcquire(ip);
                leases[index] = lease;
                ready.countDown();
                try {
                    proceed.await();
                    if (lease != null) {
                        lease.close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "all reservation results observed");
        int granted = 0;
        for (ConnectionAdmission.Lease lease : leases) {
            if (lease != null) {
                granted++;
            }
        }
        assertEquals(3, granted, "exactly the global open cap is granted while leases are held");
        proceed.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "workers closed their leases");
        pool.shutdownNow();
        ConnectionAdmission.Lease after = a.tryAcquire(other(1));
        assertNotNull(after, "closing all leases restores capacity");
        after.close();
    }
}
