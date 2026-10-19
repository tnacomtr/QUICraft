// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.gametest.mixin.CausalLoginContextAccess;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;

/** Runtime proof for the three real producer wrappers on offline TCP login. */
public final class CausalLoginGameTest implements FabricClientGameTest {
    private static final String[] FAULTS = {null, "causal login spawn", "causal login ready", "causal place new player"};

    @Override
    public void runTest(ClientGameTestContext context) {
        Instant started = Instant.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String gameVersion = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        Path dir = outputDir();
        Path summary = dir.resolve("causal-login-summary.txt");
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(summary);
            QuicraftClient.get().setMode(Mode.TCP_ONLY);
            for (String fault : FAULTS) {
                CausalLoginProbe.Attempt attempt = CausalLoginProbe.arm(fault);
                try {
                    if (fault == null) Hooks.setFaultInjection();
                    else Hooks.setFaultInjection(fault);
                    try (TestDedicatedServerContext server = GameTests.server(context)) {
                        check(GameTests.serverListPing(context, GameTests.address(server)), "status ping for " + fault);
                        try (var connection = server.connect()) {
                            GameTests.waitForChunks(connection);
                            check(!GameTests.connectedOverQuic(context), "TCP transport for " + fault);
                            attempt.validate();
                            check(fault == null ? Hooks.faultInjectedCount() == 0 : Hooks.wasFaultInjected(fault),
                                    "injection evidence for " + fault + ": " + Hooks.faultInjectedCount());
                            CausalSendContext retained = attempt.context;
                            check(retained != null && retained.connection() == attempt.connection,
                                    "same retained context and connection for " + fault);
                            server.computeOnServer(s -> {
                                check(Thread.currentThread() == attempt.owner, "server-thread barrier owns producer thread");
                                check(attempt.connection == retained.connection(), "retained context still names observed connection");
                                check(((ConnectionAccessor) attempt.connection).quicraft$channel() == attempt.channel,
                                        "same live channel retained");
                                check(attempt.channel.isOpen() && attempt.channel.isActive(), "channel remains live before cleanup inspection");
                                check(CausalSendContext.attached(attempt.channel) == retained, "same retained context remains attached");
                                var map = ((CausalLoginContextAccess) (Object) retained).quicraft$producerFrames();
                                check(map.get(attempt.owner) == null || map.get(attempt.owner).isEmpty(),
                                        "same context has no frames after login: " + attempt.report());
                                for (var event : attempt.events) for (var frame : event.frames())
                                    check(map.values().stream().noneMatch(stack -> stack.stream().anyMatch(f -> f == frame)),
                                            "observed frame identity absent after login: " + frame.name());
                                return null;
                            });
                            rows.add(row(attempt, Hooks.faultInjectedCount(), fault != null && Hooks.wasFaultInjected(fault)));
                        }
                    }
                } catch (Throwable error) {
                    attempt.error("attempt=" + fault + ": " + error);
                    errors.add("fault=" + fault + " " + error);
                    rows.add(row(attempt, Hooks.faultInjectedCount(), fault != null && Hooks.wasFaultInjected(fault)));
                    throw error;
                } finally {
                    CausalLoginProbe.disarm(attempt);
                    Hooks.setFaultInjection(false);
                }
            }
        } catch (Throwable error) {
            errors.add("run=" + error);
            if (error instanceof Error failure) throw failure;
            if (error instanceof RuntimeException failure) throw failure;
            throw new AssertionError(error);
        } finally {
            QuicraftClient.get().setMode(Mode.AUTO);
            Instant completed = Instant.now();
            try {
                Files.createDirectories(dir);
                String content = "started=" + started + " status=" + (errors.isEmpty() ? "COMPLETED" : "FAILED")
                        + "\ncompleted=" + completed + "\ngameVersion=" + gameVersion + "\ntransport=TCP\nrows="
                        + rows.size() + "\nerrors=" + errors + "\n";
                Files.writeString(summary, content);
                System.out.println("CAUSAL-LOGIN " + content.replace('\n', ' '));
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("status", errors.isEmpty() && rows.size() == FAULTS.length ? "COMPLETED" : "FAILED");
                report.put("started", started.toString());
                report.put("completed", completed.toString());
                report.put("gameVersion", gameVersion);
                report.put("transport", "TCP");
                report.put("rows", rows);
                report.put("errors", errors);
                Files.writeString(dir.resolve("causal-login-" + started.toEpochMilli() + ".json"), new Gson().toJson(report) + "\n");
            } catch (IOException writeError) {
                throw new AssertionError("could not write causal-login evidence", writeError);
            }
        }
    }

    private static Map<String, Object> row(CausalLoginProbe.Attempt attempt, int injectedCount, boolean selectedInjected) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", attempt.errors.isEmpty() ? "PASS" : "FAIL");
        result.put("fault", attempt.faultName);
        result.put("connectionIdentity", System.identityHashCode(attempt.connection));
        result.put("heads", new LinkedHashMap<>(attempt.heads));
        result.put("returns", new LinkedHashMap<>(attempt.returns));
        result.put("injectedCount", injectedCount);
        result.put("selectedInjected", selectedInjected);
        result.put("events", attempt.events.stream().map(e -> Map.of("key", e.key(), "names", e.names(),
                "frameIds", e.frames().stream().map(System::identityHashCode).toList())).toList());
        result.put("errors", List.copyOf(attempt.errors));
        return result;
    }

    /** Resolve evidence to this source worktree even when GameTest runs from its generated run directory. */
    private static Path outputDir() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve("settings.gradle.kts"))) {
                return directory.resolve("fabric-26x").resolve("build").resolve("causal-login");
            }
        }
        throw new IllegalStateException("cannot locate worktree root containing settings.gradle.kts from " + start);
    }
}
