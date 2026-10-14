// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.FakePlatform;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.Output;
import rs.sudoe.quicraft.updater.UpdaterRuntime.Reply;

/** Init order, refusal reasons, disclosure and the scheduler seam of {@link UpdaterRuntime#start}. */
class UpdaterRuntimeInitTest {

    @TempDir
    Path tmp;

    private FakePlatform p;
    private final List<UpdaterUnderTest> started = new ArrayList<>();

    /** A started runtime, shut down after the test. */
    private record UpdaterUnderTest(UpdaterRuntime runtime) {
    }

    @BeforeEach
    void setUp() throws Exception {
        p = new FakePlatform();
        p.updateDir = tmp.resolve("upd");
        p.configFile = tmp.resolve("server.properties");
        p.existedBeforeStart = false;
        p.discovery = UpdaterFacadeFixtures.discoveryFor(
                UpdaterFacadeFixtures.writeLive(tmp.resolve("live"),
                        UpdaterFacadeFixtures.liveJar(UpdaterFacadeFixtures.RUNNING),
                        UpdaterFacadeFixtures.RUNNING));
    }

    @AfterEach
    void tearDown() {
        for (UpdaterUnderTest u : started) {
            u.runtime().shutdown();
        }
    }

    private UpdaterRuntime start(Optional<ReleaseHosts> hosts) {
        return start(hosts, UpdaterFacadeFixtures.realExecutors());
    }

