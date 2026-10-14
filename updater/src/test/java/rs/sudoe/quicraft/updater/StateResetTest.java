// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.PipelineFixtures.ScriptedFetcher;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;
import rs.sudoe.quicraft.updater.StateReset.ResetResult;

/** Tests for {@link StateReset} (docs/updater.md 12.8). */
class StateResetTest {

    private static final String REVOKED_UNREADABLE = StateReset.REVOKED_UNREADABLE;

    @TempDir
    Path tmp;

    private Path dir() throws Exception {
        return PipelineFixtures.privateDir(tmp.resolve("upd"));
    }

    private StateStore store(Path dir, StateDelta initial) throws Exception {
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(initial, true);
        return store;
    }

    private static CheckPipeline pipeline(StateStore store, ScriptedFetcher fetcher) throws Exception {
        return new CheckPipeline(store, PipelineFixtures.collector(fetcher), PipelineFixtures.HOSTS,
                PipelineFixtures.identity(), PipelineFixtures.CLOCK);
    }

    /** A state with raised floors: a current revocations seq 9 and a manifest floor of 9. */
    private static UpdaterState raisedFloors() throws Exception {
        UpdaterState seed = PipelineFixtures.seedState();
        CurrentRevocations raised = new CurrentRevocations(9, "ee".repeat(32),
                java.time.Instant.parse(PipelineFixtures.ISSUED),
                java.time.Instant.parse(PipelineFixtures.REV_EXPIRES), 9);
        return new UpdaterState(seed.epoch(), seed.keySetFingerprint(), seed.revocationKey(), raised,
                Map.of(), List.of(), "", List.of(), Map.of());
    }

    private static RevokedState revokedRelease() {
        return new RevokedState(List.of(new UpdateDocuments.RevokedKey(TestKeys.KID_1, TestKeys.PUB_1)),
                List.of());
    }

    @Test
    void validRevokedKeepsItsBytesAndRebuildsFloorsFromTheTrustSeeds() throws Exception {
        Path dir = dir();
        StateStore store = store(dir, new StateDelta(raisedFloors(), revokedRelease()));
        byte[] revokedBefore = Files.readAllBytes(dir.resolve(StateStore.REVOKED));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertTrue(result.stateReset());
        assertTrue(result.stagingEnabled(), result.messages().toString());
        assertArrayEquals(revokedBefore, Files.readAllBytes(dir.resolve(StateStore.REVOKED)));
        Snapshot after = store.load().orElseThrow();
        assertEquals(3, after.state().revocations().seq(), "rebuilt from seed, then refetched seq 3");
        assertEquals(PipelineFixtures.seedState().keySetFingerprint(), after.state().keySetFingerprint());
        assertTrue(after.revoked().revokedPubs().contains(TestKeys.PUB_1), "permanent revocation kept");
    }

