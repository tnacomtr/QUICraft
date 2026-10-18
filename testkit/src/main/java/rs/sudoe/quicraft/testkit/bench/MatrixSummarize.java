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
 * when every requested profile has a verified TCP, {@code /2} and {@code /3} cell and every
 * measured run is usable evidence; 1 if any measured run is incompatible, malformed, failed or
 * disconnected, if a successful run carries an impossible metric value, if an artifact carries no
 * usable netem profile, or if {@code /3} evidence is present but invalid; 2 while {@code /3}
 * evidence is absent, which is the expected state before U3.
 *
 * <p>An artifact that cannot be attributed to a netem profile is never dropped silently: the rest of
 * the directory would then pass with part of its evidence missing. Only a known profile that the
 * caller did not request is ignored. A successful run that took no ping sample keeps the bench's
 * {@code rttMedianMs=-1}; that is unavailable RTT evidence, so its cell is reported as not run and
 * never as a median.
 */
public final class MatrixSummarize {
    /** The six profiles defined by testkit/netem/profiles.sh. */
    public static final String DEFAULT_PROFILES = "clean loss delay delayloss reorder jitter";

    /**
     * Profiles this command can compare. An artifact outside this set is only usable when the
     * caller requested its profile explicitly; otherwise it is unclassifiable evidence.
     */
    private static final Set<String> KNOWN_PROFILES = Set.of(DEFAULT_PROFILES.split(" "));

    /** The bench writes this {@code rttMedianMs} for a successful run that took no ping sample. */
    private static final double RTT_UNAVAILABLE = -1;

    private static final Gson GSON = new Gson();

    /** Metric fields a run must carry; Gson would otherwise deserialize a missing one as zero. */
    private static final List<String> REQUIRED_NUMBERS = List.of("joinMs", "chunkLoadMs", "rttMedianMs");

    /** Flags that classify and gate a run; Gson would otherwise default a missing one to false. */
    private static final List<String> REQUIRED_FLAGS = List.of("warmup", "ok", "disconnected");

    /**
     * One metric column. {@code unavailableWhenNegative} is true only for play RTT: the bench writes
     * {@code -1} when a successful run took no ping sample, while a completed join and chunk load can
     * never be negative.
     */
    private record Metric(String label, ToDoubleFunction<RunResult> value, boolean unavailableWhenNegative) {
        /** Why a cell with successful runs has no sample for this metric. */
        String absentDetail() {
            return unavailableWhenNegative
                    ? "RTT unavailable in every successful run (rttMedianMs=" + (int) RTT_UNAVAILABLE + ")"
                    : "no usable " + label + " values";
        }
    }

