// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.PipelineFixtures.ScriptedFetcher;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;

/** Tests for {@link CheckPipeline} (docs/updater.md 12.6 and 12.12). */
class CheckPipelineTest {

    private static final String KID_1 = TestKeys.KID_1;
    private static final String PUB_1 = TestKeys.PUB_1;

    @TempDir
    Path tmp;

    private StateStore initialisedStore(StateRecords.UpdaterState state, StateRecords.RevokedState revoked)
            throws Exception {
        Path dir = PipelineFixtures.privateDir(tmp.resolve("upd"));
        StateStore store = new StateStore(dir);
        store.initialiseIfAbsent(new StateDelta(state, revoked));
        return store;
    }

    private StateStore initialisedStore() throws Exception {
        return initialisedStore(PipelineFixtures.seedState(), StateRecords.RevokedState.empty());
    }

    private CheckPipeline pipeline(StateStore store, ScriptedFetcher fetcher) throws Exception {
        return new CheckPipeline(store, PipelineFixtures.collector(fetcher), PipelineFixtures.HOSTS,
                PipelineFixtures.identity(), PipelineFixtures.CLOCK);
    }

    private static UpdateVersion onDisk() throws Exception {
        return UpdateVersion.parse("0.3.0");
    }

    @Test
    void controlManifestIsCandidateWhenNothingIsRevoked() throws Exception {
        StateStore store = initialisedStore();
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        CheckResult result = pipeline(store, fetcher).run(CheckMode.REPORT, onDisk(), new MutableCancelToken());

        assertTrue(result.candidate().isPresent(), result.diagnostics().toString());
        assertTrue(result.freshRevocations());
        assertFalse(result.stateFull());
    }

    @Test
    void revocationRestrictionsArePersistedBeforeManifestsAreEvaluated() throws Exception {
        StateStore store = initialisedStore();
        ScriptedFetcher fetcher = new ScriptedFetcher();
        // The revocations document of this run revokes the release key that signs the manifest.
        byte[] revoke = PipelineFixtures.revocations(PipelineFixtures.revokedKey(KID_1, PUB_1));
        fetcher.answerEveryHost(revoke, PipelineFixtures.manifest());

        CheckResult result = pipeline(store, fetcher).run(CheckMode.REPORT, onDisk(), new MutableCancelToken());

        assertFalse(result.candidate().isPresent(), "a manifest signed by a key revoked this run");
        Snapshot persisted = store.load().orElseThrow();
        assertTrue(persisted.revoked().revokedPubs().contains(PUB_1), "revocation was persisted");
    }

    @Test
    void reportAcceptsWithOneHostWhileStageNeedsTwoOfThree() throws Exception {
        StateStore store = initialisedStore();
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());
        fetcher.fail(PipelineFixtures.ENDPOINTS.revocations(PipelineFixtures.GH),
                FetchOutcome.TRANSPORT_ERROR);
        fetcher.fail(PipelineFixtures.ENDPOINTS.revocations(PipelineFixtures.SU),
                FetchOutcome.TRANSPORT_ERROR);
        CheckPipeline pipeline = pipeline(store, fetcher);

        CheckResult report = pipeline.run(CheckMode.REPORT, onDisk(), new MutableCancelToken());
        assertTrue(report.candidate().isPresent(), report.diagnostics().toString());

