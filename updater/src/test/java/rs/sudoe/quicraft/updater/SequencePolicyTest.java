// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.updater.SequencePolicy.AcceptResult;
import rs.sudoe.quicraft.updater.SequencePolicy.ManifestDecision;
import rs.sudoe.quicraft.updater.SequencePolicy.SelectionResult;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.KidSeq;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;
import rs.sudoe.quicraft.updater.UpdateDocuments.ReleaseManifest;
import rs.sudoe.quicraft.updater.UpdateDocuments.Revocations;

/**
 * Tests for {@link SequencePolicy} (docs/updater.md 12.4, 12.5, 12.6, 12.12).
 *
 * <p>Documents are signed only with the throwaway RFC 8032 TEST 1, TEST 2 and TEST 3 seeds and
 * verified through {@link AuthenticatedDocuments}, so every {@link VerifiedDocument} is real
 * evidence. A few tests build a {@link VerifiedDocument} directly to model evidence whose claimed
 * signer roles disagree with the current trust.
 */
class SequencePolicyTest {

    /** RFC 8032 section 7.1 TEST 3 seed (throwaway, published). Test sources only. */
    private static final String TEST3_SEED =
            "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7";
    /** RFC 8032 section 7.1 TEST 3 public key. */
    private static final String TEST3_PUB =
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
    private static final String TEST3_KID = "dac073e0123bdea5";

    private static final String FP = "ab".repeat(32);
    private static final String SHA_X = "ab".repeat(32);
    private static final String SHA_Y = "cd".repeat(32);
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final Instant ISSUED = Instant.parse("2026-11-01T12:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-12-01T12:00:00Z");
    private static final String ISSUED_TEXT = "2026-11-01T12:00:00Z";
    private static final String EXPIRES_TEXT = "2026-12-01T12:00:00Z";

    /** Trust file floors used by the policy: both zero unless a test says otherwise. */
    private static final UpdateDocuments.Trust TRUST =
            new UpdateDocuments.Trust(1, ISSUED, 0, 0, List.of());

    private static final CurrentRevocations SEED = new CurrentRevocations(0, "", null, null, 0);

    private static final String REVOKED_TEST3 =
            "{\"kid\":\"" + TEST3_KID + "\",\"pub\":\"" + TEST3_PUB + "\"}";

    /** A signing key: kid and seed. */
    private record Key(String kid, String seed) {
    }

    private static final Key K1 = new Key(TestKeys.KID_1, TestKeys.SEED_1);
    private static final Key K2 = new Key(TestKeys.KID_2, TestKeys.SEED_2);
    private static final Key K3 = new Key(TEST3_KID, TEST3_SEED);

    @Test
    void thirdKeyFixtureIsDerivedFromTheRfcSeed() throws Exception {
        assertEquals(TEST3_KID, Hex.kid(TEST3_PUB));
        VerifiedDocument<ReleaseManifest> doc = release(trust3(), releaseBytes(1, "a", ""), K3);
        assertEquals(1L, doc.document().seq());
    }

    @Test
    void floorIgnoresRevokedKidAndCountsKidAbsentFromTrust() throws Exception {
        Map<String, KidSeq> seqByKid = Map.of(
                TestKeys.KID_1, kidSeq(10),
                TEST3_KID, kidSeq(7),
                TestKeys.KID_2, kidSeq(5));
        RevokedState revoked = new RevokedState(
                List.of(new UpdateDocuments.RevokedKey(TestKeys.KID_1, TestKeys.PUB_1)), List.of());
        Snapshot s = new Snapshot(state(SEED, seqByKid, List.of()), revoked);
        UpdateDocuments.Trust trust = new UpdateDocuments.Trust(1, ISSUED, 0, 2, List.of());
        assertEquals(7L, SequencePolicy.manifestFloor(s, trust));
    }

    @Test
    void floorIncludesTrustAndRevocationMinimums() throws Exception {
        CurrentRevocations rev = new CurrentRevocations(3, SHA_X, ISSUED, EXPIRES, 9);
        Snapshot s = new Snapshot(state(rev, Map.of(), List.of()), RevokedState.empty());
        UpdateDocuments.Trust trust = new UpdateDocuments.Trust(1, ISSUED, 0, 4, List.of());
        assertEquals(9L, SequencePolicy.manifestFloor(s, trust));
    }

    @Test
    void sameSeqWithDifferentBytesIsEquivocationAndIdenticalBytesReplay() throws Exception {
        byte[] docA = releaseBytes(42, "a", "");
        byte[] docB = releaseBytes(42, "b", "");
        Map<String, KidSeq> seqByKid = Map.of(
                TestKeys.KID_2, new KidSeq(42, Hex.sha256(docA), ISSUED));
        Snapshot s = new Snapshot(state(SEED, seqByKid, List.of()), RevokedState.empty());
        assertEquals(ManifestDecision.REPLAY_SAME,
                SequencePolicy.manifestDecision(release(trust3(), docA, K2), s, TRUST, trust3(), NOW, NOW));
        assertEquals(ManifestDecision.EQUIVOCATION,
                SequencePolicy.manifestDecision(release(trust3(), docB, K2), s, TRUST, trust3(), NOW, NOW));
    }

