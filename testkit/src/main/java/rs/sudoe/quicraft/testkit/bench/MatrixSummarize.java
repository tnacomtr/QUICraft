// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import com.google.gson.Gson;
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
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Emits TCP vs {@code /2} vs {@code /3} comparison rows per netem profile from bench result files.
 *
 * <p>This command adds no measurements. It only groups what the bench wrote and applies the
 * fail-closed {@code /3} assertion: a {@code /3} cell is verified only when every contributing
 * {@code /3} run passed {@link NegotiatedTransportCheck}. A {@code /2} run is never promoted, and a
 * rejected or missing {@code /3} arm is printed without a median. The process exit status is 0 only
 * when every requested profile has a verified TCP, {@code /2} and {@code /3} cell; 1 if {@code /3}
 * evidence is present but invalid; 2 while {@code /3} evidence is absent, which is the expected
 * state before U3.
 */
public final class MatrixSummarize {
    /** The six profiles defined by testkit/netem/profiles.sh. */
    public static final String DEFAULT_PROFILES = "clean loss delay delayloss reorder jitter";

    private static final Gson GSON = new Gson();

    private record Metric(String label, ToDoubleFunction<RunResult> value) {}

    private static final List<Metric> METRICS = List.of(
            new Metric("join (ms)", RunResult::joinMs),
            new Metric("chunk load (ms)", RunResult::chunkLoadMs),
            new Metric("play RTT (ms)", RunResult::rttMedianMs));

    private MatrixSummarize() {}

    public static int run(Args args) throws IOException {
        Path dir = Path.of(args.required("dir"));
        String requested = args.string("profiles", DEFAULT_PROFILES).trim();
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("--profiles must name at least one netem profile");
        }
        List<String> profiles = List.of(requested.split("\\s+"));
        Set<String> wanted = Set.copyOf(profiles);

