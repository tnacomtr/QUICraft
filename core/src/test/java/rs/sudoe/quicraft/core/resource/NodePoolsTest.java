// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NodePoolsTest {
    @Test void clampsExcessAndPromotionIsAtomic() {
        NodePools pools = new NodePools(1000, 1, 1000, 10, false);
        assertEquals(NodePools.MIN_EXCESS, pools.excessLimit());
        assertTrue(pools.reserve(ResourcePools.Pool.NODE, "full", 1000));
        assertTrue(pools.reserve(ResourcePools.Pool.PRE_LOGIN, "a", 100));
        assertFalse(pools.promote(ResourcePools.Pool.PRE_LOGIN, ResourcePools.Pool.NODE, "a", 100));
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN, "a"));
        assertEquals(1000, pools.reserved(ResourcePools.Pool.NODE));
    }

    @Test void concurrentReservationsStayWithinCapacity() throws Exception {
        final NodePools pools = new NodePools(10000, 10000, 10000, 10, false);
        List<Thread> threads = new ArrayList<Thread>();
        for (int n = 0; n < 8; n++) {
            final int id = n;
            Thread t = new Thread(() -> {
                for (int i = 0; i < 10000; i++) {
                    String owner = "owner" + id;
                    if (pools.reserve(ResourcePools.Pool.NODE, owner, 10)) pools.release(ResourcePools.Pool.NODE, owner, 10);
                }
            });
            threads.add(t); t.start();
        }
        for (Thread t : threads) t.join();
        assertEquals(0, pools.reserved(ResourcePools.Pool.NODE));
        assertTrue(pools.reserved(ResourcePools.Pool.NODE) <= pools.limit(ResourcePools.Pool.NODE));
    }

    @Test void identityPressureDecaysAndPrefixBucketsAreDeterministic() throws Exception {
        IdentityHistory history = new IdentityHistory(60000, 0);
        history.integrate(100, 1_000_000_000L, 0);
        double before = history.byteSeconds();
        history.integrate(0, 60_000_000_000L, 0);
        assertTrue(history.byteSeconds() < before);
        byte[] secret = new byte[] {1,2,3};
        PrefixKey a = PrefixKey.overflow(java.net.InetAddress.getByName("2001:db8:1::1"));
        PrefixKey b = PrefixKey.overflow(java.net.InetAddress.getByName("2001:db8:1:ffff::1"));
        assertEquals(a, b);
        assertEquals(a.bucket(secret), b.bucket(secret));
    }
}