    private UpdaterRuntime start(Optional<ReleaseHosts> hosts,
            java.util.function.Function<java.util.concurrent.ThreadFactory,
                    java.util.concurrent.ScheduledExecutorService> executors) {
        UpdaterRuntime rt = UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(
                new PipelineFixtures.ScriptedFetcher(), executors, hosts));
        started.add(new UpdaterUnderTest(rt));
        return rt;
    }

    private static String disabledLine(String reason) {
        return "updater disabled: " + reason;
    }

    private static Output check(UpdaterRuntime rt) {
        Output out = new Output();
        rt.dispatch(new String[] {"check"}, true, out::add);
        return out;
    }

    private void assertDisabledWith(UpdaterRuntime rt, String reason) {
        Output out = check(rt);
        assertTrue(out.contains(disabledLine(reason)), () -> "output was " + out.snapshot());
        assertTrue(p.log.warnings().stream().anyMatch(w -> w.contains(disabledLine(reason))),
                () -> "log was " + p.log.lines);
    }

    private void writeConfig(String text) throws IOException {
        Files.writeString(p.configFile, text);
    }

    @Test
    void healthyStartIsEnabledAndAnswersChecks() {
        UpdaterRuntime rt = start(UpdaterFacadeFixtures.testHosts());
        Output out = new Output();
        assertEquals(Reply.STARTED, rt.dispatch(new String[] {"check", "-s"}, false, out::add));
    }

    @Test
    void missingTrustFileDisablesTheUpdater() throws Exception {
        p.discovery = UpdaterFacadeFixtures.discoveryFor(UpdaterFacadeFixtures.writeLive(tmp.resolve("live"),
                UpdaterFacadeFixtures.jarWith(UpdaterFacadeFixtures.buildJson(UpdaterFacadeFixtures.PLATFORM,
                        UpdaterFacadeFixtures.RUNNING), null), UpdaterFacadeFixtures.RUNNING));
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), "live jar metadata is unusable: "
                + OwnJarResources.MISSING);
    }

    @Test
    void duplicateBuildEntryDisablesTheUpdater() throws Exception {
        p.discovery = UpdaterFacadeFixtures.discoveryFor(UpdaterFacadeFixtures.writeLive(tmp.resolve("live"),
                UpdaterFacadeFixtures.jarWithDuplicateBuild(UpdaterFacadeFixtures.buildJson(
                        UpdaterFacadeFixtures.PLATFORM, UpdaterFacadeFixtures.RUNNING),
                        SignedFixtures.trustJson()), UpdaterFacadeFixtures.RUNNING));
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), "live jar metadata is unusable: "
                + OwnJarResources.DUPLICATE);
    }

    @Test
    void developmentBuildDisablesTheUpdater() throws Exception {
        p.discovery = UpdaterFacadeFixtures.discoveryFor(UpdaterFacadeFixtures.writeLive(tmp.resolve("live"),
                UpdaterFacadeFixtures.jarWith(UpdaterFacadeFixtures.buildJson(UpdaterFacadeFixtures.PLATFORM,
                        "0.3.0-SNAPSHOT"), SignedFixtures.trustJson()), "0.3.0"));
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), UpdaterRuntime.REASON_DEV_BUILD);
    }

    @Test
    void platformMismatchDisablesTheUpdater() {
        p.platform = "velocity";
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), UpdaterRuntime.REASON_PLATFORM);
    }

    @Test
    void buildVersionDifferentFromTheInstalledJarDisablesTheUpdater() throws Exception {
        p.discovery = UpdaterFacadeFixtures.discoveryFor(UpdaterFacadeFixtures.writeLive(tmp.resolve("live"),
                UpdaterFacadeFixtures.liveJar(UpdaterFacadeFixtures.RUNNING), "0.9.0"));
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), UpdaterRuntime.REASON_VERSION);
    }

    @Test
    void discoveryFailureDisablesTheUpdater() {
        UpdaterFacadeFixtures.FixedDiscovery d = new UpdaterFacadeFixtures.FixedDiscovery();
        d.ioFailure = new IOException("gone");
        p.discovery = d;
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), UpdaterRuntime.REASON_DISCOVERY);
    }

    @Test
    void runtimeFailureInsideDiscoveryNeverEscapesStart() {
        UpdaterFacadeFixtures.FixedDiscovery d = new UpdaterFacadeFixtures.FixedDiscovery();
        d.runtimeFailure = new IllegalStateException("boom");
        p.discovery = d;
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), UpdaterRuntime.REASON_INTERNAL);
    }

    @Test
    void discoveryReportingADevelopmentBuildDisablesTheUpdater() {
        UpdaterFacadeFixtures.FixedDiscovery d = new UpdaterFacadeFixtures.FixedDiscovery();
        d.runtimeFailure = new DevelopmentBuildException();
        p.discovery = d;
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), UpdaterRuntime.REASON_DEV_BUILD);
    }

    @Test
    void developmentBuildWrappedByDiscoveryIsStillNamed() {
        UpdaterFacadeFixtures.FixedDiscovery d = new UpdaterFacadeFixtures.FixedDiscovery();
        d.runtimeFailure = new IllegalStateException("wrapped", new DevelopmentBuildException());
        p.discovery = d;
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), UpdaterRuntime.REASON_DEV_BUILD);
    }

    @Test
    void storeInitialiseFailureDisablesTheUpdater() throws Exception {
        // An existing but empty directory means a state file vanished after use: never rebuilt silently.
        PipelineFixtures.privateDir(p.updateDir);
        p.existedBeforeStart = true;
        assertDisabledWith(start(UpdaterFacadeFixtures.testHosts()), UpdaterRuntime.REASON_STATE);
    }

    @Test
    void recoveryThatCannotUseTheDirectoryDisablesTheUpdater() throws Exception {
        Files.writeString(p.updateDir, "not a directory");
        UpdaterRuntime rt = start(UpdaterFacadeFixtures.testHosts());
        Output out = check(rt);
        assertTrue(out.contains("updater disabled: " + UpdaterRuntime.REASON_RECOVERY)
                || out.contains("updater disabled: " + UpdaterRuntime.REASON_STATE)
                || out.contains("updater disabled: " + UpdaterRuntime.REASON_DISCOVERY),
                () -> "output was " + out.snapshot());
        assertTrue(Files.isRegularFile(p.updateDir), "the file in the update directory's place is kept");
    }

    @Test
    void withoutHostsCommandsAnswerWithTheReasonAndNothingIsScheduled() throws Exception {
        List<Runnable> scheduled = new ArrayList<>();
        UpdaterRuntime rt = start(Optional.empty(), UpdaterFacadeFixtures.countingExecutors(scheduled));
        Output out = check(rt);
        assertTrue(out.contains(UpdaterOrigins.NOT_CONFIGURED), () -> "output was " + out.snapshot());
        assertTrue(p.log.contains("release hosts not configured in this build"));
        assertFalse(p.log.contains("update checks send"), "nothing is sent, so nothing is disclosed");
        assertFalse(Files.exists(p.updateDir.resolve(UpdaterRuntime.DISCLOSURE_MARKER)));
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void intervalOffSchedulesNoCheck() throws Exception {
        writeConfig("update.check-interval=off\n");
        List<Runnable> scheduled = new ArrayList<>();
        start(UpdaterFacadeFixtures.testHosts(), UpdaterFacadeFixtures.countingExecutors(scheduled));
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void defaultIntervalSchedulesOneHourlyCheck() {
        List<Runnable> scheduled = new ArrayList<>();
        start(UpdaterFacadeFixtures.testHosts(), UpdaterFacadeFixtures.countingExecutors(scheduled));
        assertEquals(1, scheduled.size());
    }

    @Test
    void disclosureIsPrintedOnceAcrossTwoStarts() throws Exception {
        UpdaterRuntime first = start(UpdaterFacadeFixtures.testHosts());
        first.shutdown();
        started.clear();
        p.existedBeforeStart = true;
        UpdaterRuntime second = start(UpdaterFacadeFixtures.testHosts());
        assertFalse(second == first);
        long disclosures = p.log.lines.stream().filter(l -> l.contains("update checks send")).count();
        assertEquals(1, disclosures);
        assertTrue(Files.exists(p.updateDir.resolve(UpdaterRuntime.DISCLOSURE_MARKER)));
        assertTrue(p.log.contains("github, modrinth, sudoe"));
    }

    @Test
    void shutdownStopsTheWorkerThread() throws Exception {
        UpdaterRuntime rt = start(UpdaterFacadeFixtures.testHosts());
        Output out = new Output();
        assertEquals(Reply.STARTED, rt.dispatch(new String[] {"check", "-s"}, false, out::add));
        assertTrue(rt.awaitIdle(Duration.ofSeconds(30)));
        assertTrue(workerAlive(), "the worker thread exists after a command");
        rt.shutdown();
        started.clear();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (workerAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(workerAlive(), "no quicraft-updater thread after shutdown");
    }

    @Test
    void aChangedHostSetIsDisclosedAgain() throws Exception {
        UpdaterRuntime first = start(UpdaterFacadeFixtures.testHosts());
        first.shutdown();
        started.clear();
        p.existedBeforeStart = true;
        UpdaterRuntime second = start(Optional.of(ReleaseHosts.of(
                List.of(PipelineFixtures.MOD, PipelineFixtures.GH))));
        assertFalse(second == first);
        assertEquals(2, p.log.lines.stream().filter(l -> l.contains("update checks send")).count());
        assertTrue(p.log.contains("github, modrinth"));
    }

    @Test
    void devOriginsDoNotConsumeTheProductionDisclosure() throws Exception {
        UpdaterRuntime production = start(UpdaterFacadeFixtures.testHosts());
        production.shutdown();
        started.clear();
        p.existedBeforeStart = true;
        Optional<ReleaseHosts> dev = UpdaterOrigins.resolve(
                "modrinth=https://localhost:9001,github=https://localhost:9002,sudoe=https://localhost:9003",
                p.log);
        UpdaterRuntime devRun = start(dev);
        assertFalse(devRun == production);
        assertEquals(2, p.log.lines.stream().filter(l -> l.contains("update checks send")).count());
    }

    @Test
    void theDisclosureMarkerIsOwnerOnlyOnPosix() throws Exception {
        start(UpdaterFacadeFixtures.testHosts());
        Path marker = p.updateDir.resolve(UpdaterRuntime.DISCLOSURE_MARKER);
        Assumptions.assumeTrue(marker.getFileSystem().supportedFileAttributeViews().contains("posix"));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(marker));
    }

    @Test
    void aSymlinkedDisclosureMarkerIsLoggedButNeitherReadNorWritten() throws Exception {
        Path target = tmp.resolve("elsewhere.txt");
        Files.writeString(target, "kept");
        Path marker = tmp.resolve(UpdaterRuntime.DISCLOSURE_MARKER);
        Files.createSymbolicLink(marker, target);

        UpdaterRuntime.recordDisclosure(marker, "disclosure-v1 production github\n", "update checks send", p.log);
        assertEquals(1, p.log.lines.stream().filter(l -> l.equals("INFO: update checks send")).count(),
                () -> "log was " + p.log.lines);
        assertTrue(Files.isSymbolicLink(marker), "the link is still a link");
        assertEquals("kept", Files.readString(target), "the link target is unchanged");
    }

    @Test
    void aSeamsSupplierThatFailsWithALinkageErrorGivesADisabledRuntime() {
        UpdaterRuntime rt = assertDoesNotThrow(() -> UpdaterRuntime.start(p, () -> {
            throw new NoClassDefFoundError("seams");
        }));
        started.add(new UpdaterUnderTest(rt));
        Output out = check(rt);
        assertTrue(out.contains("updater disabled: " + UpdaterRuntime.REASON_INTERNAL),
                () -> "output was " + out.snapshot());
    }

    private static boolean workerAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && UpdaterRuntime.WORKER_NAME.equals(t.getName()));
    }
}
