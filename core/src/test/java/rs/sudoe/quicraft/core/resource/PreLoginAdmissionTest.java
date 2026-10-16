// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;
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

    @Test void promotionIsAtomicAndKeepsTheSharedNodeBudget() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(1));
        assertTrue(admission.reserve("a", ip("2001:db8:1::1"), 60));
        // The pre-login charge is part of the node budget, so a full-budget reserve is refused while it is held.
        assertFalse(pools.reserve(ResourcePools.Pool.NODE, "full", 1000));
        assertTrue(pools.reserve(ResourcePools.Pool.NODE, "other", 940));
        assertEquals(60, admission.reserved("a"));
        assertEquals(60, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        assertTrue(admission.promote("a", 60));
        assertEquals(0, admission.reserved("a"));
        assertEquals(0, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        assertEquals(60, pools.reserved(ResourcePools.Pool.NODE, "a"));
        assertEquals(1000, pools.reserved(ResourcePools.Pool.NODE));
    }

    @Test void manyIpsInOneSlash48CannotStarveAnotherSlash48() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(2));
        for (int i = 0; i < 10; i++) {
            assertTrue(admission.reserve("att" + i, ip("2001:db8:1:" + i + "::1"), 10));
        }
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        // The honest requester waits: four victims (40 bytes) are pending shed but still charged.
        assertFalse(admission.reserve("honest", ip("2001:db8:2::1"), 40));
        List<Object> shed = admission.drainShedOwners();
        assertEquals(4, shed.size());
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN), "shed bytes stay charged until confirmed");
        assertEquals(100, admission.reservedByPrefix(ip("2001:db8:1::99")), "shed victims still count against their prefix");
        // Pending sheds already cover the deficit: a retry picks no further victim.
        assertFalse(admission.reserve("honest", ip("2001:db8:2::1"), 40));
        assertTrue(admission.drainShedOwners().isEmpty());
        for (Object owner : shed) admission.confirmClosed(owner);
        assertEquals(60, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        // The replacement is granted only once the confirmed bytes have left the pool.
        assertTrue(admission.reserve("honest", ip("2001:db8:2::1"), 40));
        assertEquals(40, admission.reservedByPrefix(ip("2001:db8:2::99")));
        assertEquals(60, admission.reservedByPrefix(ip("2001:db8:1::99")));
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test void shedVictimStaysChargedUntilConfirmedAndReplacementFollowsIt() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(8));
        for (int i = 0; i < 10; i++) {
            assertTrue(admission.reserve("att" + i, ip("2001:db8:1:" + i + "::1"), 10));
        }
        assertFalse(admission.reserve("honest", ip("2001:db8:2::1"), 10));
        List<Object> shed = admission.drainShedOwners();
        assertEquals(1, shed.size());
        Object victim = shed.get(0);
        // The old charge is still held: pool and victim both unchanged, the replacement is not granted.
        assertEquals(10, admission.reserved(victim));
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        assertFalse(admission.reserve("honest", ip("2001:db8:2::1"), 10));
        // The victim takes no new bytes while it is pending shed.
        int victimIndex = Integer.parseInt(victim.toString().substring("att".length()));
        assertFalse(admission.reserve(victim, ip("2001:db8:1:" + victimIndex + "::1"), 5));
        assertEquals(10, admission.reserved(victim));
        admission.confirmClosed(victim);
        assertEquals(0, admission.reserved(victim));
        assertEquals(90, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        assertTrue(admission.reserve("honest", ip("2001:db8:2::1"), 10));
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
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
        assertFalse(admission.reserve("honest", ip("2001:db8:2::1"), 10));
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

    @Test void fullSlash48CannotHideAnotherSlash64InsideItFromItsFairShare() throws Exception {
        NodePools pools = pools();
        PreLoginAdmission admission = new PreLoginAdmission(pools, new Random(7));
        for (int i = 1; i <= 6; i++) assertTrue(admission.reserve("x" + i, ip("2001:db8:1:1::" + i), 10));
        for (int i = 1; i <= 4; i++) assertTrue(admission.reserve("c" + i, ip("2001:db8:3:1::" + i), 10));
        assertEquals(100, pools.reserved(ResourcePools.Pool.PRE_LOGIN));
        // Max-min over /48s gives A 60 and C 40. Inside A, the new /64 gets its 20 by shedding over-share /64 bytes.
        assertFalse(admission.reserve("y", ip("2001:db8:1:2::1"), 20));
        List<Object> shed = admission.drainShedOwners();
        assertEquals(2, shed.size());
        for (Object owner : shed) admission.confirmClosed(owner);
        assertTrue(admission.reserve("y", ip("2001:db8:1:2::1"), 20));
        assertEquals(20, admission.reserved("y"));
        assertEquals(60, admission.reservedByPrefix(ip("2001:db8:1::9")), "X keeps 40, Y holds 20");
        assertEquals(40, admission.reservedByPrefix(ip("2001:db8:3::9")));
        assertTrue(pools.reserved(ResourcePools.Pool.PRE_LOGIN) <= 100);
    }
}