    @Test
    void failedRefetchResetsStateButKeepsStagingDisabled() throws Exception {
        Path dir = dir();
        StateStore store = store(dir, new StateDelta(raisedFloors(), revokedRelease()));
        byte[] revokedBefore = Files.readAllBytes(dir.resolve(StateStore.REVOKED));
        ScriptedFetcher unreachable = new ScriptedFetcher();

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, unreachable),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertTrue(result.stateReset());
        assertFalse(result.stagingEnabled(), "no fresh revocations after a failed refetch");
        assertFalse(unreachable.requests.isEmpty(), "the refetch was attempted");
        assertArrayEquals(revokedBefore, Files.readAllBytes(dir.resolve(StateStore.REVOKED)));
        assertTrue(store.load().orElseThrow().revoked().revokedPubs().contains(TestKeys.PUB_1));
    }

    @Test
    void overCapDocumentKeepsStagingDisabledEvenWithFreshRevocations() throws Exception {
        Path dir = dir();
        StateStore store = store(dir, new StateDelta(PipelineFixtures.seedState(), new RevokedState(List.of(), List.of())));
        // Fill revoked.json to its cap of 64 keys: a document holds at most 16.
        for (int run = 0; run < 4; run++) {
            ScriptedFetcher fill = new ScriptedFetcher();
            fill.answerEveryHost(PipelineFixtures.revocations(revokedKeys(run * 16, 16)), PipelineFixtures.manifest());
            pipeline(store, fill).run(CheckMode.REPORT, UpdateVersion.parse("0.3.0"), new MutableCancelToken());
        }
        assertEquals(64, store.load().orElseThrow().revoked().revokedPubs().size());

        // One host serves a newer document that would overflow the cap; the others serve a document
        // that fits, so the refetch still ends with fresh revocations.
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());
        byte[] overCap = PipelineFixtures.revocationsSeq(4, revokedKeys(100, 1));
        fetcher.answer(PipelineFixtures.ENDPOINTS.revocations(PipelineFixtures.MOD), overCap);
        fetcher.answer(PipelineFixtures.ENDPOINTS.revocationsSignature(PipelineFixtures.MOD),
                PipelineFixtures.revocationsSig(overCap));
        CheckResult probe = pipeline(store, fetcher).run(CheckMode.REPORT, UpdateVersion.parse("0.3.0"),
                new MutableCancelToken());
        assertTrue(probe.freshRevocations(), "precondition: fresh revocations " + probe.diagnostics());
        assertTrue(probe.stateFull(), "precondition: over-cap document reported");

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertTrue(result.stateReset());
        assertFalse(result.stagingEnabled(), "a full revoked.json keeps staging disabled");
        assertEquals(64, store.load().orElseThrow().revoked().revokedPubs().size(), "nothing evicted");
    }

    /** The seed state with the given withdrawn entries, otherwise as a first run writes it. */
    private static UpdaterState withWithdrawn(List<WithdrawnEntry> withdrawn) throws Exception {
        UpdaterState seed = PipelineFixtures.seedState();
        return new UpdaterState(seed.epoch(), seed.keySetFingerprint(), seed.revocationKey(), seed.revocations(),
                Map.of(), withdrawn, "", List.of(), Map.of());
    }

    @Test
    void resetKeepsOnlyWithdrawnEntriesThatStillApply() throws Exception {
        Path dir = dir();
        WithdrawnEntry live = new WithdrawnEntry("11".repeat(64), UpdateVersion.parse("0.4.0"), TestKeys.KID_2);
        WithdrawnEntry revokedKid = new WithdrawnEntry("22".repeat(64), UpdateVersion.parse("0.5.0"), TestKeys.KID_1);
        StateStore store = store(dir, new StateDelta(withWithdrawn(List.of(live, revokedKid)), revokedRelease()));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertTrue(result.stateReset());
        assertEquals(List.of(live), store.load().orElseThrow().state().withdrawn(),
                "the non-revoked, newer entry survives; the revoked-kid entry does not");
    }

    @Test
    void resetDropsWithdrawnEntriesNotNewerThanTheRunningOrOnDiskVersion() throws Exception {
        Path dir = dir();
        WithdrawnEntry older = new WithdrawnEntry("33".repeat(64), UpdateVersion.parse("0.3.0"), TestKeys.KID_2);
        StateStore store = store(dir, new StateDelta(withWithdrawn(List.of(older)), revokedRelease()));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertEquals(List.of(), store.load().orElseThrow().state().withdrawn(),
                "an entry at the running version is no longer withdrawn");
    }

    @Test
    void unreadableStateResetsWithEmptyWithdrawn() throws Exception {
        Path dir = dir();
        WithdrawnEntry live = new WithdrawnEntry("44".repeat(64), UpdateVersion.parse("0.4.0"), TestKeys.KID_2);
        StateStore store = store(dir, new StateDelta(withWithdrawn(List.of(live)), revokedRelease()));
        PipelineFixtures.writePrivate(dir.resolve(StateStore.STATE), SignedFixtures.bytes("garbage"));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertTrue(result.stateReset());
        assertEquals(List.of(), store.load().orElseThrow().state().withdrawn(),
                "no readable state, so no withdrawn entries are carried");
    }

    /** The seed state with the given persisted trust, otherwise as a first run writes it. */
    private static UpdaterState withTrust(int epoch, String keySetFingerprint) throws Exception {
        UpdaterState seed = PipelineFixtures.seedState();
        return new UpdaterState(epoch, keySetFingerprint, seed.revocationKey(), seed.revocations(),
                Map.of(), List.of(), "", List.of(), Map.of());
    }

    @Test
    void persistedHigherEpochRefusesResetWithoutWritingOrFetching() throws Exception {
        Path dir = dir();
        UpdaterState newer = withTrust(2, PipelineFixtures.seedState().keySetFingerprint());
        StateStore store = store(dir, new StateDelta(newer, revokedRelease()));
        byte[] stateBefore = Files.readAllBytes(dir.resolve(StateStore.STATE));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertFalse(result.stateReset());
        assertFalse(result.stagingEnabled());
        assertEquals(List.of(StateReset.REFUSED_OLDER_EPOCH), result.messages());
        assertArrayEquals(stateBefore, Files.readAllBytes(dir.resolve(StateStore.STATE)));
        assertTrue(fetcher.requests.isEmpty(), "no refetch when the reset is refused");
    }

    @Test
    void persistedSameEpochConflictRefusesResetWithoutWritingOrFetching() throws Exception {
        Path dir = dir();
        UpdaterState conflicting = withTrust(1, "ff".repeat(32));
        StateStore store = store(dir, new StateDelta(conflicting, revokedRelease()));
        byte[] stateBefore = Files.readAllBytes(dir.resolve(StateStore.STATE));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertFalse(result.stateReset());
        assertEquals(List.of(StateReset.REFUSED_SAME_EPOCH_CONFLICT), result.messages());
        assertArrayEquals(stateBefore, Files.readAllBytes(dir.resolve(StateStore.STATE)));
        assertTrue(fetcher.requests.isEmpty(), "no refetch when the reset is refused");
    }

    /** A persisted state with the given trust and withdrawn entries; everything else as a first run writes it. */
    private static UpdaterState withPersisted(int epoch, String keySetFingerprint, String revocationKey,
            List<WithdrawnEntry> withdrawn) throws Exception {
        UpdaterState seed = PipelineFixtures.seedState();
        return new UpdaterState(epoch, keySetFingerprint, revocationKey, seed.revocations(), Map.of(), withdrawn,
                "", List.of(), Map.of());
    }

    /** The running trust file at {@code epoch}, with the same keys as the fixture trust file. */
    private static RuntimeIdentity runningAtEpoch(int epoch) throws Exception {
        UpdateDocuments.Trust t = PipelineFixtures.trust();
        UpdateDocuments.Trust at = new UpdateDocuments.Trust(epoch, t.built(), t.revocationsSeqFloor(),
                t.manifestSeqFloor(), t.keys());
        return new RuntimeIdentity("fabric-26x", "26.1", UpdateVersion.parse("0.3.0"), at,
                PipelineFixtures.RUNNING_SHA512);
    }

    private void assertRefusedWithoutWritingOrFetching(String message, UpdaterState persisted,
            RuntimeIdentity id) throws Exception {
        Path dir = dir();
        StateStore store = store(dir, new StateDelta(persisted, revokedRelease()));
        byte[] stateBefore = Files.readAllBytes(dir.resolve(StateStore.STATE));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher), id,
                UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertFalse(result.stateReset());
        assertFalse(result.stagingEnabled());
        assertEquals(List.of(message), result.messages());
        assertArrayEquals(stateBefore, Files.readAllBytes(dir.resolve(StateStore.STATE)));
        assertTrue(fetcher.requests.isEmpty(), "no refetch when the reset is refused");
    }

    @Test
    void rotationPendingTrustRefusesResetWithoutWritingOrFetching() throws Exception {
        // Same revocation key as the running file, higher running epoch: classify gives ROTATION.
        UpdaterState persisted = withPersisted(1, PipelineFixtures.seedState().keySetFingerprint(),
                TestKeys.PUB_2, List.of());
        assertRefusedWithoutWritingOrFetching(StateReset.REFUSED_TRUST_NOT_APPLIED, persisted,
                runningAtEpoch(2));
    }

    @Test
    void revocationKeyReplacementPendingTrustRefusesResetWithoutWritingOrFetching() throws Exception {
        // Persisted revocation key differs from the running one at a higher running epoch:
        // classify gives REVOCATION_KEY_REPLACEMENT, whose old key only apply() revokes.
        UpdaterState persisted = withPersisted(1, PipelineFixtures.seedState().keySetFingerprint(),
                TestKeys.PUB_1, List.of());
        assertRefusedWithoutWritingOrFetching(StateReset.REFUSED_TRUST_NOT_APPLIED, persisted,
                runningAtEpoch(2));
    }

    @Test
    void resetCarriesAWithdrawnEntryWhoseKidIsNotInTheRunningTrust() throws Exception {
        WithdrawnEntry foreign = new WithdrawnEntry("66".repeat(64), UpdateVersion.parse("0.4.0"), "aa".repeat(8));
        Path dir = dir();
        StateStore store = store(dir, new StateDelta(withWithdrawn(List.of(foreign)), revokedRelease()));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertEquals(List.of(foreign), store.load().orElseThrow().state().withdrawn(),
                "a kid absent from the trust file but not revoked still withdraws");
    }

    @Test
    void withdrawnBoundaryWhenOnDiskIsHigherThanRunning() throws Exception {
        Path dir = dir();
        WithdrawnEntry atOnDisk = new WithdrawnEntry("51".repeat(64), UpdateVersion.parse("0.5.0"), TestKeys.KID_2);
        WithdrawnEntry aboveOnDisk = new WithdrawnEntry("52".repeat(64), UpdateVersion.parse("0.5.1"), TestKeys.KID_2);
        StateStore store = store(dir, new StateDelta(withWithdrawn(List.of(atOnDisk, aboveOnDisk)),
                revokedRelease()));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.5.0"), new MutableCancelToken());

        assertEquals(List.of(aboveOnDisk), store.load().orElseThrow().state().withdrawn(),
                "the floor is the on-disk version when it is higher: equal drops, greater survives");
    }

    @Test
    void withdrawnBoundaryWhenRunningIsHigherThanOnDisk() throws Exception {
        Path dir = dir();
        WithdrawnEntry belowRunning = new WithdrawnEntry("53".repeat(64), UpdateVersion.parse("0.2.9"), TestKeys.KID_2);
        WithdrawnEntry atRunning = new WithdrawnEntry("54".repeat(64), UpdateVersion.parse("0.3.0"), TestKeys.KID_2);
        WithdrawnEntry aboveRunning = new WithdrawnEntry("55".repeat(64), UpdateVersion.parse("0.3.1"), TestKeys.KID_2);
        StateStore store = store(dir, new StateDelta(withWithdrawn(List.of(belowRunning, atRunning, aboveRunning)),
                revokedRelease()));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        // Running 0.3.0 is above the on-disk 0.2.0, so 0.2.9 is above the on-disk version but must still drop.
        StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.2.0"), new MutableCancelToken());

        assertEquals(List.of(aboveRunning), store.load().orElseThrow().state().withdrawn(),
                "the floor is the running version when it is higher: 0.2.9 and 0.3.0 drop, 0.3.1 survives");
    }

    @Test
    void replayedPreWithdrawalManifestIsNotOfferedAfterTheResetCarriedTheWithdrawal() throws Exception {
        // The withdrawn build is the release in the standard manifest (0.4.0, SHA512_A).
        WithdrawnEntry withdrawn = new WithdrawnEntry(SignedFixtures.SHA512_A, UpdateVersion.parse("0.4.0"),
                TestKeys.KID_2);
        Path dir = dir();
        // No revoked keys: revokedRelease() would revoke KID_1, the release key that signs the manifest.
        StateStore store = store(dir, new StateDelta(withWithdrawn(List.of(withdrawn)), RevokedState.empty()));
        // The reset's own refetch fails, so the replayed manifest is only seen by the check below.
        StateReset.resetStateAndRefetch(store, pipeline(store, new ScriptedFetcher()),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());
        assertEquals(List.of(withdrawn), store.load().orElseThrow().state().withdrawn());

        ScriptedFetcher replay = new ScriptedFetcher();
        replay.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());
        CheckResult afterReset = pipeline(store, replay).run(CheckMode.REPORT, UpdateVersion.parse("0.3.0"),
                new MutableCancelToken());
        assertTrue(afterReset.candidate().isEmpty(), "the withdrawn build must not be offered: "
                + afterReset.diagnostics());

        // Control: the same manifest offers the build when no withdrawal was carried.
        StateStore control = new StateStore(PipelineFixtures.privateDir(tmp.resolve("control")));
        control.initialiseIfAbsent(new StateDelta(withWithdrawn(List.of()), RevokedState.empty()), true);
        ScriptedFetcher controlFetcher = new ScriptedFetcher();
        controlFetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());
        CheckResult offered = pipeline(control, controlFetcher).run(CheckMode.REPORT,
                UpdateVersion.parse("0.3.0"), new MutableCancelToken());
        assertTrue(offered.candidate().isPresent(), "control: the build is offered without a withdrawal "
                + offered.diagnostics());
    }

    /** The snapshot with one more withdrawn entry; used as a concurrent commit's merge. */
    private static Snapshot addWithdrawn(Snapshot s, WithdrawnEntry e) {
        UpdaterState st = s.state();
        List<WithdrawnEntry> all = new ArrayList<>(st.withdrawn());
        all.add(e);
        return new Snapshot(new UpdaterState(st.epoch(), st.keySetFingerprint(), st.revocationKey(),
                st.revocations(), st.seqByKid(), all, st.lastAnnounced(), st.warnedBad(), st.tornSince()),
                s.revoked());
    }

    @Test
    void commitFromAnotherThreadDuringTheResetIsSerialisedAfterTheWrite() throws Exception {
        Path dir = dir();
        StateStore store = store(dir, new StateDelta(withWithdrawn(List.of()), revokedRelease()));
        WithdrawnEntry late = new WithdrawnEntry("77".repeat(64), UpdateVersion.parse("0.4.0"), TestKeys.KID_2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Snapshot>> commitRef = new AtomicReference<>();
        RuntimeIdentity id = PipelineFixtures.identity();
        try {
            StateStore.ResetDecision decision = store.resetLocked(input -> {
                commitRef.set(pool.submit(() -> store.commit(s -> addWithdrawn(s, late))));
                // The commit must wait while the reset holds the state lock.
                assertThrows(TimeoutException.class, () -> commitRef.get().get(300, TimeUnit.MILLISECONDS),
                        "a commit cannot run between the reset's read and its write");
                return StateReset.plan(input, id, UpdateVersion.parse("0.3.0"));
            });
            assertTrue(decision.rebuilt().isPresent());
            // Once the reset returned, the queued commit runs and merges onto the rebuilt state.
            commitRef.get().get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(store.load().orElseThrow().state().withdrawn().contains(late),
                "the entry committed during the reset survives");
    }

    private static String[] revokedKeys(int from, int count) throws Exception {
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            String pub = Hex.sha256(("reset-over-cap-" + (from + i)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            keys[i] = PipelineFixtures.revokedKey(Hex.kid(pub), pub);
        }
        return keys;
    }

    @Test
    void invalidRevokedWritesNothingAndDoesNotRefetch() throws Exception {
        Path dir = dir();
        StateStore store = store(dir, new StateDelta(raisedFloors(), revokedRelease()));
        byte[] stateBefore = Files.readAllBytes(dir.resolve(StateStore.STATE));
        PipelineFixtures.writePrivate(dir.resolve(StateStore.REVOKED), SignedFixtures.bytes("garbage"));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertFalse(result.stateReset());
        assertFalse(result.stagingEnabled());
        assertEquals(List.of(REVOKED_UNREADABLE), result.messages());
        assertArrayEquals(stateBefore, Files.readAllBytes(dir.resolve(StateStore.STATE)));
        assertEquals("garbage", Files.readString(dir.resolve(StateStore.REVOKED)));
        assertTrue(fetcher.requests.isEmpty(), "no refetch when revoked.json is unreadable");
    }

    @Test
    void uninitialisedStoreWritesNothing() throws Exception {
        StateStore store = new StateStore(dir());
        ScriptedFetcher fetcher = new ScriptedFetcher();

        ResetResult result = StateReset.resetStateAndRefetch(store, pipeline(store, fetcher),
                PipelineFixtures.identity(), UpdateVersion.parse("0.3.0"), new MutableCancelToken());

        assertFalse(result.stateReset());
        assertEquals(List.of(StateReset.NOT_INITIALISED), result.messages());
        assertTrue(fetcher.requests.isEmpty());
        assertFalse(Files.exists(dir().resolve(StateStore.STATE)));
    }
}
