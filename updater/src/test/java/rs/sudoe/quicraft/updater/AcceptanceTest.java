// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.KidSeq;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;

/**
 * Tests for {@link Acceptance} (docs/updater.md 12.4, 12.5, 12.6 and 12.12). Documents are signed
 * only with the throwaway RFC 8032 TEST 1 and TEST 2 seeds (see {@link TestKeys}) and verified
 * through {@link AuthenticatedDocuments}, so every accepted document is real evidence.
 */
class AcceptanceTest {

    private static final ReleaseHost MOD =
            new ReleaseHost("modrinth", URI.create("https://modrinth.example"));
    private static final ReleaseHost GH =
            new ReleaseHost("github", URI.create("https://github.example"));
    private static final ReleaseHost SU =
            new ReleaseHost("sudoe", URI.create("https://sudoe.example"));
    private static final ReleaseHosts ALL = ReleaseHosts.of(List.of(MOD, GH, SU));

    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final String ISSUED = "2026-11-01T12:00:00Z";
    private static final String EXPIRES = "2026-12-01T12:00:00Z";
    private static final String REV_EXPIRES = "2027-11-01T12:00:00Z";
    private static final String FP = "ab".repeat(32);
    private static final String REV_SHA = "ee".repeat(32);
    private static final String A512 = "a1".repeat(64);
    private static final String B512 = "b2".repeat(64);
    private static final String C512 = "c3".repeat(64);
    private static final String D512 = "d4".repeat(64);
    private static final String BLOCKED = "11".repeat(64);
    private static final String WITHDRAWN_STATE = "22".repeat(64);
    private static final String WITHDRAWN_OWN = "33".repeat(64);
    private static final String ENTRY_44 = "44".repeat(64);

    private static final CurrentRevocations SEED = new CurrentRevocations(0, "", null, null, 0);
    private static final CurrentRevocations FRESH = new CurrentRevocations(
            3, REV_SHA, Instant.parse(ISSUED), Instant.parse(REV_EXPIRES), 0);

    /** A signing key: kid and seed. */
    private record Key(String kid, String seed) {
    }

    private static final Key K1 = new Key(TestKeys.KID_1, TestKeys.SEED_1);
    private static final Key K2 = new Key(TestKeys.KID_2, TestKeys.SEED_2);

    // ---- fixtures -------------------------------------------------------------------------

    private static UpdateDocuments.Trust running() throws Exception {
        return DocumentParser.parseTrust(SignedFixtures.bytes(SignedFixtures.trustJson()));
    }

    private static RuntimeIdentity identity() throws Exception {
        return new RuntimeIdentity("fabric-26x", "26.1", UpdateVersion.parse("0.3.0"),
                running(), "ab".repeat(64));
    }

    private static UpdateVersion v(String text) throws Exception {
        return UpdateVersion.parse(text);
    }

    private static UpdaterState state(
            CurrentRevocations rev,
            Map<String, KidSeq> seqByKid,
            List<WithdrawnEntry> withdrawn,
            Map<String, Instant> torn) {
        return new UpdaterState(1, FP, TestKeys.PUB_2, rev, seqByKid, withdrawn, "", List.of(), torn);
    }

    private static Snapshot seedSnapshot() {
        return new Snapshot(state(SEED, Map.of(), List.of(), Map.of()), RevokedState.empty());
    }

    /** A snapshot whose revocations are already current and fresh at {@link #NOW}. */
    private static Snapshot freshSnapshot() {
        return new Snapshot(state(FRESH, Map.of(), List.of(), Map.of()), RevokedState.empty());
    }

    private static KidSeq kidSeq(long seq) {
        return new KidSeq(seq, REV_SHA, Instant.parse(ISSUED));
    }

    private static String entry(String platform, String version, String target, String sha512) {
        return "{\"platform\":\"" + platform + "\",\"version\":\"" + version + "\",\"targets\":[\""
                + target + "\"],\"file\":\"q-" + version + ".jar\",\"size\":1,\"sha512\":\""
                + sha512 + "\",\"tag\":\"v" + version + "\"}";
    }

