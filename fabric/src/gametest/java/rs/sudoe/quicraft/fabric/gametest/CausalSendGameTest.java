// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import static rs.sudoe.quicraft.fabric.gametest.GameTests.check;

import com.google.gson.Gson;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;
import rs.sudoe.quicraft.fabric.Hooks;
import rs.sudoe.quicraft.fabric.client.QuicraftClient;
import rs.sudoe.quicraft.fabric.gametest.mixin.CausalSendContextAccess;
import rs.sudoe.quicraft.fabric.mixin.ServerCommonPacketListenerImplAccessor;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;

/** Runtime evidence for the real production envelope from allocation to encoded write. */
public final class CausalSendGameTest implements FabricClientGameTest {
    private static final List<String> EXPECTED = List.of("pending_arity1", "pending_arity2", "pending_arity3",
            "pending_trigger", "inline", "reuse_1", "reuse_2", "nested_outer", "nested_inner", "live_offloop");
    private static final List<String> EXPECTED_SYNTHETIC = EXPECTED.subList(0, 9);
    private static volatile Connection syntheticConnection;
    private static volatile Packet<?> nestedPacket;

    @Override public void runTest(ClientGameTestContext context) {
        String runId = readRunId();
        Instant started = Instant.now();
        Path dir = outputDir();
        List<String> errors = new ArrayList<>();
        String[] observedTransport = {"NOT_OBSERVED"};
        QuicraftClient.get().setMode(Mode.TCP_ONLY);
        Hooks.setFaultInjection(false);
        CausalSendProbe.reset();
        try {
            synthetic(errors);
            try (TestDedicatedServerContext server = GameTests.server(context); var client = server.connect()) {
                GameTests.serverListPing(context, GameTests.address(server));
                GameTests.waitForChunks(client);
                check(!GameTests.connectedOverQuic(context), "live evidence must use TCP");
                observedTransport[0] = "TCP";
                live(server, errors);
                context.waitTicks(20);
                check(context.computeOnClient(c -> c.getConnection() != null && c.level != null),
                        "client remains joined after live off-loop send");
                validateRows();
            }
        } catch (Throwable error) {
            errors.add(error.toString());
            if (error instanceof Error e) throw e;
            if (error instanceof RuntimeException e) throw e;
            throw new AssertionError(error);
        } finally {
            try { Hooks.setFaultInjection(false); }
            catch (Throwable error) { errors.add("restore fault injection: " + error); }
            try { QuicraftClient.get().setMode(Mode.AUTO); }
            catch (Throwable error) { errors.add("restore AUTO mode: " + error); }
            for (CausalSendProbe.Row row : CausalSendProbe.rows())
                if (row.channel != null && row.channel.isOpen()) {
                    try { CausalSendTap.remove(row.channel); }
                    catch (Throwable error) { errors.add("final tap removal " + row.id + ": " + error); }
                }
            writeEvidence(dir, runId, started, CausalSendProbe.rows(), errors, observedTransport[0]);
        }
    }

