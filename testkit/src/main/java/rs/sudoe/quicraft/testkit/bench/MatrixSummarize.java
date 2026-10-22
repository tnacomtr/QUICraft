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

    /** /3 variants the arm writer can label, see ExternalArmGameTest's armVariant property. */
    private static final Set<String> KNOWN_VARIANTS = Set.of("plain", "busy");

    /** The bench writes this {@code rttMedianMs} for a successful run that took no ping sample. */
    private static final double RTT_UNAVAILABLE = -1;

    private static final Gson GSON = new Gson();

    /** One measured run plus the /3 variant its writer recorded, so variants never mix silently. */
    private record ParsedRun(RunResult run, String variant) {}

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
        // --variants plain,busy splits the /3 arm into its labelled variants. Without it, busy runs
        // are rejected rather than silently mixed into the plain median.
        String variantList = args.string("variants", "").trim();
        if (variantList.equals("true")) {
            variantList = "plain,busy";
        }
        boolean variantMode = !variantList.isEmpty();
        List<String> variants = variantMode ? List.of(variantList.split("[,\\s]+")) : List.of();
        if (variantMode) {
            for (String variant : variants) {
                if (!KNOWN_VARIANTS.contains(variant)) {
                    throw new IllegalArgumentException("--variants takes plain, busy, got " + variant);
                }
            }
        }
        Set<String> wantedVariants = Set.copyOf(variants);
        // --moving: every successful run must prove the moving window actually moved.
        boolean moving = args.flag("moving");
        String manifestPath = args.string("manifest", Files.exists(dir.resolve("runs.tsv"))
                ? dir.resolve("runs.tsv").toString() : null);
        List<String> arms = new ArrayList<>(List.of("tcp"));
        if (variantMode) {
            for (String variant : variants) arms.add(variant.equals("busy") ? "quic3-busy" : "quic3");
        } else {
            arms.addAll(List.of("quic2", "quic3"));
        }
        RunManifest manifest = manifestPath == null ? null
                : RunManifest.read(Path.of(manifestPath), dir, profiles, arms);

        Map<String, List<ParsedRun>> byProfile = new LinkedHashMap<>();
        for (String profile : profiles) {
            byProfile.put(profile, new ArrayList<>());
        }
        int incompatibleRuns = 0;
        int malformedRuns = 0;
        int invalidMetricRuns = 0;
        int variantRuns = 0;
        int mislabeledRuns = 0;
        int invalidMovingRuns = 0;
        int invalidFiles = 0;
        int unclassifiedFiles = 0;
        List<RunResult> all = new ArrayList<>();

        // Legacy summaries without a manifest retain their old directory input contract.
        // Matrix runners always provide a manifest: no status/config/archive file is discovered.
        try (Stream<Path> files = manifest == null ? Files.walk(dir) : manifest.files.keySet().stream()) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonObject doc;
                try {
                    doc = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (RuntimeException | IOException e) {
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
                if (manifest != null && doc.getAsJsonArray("runs").size() != 1) {
                    invalidFiles++;
                    continue;
                }
                for (JsonElement element : doc.getAsJsonArray("runs")) {
                    RunResult run;
                    JsonObject raw;
                    try {
                        raw = element.getAsJsonObject();
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
                    String variant = variantOf(raw);
                    if (manifest != null && !manifest.matches(file, profile, run, variant)) {
                        malformedRuns++;
                        continue;
                    }
                    if (variantMode) {
                        if (!wantedVariants.contains(variant)) {
                            if (KNOWN_VARIANTS.contains(variant)) {
                                continue; // a known variant the caller did not request
                            }
                            variantRuns++;
                            continue;
                        }
                        // A busy-labelled run without the timer on, or a plain one with it on, is
                        // mislabelled evidence: the row's numbers would be attributed to the wrong arm.
                        if (variant.equals("busy") != busyTimerOf(raw)) {
                            mislabeledRuns++;
                            continue;
                        }
                    } else if (!variant.equals("plain")) {
                        // Mixing a busy run into the plain /3 median would hide which arm produced it.
                        variantRuns++;
                        continue;
                    }
                    if (run.ok() && !run.disconnected() && !hasUsableMetrics(run)) {
                        // A successful join cannot have a negative duration, and the only negative
                        // RTT the bench records is its "no ping sample" sentinel. Dropping the run
                        // would shrink a sample while the rest of the matrix still looked valid.
                        invalidMetricRuns++;
                        continue;
                    }
                    if (moving && run.ok() && !run.disconnected() && !moved(raw)) {
                        // A successful moving run that recorded no movement is not moving evidence.
                        invalidMovingRuns++;
                        continue;
                    }
                    byProfile.get(profile).add(new ParsedRun(run, variant));
                    all.add(run);
                    if (manifest != null && run.ok() && !run.disconnected()
                            && (run.transportVersion() != TransportVersion.QUIC_3
                                || NegotiatedTransportCheck.check(TransportVersion.QUIC_3, run).verified())) {
                        manifest.passed.add(manifest.files.get(file).key());
                    }
                }
            }
        }
        if (all.isEmpty() && manifest == null) {
            System.err.println("no usable measured runs under " + dir);
            if (incompatibleRuns + malformedRuns + invalidMetricRuns + invalidFiles
                    + unclassifiedFiles + variantRuns + mislabeledRuns + invalidMovingRuns > 0) {
                System.err.println("rejected evidence: incompatible=" + incompatibleRuns
                        + " malformed=" + malformedRuns + " invalidMetrics=" + invalidMetricRuns
                        + " unreadableArtifacts=" + invalidFiles
                        + " unclassifiedArtifacts=" + unclassifiedFiles
                        + " variantMismatches=" + variantRuns
                        + " mislabeledVariants=" + mislabeledRuns
                        + " nonMovingRuns=" + invalidMovingRuns);
            }
            return 1;
        }

        // Per-metric verification. In variant mode the /3 identity (routing table, build commit,
        // stream count) must hold across every requested variant, not just within each one.
        List<String[]> tableRows = new ArrayList<>();
        List<ComparisonRow> rows = new ArrayList<>();
        boolean complete = manifest == null || manifest.complete();
        boolean anyFailClosed = false;
        for (String profile : profiles) {
            List<ParsedRun> runs = byProfile.get(profile);
            String identityProblem = variantMode ? identityProblem(quic3Runs(runs)) : null;
            for (Metric metric : METRICS) {
                ComparisonRow.Cell tcp = measured(runs, TransportVersion.TCP, metric);
                if (variantMode) {
                    List<ComparisonRow.Cell> cells = new ArrayList<>();
                    for (String variant : variants) {
                        cells.add(identityProblem != null
                                ? ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3, identityProblem)
                                : quic3Cell(runs, variant, metric));
                    }
                    List<String> evidence = new ArrayList<>();
                    for (ComparisonRow.Cell cell : cells) {
                        evidence.add(cell.detail());
                    }
                    String[] row = new String[4 + cells.size()];
                    row[0] = profile;
                    row[1] = metric.label();
                    row[2] = manifest == null ? tcp.render() : manifest.render(profile, "tcp", tcp.render());
                    for (int i = 0; i < cells.size(); i++) {
                        String arm = variants.get(i).equals("busy") ? "quic3-busy" : "quic3";
                        row[3 + i] = manifest == null ? cells.get(i).render()
                                : manifest.render(profile, arm, cells.get(i).render());
                    }
                    row[3 + cells.size()] = String.join("; ", evidence);
                    tableRows.add(row);
                    boolean verified = tcp.status() == ComparisonRow.Status.VERIFIED
                            && cells.stream().allMatch(c -> c.status() == ComparisonRow.Status.VERIFIED);
                    complete &= verified;
                    anyFailClosed |= cells.stream()
                            .anyMatch(c -> c.status() == ComparisonRow.Status.FAIL_CLOSED);
                } else {
                    ComparisonRow row = new ComparisonRow(profile, metric.label(), tcp,
                            measured(runs, TransportVersion.QUIC_2, metric),
                            quic3(runs, metric));
                    rows.add(row);
                    anyFailClosed |= row.quic3().status() == ComparisonRow.Status.FAIL_CLOSED;
                    complete &= row.tcp().status() == ComparisonRow.Status.VERIFIED
                            && row.quic2().status() == ComparisonRow.Status.VERIFIED
                            && row.quic3().status() == ComparisonRow.Status.VERIFIED;
                }
            }
        }

        boolean artifactsValid = invalidFiles == 0 && unclassifiedFiles == 0;
        long failedRuns = all.stream().filter(run -> !run.ok()).count();
        long disconnects = all.stream().filter(RunResult::disconnected).count();
        long inconsistent = all.stream().filter(run -> run.ok() && run.disconnected()).count();
        // A disconnect means the join did not survive the bench window, so an ok flag alongside it
        // is contradictory evidence. Either way the matrix may not pass.
        int rejectedEvidence = incompatibleRuns + malformedRuns + invalidMetricRuns + variantRuns
                + mislabeledRuns + invalidMovingRuns;
        boolean rejected = !artifactsValid || rejectedEvidence > 0 || failedRuns > 0 || disconnects > 0;
        String gate = anyFailClosed || rejected ? "FAIL-CLOSED" : complete ? "PASS" : "INCOMPLETE";

        StringBuilder md = new StringBuilder();
        if (manifest != null) manifest.append(md);
        if (variantMode) {
            md.append("Variant comparison rows (/3 ").append(String.join(", ", variants))
                    .append("). Medians are computed from existing run artifacts, not from new")
                    .append(" measurements.\n");
            md.append("/3 is fail-closed: a cell is verified only with negotiated quicraft/3 evidence,")
                    .append(" routing table schema ").append(BenchmarkSchema.ROUTING_SCHEMA_V3)
                    .append(", and a variant/timer label the row itself carries.\n\n");
            md.append("| Profile | Metric | TCP |");
            for (String variant : variants) {
                md.append(" QUIC /3 ").append(variant).append(" |");
            }
            md.append(" /3 evidence |\n| --- | --- | --- |");
            for (int i = 0; i < variants.size(); i++) {
                md.append(" --- |");
            }
            md.append(" --- |\n");
            for (String[] row : tableRows) {
                md.append("| ").append(String.join(" | ", row)).append(" |\n");
            }
        } else {
            md.append("Transport-neutral comparison rows. Medians are computed from existing run artifacts,")
                    .append(" not from new measurements.\n");
            md.append("/3 is fail-closed: a cell is verified only with negotiated quicraft/3 evidence and")
                    .append(" routing table schema ").append(BenchmarkSchema.ROUTING_SCHEMA_V3).append(".\n\n");
            md.append("| Profile | Metric | TCP | QUIC /2 | QUIC /3 | /3 evidence |\n");
            md.append("| --- | --- | --- | --- | --- | --- |\n");
            for (ComparisonRow row : rows) {
                md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s |%n",
                        row.profile(), row.metric(),
                        manifest == null ? row.tcp().render() : manifest.render(row.profile(), "tcp", row.tcp().render()),
                        manifest == null ? row.quic2().render() : manifest.render(row.profile(), "quic2", row.quic2().render()),
                        manifest == null ? row.quic3().render() : manifest.render(row.profile(), "quic3", row.quic3().render()),
                        row.quic3().detail()));
            }
        }
        md.append('\n');
        if (variantMode) {
            appendArmStats(md, profiles, byProfile, variants);
        }
        if (invalidFiles > 0) {
            md.append("Unreadable JSON files skipped: ").append(invalidFiles).append('\n');
        }
        if (unclassifiedFiles > 0) {
            md.append("Unclassifiable artifacts: ").append(unclassifiedFiles)
                    .append(" (no usable netem profile)\n");
        }
        if (rejectedEvidence > 0 || inconsistent > 0) {
            md.append("Rejected measured runs: ").append(rejectedEvidence + inconsistent)
                    .append(" (incompatible schema or transport: ").append(incompatibleRuns)
                    .append(", missing required fields: ").append(malformedRuns)
                    .append(", success reported after a disconnect: ").append(inconsistent)
                    .append(", impossible metric values: ").append(invalidMetricRuns)
                    .append(", run outside the requested /3 variants: ").append(variantRuns)
                    .append(", variant/timer label mismatch: ").append(mislabeledRuns)
                    .append(", successful moving run that did not move: ").append(invalidMovingRuns)
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

    /**
     * The /3 variant a row recorded. Rows written before variants existed are plain: they are only
     * ever accepted in non-variant mode, where a busy run is rejected instead of mixed in.
     */
    static String variantOf(JsonObject run) {
        if (!run.has("variant") || !run.get("variant").isJsonPrimitive()) {
            return "plain";
        }
        String variant = run.get("variant").getAsString();
        return variant.isBlank() ? "plain" : variant;
    }

    /** The row's own busy-timer flag; a missing field is false, as the writer's default. */
    private static boolean busyTimerOf(JsonObject run) {
        return run.has("busyTimer") && run.get("busyTimer").isJsonPrimitive()
                && run.get("busyTimer").getAsJsonPrimitive().isBoolean() && run.get("busyTimer").getAsBoolean();
    }

    /** Same sustained-load thresholds as ExternalArmGameTest: 0.5 m/s, one new chunk/5 s. */
    private static boolean moved(JsonObject run) {
        if (!run.has("moved") || !run.get("moved").isJsonPrimitive()
                || !run.get("moved").getAsJsonPrimitive().isBoolean() || !run.get("moved").getAsBoolean()) {
            return false;
        }
        double distance = finite(run, "moveDistanceM", -1);
        double chunks = finite(run, "moveChunks", 0);
        double seconds = finite(run, "moveSeconds", -1);
        return seconds > 0 && distance >= 0.5 * seconds && chunks >= Math.ceil(seconds / 5.0);
    }

    private static double finite(JsonObject run, String field, double fallback) {
        if (!run.has(field) || !run.get(field).isJsonPrimitive()
                || !run.get(field).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        double value = run.get(field).getAsDouble();
        return Double.isFinite(value) ? value : fallback;
    }

    private static ComparisonRow.Cell measured(List<ParsedRun> runs, TransportVersion version, Metric metric) {
        List<RunResult> matching = runs.stream().map(ParsedRun::run)
                .filter(run -> run.transportVersion() == version).toList();
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

    private static List<ParsedRun> quic3Runs(List<ParsedRun> runs) {
        List<ParsedRun> matching = new ArrayList<>();
        for (ParsedRun run : runs) {
            if (run.run().transportVersion() == TransportVersion.QUIC_3) {
                matching.add(run);
            }
        }
        return matching;
    }

    /**
     * One check for the identity every {@code /3} row must share: negotiated evidence, then the
     * routing table, the build commit and the stream count. Returns the failure detail, or null.
     */
    private static String identityProblem(List<ParsedRun> matching) {
        String routingId = null;
        String digest = null;
        int routingSchema = -1;
        String commit = null;
        int streams = -1;
        for (ParsedRun parsed : matching) {
            RunResult run = parsed.run();
            NegotiatedTransportCheck.Result result =
                    NegotiatedTransportCheck.check(TransportVersion.QUIC_3, run);
            if (!result.verified()) {
                return result.detail();
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
                return "routing table identity changed between /3 runs";
            } else if (!commit.equals(run.buildCommit())) {
                return "build commit changed between /3 runs";
            } else if (streams != run.streamCount()) {
                return "stream count changed between /3 runs";
            }
        }
        return null;
    }

    private static ComparisonRow.Cell quic3(List<ParsedRun> runs, Metric metric) {
        return quic3Cell(runs, null, metric);
    }

    /**
     * The {@code /3} cell of one variant (null: no variant split), verified only when every
     * contributing run carries negotiated evidence and the identity is unchanged.
     */
    private static ComparisonRow.Cell quic3Cell(List<ParsedRun> runs, String variant, Metric metric) {
        List<ParsedRun> matching = new ArrayList<>();
        for (ParsedRun run : runs) {
            if (run.run().transportVersion() == TransportVersion.QUIC_3
                    && (variant == null || variant.equals(run.variant()))) {
                matching.add(run);
            }
        }
        if (matching.isEmpty()) {
            return ComparisonRow.Cell.notRun(TransportVersion.QUIC_3, variant == null
                    ? "no negotiated /3 runs; the U3 driver has not run"
                    : "no " + variant + " variant runs");
        }

        String problem = identityProblem(matching);
        if (problem != null) {
            return ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3,
                    variant == null ? problem : "variant=" + variant + "; " + problem);
        }

        RunResult first = matching.get(0).run();
        String routingSchemaDetail = "R=" + first.routingTableDigest().substring(0, 12)
                + " schema=" + first.routingTableSchema() + " streams=" + first.streamCount()
                + " alpn=" + TransportVersion.QUIC_3.alpn() + " commit=" + first.buildCommit();
        List<RunResult> successful = matching.stream().map(ParsedRun::run)
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
                    ? ComparisonRow.Cell.notRun(TransportVersion.QUIC_3, variant == null
                            ? metric.absentDetail() : "variant=" + variant + "; " + metric.absentDetail())
                    : ComparisonRow.Cell.failClosed(TransportVersion.QUIC_3,
                            "no successful /3" + (variant == null ? "" : " " + variant)
                                    + " values for this metric");
        }
        return ComparisonRow.Cell.verified(TransportVersion.QUIC_3, BenchClient.median(values), values.length,
                (variant == null ? "" : "variant=" + variant + " ") + routingSchemaDetail);
    }

    /** One arm's successful values, plus round -> value for the paired comparison. */
    private record ArmValues(List<Double> values, Map<Integer, Double> byRound) {}

    private static ArmValues armValues(List<ParsedRun> runs, TransportVersion transport, String variant,
            Metric metric) {
        List<Double> values = new ArrayList<>();
        Map<Integer, Double> byRound = new LinkedHashMap<>();
        for (ParsedRun parsed : runs) {
            RunResult run = parsed.run();
            if (run.transportVersion() != transport || !run.ok() || run.disconnected()) {
                continue;
            }
            if (variant != null && !variant.equals(parsed.variant())) {
                continue;
            }
            double value = metric.value().applyAsDouble(run);
            if (value < 0) {
                continue;
            }
            values.add(value);
            byRound.putIfAbsent(run.run(), value);
        }
        return new ArmValues(values, byRound);
    }

    /**
     * Per-arm medians with their min-max range, and paired round-by-round deltas against TCP.
     * Reporting only: the pass/fail gate above stays the fail-closed one.
     */
    private static void appendArmStats(StringBuilder md, List<String> profiles,
            Map<String, List<ParsedRun>> byProfile, List<String> variants) {
        md.append("Arm medians, min-max and paired deltas vs TCP. Successful runs only; a pair needs")
                .append(" both arms in the same round.\n\n");
        md.append("| Profile | Arm | Metric | Median | Min | Max | n |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        List<String[]> pairs = new ArrayList<>();
        for (String profile : profiles) {
            List<ParsedRun> runs = byProfile.get(profile);
            for (Metric metric : METRICS) {
                ArmValues tcp = armValues(runs, TransportVersion.TCP, null, metric);
                appendArmRow(md, profile, "TCP", metric, tcp);
                for (String variant : variants) {
                    ArmValues arm = armValues(runs, TransportVersion.QUIC_3, variant, metric);
                    String label = "QUIC /3 " + variant;
                    appendArmRow(md, profile, label, metric, arm);
                    appendPair(pairs, profile, label, metric, tcp, arm);
                }
            }
        }
        md.append('\n');
        md.append("| Profile | Arm | Metric | Paired Δ vs TCP median (ms) | Δ min..max (ms) | Δ% median | Pairs |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (String[] pair : pairs) {
            md.append("| ").append(String.join(" | ", pair)).append(" |\n");
        }
        md.append('\n');
    }

    private static void appendArmRow(StringBuilder md, String profile, String arm, Metric metric, ArmValues values) {
        if (values.values().isEmpty()) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | not run | | | 0 |%n",
                    profile, arm, metric.label()));
            return;
        }
        double[] sorted = values.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        md.append(String.format(Locale.ROOT, "| %s | %s | %s | %.1f | %.1f | %.1f | %d |%n",
                profile, arm, metric.label(), BenchClient.median(sorted), sorted[0],
                sorted[sorted.length - 1], sorted.length));
    }

    private static void appendPair(List<String[]> pairs, String profile, String arm, Metric metric,
            ArmValues tcp, ArmValues variant) {
        List<Double> deltas = new ArrayList<>();
        List<Double> percents = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : variant.byRound().entrySet()) {
            Double base = tcp.byRound().get(entry.getKey());
            if (base == null) {
                continue;
            }
            double delta = entry.getValue() - base;
            deltas.add(delta);
            if (base > 0) {
                percents.add(delta * 100.0 / base);
            }
        }
        String median;
        String range;
        String percent;
        if (deltas.isEmpty()) {
            median = "no pairs";
            range = "";
            percent = "";
        } else {
            double[] sorted = deltas.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            median = String.format(Locale.ROOT, "%.1f", BenchClient.median(sorted));
            range = String.format(Locale.ROOT, "%.1f..%.1f", sorted[0], sorted[sorted.length - 1]);
            double[] sortedPct = percents.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            percent = sortedPct.length == 0 ? ""
                    : String.format(Locale.ROOT, "%.1f%%", BenchClient.median(sortedPct));
        }
        pairs.add(new String[] {profile, arm, metric.label(), median, range, percent,
                Integer.toString(deltas.size())});
    }
}
