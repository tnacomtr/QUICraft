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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Behavior of the bounded admission state (docs/protocol.md Retry and limits). These tests drive
 * only the package-private API with a fixed or injected clock; no pipeline is involved.
 *
 * <p>Non-expiry behavior uses {@link #FROZEN} so no window can roll over mid-test. Only the
 * window-expiry tests advance an injected {@link AtomicLong} clock. Every test-owned lease is
 * registered through {@link #owned(ConnectionAdmission.Lease)} so it is closed even when an
 * assertion fails; production {@code close()} is idempotent.
 */
class ConnectionAdmissionTest {
    private static final long WINDOW = 1_000_000_000L;

    /** A clock frozen in time so attempt budgets never roll over during a test. */
    private static final LongSupplier FROZEN = () -> 0L;

    private final ConcurrentLinkedQueue<ConnectionAdmission.Lease> ownedLeases =
            new ConcurrentLinkedQueue<>();

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

    /** Registers a test-owned lease for {@link #closeOwnedLeases()} and returns it unchanged. */
    private ConnectionAdmission.Lease owned(ConnectionAdmission.Lease lease) {
        if (lease != null) {
            ownedLeases.add(lease);
        }
        return lease;
    }

    @AfterEach
    void closeOwnedLeases() {
        ConnectionAdmission.Lease lease;
        while ((lease = ownedLeases.poll()) != null) {
            lease.close();
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
        ConnectionAdmission a = new ConnectionAdmission(10, 3, 5, 5, 10, 10, FROZEN);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.allowAttempt(addr(1)), "the fourth per-IP attempt is denied");
    }

    @Test
    void allowsExactlyGlobalAttemptsAcrossDistinctIps() {
        ConnectionAdmission a = new ConnectionAdmission(3, 5, 5, 5, 3, 10, FROZEN);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(2)));
        assertTrue(a.allowAttempt(addr(3)));
        assertFalse(a.allowAttempt(addr(4)), "the fourth global attempt is denied");
    }

    @Test
    void distinctIpsHaveIndependentPerIpBudgets() {
        ConnectionAdmission a = new ConnectionAdmission(100, 2, 5, 5, 100, 10, FROZEN);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(2)), "another IP keeps its own budget");
        assertTrue(a.allowAttempt(addr(2)));
        assertFalse(a.allowAttempt(addr(2)));
    }

    @Test
    void perIpRefusalConsumesNoGlobalAttempt() {
        ConnectionAdmission a = new ConnectionAdmission(2, 1, 5, 5, 2, 10, FROZEN);
        assertTrue(a.allowAttempt(addr(1)));
        assertFalse(a.allowAttempt(addr(1)), "per-IP limit denies");
        assertFalse(a.requiresRetry(), "the per-IP refusal consumed no global attempt");
        assertTrue(a.allowAttempt(addr(2)), "the second global attempt is still there");
        assertTrue(a.requiresRetry());
    }

    @Test
    void trackingRefusalConsumesNoGlobalAttempt() {
        ConnectionAdmission a = new ConnectionAdmission(5, 5, 5, 5, 3, 2, FROZEN);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(2)));
        assertFalse(a.allowAttempt(addr(3)), "tracking capacity denies a third IP");
        assertFalse(a.requiresRetry(), "the tracking refusal consumed no global attempt");
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.requiresRetry());
    }

    @Test
    void oneIpOverItsLimitLeavesTheGlobalBudgetForOthers() {
        ConnectionAdmission a = new ConnectionAdmission(3, 1, 5, 5, 3, 10, FROZEN);
        assertTrue(a.allowAttempt(addr(1)));
        for (int i = 0; i < 1000; i++) {
            assertFalse(a.allowAttempt(addr(1)), "per-IP limit denies the flood");
        }
        assertTrue(a.allowAttempt(addr(2)), "another IP still gets in");
        assertTrue(a.allowAttempt(addr(3)));
        assertFalse(a.allowAttempt(addr(4)), "only the real global budget of three is spent");
    }

    private static InetAddress v6(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void ipv6AddressesInOneSlash64ShareTheirAttemptLimit() {
        ConnectionAdmission a = new ConnectionAdmission(10, 1, 5, 5, 10, 10, FROZEN);
        assertTrue(a.allowAttempt(v6("2001:db8:1:2::1")));
        assertFalse(a.allowAttempt(v6("2001:db8:1:2:ffff:ffff:ffff:fffe")), "same /64, same budget");
        assertTrue(a.allowAttempt(v6("2001:db8:1:3::1")), "another /64 has its own budget");
    }

    @Test
    void ipv6AddressesInOneSlash64ShareTheirOpenCapAndReleaseTheSameKey() {
        ConnectionAdmission a = new ConnectionAdmission(10, 10, 5, 1, 10, 10, FROZEN);
        ConnectionAdmission.Lease first = owned(a.tryAcquire(v6("2001:db8:1:2::1")));
        assertNotNull(first);
        assertNull(owned(a.tryAcquire(v6("2001:db8:1:2::2"))), "same /64, one open connection");
        assertNotNull(owned(a.tryAcquire(v6("2001:db8:1:3::1"))), "another /64 has its own cap");
        first.close();
        assertNotNull(owned(a.tryAcquire(v6("2001:db8:1:2::2"))), "the lease released its /64 key");
    }

    @Test
    void limitKeyKeepsIpv4AndFoldsIpv4MappedAndMasksIpv6() throws Exception {
        assertEquals(addr(7), ConnectionAdmission.limitKey(addr(7)));
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        System.arraycopy(addr(7).getAddress(), 0, mapped, 12, 4);
        // getByAddress already folds mapped addresses; build an Inet6Address explicitly.
        InetAddress mapped6 = java.net.Inet6Address.getByAddress(null, mapped, -1);
        assertEquals(addr(7), ConnectionAdmission.limitKey(mapped6), "IPv4-mapped counts as IPv4");
        assertEquals(v6("2001:db8:1:2::"), ConnectionAdmission.limitKey(v6("2001:db8:1:2:3:4:5:6")));
        assertEquals(v6("fe80::"), ConnectionAdmission.limitKey(v6("fe80::1%1")), "scope dropped");

        ConnectionAdmission a = new ConnectionAdmission(10, 1, 5, 5, 10, 10, FROZEN);
        assertTrue(a.allowAttempt(addr(7)));
        assertFalse(a.allowAttempt(mapped6), "the mapped form shares the IPv4 budget");
    }

    @Test
    void pendingCountsUnconfirmedLeasesExactlyOnce() {
        ConnectionAdmission a = new ConnectionAdmission(10, 10, 4, 4, 10, 10, FROZEN);
        ConnectionAdmission.Lease probe = a.tryAcquire(addr(1));
        probe.close();
        assertEquals(0, a.pendingLeases(), "an acquire-and-close probe leaves pending unchanged");
        assertFalse(a.handshakesBacklogged());

        ConnectionAdmission.Lease lease = owned(a.tryAcquire(addr(1)));
        assertEquals(1, a.pendingLeases());
        assertTrue(a.handshakesBacklogged(), "one pending of four open is 25%");
        lease.confirm();
        lease.confirm();
        assertEquals(0, a.pendingLeases(), "confirm decrements once");
        assertFalse(a.handshakesBacklogged());
        lease.close();
        lease.close();
        assertEquals(0, a.pendingLeases(), "closing a confirmed lease leaves pending alone");

        ConnectionAdmission.Lease abandoned = owned(a.tryAcquire(addr(2)));
        abandoned.close();
        abandoned.confirm();
        assertEquals(0, a.pendingLeases(), "close decrements an unconfirmed lease once; confirm after close is a no-op");
    }

    @Test
    void globalRefusalAllocatesNoTrackingSlot() {
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 1, 1, 1, 2, FROZEN);
        assertTrue(a.allowAttempt(addr(1)), "first IP consumes the only global attempt and tracks");
        assertFalse(a.allowAttempt(addr(2)), "second IP is globally denied");
        ConnectionAdmission.Lease c = owned(a.tryAcquire(addr(3)));
        assertNotNull(c, "a globally denied IP must not consume the second tracking slot");
        c.close();
        c.close();
        assertNotNull(owned(a.tryAcquire(addr(3))), "repeated close calls stay safe");
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
        ConnectionAdmission a = new ConnectionAdmission(1, 1, 1, 1, 1, 10, FROZEN);
        ConnectionAdmission.Lease lease = owned(a.tryAcquire(addr(1)));
        assertNotNull(lease);
        assertFalse(a.requiresRetry(), "tryAcquire must not spend an attempt");
        assertTrue(a.allowAttempt(addr(1)), "the attempt budget is still available");
        assertTrue(a.requiresRetry());
        lease.close();
    }

    @Test
    void openLeaseCapsAreExactGloballyAndPerIp() {
        ConnectionAdmission a = new ConnectionAdmission(10, 10, 2, 1, 10, 10, FROZEN);
        ConnectionAdmission.Lease first = owned(a.tryAcquire(addr(1)));
        assertNotNull(first);
        assertNull(owned(a.tryAcquire(addr(1))), "per-IP open cap of one");
        ConnectionAdmission.Lease second = owned(a.tryAcquire(addr(2)));
        assertNotNull(second);
        assertNull(owned(a.tryAcquire(addr(3))), "global open cap of two");
        first.close();
        second.close();
    }

    @Test
    void closeIsIdempotentAndFreesCapacityExactlyOnce() {
        ConnectionAdmission a = new ConnectionAdmission(10, 10, 1, 1, 10, 10, FROZEN);
        ConnectionAdmission.Lease lease = owned(a.tryAcquire(addr(1)));
        assertNotNull(lease);
        assertNull(owned(a.tryAcquire(addr(2))), "global cap of one is full");
        lease.close();
        lease.close();
        lease.close();
        ConnectionAdmission.Lease next = owned(a.tryAcquire(addr(2)));
        assertNotNull(next, "capacity freed exactly once");
        assertNull(owned(a.tryAcquire(addr(3))), "a double close must not free capacity twice");
        next.close();
    }

    @Test
    void trackingCapacityRefusesNewIpWithoutEvictingAttemptsOrLeases() {
        ConnectionAdmission a = new ConnectionAdmission(100, 2, 5, 1, 100, 2, FROZEN);
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(1)));
        assertTrue(a.allowAttempt(addr(2)));
        ConnectionAdmission.Lease lease = owned(a.tryAcquire(addr(2)));
        assertNotNull(lease);
        assertFalse(a.allowAttempt(addr(3)), "a third IP exceeds the tracking capacity");
        assertFalse(a.allowAttempt(addr(1)), "the tracked per-IP attempts are preserved");
        assertNull(owned(a.tryAcquire(addr(2))), "the tracked open lease is preserved");
        assertNull(owned(a.tryAcquire(addr(4))), "tracking capacity also bounds new leases");
        lease.close();
    }

    @Test
    void nextWindowPrunesIdleEntriesButRetainsActiveLeases() {
        AtomicLong now = new AtomicLong(0);
        ConnectionAdmission a = new ConnectionAdmission(100, 5, 5, 1, 100, 2, now::get);
        assertTrue(a.allowAttempt(addr(1)), "idle attempt-only entry");
        ConnectionAdmission.Lease lease = owned(a.tryAcquire(addr(2)));
        assertNotNull(lease);
        now.set(WINDOW);
        assertTrue(a.allowAttempt(addr(3)), "the idle entry was pruned, freeing a tracking slot");
        assertNull(owned(a.tryAcquire(addr(2))), "the entry holding an open lease is retained");
        lease.close();
    }

    @Test
    void closeAtNewWindowPrunesFormerlySpentIdleEntry() {
        AtomicLong now = new AtomicLong(0);
        ConnectionAdmission a = new ConnectionAdmission(100, 5, 1, 1, 100, 1, now::get);
        ConnectionAdmission.Lease lease = owned(a.tryAcquire(addr(1)));
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
        final ConnectionAdmission a = new ConnectionAdmission(4, 100, 100, 100, 4, 100, FROZEN);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger allowed = new AtomicInteger();
        final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
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
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "workers finished");
            assertTrue(failures.isEmpty(), () -> "unexpected worker failures: " + failures);
            assertEquals(4, allowed.get(), "exactly the global attempt budget is admitted");
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentLeasesNeverOveradmitAndAreHeldUntilObserved() throws Exception {
        final int threads = 12;
        final ConnectionAdmission a = new ConnectionAdmission(100, 100, 3, 100, 100, 100, FROZEN);
        final ConnectionAdmission.Lease[] leases = new ConnectionAdmission.Lease[threads];
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch proceed = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                final InetAddress ip = addr(i + 1);
                pool.execute(() -> {
                    ConnectionAdmission.Lease lease = null;
                    try {
                        lease = owned(a.tryAcquire(ip));
                        leases[index] = lease;
                        ready.countDown();
                        proceed.await();
                        if (lease != null) {
                            lease.close();
                            lease = null;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        if (lease != null) {
                            lease.close();
                        }
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
            assertTrue(failures.isEmpty(), () -> "unexpected worker failures: " + failures);
            ConnectionAdmission.Lease after = owned(a.tryAcquire(other(1)));
            assertNotNull(after, "closing all leases restores capacity");
            after.close();
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }
}
