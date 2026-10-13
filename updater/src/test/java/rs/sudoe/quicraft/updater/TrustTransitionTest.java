// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.KidSeq;
import rs.sudoe.quicraft.updater.StateRecords.PersistedTrust;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;
import rs.sudoe.quicraft.updater.TrustTransition.Transition;
import rs.sudoe.quicraft.updater.TrustTransition.TrustDecision;

/** Tests for {@link TrustTransition}: the six classifications and the state deltas they produce. */
class TrustTransitionTest {
    // RFC 8032 test-vector public keys (no private keys needed here).
    private static final String PUB_1 =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String PUB_2 =
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String PUB_3 =
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
    // Arbitrary well-formed hex keys for revoked-state fixtures.
    private static final String OLD_REVOKED = "1".repeat(64);
    private static final String OTHER_REVOKED = "2".repeat(64);
    private static final String STALE_FINGERPRINT = "a".repeat(64);

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-08T00:00:00Z");

    private static UpdateDocuments.TrustedKey key(String pub, String... roles) throws Exception {
        return new UpdateDocuments.TrustedKey(Hex.kid(pub), pub, List.of(roles));
    }

    /** A trust file with release key PUB_1 and revocation key {@code revocationPub}. */
    private static UpdateDocuments.Trust trust(int epoch, String revocationPub) throws Exception {
        return new UpdateDocuments.Trust(
                epoch,
                T0,
                3,
                9,
                List.of(key(PUB_1, "release"), key(revocationPub, "revocation")));
    }

    private static String fingerprintOf(UpdateDocuments.Trust trust) throws Exception {
        return KeySetFingerprint.of(trust.keys());
    }

    /** A non-trivial persisted state: revocation key PUB_2, revoked OLD_REVOKED, mixed kids. */
    private static Snapshot snapshot() throws Exception {
        UpdaterState state = new UpdaterState(
                1,
                STALE_FINGERPRINT,
                PUB_2,
                new CurrentRevocations(5, "c".repeat(64), T0, T1, 7),
                Map.of(
                        Hex.kid(PUB_1), new KidSeq(4, "d".repeat(64), T0),
                        Hex.kid(PUB_2), new KidSeq(2, "e".repeat(64), T0)),
                List.of(
                        new WithdrawnEntry("f".repeat(128), UpdateVersion.parse("0.4.1"), Hex.kid(PUB_2)),
                        new WithdrawnEntry("9".repeat(128), UpdateVersion.parse("0.4.0"), Hex.kid(PUB_1))),
                "0.4.1",
                List.of("8".repeat(64)),
                Map.of("modrinth", T0));
        RevokedState revoked = new RevokedState(
                List.of(new UpdateDocuments.RevokedKey(Hex.kid(OLD_REVOKED), OLD_REVOKED)),
                List.of("a".repeat(128)));
        return new Snapshot(state, revoked);
    }

    private static Snapshot snapshotWithRevokedKeys(List<UpdateDocuments.RevokedKey> keys) throws Exception {
        Snapshot base = snapshot();
        return new Snapshot(base.state(), new RevokedState(keys, base.revoked().blockedArtifacts()));
    }

    // ---- classify ----

    @Test
    void noPersistedStateClassifiesAsInitialise() throws Exception {
        UpdateDocuments.Trust trust = trust(1, PUB_2);
        TrustDecision d = TrustTransition.classify(null, trust);
        assertEquals(Transition.INITIALISE, d.transition());
        assertEquals(1, d.newEpoch());
        assertEquals(fingerprintOf(trust), d.newFingerprint());
        assertEquals(PUB_2, d.newRevocationPub());
        assertTrue(d.newlyRevokedPubs().isEmpty());
        assertTrue(d.keepPermanentRevocations());
    }

    @Test
    void sameEpochSameFingerprintIsNormal() throws Exception {
        UpdateDocuments.Trust trust = trust(2, PUB_2);
        PersistedTrust persisted = new PersistedTrust(2, fingerprintOf(trust), PUB_2);
        TrustDecision d = TrustTransition.classify(persisted, trust);
        assertEquals(Transition.NORMAL, d.transition());
        assertTrue(d.newlyRevokedPubs().isEmpty());
        assertTrue(d.keepPermanentRevocations());
    }

