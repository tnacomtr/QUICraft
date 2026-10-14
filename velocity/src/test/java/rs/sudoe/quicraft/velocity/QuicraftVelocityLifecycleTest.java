// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The plugin's init/shutdown guards and the health verdict (docs/updater.md 6, 12.13). */
class QuicraftVelocityLifecycleTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(QuicraftVelocityLifecycleTest.class);

    private static boolean healthy(VelocityUpdaterPlatform platform, Duration timeout) throws Exception {
        return platform.health().awaitHealthy(timeout).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void initFailureLeavesHealthFalseEvenAfterTheLateHandler(@TempDir Path root) throws Exception {
        // A regular file where the data directory must be: Settings.load fails, so start() throws.
        Path notADirectory = Files.writeString(root.resolve("data"), "not a directory");
        QuicraftVelocity plugin = new QuicraftVelocity(UpdaterFakes.proxyServer(), LOG, notADirectory);

        plugin.onProxyInitialize(null);

        assertFalse(plugin.quicInitOk, "a caught init failure must be recorded");
        assertNotNull(plugin.updaterPlatform, "the updater must still start");
        plugin.onProxyInitializeLate(null);
        assertFalse(healthy(plugin.updaterPlatform, Duration.ofSeconds(5)));
    }

    @Test
    void healthIsTrueOnlyAfterTheLateHandlerRuns(@TempDir Path root) throws Exception {
        // QUIC disabled by config: start() returns normally, so the flag is a real success.
        Path data = Files.createDirectories(root.resolve("data"));
        Files.writeString(data.resolve("config.properties"), "enabled=false\n");
        QuicraftVelocity plugin = new QuicraftVelocity(UpdaterFakes.proxyServer(), LOG, data);

        plugin.onProxyInitialize(null);
        assertTrue(plugin.quicInitOk);
        assertNotNull(plugin.updaterPlatform);
        assertFalse(healthy(plugin.updaterPlatform, Duration.ofMillis(50)),
                "the verdict must wait for the proxy's initialize handling to finish");
        plugin.onProxyInitializeLate(null);
        assertTrue(healthy(plugin.updaterPlatform, Duration.ofSeconds(5)));
    }

    @Test
    void theHealthHandlerIsTheLastProxyInitializeSubscriber() throws Exception {
        Method init = QuicraftVelocity.class.getMethod("onProxyInitialize", ProxyInitializeEvent.class);
        Method late = QuicraftVelocity.class.getMethod("onProxyInitializeLate", ProxyInitializeEvent.class);
        short initPriority = init.getAnnotation(Subscribe.class).priority();
        short latePriority = late.getAnnotation(Subscribe.class).priority();

        assertTrue(latePriority < initPriority, "the health handler must run after the init handler");
        assertEquals(Short.MIN_VALUE, latePriority);
    }

    @Test
    void aFailedCleanupDoesNotStopTheUpdaterStart() {
        List<String> events = new ArrayList<>();

        boolean ok = QuicraftVelocity.startQuicThen(LOG, () -> {
            events.add("start");
            throw new IllegalStateException("bind failed");
        }, () -> {
            events.add("cleanup");
            throw new IllegalStateException("cleanup failed");
        });

        assertFalse(ok);
        assertEquals(List.of("start", "cleanup"), events);
    }

    @Test
    void aSuccessfulStartRunsNoCleanup() {
        List<String> events = new ArrayList<>();

        boolean ok = QuicraftVelocity.startQuicThen(LOG, () -> {
            events.add("start");
            return true;
        }, () -> events.add("cleanup"));

        assertTrue(ok);
        assertEquals(List.of("start"), events);
    }

    @Test
    void aFailedBindIsReportedAsNotStarted() {
        List<String> events = new ArrayList<>();

        // start() handles the UDP bind failure itself, so it reports false instead of throwing.
        boolean ok = QuicraftVelocity.startQuicThen(LOG, () -> {
            events.add("start");
            return false;
        }, () -> events.add("cleanup"));

        assertFalse(ok);
        assertEquals(List.of("start"), events);
    }

    @Test
    void theUpdaterStopsEvenWhenTheQuicStopThrows() {
        List<String> events = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> QuicraftVelocity.stopQuicThen(() -> {
            events.add("quic");
            throw new IllegalStateException("stop failed");
        }, () -> events.add("updater")));

        assertEquals(List.of("quic", "updater"), events);
    }

    @Test
    void theUpdaterStopsNormally() {
        List<String> events = new ArrayList<>();
        QuicraftVelocity.stopQuicThen(() -> events.add("quic"), () -> events.add("updater"));
        assertEquals(List.of("quic", "updater"), events);
    }
}
