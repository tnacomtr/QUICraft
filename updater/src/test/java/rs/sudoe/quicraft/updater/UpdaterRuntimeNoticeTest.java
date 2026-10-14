// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.PipelineFixtures.ScriptedFetcher;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.FakePlatform;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.Output;

/** Announcements (once per version, retried after a failed notice) and bad-manifest warnings (deduplicated). */
class UpdaterRuntimeNoticeTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private FakePlatform p;
    private final List<UpdaterRuntime> started = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        p = new FakePlatform();
        p.updateDir = tmp.resolve("upd");
        p.configFile = tmp.resolve("server.properties");
        LiveJar live = UpdaterFacadeFixtures.writeLive(tmp.resolve("live"),
                UpdaterFacadeFixtures.liveJar(UpdaterFacadeFixtures.RUNNING), UpdaterFacadeFixtures.RUNNING);
        p.discovery = UpdaterFacadeFixtures.discoveryFor(live);
    }

    @AfterEach
    void tearDown() {
        for (UpdaterRuntime rt : started) {
            rt.shutdown();
        }
    }

    private UpdaterRuntime start(ScriptedFetcher fetcher, boolean existed) {
        p.existedBeforeStart = existed;
        UpdaterRuntime rt = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(fetcher,
                UpdaterFacadeFixtures.realExecutors(), UpdaterFacadeFixtures.testHosts()));
        started.add(rt);
        return rt;
    }

    private static Output silentCheck(UpdaterRuntime rt) throws InterruptedException {
        Output out = new Output();
        rt.dispatch(new String[] {"check", "-s"}, true, out::add);
        assertTrue(rt.awaitIdle(WAIT), "the check did not finish");
        return out;
    }

    private long logged(String part) {
        return p.log.lines.stream().filter(l -> l.contains(part)).count();
    }

    @Test
    void aFailedNoticeIsRetriedAndDeliveredExactlyOnce() throws Exception {
        ScriptedFetcher metadata = UpdaterFacadeFixtures.newerRelease();
        UpdaterRuntime rt = start(metadata, false);

        p.noticeFailure = new IllegalStateException("admin channel down");
        Output failed = silentCheck(rt);
        assertTrue(p.notices.isEmpty(), "nothing was delivered");
        String recorded = new StateStore(p.updateDir).load().orElseThrow().state().lastAnnounced();
        assertFalse(recorded.equals(UpdaterFacadeFixtures.NEWER), "the announcement was not recorded");
        assertFalse(failed.contains("a new QUICraft version is available"), () -> failed.snapshot().toString());

        p.noticeFailure = null;
        Output retried = silentCheck(rt);
        assertEquals(1, p.notices.size());
        assertTrue(retried.contains("a new QUICraft version is available: " + UpdaterFacadeFixtures.NEWER));

        Output again = silentCheck(rt);
        assertEquals(1, p.notices.size(), "the delivered version is not announced again");
        assertFalse(again.contains("a new QUICraft version is available"));
    }

    @Test
    void aFailedNoticeIsRetriedByAScheduledTickAndDeliveredExactlyOnce() throws Exception {
        UpdaterRuntime rt = start(UpdaterFacadeFixtures.newerRelease(), false);

        p.noticeFailure = new IllegalStateException("admin channel down");
        rt.scheduledTick();
        assertTrue(rt.awaitIdle(WAIT), "the tick did not finish");
        assertTrue(p.notices.isEmpty(), "nothing was delivered by the failed tick");

        p.noticeFailure = null;
        rt.scheduledTick();
        assertTrue(rt.awaitIdle(WAIT), "the retry tick did not finish");
        assertEquals(1, p.notices.size(), "the retry tick delivers the notice");

        rt.scheduledTick();
        assertTrue(rt.awaitIdle(WAIT), "the third tick did not finish");
        assertEquals(1, p.notices.size(), "the delivered version is not announced again");
    }

    @Test
    void aSameBadManifestWarnsOnceAcrossRunsAndARestartButANewOneWarnsAgain() throws Exception {
        ScriptedFetcher metadata = new ScriptedFetcher();
        byte[] first = UpdaterFacadeFixtures.alteredManifest(43);
        UpdaterFacadeFixtures.answerBadManifest(metadata, first);
        UpdaterRuntime rt = start(metadata, false);

        Output run1 = silentCheck(rt);
        assertEquals(1, logged("was rejected"));
        assertTrue(p.log.lines.contains("WARN: release manifest from host modrinth was rejected"),
                () -> p.log.lines.toString());
        assertTrue(run1.snapshot().isEmpty(), () -> run1.snapshot().toString());

        silentCheck(rt);
        assertEquals(1, logged("was rejected"), "the same document does not warn again");

        rt.shutdown();
        started.clear();
        UpdaterRuntime restarted = start(metadata, true);
        silentCheck(restarted);
        assertEquals(1, logged("was rejected"), "the restart keeps the suppression");

        UpdaterFacadeFixtures.answerBadManifest(metadata, UpdaterFacadeFixtures.alteredManifest(44));
        silentCheck(restarted);
        assertEquals(2, logged("was rejected"), "a different document warns again");
    }

    @Test
    void theBadManifestWarningIsSuppressedWhenLogBadSignaturesIsOff() throws Exception {
        Files.writeString(p.configFile, "update.log-bad-signatures=false\n");
        ScriptedFetcher metadata = new ScriptedFetcher();
        UpdaterFacadeFixtures.answerBadManifest(metadata, UpdaterFacadeFixtures.alteredManifest(43));
        UpdaterRuntime rt = start(metadata, false);

        Output out = silentCheck(rt);
        assertEquals(0, logged("was rejected"));
        assertTrue(out.snapshot().isEmpty(), () -> out.snapshot().toString());
    }

    @Test
    void aFailingBadManifestRecordDoesNotStopTheAnnouncement() throws Exception {
        ScriptedFetcher metadata = UpdaterFacadeFixtures.newerRelease();
        ReleaseHost first = PipelineFixtures.HOSTS.hosts().get(0);
        byte[] bad = UpdaterFacadeFixtures.alteredManifest(43);
        metadata.answer(PipelineFixtures.ENDPOINTS.manifest(first), bad);
        metadata.answer(PipelineFixtures.ENDPOINTS.manifestSignature(first),
                UpdaterFacadeFixtures.wrongKeySignature(bad));

        // Refuse only the state write made by the bad-manifest record, identified by its caller on the stack. The
        // pipeline's own state writes in the same check are let through.
        AtomicBoolean armed = new AtomicBoolean(false);
        StateStore.AtomicWriteHook refuseBadRecord = (step, name) -> {
            if (armed.get() && step == StateStore.AtomicStep.TMP_WRITE_BEFORE && StateStore.STATE.equals(name)
                    && callerIsBadRecord()) {
                throw new IOException("state write refused");
            }
        };
        UpdaterRuntime.Seams seams = new UpdaterRuntime.Seams(metadata, PipelineFixtures.CLOCK,
                DurabilityOps.system(), UpdaterFacadeFixtures.realExecutors(), log -> UpdaterFacadeFixtures.testHosts(),
                PipelineFixtures.ENDPOINTS,
                dir -> new StateStore(dir, () -> { }, directory -> directory.force(true), refuseBadRecord));
        p.existedBeforeStart = false;
        UpdaterRuntime rt = UpdaterRuntime.start(p, seams);
        started.add(rt);

        armed.set(true);
        Output out = silentCheck(rt);
        assertEquals(1, logged("was rejected"), "the rejected manifest is still reported");
        assertEquals(1, logged("DEBUG: bad-manifest record could not be saved (java.io.IOException)"));
        assertEquals(1, p.notices.size(), "the announcement still runs");
        assertTrue(out.contains("a new QUICraft version is available: " + UpdaterFacadeFixtures.NEWER),
                () -> out.snapshot().toString());
    }

    private static boolean callerIsBadRecord() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("reportBadManifests".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aFailedLastAnnouncedCommitStillLogsTheDeliveredNoticeAndRetries() throws Exception {
        ScriptedFetcher metadata = UpdaterFacadeFixtures.newerRelease();

        // Refuse only the state write made to record the announcement, identified by its caller on the
        // stack. The pipeline's own state writes in the same check are let through.
        AtomicBoolean armed = new AtomicBoolean(false);
        StateStore.AtomicWriteHook refuseAnnouncementRecord = (step, name) -> {
            if (armed.get() && step == StateStore.AtomicStep.TMP_WRITE_BEFORE && StateStore.STATE.equals(name)
                    && callerIsAnnounceIfNew()) {
                throw new IOException("state write refused");
            }
        };
        UpdaterRuntime.Seams seams = new UpdaterRuntime.Seams(metadata, PipelineFixtures.CLOCK,
                DurabilityOps.system(), UpdaterFacadeFixtures.realExecutors(),
                log -> UpdaterFacadeFixtures.testHosts(), PipelineFixtures.ENDPOINTS,
                dir -> new StateStore(dir, () -> { }, directory -> directory.force(true),
                        refuseAnnouncementRecord));
        p.existedBeforeStart = false;
        UpdaterRuntime rt = UpdaterRuntime.start(p, seams);
        started.add(rt);

        armed.set(true);
        String notice = "a new QUICraft version is available: " + UpdaterFacadeFixtures.NEWER;
        Output out = silentCheck(rt);
        assertEquals(1, p.notices.size(), "the admins were told before the write was tried");
        assertEquals(1, logged(notice), () -> "the delivered notice is logged, log was " + p.log.lines);
        assertTrue(out.contains(notice), () -> "output was " + out.snapshot());
        String recorded = new StateStore(p.updateDir).load().orElseThrow().state().lastAnnounced();
        assertFalse(recorded.equals(UpdaterFacadeFixtures.NEWER), "a failed write records nothing");

        armed.set(false);
        Output retried = silentCheck(rt);
        assertEquals(2, p.notices.size(), "the next check re-announces the version");
        assertEquals(2, logged(notice), "the retry logs the notice too");
        assertTrue(retried.contains(notice), () -> "output was " + retried.snapshot());
        assertEquals(UpdaterFacadeFixtures.NEWER,
                new StateStore(p.updateDir).load().orElseThrow().state().lastAnnounced(),
                "a successful write records the version");
    }

    private static boolean callerIsAnnounceIfNew() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("announceIfNew".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }
}