    @Test
    void sameEpochDifferentFingerprintIsInvalid() throws Exception {
        UpdateDocuments.Trust trust = trust(2, PUB_2);
        PersistedTrust persisted = new PersistedTrust(2, STALE_FINGERPRINT, PUB_2);
        TrustDecision d = TrustTransition.classify(persisted, trust);
        assertEquals(Transition.INVALID_SAME_EPOCH, d.transition());
        assertTrue(d.newlyRevokedPubs().isEmpty());
    }

    @Test
    void lowerEpochIsOlderReportOnly() throws Exception {
        UpdateDocuments.Trust trust = trust(2, PUB_2);
        PersistedTrust persisted = new PersistedTrust(3, fingerprintOf(trust), PUB_2);
        TrustDecision d = TrustTransition.classify(persisted, trust);
        assertEquals(Transition.OLDER_EPOCH_REPORT_ONLY, d.transition());
        assertEquals(2, d.newEpoch());
        assertTrue(d.newlyRevokedPubs().isEmpty());
    }

    @Test
    void higherEpochSameRevocationKeyIsRotation() throws Exception {
        UpdateDocuments.Trust trust = trust(3, PUB_2);
        PersistedTrust persisted = new PersistedTrust(2, STALE_FINGERPRINT, PUB_2);
        TrustDecision d = TrustTransition.classify(persisted, trust);
        assertEquals(Transition.ROTATION, d.transition());
        assertEquals(3, d.newEpoch());
        assertEquals(fingerprintOf(trust), d.newFingerprint());
        assertEquals(PUB_2, d.newRevocationPub());
        assertTrue(d.newlyRevokedPubs().isEmpty());
    }

    @Test
    void higherEpochDifferentRevocationKeyIsReplacementRevokingOldKey() throws Exception {
        UpdateDocuments.Trust trust = trust(3, PUB_3);
        PersistedTrust persisted = new PersistedTrust(2, STALE_FINGERPRINT, PUB_2);
        TrustDecision d = TrustTransition.classify(persisted, trust);
        assertEquals(Transition.REVOCATION_KEY_REPLACEMENT, d.transition());
        assertEquals(3, d.newEpoch());
        assertEquals(PUB_3, d.newRevocationPub());
        assertEquals(Set.of(PUB_2), d.newlyRevokedPubs());
        assertTrue(d.keepPermanentRevocations());
    }

    @Test
    void trustFileNeedsExactlyOneRevocationKey() throws Exception {
        UpdateDocuments.Trust none = new UpdateDocuments.Trust(
                1, T0, 0, 0, List.of(key(PUB_1, "release")));
        assertThrows(UpdateRejectedException.class, () -> TrustTransition.classify(null, none));

        UpdateDocuments.Trust two = new UpdateDocuments.Trust(
                1, T0, 0, 0, List.of(key(PUB_2, "revocation"), key(PUB_3, "revocation")));
        assertThrows(UpdateRejectedException.class, () -> TrustTransition.classify(null, two));
    }

    @Test
    void classifyRejectsNonPositiveTrustEpoch() throws Exception {
        UpdateDocuments.Trust zero = trust(0, PUB_2);
        assertThrows(UpdateRejectedException.class, () -> TrustTransition.classify(null, zero));
    }

    // ---- apply ----

    @Test
    void initialiseMatchesSpecFields() throws Exception {
        UpdateDocuments.Trust trust = trust(1, PUB_2);
        TrustDecision d = TrustTransition.classify(null, trust);
        StateDelta delta = TrustTransition.apply(d, trust, null);

        UpdaterState s = delta.newState();
        assertEquals(1, s.epoch());
        assertEquals(fingerprintOf(trust), s.keySetFingerprint());
        assertEquals(PUB_2, s.revocationKey());
        assertEquals(new CurrentRevocations(3, "", null, null, 9), s.revocations());
        assertTrue(s.seqByKid().isEmpty());
        assertTrue(s.withdrawn().isEmpty());
        assertEquals("", s.lastAnnounced());
        assertTrue(s.warnedBad().isEmpty());
        assertTrue(s.tornSince().isEmpty());
        assertEquals(RevokedState.empty(), delta.newRevoked());
    }