    private static String withdrawal(String platform, String version, String sha512) {
        return "{\"platform\":\"" + platform + "\",\"version\":\"" + version + "\",\"sha512\":\""
                + sha512 + "\"}";
    }

    private static byte[] release(long seq, String issued, String expires, String releases, String withdrawn) {
        return SignedFixtures.bytes("{\"type\":\"quicraft.release\",\"v\":1,\"seq\":" + seq
                + ",\"issued\":\"" + issued + "\",\"expires\":\"" + expires + "\""
                + ",\"minRevocationsSeq\":3,\"releases\":[" + releases + "]"
                + ",\"withdrawn\":[" + withdrawn + "]}");
    }

    /** A manifest with the standard issued and expires times. */
    private static byte[] release(long seq, String releases, String withdrawn) {
        return release(seq, ISSUED, EXPIRES, releases, withdrawn);
    }

    private static byte[] revocations(long seq, String issued, String expires, String revokedKeys, String blocked) {
        return SignedFixtures.bytes("{\"type\":\"quicraft.revocations\",\"v\":1,\"seq\":" + seq
                + ",\"issued\":\"" + issued + "\",\"expires\":\"" + expires + "\""
                + ",\"minManifestSeq\":0,\"revokedKeys\":[" + revokedKeys + "]"
                + ",\"blockedArtifacts\":[" + blocked + "]}");
    }

    /** The standard revocations document: seq 3, unexpired, no restrictions. */
    private static byte[] revocations() {
        return revocations(3, ISSUED, REV_EXPIRES, "", "");
    }

    private static String blockedJson(String sha512) {
        return "{\"sha512\":\"" + sha512 + "\"}";
    }

