// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.FakePlatform;
import rs.sudoe.quicraft.updater.UpdaterFacadeFixtures.Output;
import rs.sudoe.quicraft.updater.UpdaterRuntime.Reply;

/**
 * Fault injection for {@link UpdaterRuntime}: collaborators that throw runtime exceptions and
 * {@link Error}s (not {@link VirtualMachineError}) must never escape start, dispatch or shutdown.
 */
class UpdaterRuntimeFaultTest {

    @TempDir
    Path tmp;

    private FakePlatform p;

    @BeforeEach
    void setUp() throws Exception {
        p = new FakePlatform();
        p.updateDir = tmp.resolve("upd");
        p.configFile = tmp.resolve("server.properties");
        LiveJar live = UpdaterFacadeFixtures.writeLive(tmp.resolve("live"),
                UpdaterFacadeFixtures.liveJar(UpdaterFacadeFixtures.RUNNING), UpdaterFacadeFixtures.RUNNING);
        p.discovery = UpdaterFacadeFixtures.discoveryFor(live);
    }

    private static SourceFetcher throwingFetcher() {
        return new SourceFetcher() {
            @Override
            public FetchedBytes fetch(FetchRequest request, CancelToken cancel) {
                throw new AssertionError("fetch");
            }

            @Override
            public long download(FetchRequest request, WritableByteChannel destination, CancelToken cancel) {
                throw new AssertionError("download");
            }
        };
    }

    private UpdaterRuntime start(SourceFetcher fetcher) {
        return UpdaterRuntime.start(p, UpdaterFacadeFixtures.seams(fetcher,
                UpdaterFacadeFixtures.realExecutors(), UpdaterFacadeFixtures.testHosts()));
    }

    @Test
    void anErrorFromTheFetcherEndsOnlyTheCommandAndIsReported() throws Exception {
        UpdaterRuntime rt = start(throwingFetcher());
        try {
            Output out = new Output();
            assertEquals(Reply.STARTED, rt.dispatch(new String[] {"check"}, true, out::add));
            assertTrue(rt.awaitIdle(Duration.ofSeconds(30)));
            assertTrue(out.contains("updater command failed"), () -> "output was " + out.snapshot());
            assertEquals(Reply.STARTED, rt.dispatch(new String[] {"check", "-s"}, true, line -> { }),
                    "the worker is usable again after a failed command");
        } finally {
            rt.shutdown();
        }
    }

    @Test
    void aLogSinkThatThrowsNeverEscapesStartDispatchOrShutdown() {
        p.log.failWith = new IllegalStateException("log down");
        UpdaterRuntime rt = assertDoesNotThrow(() -> start(new PipelineFixtures.ScriptedFetcher()));
        assertDoesNotThrow(() -> rt.dispatch(new String[] {"cancel"}, true, line -> { }));
        assertDoesNotThrow(() -> rt.dispatch(new String[] {"check"}, true, line -> { }));
        assertDoesNotThrow(rt::shutdown);
    }

    @Test
    void anErrorFromDiscoveryDisablesTheUpdaterWithoutEscaping() {
        p.discovery = () -> {
            throw new AssertionError("discover");
        };
        UpdaterRuntime rt = assertDoesNotThrow(() -> start(new PipelineFixtures.ScriptedFetcher()));
        Output out = new Output();
        assertDoesNotThrow(() -> rt.dispatch(new String[] {"check"}, true, out::add));
        assertTrue(out.contains("updater disabled: " + UpdaterRuntime.REASON_INTERNAL),
                () -> "output was " + out.snapshot());
        assertDoesNotThrow(rt::shutdown);
    }

    @Test
    void shutdownIsIdempotentAndLaterCommandsAreRefused() {
        UpdaterRuntime rt = start(new PipelineFixtures.ScriptedFetcher());
        assertDoesNotThrow(rt::shutdown);
        assertDoesNotThrow(rt::shutdown);
        Output out = new Output();
        assertEquals(Reply.DONE, rt.dispatch(new String[] {"check"}, true, out::add));
        assertTrue(out.contains(UpdaterRuntime.STOPPING_TEXT), () -> "output was " + out.snapshot());
    }

    @Test
    void shutdownOfADisabledRuntimeIsSafe() {
        p.platform = "velocity";
        p.discovery = () -> {
            throw new AssertionError("discover");
        };
        UpdaterRuntime rt = start(new PipelineFixtures.ScriptedFetcher());
        assertDoesNotThrow(rt::shutdown);
        assertDoesNotThrow(rt::shutdown);
    }
}