    @Test
    void normalInvalidAndOlderLeaveStateUnchanged() throws Exception {
        Snapshot current = snapshot();
        UpdateDocuments.Trust sameEpoch = trust(1, PUB_2);
        // Persisted epoch 1 in the snapshot; trust epoch 1 with a matching fingerprint for NORMAL.
        TrustDecision normal = new TrustDecision(
                Transition.NORMAL, 1, fingerprintOf(sameEpoch), PUB_2, Set.of(), true);
        TrustDecision invalid = new TrustDecision(
                Transition.INVALID_SAME_EPOCH, 1, STALE_FINGERPRINT, PUB_2, Set.of(), true);
        TrustDecision older = new TrustDecision(
                Transition.OLDER_EPOCH_REPORT_ONLY, 0, STALE_FINGERPRINT, PUB_2, Set.of(), true);

        for (TrustDecision d : List.of(normal, invalid, older)) {
            StateDelta delta = TrustTransition.apply(d, sameEpoch, current);
            assertEquals(current.state(), delta.newState(), d.transition().name());
            assertEquals(current.revoked(), delta.newRevoked(), d.transition().name());
        }
    }

    @Test
    void rotationReplacesEpochAndFingerprintOnly() throws Exception {
        Snapshot current = snapshot();
        UpdateDocuments.Trust trust = trust(3, PUB_2);
        TrustDecision d = TrustTransition.classify(
                new PersistedTrust(2, STALE_FINGERPRINT, PUB_2), trust);

        StateDelta delta = TrustTransition.apply(d, trust, current);
        UpdaterState s = delta.newState();
        UpdaterState before = current.state();
        assertEquals(3, s.epoch());
        assertEquals(fingerprintOf(trust), s.keySetFingerprint());
        assertEquals(PUB_2, s.revocationKey());
        assertEquals(before.revocations(), s.revocations());
        assertEquals(before.seqByKid(), s.seqByKid());
        assertEquals(before.withdrawn(), s.withdrawn());
        assertEquals(before.lastAnnounced(), s.lastAnnounced());
        assertEquals(before.warnedBad(), s.warnedBad());
        assertEquals(before.tornSince(), s.tornSince());
        assertEquals(current.revoked(), delta.newRevoked());
    }

    @Test
    void replacementRevokesOldKeyResetsFloorsAndPrunesRevokedKids() throws Exception {
        Snapshot current = snapshot();
        UpdateDocuments.Trust trust = trust(3, PUB_3);
        TrustDecision d = TrustTransition.classify(
                new PersistedTrust(2, STALE_FINGERPRINT, PUB_2), trust);

        StateDelta delta = TrustTransition.apply(d, trust, current);
        UpdaterState s = delta.newState();
        assertEquals(3, s.epoch());
        assertEquals(fingerprintOf(trust), s.keySetFingerprint());
        assertEquals(PUB_3, s.revocationKey());
        // Floors seeded from the trust file, empty sha256 marks the seed case.
        assertEquals(new CurrentRevocations(3, "", null, null, 9), s.revocations());
        // The revoked kid (PUB_2) is pruned; PUB_1's floor and withdrawn entry are kept.
        assertEquals(Map.of(Hex.kid(PUB_1), current.state().seqByKid().get(Hex.kid(PUB_1))), s.seqByKid());
        assertEquals(
                List.of(current.state().withdrawn().get(1)),
                s.withdrawn());
        assertEquals("0.4.1", s.lastAnnounced());
        assertEquals(current.state().warnedBad(), s.warnedBad());
        assertEquals(current.state().tornSince(), s.tornSince());

        RevokedState revoked = delta.newRevoked();
        assertEquals(
                List.of(
                        new UpdateDocuments.RevokedKey(Hex.kid(OLD_REVOKED), OLD_REVOKED),
                        new UpdateDocuments.RevokedKey(Hex.kid(PUB_2), PUB_2)),
                revoked.revokedKeys());
        assertEquals(current.revoked().blockedArtifacts(), revoked.blockedArtifacts());
    }

    @Test
    void replacementDoesNotDuplicateAlreadyRevokedKey() throws Exception {
        Snapshot current = snapshotWithRevokedKeys(List.of(
                new UpdateDocuments.RevokedKey(Hex.kid(OLD_REVOKED), OLD_REVOKED),
                new UpdateDocuments.RevokedKey(Hex.kid(PUB_2), PUB_2)));
        UpdateDocuments.Trust trust = trust(3, PUB_3);
        TrustDecision d = TrustTransition.classify(
                new PersistedTrust(2, STALE_FINGERPRINT, PUB_2), trust);

        RevokedState revoked = TrustTransition.apply(d, trust, current).newRevoked();
        assertEquals(2, revoked.revokedKeys().size());
        assertEquals(
                List.of(
                        new UpdateDocuments.RevokedKey(Hex.kid(OLD_REVOKED), OLD_REVOKED),
                        new UpdateDocuments.RevokedKey(Hex.kid(PUB_2), PUB_2)),
                revoked.revokedKeys());
    }

