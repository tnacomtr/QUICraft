// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import rs.sudoe.quicraft.core.connect.ClientConnector;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.client.Screens;

/**
 * Opt-in benchmark sample (-Pquicraft.arm=tcp|quic2|quic3 with -Pquicraft.armServer=host:port and
 * the other -Pquicraft.arm* properties, see testkit/run-external-arms.sh): ONE join of the real
 * client to an outside server per JVM, over exactly the arm's transport (no fallback), timed at
 * the events BenchSession uses, then a fixed idle play window with a play ping every 5 ticks.
 * Writes one RunResult row in the arm document envelope. The row's transport identity is what the
 * live connection negotiated (ExternalArmObserver), never just the arm's label; a mismatch fails
 * the row. Keep it out of the default entrypoint list: the runner narrows to this class.
 */
public class ExternalArmGameTest implements FabricClientGameTest {
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Set<String> NETEM = Set.of("clean", "loss", "delay", "delayloss", "reorder", "jitter");
    private static final int PING_EVERY_TICKS = 5;
    private static final long QUIET_NANOS = 2_000_000_000L;
    private static final int JOIN_TIMEOUT_TICKS = 20 * 90;
    private static final int CHUNK_TIMEOUT_TICKS = 20 * 60;
    /** Payloads of the pings this test sent, so only our pongs count as round trips. */
    private static final Set<Long> SENT = ConcurrentHashMap.newKeySet();
    /** The connector's status-query hook name ({@code ClientConnect}'s platform). */
    private static final String STATUS_QUERY_HOOK = "status query";

    static boolean ownPing(long payload) {
        return SENT.remove(payload);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        String arm = System.getProperty("quicraft.gametest.arm", "");
        if (arm.isEmpty()) {
            return;
        }
        Options o = Options.read(arm);
        Hooks.setFaultInjection(false);
        QuicClient.forgetSessions(); // fresh JVM anyway: no 0-RTT ticket in any arm
        QuicraftClient client = QuicraftClient.get();
        Mode originalMode = client.mode();
        boolean originalMultistream = client.multistream();
        JsonObject row;
        try {
            client.setMode(o.mode());
            client.setMultistream(o.arm.equals("quic3"));
            row = run(context, client, o);
        } catch (Throwable t) {
            row = failedRow(o, "harness: " + t);
        } finally {
            Hooks.setFaultInjection(false);
            client.setMode(originalMode);
            client.setMultistream(originalMultistream);
        }
        write(o, row);
        if (context.computeOnClient(c -> c.level != null)) {
            context.runOnClient(c -> c.disconnectFromWorld(Component.literal("arm sample done")));
            context.waitFor(c -> c.level == null, 20 * 10);
        }
        context.setScreen(TitleScreen::new); // the harness expects every test to end there
    }

