// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Inbound excess: E-sized connection share, startup clamp, eligibility, FIFO retry and attribution (13.12). */
class InboundExcessPoolTest {
    /** Test E: small numbers keep the arithmetic readable; production uses NodePools.MIN_EXCESS. */
    private static final long E = 100;

    private static HolderRank ranks(final Map<Object, Double> scores) {
        return new HolderRank() {
            @Override public double score(Object owner) {
                Double value = scores.get(owner);
                return value == null ? 0 : value;
            }
        };
    }

    private static InboundExcessPool pool(long nodeCap, long ipCap, long accountCap, Map<Object, Double> scores) {
        NodePools node = new NodePools(nodeCap, nodeCap, 0, 10, false, E);
        return new InboundExcessPool(node, ipCap, accountCap, ranks(scores));
    }

    @Test void startupClampRaisesCallerCapsUpToE() {
        InboundExcessPool pool = pool(1000, 1, 2, new HashMap<Object, Double>());
        assertEquals(E, pool.perIpCap());
        assertEquals(E, pool.perAccountCap());
    }

    @Test void startupClampKeepsHigherCapsAndBoundsAccountCapByNodeCap() {
        InboundExcessPool pool = pool(1000, 500, 2000, new HashMap<Object, Double>());
        assertEquals(500, pool.perIpCap());
        assertEquals(1000, pool.perAccountCap());
    }

    @Test void startupClampNeverFailsWhenNodeCapIsBelowE() {
        NodePools node = new NodePools(1000, 50, 0, 10, false, E);
        InboundExcessPool pool = new InboundExcessPool(node, 10, 10, ranks(new HashMap<Object, Double>()));
        assertEquals(E, pool.perIpCap());
    }

    @Test void sameIpShedsOnlyTheHolderThatRelievesTheSourceCap() {
        Map<Object, Double> scores = new HashMap<Object, Double>();
        scores.put("a", 1000.0);
        scores.put("b", 10000.0);
        InboundExcessPool pool = pool(1000, 150, 150, scores);
        assertTrue(pool.reserve("a", "ip-a", null, 100, false));
        assertTrue(pool.reserve("b", "ip-b", null, 100, false));
        assertFalse(pool.reserve("c", "ip-a", null, 100, false));
        assertEquals(Arrays.asList((Object) "a"), pool.drainShedOwners());
        assertEquals(100, pool.reserved("b"));
        pool.confirmClosed("a");
        assertEquals(0, pool.reserved("a"));
        assertEquals(100, pool.reserved("b"));
        assertEquals(100, pool.reserved("c"));
    }

    @Test void localStallHolderIsNeverAVictimAndWaiterIsGrantedOnRelease() {
        InboundExcessPool pool = pool(1000, 150, 150, new HashMap<Object, Double>());
        assertTrue(pool.reserve("a", "ip-a", null, 100, true));
        assertFalse(pool.reserve("c", "ip-a", null, 100, false));
        assertEquals(1, pool.waitingCount());
        assertEquals(100, pool.reserved("a"));
        assertTrue(pool.drainShedOwners().isEmpty());
        pool.release("a", 100);
        assertEquals(100, pool.reserved("c"));
        assertEquals(0, pool.waitingCount());
    }

    @Test void releasedCapacityRetriesWaitersFifoBeforeNewArrivals() {
        InboundExcessPool pool = pool(200, 150, 150, new HashMap<Object, Double>());
        assertTrue(pool.reserve("a", "ip-a", null, 100, true));
        assertTrue(pool.reserve("d", "ip-d", null, 100, true));
        assertFalse(pool.reserve("b", "ip-b", null, 100, false));
        assertFalse(pool.reserve("e", "ip-e", null, 10, false));
        assertEquals(2, pool.waitingCount());
        pool.release("d", 100);
        assertEquals(100, pool.reserved("b"));
        assertEquals(0, pool.reserved("e"));
        assertEquals(1, pool.waitingCount());
    }

    @Test void newArrivalFromUnrelatedIpIsNotBlockedBySourceCapWaiter() {
        InboundExcessPool pool = pool(1000, 150, 150, new HashMap<Object, Double>());
        assertTrue(pool.reserve("a", "ip-a", null, 100, true));
        assertFalse(pool.reserve("c", "ip-a", null, 100, false));
        assertTrue(pool.reserve("f", "ip-f", null, 50, false));
        assertEquals(1, pool.waitingCount());
    }

