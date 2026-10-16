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
        assertTrue(pool.reserve("c", "ip-a", null, 30));
        assertEquals(java.util.Arrays.asList((Object) "a"), pool.drainShedOwners());
        assertEquals(0, pool.reserved("a"));
        assertEquals(50, pool.reserved("b"));
        assertEquals(30, pool.reserved("c"));
    }

    @Test void accountCapSupplementsIpCapAndShedsOnlySameAccount() {
        NodePools node = new NodePools(1000, 0, 1000, 10, false, 0);
        OutboundPool pool = new OutboundPool(node, 100, 20, 100, ranks(Collections.<Object, Double>emptyMap()));
        assertTrue(pool.reserve("a", "ip-a", "account", 20));
        assertTrue(pool.reserve("d", "ip-d", "other", 5));
        assertTrue(pool.reserve("b", "ip-b", "account", 1));
        assertEquals(java.util.Arrays.asList((Object) "a"), pool.drainShedOwners());
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
        assertTrue(pool.reserve("d", "ip-d", null, 30));
        assertEquals(java.util.Arrays.asList((Object) "b"), pool.drainShedOwners());
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
}
