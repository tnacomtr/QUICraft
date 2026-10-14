// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.PipelineFixtures.ScriptedFetcher;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.FakePlatform;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.FixedDiscovery;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.Output;
import rs.sudoe.quicraft.updater.UpdaterRuntime.Reply;

/** The command dispatcher of {@link UpdaterRuntime}: worker use, BUSY, update, cancel and reset-state. */
class UpdaterRuntimeCommandTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private FakePlatform p;
    private LiveJar live;
    private byte[] liveBytes;
    private UpdaterRuntime rt;

    @BeforeEach
    void setUp() throws Exception {
        p = new FakePlatform();
        p.updateDir = tmp.resolve("upd");
        p.configFile = tmp.resolve("server.properties");
        liveBytes = UpdaterFacadeFixtures.liveJar(UpdaterFacadeFixtures.RUNNING);
        live = UpdaterFacadeFixtures.writeLive(tmp.resolve("live"), liveBytes, UpdaterFacadeFixtures.RUNNING);
        p.discovery = UpdaterFacadeFixtures.discoveryFor(live);
    }

    @AfterEach
    void tearDown() {
        if (rt != null) {
            rt.shutdown();
        }
    }

    private UpdaterRuntime start(SourceFetcher fetcher) {
        rt = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(fetcher,
                UpdaterFacadeFixtures.realExecutors(), UpdaterFacadeFixtures.testHosts()));
        return rt;
    }

    /** Starts with the OS name the capability decision sees fixed, instead of the test machine's. */
    private UpdaterRuntime startOn(String osName, SourceFetcher fetcher) {
        rt = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(fetcher,
                UpdaterFacadeFixtures.realExecutors(), UpdaterFacadeFixtures.testHosts(), osName));
        return rt;
    }

    /** The owner opt-out of docs/updater.md section 6: stage and verify, never install. */
    private void optOutOfTheSwap() throws IOException {
        Files.writeString(p.configFile, "update.install=stage-only\n");
    }

    private Reply dispatch(Output out, boolean console, String... args) {
        return rt.dispatch(args, console, out::add);
    }

    private void awaitIdle() throws InterruptedException {
        assertTrue(rt.awaitIdle(WAIT), "the worker did not finish in time");
    }

    @Test
    void checkRunsOnTheWorkerAndReportsNoUpdate() throws Exception {
        start(new ScriptedFetcher());
        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true, "check"));
        awaitIdle();
        assertTrue(out.contains("no update"), () -> "output was " + out.snapshot());
    }

    @Test
    void silentCheckPrintsNothingForNoUpdate() throws Exception {
        start(new ScriptedFetcher());
        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true, "check", "-s"));
        awaitIdle();
        assertFalse(out.contains("no update"), () -> "output was " + out.snapshot());
    }

    @Test
    void secondCommandWhileOneRunsIsBusy() throws Exception {
        ScriptedFetcher fetcher = new ScriptedFetcher();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fetcher.onFetch = uri -> {
            entered.countDown();
            try {
                release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        start(fetcher);
        Output first = new Output();
        assertEquals(Reply.STARTED, dispatch(first, true, "check"));
        assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        Output second = new Output();
        assertEquals(Reply.BUSY, dispatch(second, true));
        assertTrue(second.contains(UpdaterRuntime.BUSY_TEXT));

        release.countDown();
        awaitIdle();
        assertTrue(first.contains("no update"), () -> "output was " + first.snapshot());
    }

    @Test
    void aSilentCheckAnnouncesANewVersionOnceAndPersistsIt() throws Exception {
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        start(metadata);

        Output first = new Output();
        assertEquals(Reply.STARTED, dispatch(first, true, "check", "-s"));
        awaitIdle();
        String notice = "a new QUICraft version is available: " + UpdaterFacadeFixtures.NEWER;
        assertTrue(first.contains(notice), () -> "output was " + first.snapshot());
        assertEquals(1, p.notices.size());
        assertEquals(1, p.log.lines.stream().filter(l -> l.contains(notice)).count(),
                () -> "the console records the announcement once, log was " + p.log.lines);

        Output second = new Output();
        assertEquals(Reply.STARTED, dispatch(second, true, "check", "-s"));
        awaitIdle();
        assertFalse(second.contains(notice), () -> "output was " + second.snapshot());
        assertEquals(1, p.notices.size(), "the same version is announced once");
    }

    @Test
    void aStageOnlyConfigStagesTheVerifiedJarOnlyAndLeavesTheLiveJarAlone() throws Exception {
        optOutOfTheSwap();
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        start(new TransactionFixtures.ArtifactFetcher(metadata, newJar));

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true));
        awaitIdle();

        assertTrue(out.contains("update finished: STAGED_ONLY"), () -> "output was " + out.snapshot());
        assertArrayEquals(liveBytes, Files.readAllBytes(live.realPath()));
    }

    @Test
    void anUpdateOnALinuxNamedPlatformWithTheDefaultConfigSwapsTheLiveJar() throws Exception {
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        startOn("Linux", new TransactionFixtures.ArtifactFetcher(metadata, newJar));

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true));
        awaitIdle();

        assertTrue(out.contains("update finished: INSTALLED_RESTART_PENDING"),
                () -> "output was " + out.snapshot());
        assertArrayEquals(newJar, Files.readAllBytes(live.realPath()),
                "the live jar now holds the verified staged jar");
    }

    @Test
    void anUpdateOnAWindowsNamedPlatformWithTheDefaultConfigStagesOnly() throws Exception {
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        startOn("Windows 11", new TransactionFixtures.ArtifactFetcher(metadata, newJar));

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true));
        awaitIdle();

        assertTrue(out.contains("update finished: STAGED_ONLY"), () -> "output was " + out.snapshot());
        assertArrayEquals(liveBytes, Files.readAllBytes(live.realPath()));
    }

    @Test
    void aGarbageInstallConfigOnALinuxNamedPlatformStagesOnly() throws Exception {
        Files.writeString(p.configFile, "update.install=helper\n");
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        startOn("Linux", new TransactionFixtures.ArtifactFetcher(metadata, newJar));

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true));
        awaitIdle();

        assertTrue(out.contains("update finished: STAGED_ONLY"), () -> "output was " + out.snapshot());
        assertArrayEquals(liveBytes, Files.readAllBytes(live.realPath()));
    }

    @Test
    void emptyArgsStageAnUpdateAndTheUpdateWordIsUsage() throws Exception {
        // Stage-only, so this test measures the dispatch form and not what the platform may do.
        optOutOfTheSwap();
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        start(new TransactionFixtures.ArtifactFetcher(metadata, newJar));

        Output usageOnly = new Output();
        assertEquals(Reply.DONE, dispatch(usageOnly, true, "update"));
        assertTrue(usageOnly.contains(UpdaterRuntime.USAGE), () -> "output was " + usageOnly.snapshot());

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true));
        awaitIdle();
        assertTrue(out.contains("update finished: STAGED_ONLY"), () -> "output was " + out.snapshot());
    }

    @Test
    void cancelWithNoActiveTransactionDeletesNothing() throws Exception {
        start(new ScriptedFetcher());
        Path keep = p.updateDir.resolve("keep.txt");
        Files.createDirectories(p.updateDir);
        Files.writeString(keep, "kept");

        Output out = new Output();
        assertEquals(Reply.DONE, dispatch(out, true, "cancel"));
        assertTrue(out.contains("no update is running; nothing was deleted"), () -> out.snapshot().toString());
        assertEquals("kept", Files.readString(keep));
    }

    @Test
    void resetStateFromAnotherSourceIsRefused() throws Exception {
        start(new ScriptedFetcher());
        Output out = new Output();
        assertEquals(Reply.DONE, dispatch(out, false, "reset-state"));
        assertTrue(out.contains(UpdaterRuntime.NOT_CONSOLE));
        assertTrue(rt.awaitIdle(Duration.ofSeconds(1)));
    }

    @Test
    void resetStateWithAJournalPresentIsRefused() throws Exception {
        start(new ScriptedFetcher());
        Output seed = new Output();
        dispatch(seed, true, "check", "-s");
        awaitIdle();
        Files.write(p.updateDir.resolve(UpdateDirectory.JOURNAL), new byte[] {0});

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true, "reset-state"));
        awaitIdle();
        assertTrue(out.contains(UpdaterRuntime.RESET_JOURNAL), () -> "output was " + out.snapshot());
    }

    @Test
    void resetStateWithTheLockBusyIsRefused() throws Exception {
        start(new ScriptedFetcher());
        Output seed = new Output();
        dispatch(seed, true, "check", "-s");
        awaitIdle();

        TransactionLock.TxnLease held = TransactionLock.tryAcquire(p.updateDir);
        try {
            Output out = new Output();
            assertEquals(Reply.STARTED, dispatch(out, true, "reset-state"));
            awaitIdle();
            assertTrue(out.contains(UpdaterRuntime.RESET_BUSY), () -> "output was " + out.snapshot());
        } finally {
            held.close();
        }
    }

    @Test
    void unknownCommandsPrintTheUsage() {
        start(new ScriptedFetcher());
        Output out = new Output();
        assertEquals(Reply.DONE, dispatch(out, true, "explode"));
        assertEquals(Reply.DONE, dispatch(out, true, "check", "-x"));
        assertEquals(Reply.DONE, dispatch(out, true, "update"));
        assertEquals(3, out.snapshot().stream().filter(UpdaterRuntime.USAGE::equals).count());
    }

    @Test
    void aFailingOutputSinkNeverEscapesDispatch() {
        start(new ScriptedFetcher());
        assertEquals(Reply.DONE, rt.dispatch(new String[] {"cancel"}, true, line -> {
            throw new IllegalStateException("sink");
        }));
    }

    @Test
    void aBadManifestFromAPlayerGoesToTheLogOnlyAndNeverToOut() throws Exception {
        ScriptedFetcher metadata = new ScriptedFetcher();
        UpdaterFacadeFixtures.answerBadManifest(metadata, UpdaterFacadeFixtures.alteredManifest(43));
        start(metadata);

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, false, "check", "-s"));
        awaitIdle();

        assertEquals(List.of(), out.snapshot(), () -> "output was " + out.snapshot());
        assertEquals(1, p.log.lines.stream()
                .filter(l -> l.startsWith("WARN: ") && l.contains("was rejected")).count(),
                () -> "log was " + p.log.lines);
        assertTrue(p.log.lines.stream().anyMatch(l -> l.contains("manifest document rejected")),
                "the diagnostic text stays in the log");
    }

    @Test
    void aBadManifestAnswersTheConsoleOnlyThroughTheLog() throws Exception {
        ScriptedFetcher metadata = new ScriptedFetcher();
        UpdaterFacadeFixtures.answerBadManifest(metadata, UpdaterFacadeFixtures.alteredManifest(43));
        start(metadata);

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true, "check", "-s"));
        awaitIdle();

        assertEquals(List.of(), out.snapshot(), () -> "output was " + out.snapshot());
        assertTrue(p.log.lines.contains("WARN: release manifest from host modrinth was rejected"),
                () -> "log was " + p.log.lines);
    }

    @Test
    void aSilentAnnouncementFromAPlayerIsLoggedAndAnsweredExactlyOnce() throws Exception {
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        start(metadata);

        Output out = new Output();
        String notice = "a new QUICraft version is available: " + UpdaterFacadeFixtures.NEWER;
        assertEquals(Reply.STARTED, dispatch(out, false, "check", "-s"));
        awaitIdle();

        assertEquals(List.of(notice), out.snapshot(), () -> "output was " + out.snapshot());
        List<String> announcement = p.log.lines.stream().filter(l -> l.contains(notice)).toList();
        assertEquals(List.of("INFO: " + notice), announcement,
                () -> "the announcement is logged once at INFO, log was " + p.log.lines);
    }

    @Test
    void aSilentHostFailureIsOneDebugLineAndNothingForTheInvoker() throws Exception {
        start(new ScriptedFetcher());
        awaitIdle();
        // A state file that no longer parses makes the next check fail as a host failure.
        Files.writeString(p.updateDir.resolve(StateStore.STATE), "not json");

        Output silent = new Output();
        assertEquals(Reply.STARTED, dispatch(silent, false, "check", "-s"));
        awaitIdle();
        assertEquals(List.of(), silent.snapshot(), () -> "output was " + silent.snapshot());
        assertEquals(1, p.log.lines.stream().filter(l -> l.startsWith("DEBUG: update check failed (")).count(),
                () -> "log was " + p.log.lines);
        assertEquals(0, p.log.lines.stream().filter(l -> l.startsWith("WARN: update check failed")).count());

        Output verbose = new Output();
        assertEquals(Reply.STARTED, dispatch(verbose, true, "check"));
        awaitIdle();
        assertTrue(verbose.snapshot().stream().anyMatch(l -> l.startsWith("update check failed (")),
                () -> "output was " + verbose.snapshot());
    }

    @Test
    void shutdownDuringAResetCancelsItsToken() throws Exception {
        ScriptedFetcher fetcher = new ScriptedFetcher();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fetcher.onFetch = uri -> {
            entered.countDown();
            try {
                release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        start(fetcher);
        assertEquals(Reply.STARTED, dispatch(new Output(), true, "reset-state"));
        assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        MutableCancelToken token = resetTokenOf(rt);
        assertFalse(token.isCancelled());
        rt.shutdown();
        assertTrue(token.isCancelled(), "shutdown cancels the running reset");
        release.countDown();
    }

    private static MutableCancelToken resetTokenOf(UpdaterRuntime runtime) throws Exception {
        Field field = UpdaterRuntime.class.getDeclaredField("resetToken");
        field.setAccessible(true);
        MutableCancelToken token = (MutableCancelToken) field.get(runtime);
        assertNotNull(token, "a running reset holds a token");
        return token;
    }

    @Test
    void aResetThatPublishesItsTokenAfterShutdownStartsTouchesNoState() throws Exception {
        ScriptedFetcher fetcher = new ScriptedFetcher();
        AtomicInteger fetches = new AtomicInteger();
        fetcher.onFetch = uri -> fetches.incrementAndGet();
        UpdaterFacadeFixtures.QueuedExecutor executor = new UpdaterFacadeFixtures.QueuedExecutor();
        p.existedBeforeStart = false;
        rt = UpdaterRuntime.start(p, new UpdaterRuntime.Seams(fetcher, PipelineFixtures.CLOCK,
                DurabilityOps.system(), factory -> executor, log -> UpdaterFacadeFixtures.testHosts(),
                PipelineFixtures.ENDPOINTS));

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true, "reset-state"));
        assertEquals(1, executor.queuedCount(), "the reset waits for the test");

        // shutdown runs to its end while the reset has published no token, so there is none to cancel.
        rt.shutdown();
        executor.runQueued();

        assertEquals(0, fetches.get(), "a reset that sees the stopping flag never refetches");
        assertFalse(out.contains("state.json rebuilt"), () -> "output was " + out.snapshot());
        assertTrue(out.contains(UpdaterRuntime.STOPPING_TEXT), () -> "output was " + out.snapshot());

        TransactionLock.TxnLease lease = TransactionLock.tryAcquire(p.updateDir);
        lease.close();
    }

    @Test
    void aScheduledTickInVerboseModeLogsEveryResultAtInfo() throws Exception {
        Files.writeString(p.configFile, "update.check-silent=false\n");
        start(new ScriptedFetcher());
        rt.scheduledTick();
        assertTrue(p.log.lines.contains("INFO: no update"), () -> "log was " + p.log.lines);
    }

    @Test
    void aScheduledTickInSilentModeLogsNothingForNoUpdate() throws Exception {
        start(new ScriptedFetcher());
        rt.scheduledTick();
        assertFalse(p.log.lines.stream().anyMatch(l -> l.contains("no update")),
                () -> "log was " + p.log.lines);
    }

    @Test
    void aScheduledTickIsSkippedWhileACommandRunsAndTheNextTickRuns() throws Exception {
        ScriptedFetcher fetcher = new ScriptedFetcher();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        fetcher.onFetch = uri -> {
            fetches.incrementAndGet();
            entered.countDown();
            try {
                release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        start(fetcher);
        assertEquals(Reply.STARTED, dispatch(new Output(), true, "check"));
        assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        int before = fetches.get();
        rt.scheduledTick();
        assertEquals(before, fetches.get(), "the busy tick did not run a check");

        release.countDown();
        awaitIdle();
        int afterCommand = fetches.get();
        rt.scheduledTick();
        assertTrue(fetches.get() > afterCommand, "the next tick runs once the worker is free");
    }

    @Test
    void aCommandDispatchedAfterShutdownStartsIsRefusedAndNeverRuns() throws Exception {
        start(new ScriptedFetcher());
        rt.shutdown();

        Output out = new Output();
        assertEquals(Reply.DONE, dispatch(out, true, "check"));
        assertTrue(out.contains(UpdaterRuntime.STOPPING_TEXT), () -> "output was " + out.snapshot());
        assertTrue(rt.awaitIdle(Duration.ofSeconds(1)), "no work was started");
    }

    @Test
    void shutdownDuringAnActiveTransactionLeavesNoLockHeld() throws Exception {
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        TransactionFixtures.ArtifactFetcher fetcher = new TransactionFixtures.ArtifactFetcher(metadata, newJar);
        CountDownLatch entered = new CountDownLatch(1);
        fetcher.beforeDownload = () -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        start(fetcher);
        assertEquals(Reply.STARTED, dispatch(new Output(), true));
        assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        rt.shutdown();

        TransactionLock.TxnLease lease = TransactionLock.tryAcquire(p.updateDir);
        lease.close();
        assertTrue(new StateStore(p.updateDir).load().isPresent(), "the state store is still usable");
    }

    // ---- disclosure: a non-console invoker is a player with the admin permission -----------------

    /**
     * Makes the transaction's first live-jar discovery fail with a message that carries the update
     * directory path, which is one of the leaks the filter must hold back.
     */
    private void failTheNextDiscovery() {
        FixedDiscovery discovery = (FixedDiscovery) p.discovery;
        discovery.ioFailure = new IOException("cannot read the live jar: " + p.updateDir);
    }

    @Test
    void aFailingUpdateShowsAPlayerTheFixedLineAndKeepsTheFullLineInTheLog() throws Exception {
        start(new ScriptedFetcher());
        failTheNextDiscovery();

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, false));
        awaitIdle();

        assertEquals(List.of(UpdaterRuntime.REDACTED_TEXT), out.snapshot(),
                () -> "output was " + out.snapshot());
        assertTrue(p.log.lines.stream()
                        .anyMatch(l -> l.startsWith("INFO: " + UpdaterRuntime.OUTPUT_LOG_PREFIX)
                                && l.contains("cannot read the live jar: " + p.updateDir)),
                () -> "log was " + p.log.lines);
    }

    @Test
    void theSameFailingUpdateReachesTheConsoleInFull() throws Exception {
        start(new ScriptedFetcher());
        failTheNextDiscovery();

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true));
        awaitIdle();

        assertTrue(out.contains("cannot read the live jar: " + p.updateDir),
                () -> "output was " + out.snapshot());
        assertTrue(out.contains("update finished: FAILED"), () -> "output was " + out.snapshot());
        assertFalse(out.contains(UpdaterRuntime.REDACTED_TEXT), () -> "output was " + out.snapshot());
    }

    @Test
    void aPlayerReadsTheNoUpdateRowUnchanged() throws Exception {
        start(new ScriptedFetcher());
        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, false, "check"));
        awaitIdle();

        List<String> lines = out.snapshot();
        assertEquals("no update", lines.get(0), () -> "output was " + lines);
        assertFalse(lines.stream().anyMatch(l -> l.contains("fetch failed")),
                () -> "the diagnostics are held back, output was " + lines);
    }

    @Test
    void aPlayerReadsTheOfferRowUnchanged() throws Exception {
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        start(metadata);

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, false, "check"));
        awaitIdle();

        assertTrue(out.contains("update available: " + UpdaterFacadeFixtures.NEWER),
                () -> "output was " + out.snapshot());
    }

    @Test
    void aPlayerReadsTheUsageRowUnchanged() {
        start(new ScriptedFetcher());
        Output out = new Output();
        assertEquals(Reply.DONE, dispatch(out, false, "explode"));

        assertEquals(List.of(UpdaterRuntime.USAGE), out.snapshot(), () -> "output was " + out.snapshot());
    }

    @Test
    void aVersionRowAdmitsOnlyAVersionAndNeverAPath() {
        assertTrue(UpdaterRuntime.visibleToPlayer("update available: " + UpdaterFacadeFixtures.NEWER));
        assertTrue(UpdaterRuntime.visibleToPlayer(
                "a new QUICraft version is available: " + UpdaterFacadeFixtures.NEWER));
        assertFalse(UpdaterRuntime.visibleToPlayer("update available: " + p.updateDir));
        assertFalse(UpdaterRuntime.visibleToPlayer("a new QUICraft version is available: " + p.updateDir));
        assertFalse(UpdaterRuntime.visibleToPlayer("no update: " + p.updateDir));
        assertFalse(UpdaterRuntime.visibleToPlayer("update check failed (java.io.IOException)"));
    }
}