    @Test
    void replacementAppendsNewPubsSortedAfterExistingKeys() throws Exception {
        Snapshot current = snapshotWithRevokedKeys(List.of(
                new UpdateDocuments.RevokedKey(Hex.kid(OTHER_REVOKED), OTHER_REVOKED)));
        UpdateDocuments.Trust trust = trust(3, PUB_3);
        TrustDecision d = new TrustDecision(
                Transition.REVOCATION_KEY_REPLACEMENT,
                3,
                fingerprintOf(trust),
                PUB_3,
                Set.of(PUB_2, OLD_REVOKED),
                true);

        RevokedState revoked = TrustTransition.apply(d, trust, current).newRevoked();
        // Existing first, then the new ones sorted by pub: "1..." before "3d40...".
        assertEquals(
                List.of(OTHER_REVOKED, OLD_REVOKED, PUB_2),
                revoked.revokedKeys().stream().map(UpdateDocuments.RevokedKey::pub).toList());
    }

    @Test
    void replacementRefusesToRevokeTheNewRevocationKey() throws Exception {
        Snapshot current = snapshot();
        UpdateDocuments.Trust trust = trust(3, PUB_3);
        TrustDecision d = new TrustDecision(
                Transition.REVOCATION_KEY_REPLACEMENT,
                3,
                fingerprintOf(trust),
                PUB_3,
                Set.of(PUB_3),
                true);
        assertThrows(IllegalArgumentException.class, () -> TrustTransition.apply(d, trust, current));
    }

    @Test
    void replacementRefusesToDropPermanentRevocations() throws Exception {
        Snapshot current = snapshot();
        UpdateDocuments.Trust trust = trust(3, PUB_3);
        TrustDecision d = new TrustDecision(
                Transition.REVOCATION_KEY_REPLACEMENT,
                3,
                fingerprintOf(trust),
                PUB_3,
                Set.of(PUB_2),
                false);
        assertThrows(IllegalArgumentException.class, () -> TrustTransition.apply(d, trust, current));
    }

    @Test
    void applyRejectsDecisionThatDoesNotMatchTrustFile() throws Exception {
        UpdateDocuments.Trust trust = trust(3, PUB_2);
        TrustDecision wrongEpoch = new TrustDecision(
                Transition.ROTATION, 4, fingerprintOf(trust), PUB_2, Set.of(), true);
        assertThrows(IllegalArgumentException.class, () -> TrustTransition.apply(wrongEpoch, trust, snapshot()));

        TrustDecision wrongFingerprint = new TrustDecision(
                Transition.ROTATION, 3, STALE_FINGERPRINT, PUB_2, Set.of(), true);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrustTransition.apply(wrongFingerprint, trust, snapshot()));
    }

    @Test
    void currentStateMustBeNullExactlyForInitialise() throws Exception {
        UpdateDocuments.Trust trust = trust(1, PUB_2);
        TrustDecision initialise = TrustTransition.classify(null, trust);
        assertThrows(IllegalArgumentException.class, () -> TrustTransition.apply(initialise, trust, snapshot()));

        UpdateDocuments.Trust next = trust(2, PUB_2);
        TrustDecision rotation = TrustTransition.classify(new PersistedTrust(1, STALE_FINGERPRINT, PUB_2), next);
        assertThrows(IllegalArgumentException.class, () -> TrustTransition.apply(rotation, next, null));
    }

    @Test
    void applyIsDeterministicAndDoesNotMutateInputs() throws Exception {
        UpdateDocuments.Trust trust = trust(3, PUB_3);
        TrustDecision d = TrustTransition.classify(
                new PersistedTrust(2, STALE_FINGERPRINT, PUB_2), trust);
        Snapshot current = snapshot();
        Snapshot untouched = snapshot();

        StateDelta first = TrustTransition.apply(d, trust, current);
        StateDelta second = TrustTransition.apply(d, trust, current);
        assertEquals(first, second);
        assertEquals(untouched, current);
        assertNotEquals(current.state(), first.newState());
    }
}
