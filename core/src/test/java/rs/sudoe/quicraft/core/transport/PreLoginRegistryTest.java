// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.resource.ResourcePools;

/** docs/protocol.md 12.13: shedding order, fair share, session-call caps and promotion out of the set. */
class PreLoginRegistryTest {
    private static final long STAGED = PreLoginTracker.stagedShare(6);

    /** Pool with room for exactly {@code connections} staged connections. */
    private static PreLoginTrackerTest.FakePools poolFor(int connections) {
        return new PreLoginTrackerTest.FakePools().capacity(ResourcePools.Pool.PRE_LOGIN, STAGED * connections);
    }

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    /** One admitted connection plus the record of whether it was closed by the registry. */
    private static final class Conn {
        final String name;
        PreLoginRegistry.Entry entry;
        boolean closed;

        Conn(String name) {
            this.name = name;
        }
    }

    private static Conn admit(PreLoginRegistry registry, String name, String address, boolean online,
            List<Conn> shedLog) throws Exception {
        Conn conn = new Conn(name);
        conn.entry = registry.admit(ip(address), conn, 6, online, System::nanoTime, () -> {
            conn.closed = true;
            shedLog.add(conn);
        });
        return conn;
    }

    @Test
    void aFreeRoomAdmitsWithoutShedding() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolFor(2);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        assertNotNull(admit(registry, "a", "192.0.2.1", true, shed).entry);
        assertNotNull(admit(registry, "b", "192.0.2.2", true, shed).entry);
        assertEquals(2, registry.stagedCount());
        assertTrue(shed.isEmpty());
    }

    @Test
    void aNewPrefixShedsTheLeastProgressedConnectionOfTheOverSharePrefix() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolFor(3);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn attackerSlow = admit(registry, "attacker-slow", "192.0.2.10", true, shed);
        Conn attackerFast = admit(registry, "attacker-fast", "192.0.2.11", true, shed);
        Conn attackerThird = admit(registry, "attacker-third", "192.0.2.12", true, shed);
        attackerSlow.entry.recordProgress(10);
        attackerFast.entry.recordProgress(500);
        attackerThird.entry.recordProgress(500);

        Conn honest = admit(registry, "honest", "198.51.100.5", true, shed);

        assertNotNull(honest.entry, "the honest prefix is admitted");
        assertEquals(Arrays.asList(attackerSlow), shed, "the least-progressed attacker is shed");
        assertFalse(attackerFast.closed);
        assertEquals(STAGED * 3, pools.held(ResourcePools.Pool.PRE_LOGIN),
                "the victim's charge went back and the honest one replaced it");
    }

    @Test
    void aNewConnectionFromAPrefixOverItsShareIsRefusedInsteadOfShedding() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolFor(3);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn a = admit(registry, "a", "192.0.2.10", true, shed);
        Conn b = admit(registry, "b", "192.0.2.11", true, shed);
        Conn other = admit(registry, "other", "203.0.113.7", true, shed);
        assertNotNull(a.entry);
        assertNotNull(b.entry);
        assertNotNull(other.entry);

        // Two prefixes share the pool equally (a share of 1.5 connections each); 192.0.2.0/24 already
        // holds two, so a third from it is over its share and is refused.
        Conn c = admit(registry, "c", "192.0.2.12", true, shed);

        assertNull(c.entry);
        assertTrue(shed.isEmpty(), "no other prefix's connection is shed for it");
    }

    @Test
    void aWaitingConnectionIsNotShedWhileItsPrefixIsWithinItsShare() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolFor(2);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn waiting = admit(registry, "waiting", "192.0.2.10", true, shed);
        Conn other = admit(registry, "other", "203.0.113.7", true, shed);
        waiting.entry.pauseClientWait(); // awaiting the session server
        other.entry.recordProgress(1);

        // Both prefixes hold one connection (equal share); the waiting one is protected.
        Conn honest = admit(registry, "honest", "198.51.100.5", true, shed);

        assertNotNull(honest.entry);
        assertEquals(Arrays.asList(other), shed, "the unwaiting connection is the victim");
        assertFalse(waiting.closed);
    }

    @Test
    void aWaitingConnectionIsShedOnceItsPrefixIsOverItsShare() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolFor(4);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        // 192.0.2.0/24 holds three waiting connections, 203.0.113.0/24 one unwaiting: the first prefix is
        // over its share of the four slots and loses protection.
        Conn w1 = admit(registry, "w1", "192.0.2.10", true, shed);
        Conn w2 = admit(registry, "w2", "192.0.2.11", true, shed);
        Conn w3 = admit(registry, "w3", "192.0.2.12", true, shed);
        Conn calm = admit(registry, "calm", "203.0.113.7", true, shed);
        for (Conn c : Arrays.asList(w1, w2, w3)) {
            c.entry.pauseClientWait();
        }

        Conn honest = admit(registry, "honest", "198.51.100.5", true, shed);

        assertNotNull(honest.entry);
        assertEquals(1, shed.size());
        assertTrue(Arrays.asList(w1, w2, w3).contains(shed.get(0)), "a waiting connection of the over-share prefix");
        assertFalse(calm.closed);
    }

    @Test
    void theHierarchyPicksTheWorstSlash48BeforeTheConnectionDetails() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolFor(4);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        // Two IPv6 /64s inside one /48 hold three connections; a lone /48 holds one.
        Conn inA = admit(registry, "inA", "2001:db8:1:1::1", true, shed);
        Conn inB = admit(registry, "inB", "2001:db8:1:2::1", true, shed);
        Conn inB2 = admit(registry, "inB2", "2001:db8:1:2::2", true, shed);
        Conn lone = admit(registry, "lone", "2001:db9:1::1", true, shed);
        inA.entry.recordProgress(1000);
        inB.entry.recordProgress(1000);
        inB2.entry.recordProgress(1000);
        lone.entry.recordProgress(1);

        Conn honest = admit(registry, "honest", "2600:1::1", true, shed);

        assertNotNull(honest.entry);
        assertEquals(1, shed.size());
        assertTrue(shed.get(0) == inB || shed.get(0) == inB2,
                "the victim is in the /64 with the most bytes inside the /48 with the most bytes");
        assertFalse(lone.closed);
        assertFalse(inA.closed);
    }

    @Test
    void sessionServerCallsAreCappedPerCoarsePrefix() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(1), 2, new Random(1));
        InetAddress a = ip("2001:db8:1:1::1");
        InetAddress sameSlash48 = ip("2001:db8:1:9::1");
        InetAddress other = ip("2001:db9::1");
        assertTrue(registry.tryBeginSessionCall(a));
        assertTrue(registry.tryBeginSessionCall(a));
        assertFalse(registry.tryBeginSessionCall(sameSlash48), "one /48 shares its cap across /64s");
        assertTrue(registry.tryBeginSessionCall(other), "another /48 has its own cap");
        registry.endSessionCall(a);
        assertTrue(registry.tryBeginSessionCall(sameSlash48), "a finished call frees one slot");
    }

    @Test
    void aPromotedConnectionLeavesTheStagedSetAndIsNeverShed() throws Exception {
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, STAGED);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn promoted = admit(registry, "p", "192.0.2.10", true, shed);
        promoted.entry.onLoginSuccess();
        assertEquals(0, registry.stagedCount());
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN), "promotion released the staged charge");

        assertNotNull(admit(registry, "h", "198.51.100.5", true, shed).entry);
        assertTrue(shed.isEmpty(), "a promoted connection is never shed");
        assertFalse(promoted.closed);
    }

    @Test
    void closingAStagedConnectionFreesItsRoomForTheNextOne() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolFor(1);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn first = admit(registry, "first", "192.0.2.10", true, shed);
        first.entry.close();
        assertEquals(0, registry.stagedCount());
        assertNotNull(admit(registry, "second", "192.0.2.10", true, shed).entry);
    }

    @Test
    void anOfflineConnectionStaysStagedThroughLoginSuccess() throws Exception {
        PreLoginTrackerTest.FakePools pools = poolFor(2);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        Conn offline = admit(registry, "offline", "192.0.2.10", false, new ArrayList<>());
        offline.entry.onLoginSuccess();
        assertEquals(1, registry.stagedCount());
        offline.entry.onActivation();
        assertEquals(0, registry.stagedCount());
        assertFalse(offline.closed);
    }

    @Test
    void aRefusedPromotionStaysStagedAndASecondTryPromotesAfterNodeFrees() throws Exception {
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.NODE, 1000);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        Conn conn = admit(registry, "c", "192.0.2.10", true, new ArrayList<>());
        conn.entry.onLoginSuccess();
        assertEquals(1, registry.stagedCount(), "still staged: the node share did not fit");
        assertFalse(conn.entry.retryPromotion());
        pools.capacity(ResourcePools.Pool.NODE, TransportConfig.V3_INITIAL_EXPOSURE_BYTES);
        assertTrue(conn.entry.retryPromotion());
        assertEquals(0, registry.stagedCount(), "promotion leaves the staged set");
    }

    @Test
    void theChargeAndSessionSurfaceRefusesAPromotedConnection() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(2), 4, new Random(1));
        Conn conn = admit(registry, "c", "192.0.2.10", true, new ArrayList<>());
        assertTrue(conn.entry.tryBeginSessionCall());
        conn.entry.onLoginSuccess();
        assertFalse(conn.entry.tryBeginSessionCall(), "no session calls once promoted");
        assertFalse(conn.entry.chargeEarlyData(1));
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, conn.entry.chargeDeclaredFrame(10, 0),
                "a frame within the staged share needs no charge");
    }

    @Test
    void aSessionCallPerConnectionIsCappedAndClosingReleasesTheOnesItHeld() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(2), 2, new Random(1));
        Conn a = admit(registry, "a", "2001:db8:1:1::1", true, new ArrayList<>());
        Conn b = admit(registry, "b", "2001:db8:1:2::1", true, new ArrayList<>());
        assertTrue(a.entry.tryBeginSessionCall());
        assertTrue(a.entry.tryBeginSessionCall());
        assertFalse(b.entry.tryBeginSessionCall(), "the /48 cap of two is shared by both connections");
        a.entry.close();
        assertTrue(b.entry.tryBeginSessionCall(), "closing a releases the calls it still held");
    }

    @Test
    void theRegistryRefusesAChargeWhenThePoolIsFullAndWaitsNonFatally() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(1), 4, new Random(1));
        Conn conn = admit(registry, "c", "192.0.2.10", true, new ArrayList<>());
        assertEquals(PreLoginTracker.ChargeResult.WAIT, conn.entry.chargeDeclaredFrame(900_000, 0));
    }

    @Test
    void aDeclaredFrameCapPerPrefixWaitsWhenItIsHitAndReleasedFramesFreeIt() throws Exception {
        // A large pool so only the per-prefix declared-length cap (2 MiB) can refuse.
        PreLoginRegistry registry = new PreLoginRegistry(
                new PreLoginTrackerTest.FakePools().capacity(ResourcePools.Pool.PRE_LOGIN, 64L << 20), 4, new Random(1));
        Conn a = admit(registry, "a", "192.0.2.10", true, new ArrayList<>());
        PreLoginConnection view = a.entry;
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, view.chargeDeclaredFrame(900_000, 100));
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, view.chargeDeclaredFrame(900_000, 100));
        assertEquals(PreLoginTracker.ChargeResult.WAIT, view.chargeDeclaredFrame(900_000, 100),
                "the prefix's declared-length cap is hit: wait, never shed");

        Conn other = admit(registry, "other", "192.0.3.10", true, new ArrayList<>());
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, other.entry.chargeDeclaredFrame(900_000, 100),
                "the cap is per prefix, so another prefix still charges");

        // Once the other prefix leaves, the fair share is the whole pool, so only the cap can refuse.
        other.entry.close();
        view.releaseExtra(900_100);
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, view.chargeDeclaredFrame(900_000, 100),
                "a completed frame frees its declared-length bytes");
    }

    @Test
    void theDeclaredBytesCapIsTheProfileValueNotAConstant() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(
                new PreLoginTrackerTest.FakePools().capacity(ResourcePools.Pool.PRE_LOGIN, 64L << 20), 4,
                rs.sudoe.quicraft.core.profile.ServerProfile.MIN_DECLARED_BYTES_PER_PREFIX, new Random(1));
        Conn a = admit(registry, "a", "192.0.2.10", true, new ArrayList<>());
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, a.entry.chargeDeclaredFrame(900_000, 100));
        assertEquals(PreLoginTracker.ChargeResult.WAIT, a.entry.chargeDeclaredFrame(900_000, 100),
                "the profile's cap holds one maximum-size frame, so a second one waits");
    }

    @Test
    void twoEquallyChargedPrefixesBothGetDeclaredExtrasFromAnEmptyPool() throws Exception {
        // 12.13: with room in the pool, equal prefixes must both proceed; a share test that charges the extra
        // before comparing made every positive extra exceed the share, so both waited until the deadline.
        PreLoginRegistry registry = new PreLoginRegistry(
                new PreLoginTrackerTest.FakePools().capacity(ResourcePools.Pool.PRE_LOGIN, 64L << 20), 4, new Random(1));
        Conn a = admit(registry, "a", "192.0.2.10", true, new ArrayList<>());
        Conn b = admit(registry, "b", "198.51.100.10", true, new ArrayList<>());

        assertEquals(PreLoginTracker.ChargeResult.CHARGED, a.entry.chargeDeclaredFrame(900_000, 100));
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, b.entry.chargeDeclaredFrame(900_000, 100),
                "the second equal prefix proceeds while the pool has room");
    }

    @Test
    void aHogPrefixIsHeldBackOnlyWhenThePoolCannotTakeItsDeclaredExtra() throws Exception {
        // The pool holds the three staged shares plus 500 000 bytes: room for the honest prefix's extra, not for
        // the hog's 900 100-byte frame. The hog (two connections, over its share) waits; the honest one proceeds.
        long capacity = 3 * STAGED + 500_000;
        PreLoginRegistry registry = new PreLoginRegistry(
                new PreLoginTrackerTest.FakePools().capacity(ResourcePools.Pool.PRE_LOGIN, capacity), 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn hog1 = admit(registry, "h1", "192.0.2.10", true, shed);
        admit(registry, "h2", "192.0.2.11", true, shed);
        Conn honest = admit(registry, "honest", "198.51.100.5", true, shed);

        assertEquals(PreLoginTracker.ChargeResult.WAIT, hog1.entry.chargeDeclaredFrame(900_000, 100),
                "the pool cannot take the hog's extra: the over-share prefix waits");
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, honest.entry.chargeDeclaredFrame(400_000, 0),
                "the pool has room for the honest prefix's extra: it proceeds");
        assertTrue(shed.isEmpty(), "waiting for a declared frame never sheds another connection");
        assertEquals(3, registry.stagedCount());
    }

    @Test
    void progressRecordedThroughThePreLoginConnectionViewPicksTheShedVictim() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(3), 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn slow = admit(registry, "slow", "192.0.2.10", true, shed);
        Conn fast = admit(registry, "fast", "192.0.2.11", true, shed);
        Conn third = admit(registry, "third", "192.0.2.12", true, shed);
        PreLoginConnection slowView = slow.entry;
        PreLoginConnection fastView = fast.entry;
        PreLoginConnection thirdView = third.entry;
        slowView.recordProgress(10);
        fastView.recordProgress(500);
        thirdView.recordProgress(500);

        admit(registry, "honest", "198.51.100.5", true, shed);
        assertEquals(1, shed.size());
        assertTrue(slow.closed, "the least-progressed connection of the over-share prefix is shed");
        assertFalse(fast.closed);
    }

    @Test
    void aValidatedNewcomerShedsAnIdleStagedVictimForAnOpenSlot() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(2), 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn idle = admit(registry, "idle", "192.0.2.1", false, shed);

        assertTrue(registry.shedForLease(ip("198.51.100.1"), 6), "12.13: an idle staged victim frees a slot");
        assertTrue(idle.closed, "the victim is closed");
        assertEquals(0, registry.stagedCount());
    }

    @Test
    void aNewcomerWithNothingSheddableOrOverItsShareGetsNoSlot() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(3), 4, new Random(1));
        assertFalse(registry.shedForLease(ip("198.51.100.1"), 6), "nothing is staged, so nothing is sheddable");

        List<Conn> shed = new ArrayList<>();
        Conn a1 = admit(registry, "a1", "192.0.2.1", false, shed);
        Conn a2 = admit(registry, "a2", "192.0.2.2", false, shed);
        Conn b1 = admit(registry, "b1", "198.51.100.1", false, shed);
        // 192.0.2.x holds two of three shares, so a newcomer from it is over its fair share and is refused.
        assertFalse(registry.shedForLease(ip("192.0.2.3"), 6), "an over-share newcomer sheds no one");
        assertFalse(a1.closed || a2.closed || b1.closed, "no staged connection was shed");
        assertEquals(3, registry.stagedCount());
    }
}
