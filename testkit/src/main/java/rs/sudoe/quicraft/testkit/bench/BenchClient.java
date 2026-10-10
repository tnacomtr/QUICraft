// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ping.serverbound.ServerboundPingRequestPacket;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.testkit.Args;
import rs.sudoe.quicraft.testkit.session.MockSessionService;

/**
 * Joins a server {@code --runs} times (after {@code --warmup} discarded joins) and records, per
 * join: join time, chunk-load time, play RTT and unexpected disconnects. Method and definitions
 * are in docs/benchmarks.md.
 */
public final class BenchClient {
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /** A ping request goes out every this many ticks, right after that tick's movement packet. */
    private static final int PING_EVERY_TICKS = 5;

    private record Options(
            String host, int port, String transport, String profile, int runs, int warmup, boolean online, URI sessionUrl,
            int viewDistance, long playNanos, long quietNanos, long timeoutNanos, Path out, boolean verbose) {}

    private BenchClient() {}

    public static int run(Args args) throws IOException, InterruptedException {
        Options o = new Options(
                args.string("host", "localhost"),
                args.integer("port", 25565),
                args.string("transport", "tcp"),
                args.string("profile", "unknown"),
                args.integer("runs", 20),
                args.integer("warmup", 2),
                args.flag("online"),
                URI.create(args.string("session-url", "http://mocksession:8080")),
                args.integer("view-distance", 8),
                TimeUnit.MILLISECONDS.toNanos(args.integer("play-ms", 10_000)),
                TimeUnit.MILLISECONDS.toNanos(args.integer("quiet-ms", 2_000)),
                TimeUnit.MILLISECONDS.toNanos(args.integer("timeout-ms", 60_000)),
                args.string("out", null) == null ? null : Path.of(args.string("out", null)),
                args.flag("verbose"));

        if (!o.transport().equals("tcp") && !o.transport().equals("quic")) {
            throw new IllegalArgumentException("--transport must be tcp or quic");
        }
        List<RunResult> results = new ArrayList<>();
        int total = o.warmup() + o.runs();
        for (int i = 0; i < total; i++) {
            boolean warmup = i < o.warmup();
            RunResult result = runOnce(o, i, warmup);
            results.add(result);
            System.out.printf("[%s/%s] run %d%s ok=%s join=%.1fms phases=%s chunks=%d chunkLoad=%.1fms rtt=%.2fms p95=%.2fms lost=%d%s%n",
                    o.profile(), o.transport(), i, warmup ? " (warmup)" : "", result.ok(), result.joinMs(), Arrays.toString(result.phasesMs()), result.chunks(),
                    result.chunkLoadMs(), result.rttMedianMs(), result.rttP95Ms(), result.rttLost(),
                    result.error() == null ? "" : " error=" + result.error());
            // Let the proxy and backend finish the previous player's logout.
            Thread.sleep(1_000);
        }

        if (o.out() != null) {
            JsonObject doc = new JsonObject();
            doc.addProperty("profile", o.profile());
            doc.addProperty("host", o.host());
            doc.addProperty("port", o.port());
            doc.addProperty("transport", o.transport());
            doc.addProperty("online", o.online());
            doc.addProperty("viewDistance", o.viewDistance());
            doc.addProperty("playMs", TimeUnit.NANOSECONDS.toMillis(o.playNanos()));
            doc.addProperty("quietMs", TimeUnit.NANOSECONDS.toMillis(o.quietNanos()));
            doc.addProperty("finishedAt", Instant.now().toString());
            Gson gson = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
            doc.add("runs", gson.toJsonTree(results));
            Files.createDirectories(o.out().toAbsolutePath().getParent());
            Files.writeString(o.out(), gson.toJson(doc), StandardCharsets.UTF_8);
        }
        long failed = results.stream().filter(r -> !r.warmup() && !r.ok()).count();
        return failed == 0 ? 0 : 1;
    }

