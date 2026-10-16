// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NodePoolsTest {
    @Test void preLoginPoolIsPercentOfNodeBudget() {
        NodePools pools = new NodePools(1001, 0, 0, 10, false, 100);
        assertEquals(100, pools.limit(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test void connectionExcessIsKeptSeparateFromNodeExcessCap() {
        NodePools pools = new NodePools(1000, 64, 1000, 10, false, 500);
        assertEquals(500, pools.connectionExcess());
        assertEquals(64, pools.limit(ResourcePools.Pool.INBOUND_EXCESS));
    }

    @Test void defaultConstructorUsesConservativeExcessBound() {
        NodePools pools = new NodePools(1000);
        assertEquals(NodePools.MIN_EXCESS, pools.connectionExcess());
    }

    @Test void deflaterPoolFollowsAdmissionOption() {
        assertEquals(ResourcePools.Pool.INFLATER, new NodePools(1000, 0, 0, 10, true, 0).deflaterPool());
        assertEquals(ResourcePools.Pool.OUTBOUND, new NodePools(1000, 0, 0, 10, false, 0).deflaterPool());
    }

    @Test void refusedPromotionLeavesBothPoolsUnchanged() {
        // PRE_LOGIN to NODE never changes the shared total, so a refusal needs a source outside the budget.
        NodePools pools = new NodePools(1000, 1000, 0, 10, false, 0);
        assertTrue(pools.reserve(ResourcePools.Pool.NODE, "full", 900));
        assertTrue(pools.reserve(ResourcePools.Pool.INBOUND_EXCESS, "a", 150));
        assertFalse(pools.promote(ResourcePools.Pool.INBOUND_EXCESS, ResourcePools.Pool.NODE, "a", 150));
        assertEquals(150, pools.reserved(ResourcePools.Pool.INBOUND_EXCESS, "a"));
        assertEquals(150, pools.reserved(ResourcePools.Pool.INBOUND_EXCESS));
        assertEquals(900, pools.reserved(ResourcePools.Pool.NODE));
    }

    @Test void successfulPromotionMovesTheWholeChargeAtomically() {
        NodePools pools = new NodePools(1000, 0, 0, 10, false, 0);
        assertTrue(pools.reserve(ResourcePools.Pool.PRE_LOGIN, "a", 60));
        assertTrue(pools.promote(ResourcePools.Pool.PRE_LOGIN, ResourcePools.Pool.NODE, "a", 60));
        assertEquals(0, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        assertEquals(60, pools.reserved(ResourcePools.Pool.NODE, "a"));
        assertEquals(60, pools.reserved(ResourcePools.Pool.NODE));
    }

    @Test void releaseBeyondReservationIsRejected() {
        NodePools pools = new NodePools(1000, 0, 0, 10, false, 0);
        assertTrue(pools.reserve(ResourcePools.Pool.NODE, "a", 10));
        assertThrows(IllegalStateException.class, () -> pools.release(ResourcePools.Pool.NODE, "a", 11));
        assertEquals(10, pools.reserved(ResourcePools.Pool.NODE));
    }

    @Test void concurrentReservationsNeverExceedCapOrGoNegative() throws Exception {
        final NodePools pools = new NodePools(1000, 0, 0, 10, false, 0);
        final AtomicInteger overCap = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        List<Thread> threads = new ArrayList<Thread>();
        for (int n = 0; n < 8; n++) {
            final String owner = "owner" + n;
            Thread t = new Thread(() -> {
                try {
                    for (int i = 0; i < 20_000; i++) {
                        if (pools.reserve(ResourcePools.Pool.NODE, owner, 7)) {
                            if (pools.reserved(ResourcePools.Pool.NODE) > 1000) overCap.incrementAndGet();
                            pools.release(ResourcePools.Pool.NODE, owner, 7);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        assertEquals(null, failure.get());
        assertEquals(0, overCap.get());
        assertEquals(0, pools.reserved(ResourcePools.Pool.NODE));
    }

    @Test void concurrentPromotionsConserveTotalBytes() throws Exception {
        final NodePools pools = new NodePools(10_000, 0, 0, 50, false, 0);
        for (int n = 0; n < 8; n++) assertTrue(pools.reserve(ResourcePools.Pool.PRE_LOGIN, "p" + n, 500));
        final int initial = 8 * 500;
        List<Thread> threads = new ArrayList<Thread>();
        for (int n = 0; n < 8; n++) {
            final String owner = "p" + n;
            Thread t = new Thread(() -> {
                for (int i = 0; i < 500; i++) pools.promote(ResourcePools.Pool.PRE_LOGIN, ResourcePools.Pool.NODE, owner, 1);
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        assertEquals(initial, pools.reserved(ResourcePools.Pool.PRE_LOGIN) + pools.reserved(ResourcePools.Pool.NODE));
        assertEquals(0, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test void budgetedPoolsShareOneNodeBudgetNotEachAlone() {
        NodePools pools = new NodePools(1000, 0, 0, 10, true, 0);
        assertTrue(pools.reserve(ResourcePools.Pool.NODE, "node", 900));
        // INFLATER alone has 1000 bytes of room, but the node budget only has 100 left (protocol.md 13.12).
        assertFalse(pools.reserve(ResourcePools.Pool.INFLATER, "inflater", 200));
        assertEquals(0, pools.reserved(ResourcePools.Pool.INFLATER));
        assertTrue(pools.reserve(ResourcePools.Pool.INFLATER, "inflater", 100));
        assertFalse(pools.reserve(ResourcePools.Pool.PRE_LOGIN, "pre", 1));
    }

    @Test void promotionKeepsTheSharedTotalAndNodeRoomIsCheckedAfterIt() {
        NodePools pools = new NodePools(1000, 0, 0, 10, false, 0);
        assertTrue(pools.reserve(ResourcePools.Pool.INFLATER, "inflater", 900));
        assertTrue(pools.reserve(ResourcePools.Pool.PRE_LOGIN, "pre", 100));
        assertFalse(pools.reserve(ResourcePools.Pool.NODE, "node", 1), "budget is full across pools");
        assertTrue(pools.promote(ResourcePools.Pool.PRE_LOGIN, ResourcePools.Pool.NODE, "pre", 100));
        assertEquals(100, pools.reserved(ResourcePools.Pool.NODE, "pre"));
        assertEquals(1000, pools.reserved(ResourcePools.Pool.NODE) + pools.reserved(ResourcePools.Pool.INFLATER)
                + pools.reserved(ResourcePools.Pool.PRE_LOGIN));
    }
}
