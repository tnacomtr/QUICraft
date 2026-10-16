// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Staged /3 admission: promotion atomicity, prefix-fair shares and victim order (protocol.md 12). */
class PreLoginAdmissionTest {
    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    /** Node budget 1000 bytes; PRE_LOGIN is 10% = 100 bytes. */
    private static NodePools pools() {
        return new NodePools(1000, 0, 0, 10, false, 0);
    }

    @Test void promotionIsAtomicAndRefusedWhileNodeIsFull() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(1));
        assertTrue(admission.reserve("a", ip("2001:db8:1::1"), 60));
        assertTrue(pools.reserve(ResourcePools.Pool.NODE, "full", 1000));
        assertFalse(admission.promote("a", 60));
        assertEquals(60, admission.reserved("a"));
        assertEquals(60, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        pools.release(ResourcePools.Pool.NODE, "full", 1000);
        assertTrue(admission.promote("a", 60));
        assertEquals(0, admission.reserved("a"));
        assertEquals(0, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        assertEquals(60, pools.reserved(ResourcePools.Pool.NODE, "a"));
    }

    @Test void manyIpsInOneSlash48CannotStarveAnotherSlash48() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(2));
        for (int i = 0; i < 10; i++) {
            assertTrue(admission.reserve("att" + i, ip("2001:db8:1:" + i + "::1"), 10));
        }
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        assertTrue(admission.reserve("honest", ip("2001:db8:2::1"), 40));
        assertEquals(40, admission.reservedByPrefix(ip("2001:db8:2::99")));
        assertEquals(60, admission.reservedByPrefix(ip("2001:db8:1::99")));
        assertEquals(4, admission.drainShedOwners().size());
        assertTrue(pools.reserved(ResourcePools.Pool.PRE_LOGIN) <= 100);
    }

    @Test void overShareRequesterIsRefusedWithoutShedding() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(3));
        for (int i = 0; i < 10; i++) {
            assertTrue(admission.reserve("att" + i, ip("2001:db8:1:" + i + "::1"), 10));
        }
        assertFalse(admission.reserve("late", ip("2001:db8:1:ff::1"), 10));
        assertTrue(admission.drainShedOwners().isEmpty());
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test void leastProgressedOfEquallyOverShareConnectionsIsShedFirst() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(4));
        for (int i = 0; i < 10; i++) {
            assertTrue(admission.reserve("att" + i, ip("2001:db8:1:" + i + "::1"), 10));
        }
        admission.progress("att1", 50);
        assertTrue(admission.reserve("honest", ip("2001:db8:2::1"), 10));
        java.util.List<Object> shed = admission.drainShedOwners();
        assertEquals(1, shed.size());
        assertFalse(shed.contains("att1"), "most progressed connection must survive: " + shed);
    }

    @Test void releaseFreesPreLoginBytesAndSourcePrefix() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(5));
        assertTrue(admission.reserve("q", ip("192.0.2.1"), 30));
        assertTrue(admission.reserve("r", ip("192.0.2.200"), 20));
        assertEquals(50, admission.reservedByPrefix(ip("192.0.2.99")), "IPv4 shares are per /24");
        admission.release("q", 30);
        assertEquals(20, admission.reservedByPrefix(ip("192.0.2.99")));
        assertEquals(20, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test void ownerSourcePrefixCannotChange() throws Exception {
        PreLoginAdmission admission = new PreLoginAdmission(pools(), new Random(6));
        assertTrue(admission.reserve("q", ip("2001:db8:1::1"), 5));
        assertThrows(IllegalArgumentException.class, () -> admission.reserve("q", ip("2001:db8:9::1"), 5));
    }
}
