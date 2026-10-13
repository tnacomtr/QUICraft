// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

/** Black-box tests for the state record constructors and their rejection rules. */
class StateRecordsTest {
    private static final String SHA256 = "a".repeat(64);
    private static final String SHA256_B = "b".repeat(64);
    private static final String PUB = "c".repeat(64);
    private static final String PUB_B = "d".repeat(64);
    private static final String KID = "0123456789abcdef";
    private static final String KID_B = "fedcba9876543210";
    private static final String SHA512 = "e".repeat(128);
    private static final String SHA512_B = "f".repeat(128);
    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final Instant T1 = Instant.parse("2027-11-01T12:00:00Z");
    private static final String UPPER_SHA256 = "A".repeat(64);

    private static CurrentRevocations validRevocations() {
        return new CurrentRevocations(3, SHA256, T0, T1, 0);
    }

    private static UpdaterState validState() throws UpdateRejectedException {
        return new UpdaterState(
                1,
                SHA256,
                PUB,
                validRevocations(),
                Map.of(KID, new KidSeq(42, SHA256_B, T0)),
                List.of(new WithdrawnEntry(SHA512, UpdateVersion.parse("0.4.1"), KID)),
                "0.4.0",
                List.of(SHA256_B),
                Map.of("modrinth", T0));
    }

    private static RevokedState validRevoked() {
        return new RevokedState(
                List.of(new UpdateDocuments.RevokedKey(KID, PUB_B)), List.of(SHA512_B));
    }

    // ---- PersistedTrust ----

    @Test
    void persistedTrustAcceptsValidFields() {
        PersistedTrust t = new PersistedTrust(1, SHA256, PUB);
        assertEquals(1, t.epoch());
        assertEquals(SHA256, t.keySetFingerprint());
        assertEquals(PUB, t.revocationPub());
    }

