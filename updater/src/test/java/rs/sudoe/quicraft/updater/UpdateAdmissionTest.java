// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.UpdateAdmission.AdmissionContext;
import rs.sudoe.quicraft.updater.UpdateAdmission.AdmissionDecision;

/** Tests for {@link UpdateAdmission} (docs/updater.md 12.9). */
class UpdateAdmissionTest {

    /** The live hash recorded at init, equal to the identity's liveSha512AtInit. */
    private static final String LIVE = PipelineFixtures.RUNNING_SHA512;

    private static final CurrentRevocations SEED = new CurrentRevocations(0, "", null, null, 0);

    private static Snapshot snapshot(int epoch, String fingerprint, String revocationKey) {
        UpdaterState state = new UpdaterState(epoch, fingerprint, revocationKey, SEED, Map.of(),
                List.of(), "", List.of(), Map.of());
        return new Snapshot(state, RevokedState.empty());
    }

    /** A persisted state that matches the running trust file exactly (classify gives NORMAL). */
    private static Snapshot matching() throws Exception {
        UpdateDocuments.Trust trust = PipelineFixtures.trust();
        return snapshot(trust.epoch(), KeySetFingerprint.of(trust.keys()), TestKeys.PUB_2);
    }

    private static AdmissionContext ctx(boolean init, boolean journal, String liveAtInit, String current,
            boolean stateFull) {
        return new AdmissionContext(init, journal, liveAtInit, current, stateFull);
    }

    private static AdmissionContext healthy() {
        return ctx(true, false, LIVE, LIVE, false);
    }

    private static AdmissionDecision admit(AdmissionContext c, Snapshot s) throws Exception {
        return UpdateAdmission.admit(c, PipelineFixtures.identity(), s);
    }

    @Test
    void healthyServerWithMatchingStateIsAdmitted() throws Exception {
        AdmissionDecision d = admit(healthy(), matching());
        assertTrue(d.admitted());
        assertEquals(List.of(), d.reasons());
    }

    @Test
    void initNotCompleteAloneRefuses() throws Exception {
        AdmissionDecision d = admit(ctx(false, false, LIVE, LIVE, false), matching());
        assertFalse(d.admitted());
        assertEquals(List.of(UpdateAdmission.INIT_NOT_COMPLETE), d.reasons());
    }

    @Test
    void journalPresentAloneRefuses() throws Exception {
        AdmissionDecision d = admit(ctx(true, true, LIVE, LIVE, false), matching());
        assertEquals(List.of(UpdateAdmission.JOURNAL_PRESENT), d.reasons());
    }

    @Test
    void changedJarAloneRefuses() throws Exception {
        AdmissionDecision d = admit(ctx(true, false, LIVE, "cd".repeat(64), false), matching());
        assertEquals(List.of(UpdateAdmission.JAR_CHANGED), d.reasons());
    }

    @Test
    void olderTrustEpochAloneRefuses() throws Exception {
        UpdateDocuments.Trust trust = PipelineFixtures.trust();
        Snapshot newer = snapshot(trust.epoch() + 1, KeySetFingerprint.of(trust.keys()), TestKeys.PUB_2);
        AdmissionDecision d = admit(healthy(), newer);
        assertEquals(List.of(UpdateAdmission.TRUST_EPOCH_OLDER), d.reasons());
    }

    @Test
    void sameEpochDifferentKeySetAloneRefuses() throws Exception {
        UpdateDocuments.Trust trust = PipelineFixtures.trust();
        Snapshot other = snapshot(trust.epoch(), "ff".repeat(32), TestKeys.PUB_2);
        AdmissionDecision d = admit(healthy(), other);
        assertEquals(List.of(UpdateAdmission.TRUST_INVALID), d.reasons());
    }

    @Test
    void stateFullAloneRefuses() throws Exception {
        AdmissionDecision d = admit(ctx(true, false, LIVE, LIVE, true), matching());
        assertEquals(List.of(UpdateAdmission.STATE_FULL), d.reasons());
    }