    private static RunResult runOnce(Options o, int index, boolean warmup) throws InterruptedException {
        String name = "bench" + index;
        UUID uuid = UUID.nameUUIDFromBytes(("quicraft-bench:" + name).getBytes(StandardCharsets.UTF_8));
        MinecraftProtocol protocol = o.online()
                ? new MinecraftProtocol(new GameProfile(uuid, name), "testkit-token")
                : new MinecraftProtocol(name);
        BenchSession bench = new BenchSession(protocol, o.viewDistance(), o.verbose());
        // Resolve before the clock starts, so DNS is not part of join time.
        InetSocketAddress address = new InetSocketAddress(o.host(), o.port());
        Advertisement ad = null;
        if (o.transport().equals("quic")) {
            // As a client with a fresh server-list entry would: the advertisement is in hand
            // before the player clicks Join, so the ping isn't part of join time.
            try {
                ad = Advertisement.extract(StatusPing.fetch(address, o.host(),
                        MinecraftCodec.CODEC.getProtocolVersion(), 10_000)).orElse(null);
            } catch (IOException e) {
                return failure(index, warmup, bench, "status ping failed: " + e);
            }
            if (ad == null) {
                return failure(index, warmup, bench, "server does not advertise QUIC");
            }
        }

        long start = System.nanoTime();
        ClientNetworkSession session;
        if (ad != null) {
            QuicByteStream stream;
            try {
                stream = QuicClient.connect(new InetSocketAddress(address.getAddress(), ad.port()), ad.fingerprint(),
                        TransportConfig.DEFAULT).get(o.timeoutNanos(), TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException e) {
                return failure(index, warmup, bench, "QUIC connect failed: " + e);
            }
            session = new QuicClientSession(address, protocol, stream);
        } else {
            session = new ClientNetworkSession(address, protocol, Runnable::run, null, null);
        }
        if (o.online()) {
            session.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new MockSessionService(o.sessionUrl()));
        }
        session.addListener(bench);

        try {
            session.connect(false);
            if (!bench.joined.await(o.timeoutNanos(), TimeUnit.NANOSECONDS) || bench.loginNanos == 0) {
                return failure(index, warmup, bench, "no play login");
            }
            double joinMs = millis(bench.loginNanos - start);

            if (!bench.positioned.await(o.timeoutNanos(), TimeUnit.NANOSECONDS) || bench.position == null) {
                return failure(index, warmup, bench, "no spawn position");
            }
            long deadline = System.nanoTime() + o.timeoutNanos();
            while (System.nanoTime() < deadline && !bench.disconnected
                    && (bench.chunks.get() == 0 || System.nanoTime() - bench.lastChunkNanos < o.quietNanos())) {
                Thread.sleep(20);
            }
            if (bench.disconnected || bench.chunks.get() == 0) {
                return failure(index, warmup, bench, "chunks did not load");
            }
            double chunkLoadMs = millis(bench.lastChunkNanos - bench.loginNanos);

            int pingsSent = play(o, session, bench);
            // Grace period for in-flight pongs.
            long graceEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (bench.rttNanos.size() < pingsSent && System.nanoTime() < graceEnd && !bench.disconnected) {
                Thread.sleep(10);
            }
            if (bench.disconnected) {
                return failure(index, warmup, bench, "disconnected during play");
            }
            double[] rtt = bench.rttNanos.stream().mapToDouble(nanos -> millis(nanos)).sorted().toArray();
            double[] phases = {
                millis(bench.helloNanos - start), millis(bench.loginFinishedNanos - start),
                millis(bench.configurationNanos - start), joinMs,
            };
            return new RunResult(index, warmup, true, phases, joinMs, chunkLoadMs, bench.chunks.get(),
                    percentile(rtt, 0.5), percentile(rtt, 0.95), rtt.length, pingsSent - rtt.length, false, null);
        } finally {
            bench.benchClosing = !bench.disconnected;
            if (session.isConnected()) {
                session.disconnect("bench finished");
            }
            bench.closed.await(5, TimeUnit.SECONDS);
        }
    }

    /** Walks back and forth by 0.1 blocks at 20 Hz, with a ping request every 5th tick. */
    private static int play(Options o, ClientNetworkSession session, BenchSession bench) {
        Vector3d base = bench.position;
        long next = System.nanoTime();
        long end = next + o.playNanos();
        int pings = 0;
        for (int tick = 0; next < end && !bench.disconnected; tick++) {
            double x = base.getX() + ((tick & 1) == 0 ? 0.1 : 0.0);
            session.send(new ServerboundMovePlayerPosPacket(true, false, x, base.getY(), base.getZ()));
            if (tick % PING_EVERY_TICKS == 0) {
                session.send(new ServerboundPingRequestPacket(System.nanoTime()));
                pings++;
            }
            next += TICK_NANOS;
            LockSupport.parkNanos(next - System.nanoTime());
        }
        return pings;
    }

    private static RunResult failure(int index, boolean warmup, BenchSession bench, String what) {
        String error = bench.disconnected ? what + ": " + bench.disconnectReason : what;
        return new RunResult(index, warmup, false, new double[0], -1, -1, bench.chunks.get(), -1, -1, 0, 0,
                bench.disconnected, error);
    }

    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return -1;
        }
        double rank = p * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return percentile(sorted, 0.5);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