    @Test
    void persistedTrustRejectsEpochBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new PersistedTrust(0, SHA256, PUB));
    }

    @Test
    void persistedTrustRejectsUppercaseHex() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedTrust(1, UPPER_SHA256, PUB));
    }

    @Test
    void persistedTrustRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistedTrust(1, SHA256, PUB.substring(1)));
    }

    // ---- KidSeq ----

    @Test
    void kidSeqAcceptsValidFields() {
        KidSeq k = new KidSeq(0, SHA256, T0);
        assertEquals(0, k.seq());
        assertEquals(SHA256, k.sha256());
        assertEquals(T0, k.issued());
    }

    @Test
    void kidSeqRejectsNegativeSeq() {
        assertThrows(IllegalArgumentException.class, () -> new KidSeq(-1, SHA256, T0));
    }

    @Test
    void kidSeqRejectsWrongLengthSha256() {
        assertThrows(IllegalArgumentException.class, () -> new KidSeq(1, "a".repeat(63), T0));
    }

    @Test
    void kidSeqRejectsNullIssued() {
        assertThrows(NullPointerException.class, () -> new KidSeq(1, SHA256, null));
    }

    // ---- WithdrawnEntry ----

    @Test
    void withdrawnEntryAcceptsValidFields() throws UpdateRejectedException {
        WithdrawnEntry w = new WithdrawnEntry(SHA512, UpdateVersion.parse("0.4.1"), KID);
        assertEquals(SHA512, w.sha512());
        assertEquals(KID, w.kid());
    }

    @Test
    void withdrawnEntryRejectsUppercaseSha512() throws UpdateRejectedException {
        UpdateVersion v = UpdateVersion.parse("0.4.1");
        assertThrows(IllegalArgumentException.class,
                () -> new WithdrawnEntry("E".repeat(128), v, KID));
    }

    @Test
    void withdrawnEntryRejectsNullVersion() {
        assertThrows(NullPointerException.class, () -> new WithdrawnEntry(SHA512, null, KID));
    }

    @Test
    void withdrawnEntryRejectsWrongLengthKid() throws UpdateRejectedException {
        UpdateVersion v = UpdateVersion.parse("0.4.1");
        assertThrows(IllegalArgumentException.class,
                () -> new WithdrawnEntry(SHA512, v, "0123456789abcde"));
    }

    // ---- CurrentRevocations ----

    @Test
    void currentRevocationsAcceptsNonSeedCase() {
        CurrentRevocations r = validRevocations();
        assertFalse(r.seed());
        assertEquals(3, r.seq());
    }

    @Test
    void currentRevocationsAcceptsSeedCase() {
        CurrentRevocations r = new CurrentRevocations(0, "", null, null, 0);
        assertTrue(r.seed());
    }

    @Test
    void currentRevocationsRejectsSeedWithIssued() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrentRevocations(0, "", T0, null, 0));
    }

    @Test
    void currentRevocationsRejectsSeedWithExpires() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrentRevocations(0, "", null, T1, 0));
    }

    @Test
    void currentRevocationsRejectsNonSeedWithoutExpires() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrentRevocations(1, SHA256, T0, null, 0));
    }

    @Test
    void currentRevocationsRejectsNonSeedWithoutIssued() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrentRevocations(1, SHA256, null, T1, 0));
    }

    @Test
    void currentRevocationsRejectsUppercaseSha256() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrentRevocations(1, UPPER_SHA256, T0, T1, 0));
    }

    @Test
    void currentRevocationsRejectsNegativeSeq() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrentRevocations(-1, SHA256, T0, T1, 0));
    }

    @Test
    void currentRevocationsRejectsNegativeMinManifestSeq() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrentRevocations(1, SHA256, T0, T1, -1));
    }

    // ---- UpdaterState ----

    @Test
    void updaterStateAcceptsValidFields() throws UpdateRejectedException {
        UpdaterState s = validState();
        assertEquals(1, s.epoch());
        assertEquals("0.4.0", s.lastAnnounced());
        assertEquals(new PersistedTrust(1, SHA256, PUB), s.persistedTrust());
    }

    @Test
    void updaterStateAcceptsEmptyLastAnnounced() throws UpdateRejectedException {
        UpdaterState base = validState();
        UpdaterState s = new UpdaterState(1, SHA256, PUB, base.revocations(), Map.of(),
                List.of(), "", List.of(), Map.of());
        assertEquals("", s.lastAnnounced());
    }

    @Test
    void updaterStateRejectsEpochBelowOne() throws UpdateRejectedException {
        UpdaterState base = validState();
        assertThrows(IllegalArgumentException.class, () -> new UpdaterState(0, SHA256, PUB,
                base.revocations(), Map.of(), List.of(), "", List.of(), Map.of()));
    }

    @Test
    void updaterStateRejectsUppercaseRevocationKey() throws UpdateRejectedException {
        UpdaterState base = validState();
        assertThrows(IllegalArgumentException.class, () -> new UpdaterState(1, SHA256,
                UPPER_SHA256, base.revocations(), Map.of(), List.of(), "", List.of(), Map.of()));
    }

    @Test
    void updaterStateRejectsSeqByKidKeyOfWrongLength() throws UpdateRejectedException {
        UpdaterState base = validState();
        assertThrows(IllegalArgumentException.class, () -> new UpdaterState(1, SHA256, PUB,
                base.revocations(), Map.of("abc", new KidSeq(1, SHA256, T0)), List.of(), "",
                List.of(), Map.of()));
    }

    @Test
    void updaterStateRejectsDuplicateWithdrawnSha512() throws UpdateRejectedException {
        UpdaterState base = validState();
        UpdateVersion v = UpdateVersion.parse("0.4.1");
        List<WithdrawnEntry> dup = List.of(
                new WithdrawnEntry(SHA512, v, KID), new WithdrawnEntry(SHA512, v, KID_B));
        assertThrows(IllegalArgumentException.class, () -> new UpdaterState(1, SHA256, PUB,
                base.revocations(), Map.of(), dup, "", List.of(), Map.of()));
    }

    @Test
    void updaterStateRejectsInvalidLastAnnounced() throws UpdateRejectedException {
        UpdaterState base = validState();
        assertThrows(IllegalArgumentException.class, () -> new UpdaterState(1, SHA256, PUB,
                base.revocations(), Map.of(), List.of(), "not-a-version", List.of(), Map.of()));
    }

    @Test
    void updaterStateRejectsWarnedBadUppercase() throws UpdateRejectedException {
        UpdaterState base = validState();
        assertThrows(IllegalArgumentException.class, () -> new UpdaterState(1, SHA256, PUB,
                base.revocations(), Map.of(), List.of(), "", List.of(UPPER_SHA256),
                Map.of()));
    }

    @Test
    void updaterStateRejectsUnknownTornSinceHost() throws UpdateRejectedException {
        UpdaterState base = validState();
        assertThrows(IllegalArgumentException.class, () -> new UpdaterState(1, SHA256, PUB,
                base.revocations(), Map.of(), List.of(), "", List.of(), Map.of("gitlab", T0)));
    }

    @Test
    void updaterStateRejectsNullTornSinceValue() throws UpdateRejectedException {
        UpdaterState base = validState();
        Map<String, Instant> torn = new HashMap<>();
        torn.put("github", null);
        assertThrows(NullPointerException.class, () -> new UpdaterState(1, SHA256, PUB,
                base.revocations(), Map.of(), List.of(), "", List.of(), torn));
    }

    @Test
    void updaterStateCopiesCollectionsDefensively() throws UpdateRejectedException {
        List<String> warned = new ArrayList<>(List.of(SHA256_B));
        Map<String, Instant> torn = new HashMap<>(Map.of("sudoe", T0));
        UpdaterState base = validState();
        UpdaterState s = new UpdaterState(1, SHA256, PUB, base.revocations(), Map.of(),
                List.of(), "", warned, torn);
        warned.add(SHA256);
        torn.put("github", T1);
        assertEquals(List.of(SHA256_B), s.warnedBad());
        assertEquals(Map.of("sudoe", T0), s.tornSince());
    }

    @Test
    void updaterStateCollectionsAreUnmodifiable() throws UpdateRejectedException {
        UpdaterState s = validState();
        assertThrows(UnsupportedOperationException.class,
                () -> s.seqByKid().put(KID_B, new KidSeq(1, SHA256, T0)));
        assertThrows(UnsupportedOperationException.class,
                () -> s.tornSince().put("github", T0));
        assertThrows(UnsupportedOperationException.class,
                () -> s.withdrawn().add(new WithdrawnEntry(SHA512_B,
                        UpdateVersion.parse("0.4.2"), KID)));
        assertThrows(UnsupportedOperationException.class, () -> s.warnedBad().add(SHA256));
    }

    @Test
    void updaterStateSeqByKidIsSorted() throws UpdateRejectedException {
        Map<String, KidSeq> seq = new HashMap<>();
        seq.put(KID_B, new KidSeq(1, SHA256, T0));
        seq.put(KID, new KidSeq(2, SHA256, T0));
        UpdaterState base = validState();
        UpdaterState s = new UpdaterState(1, SHA256, PUB, base.revocations(), seq, List.of(),
                "", List.of(), Map.of());
        assertEquals(List.of(KID, KID_B), List.copyOf(s.seqByKid().keySet()));
    }

    // ---- RevokedState ----

    @Test
    void revokedStateAcceptsValidFieldsAndExposesPubs() {
        RevokedState r = validRevoked();
        assertEquals(Set.of(PUB_B), r.revokedPubs());
        assertEquals(List.of(SHA512_B), r.blockedArtifacts());
    }

    @Test
    void revokedStateEmptyHasNoEntries() {
        RevokedState r = RevokedState.empty();
        assertTrue(r.revokedKeys().isEmpty());
        assertTrue(r.blockedArtifacts().isEmpty());
        assertTrue(r.revokedPubs().isEmpty());
    }

    @Test
    void revokedStateRejectsUppercasePub() {
        List<UpdateDocuments.RevokedKey> keys = List.of(
                new UpdateDocuments.RevokedKey(KID, "C".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new RevokedState(keys, List.of()));
    }

    @Test
    void revokedStateRejectsWrongLengthKid() {
        List<UpdateDocuments.RevokedKey> keys = List.of(
                new UpdateDocuments.RevokedKey("0123", PUB_B));
        assertThrows(IllegalArgumentException.class,
                () -> new RevokedState(keys, List.of()));
    }

    @Test
    void revokedStateRejectsDuplicateRevokedPub() {
        List<UpdateDocuments.RevokedKey> keys = List.of(
                new UpdateDocuments.RevokedKey(KID, PUB_B),
                new UpdateDocuments.RevokedKey(KID_B, PUB_B));
        assertThrows(IllegalArgumentException.class,
                () -> new RevokedState(keys, List.of()));
    }

    @Test
    void revokedStateRejectsWrongLengthBlockedArtifact() {
        assertThrows(IllegalArgumentException.class,
                () -> new RevokedState(List.of(), List.of(SHA512.substring(1))));
    }

    @Test
    void revokedStateRejectsDuplicateBlockedArtifact() {
        assertThrows(IllegalArgumentException.class,
                () -> new RevokedState(List.of(), List.of(SHA512_B, SHA512_B)));
    }

    @Test
    void revokedStateCopiesDefensively() {
        List<String> blocked = new ArrayList<>(List.of(SHA512_B));
        RevokedState r = new RevokedState(List.of(), blocked);
        blocked.add(SHA512);
        assertEquals(List.of(SHA512_B), r.blockedArtifacts());
        assertThrows(UnsupportedOperationException.class,
                () -> r.blockedArtifacts().add(SHA512));
        assertThrows(UnsupportedOperationException.class,
                () -> r.revokedPubs().add(PUB));
    }

    // ---- Snapshot and StateDelta ----

    @Test
    void snapshotAcceptsValidComponents() throws UpdateRejectedException {
        Snapshot s = new Snapshot(validState(), validRevoked());
        assertNotNull(s.state());
        assertNotNull(s.revoked());
    }

    @Test
    void snapshotRejectsNullComponents() throws UpdateRejectedException {
        assertThrows(NullPointerException.class, () -> new Snapshot(null, validRevoked()));
        assertThrows(NullPointerException.class,
                () -> new Snapshot(validState(), null));
    }

    @Test
    void stateDeltaAcceptsValidComponents() throws UpdateRejectedException {
        StateDelta d = new StateDelta(validState(), RevokedState.empty());
        assertNotNull(d.newState());
        assertNotNull(d.newRevoked());
    }

    @Test
    void stateDeltaRejectsNullComponents() throws UpdateRejectedException {
        assertThrows(NullPointerException.class,
                () -> new StateDelta(null, RevokedState.empty()));
        assertThrows(NullPointerException.class,
                () -> new StateDelta(validState(), null));
    }
}