    @Test
    void emptyLiveHashesAreRefused() throws Exception {
        AdmissionDecision d = admit(ctx(true, false, "", "", false), matching());
        assertFalse(d.admitted());
        assertEquals(List.of(UpdateAdmission.LIVE_HASH_UNKNOWN), d.reasons());
    }

    @Test
    void uppercaseAndShortLiveHashesAreRefused() throws Exception {
        String upper = LIVE.toUpperCase(java.util.Locale.ROOT);
        String shortHash = LIVE.substring(1);
        assertEquals(List.of(UpdateAdmission.LIVE_HASH_UNKNOWN),
                admit(ctx(true, false, upper, upper, false), matching()).reasons());
        assertEquals(List.of(UpdateAdmission.LIVE_HASH_UNKNOWN),
                admit(ctx(true, false, shortHash, shortHash, false), matching()).reasons());
        assertEquals(List.of(UpdateAdmission.LIVE_HASH_UNKNOWN),
                admit(ctx(true, false, LIVE, shortHash, false), matching()).reasons());
    }

    @Test
    void rotationPersistedStateIsRefusedUntilTheTrustIsApplied() throws Exception {
        UpdateDocuments.Trust trust = PipelineFixtures.trust();
        UpdateDocuments.Trust newer = new UpdateDocuments.Trust(trust.epoch() + 1, trust.built(),
                trust.revocationsSeqFloor(), trust.manifestSeqFloor(), trust.keys());
        RuntimeIdentity runningNewer = new RuntimeIdentity("fabric-26x", "26.1",
                UpdateVersion.parse("0.3.0"), newer, PipelineFixtures.RUNNING_SHA512);
        // Same revocation key as the running file, lower epoch: classify gives ROTATION.
        AdmissionDecision d = UpdateAdmission.admit(healthy(), runningNewer, matching());
        assertFalse(d.admitted());
        assertEquals(List.of(UpdateAdmission.TRUST_NOT_APPLIED), d.reasons());
    }

    @Test
    void revocationKeyReplacementPendingIsRefusedUntilTheTrustIsApplied() throws Exception {
        UpdateDocuments.Trust trust = PipelineFixtures.trust();
        UpdateDocuments.Trust newer = new UpdateDocuments.Trust(trust.epoch() + 1, trust.built(),
                trust.revocationsSeqFloor(), trust.manifestSeqFloor(), trust.keys());
        RuntimeIdentity runningNewer = new RuntimeIdentity("fabric-26x", "26.1",
                UpdateVersion.parse("0.3.0"), newer, PipelineFixtures.RUNNING_SHA512);
        // Persisted revocation key differs from the running one at a higher running epoch.
        Snapshot persisted = snapshot(trust.epoch(), KeySetFingerprint.of(trust.keys()), TestKeys.PUB_1);
        AdmissionDecision d = UpdateAdmission.admit(healthy(), runningNewer, persisted);
        assertFalse(d.admitted());
        assertEquals(List.of(UpdateAdmission.TRUST_NOT_APPLIED), d.reasons());
    }

    @Test
    void everyReasonIsCollectedInRuleOrder() throws Exception {
        UpdateDocuments.Trust trust = PipelineFixtures.trust();
        Snapshot older = snapshot(trust.epoch() + 1, KeySetFingerprint.of(trust.keys()), TestKeys.PUB_2);
        AdmissionContext all = ctx(false, true, LIVE, "cd".repeat(64), true);
        AdmissionDecision d = admit(all, older);
        assertFalse(d.admitted());
        assertEquals(List.of(
                UpdateAdmission.INIT_NOT_COMPLETE,
                UpdateAdmission.JOURNAL_PRESENT,
                UpdateAdmission.JAR_CHANGED,
                UpdateAdmission.TRUST_EPOCH_OLDER,
                UpdateAdmission.STATE_FULL), d.reasons());
    }
}
