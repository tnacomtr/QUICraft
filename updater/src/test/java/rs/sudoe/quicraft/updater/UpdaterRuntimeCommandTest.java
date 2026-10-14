// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(Reply.BUSY, dispatch(second, true, "update"));
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

        Output second = new Output();
        assertEquals(Reply.STARTED, dispatch(second, true, "check", "-s"));
        awaitIdle();
        assertFalse(second.contains(notice), () -> "output was " + second.snapshot());
        assertEquals(1, p.notices.size(), "the same version is announced once");
    }

    @Test
    void updateStagesTheVerifiedJarOnlyAndLeavesTheLiveJarAlone() throws Exception {
        byte[] newJar = TransactionFixtures.releaseJar(UpdaterFacadeFixtures.NEWER);
        ScriptedFetcher metadata = new ScriptedFetcher();
        TransactionFixtures.serveMetadata(metadata, PipelineFixtures.revocations(),
                TransactionFixtures.manifestFor(newJar, UpdaterFacadeFixtures.NEWER));
        start(new TransactionFixtures.ArtifactFetcher(metadata, newJar));

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true, "update"));
        awaitIdle();

        assertTrue(out.contains("update finished: STAGED_ONLY"), () -> "output was " + out.snapshot());
        assertArrayEquals(liveBytes, Files.readAllBytes(live.realPath()));
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
        assertEquals(Reply.DONE, dispatch(out, true));
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
    void silentCheckWritesOnlyTheSectionSixRowsToOut() throws Exception {
        ScriptedFetcher metadata = new ScriptedFetcher();
        UpdaterFacadeFixtures.answerBadManifest(metadata, UpdaterFacadeFixtures.alteredManifest(43));
        start(metadata);

        Output out = new Output();
        assertEquals(Reply.STARTED, dispatch(out, true, "check", "-s"));
        awaitIdle();

        assertEquals(List.of("release manifest from host modrinth failed signature verification"), out.snapshot());
        assertTrue(p.log.lines.stream().anyMatch(l -> l.contains("manifest document rejected")),
                "the diagnostic text stays in the log");
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
        assertEquals(Reply.STARTED, dispatch(new Output(), true, "update"));
        assertTrue(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        rt.shutdown();

        TransactionLock.TxnLease lease = TransactionLock.tryAcquire(p.updateDir);
        lease.close();
        assertTrue(new StateStore(p.updateDir).load().isPresent(), "the state store is still usable");
    }
}
