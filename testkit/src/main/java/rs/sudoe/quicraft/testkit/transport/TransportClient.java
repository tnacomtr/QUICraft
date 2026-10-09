// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.transport;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Transport benchmark client: per run, connects over one transport (rotating TCP, QUIC-RENO,
 * QUIC-CUBIC, QUIC-BBR so all see the same conditions), and records handshake time, burst time
 * (login + chunk burst) and the median play RTT.
 */
public final class TransportClient {
    record Run(String transport, int run, boolean warmup, boolean ok, double handshakeMs, double burstMs,
            double rttMedianMs, double rttP95Ms, int rttSamples, java.util.Map<String, Long> senderStats,
            String error) {}

    static final String[] TRANSPORTS = {"tcp", "quic-reno", "quic-cubic", "quic-bbr"};

    private TransportClient() {}

    public static int run(Args args) throws Exception {
        String host = args.string("host", "transportserver");
        int port = args.integer("port", 30000);
        String profile = args.string("profile", "unknown");
        int runs = args.integer("runs", 10);
        int warmup = args.integer("warmup", 1);
        long playNanos = TimeUnit.MILLISECONDS.toNanos(args.integer("play-ms", 3000));
        Fingerprint fp = Fingerprint.parse(Files.readString(Path.of(args.required("fingerprint-file"))).trim());
        InetAddress address = InetAddress.getByName(host);

        List<Run> results = new ArrayList<>();
        for (int i = 0; i < warmup + runs; i++) {
            for (int t = 0; t < TRANSPORTS.length; t++) {
                // Rotate the order each round so no transport always goes first.
                String transport = TRANSPORTS[(t + i) % TRANSPORTS.length];
                Run r = once(transport, i, i < warmup, address, port, fp, playNanos);
                results.add(r);
                System.out.printf("[%s] %-10s run %d%s ok=%s handshake=%.1fms burst=%.1fms rtt=%.2fms p95=%.2fms%s%n",
                        profile, transport, i, r.warmup() ? " (warmup)" : "", r.ok(), r.handshakeMs(), r.burstMs(),
                        r.rttMedianMs(), r.rttP95Ms(), r.error() == null ? "" : " error=" + r.error());
                Thread.sleep(300);
            }
        }
        JsonObject doc = new JsonObject();
        doc.addProperty("profile", profile);
        doc.addProperty("playMs", TimeUnit.NANOSECONDS.toMillis(playNanos));
        doc.addProperty("loginBytes", TransportServer.LOGIN_BYTES);
        doc.addProperty("chunkBytes", TransportServer.CHUNK_BYTES);
        doc.addProperty("finishedAt", Instant.now().toString());
        var gson = new GsonBuilder().setPrettyPrinting().create();
        doc.add("runs", gson.toJsonTree(results));
        Path out = Path.of(args.required("out"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, gson.toJson(doc), StandardCharsets.UTF_8);
        return results.stream().allMatch(Run::ok) ? 0 : 1;
    }

    private static Run once(String transport, int index, boolean warmup, InetAddress address, int port,
            Fingerprint fp, long playNanos) {
        long start = System.nanoTime();
        Conn conn = null;
        try {
            if (transport.equals("tcp")) {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(address, port), 10_000);
                conn = Conn.tcp(s);
            } else {
                TransportConfig.CongestionControl cc = TransportConfig.CongestionControl.valueOf(
                        transport.substring("quic-".length()).toUpperCase());
                conn = Conn.quic(QuicClient.connect(new InetSocketAddress(address, port + 1 + cc.ordinal()), fp,
                        TransportConfig.builder().congestionControl(cc).build()).get(10, TimeUnit.SECONDS));
            }
            double handshakeMs = millis(System.nanoTime() - start);

            long burstStart = System.nanoTime();
            conn.send(Conn.HELLO, new byte[0]);
            while (conn.receive()[0] != Conn.END_BURST) {
                // login and chunk messages
            }
            double burstMs = millis(System.nanoTime() - burstStart);

            ConcurrentLinkedQueue<Long> rtts = new ConcurrentLinkedQueue<>();
            java.util.concurrent.CompletableFuture<java.util.Map<String, Long>> stats =
                    new java.util.concurrent.CompletableFuture<>();
            Conn c = conn;
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        byte[] f = c.receive();
                        if (f[0] == Conn.STATS) {
                            java.util.Map<String, Long> m = new java.util.LinkedHashMap<>();
                            String text = new String(f, 1, f.length - 1, StandardCharsets.US_ASCII);
                            for (String kv : text.split(",")) {
                                int eq = kv.indexOf('=');
                                if (eq > 0) {
                                    m.put(kv.substring(0, eq), Long.parseLong(kv.substring(eq + 1)));
                                }
                            }
                            stats.complete(m);
                        } else if (f[0] == Conn.PONG) {
                            long sent = 0;
                            for (int i = 1; i <= 8; i++) {
                                sent = (sent << 8) | (f[i] & 0xFF);
                            }
                            rtts.add(System.nanoTime() - sent);
                        }
                    }
                } catch (Exception ignored) {
                    // closed
                }
            });
            byte[] ping = new byte[64];
            long next = System.nanoTime();
            long end = next + playNanos;
            while (next < end) {
                long now = System.nanoTime();
                for (int i = 0; i < 8; i++) {
                    ping[i] = (byte) (now >>> (56 - 8 * i));
                }
                c.send(Conn.PING, ping);
                next += TimeUnit.MILLISECONDS.toNanos(50);
                LockSupport.parkNanos(next - System.nanoTime());
            }
            Thread.sleep(500); // in-flight pongs
            c.send(Conn.BYE, new byte[0]);
            java.util.Map<String, Long> senderStats;
            try {
                senderStats = stats.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                senderStats = java.util.Map.of();
            }
            double[] sorted = rtts.stream().mapToDouble(TransportClient::millis).sorted().toArray();
            reader.interrupt();
            return new Run(transport, index, warmup, sorted.length > 0, handshakeMs, burstMs,
                    percentile(sorted, 0.5), percentile(sorted, 0.95), sorted.length, senderStats,
                    sorted.length > 0 ? null : "no pongs");
        } catch (Exception e) {
            return new Run(transport, index, warmup, false, -1, -1, -1, -1, 0, java.util.Map.of(), String.valueOf(e));
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
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

    private static double millis(long nanos) {
        return nanos / 1e6;
    }

}
