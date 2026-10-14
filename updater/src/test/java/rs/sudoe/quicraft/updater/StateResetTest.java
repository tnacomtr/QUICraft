// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