    private static final List<Metric> METRICS = List.of(
            new Metric("join (ms)", RunResult::joinMs, false),
            new Metric("chunk load (ms)", RunResult::chunkLoadMs, false),
            new Metric("play RTT (ms)", RunResult::rttMedianMs, true));

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
        int incompatibleRuns = 0;
        int malformedRuns = 0;
        int invalidMetricRuns = 0;
        int invalidFiles = 0;
        int unclassifiedFiles = 0;
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
                if (profile == null || profile.isBlank()
                        || (!wanted.contains(profile) && !KNOWN_PROFILES.contains(profile))) {
                    // An artifact whose profile cannot be attributed cannot be compared, and
                    // dropping it would let the rest of the directory pass with evidence missing.
                    unclassifiedFiles++;
                    continue;
                }
                if (!wanted.contains(profile)) {
                    // A known profile the caller did not request makes no claim here.
                    continue;
                }
                for (JsonElement element : doc.getAsJsonArray("runs")) {
                    RunResult run;
                    try {
                        run = parseRun(element);
                    } catch (RuntimeException e) {
                        malformedRuns++;
                        continue;
                    }
                    if (run.warmup()) {
                        continue;
                    }
                    if (run.transportVersion() == null || run.schemaVersion() != BenchmarkSchema.VERSION) {
                        // A measured run from another schema cannot be compared with current
                        // evidence. Counting it as rejected makes mixed evidence fail closed
                        // instead of silently dropping the incompatible half.
                        incompatibleRuns++;
                        continue;
                    }
                    if (run.ok() && !run.disconnected() && !hasUsableMetrics(run)) {
                        // A successful join cannot have a negative duration, and the only negative
                        // RTT the bench records is its "no ping sample" sentinel. Dropping the run
                        // would shrink a sample while the rest of the matrix still looked valid.
                        invalidMetricRuns++;
                        continue;
                    }
                    byProfile.get(profile).add(run);
                    all.add(run);
                }
            }
        }
        if (all.isEmpty()) {
            System.err.println("no usable measured runs under " + dir);
            if (incompatibleRuns + malformedRuns + invalidMetricRuns + invalidFiles
                    + unclassifiedFiles > 0) {
                System.err.println("rejected evidence: incompatible=" + incompatibleRuns
                        + " malformed=" + malformedRuns + " invalidMetrics=" + invalidMetricRuns
                        + " unreadableArtifacts=" + invalidFiles
                        + " unclassifiedArtifacts=" + unclassifiedFiles);
            }
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
        boolean artifactsValid = invalidFiles == 0 && unclassifiedFiles == 0;
        long failedRuns = all.stream().filter(run -> !run.ok()).count();
        long disconnects = all.stream().filter(RunResult::disconnected).count();
        long inconsistent = all.stream().filter(run -> run.ok() && run.disconnected()).count();
        // A disconnect means the join did not survive the bench window, so an ok flag alongside it
        // is contradictory evidence. Either way the matrix may not pass.
        boolean rejected = !artifactsValid || incompatibleRuns > 0 || malformedRuns > 0
                || invalidMetricRuns > 0 || failedRuns > 0 || disconnects > 0;
        boolean complete = rows.stream().allMatch(row -> row.tcp().status() == ComparisonRow.Status.VERIFIED
                && row.quic2().status() == ComparisonRow.Status.VERIFIED
                && row.quic3().status() == ComparisonRow.Status.VERIFIED);
        String gate = anyFailClosed || rejected ? "FAIL-CLOSED" : complete ? "PASS" : "INCOMPLETE";

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
        if (invalidFiles > 0) {
            md.append("Unreadable JSON files skipped: ").append(invalidFiles).append('\n');
        }
        if (unclassifiedFiles > 0) {
            md.append("Unclassifiable artifacts: ").append(unclassifiedFiles)
                    .append(" (no usable netem profile)\n");
        }
        if (incompatibleRuns > 0 || malformedRuns > 0 || inconsistent > 0 || invalidMetricRuns > 0) {
            md.append("Rejected measured runs: ")
                    .append(incompatibleRuns + malformedRuns + inconsistent + invalidMetricRuns)
                    .append(" (incompatible schema or transport: ").append(incompatibleRuns)
                    .append(", missing required fields: ").append(malformedRuns)
                    .append(", success reported after a disconnect: ").append(inconsistent)
                    .append(", impossible metric values: ").append(invalidMetricRuns)
                    .append(")\n");
        }
        md.append("Failed measured runs: ").append(failedRuns)
                .append(" (disconnects: ").append(disconnects).append(")\n");
        md.append("Matrix /3 gate: ").append(gate).append('\n');

        System.out.print(md);
        String out = args.string("markdown", null);
        if (out != null) {
            Files.writeString(Path.of(out), md.toString(), StandardCharsets.UTF_8);
        }

        if (anyFailClosed || rejected) {
            return 1;
        }
        return complete ? 0 : 2;
    }

    /**
     * Deserializes one run only after its required fields are present and typed. Gson fills a
     * missing primitive with zero or false, which would turn a partial artifact into a verified
     * zero-valued metric or hide a disconnect.
     */
    private static RunResult parseRun(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("run is not a JSON object");
        }
        JsonObject run = element.getAsJsonObject();
        for (String field : REQUIRED_NUMBERS) {
            JsonElement value = run.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(value.getAsDouble())) {
                throw new IllegalArgumentException("run is missing a finite numeric " + field);
            }
        }
        for (String field : REQUIRED_FLAGS) {
            JsonElement value = run.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("run is missing boolean " + field);
            }
        }
        RunResult parsed = GSON.fromJson(run, RunResult.class);
        if (parsed == null) {
            throw new IllegalArgumentException("run did not deserialize");
        }
        return parsed;
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

    /**
     * True when a successful run carries plausible metrics. A completed join and chunk load cannot
     * be negative, and the only negative RTT the bench writes is {@link #RTT_UNAVAILABLE} for a run
     * that took no ping sample.
     */
    private static boolean hasUsableMetrics(RunResult run) {
        return run.joinMs() >= 0 && run.chunkLoadMs() >= 0
                && (run.rttMedianMs() >= 0 || run.rttMedianMs() == RTT_UNAVAILABLE);
    }

    private static ComparisonRow.Cell measured(List<RunResult> runs, TransportVersion version, Metric metric) {
        List<RunResult> matching = runs.stream().filter(run -> run.transportVersion() == version).toList();
        if (matching.isEmpty()) {
            return ComparisonRow.Cell.notRun(version, "no measured runs");
        }
        List<RunResult> successful = matching.stream()
                .filter(run -> run.ok() && !run.disconnected())
                .toList();
        if (successful.isEmpty()) {
            return ComparisonRow.Cell.notRun(version, "no successful runs");
        }
        double[] values = successful.stream()
                .mapToDouble(metric.value())
                .filter(value -> value >= 0)
                .toArray();
        if (values.length == 0) {
            // No median here: -1 RTT means the run took no sample, and a negative time would
            // otherwise have been rejected as invalid evidence before reaching this point.
            return ComparisonRow.Cell.notRun(version, metric.absentDetail());
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

        List<RunResult> successful = matching.stream()
                .filter(run -> run.ok() && !run.disconnected())
                .toList();
        double[] values = successful.stream()
                .mapToDouble(metric.value())
                .filter(value -> value >= 0)
                .toArray();
        if (values.length == 0) {
            // A /3 run with no RTT sample is verified evidence with that metric unavailable, not
            // invalid evidence; join and chunk load cannot be negative, so their absence stays
            // fail-closed.
            return metric.unavailableWhenNegative()
                    ? ComparisonRow.Cell.notRun(TransportVersion.QUIC_3, metric.absentDetail())
                    : ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3,
                            "no successful /3 values for this metric");
        }
        return ComparisonRow.Cell.verified(TransportVersion.QUIC_3, BenchClient.median(values), values.length,
                "R=" + digest.substring(0, 12) + " schema=" + routingSchema + " streams=" + streams
                        + " alpn=" + TransportVersion.QUIC_3.alpn() + " commit=" + commit);
    }
}
