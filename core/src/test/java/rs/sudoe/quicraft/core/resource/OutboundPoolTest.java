// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OutboundPoolTest {
    @Test void nodeAndSourceCapsAndFifoRetry() {
        NodePools node = new NodePools(1000, NodePools.MIN_EXCESS, 100, 10, false);
        OutboundPool pool = new OutboundPool(node, 100, 100);
        assertTrue(pool.reserve("a", "ip-a", null, 80));
        assertFalse(pool.reserve("b", "ip-a", null, 30));
        assertEquals(1, pool.waitingCount());
        pool.release("a", 80);
        assertEquals(30, pool.reserved("b"));
        assertEquals(0, pool.waitingCount());
    }

    @Test void accountCapSupplementsIpCap() {
        NodePools node = new NodePools(1000, NodePools.MIN_EXCESS, 100, 10, false);
        OutboundPool pool = new OutboundPool(node, 100, 20);
        assertTrue(pool.reserve("a", "ip-a", "account", 20));
        assertFalse(pool.reserve("b", "ip-b", "account", 1));
        assertEquals(20, pool.reserved("a"));
    }
}