    private static JsonObject run(ClientGameTestContext context, QuicraftClient client, Options o) throws Exception {
        // The status query happens here, outside the timed window, as BenchClient's StatusPing does.
        if (!o.arm.equals("tcp")) {
            if (o.arm.equals("quic3")) {
                check(client.multistreamConnector() != null, "multistream connector unavailable");
            }
            // A direct status request (as BenchClient's StatusPing), recorded under the connector's
            // own key, so the join below takes the cached advertisement and runs no status query.
            InetSocketAddress key = ClientConnector.key(new InetSocketAddress(InetAddress.getByName(o.host), o.port));
            String status = statusJson(key);
            Advertisement ad = Advertisement.extractForSelection(status).orElse(null);
            check(ad != null, "no QUICraft advertisement in the status response: " + status);
            String expected = o.arm.equals("quic3") ? "quicraft/3" : null;
            check(expected == null || expected.equals(ad.alpn()), "server advertises " + ad.alpn());
            client.connector().advertisements().record(key, java.util.Optional.of(ad));
            check(client.connector().advertisements().fresh(key).isPresent(), "advertisement not cached for " + key);
        }
        ArmClock.reset();
        SENT.clear();
        // Any status query inside the timed window throws at its hook (so it cannot succeed quietly)
        // and is recorded; the row then fails below.
        Hooks.setFaultInjection(STATUS_QUERY_HOOK);
        long start = System.nanoTime();
        context.runOnClient(c -> ConnectScreen.startConnecting(new TitleScreen(), c,
                ServerAddress.parseString(o.server), new ServerData("arm", o.server, ServerData.Type.OTHER),
                false, null));
        for (int t = 0; !context.computeOnClient(c -> c.level != null && c.player != null
                && Screens.current(c) == null); t++) {
            if (t > JOIN_TIMEOUT_TICKS) {
                String state = context.computeOnClient(c -> "screen " + (Screens.current(c) == null ? "none"
                        : Screens.current(c).getClass().getName()));
                String query = Hooks.wasFaultInjected(STATUS_QUERY_HOOK)
                        ? "; a status query ran inside the timed window" : "";
                return failedRow(o, "not in the world after " + JOIN_TIMEOUT_TICKS / 20 + " s (" + state + ")" + query);
            }
            context.waitTick();
        }
        if (Hooks.wasFaultInjected(STATUS_QUERY_HOOK)) {
            return failedRow(o, "a status query ran inside the timed window");
        }
        // The /3 controller may still be finishing its cutover at the first rendered tick.
        ExternalArmObserver.Seen seen = null;
        String mismatch = null;
        for (int t = 0; t < 40; t++) {
            seen = context.computeOnClient(c -> ExternalArmObserver.observe(c.getConnection().getConnection()));
            mismatch = mismatch(o.arm, seen);
            if (mismatch == null) {
                break;
            }
            context.waitTick();
        }
        // Chunk load: chunks arrived and none for QUIET_NANOS (BenchClient's quiet rule).
        for (int t = 0; ArmClock.chunks.get() == 0
                || System.nanoTime() - ArmClock.lastChunkAt.get() < QUIET_NANOS; t++) {
            if (t > CHUNK_TIMEOUT_TICKS) {
                return failedRow(o, "chunks not quiet after " + CHUNK_TIMEOUT_TICKS / 20 + " s");
            }
            context.waitTick();
        }
        // Idle play window: a play ping every 5 ticks, identical in every arm.
        int sent = 0;
        int ticks = o.playSeconds * 20;
        for (int t = 0; t < ticks; t++) {
            if (t % PING_EVERY_TICKS == 0) {
                boolean ok = context.computeOnClient(c -> {
                    if (c.getConnection() == null) {
                        return false;
                    }
                    long now = System.nanoTime();
                    SENT.add(now);
                    c.getConnection().send(new ServerboundPingRequestPacket(now));
                    return true;
                });
                if (!ok) {
                    return failedRow(o, "disconnected during play");
                }
                sent++;
            }
            context.waitTick();
        }
        for (int t = 0; ArmClock.rtts.size() < sent && t < 40; t++) {
            context.waitTick(); // 2 s grace for the last pongs
        }
        // Still armed through chunk load and play; the finally in runTest disarms it.
        if (Hooks.wasFaultInjected(STATUS_QUERY_HOOK)) {
            return failedRow(o, "a status query ran during the measurement");
        }
        boolean connected = context.computeOnClient(c -> c.getConnection() != null && c.level != null);
        ExternalArmObserver.Seen after = connected ? context.computeOnClient(
                c -> ExternalArmObserver.observe(c.getConnection().getConnection())) : null;
        if (!connected) {
            return failedRow(o, "disconnected during play");
        }
        if (mismatch == null && !seen.equals(after)) {
            mismatch = "transport changed during the run: " + seen + " then " + after;
        }
        long hello = ArmClock.helloAt.get() != 0 ? ArmClock.helloAt.get() : ArmClock.loginFinishedAt.get();
        long[] marks = {hello, ArmClock.loginFinishedAt.get(), ArmClock.configurationAt.get(),
                ArmClock.playLoginAt.get()};
        for (int i = 0; i < marks.length; i++) {
            if (marks[i] <= start) {
                return failedRow(o, "phase " + i + " was not observed on the Netty thread");
            }
        }
        double[] phases = new double[marks.length];
        for (int i = 0; i < marks.length; i++) {
            phases[i] = millis(marks[i] - start);
        }
        List<Double> rtt = new ArrayList<>();
        for (long nanos : ArmClock.rtts) {
            rtt.add(millis(nanos));
        }
        double[] sorted = rtt.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        JsonObject row = baseRow(o);
        boolean ok = mismatch == null && sorted.length > 0;
        row.addProperty("ok", ok);
        add(row, "phasesMs", phases);
        row.addProperty("joinMs", phases[3]);
        // As BenchClient: play login to the last chunk, not from the connect.
        row.addProperty("chunkLoadMs", millis(ArmClock.lastChunkAt.get() - ArmClock.playLoginAt.get()));
        row.addProperty("chunks", ArmClock.chunks.get());
        row.addProperty("rttMedianMs", percentile(sorted, 0.5));
        row.addProperty("rttP95Ms", percentile(sorted, 0.95));
        row.addProperty("rttSamples", sorted.length);
        row.addProperty("rttLost", sent - sorted.length);
        row.addProperty("disconnected", false);
        row.add("error", mismatch != null ? new com.google.gson.JsonPrimitive(mismatch)
                : sorted.length == 0 ? new com.google.gson.JsonPrimitive("no pong received") : JsonNull.INSTANCE);
        row.addProperty("streamCount", seen.streamCount());
        row.addProperty("routingTableId", seen.tableId());
        row.addProperty("routingTableDigest", seen.tableDigest());
        row.addProperty("routingTableSchema", seen.tableSchema());
        row.addProperty("negotiatedAlpn", seen.alpn());
        row.addProperty("negotiatedTransportVerified", mismatch == null);
        QuicraftFabricTestLog.info(String.format(Locale.ROOT, "arm %s run %d: join %.1f ms, chunks %d in %.1f ms,"
                + " rtt median %.2f ms p95 %.2f ms (%d/%d), seen %s, ok %s", o.arm, o.run, phases[3],
                ArmClock.chunks.get(), millis(ArmClock.lastChunkAt.get() - ArmClock.playLoginAt.get()), percentile(sorted, 0.5),
                percentile(sorted, 0.95), sorted.length, sent, seen, ok));
        return row;
    }