    @Test
    void replayOfSameManifestIsAcceptedWithoutChangingSequence() throws Exception {
        byte[] doc = releaseBytes(42, "a", "");
        Map<String, KidSeq> seqByKid = Map.of(TestKeys.KID_2, new KidSeq(42, Hex.sha256(doc), ISSUED));
        Snapshot s = new Snapshot(state(SEED, seqByKid, List.of()), RevokedState.empty());
        AcceptResult result = SequencePolicy.acceptManifest(
                s, release(trust3(), doc, K2), TRUST, trust3(), "fabric-26x", version("0.4.0"), NOW, NOW);
        assertFalse(result.stateFull());
        assertEquals(42L, result.delta().newState().seqByKid().get(TestKeys.KID_2).seq());
        assertEquals(Hex.sha256(doc), result.delta().newState().seqByKid().get(TestKeys.KID_2).sha256());
    }

    @Test
    void manifestSignedOnlyByRevokedReleaseKeyIsUntrusted() throws Exception {
        RevokedState revoked = new RevokedState(
                List.of(new UpdateDocuments.RevokedKey(TestKeys.KID_2, TestKeys.PUB_2)), List.of());
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), revoked);
        assertEquals(ManifestDecision.UNTRUSTED, SequencePolicy.manifestDecision(
                release(trust3(), releaseBytes(5, "a", ""), K2), s, TRUST, trust3(), NOW, NOW));
    }

    @Test
    void manifestVerifiedBeforeRotationIsUntrustedAfterIt() throws Exception {
        VerifiedDocument<ReleaseManifest> m = release(trust3(), releaseBytes(5, "a", ""), K2);
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        TrustSnapshot rotated = new TrustSnapshot(2, FP, Set.of(),
                Map.of(TEST3_KID, TEST3_PUB),
                Map.of(TEST3_PUB, Set.of(KeyRole.RELEASE, KeyRole.REVOCATION)));
        assertEquals(ManifestDecision.ACCEPT, SequencePolicy.manifestDecision(m, s, TRUST, trust3(), NOW, NOW));
        assertEquals(ManifestDecision.UNTRUSTED, SequencePolicy.manifestDecision(m, s, TRUST, rotated, NOW, NOW));
        AcceptResult result = SequencePolicy.acceptManifest(
                s, m, TRUST, rotated, "fabric-26x", version("0.4.0"), NOW, NOW);
        assertFalse(result.stateFull());
        assertEquals(s.state(), result.delta().newState());
    }

    @Test
    void signerRoleRemovedInCurrentTrustIsNotCounted() throws Exception {
        VerifiedDocument<ReleaseManifest> m = release(trust3(), releaseBytes(5, "a", ""), K2);
        // Evidence that still claims RELEASE for PUB_2, under a current trust where it lost it.
        TrustSnapshot roleRemoved = new TrustSnapshot(1, FP, Set.of(),
                Map.of(TestKeys.KID_1, TestKeys.PUB_1, TestKeys.KID_2, TestKeys.PUB_2),
                Map.of(TestKeys.PUB_1, Set.of(KeyRole.RELEASE),
                        TestKeys.PUB_2, Set.of(KeyRole.REVOCATION)));
        VerifiedDocument<ReleaseManifest> forged = new VerifiedDocument<>(
                m.document(), m.exactBytes(), m.signers(), roleRemoved);
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        assertEquals(ManifestDecision.UNTRUSTED,
                SequencePolicy.manifestDecision(forged, s, TRUST, roleRemoved, NOW, NOW));
    }

    @Test
    void restrictionMergeFromOldAndExpiredDocumentStillMerges() throws Exception {
        CurrentRevocations rev = new CurrentRevocations(5, SHA_Y, ISSUED, EXPIRES, 1);
        Snapshot s = new Snapshot(state(rev, Map.of(), List.of()), RevokedState.empty());
        VerifiedDocument<Revocations> old = revocations(trust3(), revocationBytes(2,
                "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", 4, REVOKED_TEST3, blocked(9)), K2);
        StateDelta delta = SequencePolicy.mergeRestrictions(s, trust3(), old);
        assertTrue(delta.newRevoked().revokedPubs().contains(TEST3_PUB));
        assertTrue(delta.newRevoked().blockedArtifacts().contains(h(9)));
        assertEquals(4L, delta.newState().revocations().minManifestSeq());
        assertEquals(5L, delta.newState().revocations().seq());
        assertEquals(SHA_Y, delta.newState().revocations().sha256());
    }

    @Test
    void restrictionMergeIgnoredWhenSignedByNonCurrentRevocationKey() throws Exception {
        CurrentRevocations rev = new CurrentRevocations(5, SHA_Y, ISSUED, EXPIRES, 1);
        UpdaterState st = state(rev, Map.of(), List.of());
        Snapshot s = new Snapshot(st, RevokedState.empty());
        TrustSnapshot rotated = revocationSnapshot();

        VerifiedDocument<Revocations> byOldKey = revocations(rotated, revocationBytes(7, ISSUED_TEXT,
                EXPIRES_TEXT, 8, REVOKED_TEST3, blocked(3)), K1);
        StateDelta ignored = SequencePolicy.mergeRestrictions(s, rotated, byOldKey);
        assertEquals(st, ignored.newState());
        assertTrue(ignored.newRevoked().revokedKeys().isEmpty());

        VerifiedDocument<Revocations> byBoth = revocations(rotated, revocationBytes(7, ISSUED_TEXT,
                EXPIRES_TEXT, 8, REVOKED_TEST3, blocked(3)), K1, K2);
        StateDelta merged = SequencePolicy.mergeRestrictions(s, rotated, byBoth);
        assertTrue(merged.newRevoked().revokedPubs().contains(TEST3_PUB));
        assertEquals(8L, merged.newState().revocations().minManifestSeq());
    }

    @Test
    void structurallyInvalidRevocationsDocumentMergesNothing() throws Exception {
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        Instant limit = issued.plus(FreshnessPolicy.REVOCATIONS_MAX_LIFETIME);

        VerifiedDocument<Revocations> reversed = direct(trust3(), 3, ISSUED, EXPIRES, List.of(
                new UpdateDocuments.RevokedKey(TEST3_KID, TEST3_PUB)), List.of(h(4)));
        assertEquals(SEED, SequencePolicy.mergeRestrictions(s, trust3(), reversed)
                .newState().revocations());
        assertEquals(s.state(), SequencePolicy.mergeRestrictions(s, trust3(), reversed).newState());
        assertTrue(SequencePolicy.mergeRestrictions(s, trust3(), reversed)
                .newRevoked().revokedKeys().isEmpty());

        VerifiedDocument<Revocations> tooLong = direct(trust3(), 3, limit.plusSeconds(1), issued,
                List.of(new UpdateDocuments.RevokedKey(TEST3_KID, TEST3_PUB)), List.of(h(4)));
        assertEquals(s.state(), SequencePolicy.mergeRestrictions(s, trust3(), tooLong).newState());
        assertTrue(SequencePolicy.mergeRestrictions(s, trust3(), tooLong)
                .newRevoked().revokedKeys().isEmpty());

        VerifiedDocument<Revocations> exactly400 = direct(trust3(), 3, limit, issued,
                List.of(new UpdateDocuments.RevokedKey(TEST3_KID, TEST3_PUB)), List.of(h(4)));
        assertTrue(SequencePolicy.mergeRestrictions(s, trust3(), exactly400)
                .newRevoked().revokedPubs().contains(TEST3_PUB));
    }

    @Test
    void seedPicksHighestSeqRegardlessOfListOrder() throws Exception {
        VerifiedDocument<Revocations> d2 = revocations(trust3(), revocationBytes(2,
                ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(2)), K2);
        VerifiedDocument<Revocations> d4 = revocations(trust3(), revocationBytes(4,
                ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(4)), K2);
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        for (List<VerifiedDocument<Revocations>> order : List.of(List.of(d4, d2), List.of(d2, d4))) {
            SelectionResult result = SequencePolicy.selectCurrent(s, trust3(), order, NOW);
            assertEquals(4L, result.delta().newState().revocations().seq());
            assertEquals(Hex.sha256(d4.exactBytes()), result.delta().newState().revocations().sha256());
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void seedAcceptsExpiredDocument() throws Exception {
        VerifiedDocument<Revocations> expired = revocations(trust3(), revocationBytes(3,
                "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", 0, "", blocked(3)), K2);
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        SelectionResult result = SequencePolicy.selectCurrent(s, trust3(), List.of(expired), NOW);
        CurrentRevocations cur = result.delta().newState().revocations();
        assertEquals(3L, cur.seq());
        assertEquals(Hex.sha256(expired.exactBytes()), cur.sha256());
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), cur.expires());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void seedEqualSeqConflictMakesNoChangeAndWarns() throws Exception {
        VerifiedDocument<Revocations> one = revocations(trust3(), revocationBytes(4,
                ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(1)), K2);
        VerifiedDocument<Revocations> two = revocations(trust3(), revocationBytes(4,
                ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(2)), K2);
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        SelectionResult result = SequencePolicy.selectCurrent(s, trust3(), List.of(one, two), NOW);
        assertEquals(SEED, result.delta().newState().revocations());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void nonSeedKeepsPersistedWhenNothingNew() throws Exception {
        VerifiedDocument<Revocations> persistedDoc = revocations(trust3(), revocationBytes(3,
                ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(1)), K2);
        CurrentRevocations rev = new CurrentRevocations(3, Hex.sha256(persistedDoc.exactBytes()),
                ISSUED, EXPIRES, 0);
        Snapshot s = new Snapshot(state(rev, Map.of(), List.of()), RevokedState.empty());
        VerifiedDocument<Revocations> older = revocations(trust3(), revocationBytes(2,
                ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(2)), K2);
        SelectionResult result = SequencePolicy.selectCurrent(s, trust3(), List.of(older), NOW);
        assertEquals(rev, result.delta().newState().revocations());
        assertTrue(result.warnings().isEmpty());

        VerifiedDocument<Revocations> sameSeqOther = revocations(trust3(), revocationBytes(3,
                ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(5)), K2);
        SelectionResult conflict = SequencePolicy.selectCurrent(s, trust3(), List.of(sameSeqOther), NOW);
        assertEquals(rev, conflict.delta().newState().revocations());
        assertFalse(conflict.warnings().isEmpty());
    }

    @Test
    void futureIssuedDocumentNeverBecomesCurrentButStillMergesRestrictions() throws Exception {
        VerifiedDocument<Revocations> future = revocations(trust3(), revocationBytes(6,
                "2026-12-20T00:00:00Z", "2027-12-20T00:00:00Z", 0, REVOKED_TEST3, blocked(3)), K2);
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        SelectionResult result = SequencePolicy.selectCurrent(s, trust3(), List.of(future), NOW);
        assertEquals(SEED, result.delta().newState().revocations());
        assertTrue(result.delta().newRevoked().revokedPubs().contains(TEST3_PUB));
        assertTrue(result.delta().newRevoked().blockedArtifacts().contains(h(3)));
    }

    @Test
    void documentFromAnotherTrustSnapshotIsIgnoredEverywhere() throws Exception {
        TrustSnapshot current = trust3();
        TrustSnapshot other = otherSnapshot();
        byte[] d1Bytes = revocationBytes(3, ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(1));
        VerifiedDocument<Revocations> d1 = revocations(current, d1Bytes, K2);
        VerifiedDocument<Revocations> d1Other = revocations(other, d1Bytes, K2);
        VerifiedDocument<Revocations> d2Other = revocations(other, revocationBytes(9, ISSUED_TEXT,
                EXPIRES_TEXT, 0, "", blocked(9)), K2);
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());

        SelectionResult selected = SequencePolicy.selectCurrent(s, current, List.of(d2Other, d1), NOW);
        assertEquals(3L, selected.delta().newState().revocations().seq());
        assertEquals(Hex.sha256(d1Bytes), selected.delta().newState().revocations().sha256());
        assertFalse(selected.delta().newRevoked().blockedArtifacts().contains(h(9)));
        assertTrue(selected.delta().newRevoked().blockedArtifacts().contains(h(1)));
        assertFalse(selected.warnings().isEmpty());

        assertEquals(s.state(), SequencePolicy.mergeRestrictions(s, current, d2Other).newState());

        CurrentRevocations persisted = new CurrentRevocations(3, Hex.sha256(d1Bytes), ISSUED, EXPIRES, 0);
        Snapshot withD1 = new Snapshot(state(persisted, Map.of(), List.of()), RevokedState.empty());
        assertFalse(SequencePolicy.stagingCoverage(withD1, current,
                Map.of("modrinth", d1, "github", d1Other)));
        assertTrue(SequencePolicy.stagingCoverage(withD1, current,
                Map.of("modrinth", d1, "github", d1, "sudoe", d1Other)));
    }

    @Test
    void freshRevocationsRequiresUnexpiredNonSeedAndBothFloors() throws Exception {
        ReleaseManifest manifest = new ReleaseManifest(1, ISSUED, EXPIRES, 3, List.of(), List.of());
        UpdateDocuments.Trust trust = new UpdateDocuments.Trust(1, ISSUED, 0, 0, List.of());
        CurrentRevocations cur = new CurrentRevocations(5, SHA_X, ISSUED, EXPIRES, 0);
        Snapshot fresh = new Snapshot(state(cur, Map.of(), List.of()), RevokedState.empty());
        assertTrue(SequencePolicy.freshRevocations(fresh, manifest, trust, NOW));

        Snapshot seed = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        assertFalse(SequencePolicy.freshRevocations(seed, manifest, trust, NOW));
        assertFalse(SequencePolicy.freshRevocations(fresh, manifest, trust, EXPIRES));
        assertFalse(SequencePolicy.freshRevocations(fresh, manifest,
                new UpdateDocuments.Trust(1, ISSUED, 6, 0, List.of()), NOW));
        assertFalse(SequencePolicy.freshRevocations(fresh,
                new ReleaseManifest(1, ISSUED, EXPIRES, 9, List.of(), List.of()), trust, NOW));
    }

    @Test
    void stagingCoverageCountsDistinctKnownOriginsWithMatchingBytes() throws Exception {
        byte[] dBytes = revocationBytes(3, ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(1));
        VerifiedDocument<Revocations> d = revocations(trust3(), dBytes, K2);
        VerifiedDocument<Revocations> e = revocations(trust3(), revocationBytes(3, ISSUED_TEXT,
                EXPIRES_TEXT, 0, "", blocked(2)), K2);
        VerifiedDocument<Revocations> f = revocations(trust3(), revocationBytes(3, ISSUED_TEXT,
                EXPIRES_TEXT, 0, "", blocked(3)), K2);
        Snapshot s = new Snapshot(state(new CurrentRevocations(3, Hex.sha256(dBytes), ISSUED,
                EXPIRES, 0), Map.of(), List.of()), RevokedState.empty());

        assertTrue(SequencePolicy.stagingCoverage(s, trust3(),
                Map.of("modrinth", d, "github", d, "sudoe", e)));
        assertFalse(SequencePolicy.stagingCoverage(s, trust3(),
                Map.of("modrinth", d, "github", e, "sudoe", f)));
        assertFalse(SequencePolicy.stagingCoverage(s, trust3(),
                Map.of("modrinth", d, "gitlab", d, "sudoe", e)));

        Snapshot stale = new Snapshot(state(new CurrentRevocations(3, Hex.sha256(
                revocationBytes(3, ISSUED_TEXT, EXPIRES_TEXT, 0, "", blocked(7))), ISSUED, EXPIRES, 0),
                Map.of(), List.of()), RevokedState.empty());
        assertFalse(SequencePolicy.stagingCoverage(stale, trust3(), Map.of("modrinth", d, "github", d)));

        Snapshot seed = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        assertFalse(SequencePolicy.stagingCoverage(seed, trust3(), Map.of("modrinth", d, "github", d)));
    }

    @Test
    void acceptedManifestRaisesFloorSoEarlierManifestBecomesStale() throws Exception {
        VerifiedDocument<ReleaseManifest> m1 = release(trust3(), releaseBytes(5, "a", ""), K2);
        VerifiedDocument<ReleaseManifest> m2 = release(trust3(), releaseBytes(9, "b", ""), K2);
        Snapshot before = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        assertEquals(ManifestDecision.ACCEPT, SequencePolicy.manifestDecision(m1, before, TRUST, trust3(), NOW, NOW));

        AcceptResult accepted = SequencePolicy.acceptManifest(
                before, m2, TRUST, trust3(), "fabric-26x", version("0.4.0"), NOW, NOW);
        Snapshot after = new Snapshot(accepted.delta().newState(), accepted.delta().newRevoked());
        assertEquals(ManifestDecision.STALE, SequencePolicy.manifestDecision(m1, after, TRUST, trust3(), NOW, NOW));
        assertEquals(ManifestDecision.REPLAY_SAME,
                SequencePolicy.manifestDecision(m2, after, TRUST, trust3(), NOW, NOW));
    }

    @Test
    void withdrawnEntriesOnlyForOwnPlatformAndNewerVersions() throws Exception {
        String w1 = withdrawal("fabric-26x", "0.5.0", h(10));
        String w2 = withdrawal("fabric-26x", "0.3.9", h(9));
        String w3 = withdrawal("velocity", "0.5.0", h(11));
        VerifiedDocument<ReleaseManifest> m = release(trust3(),
                releaseBytes(9, "a", w1 + "," + w2 + "," + w3), K2);
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        AcceptResult result = SequencePolicy.acceptManifest(
                s, m, TRUST, trust3(), "fabric-26x", version("0.4.0"), NOW, NOW);
        assertFalse(result.stateFull());
        List<WithdrawnEntry> withdrawn = result.delta().newState().withdrawn();
        assertEquals(1, withdrawn.size());
        assertEquals(h(10), withdrawn.get(0).sha512());
        assertEquals(version("0.5.0"), withdrawn.get(0).version());
        assertEquals(TestKeys.KID_2, withdrawn.get(0).kid());
    }

    @Test
    void seqByKidCapExceededReturnsStateFullAndUnchangedDelta() throws Exception {
        Map<String, KidSeq> full = new java.util.HashMap<>();
        for (int i = 1; i <= SequencePolicy.MAX_SEQ_BY_KID; i++) {
            full.put(String.format("%016x", i), kidSeq(1));
        }
        UpdaterState st = state(SEED, full, List.of());
        Snapshot s = new Snapshot(st, RevokedState.empty());
        AcceptResult result = SequencePolicy.acceptManifest(s,
                release(trust3(), releaseBytes(9, "a", ""), K2), TRUST, trust3(), "fabric-26x",
                version("0.4.0"), NOW, NOW);
        assertTrue(result.stateFull());
        assertEquals(st, result.delta().newState());
    }

    @Test
    void withdrawnCapExceededReturnsStateFullAndUnchangedDelta() throws Exception {
        List<WithdrawnEntry> full = new ArrayList<>();
        for (int i = 0; i < SequencePolicy.MAX_WITHDRAWN; i++) {
            full.add(new WithdrawnEntry(h(1000 + i), version("0.1.0"), TestKeys.KID_2));
        }
        UpdaterState st = state(SEED, Map.of(), full);
        Snapshot s = new Snapshot(st, RevokedState.empty());
        String w1 = withdrawal("fabric-26x", "0.5.0", h(10));
        AcceptResult result = SequencePolicy.acceptManifest(s,
                release(trust3(), releaseBytes(9, "a", w1), K2), TRUST, trust3(), "fabric-26x",
                version("0.4.0"), NOW, NOW);
        assertTrue(result.stateFull());
        assertEquals(st, result.delta().newState());
    }

    @Test
    void manifestWithInvalidTimesIsRejectedBeforeAnyFloorLogic() throws Exception {
        Map<String, KidSeq> seqByKid = Map.of(TestKeys.KID_2, kidSeq(10));
        Snapshot s = new Snapshot(state(SEED, seqByKid, List.of()), RevokedState.empty());
        String[][] bad = {
            // Year 9999 issued: future-issued and a far-future window.
            {"9999-01-01T00:00:00Z", "9999-02-01T00:00:00Z"},
            // Expired at NOW.
            {"2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"},
            // Lifetime 45 days plus one second: structurally invalid.
            {"2026-11-01T12:00:00Z", "2026-12-16T12:00:01Z"},
        };
        for (String[] times : bad) {
            // seq 50 is far above the floor, so only the time check can refuse it.
            VerifiedDocument<ReleaseManifest> m =
                    release(trust3(), releaseBytesAt(50, "t", "", times[0], times[1]), K2);
            assertEquals(ManifestDecision.INVALID_TIMES,
                    SequencePolicy.manifestDecision(m, s, TRUST, trust3(), NOW, NOW));
            AcceptResult result = SequencePolicy.acceptManifest(
                    s, m, TRUST, trust3(), "fabric-26x", version("0.4.0"), NOW, NOW);
            assertFalse(result.stateFull());
            assertEquals(s.state(), result.delta().newState());
            assertEquals(s.revoked(), result.delta().newRevoked());
            assertEquals(10L, SequencePolicy.manifestFloor(s, TRUST));
        }
    }

    @Test
    void manifestAtExactly45DaysAndExpiryOnlyAfterEffectiveNowIsAdmissible() throws Exception {
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        VerifiedDocument<ReleaseManifest> exact =
                release(trust3(), releaseBytesAt(1, "e", "", "2026-11-01T12:00:00Z", "2026-12-16T12:00:00Z"), K2);
        assertEquals(ManifestDecision.ACCEPT,
                SequencePolicy.manifestDecision(exact, s, TRUST, trust3(), NOW, NOW));
        // expires equal to effectiveNow is expired (no grace period).
        assertEquals(ManifestDecision.INVALID_TIMES, SequencePolicy.manifestDecision(exact, s, TRUST,
                trust3(), NOW, Instant.parse("2026-12-16T12:00:00Z")));
    }

    @Test
    void revocationsAtExactly64KeysMergeAnd65KeysAreSkippedWithoutEviction() throws Exception {
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        VerifiedDocument<Revocations> at64 = direct(trust3(), 3, EXPIRES, ISSUED, revokedKeys(0, 64), List.of());
        SelectionResult ok = SequencePolicy.selectCurrent(s, trust3(), List.of(at64), NOW);
        assertFalse(ok.stateFull());
        assertEquals(64, ok.delta().newRevoked().revokedKeys().size());
        assertEquals(3L, ok.delta().newState().revocations().seq());
        assertTrue(ok.warnings().isEmpty());

        VerifiedDocument<Revocations> at65 = direct(trust3(), 4, EXPIRES, ISSUED, revokedKeys(0, 65), List.of());
        SelectionResult full = SequencePolicy.selectCurrent(s, trust3(), List.of(at65), NOW);
        assertTrue(full.stateFull());
        assertEquals(List.of("revocations state full; document not merged"), full.warnings());
        assertEquals(s.revoked(), full.delta().newRevoked());
        assertEquals(SEED, full.delta().newState().revocations());
        assertEquals(s.revoked(), SequencePolicy.mergeRestrictions(s, trust3(), at65).newRevoked());
    }

    @Test
    void mergeNeverEvictsAlreadyPersistedKeysWhenALaterDocumentWouldOverflow() throws Exception {
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        VerifiedDocument<Revocations> sixty = direct(trust3(), 3, EXPIRES, ISSUED, revokedKeys(0, 60), List.of());
        VerifiedDocument<Revocations> ten = direct(trust3(), 4, EXPIRES, ISSUED, revokedKeys(60, 70), List.of());
        SelectionResult result = SequencePolicy.selectCurrent(s, trust3(), List.of(sixty, ten), NOW);
        assertTrue(result.stateFull());
        assertEquals(60, result.delta().newRevoked().revokedKeys().size());
        assertEquals(3L, result.delta().newState().revocations().seq());
    }

    @Test
    void blockedArtifactsAtExactly1024MergeAnd1025AreSkipped() throws Exception {
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        VerifiedDocument<Revocations> at1024 = direct(trust3(), 3, EXPIRES, ISSUED, List.of(), blockedRange(0, 1024));
        SelectionResult ok = SequencePolicy.selectCurrent(s, trust3(), List.of(at1024), NOW);
        assertFalse(ok.stateFull());
        assertEquals(1024, ok.delta().newRevoked().blockedArtifacts().size());
        assertEquals(3L, ok.delta().newState().revocations().seq());

        VerifiedDocument<Revocations> at1025 = direct(trust3(), 4, EXPIRES, ISSUED, List.of(), blockedRange(0, 1025));
        SelectionResult full = SequencePolicy.selectCurrent(s, trust3(), List.of(at1025), NOW);
        assertTrue(full.stateFull());
        assertEquals(s.revoked(), full.delta().newRevoked());
        assertEquals(SEED, full.delta().newState().revocations());
    }

    @Test
    void withdrawalIsAttributedToLowestSortedSigningKidAndPrunedWhenThatKidIsRevoked() throws Exception {
        // K1 and K3 are both trusted release keys. The withdrawal is attributed to the lowest kid.
        String lowestKid = Collections.min(List.of(TestKeys.KID_1, TEST3_KID));
        String lowestPub = lowestKid.equals(TestKeys.KID_1) ? TestKeys.PUB_1 : TEST3_PUB;
        byte[] doc = releaseBytes(40, "w", withdrawal("fabric-26x", "0.5.0", h(7)));
        Snapshot s = new Snapshot(state(SEED, Map.of(), List.of()), RevokedState.empty());
        AcceptResult accepted = SequencePolicy.acceptManifest(
                s, release(trust3(), doc, K1, K3), TRUST, trust3(), "fabric-26x", version("0.4.0"), NOW, NOW);
        assertEquals(1, accepted.delta().newState().withdrawn().size());
        assertEquals(lowestKid, accepted.delta().newState().withdrawn().get(0).kid());

        // Revoking the attributed kid prunes the withdrawal with its floor (12.4), even though the
        // other signer of the same manifest is still trusted.
        VerifiedDocument<Revocations> revokeLowest = direct(trust3(), 2, EXPIRES, ISSUED,
                List.of(new UpdateDocuments.RevokedKey(lowestKid, lowestPub)), List.of());
        StateDelta afterRevoke = SequencePolicy.mergeRestrictions(
                new Snapshot(accepted.delta().newState(), accepted.delta().newRevoked()),
                trust3(), revokeLowest);
        assertTrue(afterRevoke.newRevoked().revokedPubs().contains(lowestPub));
        assertTrue(afterRevoke.newState().withdrawn().isEmpty());
    }

    /** Revoked keys {@code from} (inclusive) to {@code to} (exclusive), valid 64-hex publics. */
    private static List<UpdateDocuments.RevokedKey> revokedKeys(int from, int to) throws Exception {
        List<UpdateDocuments.RevokedKey> keys = new ArrayList<>();
        for (int i = from; i < to; i++) {
            String pub = String.format("%064x", 0x1000 + i);
            keys.add(new UpdateDocuments.RevokedKey(Hex.kid(pub), pub));
        }
        return keys;
    }

    /** Blocked artifacts {@code from} (inclusive) to {@code to} (exclusive). */
    private static List<String> blockedRange(int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            out.add(h(i));
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static TrustSnapshot trust3() throws Exception {
        return new TrustSnapshot(1, FP, Set.of(),
                Map.of(TestKeys.KID_1, TestKeys.PUB_1, TestKeys.KID_2, TestKeys.PUB_2, TEST3_KID, TEST3_PUB),
                Map.of(TestKeys.PUB_1, Set.of(KeyRole.RELEASE),
                        TestKeys.PUB_2, Set.of(KeyRole.RELEASE, KeyRole.REVOCATION),
                        TEST3_PUB, Set.of(KeyRole.RELEASE)));
    }

    /** Same keys and roles as {@link #trust3()} but a different epoch, so a different snapshot. */
    private static TrustSnapshot otherSnapshot() throws Exception {
        TrustSnapshot t = trust3();
        return new TrustSnapshot(2, t.keySetFingerprint(), t.revokedPubs(), t.pubByKid(), t.rolesByPub());
    }

    /** TEST 1 holds only the revocation role, so a document signed by it alone verifies as revocations. */
    private static TrustSnapshot revocationSnapshot() {
        return new TrustSnapshot(1, FP, Set.of(),
                Map.of(TestKeys.KID_1, TestKeys.PUB_1, TestKeys.KID_2, TestKeys.PUB_2),
                Map.of(TestKeys.PUB_1, Set.of(KeyRole.REVOCATION),
                        TestKeys.PUB_2, Set.of(KeyRole.RELEASE, KeyRole.REVOCATION)));
    }

    /**
     * A revocations document that did not go through the parser, signed (as claimed) by TEST 2 and
     * bound to {@code snap}. Used to test structural rules that the parser may not enforce.
     */
    private static VerifiedDocument<Revocations> direct(TrustSnapshot snap, long seq, Instant expires,
            Instant issued, List<UpdateDocuments.RevokedKey> revokedKeys, List<String> blockedArtifacts) {
        Revocations doc = new Revocations(seq, issued, expires, 0, revokedKeys, blockedArtifacts);
        byte[] bytes = SignedFixtures.bytes("direct-" + seq + "-" + expires + "-" + issued);
        VerifiedSigners signers = new VerifiedSigners(List.of(new VerifiedSigner(
                TestKeys.KID_2, TestKeys.PUB_2, Set.of(KeyRole.RELEASE, KeyRole.REVOCATION))));
        return new VerifiedDocument<>(doc, bytes, signers, snap);
    }

    private static UpdateVersion version(String text) throws UpdateRejectedException {
        return UpdateVersion.parse(text);
    }

    private static String h(int n) {
        return String.format("%0128x", n);
    }

    private static String blocked(int n) {
        return "{\"sha512\":\"" + h(n) + "\"}";
    }

    private static String withdrawal(String platform, String version, String sha512) {
        return "{\"platform\":\"" + platform + "\",\"version\":\"" + version
                + "\",\"sha512\":\"" + sha512 + "\"}";
    }

    private static KidSeq kidSeq(long seq) {
        return new KidSeq(seq, SHA_X, ISSUED);
    }

    private static UpdaterState state(
            CurrentRevocations rev, Map<String, KidSeq> seqByKid, List<WithdrawnEntry> withdrawn) {
        return new UpdaterState(1, FP, TestKeys.PUB_2, rev, seqByKid, withdrawn, "", List.of(), Map.of());
    }

    /** Release manifest with one fabric-26x entry; {@code variant} changes the bytes, not the seq. */
    private static byte[] releaseBytes(long seq, String variant, String withdrawn) {
        return releaseBytesAt(seq, variant, withdrawn, ISSUED_TEXT, EXPIRES_TEXT);
    }

    /** Same as {@link #releaseBytes} with explicit {@code issued} and {@code expires} text. */
    private static byte[] releaseBytesAt(long seq, String variant, String withdrawn,
            String issued, String expires) {
        return SignedFixtures.bytes("{\"type\":\"quicraft.release\",\"v\":1,\"seq\":" + seq
                + ",\"issued\":\"" + issued + "\",\"expires\":\"" + expires + "\""
                + ",\"minRevocationsSeq\":3,\"releases\":[{\"platform\":\"fabric-26x\""
                + ",\"version\":\"0.4.0\",\"targets\":[\"26.1\"]"
                + ",\"file\":\"q-" + variant + ".jar\",\"size\":1"
                + ",\"sha512\":\"" + h(0xcd) + "\",\"tag\":\"v0.4.0\"}]"
                + ",\"withdrawn\":[" + withdrawn + "]}");
    }

    private static byte[] revocationBytes(long seq, String issued, String expires,
            long minManifestSeq, String revokedKeys, String blockedJson) {
        return SignedFixtures.bytes("{\"type\":\"quicraft.revocations\",\"v\":1,\"seq\":" + seq
                + ",\"issued\":\"" + issued + "\",\"expires\":\"" + expires + "\""
                + ",\"minManifestSeq\":" + minManifestSeq
                + ",\"revokedKeys\":[" + revokedKeys + "]"
                + ",\"blockedArtifacts\":[" + blockedJson + "]}");
    }

    private static byte[] envelope(byte[] doc, Key... keys) throws Exception {
        List<String> entries = new ArrayList<>();
        for (Key key : keys) {
            entries.add(SignedFixtures.signedEntry(key.kid(), key.seed(), doc));
        }
        return SignedFixtures.envelopeFor(doc, entries.toArray(new String[0]));
    }

    private static VerifiedDocument<ReleaseManifest> release(TrustSnapshot snap, byte[] doc, Key... keys)
            throws Exception {
        DocumentVerification<ReleaseManifest> result =
                AuthenticatedDocuments.verifyRelease(doc, envelope(doc, keys), snap);
        assertEquals(VerificationOutcome.VERIFIED, result.outcome());
        assertNotNull(result.verified());
        return result.verified();
    }

    private static VerifiedDocument<Revocations> revocations(TrustSnapshot snap, byte[] doc, Key... keys)
            throws Exception {
        DocumentVerification<Revocations> result =
                AuthenticatedDocuments.verifyRevocations(doc, envelope(doc, keys), snap);
        assertEquals(VerificationOutcome.VERIFIED, result.outcome());
        assertNotNull(result.verified());
        return result.verified();
    }
}
