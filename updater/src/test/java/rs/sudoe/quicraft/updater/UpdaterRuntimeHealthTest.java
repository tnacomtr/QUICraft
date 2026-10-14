// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.FakePlatform;

/** The HEALTH_PENDING path of {@link UpdaterRuntime#start}: completeHealth runs and its outcome is logged. */
class UpdaterRuntimeHealthTest {

    private static final String TXID = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tmp;

    private FakePlatform p;
    private LiveJar live;

    @BeforeEach
    void setUp() throws Exception {
        p = new FakePlatform();
        p.updateDir = tmp.resolve("upd");
        p.configFile = tmp.resolve("server.properties");
        live = UpdaterFacadeFixtures.writeLive(tmp.resolve("live"),
                UpdaterFacadeFixtures.liveJar(UpdaterFacadeFixtures.RUNNING), UpdaterFacadeFixtures.RUNNING);
        p.discovery = UpdaterFacadeFixtures.discoveryFor(live);
    }

    /** Starts once to create the state, then leaves a SWAPPED journal for the newer jar, as after an install. */
    private void leaveSwappedJournal() throws Exception {
        leaveSwappedJournal(false);
    }

    /** As {@link #leaveSwappedJournal()}; {@code removeState} also deletes the state files so init fails. */
    private void leaveSwappedJournal(boolean removeState) throws Exception {
        UpdaterRuntime first = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(
                new PipelineFixtures.ScriptedFetcher(), UpdaterFacadeFixtures.realExecutors(),
                UpdaterFacadeFixtures.testHosts()));
        first.shutdown();

        long oldSize = Files.size(live.realPath());
        String oldSum = live.sha512();
        byte[] newBytes = UpdaterFacadeFixtures.liveJar(UpdaterFacadeFixtures.NEWER);
        Files.write(live.realPath(), newBytes);
        LiveJar installed = UpdaterFacadeFixtures.writeLive(live.realScanDirectory(), newBytes,
                UpdaterFacadeFixtures.NEWER);
        p.discovery = UpdaterFacadeFixtures.discoveryFor(installed);

        JournalRecord journal = new JournalRecord(TXID, JournalPhase.SWAPPED, UpdaterFacadeFixtures.PLATFORM, 7,
                new JournalLive(installed.realPath().toString(), oldSize, oldSum,
                        UpdateVersion.parse(UpdaterFacadeFixtures.RUNNING)),
                new JournalStaged(newBytes.length, installed.sha512(),
                        UpdateVersion.parse(UpdaterFacadeFixtures.NEWER)),
                oldSum, Instant.parse("2026-11-01T00:00:00Z"), 0);
        new UpdateDirectory(p.updateDir, DurabilityOps.system()).writeJournal(journal);
        p.existedBeforeStart = true;
        if (removeState) {
            Files.deleteIfExists(p.updateDir.resolve(StateStore.STATE));
            Files.deleteIfExists(p.updateDir.resolve(StateStore.REVOKED));
        }
    }

    private void awaitLog(String part) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!p.log.contains(part) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(p.log.contains(part), () -> "log was " + p.log.lines);
    }

    @Test
    void healthyVerdictClearsTheJournalAndLogsIt() throws Exception {
        leaveSwappedJournal();
        p.health = UpdaterFacadeFixtures.healthy(true);
        UpdaterRuntime rt = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(
                new PipelineFixtures.ScriptedFetcher(), UpdaterFacadeFixtures.realExecutors(),
                UpdaterFacadeFixtures.testHosts()));
        try {
            awaitLog("passed the health check");
            assertFalse(Files.exists(p.updateDir.resolve(UpdateDirectory.JOURNAL)));
        } finally {
            rt.shutdown();
        }
    }

    @Test
    void unhealthyVerdictKeepsTheJournalAndWarns() throws Exception {
        leaveSwappedJournal();
        p.health = UpdaterFacadeFixtures.healthy(false);
        UpdaterRuntime rt = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(
                new PipelineFixtures.ScriptedFetcher(), UpdaterFacadeFixtures.realExecutors(),
                UpdaterFacadeFixtures.testHosts()));
        try {
            awaitLog("did not pass the health check");
            assertTrue(Files.exists(p.updateDir.resolve(UpdateDirectory.JOURNAL)));
            assertTrue(p.log.warnings().stream().anyMatch(w -> w.contains("did not pass the health check")));
        } finally {
            rt.shutdown();
        }
    }

    @Test
    void healthPendingDoesNotBlockStartAndUpdateIsRefused() throws Exception {
        leaveSwappedJournal();
        p.health = new HealthSignal() {
            @Override
            public java.util.concurrent.CompletionStage<Boolean> awaitHealthy(Duration timeout) {
                return new java.util.concurrent.CompletableFuture<>();
            }
        };
        UpdaterRuntime rt = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(
                new PipelineFixtures.ScriptedFetcher(), UpdaterFacadeFixtures.realExecutors(),
                UpdaterFacadeFixtures.testHosts()));
        try {
            UpdaterFacadeFixtures.Output out = new UpdaterFacadeFixtures.Output();
            rt.dispatch(new String[] {"update"}, true, out::add);
            assertTrue(rt.awaitIdle(Duration.ofSeconds(30)));
            assertTrue(out.snapshot().stream().anyMatch(l -> l.contains("update finished: NOT_ADMITTED")),
                    () -> "output was " + out.snapshot());
        } finally {
            rt.shutdown();
        }
    }

    @Test
    void aFailedInitAfterRecoveryStillRecordsTheHealthVerdict() throws Exception {
        leaveSwappedJournal(true);
        p.health = UpdaterFacadeFixtures.healthy(true);
        UpdaterRuntime rt = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(
                new PipelineFixtures.ScriptedFetcher(), UpdaterFacadeFixtures.realExecutors(),
                UpdaterFacadeFixtures.testHosts()));
        try {
            UpdaterFacadeFixtures.Output out = new UpdaterFacadeFixtures.Output();
            rt.dispatch(new String[] {"check"}, true, out::add);
            assertTrue(out.contains("updater disabled: " + UpdaterRuntime.REASON_STATE),
                    () -> "output was " + out.snapshot());
            awaitLog("passed the health check");
            assertFalse(Files.exists(p.updateDir.resolve(UpdateDirectory.JOURNAL)));
        } finally {
            rt.shutdown();
        }
    }
}