    private static byte[] signed(byte[] doc, Key... keys) throws GeneralSecurityException {
        String[] entries = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            entries[i] = SignedFixtures.signedEntry(keys[i].kid(), keys[i].seed(), doc);
        }
        return SignedFixtures.envelopeFor(doc, entries);
    }

    private static HostEvidence ok(ReleaseHost host, byte[] doc, Key... keys) throws GeneralSecurityException {
        return new HostEvidence(host, FetchOutcome.OK, new DocumentPair(doc, signed(doc, keys)));
    }

    /** A pair whose payload hash does not match the document: torn (12.12 R3). */
    private static HostEvidence torn(ReleaseHost host, byte[] doc, Key key) throws GeneralSecurityException {
        byte[] sig = SignedFixtures.envelope("00".repeat(32),
                SignedFixtures.signedEntry(key.kid(), key.seed(), doc));
        return new HostEvidence(host, FetchOutcome.OK, new DocumentPair(doc, sig));
    }

    private static HostEvidence failed(ReleaseHost host) {
        return new HostEvidence(host, FetchOutcome.TRANSPORT_ERROR, null);
    }

    private static HostCollection hosts(HostEvidence... evidence) {
        return new HostCollection(List.of(evidence));
    }

    private static RevocationAcceptance revs(HostCollection responses, ReleaseHosts configured,
            Snapshot current, Instant now) throws Exception {
        return Acceptance.evaluateRevocations(responses, configured, current, running(), now);
    }

    private static ManifestAcceptance mans(HostCollection revocationResponses,
            HostCollection manifestResponses, ReleaseHosts configured, Snapshot current,
            RuntimeIdentity id, CheckMode mode, Instant now) throws Exception {
        return Acceptance.evaluateManifests(revocationResponses, manifestResponses, configured,
                current, id, UpdateVersion.parse("0.3.0"), mode, now);
    }

    private static Snapshot after(StateRecords.StateDelta delta) {
        return new Snapshot(delta.newState(), delta.newRevoked());
    }

    /** One release entry: platform 26.x, version 0.4.0, target 26.1. */
    private static String standardEntry(String sha512) {
        return entry("fabric-26x", "0.4.0", "26.1", sha512);
    }

    // ---- revocations ----------------------------------------------------------------------

    @Test
    void unconfiguredHostIsIgnoredWithDiagnostic() throws Exception {
        ReleaseHosts twoHosts = ReleaseHosts.of(List.of(MOD, GH));
        byte[] doc = revocations();
        RevocationAcceptance r = revs(hosts(ok(SU, doc, K2)), twoHosts, seedSnapshot(), NOW);
        assertTrue(r.diagnostics().contains("sudoe: host not configured, ignored"));
        assertTrue(r.delta().newState().revocations().seed(), "unconfigured evidence must not be used");
    }

    @Test
    void failedFetchGivesDiagnosticAndNoHostLeaksUri() throws Exception {
        byte[] doc = revocations();
        RevocationAcceptance r = revs(hosts(failed(MOD), ok(GH, doc, K2), ok(SU, doc, K2)), ALL,
                seedSnapshot(), NOW);
        assertTrue(r.diagnostics().contains("modrinth: revocations fetch failed (TRANSPORT_ERROR)"));
        for (String d : r.diagnostics()) {
            assertFalse(d.contains("https"), "diagnostics must not contain URIs: " + d);
        }
        assertEquals(Hex.sha256(doc), after(r.delta()).state().revocations().sha256());
    }

    @Test
    void tornPairSetsTornSinceAndWarnsAfterTwentyFourHours() throws Exception {
        byte[] doc = revocations();
        RevocationAcceptance first = revs(hosts(torn(MOD, doc, K2)), ALL, seedSnapshot(), NOW);
        assertEquals(NOW, first.delta().newState().tornSince().get("modrinth"));
        assertFalse(first.diagnostics().stream().anyMatch(d -> d.contains("24 h")));

        RevocationAcceptance later = revs(hosts(torn(MOD, doc, K2)), ALL,
                after(first.delta()), NOW.plusSeconds(25 * 3600));
        assertTrue(later.diagnostics().contains("modrinth: torn for more than 24 h"));
        assertEquals(NOW, later.delta().newState().tornSince().get("modrinth"),
                "first time torn is kept");
    }

    @Test
    void verifiedRevocationsAloneDoNotClearTornSince() throws Exception {
        UpdaterState torn = state(SEED, Map.of(), List.of(), Map.of("modrinth", NOW.minusSeconds(3600)));
        Snapshot snap = new Snapshot(torn, RevokedState.empty());
        RevocationAcceptance r = revs(hosts(ok(MOD, revocations(), K2)), ALL, snap, NOW);
        assertEquals(NOW.minusSeconds(3600), r.delta().newState().tornSince().get("modrinth"),
                "a torn manifest pair must keep its host torn even when the revocations verify");
    }

    @Test
    void torn_manifestAcrossRunsWarnsThenBothVerifiedClears() throws Exception {
        byte[] rev = revocations();
        byte[] man = release(42, standardEntry(A512), "");
        Snapshot persisted = after(revs(hosts(ok(MOD, rev, K2), ok(GH, rev, K2), ok(SU, rev, K2)),
                ALL, seedSnapshot(), NOW).delta());

        ManifestAcceptance run1 = mans(hosts(ok(MOD, rev, K2)), hosts(torn(MOD, man, K1)), ALL,
                persisted, identity(), CheckMode.REPORT, NOW);
        assertEquals(NOW, run1.delta().newState().tornSince().get("modrinth"));
        Snapshot afterRun1 = after(run1.delta());

        Instant later = NOW.plusSeconds(25 * 3600);
        RevocationAcceptance revLater = revs(hosts(ok(MOD, rev, K2)), ALL, afterRun1, later);
        assertEquals(NOW, revLater.delta().newState().tornSince().get("modrinth"),
                "a verified revocations pair must not clear a manifest-torn host");
        ManifestAcceptance run2 = mans(hosts(ok(MOD, rev, K2)), hosts(torn(MOD, man, K1)), ALL,
                after(revLater.delta()), identity(), CheckMode.REPORT, later);
        assertTrue(run2.diagnostics().contains("modrinth: torn for more than 24 h"),
                run2.diagnostics().toString());
        assertEquals(NOW, run2.delta().newState().tornSince().get("modrinth"), "first torn time is kept");

        Instant clean = NOW.plusSeconds(26 * 3600);
        ManifestAcceptance run3 = mans(hosts(ok(MOD, rev, K2)), hosts(ok(MOD, man, K1)), ALL,
                after(run2.delta()), identity(), CheckMode.REPORT, clean);
        assertFalse(run3.delta().newState().tornSince().containsKey("modrinth"),
                "both pairs verified in this run must clear the host");
    }

    @Test
    void seedCaseThroughEvaluateRevocationsMakesHighestSeqCurrent() throws Exception {
        byte[] doc = revocations();
        RevocationAcceptance r = revs(hosts(ok(MOD, doc, K2), ok(GH, doc, K2), ok(SU, doc, K2)),
                ALL, seedSnapshot(), NOW);
        CurrentRevocations current = r.delta().newState().revocations();
        assertEquals(3L, current.seq());
        assertEquals(Hex.sha256(doc), current.sha256());
        assertEquals(Instant.parse(ISSUED), current.issued());
        assertEquals(Instant.parse(REV_EXPIRES), current.expires());
        assertTrue(r.diagnostics().isEmpty(), "unexpected diagnostics: " + r.diagnostics());
    }

    @Test
    void restrictionsMergedThenManifestFromRevokedKeyIsUntrusted() throws Exception {
        byte[] rev = revocations(3, ISSUED, REV_EXPIRES,
                "{\"kid\":\"" + TestKeys.KID_1 + "\",\"pub\":\"" + TestKeys.PUB_1 + "\"}", "");
        Snapshot snap = after(revs(hosts(ok(MOD, rev, K2)), ALL, seedSnapshot(), NOW).delta());
        assertTrue(snap.revoked().revokedPubs().contains(TestKeys.PUB_1));

        byte[] fromRevoked = release(42, standardEntry(A512), "");
        ManifestAcceptance bad = mans(hosts(), hosts(ok(MOD, fromRevoked, K1)), ALL, snap,
                identity(), CheckMode.REPORT, NOW);
        assertTrue(bad.candidate().isEmpty());
        assertFalse(bad.delta().newState().seqByKid().containsKey(TestKeys.KID_1));

        byte[] fromLive = release(42, standardEntry(A512), "");
        ManifestAcceptance good = mans(hosts(), hosts(ok(MOD, fromLive, K2)), ALL, snap,
                identity(), CheckMode.REPORT, NOW);
        assertTrue(good.candidate().isPresent(), "control: a live release key must be accepted");
    }

    @Test
    void tornManifestSetsTornSince() throws Exception {
        byte[] man = release(42, standardEntry(A512), "");
        ManifestAcceptance r = mans(hosts(), hosts(torn(MOD, man, K1)), ALL, freshSnapshot(),
                identity(), CheckMode.REPORT, NOW);
        assertEquals(NOW, r.delta().newState().tornSince().get("modrinth"));
        assertTrue(r.candidate().isEmpty());
    }

    // ---- manifests: sequence and winner ---------------------------------------------------

    @Test
    void highestSeqWinsAndArtifactSourcesAreOnlyIdenticalHosts() throws Exception {
        byte[] seq5 = release(5, standardEntry(A512), "");
        byte[] seq6 = release(6, standardEntry(B512), "");
        ManifestAcceptance r = mans(hosts(), hosts(ok(MOD, seq5, K1), ok(GH, seq6, K1)), ALL,
                freshSnapshot(), identity(), CheckMode.REPORT, NOW);
        AcceptedCandidate c = r.candidate().orElseThrow();
        assertEquals(6L, c.manifest().document().seq());
        assertEquals(List.of(GH), c.artifactSources());
        assertEquals(6L, r.delta().newState().seqByKid().get(TestKeys.KID_1).seq());
    }

    @Test
    void artifactSourcesOnlyCountHostsWithIdenticalBytes() throws Exception {
        byte[] docA = release(7, standardEntry(A512), "");
        byte[] docB = release(7, standardEntry(B512), "");
        ManifestAcceptance r = mans(hosts(), hosts(ok(MOD, docA, K1), ok(GH, docB, K1), ok(SU, docA, K1)),
                ALL, freshSnapshot(), identity(), CheckMode.REPORT, NOW);
        AcceptedCandidate c = r.candidate().orElseThrow();
        assertEquals(List.of(MOD, SU), c.artifactSources());
        assertTrue(r.diagnostics().contains("github: manifest equivocation at the floor sequence"));
    }

    @Test
    void staleManifestBelowFloorIsRejected() throws Exception {
        UpdaterState st = state(FRESH, Map.of(TestKeys.KID_2, kidSeq(10)), List.of(), Map.of());
        Snapshot snap = new Snapshot(st, RevokedState.empty());
        byte[] old = release(9, standardEntry(A512), "");
        ManifestAcceptance r = mans(hosts(), hosts(ok(MOD, old, K1)), ALL, snap,
                identity(), CheckMode.REPORT, NOW);
        assertTrue(r.candidate().isEmpty());
        assertTrue(r.diagnostics().contains("modrinth: manifest sequence is below the floor"));
    }

    // ---- manifests: entry selection -------------------------------------------------------

    @Test
    void targetMustMatchExactly() throws Exception {
        byte[] otherTarget = release(5, entry("fabric-26x", "0.5.0", "26.1.1", B512), "");
        ManifestAcceptance wrong = mans(hosts(), hosts(ok(MOD, otherTarget, K1)), ALL,
                freshSnapshot(), identity(), CheckMode.REPORT, NOW);
        assertTrue(wrong.candidate().isEmpty(), "target 26.1.1 must not match 26.1");

        byte[] exact = release(5, standardEntry(A512), "");
        ManifestAcceptance right = mans(hosts(), hosts(ok(MOD, exact, K1)), ALL,
                freshSnapshot(), identity(), CheckMode.REPORT, NOW);
        assertEquals("0.4.0", right.candidate().orElseThrow().entry().version().toString());
    }

    @Test
    void platformMustMatch() throws Exception {
        byte[] velocity = release(5, entry("velocity", "0.9.0", "26.1", C512), "");
        ManifestAcceptance r = mans(hosts(), hosts(ok(MOD, velocity, K1)), ALL, freshSnapshot(),
                identity(), CheckMode.REPORT, NOW);
        assertTrue(r.candidate().isEmpty(), "an entry for another platform must not be selected");
    }

    @Test
    void versionMustExceedRunningAndOnDiskVersion() throws Exception {
        byte[] man = release(5, standardEntry(A512), "");
        ManifestAcceptance onDiskEqual = Acceptance.evaluateManifests(hosts(), hosts(ok(MOD, man, K1)),
                ALL, freshSnapshot(), identity(), v("0.4.0"), CheckMode.REPORT, NOW);
        assertTrue(onDiskEqual.candidate().isEmpty(), "a version equal to on-disk must be refused");

        RuntimeIdentity newer = new RuntimeIdentity("fabric-26x", "26.1", v("0.4.5"), running(),
                "ab".repeat(64));
        ManifestAcceptance runningHigher = mans(hosts(), hosts(ok(MOD, man, K1)), ALL,
                freshSnapshot(), newer, CheckMode.REPORT, NOW);
        assertTrue(runningHigher.candidate().isEmpty(), "a version below running must be refused");

        ManifestAcceptance above = Acceptance.evaluateManifests(hosts(), hosts(ok(MOD, man, K1)),
                ALL, freshSnapshot(), identity(), v("0.3.9"), CheckMode.REPORT, NOW);
        assertEquals("0.4.0", above.candidate().orElseThrow().entry().version().toString());
    }

    @Test
    void blockedAndWithdrawnHashesAreExcluded() throws Exception {
        Snapshot blockedSnap = new Snapshot(state(FRESH, Map.of(), List.of(), Map.of()),
                new RevokedState(List.of(), List.of(BLOCKED)));
        byte[] blockedEntry = release(5, entry("fabric-26x", "0.9.0", "26.1", BLOCKED), "");
        assertTrue(mans(hosts(), hosts(ok(MOD, blockedEntry, K1)), ALL, blockedSnap, identity(),
                CheckMode.REPORT, NOW).candidate().isEmpty(), "blocked artifact must be excluded");

        Snapshot withdrawnSnap = new Snapshot(state(FRESH, Map.of(),
                List.of(new WithdrawnEntry(WITHDRAWN_STATE, v("0.8.0"), TestKeys.KID_2)), Map.of()),
                RevokedState.empty());
        byte[] withdrawnEntry = release(5, entry("fabric-26x", "0.8.0", "26.1", WITHDRAWN_STATE), "");
        assertTrue(mans(hosts(), hosts(ok(MOD, withdrawnEntry, K1)), ALL, withdrawnSnap, identity(),
                CheckMode.REPORT, NOW).candidate().isEmpty(), "persisted withdrawn must be excluded");

        byte[] ownWithdrawal = release(5, entry("fabric-26x", "0.7.0", "26.1", WITHDRAWN_OWN),
                withdrawal("fabric-26x", "0.7.0", WITHDRAWN_OWN));
        assertTrue(mans(hosts(), hosts(ok(MOD, ownWithdrawal, K1)), ALL, freshSnapshot(), identity(),
                CheckMode.REPORT, NOW).candidate().isEmpty(), "own withdrawn entry must be excluded");

        byte[] clean = release(5, entry("fabric-26x", "0.6.0", "26.1", ENTRY_44), "");
        assertEquals("0.6.0", mans(hosts(), hosts(ok(MOD, clean, K1)), ALL, freshSnapshot(), identity(),
                CheckMode.REPORT, NOW).candidate().orElseThrow().entry().version().toString());
    }

    // ---- freshness and staging ------------------------------------------------------------

    @Test
    void reportNeedsFreshRevocations() throws Exception {
        byte[] man = release(42, standardEntry(A512), "");
        ManifestAcceptance seed = mans(hosts(), hosts(ok(MOD, man, K1)), ALL, seedSnapshot(),
                identity(), CheckMode.REPORT, NOW);
        assertTrue(seed.candidate().isEmpty(), "seed revocations are never fresh");

        CurrentRevocations expired = new CurrentRevocations(3, REV_SHA, Instant.parse(ISSUED),
                Instant.parse("2026-11-10T00:00:00Z"), 0);
        Snapshot stale = new Snapshot(state(expired, Map.of(), List.of(), Map.of()), RevokedState.empty());
        ManifestAcceptance old = mans(hosts(), hosts(ok(MOD, man, K1)), ALL, stale, identity(),
                CheckMode.REPORT, NOW);
        assertTrue(old.candidate().isEmpty(), "expired revocations are never fresh");
    }

    @Test
    void stageNeedsTwoOfThreeCoverage() throws Exception {
        byte[] rev = revocations();
        byte[] differentRev = revocations(3, ISSUED, REV_EXPIRES, "", blockedJson(BLOCKED));
        Snapshot persisted = after(revs(hosts(ok(MOD, rev, K2), ok(GH, rev, K2), ok(SU, rev, K2)),
                ALL, seedSnapshot(), NOW).delta());
        byte[] man = release(42, standardEntry(A512), "");

        ManifestAcceptance oneOfThree = mans(
                hosts(ok(MOD, rev, K2), failed(GH), ok(SU, differentRev, K2)),
                hosts(ok(MOD, man, K1)), ALL, persisted, identity(), CheckMode.STAGE, NOW);
        assertTrue(oneOfThree.candidate().isEmpty());
        assertTrue(oneOfThree.diagnostics().stream()
                .anyMatch(d -> d.contains("not matching: github, sudoe")), oneOfThree.diagnostics().toString());

        ManifestAcceptance twoOfThree = mans(
                hosts(ok(MOD, rev, K2), ok(GH, rev, K2), ok(SU, differentRev, K2)),
                hosts(ok(MOD, man, K1)), ALL, persisted, identity(), CheckMode.STAGE, NOW);
        assertTrue(twoOfThree.candidate().isPresent(),
                "two matching hosts suffice; a third host with other bytes does not block");
    }

    @Test
    void clockBehindGivesDiagnosticAndFutureIssuedManifestIsSkipped() throws Exception {
        Instant behind = Instant.parse("2026-10-30T00:00:00Z");
        byte[] man = release(42, standardEntry(A512), "");
        ManifestAcceptance r = mans(hosts(), hosts(ok(MOD, man, K1)), ALL, freshSnapshot(),
                identity(), CheckMode.REPORT, behind);
        assertTrue(r.diagnostics().contains("system clock is behind the clock floor"));
        assertTrue(r.candidate().isEmpty());
    }

    @Test
    void manifestOutsideLifetimeOrWindowIsSkipped() throws Exception {
        byte[] future = release(42, "2026-11-20T00:00:00Z", "2026-12-20T00:00:00Z", standardEntry(A512), "");
        byte[] expired = release(42, "2026-10-01T00:00:00Z", "2026-11-10T00:00:00Z", standardEntry(A512), "");
        byte[] overLong = release(42, "2026-08-01T00:00:00Z", "2026-12-31T00:00:00Z", standardEntry(A512), "");
        for (byte[] doc : List.of(future, expired, overLong)) {
            ManifestAcceptance r = mans(hosts(), hosts(ok(MOD, doc, K1)), ALL, freshSnapshot(),
                    identity(), CheckMode.REPORT, NOW);
            assertTrue(r.candidate().isEmpty());
            assertTrue(r.diagnostics().contains("modrinth: manifest outside the freshness window, skipped"));
        }
        byte[] valid = release(42, standardEntry(A512), "");
        assertTrue(mans(hosts(), hosts(ok(MOD, valid, K1)), ALL, freshSnapshot(), identity(),
                CheckMode.REPORT, NOW).candidate().isPresent());
    }

    @Test
    void stateFullStopsAcceptance() throws Exception {
        Map<String, KidSeq> full = new java.util.HashMap<>();
        for (int i = 1; i <= 32; i++) {
            full.put(String.format("%016x", i), kidSeq(1));
        }
        Snapshot snap = new Snapshot(state(FRESH, full, List.of(), Map.of()), RevokedState.empty());
        byte[] first = release(42, standardEntry(A512), "");
        byte[] second = release(43, standardEntry(B512), "");
        ManifestAcceptance r = mans(hosts(), hosts(ok(MOD, first, K1), ok(GH, second, K1)), ALL,
                snap, identity(), CheckMode.REPORT, NOW);
        assertTrue(r.stateFull());
        assertTrue(r.candidate().isEmpty());
        assertEquals(full, r.delta().newState().seqByKid());
    }

    @Test
    void inputsAreNeverMutated() throws Exception {
        byte[] rev = revocations();
        byte[] man = release(42, standardEntry(A512), "");
        Map<String, KidSeq> seq = Map.of(TestKeys.KID_2, kidSeq(10));
        Map<String, Instant> torn = Map.of("github", Instant.parse(ISSUED));
        Snapshot snap = new Snapshot(state(FRESH, seq, List.of(), torn), RevokedState.empty());
        Snapshot copy = new Snapshot(state(FRESH, seq, List.of(), torn), RevokedState.empty());
        HostCollection revResponses = hosts(ok(MOD, rev, K2));
        HostCollection manResponses = hosts(ok(MOD, man, K1));
        byte[] revBefore = revResponses.responses().get(0).pair().documentBytes();

        revs(revResponses, ALL, snap, NOW);
        mans(revResponses, manResponses, ALL, snap, identity(), CheckMode.REPORT, NOW);

        assertEquals(copy, snap);
        assertTrue(java.util.Arrays.equals(revBefore,
                revResponses.responses().get(0).pair().documentBytes()));
    }
}