    private static void synthetic(List<String> errors) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        syntheticConnection = connection;
        ClientboundKeepAlivePacket p1 = new ClientboundKeepAlivePacket(0x11L);
        ClientboundKeepAlivePacket p2 = new ClientboundKeepAlivePacket(0x12L);
        ClientboundKeepAlivePacket p3 = new ClientboundKeepAlivePacket(0x13L);
        CausalSendProbe.invoke("pending_arity1", "synthetic", connection, p1, () -> connection.send(p1));
        CausalSendProbe.invoke("pending_arity2", "synthetic", connection, p2, () -> connection.send(p2, null));
        CausalSendProbe.invoke("pending_arity3", "synthetic", connection, p3, () -> connection.send(p3, null, true));
        for (String id : EXPECTED.subList(0, 3)) {
            CausalSendProbe.Row row = row(id);
            check(row.birthCount == 1, id + " must have exactly one production allocation before activation");
            check(row.terminalCount == 0, id + " wrote before channel activation");
            check(!connection.isConnected(), id + " was queued while disconnected");
        }
        CausalSendRig.Synthetic rig = CausalSendRig.syntheticConnection(connection);
        EmbeddedChannel channel = rig.channel();
        try {
            CausalSendTap.install(channel);
            channel.pipeline().fireChannelActive();
            channel.runPendingTasks();
            channel.flushOutbound();
            ClientboundKeepAlivePacket trigger = new ClientboundKeepAlivePacket(0x14L);
            CausalSendProbe.invoke("pending_trigger", "synthetic", connection, trigger, () -> connection.send(trigger));
            channel.runPendingTasks();
            channel.flushOutbound();
            for (String id : EXPECTED.subList(0, 3)) check(row(id).terminalCount == 1, id + " activation drain write count");
            for (String id : EXPECTED.subList(0, 4)) prepareSyntheticRow(row(id), channel);

            sendSynthetic("inline", connection, channel, new ClientboundKeepAlivePacket(0x15L));
            ClientboundKeepAlivePacket reuse = new ClientboundKeepAlivePacket(0x16L);
            sendSynthetic("reuse_1", connection, channel, reuse);
            channel.runPendingTasks();
            channel.flushOutbound();
            CausalSendProbe.retire(row("reuse_1"));
            sendSynthetic("reuse_2", connection, channel, reuse);
            check(row("reuse_1").envelope != row("reuse_2").envelope, "reused packet gets distinct envelope objects");
            check(row("reuse_1").envelope.sendId() != row("reuse_2").envelope.sendId(), "reused packet gets distinct sendIds");

            nestedPacket = new ClientboundKeepAlivePacket(0x18L);
            sendSynthetic("nested_outer", connection, channel, new ClientboundKeepAlivePacket(0x17L));
            CausalSendProbe.Row inner = row("nested_inner");
            prepareSyntheticRow(inner, channel);
            check(inner.terminalCount == 1, "nested witness had one terminal write");
            check(CausalSendContext.currentEnvelope(channel) == null, "synthetic frame cleared after outer returns");
            check(CausalSendContext.attached(channel) == row("nested_outer").context && channel.isOpen() && channel.isActive(),
                    "synthetic production context remains attached to live channel before close");
            for (String id : EXPECTED_SYNTHETIC) row(id).validate();
        } finally {
            try { CausalSendTap.remove(channel); }
            catch (Throwable error) { errors.add("synthetic tap removal: " + error); }
            try { channel.finishAndReleaseAll(); }
            catch (Throwable error) { errors.add("synthetic channel cleanup: " + error); }
        }
    }

    private static void sendSynthetic(String id, Connection connection, EmbeddedChannel channel, Packet<?> packet) {
        CausalSendProbe.invoke(id, "synthetic", connection, packet, () -> connection.send(packet));
        channel.runPendingTasks();
        channel.flushOutbound();
        prepareSyntheticRow(row(id), channel);
        row(id).validate();
    }

    private static void prepareSyntheticRow(CausalSendProbe.Row row, Channel channel) {
        check(CausalSendContext.attached(channel) == row.context && channel.isOpen() && channel.isActive(),
                row.id + " same production context and active channel before cleanup inspection");
        check(channel.eventLoop().inEventLoop() && row.writerThread == Thread.currentThread(),
                row.id + " EmbeddedChannel writer cleanup is inspected on its owner thread");
        assertOwnerFramesEmpty(row);
        row.producerClean = true;
        row.writerClean = true;
    }

    static void nestedInner(CausalSendProbe.Row outer) {
        if (CausalSendProbe.observing() != outer) outer.error("outer observer token missing before nested send");
        Connection connection = syntheticConnection;
        CausalSendProbe.invoke("nested_inner", "synthetic", connection, nestedPacket, () -> connection.send(nestedPacket));
        if (CausalSendProbe.observing() != outer) outer.error("outer observer token not restored after nested send");
    }

    private static void live(TestDedicatedServerContext server, List<String> errors) {
        Connection[] connection = new Connection[1];
        Channel[] channel = new Channel[1];
        CausalSendContext[] retained = new CausalSendContext[1];
        server.computeOnServer(s -> {
            List<? extends net.minecraft.server.level.ServerPlayer> players = s.getPlayerList().getPlayers();
            check(players.size() == 1, "one connected player");
            ServerCommonPacketListenerImpl listener = (ServerCommonPacketListenerImpl) players.get(0).connection;
            connection[0] = ((ServerCommonPacketListenerImplAccessor) listener).quicraft$connection();
            channel[0] = CausalSendRig.channel(connection[0]);
            retained[0] = CausalSendContext.attached(channel[0]);
            check(retained[0] != null && retained[0].connection() == connection[0], "real production context attached");
            return null;
        });
        Channel ch = channel[0];
        if (ch.eventLoop().inEventLoop()) throw new AssertionError("test thread owns live event loop");
        try {
            ch.eventLoop().submit(() -> CausalSendTap.install(ch)).get(10, TimeUnit.SECONDS);
            ClientboundBlockChangedAckPacket packet = new ClientboundBlockChangedAckPacket(0);
            server.computeOnServer(s -> {
                CausalSendProbe.invoke("live_offloop", "live", connection[0], packet, () -> connection[0].send(packet));
                CausalSendProbe.Row row = row("live_offloop");
                check(CausalSendContext.attached(ch) == retained[0] && ch.isOpen() && ch.isActive(), "retained live context/channel");
                assertOwnerFramesEmpty(row);
                row.producerClean = true;
                return null;
            });
            CausalSendProbe.Row row = row("live_offloop");
            ch.eventLoop().submit(() -> {
                check(row.writerThread == Thread.currentThread() && ch.eventLoop().inEventLoop(), "writer is actual channel event-loop thread");
                check(CausalSendContext.currentEnvelope(ch) == null, "event-loop frame empty on its owner thread");
                Map<Thread, java.util.Deque<CausalSendContext.Frame>> frames =
                        ((CausalSendContextAccess) (Object) retained[0]).quicraft$frames();
                java.util.Deque<CausalSendContext.Frame> active = frames.get(Thread.currentThread());
                check(active == null || active.isEmpty(), "no live writer frame remains on event-loop owner");
                check(CausalSendContext.attached(ch) == retained[0] && ch.isOpen() && ch.isActive(),
                        "same live context remains attached to open active channel");
                row.writerClean = true;
                row.sameLiveContextChecked = true;
            }).get(10, TimeUnit.SECONDS);
            check(row.producerThread != row.writerThread, "producer differs from network writer thread");
            check(ch.eventLoop().inEventLoop(row.writerThread), "writer belongs to channel event loop");
            row.validate();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for live writer cleanup", error);
        } catch (Exception error) {
            throw new AssertionError("live writer cleanup barrier failed", error);
        } finally {
            try { CausalSendTap.remove(ch); }
            catch (Throwable error) { errors.add("live tap removal: " + error); }
        }
    }

    private static void assertOwnerFramesEmpty(CausalSendProbe.Row row) {
        Map<Thread, java.util.Deque<CausalSendContext.Frame>> frames =
                ((CausalSendContextAccess) (Object) row.context).quicraft$frames();
        java.util.Deque<CausalSendContext.Frame> owned = frames.get(Thread.currentThread());
        if (owned != null && !owned.isEmpty()) throw new AssertionError(row.id + " leaked producer-thread frame " + owned);
    }

    private static CausalSendProbe.Row row(String id) {
        return CausalSendProbe.rows().stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow(() -> new AssertionError("missing row " + id));
    }

    private static void validateRows() {
        List<CausalSendProbe.Row> rows = CausalSendProbe.rows();
        if (!rows.stream().map(row -> row.id).toList().equals(EXPECTED))
            throw new AssertionError("row ids/order=" + rows.stream().map(row -> row.id).toList() + " expected=" + EXPECTED);
        for (CausalSendProbe.Row row : rows) {
            if (!row.validated || !row.errors.isEmpty() || !row.producerClean || !row.writerClean
                    || (row.id.equals("live_offloop") && !row.sameLiveContextChecked))
                throw new AssertionError("row not cleanly validated: " + row.id + " " + row.errors);
        }
    }

    private static String readRunId() {
        Path nonce = outputDir().resolve("active-run-id.txt");
        try {
            String value = Files.readString(nonce).trim();
            if (!value.matches("[0-9a-f]{32}")) throw new AssertionError("invalid runner runId " + value);
            return value;
        } catch (IOException e) { throw new AssertionError("cannot read runner nonce " + nonce, e); }
    }

    private static Path outputDir() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent())
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir.resolve("fabric-26x/build/causal-send");
        throw new IllegalStateException("cannot find settings.gradle.kts ancestor from " + start);
    }

    private static void writeEvidence(Path dir, String runId, Instant started, List<CausalSendProbe.Row> rows,
            List<String> errors, String transport) {
        try {
            Files.createDirectories(dir);
            List<Map<String,Object>> outputRows = rows.stream().map(CausalSendProbe.Row::json).toList();
            String status = errors.isEmpty() && outputRows.size() == 10 && "TCP".equals(transport)
                    && outputRows.stream().allMatch(row -> "PASS".equals(row.get("status"))
                            && Boolean.TRUE.equals(row.get("producerClean")) && Boolean.TRUE.equals(row.get("writerClean"))
                            && (!"live".equals(row.get("category")) || Boolean.TRUE.equals(row.get("sameLiveContextChecked"))))
                    ? "COMPLETED" : "FAILED";
            Map<String,Object> report = new LinkedHashMap<>();
            report.put("status", status); report.put("started", started.toString()); report.put("completed", Instant.now().toString());
            report.put("runId", runId);
            report.put("gameVersion", FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString());
            report.put("transport", transport); report.put("rows", outputRows); report.put("errors", errors);
            Files.writeString(dir.resolve("causal-send-" + started.toEpochMilli() + ".json"), new Gson().toJson(report) + "\n");
            Files.writeString(dir.resolve("causal-send-summary.txt"), "started=" + started + " status=" + status + " runId=" + runId
                    + "\ncompleted=" + Instant.now() + "\nrunId=" + runId + "\ngameVersion=" + report.get("gameVersion")
                    + "\ntransport=" + transport + "\nrows=" + outputRows.size() + "\nerrors=" + errors + "\n");
            System.out.println("CAUSAL-SEND runId=" + runId + " status=" + status + " rows=" + outputRows.size() + " errors=" + errors);
        } catch (IOException e) { throw new AssertionError("writing causal-send evidence failed", e); }
    }
}