    @Test void cancelReleasesExcessAndGrantsWaiters() {
        InboundExcessPool pool = pool(1000, 150, 150, new HashMap<Object, Double>());
        assertTrue(pool.reserve("a", "ip-a", null, 100, true));
        assertFalse(pool.reserve("c", "ip-a", null, 100, false));
        pool.cancel("a");
        assertEquals(100, pool.reserved("c"));
        assertEquals(0, pool.waitingCount());
    }

    @Test void requestAboveConnectionShareIsALocalFailure() {
        InboundExcessPool pool = pool(1000, 150, 150, new HashMap<Object, Double>());
        assertThrows(IllegalArgumentException.class, () -> pool.reserve("a", "ip-a", null, E + 1, false));
    }

    @Test void peerAttributionFollowsTheSpecRules() {
        // Bytes waiting on our executor are never peer-caused.
        assertFalse(InboundExcessPool.peerAttributable(true, 500, 500, E, true, E, E));
        // A connection exceeding its own cap is peer-caused.
        assertTrue(InboundExcessPool.peerAttributable(false, E, 1, E, false, 1000, E));
        // A shared cap held only by this connection, with a cap of at least E, is peer-caused.
        assertTrue(InboundExcessPool.peerAttributable(false, 0, 10, E, true, E, E));
        // A shared cap held partly by other users (CGNAT) is never peer-caused when the connection is within E.
        assertFalse(InboundExcessPool.peerAttributable(false, 10, 10, E, false, E, E));
        // A cap below E is a local misconfiguration, never peer-caused.
        assertFalse(InboundExcessPool.peerAttributable(false, 0, 10, E, true, E - 1, E));
    }

    @Test void concurrentExcessGrantsNeverExceedNodeCapOrGoNegative() throws Exception {
        final NodePools node = new NodePools(200, 200, 0, 10, false, E);
        final InboundExcessPool pool = new InboundExcessPool(node, 200, 200, ranks(new HashMap<Object, Double>()));
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicInteger grants = new AtomicInteger();
        final AtomicInteger refusals = new AtomicInteger();
        final List<Thread> threads = new ArrayList<Thread>();
        for (int n = 0; n < 6; n++) {
            final String owner = "owner" + n;
            Thread t = new Thread(() -> {
                try {
                    for (int i = 0; i < 2000; i++) {
                        // Six owners each ask for 60 bytes against a 200-byte node cap, so grants contend at the cap.
                        // Each step ends the owner's previous grant, or asks again; a refused wait is dropped at once.
                        synchronized (pool) {
                            if (pool.reserved(owner) > 0) {
                                pool.release(owner, pool.reserved(owner));
                            } else if (pool.reserve(owner, "ip-" + owner, null, 60, false)) {
                                grants.incrementAndGet();
                            } else {
                                refusals.incrementAndGet();
                                pool.cancel(owner);
                            }
                            // Shed victims are closed as the integration does, so every release stays legal.
                            for (Object victim : pool.drainShedOwners()) pool.confirmClosed(victim);
                            if (node.reserved(ResourcePools.Pool.INBOUND_EXCESS) > 200) throw new AssertionError("over cap");
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
        assertTrue(grants.get() > 0, "some requests are granted");
        assertTrue(refusals.get() > 0, "requests contend at the node cap and are refused");
        assertTrue(node.reserved(ResourcePools.Pool.INBOUND_EXCESS) <= 200);
    }

    @Test void drainShedOwnersReturnsEachVictimOnce() {
        Map<Object, Double> scores = Collections.<Object, Double>singletonMap("a", 1.0);
        InboundExcessPool pool = pool(1000, 150, 150, scores);
        assertTrue(pool.reserve("a", "ip-a", null, 100, false));
        assertFalse(pool.reserve("c", "ip-a", null, 100, false), "c waits until the victim is confirmed closed");
        assertEquals(1, pool.drainShedOwners().size());
        assertTrue(pool.drainShedOwners().isEmpty());
        pool.confirmClosed("a");
        assertEquals(100, pool.reserved("c"));
    }
}
