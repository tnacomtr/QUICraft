// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Per profile and metric: median per transport and, for each QUIC variant, the bootstrap 95%
 * CI of (QUIC median - TCP median). A difference counts only if its CI excludes zero
 * (docs/benchmarks.md). Also prints QUIC handshake percentiles, the data for the head start.
 */
public final class TransportSummarize {
    private static final String[][] METRICS = {
        {"handshakeMs", "handshake (ms)"}, {"burstMs", "login+chunk burst (ms)"}, {"rttMedianMs", "play RTT (ms)"},
    };
    private static final int RESAMPLES = 5000;

    /** Every QUIC transport, in TransportClient order. */
    private static final String[] QUIC = java.util.Arrays.stream(TransportClient.TRANSPORTS)
            .filter(t -> !t.equals("tcp")).toArray(String[]::new);

    private TransportSummarize() {}

    public static int run(Args args) throws Exception {
        Path dir = Path.of(args.required("dir"));
        StringBuilder md = new StringBuilder();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        Random random = new Random(1);
        md.append("| Profile | Metric | TCP | QUIC Reno | QUIC CUBIC | QUIC BBR | QUIC BBR relaxed |\n"
                + "| --- | --- | --- | --- | --- | --- | --- |\n");
        StringBuilder relaxed = new StringBuilder("\nQUIC BBR with the relaxed loss threshold against plain QUIC BBR"
                + " (difference of medians [bootstrap 95% CI]):\n\n| Profile | Metric | BBR | BBR relaxed | Difference |\n"
                + "| --- | --- | --- | --- | --- |\n");
        StringBuilder handshakes = new StringBuilder(
                "\n| Profile | QUIC handshake p50 | p90 | p99 | max | TCP connect p50 |\n| --- | --- | --- | --- | --- | --- |\n");
        StringBuilder counters = new StringBuilder("\nSender-side counters (median per run):\n\n"
                + "| Profile | Transport | Counters |\n| --- | --- | --- |\n");
        int failures = 0;
        for (Path file : files) {
            JsonObject doc = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            String profile = doc.get("profile").getAsString();
            Map<String, List<JsonObject>> byTransport = new LinkedHashMap<>();
            for (String t : TransportClient.TRANSPORTS) {
                byTransport.put(t, new ArrayList<>());
            }
            for (JsonElement e : doc.getAsJsonArray("runs")) {
                JsonObject r = e.getAsJsonObject();
                if (r.get("warmup").getAsBoolean()) {
                    continue;
                }
                if (!r.get("ok").getAsBoolean()) {
                    failures++;
                    continue;
                }
                byTransport.get(r.get("transport").getAsString()).add(r);
            }
            for (String[] metric : METRICS) {
                double[] tcp = values(byTransport.get("tcp"), metric[0]);
                md.append(String.format(Locale.ROOT, "| %s | %s | %.1f", profile, metric[1], median(tcp)));
                for (String t : QUIC) {
                    double[] q = values(byTransport.get(t), metric[0]);
                    double[] ci = diffCi(q, tcp, random);
                    String mark = ci[0] > 0 || ci[1] < 0 ? "**" : "";
                    md.append(String.format(Locale.ROOT, " | %.1f (%s%+.1f [%+.1f, %+.1f]%s)", median(q), mark,
                            median(q) - median(tcp), ci[0], ci[1], mark));
                }
                md.append(" |\n");
                double[] bbr = values(byTransport.get("quic-bbr"), metric[0]);
                double[] rel = values(byTransport.get("quic-bbr-relaxed"), metric[0]);
                double[] rci = diffCi(rel, bbr, random);
                String rmark = rci[0] > 0 || rci[1] < 0 ? "**" : "";
                relaxed.append(String.format(Locale.ROOT, "| %s | %s | %.1f | %.1f | %s%+.1f [%+.1f, %+.1f]%s |%n",
                        profile, metric[1], median(bbr), median(rel), rmark, median(rel) - median(bbr), rci[0],
                        rci[1], rmark));
            }
            for (Map.Entry<String, List<JsonObject>> e : byTransport.entrySet()) {
                Map<String, List<Double>> perKey = new java.util.TreeMap<>();
                for (JsonObject r : e.getValue()) {
                    if (r.has("senderStats")) {
                        for (Map.Entry<String, JsonElement> kv : r.getAsJsonObject("senderStats").entrySet()) {
                            perKey.computeIfAbsent(kv.getKey(), k -> new ArrayList<>()).add(kv.getValue().getAsDouble());
                        }
                    }
                }
                StringBuilder cells = new StringBuilder();
                perKey.forEach((k, v) -> cells.append(cells.length() == 0 ? "" : ", ").append(k).append(' ')
                        .append(String.format(Locale.ROOT, "%.0f", median(v.stream().mapToDouble(Double::doubleValue).toArray()))));
                counters.append(String.format(Locale.ROOT, "| %s | %s | %s |%n", profile, e.getKey(), cells));
            }
            List<Double> quicHs = new ArrayList<>();
            for (String t : QUIC) {
                for (double v : values(byTransport.get(t), "handshakeMs")) {
                    quicHs.add(v);
                }
            }
            double[] hs = quicHs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            handshakes.append(String.format(Locale.ROOT, "| %s | %.1f | %.1f | %.1f | %.1f | %.1f |%n", profile,
                    pct(hs, 0.5), pct(hs, 0.9), pct(hs, 0.99), hs.length == 0 ? -1 : hs[hs.length - 1],
                    median(values(byTransport.get("tcp"), "handshakeMs"))));
        }
        md.append("\nCells: median (difference to TCP [bootstrap 95% CI]); **bold** where the CI excludes zero.\n");
        md.append(relaxed);
        md.append(handshakes);
        md.append(counters);
        md.append(String.format(Locale.ROOT, "%nFailed runs: %d%n", failures));
        System.out.print(md);
        String out = args.string("markdown", null);
        if (out != null) {
            Files.writeString(Path.of(out), md.toString(), StandardCharsets.UTF_8);
        }
        return failures == 0 ? 0 : 1;
    }

    private static double[] values(List<JsonObject> runs, String key) {
        return runs.stream().mapToDouble(r -> r.get(key).getAsDouble()).toArray();
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        return pct(s, 0.5);
    }

    private static double pct(double[] sorted, double p) {
        return TransportClient.percentile(sorted, p);
    }

    private static double[] diffCi(double[] a, double[] b, Random random) {
        if (a.length == 0 || b.length == 0) {
            return new double[] {Double.NaN, Double.NaN};
        }
        double[] diffs = new double[RESAMPLES];
        double[] ra = new double[a.length];
        double[] rb = new double[b.length];
        for (int i = 0; i < RESAMPLES; i++) {
            for (int j = 0; j < a.length; j++) {
                ra[j] = a[random.nextInt(a.length)];
            }
            for (int j = 0; j < b.length; j++) {
                rb[j] = b[random.nextInt(b.length)];
            }
            diffs[i] = median(ra) - median(rb);
        }
        java.util.Arrays.sort(diffs);
        return new double[] {diffs[(int) (0.025 * RESAMPLES)], diffs[(int) (0.975 * RESAMPLES) - 1]};
    }
}
