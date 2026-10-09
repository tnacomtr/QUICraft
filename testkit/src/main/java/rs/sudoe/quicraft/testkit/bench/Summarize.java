// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Reads {@code <dir>/batch<k>/<profile>.json} files written by the bench and checks the Phase 0
 * gate: for every profile and metric, each batch's median lies within the tolerance of the
 * median over all batches. Tolerance is {@code max(pct * median, abs-ms)}; see docs/benchmarks.md.
 */
public final class Summarize {
    private record Metric(String name, ToDoubleFunction<JsonObject> value) {}

    private static final List<Metric> METRICS = List.of(
            new Metric("join (ms)", r -> r.get("joinMs").getAsDouble()),
            new Metric("chunk load (ms)", r -> r.get("chunkLoadMs").getAsDouble()),
            new Metric("play RTT (ms)", r -> r.get("rttMedianMs").getAsDouble()));

    private Summarize() {}

    public static int run(Args args) throws IOException {
        Path dir = Path.of(args.required("dir"));
        double pct = args.decimal("tolerance-pct", 10.0) / 100.0;
        double absMs = args.decimal("tolerance-ms", 5.0);

        // profile -> batch -> measured (non-warmup) runs
        Map<String, Map<String, List<JsonObject>>> data = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonObject doc = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                String batch = file.getParent().getFileName().toString();
                List<JsonObject> runs = new ArrayList<>();
                for (JsonElement e : doc.getAsJsonArray("runs")) {
                    JsonObject run = e.getAsJsonObject();
                    if (!run.get("warmup").getAsBoolean()) {
                        runs.add(run);
                    }
                }
                data.computeIfAbsent(doc.get("profile").getAsString(), k -> new TreeMap<>()).put(batch, runs);
            }
        }
        if (data.isEmpty()) {
            System.err.println("no results under " + dir);
            return 1;
        }

        StringBuilder md = new StringBuilder();
        md.append(String.format(Locale.ROOT,
                "Tolerance: max(%.0f%% of the all-batch median, %.1f ms).%n%n", pct * 100, absMs));
        md.append("| Profile | Metric | Batch medians | All-batch median | Max deviation | Allowed | Result |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        boolean pass = true;
        for (var profile : data.entrySet()) {
            Map<String, List<JsonObject>> batches = profile.getValue();
            for (Metric metric : METRICS) {
                Map<String, Double> batchMedians = new LinkedHashMap<>();
                List<Double> all = new ArrayList<>();
                for (var batch : batches.entrySet()) {
                    double[] values = batch.getValue().stream()
                            .filter(r -> r.get("ok").getAsBoolean())
                            .mapToDouble(metric.value()).toArray();
                    for (double v : values) {
                        all.add(v);
                    }
                    batchMedians.put(batch.getKey(), values.length == 0 ? Double.NaN : BenchClient.median(values));
                }
                double overall = BenchClient.median(all.stream().mapToDouble(Double::doubleValue).toArray());
                double maxDev = batchMedians.values().stream()
                        .mapToDouble(m -> Math.abs(m - overall)).max().orElse(Double.NaN);
                double allowed = Math.max(pct * overall, absMs);
                boolean ok = maxDev <= allowed;
                pass &= ok;
                md.append(String.format(Locale.ROOT, "| %s | %s | %s | %.1f | %.1f | %.1f | %s |%n",
                        profile.getKey(), metric.name(), join(batchMedians.values()), overall, maxDev, allowed,
                        ok ? "pass" : "**FAIL**"));
            }
            int disconnects = 0;
            int failures = 0;
            int runs = 0;
            List<String> perBatch = new ArrayList<>();
            for (List<JsonObject> batch : batches.values()) {
                int d = 0;
                for (JsonObject r : batch) {
                    runs++;
                    if (r.get("disconnected").getAsBoolean()) {
                        d++;
                    }
                    if (!r.get("ok").getAsBoolean()) {
                        failures++;
                    }
                }
                disconnects += d;
                perBatch.add(Integer.toString(d));
            }
            boolean ok = disconnects == 0 && failures == 0;
            pass &= ok;
            md.append(String.format(Locale.ROOT, "| %s | disconnects / failed runs | %s | %d / %d of %d runs | | 0 | %s |%n",
                    profile.getKey(), String.join(", ", perBatch), disconnects, failures, runs, ok ? "pass" : "**FAIL**"));
        }
        md.append(String.format(Locale.ROOT, "%nGate: %s%n", pass ? "PASS" : "FAIL"));
        System.out.print(md);
        String out = args.string("markdown", null);
        if (out != null) {
            Files.writeString(Path.of(out), md.toString(), StandardCharsets.UTF_8);
        }
        return pass ? 0 : 1;
    }

    private static String join(Iterable<Double> values) {
        List<String> parts = new ArrayList<>();
        for (double v : values) {
            parts.add(String.format(Locale.ROOT, "%.1f", v));
        }
        return String.join(", ", parts);
    }
}