    /** One status request over TCP (handshake, request, response); returns the status JSON. */
    private static String statusJson(InetSocketAddress server) throws IOException {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(server, 5_000);
            socket.setSoTimeout(5_000);
            java.io.ByteArrayOutputStream handshake = new java.io.ByteArrayOutputStream();
            varInt(handshake, 0x00);
            varInt(handshake, net.minecraft.SharedConstants.getProtocolVersion());
            byte[] host = server.getHostString().getBytes(StandardCharsets.UTF_8);
            varInt(handshake, host.length);
            handshake.write(host);
            handshake.write(server.getPort() >> 8);
            handshake.write(server.getPort());
            varInt(handshake, 1); // next state: status
            java.io.ByteArrayOutputStream frames = new java.io.ByteArrayOutputStream();
            varInt(frames, handshake.size());
            handshake.writeTo(frames);
            varInt(frames, 1);
            varInt(frames, 0x00); // status request
            socket.getOutputStream().write(frames.toByteArray());
            java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
            readVarInt(in); // frame length
            check(readVarInt(in) == 0x00, "unexpected status response packet");
            int length = readVarInt(in);
            check(length >= 0 && length <= 32767 * 4, "status response length out of range: " + length);
            byte[] json = new byte[length];
            in.readFully(json);
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    private static void varInt(java.io.ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(java.io.DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }

    /** Why the live transport is not the arm's, or null when it is. */
    private static String mismatch(String arm, ExternalArmObserver.Seen seen) {
        return switch (arm) {
            case "tcp" -> !seen.quic() && seen.alpn().isEmpty() ? null : "tcp arm negotiated " + seen;
            case "quic2" -> seen.quic() && "quicraft/2".equals(seen.alpn()) && seen.streamCount() == 1
                    && seen.tableId().isEmpty() ? null : "quic2 arm negotiated " + seen;
            case "quic3" -> seen.quic() && "quicraft/3".equals(seen.alpn()) && seen.streamCount() > 3
                    && !seen.tableId().isEmpty() && "ACTIVE".equals(seen.controllerState())
                    ? null : "quic3 arm negotiated " + seen;
            default -> "unknown arm " + arm;
        };
    }

    private static JsonObject baseRow(Options o) {
        JsonObject row = new JsonObject();
        row.addProperty("run", o.run);
        row.addProperty("warmup", o.warmup);
        row.addProperty("zeroRtt", false);
        row.addProperty("schemaVersion", 1);
        row.addProperty("transportVersion", switch (o.arm) {
            case "tcp" -> "TCP";
            case "quic2" -> "QUIC_2";
            default -> "QUIC_3";
        });
        row.addProperty("buildCommit", o.commit);
        row.add("net", JsonNull.INSTANCE);
        row.add("quic", JsonNull.INSTANCE);
        return row;
    }

    /** A complete row that says the run failed; numbers are zero, never invented. */
    private static JsonObject failedRow(Options o, String error) {
        QuicraftFabricTestLog.info("arm " + o.arm + " run " + o.run + " failed: " + error);
        JsonObject row = baseRow(o);
        row.addProperty("ok", false);
        add(row, "phasesMs", new double[4]);
        row.addProperty("joinMs", 0.0);
        row.addProperty("chunkLoadMs", 0.0);
        row.addProperty("chunks", ArmClock.chunks.get());
        row.addProperty("rttMedianMs", 0.0);
        row.addProperty("rttP95Ms", 0.0);
        row.addProperty("rttSamples", 0);
        row.addProperty("rttLost", 0);
        row.addProperty("disconnected", error.contains("disconnected"));
        row.addProperty("error", error);
        row.addProperty("streamCount", 0);
        row.addProperty("routingTableId", "");
        row.addProperty("routingTableDigest", "");
        row.addProperty("routingTableSchema", 0);
        row.addProperty("negotiatedAlpn", "");
        row.addProperty("negotiatedTransportVerified", false);
        return row;
    }

    private static void write(Options o, JsonObject row) {
        JsonObject doc = new JsonObject();
        doc.addProperty("clientType", "fabric");
        doc.addProperty("arm", o.arm);
        doc.addProperty("netemProfile", o.netem);
        doc.addProperty("profile", o.netem + switch (o.arm) {
            case "tcp" -> "-tcp";
            case "quic2" -> "-quic-2";
            default -> "-quic-3";
        });
        doc.addProperty("server", o.server);
        JsonArray runs = new JsonArray();
        runs.add(row);
        doc.add("runs", runs);
        String json = new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(doc) + "\n";
        check(!Files.exists(o.out), "refusing to overwrite " + o.out);
        Path tmp = o.out.resolveSibling(o.out.getFileName() + ".tmp");
        boolean ownsTmp = false;
        try {
            Files.createFile(tmp); // fails if it exists, so ownership starts exactly here
            ownsTmp = true;
            Files.writeString(tmp, json, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
            // A hard link publishes the complete file atomically and fails if the name exists,
            // unlike a rename, which would replace a file created after the check above.
            Files.createLink(o.out, tmp);
        } catch (IOException e) {
            throw new AssertionError("could not write " + o.out, e);
        } finally {
            try {
                if (ownsTmp) { // never remove a temp file someone else left there
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException ignored) {
                // the runner reports a leftover side file
            }
        }
        QuicraftFabricTestLog.info("arm sample written to " + o.out);
    }

    private static void add(JsonObject row, String name, double[] values) {
        JsonArray array = new JsonArray();
        for (double v : values) {
            array.add(v);
        }
        row.add(name, array);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /** As BenchClient.percentile: linear interpolation; 0 (not -1) when there are no samples. */
    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        double rank = p * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException(what);
        }
    }

    private record Options(String arm, String server, String host, int port, Path out, String netem, int run,
            boolean warmup, String commit, int playSeconds) {
        static Options read(String arm) {
            check(Set.of("tcp", "quic2", "quic3").contains(arm), "quicraft.gametest.arm must be tcp, quic2 or quic3");
            String server = System.getProperty("quicraft.gametest.armServer", "");
            int colon = server.lastIndexOf(':');
            check(colon > 0, "quicraft.gametest.armServer must be host:port, got " + server);
            int port = Integer.parseInt(server.substring(colon + 1));
            check(port > 0 && port < 65536, "bad port " + port);
            String out = System.getProperty("quicraft.gametest.armOut", "");
            check(!out.isEmpty() && Path.of(out).isAbsolute(), "quicraft.gametest.armOut must be an absolute path");
            String netem = System.getProperty("quicraft.gametest.armNetem", "");
            check(NETEM.contains(netem), "quicraft.gametest.armNetem must be one of " + NETEM);
            int run = Integer.parseInt(System.getProperty("quicraft.gametest.armRun", "-1"));
            check(run >= 0, "quicraft.gametest.armRun must be >= 0");
            String warmup = System.getProperty("quicraft.gametest.armWarmup", "");
            check(warmup.equals("true") || warmup.equals("false"), "quicraft.gametest.armWarmup must be true or false");
            String commit = System.getProperty("quicraft.gametest.armCommit", "");
            check(COMMIT.matcher(commit).matches(), "quicraft.gametest.armCommit must be a 40-hex commit");
            int play = Integer.parseInt(System.getProperty("quicraft.gametest.armPlaySeconds", "20"));
            check(play >= 1 && play <= 120, "quicraft.gametest.armPlaySeconds must be 1..120");
            if (arm.equals("quic3")) {
                check(Boolean.getBoolean("quicraft.multistream.offlineTest"),
                        "quic3 needs -Dquicraft.multistream.offlineTest=true on the client");
            }
            return new Options(arm, server, server.substring(0, colon), port, Path.of(out), netem, run,
                    Boolean.parseBoolean(warmup), commit, play);
        }

        Mode mode() {
            return arm.equals("tcp") ? Mode.TCP_ONLY : Mode.QUIC_ONLY;
        }
    }
}
