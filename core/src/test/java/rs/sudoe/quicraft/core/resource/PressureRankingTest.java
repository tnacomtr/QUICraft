// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Pressure ranking: decay, stall exclusion, progress windows, overflow buckets, eviction and floors (13.12). */
class PressureRankingTest {
    private static final long S = 1_000_000_000L;
    private static final byte[] SECRET = {1, 2, 3, 4, 5, 6, 7, 8};

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    /** Exact decayed byte-seconds of {@code bytes} held for {@code seconds} with the 60 s half-life (13.12). */
    private static double held(double bytes, double seconds) {
        return bytes * 60.0 / Math.log(2) * -Math.expm1(-Math.log(2) * seconds / 60.0);
    }

    @Test void longHeldModestBytesOutrankFreshLargerAllocation() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 100, 100, SECRET);
        r.connect("a", ip("192.0.2.1"), null, 0);
        r.setRetained("a", 100, 0);
        r.connect("b", ip("192.0.2.2"), null, 59 * S);
        r.setRetained("b", 1000, 59 * S);
        double longHeld = r.score("a", 60 * S);
        double fresh = r.score("b", 60 * S);
        assertEquals(held(100, 60), longHeld, 1e-6);
        assertEquals(held(1000, 1), fresh, 1e-6);
        assertTrue(longHeld > fresh);
    }

    @Test void oneUpdateAndManySmallUpdatesGiveTheSameDecayedScore() {
        IdentityHistory one = new IdentityHistory(60 * S, 0, 0);
        one.addBytes(100, 0, 0);
        double single = one.projected(600 * S, 0);

        IdentityHistory many = new IdentityHistory(60 * S, 0, 0);
        many.addBytes(100, 0, 0);
        for (long tick = 1; tick <= 6000; tick++) many.accrue(tick * S / 10, 0);
        double split = many.projected(600 * S, 0);

        assertEquals(single, split, single * 1e-9, "exact integration composes across update points");
        assertEquals(held(100, 600), single, 1e-6);
    }

    @Test void localExecutorStallIsExcludedFromByteSeconds() throws Exception {
        // The executor stall is global, so every holder on this ranking loses the stalled 10 seconds.
        PressureRanking stalled = new PressureRanking(60_000, 100, 100, SECRET);
        stalled.connect("holder", ip("192.0.2.10"), null, 0);
        stalled.setRetained("holder", 100, 0);
        stalled.stallStart(10 * S);
        stalled.stallEnd(20 * S);
        assertEquals(held(100, 20), stalled.score("holder", 30 * S), 1e-6);

        PressureRanking control = new PressureRanking(60_000, 100, 100, SECRET);
        control.connect("holder", ip("192.0.2.10"), null, 0);
        control.setRetained("holder", 100, 0);
        assertEquals(held(100, 30), control.score("holder", 30 * S), 1e-6);
    }

    @Test void progressWindowLowersScoreAndExpiresAfterTenSeconds() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 100, 100, SECRET);
        r.connect("p", ip("192.0.2.20"), null, 0);
        r.setRetained("p", 100, 0);
        r.progress("p", 1000, 10 * S);
        double withProgress = r.score("p", 10 * S);
        assertTrue(withProgress < 10.0, "progress rate 100 B/s divides the decayed byte-seconds: " + withProgress);
        assertEquals(held(100, 25), r.score("p", 25 * S), 1e-6);
    }

    @Test void scoreIsWorstOfConnectionLocalAndIdentityHistory() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 100, 100, SECRET);
        r.connect("old", ip("192.0.2.30"), null, 0);
        r.setRetained("old", 100, 0);
        r.setRetained("old", 0, 10 * S);
        r.disconnect("old", 10 * S);
        r.connect("new", ip("192.0.2.30"), null, 10 * S);
        double score = r.score("new", 10 * S);
        assertEquals(held(100, 10), score, 1e-6, "identity history persists across reconnects");
    }

    @Test void reconnectKeepsIdentityHistoryButNotConnectionLocalStatistics() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 100, 100, SECRET);
        r.connect("r1", ip("192.0.2.31"), null, 0);
        r.setRetained("r1", 100, 0);
        r.disconnect("r1", 10 * S);
        r.connect("r2", ip("192.0.2.31"), null, 20 * S);
        double score = r.score("r2", 20 * S);
        assertEquals(held(100, 10) * Math.pow(0.5, 10.0 / 60), score, 1e-6, "decayed identity history only");
    }

    @Test void overflowShareIsAnEqualPerIdentityShareOfTheBucket() {
        assertEquals(110.0, PressureRanking.overflowScore(10, 100, 1), 1e-9);
        assertEquals(35.0, PressureRanking.overflowScore(10, 100, 4), 1e-9);
        assertEquals(20.0, PressureRanking.overflowScore(10, 100, 100), 1e-9);
        assertEquals(100.0, PressureRanking.overflowScore(50, 100, 100), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> PressureRanking.overflowScore(0, 1, 0));
    }

    @Test void overflowIdentitiesUseKeyedSlash48BucketsAndChargeOnce() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 1, 10, SECRET);
        r.connect("h1", ip("2001:db8:1::1"), null, 0);
        r.connect("h2", ip("2001:db8:2::2"), null, 0);
        assertEquals(1, r.exactIpCount());
        int bucket = r.bucketFor(ip("2001:db8:2::2"));
        assertEquals(bucket, r.bucketFor(ip("2001:db8:2:ffff::9")));
        r.setRetained("h2", 100, 0);
        assertEquals(100, r.bucketBytes(bucket));
        r.connect("h3", ip("2001:db8:2:ffff::9"), null, 0);
        assertEquals(1, r.liveIdentities(bucket), "one /48 is one overflow identity");
        r.setRetained("h3", 50, 0);
        assertEquals(150, r.bucketBytes(bucket));
    }

    @Test void authenticatedOverflowChargesOnlyTheAccountBucket() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 1, 1, SECRET);
        r.connect("x", ip("192.0.2.1"), "alice", 0);
        r.connect("y", ip("192.0.2.2"), "bob", 0);
        r.setRetained("y", 100, 0);
        long total = 0;
        for (int b = 0; b < PrefixKey.OVERFLOW_BUCKETS; b++) total += r.bucketBytes(b);
        assertEquals(100, total, "IP and account overflow must not both be charged");
        assertEquals(100, r.bucketBytes(r.bucketFor("bob")));
    }

    @Test void exactEntryEvictionFoldsLowestInactiveHistoryIntoItsBucket() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 2, 10, SECRET);
        r.connect("c1", ip("192.0.2.40"), null, 0);
        r.setRetained("c1", 100, 0);
        r.disconnect("c1", 10 * S);
        r.connect("c2", ip("192.0.2.41"), null, 10 * S);
        r.connect("c3", ip("192.0.2.42"), null, 10 * S);
        assertEquals(2, r.exactIpCount());
        assertEquals(held(100, 10), r.bucketScore(r.bucketFor(ip("192.0.2.40")), 10 * S), 1e-6);
    }

    @Test void accountProtectedIpEntryIsNotEvictedWhileOtherInactiveEntryIs() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 2, 10, SECRET);
        int protectedBucket = r.bucketFor(ip("192.0.2.50"));
        int evictedBucket = r.bucketFor(ip("192.0.2.51"));
        assumeTrue(protectedBucket != evictedBucket);
        r.connect("c1", ip("192.0.2.50"), "carol", 0);
        r.setRetained("c1", 10, 0);
        r.disconnect("c1", 10 * S);
        r.connect("c2", ip("192.0.2.51"), null, 0);
        r.setRetained("c2", 100, 0);
        r.disconnect("c2", 10 * S);
        r.connect("c3", ip("192.0.2.52"), null, 10 * S);
        assertEquals(held(100, 10), r.bucketScore(evictedBucket, 10 * S), 1e-6);
        assertEquals(0.0, r.bucketScore(protectedBucket, 10 * S), 1e-9);
    }

    @Test void refusedWaitFloorLowersScoreOfAStalledRequester() throws Exception {
        PressureRanking plain = new PressureRanking(60_000, 100, 100, SECRET);
        PressureRanking floored = new PressureRanking(60_000, 100, 100, SECRET);
        for (PressureRanking r : new PressureRanking[] {plain, floored}) {
            r.connect("w", ip("192.0.2.60"), null, 0);
            r.setRetained("w", 100, 0);
            r.progress("w", 1000, 5 * S);
        }
        floored.beginRefusedWait("w", 10 * S);
        double withFloor = floored.score("w", 15 * S);
        double withoutFloor = plain.score("w", 15 * S);
        assertEquals(held(100, 15), withoutFloor, 1e-6);
        assertTrue(withFloor < withoutFloor / 50, "floor rate 100 B/s applies: " + withFloor);
        floored.endRefusedWait("w");
        assertEquals(held(100, 15), floored.score("w", 15 * S), 1e-6);
    }

    @Test void refusedWaitIsNotAnExcludedRetainedByteAccrual() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 100, 100, SECRET);
        r.connect("keeps-asking", ip("192.0.2.70"), null, 0);
        r.setRetained("keeps-asking", 100, 0);
        r.beginRefusedWait("keeps-asking", 5 * S);
        assertTrue(r.score("keeps-asking", 10 * S) > 0, "retained bytes accrue during a refused wait");
    }

    @Test void evictedIdentityReconnectsToItsBucketInsteadOfAFreshExactEntry() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 2, 10, SECRET);
        r.connect("c1", ip("192.0.2.90"), null, 0);
        r.setRetained("c1", 100, 0);
        r.disconnect("c1", 10 * S);
        r.connect("c2", ip("192.0.2.91"), null, 10 * S);
        r.connect("c3", ip("192.0.2.92"), null, 10 * S);
        assertEquals(2, r.exactIpCount());
        r.disconnect("c2", 10 * S);
        r.disconnect("c3", 10 * S);
        // Exact slots are full of inactive entries with no bytes; a reconnect must not take one (13.12 continuity).
        r.connect("c4", ip("192.0.2.90"), null, 11 * S);
        assertEquals(2, r.exactIpCount());
        assertEquals(held(100, 10) * Math.pow(0.5, 1.0 / 60), r.score("c4", 11 * S), 1e-6,
                "the evicted identity reads its folded history from its bucket");
    }

    @Test void overflowChurnDoesNotEraseAnEvictedIdentitysBucketContinuity() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 2, 10, SECRET);
        r.connect("c1", ip("192.0.2.90"), null, 0);
        r.setRetained("c1", 100, 0);
        r.disconnect("c1", 10 * S);
        r.connect("c2", ip("192.0.2.91"), null, 10 * S);
        r.connect("c3", ip("192.0.2.92"), null, 10 * S);
        // .90 is evicted into its bucket. Live exact holders keep the map full, so the next overflowing identities
        // push .90's continuity record out of the bounded memory.
        r.connect("c5", ip("192.0.2.93"), null, 10 * S);
        r.connect("c6", ip("192.0.2.94"), null, 10 * S);
        r.connect("c7", ip("192.0.2.95"), null, 10 * S);
        r.disconnect("c5", 10 * S);
        r.disconnect("c6", 10 * S);
        r.disconnect("c7", 10 * S);
        r.disconnect("c2", 10 * S);
        r.disconnect("c3", 10 * S);
        // Exact slots are free now, but the trimmed record must still send .90 to its bucket, not a fresh entry.
        r.connect("c4", ip("192.0.2.90"), null, 11 * S);
        double bucketScore = r.bucketScore(r.bucketFor(ip("192.0.2.90")), 11 * S);
        assertTrue(bucketScore > 0, "the evicted history is folded into its bucket");
        assertTrue(r.score("c4", 11 * S) >= bucketScore - 1e-9,
                "a reconnect after overflow churn inherits at least its live bucket score");
    }

    @Test void refusedWaitFloorDoesNotApplyToAConnectionYoungerThanTheWindow() throws Exception {
        PressureRanking plain = new PressureRanking(60_000, 100, 100, SECRET);
        PressureRanking floored = new PressureRanking(60_000, 100, 100, SECRET);
        for (PressureRanking r : new PressureRanking[] {plain, floored}) {
            r.connect("young", ip("192.0.2.100"), null, 0);
            r.setRetained("young", 100, 0);
            r.progress("young", 1000, 1 * S);
        }
        // The connection is only 2 s old at its first refusal: the floor is the live rate, which adds nothing.
        floored.beginRefusedWait("young", 2 * S);
        assertEquals(plain.score("young", 15 * S), floored.score("young", 15 * S), 1e-9);
    }

    @Test void refusedWaitFloorLiftsTheSiblingsSharingTheIdentity() throws Exception {
        PressureRanking plain = new PressureRanking(60_000, 100, 100, SECRET);
        PressureRanking floored = new PressureRanking(60_000, 100, 100, SECRET);
        for (PressureRanking r : new PressureRanking[] {plain, floored}) {
            r.connect("stalled", ip("192.0.2.110"), null, 0);
            r.setRetained("stalled", 100, 0);
            r.progress("stalled", 1000, 5 * S);
            r.connect("sibling", ip("192.0.2.110"), null, 0);
        }
        floored.beginRefusedWait("stalled", 10 * S);
        double siblingWithFloor = floored.score("sibling", 15 * S);
        double siblingWithout = plain.score("sibling", 15 * S);
        assertTrue(siblingWithFloor < siblingWithout / 50,
                "the stalled holder's floor lifts the shared IP identity: " + siblingWithFloor);
    }

    @Test void unknownConnectionsAreRejected() throws Exception {
        PressureRanking r = new PressureRanking();
        assertThrows(IllegalStateException.class, () -> r.setRetained("nobody", 1, 0));
        assertEquals(0.0, r.score("nobody", 0), 0.0);
        r.connect("one", ip("192.0.2.80"), null, 0);
        assertFalse(r.score("one", 0) < 0);
    }

    @Test void overflowConnectionLivingPastTheWindowKeepsItsBucketAcrossReconnect() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 1, 10, SECRET);
        r.connect("c1", ip("192.0.2.50"), null, 0);
        InetAddress guest = ip("192.0.2.51");
        r.connect("c2", guest, null, 0);
        int bucket = r.bucketFor(guest);
        // The overflow connection lives 700 s, longer than the 10-minute retention counted from admission.
        r.disconnect("c2", 700 * S);
        r.disconnect("c1", 700 * S);
        r.connect("c3", guest, null, 701 * S);
        r.setRetained("c3", 1000, 701 * S);
        assertEquals(1000, r.bucketBytes(bucket), "the reconnect is charged to its bucket, not a fresh exact entry");
    }

    @Test void liveIdleBothOverflowConnectionKeepsItsIpRecordSticky() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 1, 1, SECRET);
        r.connect("c1", ip("192.0.2.70"), "holder", 0);
        InetAddress guest = ip("192.0.2.71");
        // Both identities overflow; the connection lives on with an unchanged charge past the window.
        r.connect("c2", guest, "guest", 0);
        r.disconnect("c1", 601 * S);
        r.connect("c3", guest, null, 601 * S);
        r.setRetained("c3", 1000, 601 * S);
        assertEquals(1000, r.bucketBytes(r.bucketFor(guest)),
                "a sibling of a live overflowed identity stays in its bucket, it is not promoted");
    }

    /** An IPv4 address in the same overflow bucket as {@code bucket}, distinct from every address in {@code avoid}. */
    private static InetAddress sameBucket(PressureRanking r, int bucket, InetAddress... avoid) throws Exception {
        for (int i = 1; i < 65_536; i++) {
            InetAddress candidate = InetAddress.getByAddress(new byte[] {10, 9, (byte) (i >> 8), (byte) i});
            if (r.bucketFor(candidate) == bucket && !Arrays.asList(avoid).contains(candidate)) return candidate;
        }
        throw new AssertionError("no address maps to bucket " + bucket);
    }

    @Test void neverSeenNewcomerTakesAnExactEntryWhileAStickyBucketExists() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 1, 10, SECRET);
        r.connect("c1", ip("192.0.2.60"), null, 0);
        InetAddress first = ip("192.0.2.61");
        int bucket = r.bucketFor(first);
        InetAddress second = sameBucket(r, bucket, first);
        r.connect("c2", first, null, 0);
        r.connect("c3", second, null, 0);
        r.disconnect("c2", 0);
        r.disconnect("c1", 0);
        // The bucket is live through c3; the newcomer has no record, so it must not be routed by the bucket.
        InetAddress newcomer = sameBucket(r, bucket, first, second);
        r.connect("c4", newcomer, null, S);
        r.setRetained("c4", 1000, S);
        assertEquals(0, r.bucketBytes(bucket), "a never-seen identity does not read a sticky bucket");
        assertEquals(1, r.exactIpCount());
    }

    @Test void overflowedIdentityKeepsItsBucketWhileItsExactSlotIsReclaimable() throws Exception {
        PressureRanking r = new PressureRanking(60_000, 1, 10, SECRET);
        r.connect("c1", ip("192.0.2.70"), null, 0);
        InetAddress guest = ip("192.0.2.71");
        r.connect("c2", guest, null, 0);
        r.disconnect("c1", S);
        // The exact slot is inactive and reclaimable, but the guest's bucket is still live through c2.
        r.connect("c3", guest, null, 2 * S);
        r.setRetained("c3", 500, 2 * S);
        assertEquals(500, r.bucketBytes(r.bucketFor(guest)));
        assertEquals(1, r.exactIpCount(), "the overflowed guest does not take the reclaimable exact slot");
    }

    @Test void continuityTableNeverExceedsItsTrackingBounds() throws Exception {
        PressureRanking ipOnly = new PressureRanking(60_000, 3, 10, SECRET);
        ipOnly.connect("h1", ip("192.0.2.80"), null, 0);
        ipOnly.connect("h2", ip("192.0.2.81"), null, 0);
        ipOnly.connect("h3", ip("192.0.2.82"), null, 0);
        for (int i = 0; i < 100; i++) {
            InetAddress guest = InetAddress.getByAddress(new byte[] {10, 8, 0, (byte) i});
            ipOnly.connect("g" + i, guest, null, 0);
            ipOnly.disconnect("g" + i, 0);
            assertTrue(ipOnly.continuityEntries() <= 3, "IP continuity is bounded by maxTrackedIps");
        }
        PressureRanking accounts = new PressureRanking(60_000, 1000, 2, SECRET);
        accounts.connect("h1", ip("192.0.2.90"), "a1", 0);
        accounts.connect("h2", ip("192.0.2.91"), "a2", 0);
        for (int i = 0; i < 100; i++) {
            InetAddress guest = InetAddress.getByAddress(new byte[] {10, 8, 1, (byte) i});
            accounts.connect("g" + i, guest, "guest" + i, 0);
            accounts.disconnect("g" + i, 0);
            assertTrue(accounts.continuityEntries() <= 2, "account continuity is bounded by maxTrackedAccounts");
        }
    }
}
