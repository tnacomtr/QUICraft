// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;

/**
 * Opt-in netem no-desync session, driven by {@code testkit/run-desync-sessions.sh}: ONE
 * {@link PlaySession} against the in-process dedicated server per JVM, over exactly one transport
 * ({@code tcp} or {@code quic3}, plain or busy timer). The runner places the whole client JVM in a
 * private network namespace whose loopback carries the netem profile, so the session sees loss,
 * delay, reordering or a rate cap on both directions without an external server.
 *
 * <p>Writes one JSON row with the pass/fail state and the first mismatch message. PlaySession's
 * checks fail with that message; the first line is recorded verbatim (bounded), never reworded.
 * Not in the default entrypoint list: the runner narrows to this class, like the arm samples.
 */
public class DesyncSessionGameTest implements FabricClientGameTest {
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Set<String> NETEM = Set.of("clean", "loss", "delay", "delayloss", "reorder", "jitter", "slow");
    private static final int MAX_ERROR_CHARS = 800;
    private static final int TRANSPORT_TIMEOUT_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        String out = System.getProperty("quicraft.gametest.desyncOut", "");
        if (out.isEmpty()) {
            return; // not selected: leave the default entrypoint list usable as-is
        }
        Options o = Options.read();
        Hooks.setFaultInjection(false);
        QuicraftClient client = QuicraftClient.get();
        Mode originalMode = client.mode();
        boolean originalMultistream = client.multistream();
        Path settings = FabricLoader.getInstance().getConfigDir().resolve("quicraft").resolve("server.properties");
        byte[] settingsBefore = null;
        Throwable failure = null;
        JsonObject row;
        try {
            if (o.transport.equals("quic3")) {
                settingsBefore = Files.exists(settings) ? Files.readAllBytes(settings) : null;
                Files.createDirectories(settings.getParent());
                Files.write(settings, "multistream=true\n".getBytes(StandardCharsets.UTF_8));
            }
            client.setMode(o.mode());
            client.setMultistream(o.transport.equals("quic3"));
            row = run(context, o);
        } catch (Throwable t) {
            failure = t;
            row = failedRow(o, message(t));
        } finally {
            Hooks.setFaultInjection(false);
            client.setMode(originalMode);
            client.setMultistream(originalMultistream);
            if (o.transport.equals("quic3")) {
                try {
                    if (settingsBefore != null) {
                        Files.write(settings, settingsBefore);
                    } else {
                        Files.deleteIfExists(settings);
                    }
                } catch (IOException ignored) {
                    // the runner reports an unrestored config; it cannot hide a session result
                }
            }
            if (context.computeOnClient(c -> c.level != null)) {
                try {
                    context.runOnClient(c -> c.disconnectFromWorld(Component.literal("desync session done")));
                    context.waitFor(c -> c.level == null, 20 * 10);
                } catch (Throwable ignored) {
                    // A failed session may not reach the title screen cleanly; the row still records it.
                }
            }
            context.setScreen(TitleScreen::new);
        }
        write(o, row);
        if (failure != null || !row.get("ok").getAsBoolean()) {
            String error = row.get("error").isJsonNull() ? "unknown failure" : row.get("error").getAsString();
            throw new AssertionError("desync session failed: " + error, failure);
        }
    }

    private static JsonObject run(ClientGameTestContext context, Options o) {
        long start = System.nanoTime();
        try (TestDedicatedServerContext server = GameTests.server(context)) {
            try (var connection = server.connect()) {
                GameTests.waitForChunks(connection);
                ExternalArmObserver.Seen seen = waitForTransport(context, o.transport);
                String mismatch = ExternalArmGameTest.mismatch(
                        o.transport.equals("tcp") ? "tcp" : "quic3", seen);
                if (mismatch != null) {
                    return row(o, seen, start, mismatch);
                }
                String error = null;
                try {
                    PlaySession.run(context, server, connection);
                } catch (Throwable t) {
                    error = message(t);
                }
                return row(o, seen, start, error);
            }
        }
    }

    /** The same 40-tick cutover grace ExternalArmGameTest gives the /3 controller. */
    private static ExternalArmObserver.Seen waitForTransport(ClientGameTestContext context, String transport) {
        String arm = transport.equals("tcp") ? "tcp" : "quic3";
        ExternalArmObserver.Seen seen = null;
        String mismatch = null;
        for (int t = 0; t < TRANSPORT_TIMEOUT_TICKS; t++) {
            seen = context.computeOnClient(c -> ExternalArmObserver.observe(c.getConnection().getConnection()));
            mismatch = ExternalArmGameTest.mismatch(arm, seen);
            if (mismatch == null) {
                break;
            }
            context.waitTick();
        }
        QuicraftFabricTestLog.info("desync session transport after join: " + seen
                + (mismatch == null ? "" : " (mismatch: " + mismatch + ")"));
        return seen;
    }

    private static JsonObject row(Options o, ExternalArmObserver.Seen seen, long start, String error) {
        JsonObject row = baseRow(o);
        row.addProperty("ok", error == null);
        row.addProperty("elapsedMs", (System.nanoTime() - start) / 1_000_000.0);
        row.addProperty("disconnected", error != null && error.toLowerCase(Locale.ROOT).contains("disconnect"));
        row.add("error", error == null ? JsonNull.INSTANCE : new JsonPrimitive(error));
        row.addProperty("streamCount", seen.streamCount());
        row.addProperty("routingTableId", seen.tableId());
        row.addProperty("routingTableDigest", seen.tableDigest());
        row.addProperty("routingTableSchema", seen.tableSchema());
        row.addProperty("negotiatedAlpn", seen.alpn());
        row.addProperty("negotiatedTransportVerified", error == null
                && ExternalArmGameTest.mismatch(o.transport.equals("tcp") ? "tcp" : "quic3", seen) == null);
        return row;
    }

    /** A row that says setup failed; every field a reader expects is present. */
    private static JsonObject failedRow(Options o, String error) {
        QuicraftFabricTestLog.info("desync session " + o.transport + " run " + o.run + " failed: " + error);
        JsonObject row = baseRow(o);
        row.addProperty("ok", false);
        row.addProperty("elapsedMs", 0.0);
        row.addProperty("disconnected", error.toLowerCase(Locale.ROOT).contains("disconnect"));
        row.add("error", new JsonPrimitive(error));
        row.addProperty("streamCount", 0);
        row.addProperty("routingTableId", "");
        row.addProperty("routingTableDigest", "");
        row.addProperty("routingTableSchema", 0);
        row.addProperty("negotiatedAlpn", "");
        row.addProperty("negotiatedTransportVerified", false);
        return row;
    }

    private static JsonObject baseRow(Options o) {
        JsonObject row = new JsonObject();
        row.addProperty("run", o.run);
        row.addProperty("warmup", false);
        row.addProperty("schemaVersion", 1);
        row.addProperty("kind", "desync-session");
        row.addProperty("playSession", true);
        row.addProperty("transport", o.transport);
        row.addProperty("transportVersion", o.transport.equals("tcp") ? "TCP" : "QUIC_3");
        row.addProperty("variant", o.variant);
        row.addProperty("busyTimer", Boolean.getBoolean("quicraft.v3.batchTimerBusy"));
        row.addProperty("netemProfile", o.netem);
        row.addProperty("profile", o.profile());
        row.addProperty("buildCommit", o.commit);
        row.addProperty("protocol", SharedConstants.getProtocolVersion());
        return row;
    }

    private static void write(Options o, JsonObject row) {
        JsonObject doc = new JsonObject();
        doc.addProperty("clientType", "fabric");
        doc.addProperty("kind", "desync-session");
        doc.addProperty("transport", o.transport);
        doc.addProperty("variant", o.variant);
        doc.addProperty("netemProfile", o.netem);
        doc.addProperty("profile", o.profile());
        doc.addProperty("buildCommit", o.commit);
        JsonArray runs = new JsonArray();
        runs.add(row);
        doc.add("runs", runs);
        GameTestJson.write(o.out, doc);
        QuicraftFabricTestLog.info("desync session row written to " + o.out);
    }

    /** The first line of a thrown message, bounded; PlaySession puts the mismatch there. */
    private static String message(Throwable t) {
        String text = t.getMessage();
        if (text == null || text.isBlank()) {
            text = t.getClass().getName();
        }
        String firstLine = text.split("\\R", 2)[0];
        return firstLine.length() <= MAX_ERROR_CHARS ? firstLine : firstLine.substring(0, MAX_ERROR_CHARS - 3) + "...";
    }

    private record Options(Path out, String transport, String variant, String netem, int run, String commit) {
        static Options read() {
            String out = System.getProperty("quicraft.gametest.desyncOut", "");
            check(!out.isEmpty() && Path.of(out).isAbsolute(), "quicraft.gametest.desyncOut must be an absolute path");
            String transport = System.getProperty("quicraft.gametest.desyncTransport", "");
            check(transport.equals("tcp") || transport.equals("quic3"),
                    "quicraft.gametest.desyncTransport must be tcp or quic3, got " + transport);
            String variant = System.getProperty("quicraft.gametest.desyncVariant", "plain");
            check(variant.equals("plain") || variant.equals("busy"),
                    "quicraft.gametest.desyncVariant must be plain or busy, got " + variant);
            check(!variant.equals("busy") || transport.equals("quic3"),
                    "only the quic3 transport has a busy-timer variant");
            check(variant.equals("busy") == Boolean.getBoolean("quicraft.v3.batchTimerBusy"),
                    "the busy variant needs -Dquicraft.v3.batchTimerBusy=true, and the plain variant must not have it");
            String netem = System.getProperty("quicraft.gametest.desyncNetem", "");
            check(NETEM.contains(netem), "quicraft.gametest.desyncNetem must be one of " + NETEM);
            int run = Integer.parseInt(System.getProperty("quicraft.gametest.desyncRun", "-1"));
            check(run >= 0, "quicraft.gametest.desyncRun must be >= 0");
            String commit = System.getProperty("quicraft.gametest.desyncCommit", "");
            check(COMMIT.matcher(commit).matches(), "quicraft.gametest.desyncCommit must be a 40-hex commit");
            if (transport.equals("quic3")) {
                check(Boolean.getBoolean("quicraft.multistream.offlineTest"),
                        "quic3 needs -Dquicraft.multistream.offlineTest=true on the client");
            }
            return new Options(Path.of(out), transport, variant, netem, run, commit);
        }

        String profile() {
            return netem + (transport.equals("tcp") ? "-tcp" : "-quic-3");
        }

        Mode mode() {
            return transport.equals("tcp") ? Mode.TCP_ONLY : Mode.QUIC_ONLY;
        }
    }
}