        CheckResult stage = pipeline.run(CheckMode.STAGE, onDisk(), new MutableCancelToken());
        assertFalse(stage.candidate().isPresent());
        assertTrue(stage.diagnostics().stream().anyMatch(d -> d.contains("staging coverage")),
                stage.diagnostics().toString());
    }

    @Test
    void stageWithAllThreeHostsHasCoverage() throws Exception {
        StateStore store = initialisedStore();
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        CheckResult stage = pipeline(store, fetcher).run(CheckMode.STAGE, onDisk(), new MutableCancelToken());

        assertTrue(stage.candidate().isPresent(), stage.diagnostics().toString());
    }

    @Test
    void noStateLockIsHeldDuringFetch() throws Exception {
        StateStore store = initialisedStore();
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());
        int[] commitsInsideFetch = {0};
        fetcher.onFetch = uri -> {
            // Would throw StoreBusyException or IllegalStateException if the lock were held.
            try {
                assertTrue(store.load().isPresent());
                store.commit(snap -> snap);
            } catch (Exception e) {
                throw new AssertionError("store unusable during fetch", e);
            }
            commitsInsideFetch[0]++;
        };

        CheckResult result = pipeline(store, fetcher).run(CheckMode.REPORT, onDisk(), new MutableCancelToken());

        assertTrue(result.candidate().isPresent());
        assertEquals(12, commitsInsideFetch[0], "one fetch per document and signature per host");
    }

    @Test
    void cancelledBeforeAnyCommitLeavesTheStoreUntouched() throws Exception {
        StateStore store = initialisedStore();
        Snapshot before = store.load().orElseThrow();
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());
        MutableCancelToken cancel = new MutableCancelToken();
        cancel.cancel();

        CheckResult result = pipeline(store, fetcher).run(CheckMode.REPORT, onDisk(), cancel);

        assertFalse(result.candidate().isPresent());
        assertEquals(List.of("cancelled"), result.diagnostics());
        assertEquals(before, store.load().orElseThrow());
        assertTrue(fetcher.requests.isEmpty(), "no fetch after cancellation");
    }

    @Test
    void cancelledBeforeManifestCommitReturnsCancelledWithoutCandidate() throws Exception {
        StateStore store = initialisedStore();
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());
        MutableCancelToken cancel = new MutableCancelToken();
        fetcher.onFetch = uri -> {
            if (uri.getPath().contains("manifest")) {
                cancel.cancel();
            }
        };

        CheckResult result = pipeline(store, fetcher).run(CheckMode.REPORT, onDisk(), cancel);

        assertFalse(result.candidate().isPresent());
        assertEquals(List.of("cancelled"), result.diagnostics());
    }

    @Test
    void stateFullFromManifestCommitPropagates() throws Exception {
        Map<String, StateRecords.KidSeq> full = new java.util.HashMap<>();
        for (int i = 0; i < SequencePolicy.MAX_SEQ_BY_KID; i++) {
            full.put(String.format("%016x", i + 1), new StateRecords.KidSeq(1, "ee".repeat(32),
                    java.time.Instant.parse(PipelineFixtures.ISSUED)));
        }
        StateRecords.UpdaterState seed = PipelineFixtures.seedState();
        StateRecords.UpdaterState withKids = new StateRecords.UpdaterState(seed.epoch(),
                seed.keySetFingerprint(), seed.revocationKey(), seed.revocations(), full,
                seed.withdrawn(), seed.lastAnnounced(), seed.warnedBad(), seed.tornSince());
        StateStore store = initialisedStore(withKids, StateRecords.RevokedState.empty());
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.answerEveryHost(PipelineFixtures.revocations(), PipelineFixtures.manifest());

        CheckResult result = pipeline(store, fetcher).run(CheckMode.REPORT, onDisk(), new MutableCancelToken());

        assertTrue(result.stateFull(), result.diagnostics().toString());
        assertFalse(result.candidate().isPresent());
    }

    @Test
    void uninitialisedStoreIsAnIllegalStateAndNothingIsFetched() throws Exception {
        StateStore store = new StateStore(PipelineFixtures.privateDir(tmp.resolve("empty")));
        ScriptedFetcher fetcher = new ScriptedFetcher();
        CheckPipeline pipeline = pipeline(store, fetcher);

        assertThrows(IllegalStateException.class,
                () -> pipeline.run(CheckMode.REPORT, onDisk(), new MutableCancelToken()));
        assertTrue(fetcher.requests.isEmpty());
    }
}
