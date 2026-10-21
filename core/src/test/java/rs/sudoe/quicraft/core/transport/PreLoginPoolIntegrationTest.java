// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.resource.ResourcePools;

/**
 * docs/protocol.md 12.13 pool ownership: a shed victim keeps its bytes until its close is confirmed, a declared
 * charge is retried with the same frame, expired staged connections are never granted, and promotion moves each
 * byte exactly once. Registry-level: the frame boundary is driven directly, not through a loopback stream.
 */
class PreLoginPoolIntegrationTest {
    private static final long STAGED = PreLoginTracker.stagedShare(5);
    /** A declared charge of STAGED + 4 bytes: larger than the staged share, so it needs pool bytes. */
    private static final long DECLARED = STAGED + 3;
    private static final long TOTAL = DECLARED + 1;

    private static PreLoginTrackerTest.FakePools poolOf(long bytes) {
        return new PreLoginTrackerTest.FakePools().capacity(ResourcePools.Pool.PRE_LOGIN, bytes);
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    /** One connection plus the registry's close record. */
    private static final class Conn {
        PreLoginRegistry.Entry entry;
    }

    private static Conn admit(PreLoginRegistry registry, String address, boolean online, List<Conn> shedLog,
            LongSupplier clock) throws Exception {
        Conn conn = new Conn();
        conn.entry = registry.admit(ip(address), conn, 5, online, clock, () -> shedLog.add(conn));
        return conn;
    }

    @Test
    void pendingShedDoesNotFundNewcomer() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolOf(STAGED * 3);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn slow = admit(registry, "192.0.2.10", true, shed, System::nanoTime);
        Conn fast = admit(registry, "192.0.2.11", true, shed, System::nanoTime);
        Conn third = admit(registry, "192.0.2.12", true, shed, System::nanoTime);
        slow.entry.recordProgress(10);
        fast.entry.recordProgress(500);
        third.entry.recordProgress(500);

        Conn honest = admit(registry, "198.51.100.5", true, shed, System::nanoTime);
        assertNull(honest.entry, "the newcomer is not granted while the victim's bytes are still charged");
        assertEquals(1, shed.size(), "the least-progressed victim is shed");
        assertTrue(shed.get(0) == slow);
        assertEquals(STAGED * 3, pools.held(ResourcePools.Pool.PRE_LOGIN), "the shed victim still holds its bytes");

        // A second newcomer must not shed anybody else while the first confirmation is pending.
        Conn other = admit(registry, "198.51.100.6", true, shed, System::nanoTime);
        assertNull(other.entry);
        assertEquals(1, shed.size(), "no second victim is chosen while the first close is pending");

        // Confirmation: the victim's close releases its bytes; only then can the newcomer be granted.
        slow.entry.close();
        Conn retried = admit(registry, "198.51.100.5", true, shed, System::nanoTime);
        assertNotNull(retried.entry, "the newcomer is granted from the confirmed bytes");
        assertEquals(STAGED * 3, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test
    void declaredWaitRetriesSameFrameOnce() throws Exception {
        // Room for three staged shares plus 10 bytes: the waiter's extra charge (TOTAL) does not fit until one closes.
        PreLoginTrackerTest.FakePools pools = poolOf(STAGED * 3 + 10);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn a = admit(registry, "192.0.2.10", true, shed, System::nanoTime);
        Conn b = admit(registry, "198.51.100.5", true, shed, System::nanoTime);
        Conn waiter = admit(registry, "203.0.113.9", true, shed, System::nanoTime);
        assertNotNull(a.entry);
        assertNotNull(b.entry);
        assertNotNull(waiter.entry);

        assertEquals(PreLoginTracker.ChargeResult.WAIT, waiter.entry.chargeDeclaredFrame(DECLARED, 1));
        assertEquals(STAGED * 3, pools.held(ResourcePools.Pool.PRE_LOGIN), "the waiter holds no extra bytes yet");

        // Another owner releases capacity; the wait set grants the waiter.
        a.entry.close();
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, waiter.entry.chargeDeclaredFrame(DECLARED, 1),
                "the retry with the same frame claims the grant");
        assertEquals(STAGED * 2 + TOTAL, pools.held(ResourcePools.Pool.PRE_LOGIN),
                "the frame is charged exactly once: two staged shares plus its one extra charge");
    }

    @Test
    void expiredDeclaredWaitClosesResource() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolOf(STAGED * 2 + 10);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        AtomicLong now = new AtomicLong(1_000_000_000L);
        List<Conn> shed = new ArrayList<>();
        Conn a = admit(registry, "192.0.2.10", true, shed, now::get);
        Conn b = admit(registry, "198.51.100.5", true, shed, now::get);
        assertNotNull(a.entry);
        assertNotNull(b.entry);
        assertEquals(PreLoginTracker.ChargeResult.WAIT, b.entry.chargeDeclaredFrame(DECLARED, 1));

        // Past the 30 s hard cap the connection has expired: the waiter is refused even once the pool frees up.
        now.addAndGet(31_000_000_000L);
        assertTrue(b.entry.expired());
        a.entry.close();
        assertEquals(PreLoginTracker.ChargeResult.WAIT, b.entry.chargeDeclaredFrame(DECLARED, 1),
                "an expired waiter is never granted");
        // A frame inside the staged share is refused too: an expired connection is not granted anything.
        assertEquals(PreLoginTracker.ChargeResult.WAIT, b.entry.chargeDeclaredFrame(10, 1),
                "an expired connection gets no in-share frame either");
        assertEquals(STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN), "only its own staged share is held");
    }

    @Test
    void promotionAndCompressionDoNotDoubleCharge() throws Exception {
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, STAGED * 2)
                .capacity(ResourcePools.Pool.NODE, PreLoginTracker.PROMOTED_BYTES);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn online = admit(registry, "192.0.2.10", true, shed, System::nanoTime);
        assertNotNull(online.entry);
        online.entry.onLoginSuccess();
        assertEquals(PreLoginTracker.PROMOTED_BYTES, pools.held(ResourcePools.Pool.NODE));
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN), "the staged share moved, not copied");

        // A late release after promotion must not release the pre-login pool again.
        online.entry.releaseExtra(STAGED);
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN));
        online.entry.close();
        online.entry.close();
        assertEquals(0, pools.held(ResourcePools.Pool.NODE), "the node exposure is released exactly once");
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test
    void continuityChurnRemainsBounded() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolOf(STAGED * 4);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(7));
        List<Conn> shed = new ArrayList<>();
        List<Conn> live = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            Conn conn = admit(registry, "192.0.2." + (i % 200 + 1), true, shed, System::nanoTime);
            if (conn.entry != null) {
                live.add(conn);
            }
            // Confirm every close the registry asked for, and retire an old connection now and then.
            for (Conn victim : shed) {
                victim.entry.close();
            }
            shed.clear();
            if (i % 3 == 0 && !live.isEmpty()) {
                live.remove(0).entry.close();
            }
            assertTrue(registry.stagedCount() <= 4, "staged connections stay within the pool");
            assertTrue(pools.held(ResourcePools.Pool.PRE_LOGIN) <= STAGED * 4, "the pool is never over capacity");
        }
    }

    @Test
    void declaredWaitersDrainAfterRelease() throws Exception {
        // Four staged shares and 10 spare bytes: each waiter needs TOTAL = STAGED + 4, so one release fits one waiter.
        PreLoginTrackerTest.FakePools pools = poolOf(STAGED * 4 + 10);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn a = admit(registry, "192.0.2.10", true, shed, System::nanoTime);
        Conn b = admit(registry, "198.51.100.5", true, shed, System::nanoTime);
        Conn w1 = admit(registry, "203.0.113.9", true, shed, System::nanoTime);
        Conn w2 = admit(registry, "203.0.114.9", true, shed, System::nanoTime);
        long waiterDeclared = STAGED + 3;
        assertEquals(PreLoginTracker.ChargeResult.WAIT, w1.entry.chargeDeclaredFrame(waiterDeclared, 1));
        assertEquals(PreLoginTracker.ChargeResult.WAIT, w2.entry.chargeDeclaredFrame(waiterDeclared, 1));

        // First release: exactly one waiter fits and is granted; the other keeps waiting.
        a.entry.close();
        Conn waiting = null;
        int granted = 0;
        for (Conn w : new Conn[] {w1, w2}) {
            if (w.entry.chargeDeclaredFrame(waiterDeclared, 1) == PreLoginTracker.ChargeResult.CHARGED) {
                granted++;
            } else {
                waiting = w;
            }
        }
        assertEquals(1, granted, "one freed share funds exactly one waiter");
        assertNotNull(waiting);

        // Second release: the remaining waiter is granted from the wait set and claims its frame.
        b.entry.close();
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, waiting.entry.chargeDeclaredFrame(waiterDeclared, 1),
                "the remaining waiter is granted once its room is released");
    }
}
