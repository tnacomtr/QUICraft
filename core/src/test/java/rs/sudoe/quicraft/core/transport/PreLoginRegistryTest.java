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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.resource.ResourcePools;

/** docs/protocol.md 12.13: shedding order, fair share, session-call caps and promotion out of the set. */
class PreLoginRegistryTest {
    private static final long STAGED = PreLoginTracker.stagedShare(5);

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
        conn.entry = registry.admit(ip(address), conn, 5, online, System::nanoTime, () -> {
            conn.closed = true;
            shedLog.add(conn);
        });
        // The shed victims' bytes are confirmed released only when their close completes (12.13), so the newcomer
        // is refused until then. Confirm each victim's close in order and retry, as the listener does.
        for (int next = 0; conn.entry == null && next < shedLog.size(); next++) {
            Conn victim = shedLog.get(next);
            if (victim.entry != null) {
                victim.entry.close();
            }
            conn.entry = registry.admit(ip(address), conn, 5, online, System::nanoTime, () -> {
                conn.closed = true;
                shedLog.add(conn);
            });
        }
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

        assertTrue(registry.shedForLease(ip("198.51.100.1"), 5), "12.13: an idle staged victim frees a slot");
        assertTrue(idle.closed, "the victim is closed");
        assertEquals(0, registry.stagedCount());
    }

    @Test
    void aNewcomerWithNothingSheddableOrOverItsShareGetsNoSlot() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(3), 4, new Random(1));
        assertFalse(registry.shedForLease(ip("198.51.100.1"), 5), "nothing is staged, so nothing is sheddable");

        List<Conn> shed = new ArrayList<>();
        Conn a1 = admit(registry, "a1", "192.0.2.1", false, shed);
        Conn a2 = admit(registry, "a2", "192.0.2.2", false, shed);
        Conn b1 = admit(registry, "b1", "198.51.100.1", false, shed);
        // 192.0.2.x holds two of three shares, so a newcomer from it is over its fair share and is refused.
        assertFalse(registry.shedForLease(ip("192.0.2.3"), 5), "an over-share newcomer sheds no one");
        assertFalse(a1.closed || a2.closed || b1.closed, "no staged connection was shed");
        assertEquals(3, registry.stagedCount());
    }

    @Test
    void aWaitingChargeIsGrantedBeforeBusyPrefixesWhenRoomFrees() throws Exception {
        // 12.13 wait set: 50 busy prefixes each sit at their declared-length cap and fill the pool. An honest
        // prefix's charge waits, and the first freed bytes go to it at once, not to a later retry.
        long frame = 900_000 + 100;
        int busy = 50;
        long capacity = (busy + 1) * STAGED + busy * frame;
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, capacity);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4,
                rs.sudoe.quicraft.core.profile.ServerProfile.MIN_DECLARED_BYTES_PER_PREFIX, new Random(1));
        Conn honest = admit(registry, "honest", "203.0.113.1", true, new ArrayList<>());
        List<Conn> busyConns = new ArrayList<>();
        for (int i = 0; i < busy; i++) {
            busyConns.add(admit(registry, "busy" + i, "198.51." + i + ".1", true, new ArrayList<>()));
        }
        for (Conn c : busyConns) {
            assertEquals(PreLoginTracker.ChargeResult.CHARGED, c.entry.chargeDeclaredFrame(900_000, 100));
        }
        assertEquals(capacity, pools.held(ResourcePools.Pool.PRE_LOGIN), "the busy prefixes fill the pool");
        assertEquals(PreLoginTracker.ChargeResult.WAIT, honest.entry.chargeDeclaredFrame(900_000, 100));

        busyConns.get(0).entry.releaseExtra(frame);
        assertEquals(capacity, pools.held(ResourcePools.Pool.PRE_LOGIN),
                "the freed bytes were granted to the honest prefix on the release itself");
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, honest.entry.chargeDeclaredFrame(900_000, 100),
                "the honest charge is claimed without another pool reservation");
    }

    @Test
    void roomShedForANewcomerIsNotTakenByAWaiterFirst() throws Exception {
        // 12.13: bytes freed by shedding for a newcomer serve that newcomer; a waiting declared charge that fits
        // in them must not take them first and force a second shed.
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, 3 * STAGED + 100);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        // The waiter's charge (STAGED + 100) fits in one shed victim plus the 100-byte slack, but not in the slack.
        Conn quiet = admit(registry, "quiet", "203.0.113.1", true, shed);
        admit(registry, "busy1", "198.51.100.1", true, shed);
        admit(registry, "busy2", "198.51.100.2", true, shed);
        assertEquals(PreLoginTracker.ChargeResult.WAIT, quiet.entry.chargeDeclaredFrame(STAGED, 100));

        Conn newcomer = admit(registry, "newcomer", "192.0.2.1", true, shed);
        assertNotNull(newcomer.entry, "the newcomer is admitted");
        assertEquals(1, shed.size(), "one shed frees exactly the newcomer's room");
        assertEquals(PreLoginTracker.ChargeResult.WAIT, quiet.entry.chargeDeclaredFrame(STAGED, 100),
                "the pool is full again, so the waiter keeps waiting");
    }

    @Test
    void roomShedForANewcomersSlotIsNotTakenByAWaiterFirst() throws Exception {
        // 12.13: a lease shed keeps the victim's pool bytes for the newcomer's admit too.
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, 3 * STAGED + 100);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn quiet = admit(registry, "quiet", "203.0.113.1", true, shed);
        admit(registry, "busy1", "198.51.100.1", true, shed);
        admit(registry, "busy2", "198.51.100.2", true, shed);
        assertEquals(PreLoginTracker.ChargeResult.WAIT, quiet.entry.chargeDeclaredFrame(STAGED, 100));

        assertTrue(registry.shedForLease(ip("192.0.2.1"), 5));
        Conn newcomer = admit(registry, "newcomer", "192.0.2.1", true, shed);
        assertNotNull(newcomer.entry, "the newcomer is admitted");
        assertEquals(1, shed.size(), "one shed frees both the slot and the newcomer's room");
        assertEquals(PreLoginTracker.ChargeResult.WAIT, quiet.entry.chargeDeclaredFrame(STAGED, 100));
    }

    @Test
    void theWaitSetIsReRankedAfterEveryGrant() throws Exception {
        // 12.13: group A holds the least, but after one grant it holds more than group B, so B's waiter comes next.
        long want = STAGED + 100;
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, 8 * STAGED + 3 * want);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        List<Conn> groupA = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            groupA.add(admit(registry, "a" + i, "203.0.113." + (i + 1), true, shed));
        }
        List<Conn> groupB = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            groupB.add(admit(registry, "b" + i, "198.51.100." + (i + 1), true, shed));
        }
        Conn filler = admit(registry, "filler", "192.0.2.1", true, shed);
        assertTrue(filler.entry.chargeEarlyData(3 * want));
        assertEquals(8 * STAGED + 3 * want, pools.held(ResourcePools.Pool.PRE_LOGIN), "the pool is full");
        for (Conn c : groupA) {
            assertEquals(PreLoginTracker.ChargeResult.WAIT, c.entry.chargeDeclaredFrame(STAGED, 100));
        }
        assertEquals(PreLoginTracker.ChargeResult.WAIT, groupB.get(0).entry.chargeDeclaredFrame(STAGED, 100));

        filler.entry.releaseExtra(3 * want);
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, groupB.get(0).entry.chargeDeclaredFrame(STAGED, 100),
                "B is granted once A's first grant lifts A above it");
        int grantedA = 0;
        for (Conn c : groupA) {
            if (c.entry.chargeDeclaredFrame(STAGED, 100) == PreLoginTracker.ChargeResult.CHARGED) grantedA++;
        }
        assertEquals(2, grantedA);
    }

    @Test
    void aWaiterThatDoesNotFitDoesNotBlockASmallerLowerPriorityOne() throws Exception {
        // 12.13 wait set: the quiet prefix (higher priority) needs more than the freed room, so it is skipped
        // and the busy prefix's smaller charge, which fits, is granted in the same pass.
        long frame = 900_100;
        long early = 50_000;
        long room = 10_000;
        long capacity = 3 * STAGED + frame + early + room;
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, capacity);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        Conn quiet = admit(registry, "quiet", "203.0.113.1", true, new ArrayList<>());
        Conn busy = admit(registry, "busy", "198.51.100.1", true, new ArrayList<>());
        Conn filler = admit(registry, "filler", "192.0.2.1", true, new ArrayList<>());
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, filler.entry.chargeDeclaredFrame(900_000, 100));
        assertTrue(busy.entry.chargeEarlyData(early));
        assertEquals(PreLoginTracker.ChargeResult.WAIT, quiet.entry.chargeDeclaredFrame(900_000, 100));
        assertEquals(PreLoginTracker.ChargeResult.WAIT, busy.entry.chargeDeclaredFrame(29_000, 1_000),
                "the pool has only 10 000 bytes free");

        filler.entry.releaseExtra(20_000);
        assertEquals(capacity, pools.held(ResourcePools.Pool.PRE_LOGIN),
                "the 30 000 bytes freed go to the busy prefix; the quiet one does not fit and is skipped");
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, busy.entry.chargeDeclaredFrame(29_000, 1_000));
        assertEquals(PreLoginTracker.ChargeResult.WAIT, quiet.entry.chargeDeclaredFrame(900_000, 100),
                "the large waiter still waits for its own room");
    }

    @Test
    void anExpiredWaiterIsRemovedAndNeverGranted() throws Exception {
        // 12.13: an expired waiter leaves the wait set and its bytes are never granted by a later release.
        AtomicLong now = new AtomicLong();
        long capacity = 2 * STAGED + 950_000 + 10_000;
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, capacity);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        Conn filler = new Conn("filler");
        filler.entry = registry.admit(ip("192.0.2.1"), filler, 5, true, now::get, () -> filler.closed = true);
        Conn waiter = new Conn("waiter");
        waiter.entry = registry.admit(ip("198.51.100.1"), waiter, 5, true, now::get, () -> waiter.closed = true);

        assertTrue(filler.entry.chargeEarlyData(950_000));
        assertEquals(PreLoginTracker.ChargeResult.WAIT, waiter.entry.chargeDeclaredFrame(900_000, 100));
        now.set(PreLoginTracker.CLIENT_WAIT_NANOS);
        assertTrue(waiter.entry.expired());

        filler.entry.releaseExtra(950_000);
        assertEquals(2 * STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN),
                "the expired waiter was not granted the freed bytes");
        assertEquals(PreLoginTracker.ChargeResult.WAIT, waiter.entry.chargeDeclaredFrame(900_000, 100),
                "an expired connection is refused a declared-length charge");
        assertEquals(2 * STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    /**
     * 12.13: a newcomer that meets an unconfirmed shed is pending, not refused. It is woken only when the victim's
     * close is confirmed, and then it is admitted.
     */
    @Test
    void pendingNewcomerIsAdmittedAfterVictimCloseConfirms() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(1), 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn victim = admit(registry, "victim", "192.0.2.1", false, shed);
        List<String> woken = new ArrayList<>();

        PreLoginRegistry.Admission first = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false,
                System::nanoTime, () -> victim.closed = true, () -> woken.add("newcomer"));
        assertTrue(first.pending, "the victim's bytes are still charged, so the newcomer waits");
        assertNull(first.entry, "pending is not an admission");
        assertTrue(victim.closed, "the victim was asked to close");
        assertTrue(woken.isEmpty(), "the newcomer is not woken before the victim's close is confirmed");

        victim.entry.close();
        assertEquals(Arrays.asList("newcomer"), woken, "the confirmed close wakes the pending newcomer once");

        PreLoginRegistry.Admission second = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false,
                System::nanoTime, () -> { }, () -> woken.add("again"));
        assertFalse(second.pending, "after the confirmation the retry is decided");
        assertNotNull(second.entry, "the newcomer is admitted, not refused");
        assertEquals(Arrays.asList("newcomer"), woken, "an admitted newcomer registers no waiter");
    }

    /**
     * 12.13: an open-connection slot is not free while the victim that gives it up is still closing. The newcomer
     * waits, no second victim is shed meanwhile, and it is woken only when the victim's close is confirmed.
     */
    @Test
    void leaseIsNotReusedBeforeVictimCloseConfirms() throws Exception {
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(2), 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn victim = admit(registry, "victim", "192.0.2.1", false, shed);
        Conn other = admit(registry, "other", "192.0.2.2", false, shed);
        List<String> woken = new ArrayList<>();

        PreLoginRegistry.Admission lease = registry.shedForLease(ip("198.51.100.1"), 5, "lease", () -> woken.add("lease"));
        assertTrue(lease.pending, "the newcomer waits for the victim's lease to come back");
        assertTrue(victim.closed || other.closed, "one victim was shed");
        Conn shedOne = victim.closed ? victim : other;
        Conn kept = shedOne == victim ? other : victim;

        PreLoginRegistry.Admission again = registry.shedForLease(ip("198.51.100.1"), 5, "again", () -> woken.add("again"));
        assertTrue(again.pending, "a second newcomer also waits");
        assertFalse(kept.closed, "no second victim is shed while the first close is unconfirmed");
        assertTrue(woken.isEmpty(), "nothing is woken before the victim's close is confirmed");

        shedOne.entry.close();
        assertEquals(Arrays.asList("lease", "again"), woken, "the confirmed close wakes both waiters");
    }

    /**
     * 12.13: the bytes a confirmed shed frees belong to its pending newcomer until it settles. A declared retry or a
     * release before the newcomer's retry must not hand them to a waiting declared charge, and the newcomer is then
     * admitted without a second shed.
     */
    @Test
    void declaredWaiterDoesNotTakeBytesOwedToAPendingNewcomer() throws Exception {
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, STAGED * 2 + 2048);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn victim = admit(registry, "victim", "192.0.2.1", false, shed);
        Conn other = admit(registry, "other", "192.0.2.1", false, shed);
        other.entry.recordProgress(1);
        // Declared charge of one staged share plus 1 KiB: 2 KiB is free now, so it waits.
        long declared = STAGED + 1024;
        assertEquals(PreLoginTracker.ChargeResult.WAIT, other.entry.chargeDeclaredFrame(declared, 0));
        assertEquals(2 * STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN));

        List<String> woken = new ArrayList<>();
        PreLoginRegistry.Admission first = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false,
                System::nanoTime, () -> { }, () -> woken.add("newcomer"));
        assertTrue(first.pending, "the newcomer waits for the victim's close");
        assertTrue(victim.closed, "the least-progressed victim was shed");

        victim.entry.close();
        assertEquals(Arrays.asList("newcomer"), woken, "the confirmed close wakes the newcomer");
        assertEquals(STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN), "the victim's bytes are free");

        assertEquals(PreLoginTracker.ChargeResult.WAIT, other.entry.chargeDeclaredFrame(declared, 0),
                "a declared retry is not granted the bytes owed to the newcomer");
        other.entry.releaseExtra(0);
        assertEquals(STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN), "no declared bytes were granted");

        PreLoginRegistry.Admission second = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false,
                System::nanoTime, () -> { }, () -> woken.add("again"));
        assertNotNull(second.entry, "the newcomer is admitted on its retry");
        assertFalse(second.pending);
        assertFalse(other.closed, "no second victim is shed for the newcomer");
        assertEquals(Arrays.asList("newcomer"), woken, "an admitted newcomer registers no waiter");
        assertEquals(2 * STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    /** 12.13: with no declared waiter queued, a fresh declared charge does not take bytes owed to a newcomer either. */
    @Test
    void aFreshDeclaredChargeDoesNotTakeBytesOwedToAPendingNewcomer() throws Exception {
        PreLoginTrackerTest.FakePools pools = new PreLoginTrackerTest.FakePools()
                .capacity(ResourcePools.Pool.PRE_LOGIN, STAGED * 2 + 2048);
        PreLoginRegistry registry = new PreLoginRegistry(pools, 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn victim = admit(registry, "victim", "192.0.2.1", false, shed);
        Conn other = admit(registry, "other", "192.0.2.1", false, shed);
        other.entry.recordProgress(1);

        List<String> woken = new ArrayList<>();
        PreLoginRegistry.Admission first = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false,
                System::nanoTime, () -> { }, () -> woken.add("newcomer"));
        assertTrue(first.pending, "the newcomer waits for the victim's close");
        assertTrue(victim.closed, "the least-progressed victim was shed");
        victim.entry.close();
        assertEquals(STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN), "the victim's bytes are free");

        assertEquals(PreLoginTracker.ChargeResult.WAIT, other.entry.chargeDeclaredFrame(STAGED + 1024, 0),
                "a fresh declared charge is not granted the bytes owed to the newcomer");
        assertEquals(STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN), "no declared bytes were granted");

        PreLoginRegistry.Admission second = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false,
                System::nanoTime, () -> { }, () -> woken.add("again"));
        assertNotNull(second.entry, "the newcomer is admitted on its retry");
        assertFalse(other.closed, "no second victim is shed for the newcomer");
        assertEquals(2 * STAGED, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    /**
     * 12.13: a newcomer woken by a shed victim's confirmed close finds the victim's open-connection lease already
     * free, so it does not shed a second victim.
     */
    @Test
    void aWokenNewcomerFindsTheVictimsLeaseFree() throws Exception {
        ConnectionAdmission admission = new ConnectionAdmission(1000, 1000, 1, 1, 1000, 100);
        ConnectionAdmission.Lease victimLease = admission.tryAcquire(ip("192.0.2.1"));
        assertNotNull(victimLease, "the victim holds the only open-connection slot");
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(1), 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn victim = admit(registry, "victim", "192.0.2.1", false, shed);
        List<Integer> openWhenWoken = new ArrayList<>();
        PreLoginRegistry.Admission lease = registry.shedForLease(ip("198.51.100.1"), 5, "newcomer",
                () -> openWhenWoken.add(admission.openConnections()));
        assertTrue(lease.pending, "the newcomer waits for the slot");
        assertTrue(victim.closed, "the victim was shed");

        QuicServer.releaseCharges(victimLease, victim.entry);

        assertEquals(Arrays.asList(0), openWhenWoken, "the slot is free when the newcomer runs");
    }

    /** 12.13: a newcomer still pending at its 30 s hard cap is refused on its next retry; the cap is never extended. */
    @Test
    void pendingNewcomerClosesAtItsDeadline() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(1), 4, new Random(1));
        List<Conn> shed = new ArrayList<>();
        Conn victim = admit(registry, "victim", "192.0.2.1", false, shed);
        long accepted = now.get();
        PreLoginRegistry.Admission first = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false, now::get,
                accepted, () -> { }, () -> { });
        assertTrue(first.pending, "the newcomer waits for the victim's close");

        now.set(accepted + PreLoginTracker.HARD_CAP_NANOS - 1_000_000_000L);
        victim.entry.close();
        now.set(accepted + PreLoginTracker.HARD_CAP_NANOS);
        PreLoginRegistry.Admission retry = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false, now::get,
                accepted, () -> { }, () -> { });
        assertNull(retry.entry, "an expired newcomer is refused, not admitted");
        assertFalse(retry.pending, "an expired newcomer is refused, not kept pending");
    }

    /**
     * 12.13: a grant near the deadline keeps the original 30 s cap. The admitted connection expires at the original
     * deadline, not 30 s after its grant.
     */
    @Test
    void aGrantNearTheDeadlineDoesNotExtendTheCap() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PreLoginRegistry registry = new PreLoginRegistry(poolFor(1), 4, new Random(1));
        long accepted = now.get();
        // Granted at 9 s, inside the 10 s client-wait deadline. Its platform wait then pauses the client-wait clock,
        // so only the 30 s hard cap (from the first acceptance, not from the grant) can expire it.
        now.set(accepted + 9_000_000_000L);
        PreLoginRegistry.Admission grant = registry.attempt(ip("198.51.100.1"), "newcomer", 5, false, now::get,
                accepted, () -> { }, () -> { });
        assertNotNull(grant.entry, "the newcomer is granted at 9 s");
        assertFalse(grant.entry.expired(), "at 9 s it is alive");
        grant.entry.pauseClientWait();
        now.set(accepted + PreLoginTracker.HARD_CAP_NANOS - 1_000_000L);
        assertFalse(grant.entry.expired(), "just before 30 s from the first acceptance it is alive");
        now.set(accepted + PreLoginTracker.HARD_CAP_NANOS);
        assertTrue(grant.entry.expired(), "the cap is 30 s from the first acceptance, not from the grant");
    }
}
