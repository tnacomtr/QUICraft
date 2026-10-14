// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import rs.sudoe.quicraft.updater.UpdaterRuntime;

/** Platform values, notice delivery and the fail-safe start of the updater (docs/updater.md 6, 7). */
class VelocityUpdaterWiringTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(VelocityUpdaterWiringTest.class);

    private static VelocityUpdaterPlatform platform(Path dataDir, Path pluginsDir,
            VelocityUpdateNotifier notifier) {
        return new VelocityUpdaterPlatform("4.2", LOG, dataDir, Optional::empty, pluginsDir, notifier);
    }

    @Test
    void gameTargetIsTheProxyMajorMinor() {
        assertEquals("4.2", VelocityUpdaterPlatform.targetOf("4.2.0-30"));
        assertEquals("4.2", VelocityUpdaterPlatform.targetOf("4.2.0-SNAPSHOT"));
        assertEquals("", VelocityUpdaterPlatform.targetOf("unknown"));
        assertEquals("", VelocityUpdaterPlatform.targetOf(null));
    }

    @Test
    void updateDirExistedIsReadBeforeAnythingCreatesIt(@TempDir Path root) throws Exception {
        Path data = root.resolve("quicraft");
        Files.createDirectories(data.resolve("update"));
        assertTrue(platform(data, root, notifier()).updateDirExistedBeforeStart());

        Path fresh = root.resolve("fresh");
        assertFalse(platform(fresh, root, notifier()).updateDirExistedBeforeStart());
        assertFalse(Files.exists(fresh.resolve("update")), "the platform must not create the update directory");
    }

    @Test
    void pathsAreUnderTheDataDirectory(@TempDir Path root) {
        VelocityUpdaterPlatform p = platform(root, root, notifier());
        assertEquals(root.resolve("update"), p.updateDir());
        assertEquals(root.resolve("updater.properties"), p.configFile());
        assertEquals("velocity", p.platform());
        assertEquals("4.2", p.gameTarget());
    }

    @Test
    void healthFollowsTheInitVerdict(@TempDir Path root) throws Exception {
        VelocityUpdaterPlatform p = platform(root, root, notifier());
        p.completeInit(true);
        assertTrue(p.health().awaitHealthy(Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS));

        VelocityUpdaterPlatform failed = platform(root, root, notifier());
        failed.completeInit(false);
        assertFalse(failed.health().awaitHealthy(Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void healthTimesOutToUnhealthy(@TempDir Path root) throws Exception {
        VelocityUpdaterPlatform p = platform(root, root, notifier());
        assertFalse(p.health().awaitHealthy(Duration.ofMillis(50)).toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void adminGetsTheNoticeOnceAndALaterJoinerGetsTheRememberedOne() {
        UpdaterFakes.Recorder onlineOut = new UpdaterFakes.Recorder();
        UpdaterFakes.Recorder laterOut = new UpdaterFakes.Recorder();
        UpdaterFakes.Recorder plainOut = new UpdaterFakes.Recorder();
        Player online = UpdaterFakes.notifiedPlayer(onlineOut, Set.of(VelocityUpdateNotifier.NOTIFY_PERMISSION),
                UUID.randomUUID());
        Player plain = UpdaterFakes.notifiedPlayer(plainOut, Set.of(), UUID.randomUUID());
        Player later = UpdaterFakes.notifiedPlayer(laterOut, Set.of(VelocityUpdateNotifier.NOTIFY_PERMISSION),
                UUID.randomUUID());

        List<Player> roster = new java.util.ArrayList<>(List.of(online, plain));
        VelocityUpdateNotifier notifier = new VelocityUpdateNotifier(() -> roster);

        notifier.announce("update 0.1.0 is available");
        assertEquals(List.of("update 0.1.0 is available"), onlineOut.lines);
        assertTrue(plainOut.lines.isEmpty());

        roster.add(later);
        notifier.onJoin(later);
        notifier.onJoin(later);
        assertEquals(List.of("update 0.1.0 is available"), laterOut.lines);

        notifier.onJoin(online);
        assertEquals(1, onlineOut.lines.size(), "an online admin is not repeated on a second join check");

        notifier.announce("update 0.2.0 is available");
        assertEquals(List.of("update 0.1.0 is available", "update 0.2.0 is available"), laterOut.lines);
    }

    @Test
    void noNoticeBeforeTheFirstAnnouncement() {
        UpdaterFakes.Recorder out = new UpdaterFakes.Recorder();
        Player admin = UpdaterFakes.notifiedPlayer(out, Set.of(VelocityUpdateNotifier.NOTIFY_PERMISSION),
                UUID.randomUUID());
        new VelocityUpdateNotifier(() -> List.of()).onJoin(admin);
        assertTrue(out.lines.isEmpty());
    }

    private static VelocityUpdateNotifier notifier() {
        return new VelocityUpdateNotifier(List::of);
    }

    @Test
    void startReturnsTheRuntimeWhenItStarts(@TempDir Path root) {
        UpdaterRuntime runtime = UpdaterFakes.disabledRuntime(root);
        assertNotNull(QuicraftVelocity.runUpdaterStart(null, LOG, p -> runtime));
    }

    @Test
    void aRuntimeExceptionFromTheStarterLeavesTheInitGoing() {
        assertNull(QuicraftVelocity.runUpdaterStart(null, LOG, p -> {
            throw new IllegalStateException("boom");
        }));
    }

    @Test
    void anErrorFromTheStarterLeavesTheInitGoing() {
        assertNull(QuicraftVelocity.runUpdaterStart(null, LOG, p -> {
            throw new LinkageError("fake linkage failure");
        }));
    }

    @Test
    void aVirtualMachineErrorIsNotSwallowed() {
        assertThrows(OutOfMemoryError.class, () -> QuicraftVelocity.runUpdaterStart(null, LOG, p -> {
            throw new OutOfMemoryError("fake");
        }));
    }

}
