// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboundPoolTest {
    private static HolderRank ranks(final Map<Object, Double> scores) {
        return new HolderRank() {
            @Override public double score(Object owner) {
                Double value = scores.get(owner);
                return value == null ? 0 : value;
            }
        };
    }

    @Test void sourceCapShedsOnlyAHolderOfTheSameIp() {
        NodePools node = new NodePools(1000, 0, 1000, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 100, 100, 100, ranks(Collections.<Object, Double>emptyMap()));
        assertTrue(pool.reserve("a", "ip-a", null, 80));
        assertTrue(pool.reserve("b", "ip-b", null, 50));
        assertFalse(pool.reserve("c", "ip-a", null, 30));
        assertEquals(java.util.Arrays.asList((Object) "a"), pool.drainShedOwners());
        assertEquals(80, pool.reserved("a"), "a shed holder stays charged until confirmed");
        pool.confirmClosed("a");
        assertEquals(0, pool.reserved("a"));
        assertEquals(50, pool.reserved("b"));
        assertEquals(30, pool.reserved("c"));
    }

    @Test void aHolderOfOnlyItsProtectedSliceIsNeverShed() {
        NodePools node = new NodePools(1000, 0, 1000, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 100, 100, 100, ranks(Collections.<Object, Double>emptyMap()));
        assertTrue(pool.takeGrant(pool.reserveProtectedTicket("a", "ip-a", null, 40)));
        assertTrue(pool.takeGrant(pool.reserveProtectedTicket("b", "ip-a", null, 40)));
        assertFalse(pool.reserve("c", "ip-a", null, 30));
        assertTrue(pool.drainShedOwners().isEmpty(), "holders of only their slice are not victims");
        pool.cancel("c");
        assertTrue(pool.reserve("a", "ip-a", null, 1));
        assertFalse(pool.reserve("d", "ip-a", null, 30));
        assertEquals(java.util.Arrays.asList((Object) "a"), pool.drainShedOwners(),
                "bytes beyond the slice make the holder a victim again");
    }

    @Test void accountCapSupplementsIpCapAndShedsOnlySameAccount() {
        NodePools node = new NodePools(1000, 0, 1000, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 100, 20, 100, ranks(Collections.<Object, Double>emptyMap()));
        assertTrue(pool.reserve("a", "ip-a", "account", 20));
        assertTrue(pool.reserve("d", "ip-d", "other", 5));
        assertFalse(pool.reserve("b", "ip-b", "account", 1));
        assertEquals(java.util.Arrays.asList((Object) "a"), pool.drainShedOwners());
        pool.confirmClosed("a");
        assertEquals(5, pool.reserved("d"));
        assertEquals(1, pool.reserved("b"));
    }

    @Test void highestRankedHolderIsShedFirstAndOnlyAsFarAsNeeded() {
        NodePools node = new NodePools(1000, 0, 100, 10, false, 0);
        Map<Object, Double> scores = new HashMap<Object, Double>();
        scores.put("a", 5.0);
        scores.put("b", 50.0);
        scores.put("c", 1.0);
        OutboundPool pool = new OutboundPool(node, 1000, 1000, 100, ranks(scores));
        assertTrue(pool.reserve("a", "ip-a", null, 40));
        assertTrue(pool.reserve("b", "ip-b", null, 40));
        assertTrue(pool.reserve("c", "ip-c", null, 20));
        assertFalse(pool.reserve("d", "ip-d", null, 30));
        assertEquals(java.util.Arrays.asList((Object) "b"), pool.drainShedOwners());
        pool.confirmClosed("b");
        assertEquals(40, pool.reserved("a"));
        assertEquals(20, pool.reserved("c"));
        assertEquals(30, pool.reserved("d"));
    }

    @Test void requestNoCapCanEverAdmitIsALocalFailureNotAWait() {
        NodePools node = new NodePools(1000, 0, 100, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1000, 1000, 100, ranks(Collections.<Object, Double>emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> pool.reserve("a", "ip-a", null, 101));
        assertEquals(0, pool.waitingCount());
    }

    @Test void cancelReleasesBytesAndWaits() {
        NodePools node = new NodePools(1000, 0, 100, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1000, 1000, 100, ranks(Collections.<Object, Double>emptyMap()));
        assertTrue(pool.reserve("a", "ip-a", null, 100));
        pool.cancel("a");
        assertEquals(0, node.reserved(ResourcePools.Pool.OUTBOUND));
        assertTrue(pool.reserve("b", "ip-b", null, 100));
        assertFalse(pool.drainShedOwners().contains("b"));
    }

    @Test void waiterBlockedOnlyByItsOwnOwnerCapDoesNotBlockASameIpRequest() {
        NodePools node = new NodePools(1000, 0, 1000, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 1000, 1000, 100, ranks(Collections.<Object, Double>emptyMap()));
        assertTrue(pool.reserve("a", "ip-a", null, 100));
        assertFalse(pool.reserve("a", "ip-a", null, 10), "a is at its own 100-byte owner cap");
        // The earlier waiter shares the IP but faces only its owner cap, which c does not share (13.12 FIFO).
        assertTrue(pool.reserve("c", "ip-a", null, 10));
        assertEquals(10, pool.reserved("c"));
    }

    @Test void shedVictimStaysChargedUntilConfirmedAndWaitersAreNotGrantedBeforehand() {
        NodePools node = new NodePools(100, 0, 100, 10, false, 0);
        Map<Object, Double> scores = new HashMap<Object, Double>();
        scores.put("a", 5.0);
        scores.put("b", 1.0);
        OutboundPool pool = new OutboundPool(node, 1000, 1000, 100, ranks(scores));
        assertTrue(pool.reserve("a", "ip-a", null, 60));
        assertTrue(pool.reserve("b", "ip-b", null, 40));
        assertFalse(pool.reserve("d", "ip-d", null, 30));
        assertEquals(java.util.Arrays.asList((Object) "a"), pool.drainShedOwners());
        assertEquals(60, pool.reserved("a"), "shed bytes are not released before the close is confirmed");
        assertEquals(100, node.reserved(ResourcePools.Pool.OUTBOUND));
        assertEquals(1, pool.waitingCount(), "the waiter keeps its FIFO place while the victim is unconfirmed");
        // A second waiter must not pick another victim for the deficit that a shed holder already covers.
        assertFalse(pool.reserve("e", "ip-e", null, 30));
        assertTrue(pool.drainShedOwners().isEmpty(), "no extra victim while the first shed is unconfirmed");
        pool.confirmClosed("a");
        assertEquals(0, pool.reserved("a"));
        assertEquals(40, pool.reserved("b"), "b is not shed: the deficit was covered by a alone");
        assertEquals(30, pool.reserved("d"));
    }
}
