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
        store.initialiseIfAbsent(initial);
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
