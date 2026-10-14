// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    void aSameBadManifestWarnsOnceAcrossRunsAndARestartButANewOneWarnsAgain() throws Exception {
        ScriptedFetcher metadata = new ScriptedFetcher();
        byte[] first = UpdaterFacadeFixtures.alteredManifest(43);
        UpdaterFacadeFixtures.answerBadManifest(metadata, first);
        UpdaterRuntime rt = start(metadata, false);

        Output run1 = silentCheck(rt);
        assertEquals(1, logged("failed signature verification"));
        assertTrue(run1.contains("release manifest from host modrinth failed signature verification"),
                () -> run1.snapshot().toString());

        silentCheck(rt);
        assertEquals(1, logged("failed signature verification"), "the same document does not warn again");

        rt.shutdown();
        started.clear();
        UpdaterRuntime restarted = start(metadata, true);
        silentCheck(restarted);
        assertEquals(1, logged("failed signature verification"), "the restart keeps the suppression");

        UpdaterFacadeFixtures.answerBadManifest(metadata, UpdaterFacadeFixtures.alteredManifest(44));
        silentCheck(restarted);
        assertEquals(2, logged("failed signature verification"), "a different document warns again");
    }

    @Test
    void theBadManifestWarningIsSuppressedWhenLogBadSignaturesIsOff() throws Exception {
        Files.writeString(p.configFile, "update.log-bad-signatures=false\n");
        ScriptedFetcher metadata = new ScriptedFetcher();
        UpdaterFacadeFixtures.answerBadManifest(metadata, UpdaterFacadeFixtures.alteredManifest(43));
        UpdaterRuntime rt = start(metadata, false);

        Output out = silentCheck(rt);
        assertEquals(0, logged("failed signature verification"));
        assertTrue(out.snapshot().isEmpty(), () -> out.snapshot().toString());
    }
}