        Map<String, List<RunResult>> byProfile = new LinkedHashMap<>();
        for (String profile : profiles) {
            byProfile.put(profile, new ArrayList<>());
        }
        int unclassified = 0;
        int invalidFiles = 0;
        List<RunResult> all = new ArrayList<>();

        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonObject doc;
                try {
                    doc = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (RuntimeException e) {
                    invalidFiles++;
                    continue;
                }
                if (!doc.has("runs") || !doc.get("runs").isJsonArray()) {
                    invalidFiles++;
                    continue;
                }
                String profile = profileOf(doc);
                if (profile == null || !wanted.contains(profile)) {
                    continue;
                }
                for (JsonElement element : doc.getAsJsonArray("runs")) {
                    RunResult run;
                    try {
                        run = GSON.fromJson(element, RunResult.class);
                    } catch (RuntimeException e) {
                        invalidFiles++;
                        continue;
                    }
                    if (run == null || run.warmup()) {
                        continue;
                    }
                    if (run.transportVersion() == null || run.schemaVersion() != BenchmarkSchema.VERSION) {
                        // Legacy results, and anything from a different schema, are never compared.
                        unclassified++;
                        continue;
                    }
                    byProfile.get(profile).add(run);
                    all.add(run);
                }
            }
        }
        if (all.isEmpty()) {
            System.err.println("no classified measured runs under " + dir);
            return 1;
        }

        List<ComparisonRow> rows = new ArrayList<>();
        for (String profile : profiles) {
            List<RunResult> runs = byProfile.get(profile);
            for (Metric metric : METRICS) {
                rows.add(new ComparisonRow(profile, metric.label(),
                        measured(runs, TransportVersion.TCP, metric),
                        measured(runs, TransportVersion.QUIC_2, metric),
                        quic3(runs, metric)));
            }
        }

        boolean anyFailClosed = rows.stream()
                .anyMatch(row -> row.quic3().status() == ComparisonRow.Status.FAIL_CLOSED);
        boolean artifactsValid = invalidFiles == 0;
        long failedRuns = all.stream().filter(run -> !run.ok()).count();
        long disconnects = all.stream().filter(RunResult::disconnected).count();
        boolean complete = rows.stream().allMatch(row -> row.tcp().status() == ComparisonRow.Status.VERIFIED
                && row.quic2().status() == ComparisonRow.Status.VERIFIED
                && row.quic3().status() == ComparisonRow.Status.VERIFIED);
        String gate = anyFailClosed || !artifactsValid || failedRuns > 0
                ? "FAIL-CLOSED" : complete ? "PASS" : "INCOMPLETE";

        StringBuilder md = new StringBuilder();
        md.append("Transport-neutral comparison rows. Medians are computed from existing run artifacts,")
                .append(" not from new measurements.\n");
        md.append("/3 is fail-closed: a cell is verified only with negotiated quicraft/3 evidence and")
                .append(" routing table schema ").append(BenchmarkSchema.ROUTING_SCHEMA_V3).append(".\n\n");
        md.append("| Profile | Metric | TCP | QUIC /2 | QUIC /3 | /3 evidence |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        for (ComparisonRow row : rows) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s |%n",
                    row.profile(), row.metric(), row.tcp().render(), row.quic2().render(),
                    row.quic3().render(), row.quic3().detail()));
        }
        md.append('\n');
        if (unclassified > 0) {
            md.append("Unclassified measured runs (legacy result or different schema): ")
                    .append(unclassified).append('\n');
        }
        if (invalidFiles > 0) {
            md.append("Unreadable JSON files skipped: ").append(invalidFiles).append('\n');
        }
        md.append("Failed measured runs: ").append(failedRuns)
                .append(" (disconnects: ").append(disconnects).append(")\n");
        md.append("Matrix /3 gate: ").append(gate).append('\n');

        System.out.print(md);
        String out = args.string("markdown", null);
        if (out != null) {
            Files.writeString(Path.of(out), md.toString(), StandardCharsets.UTF_8);
        }

        if (anyFailClosed) {
            return 1;
        }
        if (!artifactsValid || failedRuns > 0) {
            return 1;
        }
        return complete ? 0 : 2;
    }

    /** The netem profile a result file belongs to, with a fallback for pre-{@code netemProfile} files. */
    static String profileOf(JsonObject doc) {
        if (doc.has("netemProfile") && doc.get("netemProfile").isJsonPrimitive()) {
            String netem = doc.get("netemProfile").getAsString();
            if (!netem.isBlank()) {
                return netem;
            }
        }
        if (!doc.has("profile") || !doc.get("profile").isJsonPrimitive()) {
            return null;
        }
        return baseProfile(doc.get("profile").getAsString());
    }

    /** Strips the transport suffix that {@code run-baseline.sh} adds when several transports run. */
    static String baseProfile(String label) {
        for (String suffix : List.of("-quic-0rtt", "-quic-3", "-quic", "-tcp")) {
            if (label.endsWith(suffix)) {
                return label.substring(0, label.length() - suffix.length());
            }
        }
        return label;
    }

    private static ComparisonRow.Cell measured(List<RunResult> runs, TransportVersion version, Metric metric) {
        List<RunResult> matching = runs.stream().filter(run -> run.transportVersion() == version).toList();
        if (matching.isEmpty()) {
            return ComparisonRow.Cell.notRun(version, "no measured runs");
        }
        double[] values = matching.stream()
                .filter(RunResult::ok)
                .mapToDouble(metric.value())
                .filter(value -> value >= 0)
                .toArray();
        if (values.length == 0) {
            return ComparisonRow.Cell.notRun(version, "no successful runs");
        }
        return ComparisonRow.Cell.verified(version, BenchClient.median(values), values.length,
                "median of successful runs");
    }

    private static ComparisonRow.Cell quic3(List<RunResult> runs, Metric metric) {
        List<RunResult> matching = runs.stream()
                .filter(run -> run.transportVersion() == TransportVersion.QUIC_3)
                .toList();
        if (matching.isEmpty()) {
            return ComparisonRow.Cell.notRun(TransportVersion.QUIC_3,
                    "no negotiated /3 runs; the U3 driver has not run");
        }

        String routingId = null;
        String digest = null;
        int routingSchema = -1;
        String commit = null;
        int streams = -1;
        for (RunResult run : matching) {
            NegotiatedTransportCheck.Result result =
                    NegotiatedTransportCheck.check(TransportVersion.QUIC_3, run);
            if (!result.verified()) {
                return ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3, result.detail());
            }
            if (routingId == null) {
                routingId = run.routingTableId();
                digest = run.routingTableDigest();
                routingSchema = run.routingTableSchema();
                commit = run.buildCommit();
                streams = run.streamCount();
            } else if (!routingId.equals(run.routingTableId())
                    || !digest.equals(run.routingTableDigest())
                    || routingSchema != run.routingTableSchema()) {
                return ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3,
                        "routing table identity changed between /3 runs");
            } else if (!commit.equals(run.buildCommit())) {
                return ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3,
                        "build commit changed between /3 runs");
            } else if (streams != run.streamCount()) {
                return ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3,
                        "stream count changed between /3 runs");
            }
        }

        double[] values = matching.stream()
                .filter(RunResult::ok)
                .mapToDouble(metric.value())
                .filter(value -> value >= 0)
                .toArray();
        if (values.length == 0) {
            return ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3,
                    "no successful /3 values for this metric");
        }
        return ComparisonRow.Cell.verified(TransportVersion.QUIC_3, BenchClient.median(values), values.length,
                "R=" + digest.substring(0, 12) + " schema=" + routingSchema + " streams=" + streams
                        + " alpn=" + TransportVersion.QUIC_3.alpn() + " commit=" + commit);
    }
}
