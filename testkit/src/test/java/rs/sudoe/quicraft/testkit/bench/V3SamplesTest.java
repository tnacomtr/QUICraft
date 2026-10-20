// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import rs.sudoe.quicraft.testkit.Args;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Strict-validator tests for {@link V3Samples#run(Args)}. Every input document here is a
 * synthetic fixture built by this test: never a benchmark artifact, never user data, and a
 * passing document certifies no live measurement. The validator's own success line must keep
 * saying so: "provenance self-reported, live measurement not verified".
 *
 * <p>The global {@code System.out} and {@code System.err} streams are captured for each run and
 * always restored in {@code finally}; one class-level shared resource lock covers both streams
 * so no two tests in this class can interleave with the globals. Verdicts rest on return codes
 * plus, where the contract demands the reject reason, one coarse substring of the concise
 * stderr diagnostic.
 */
@ResourceLock("quicraft-v3-samples-system-streams")
class V3SamplesTest {

    // Contract constants from the plan (2026-10-20 v3-samples validator), not read from code.
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DEPTH = 64;

    /** Most characters a rejection detail may emit, excluding the fixed prefix and newline. */
    private static final int MAX_DETAIL_LENGTH = 512;

    /** Fixed prefix of every concise rejection diagnostic. */
    private static final String STDERR_PREFIX = "v3-samples: ";
    private static final String SUCCESS = "/3 samples structurally valid and internally consistent;"
            + " provenance self-reported, live measurement not verified.";
    private static final String COMMIT = "3fa1910e29a4c7d5b8e6f0a1b2c3d4e5f6071892";
    private static final String DIGEST =
            "9c8b7a65f4e3d2c10987654321fedcba9876543210fedcba9876543210fedcba";
    private static final List<String> OPTIONAL = List.of("error", "net", "quic");

    @TempDir
    private Path dir;

    private record Outcome(int code, String out, String err) {}

    // ---------- synthetic document builders (fresh objects per call, no shared state) ----------

    private static JsonPrimitive num(String exact) {
        return new JsonPrimitive(new BigDecimal(exact));
    }

    private static JsonPrimitive num(long value) {
        return num(Long.toString(value));
    }

    private static JsonPrimitive str(String value) {
        return new JsonPrimitive(value);
    }

    private static JsonArray phases(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(num(value));
        }
        return array;
    }

    /** One fully valid measured row; optional error/net/quic are explicit JSON nulls. */
    private static JsonObject row() {
        JsonObject row = new JsonObject();
        row.add("run", num("7"));
        row.add("warmup", new JsonPrimitive(false));
        row.add("ok", new JsonPrimitive(true));
        row.add("phasesMs", phases("10", "20", "30", "40"));
        row.add("joinMs", num("40"));
        row.add("chunkLoadMs", num("805.5"));
        row.add("chunks", num("64"));
        row.add("rttMedianMs", num("12.5"));
        row.add("rttP95Ms", num("20"));
        row.add("rttSamples", num("100"));
        row.add("rttLost", num("3"));
        row.add("disconnected", new JsonPrimitive(false));
        row.add("error", JsonNull.INSTANCE);
        row.add("net", JsonNull.INSTANCE);
        row.add("quic", JsonNull.INSTANCE);
        row.add("zeroRtt", new JsonPrimitive(false));
        row.add("schemaVersion", num(BenchmarkSchema.VERSION));
        row.add("transportVersion", str(TransportVersion.QUIC_3.name()));
        row.add("streamCount", num("3"));
        row.add("buildCommit", str(COMMIT));
        row.add("routingTableId", str("quicraft-v3-routing-1"));
        row.add("routingTableDigest", str(DIGEST));
        row.add("routingTableSchema", num(BenchmarkSchema.ROUTING_SCHEMA_V3));
        row.add("negotiatedAlpn", str(TransportVersion.QUIC_3.alpn()));
        row.add("negotiatedTransportVerified", new JsonPrimitive(true));
        return row;
    }

    private static JsonObject warmupRow() {
        JsonObject row = row();
        row.add("run", num("6"));
        row.add("warmup", new JsonPrimitive(true));
        return row;
    }

    private static JsonObject envelope(JsonObject... rows) {
        return envelopeFor("clean", rows);
    }

    private static JsonObject envelopeFor(String netemProfile, JsonObject... rows) {
        JsonObject root = new JsonObject();
        root.add("clientType", str("fabric"));
        root.add("netemProfile", str(netemProfile));
        root.add("profile", str(netemProfile + "-quic-3"));
        JsonArray runs = new JsonArray();
        for (JsonObject row : rows) {
            runs.add(row);
        }
        root.add("runs", runs);
        return root;
    }

    private static JsonObject without(JsonObject source, String name) {
        JsonObject copy = shallowCopy(source);
        copy.remove(name);
        return copy;
    }

    private static JsonObject set(JsonObject source, String name, JsonElement value) {
        JsonObject copy = shallowCopy(source);
        copy.add(name, value);
        return copy;
    }

    private static JsonObject shallowCopy(JsonObject source) {
        JsonObject copy = new JsonObject();
        for (var entry : source.entrySet()) {
            copy.add(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    /**
     * Hangs {@code nestedObjects} empty objects off an "extra" envelope field. The root value is
     * depth 1, each hanging object is one deeper, so the innermost ends at depth
     * {@code nestedObjects + 1}; a chain of 63 stays at the limit, 64 exceeds it.
     */
    private static JsonObject withDeepExtra(JsonObject root, int nestedObjects) {
        JsonObject copy = shallowCopy(root);
        JsonObject cursor = new JsonObject();
        copy.add("extra", cursor);
        for (int i = 2; i <= nestedObjects; i++) {
            JsonObject child = new JsonObject();
            cursor.add("x", child);
            cursor = child;
        }
        return copy;
    }

    private static String json(JsonElement root) {
        return new Gson().toJson(root);
    }

    private static List<String> requiredNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : RunResult.class.getRecordComponents()) {
            names.add(component.getName());
        }
        names.removeAll(OPTIONAL);
        return names;
    }

    // ---------- execution helpers (capture streams, always restore) ----------

    private Path writeDoc(String name, String doc) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, doc, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeDoc(String name, JsonElement root) throws IOException {
        return writeDoc(name, json(root));
    }

    private Path writeRaw(String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static Outcome runFile(Path file) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = V3Samples.run(Args.parse(new String[] {"--in", file.toString()}));
            return new Outcome(code, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private Outcome args(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = V3Samples.run(Args.parse(argv));
            return new Outcome(code, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** For inputs whose only sane failure mode is a fast, small-allocation reject. */
    private static Outcome quick(Path file) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> runFile(file),
                "huge exponents must be rejected quickly, without huge allocations");
    }

    private static void assertRejected(Outcome outcome) {
        assertEquals(1, outcome.code(), () -> "stdout=" + outcome.out());
        assertFalse(outcome.err().isBlank(), "a rejection must print a concise diagnostic");
    }

    private static void assertRejected(Outcome outcome, String errPart) {
        assertRejected(outcome);
        assertTrue(outcome.err().contains(errPart),
                () -> "expected diagnostic to mention \"" + errPart + "\" but was: " + outcome.err());
    }

    private static void assertAccepted(Outcome outcome) {
        assertEquals(0, outcome.code(), () -> "stdout=" + outcome.out() + " err=" + outcome.err());
        assertEquals("", outcome.err());
    }

    /**
     * Contract shape of a rejection diagnostic: one fixed prefix, at most 512 emitted detail
     * characters, and exactly one trailing platform newline with no raw control characters in
     * the body. A rejection detail is attacker-controlled input, never trusted bytes.
     */
    private static String assertSanitizedSingleLine(Outcome outcome) {
        String separator = System.lineSeparator();
        String err = outcome.err();
        assertTrue(err.endsWith(separator), () -> "expected one final newline, got: " + err);
        String body = err.substring(0, err.length() - separator.length());
        assertTrue(body.startsWith(STDERR_PREFIX), () -> body);
        String detail = body.substring(STDERR_PREFIX.length());
        assertTrue(detail.length() <= MAX_DETAIL_LENGTH,
                () -> "detail is " + detail.length() + " emitted characters");
        for (int i = 0; i < detail.length(); i++) {
            final int position = i;
            assertFalse(Character.isISOControl(detail.charAt(position)),
                    () -> "raw ISO control at " + position + ": " + detail);
        }
        return detail;
    }

    // ---------- 1. canonical valid document ----------

    @Test
    void canonicalValidDocumentIsAcceptedWithSelfReportOutput() throws IOException {
        Outcome outcome = runFile(writeDoc("canonical.json", envelope(row())));

        assertAccepted(outcome);
        String[] lines = outcome.out().split("\n");
        assertEquals(2, lines.length);
        assertEquals(SUCCESS, lines[0]);
        assertEquals("rows: 1 (measured 1, warmup 0)", lines[1]);
        assertFalse(outcome.out().contains("median"), "success output must not print metrics");
    }

    @Test
    void warmupPlusMeasuredEnvelopeIsValidAndCountsArePrinted() throws IOException {
        Outcome outcome = runFile(writeDoc("warmup-plus-measured.json",
                envelope(row(), warmupRow())));

        assertAccepted(outcome);
        String[] lines = outcome.out().split("\n");
        assertEquals(2, lines.length);
        assertEquals(SUCCESS, lines[0]);
        assertEquals("rows: 2 (measured 1, warmup 1)", lines[1]);
    }

    // ---------- 3-4. envelope strictness and profile coupling ----------

    @Test
    void allSixNetemProfilesAreAccepted() throws IOException {
        List<String> profiles = List.of("clean", "loss", "delay", "delayloss", "reorder", "jitter");
        for (String netem : profiles) {
            JsonObject root = envelopeFor(netem, row());
            Outcome outcome = runFile(writeDoc("profile-" + netem + ".json", root));

            assertAccepted(outcome);
            assertEquals("rows: 1 (measured 1, warmup 0)", outcome.out().split("\n")[1]);
        }
    }

    @Test
    void envelopeFieldsAreStrictProfileCouplingAndClientTypeEnforced() throws IOException {
        JsonObject mismatchedCoupling =
                set(envelopeFor("loss", row()), "profile", str("clean-quic-3"));
        assertRejected(runFile(writeDoc("coupling-wrong-netem.json", mismatchedCoupling)));

        JsonObject wrongSuffix = set(envelopeFor("loss", row()), "profile", str("loss-quic-2"));
        assertRejected(runFile(writeDoc("coupling-wrong-suffix.json", wrongSuffix)));

        JsonObject bareProfile = set(envelopeFor("loss", row()), "profile", str("loss"));
        assertRejected(runFile(writeDoc("coupling-bare-profile.json", bareProfile)));

        JsonObject unknownNetem = envelopeFor("warped", row());
        assertRejected(runFile(writeDoc("netem-unknown.json", unknownNetem)));

        JsonObject nullNetem = set(envelopeFor("clean", row()), "netemProfile", JsonNull.INSTANCE);
        assertRejected(runFile(writeDoc("netem-null.json", nullNetem)));

        JsonObject wrongClient = set(envelopeFor("clean", row()), "clientType", str("velocity"));
        assertRejected(runFile(writeDoc("clienttype-wrong.json", wrongClient)));

        JsonObject nullClient = set(envelopeFor("clean", row()), "clientType", JsonNull.INSTANCE);
        assertRejected(runFile(writeDoc("clienttype-null.json", nullClient)));

        JsonObject missingClient = without(envelopeFor("clean", row()), "clientType");
        assertRejected(runFile(writeDoc("clienttype-missing.json", missingClient)), "clientType");
    }

    // ---------- 5. integral numbers in exponent and decimal forms ----------

    @Test
    void integralDecimalAndExponentNumbersAreAccepted() throws IOException {
        JsonObject integral = row();
        integral.add("run", num("1.0"));
        integral.add("chunks", num("1e2"));
        integral.add("rttSamples", num("5e0"));
        integral.add("streamCount", num("3.0"));

        Outcome outcome = runFile(writeDoc("integral.json", envelope(integral)));
        assertAccepted(outcome);
    }

    // ---------- 6. every required field: missing / null / wrong kind ----------

    @Test
    void everyRequiredFieldMissingNullOrWrongKindIsRejected() throws IOException {
        List<String> names = requiredNames();
        assertFalse(names.isEmpty(), "the record must expose its required components");
        for (String name : names) {
            String tag = name;

            JsonObject missing = envelope(without(row(), name));
            assertRejected(runFile(writeDoc("missing-" + tag + ".json", missing)), name);

            JsonObject nulled = envelope(set(row(), name, JsonNull.INSTANCE));
            assertRejected(runFile(writeDoc("null-" + tag + ".json", nulled)), name);

            JsonObject wrongKind = envelope(set(row(), name, new JsonObject()));
            assertRejected(runFile(writeDoc("wrongkind-" + tag + ".json", wrongKind)), name);
        }
    }

    // ---------- 7-8. transport identity and provenance fail closed ----------

    @Test
    void transportVersionAndAlpnFailClosed() throws IOException {
        JsonObject quic2Label = set(row(), "transportVersion", str("QUIC_2"));
        assertRejected(runFile(writeDoc("transport-quic2.json", envelope(quic2Label))));

        JsonObject lowercaseLabel = set(row(), "transportVersion", str("quic_3"));
        assertRejected(runFile(writeDoc("transport-lowercase.json", envelope(lowercaseLabel))));

        JsonObject weirdLabel = set(row(), "transportVersion", str("quicraft/3"));
        assertRejected(runFile(writeDoc("transport-alpn-label.json", envelope(weirdLabel))));

        JsonObject alpnV2 = set(row(), "negotiatedAlpn", str(TransportVersion.QUIC_2.alpn()));
        assertRejected(runFile(writeDoc("alpn-v2.json", envelope(alpnV2))));

        JsonObject alpnEmpty = set(row(), "negotiatedAlpn", str(""));
        assertRejected(runFile(writeDoc("alpn-empty.json", envelope(alpnEmpty))));

        JsonObject unverified = set(row(), "negotiatedTransportVerified", new JsonPrimitive(false));
        assertRejected(runFile(writeDoc("unverified.json", envelope(unverified))));
    }

    @Test
    void provenanceIdentityFieldsFailClosed() throws IOException {
        assertRejected(runFile(writeDoc("commit-unknown.json",
                envelope(set(row(), "buildCommit", str(BenchmarkSchema.UNKNOWN_COMMIT))))));

        assertRejected(runFile(writeDoc("commit-uppercase.json",
                envelope(set(row(), "buildCommit", str("ABCDEF1"))))));

        assertRejected(runFile(writeDoc("commit-41chars.json",
                envelope(set(row(), "buildCommit", str(COMMIT + "a"))))));

        assertRejected(runFile(writeDoc("digest-short.json",
                envelope(set(row(), "routingTableDigest", str(DIGEST.substring(1)))))));

        assertRejected(runFile(writeDoc("digest-uppercase.json",
                envelope(set(row(), "routingTableDigest", str(DIGEST.toUpperCase()))))));

        assertRejected(runFile(writeDoc("tableid-empty.json",
                envelope(set(row(), "routingTableId", str(""))))));

        assertRejected(runFile(writeDoc("tableid-unknown.json",
                envelope(set(row(), "routingTableId", str("unknown"))))));

        assertRejected(runFile(writeDoc("tableid-none.json",
                envelope(set(row(), "routingTableId", str("none"))))));

        assertRejected(runFile(writeDoc("tableschema-old.json",
                envelope(set(row(), "routingTableSchema",
                        num(BenchmarkSchema.ROUTING_SCHEMA_V3 - 1))))));

        assertRejected(runFile(writeDoc("schemaversion-new.json",
                envelope(set(row(), "schemaVersion", num(BenchmarkSchema.VERSION + 1))))));

        assertRejected(runFile(writeDoc("streams-zero.json",
                envelope(set(row(), "streamCount", num("0"))))));

        assertRejected(runFile(writeDoc("streams-negative.json",
                envelope(set(row(), "streamCount", num("-3"))))));
    }

    // ---------- 9. status flags ----------

    @Test
    void statusFlagsFailClosed() throws IOException {
        JsonObject notOk = set(row(), "ok", new JsonPrimitive(false));
        assertRejected(runFile(writeDoc("ok-false.json", envelope(notOk))));

        JsonObject disconnected = set(row(), "disconnected", new JsonPrimitive(true));
        assertRejected(runFile(writeDoc("disconnected.json", envelope(disconnected))));

        JsonObject zeroRtt = set(row(), "zeroRtt", new JsonPrimitive(true));
        assertRejected(runFile(writeDoc("zerortt.json", envelope(zeroRtt))));
    }

    // ---------- 10-12. warmups, duplicate run indexes, row identity sharing ----------

    @Test
    void warmupRowsNeedFullValidationAndAMeasuredRowIsRequired() throws IOException {
        Outcome warmupOnly = runFile(writeDoc("warmup-only.json", envelope(warmupRow())));
        assertRejected(warmupOnly, "measured");

        JsonObject badWarmup = set(warmupRow(), "ok", new JsonPrimitive(false));
        Outcome notDropped = runFile(writeDoc("bad-warmup.json", envelope(row(), badWarmup)));
        assertRejected(notDropped, "runs[1]");

        JsonObject warmupMissingJoin = without(warmupRow(), "joinMs");
        Outcome missing = runFile(writeDoc("warmup-missing-join.json",
                envelope(row(), warmupMissingJoin)));
        assertRejected(missing, "runs[1]");

        JsonObject warmupBadDigest = set(warmupRow(), "routingTableDigest", str(DIGEST.substring(1)));
        Outcome unproven = runFile(writeDoc("warmup-bad-digest.json",
                envelope(row(), warmupBadDigest)));
        assertRejected(unproven, "runs[1]");
    }

    @Test
    void duplicateRunIndexesRejectedBetweenRows() throws IOException {
        JsonObject duplicated = set(warmupRow(), "run", num("7"));
        Outcome outcome = runFile(writeDoc("duplicate-run.json", envelope(row(), duplicated)));

        assertRejected(outcome, "runs[1]");
        assertTrue(outcome.err().contains("7"), () -> "err=" + outcome.err());
    }

    @Test
    void rowIdentitySharingIsEnforcedAcrossIndependentFields() throws IOException {
        // Positive control: a second row with unchanged identity and only a unique run index.
        JsonObject control = set(row(), "run", num("8"));
        assertAccepted(runFile(writeDoc("identity-control.json", envelope(row(), control))));

        // Each candidate is a fresh row with run 8 and exactly one valid identity value differing
        // from the baseline row's. Validating the candidate ALONE first proves the rejection can
        // only come from the cross-row sharing rule, not from a per-row gate.
        JsonObject validCommit = set(row(), "run", num("8"));
        validCommit = set(validCommit, "buildCommit", str("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertAccepted(runFile(writeDoc("identity-alone-commit.json", envelope(validCommit))));
        assertRejected(runFile(writeDoc("identity-pair-commit.json", envelope(row(), validCommit))),
                "does not share the first row's build");

        // row() sets streamCount 3; the candidate below diverges only to 4.
        JsonObject validStreams = set(row(), "run", num("8"));
        validStreams = set(validStreams, "streamCount", num("4"));
        assertAccepted(runFile(writeDoc("identity-alone-streams.json", envelope(validStreams))));
        assertRejected(runFile(writeDoc("identity-pair-streams.json", envelope(row(), validStreams))),
                "does not share the first row's build");

        JsonObject validTableId = set(row(), "run", num("8"));
        validTableId = set(validTableId, "routingTableId", str("quicraft-v3-routing-2"));
        assertAccepted(runFile(writeDoc("identity-alone-tableid.json", envelope(validTableId))));
        assertRejected(runFile(writeDoc("identity-pair-tableid.json", envelope(row(), validTableId))),
                "does not share the first row's build");

        JsonObject validDigest = set(row(), "run", num("8"));
        validDigest = set(validDigest, "routingTableDigest",
                str("bbaa99887766554433221100ffeeddccbbaa99887766554433221100ffeeddcc"));
        assertAccepted(runFile(writeDoc("identity-alone-digest.json", envelope(validDigest))));
        assertRejected(runFile(writeDoc("identity-pair-digest.json", envelope(row(), validDigest))),
                "does not share the first row's build");
    }

    @Test
    void tableSchemaBelowTwoIsAPerRowGateNotAnIdentityCrossRowProof() throws IOException {
        // A routing table schema other than 2 cannot be a valid identity match in the first
        // place: the per-row negotiated gate rejects it before any cross-row comparison.
        JsonObject wrongSchema = set(row(), "routingTableSchema",
                num(BenchmarkSchema.ROUTING_SCHEMA_V3 - 1));
        assertRejected(runFile(writeDoc("schema-per-row.json", envelope(wrongSchema))),
                "routing table schema");
    }

    // ---------- 13. unknown extra fields ----------

    @Test
    void unknownExtraFieldsAreAcceptedAtEveryLevel() throws IOException {
        JsonObject decoratedRow = set(row(), "flags", new JsonObject());
        decoratedRow = set(decoratedRow, "notes", str("synthetic"));
        decoratedRow = set(decoratedRow, "run", num("8")); // unique next to the first row's 7

        JsonObject root = envelope(row(), decoratedRow);
        root.add("launcher", str("testkit"));
        root.add("emitterVersion", num("2"));

        Outcome outcome = runFile(writeDoc("extras.json", root));
        assertAccepted(outcome);
        assertEquals("rows: 2 (measured 2, warmup 0)", outcome.out().split("\n")[1]);
    }

    // ---------- 14. duplicate keys at root, row, and nested objects ----------

    @Test
    void duplicateKeysAtRootRowAndNestedObjectsAreRejected() throws IOException {
        JsonObject rowWithNet = set(row(), "net", rowNet());
        rowWithNet = set(rowWithNet, "flags", new JsonObject());
        rowWithNet.getAsJsonObject("flags").add("y", new JsonPrimitive(true));
        String base = json(envelope(rowWithNet));
        assertTrue(base.startsWith("{\"clientType\":"), () -> base);

        String rootDup = "{\"clientType\":\"fabric\"," + base.substring(1);
        assertRejected(runFile(writeDoc("dup-root.json", rootDup)), "duplicate key");

        int rowStart = base.indexOf('{', base.indexOf("\"runs\":"));
        String rowDup = base.substring(0, rowStart + 1) + "\"extra\":1,\"extra\":2,"
                + base.substring(rowStart + 1);
        assertRejected(runFile(writeDoc("dup-row.json", rowDup)), "duplicate key");

        int flagsStart = base.indexOf('{', base.indexOf("\"flags\":"));
        String nestedDup = base.substring(0, flagsStart + 1) + "\"y\":0,"
                + base.substring(flagsStart + 1);
        assertRejected(runFile(writeDoc("dup-nested.json", nestedDup)), "duplicate key");

        int netStart = base.indexOf('{', base.indexOf("\"net\":"));
        String mapDup = base.substring(0, netStart + 1) + "\"drops\":9,"
                + base.substring(netStart + 1);
        assertRejected(runFile(writeDoc("dup-map.json", mapDup)), "duplicate key");
    }

    private static JsonObject rowNet() {
        JsonObject net = new JsonObject();
        net.add("drops", num("1"));
        return net;
    }

    // ---------- 15-16. trailing tokens and loose JSON syntax ----------

    @Test
    void trailingTokensAreRejectedWhileWhitespaceIsAllowed() throws IOException {
        String base = json(envelope(row()));

        // Well-formed tokens after the root are still rejected fail-closed (exit 1 with a
        // concise diagnostic), even though the wording differs from one token kind to another;
        // the contract requires the rejection, not a particular diagnostic word.
        assertRejected(runFile(writeDoc("trailing-object.json", base + " {}")));
        assertRejected(runFile(writeDoc("trailing-number.json", base + " 3")));
        assertRejected(runFile(writeDoc("trailing-array.json", base + " []")));
        assertRejected(runFile(writeDoc("trailing-string.json", base + " \"z\"")));

        Outcome padded = runFile(writeDoc("trailing-whitespace.json", base + "  \n  "));
        assertAccepted(padded);
    }

    @Test
    void commentsSingleQuotesUnquotedNamesAndBareWordsAreRejected() throws IOException {
        String base = json(envelope(row()));

        String lineComment = "// hi\n" + base;
        assertRejected(runFile(writeDoc("line-comment.json", lineComment)), "not valid JSON");

        String blockComment = "/*x*/" + base;
        assertRejected(runFile(writeDoc("block-comment.json", blockComment)), "not valid JSON");

        assertRejected(runFile(writeDoc("single-quotes.json", "{'clientType':'fabric'}")),
                "not valid JSON");

        assertRejected(runFile(writeDoc("unquoted-name.json", "{clientType:fabric}")),
                "not valid JSON");

        assertRejected(runFile(writeDoc("bare-word.json", base + " x")), "not valid JSON");
    }

    // ---------- 17. nonfinite numbers and string numbers ----------

    @Test
    void nonFiniteNumbersAndStringNumbersAreRejected() throws IOException {
        String base = json(envelope(row()));

        assertRejected(runFile(writeDoc("nan-root.json", "NaN")), "not valid JSON");

        assertRejected(runFile(writeDoc("inf-root.json", "Infinity")), "not valid JSON");

        assertRejected(runFile(writeDoc("inf-negative-root.json", "-Infinity")), "not valid JSON");

        String joinedNan = base.replace("\"joinMs\":40", "\"joinMs\":NaN");
        assertTrue(joinedNan.contains("NaN"), () -> joinedNan);
        assertRejected(runFile(writeDoc("nan-field.json", joinedNan)), "not valid JSON");

        String joinedInf = base.replace("\"joinMs\":40", "\"joinMs\":Infinity");
        assertRejected(runFile(writeDoc("inf-field.json", joinedInf)));

        String hugePhase = base.replace("\"phasesMs\":[10,20,30,40]", "\"phasesMs\":[0,0,0,1e400]");
        assertRejected(runFile(writeDoc("phase-overflow.json", hugePhase)), "not a finite number");

        String hugeP95 = base.replace("\"rttP95Ms\":20", "\"rttP95Ms\":1e400");
        assertRejected(runFile(writeDoc("p95-overflow.json", hugeP95)), "not a finite number");

        String quotedJoin = base.replace("\"joinMs\":40", "\"joinMs\":\"40\"");
        assertRejected(runFile(writeDoc("string-number-join.json", quotedJoin)),
                "must be a JSON number");

        String quotedPhases = base.replace("\"phasesMs\":[10,20,30,40]",
                "\"phasesMs\":[\"10\",\"20\",\"30\",\"40\"]");
        assertRejected(runFile(writeDoc("string-number-phases.json", quotedPhases)),
                "must be a JSON number");

        String quotedRtt = base.replace("\"rttMedianMs\":12.5", "\"rttMedianMs\":\"12.5\"");
        assertRejected(runFile(writeDoc("string-number-rtt.json", quotedRtt)),
                "must be a JSON number");
    }

    // ---------- 18-19. metric bounds and phase/join boundaries ----------

    @Test
    void metricBoundsFailClosed() throws IOException {
        JsonObject negativeJoin = set(row(), "joinMs", num("-1"));
        assertRejected(runFile(writeDoc("join-negative.json", envelope(negativeJoin))),
                "must not be negative");

        JsonObject negativeChunkLoad = set(row(), "chunkLoadMs", num("-1"));
        assertRejected(runFile(writeDoc("chunkload-negative.json", envelope(negativeChunkLoad))));

        JsonObject zeroChunks = set(row(), "chunks", num("0"));
        assertRejected(runFile(writeDoc("chunks-zero.json", envelope(zeroChunks))));

        JsonObject negativeChunks = set(row(), "chunks", num("-2"));
        assertRejected(runFile(writeDoc("chunks-negative.json", envelope(negativeChunks))));

        JsonObject overflowChunks = set(row(), "chunks", num("2147483648"));
        assertRejected(runFile(writeDoc("chunks-overflow.json", envelope(overflowChunks))),
                "outside the int range");

        JsonObject zeroSamples = set(row(), "rttSamples", num("0"));
        assertRejected(runFile(writeDoc("samples-zero.json", envelope(zeroSamples))));

        JsonObject negativeLost = set(row(), "rttLost", num("-1"));
        assertRejected(runFile(writeDoc("lost-negative.json", envelope(negativeLost))));

        JsonObject negativeMedian = set(row(), "rttMedianMs", num("-1"));
        assertRejected(runFile(writeDoc("median-negative.json", envelope(negativeMedian))));

        JsonObject lowP95 = set(row(), "rttP95Ms", num("11.9"));
        assertRejected(runFile(writeDoc("p95-below-median.json", envelope(lowP95))));

        JsonObject negativeRun = set(row(), "run", num("-1"));
        assertRejected(runFile(writeDoc("run-negative.json", envelope(negativeRun))));

        JsonObject fractionalRun = set(row(), "run", num("1.5"));
        assertRejected(runFile(writeDoc("run-fraction.json", envelope(fractionalRun))),
                "not an integer");

        JsonObject overflowRun = set(row(), "run", num("2147483648"));
        assertRejected(runFile(writeDoc("run-overflow.json", envelope(overflowRun))),
                "outside the int range");
    }

    @Test
    void phasesAndJoinToleranceBoundariesAreEnforced() throws IOException {
        JsonObject three = set(row(), "phasesMs", phases("10", "20", "30"));
        assertRejected(runFile(writeDoc("phases-three.json", envelope(three))),
                "exactly four phases");

        JsonObject five = set(row(), "phasesMs", phases("10", "20", "30", "39", "40"));
        assertRejected(runFile(writeDoc("phases-five.json", envelope(five))));

        JsonObject decreasing = set(row(), "phasesMs", phases("40", "30", "20", "10"));
        assertRejected(runFile(writeDoc("phases-decreasing.json", envelope(decreasing))),
                "must not decrease");

        JsonObject insideTolerance = row();
        insideTolerance.add("phasesMs", phases("0", "0", "0", "0"));
        insideTolerance.add("joinMs", num("5e-7"));
        assertAccepted(runFile(writeDoc("tolerance-inside.json", envelope(insideTolerance))));

        JsonObject outsideTolerance = row();
        outsideTolerance.add("phasesMs", phases("0", "0", "0", "0"));
        outsideTolerance.add("joinMs", num("2e-6"));
        assertRejected(runFile(writeDoc("tolerance-outside.json", envelope(outsideTolerance))),
                "1e-6");
    }

    // ---------- 20-22. optional error and long maps ----------

    @Test
    void optionalErrorAcceptsOnlyNullAndEmptyString() throws IOException {
        JsonObject emptyString = set(row(), "error", str(""));
        assertAccepted(runFile(writeDoc("error-empty.json", envelope(emptyString))));

        JsonObject message = set(row(), "error", str("oops"));
        assertRejected(runFile(writeDoc("error-text.json", envelope(message))), "empty");

        JsonObject number = set(row(), "error", num("3"));
        assertRejected(runFile(writeDoc("error-number.json", envelope(number))));

        JsonObject object = set(row(), "error", new JsonObject());
        assertRejected(runFile(writeDoc("error-object.json", envelope(object))));

        JsonObject boolValue = set(row(), "error", new JsonPrimitive(true));
        assertRejected(runFile(writeDoc("error-bool.json", envelope(boolValue))));
    }

    @Test
    void optionalLongMapsAcceptNullEmptyAndIntegralLongs() throws IOException {
        JsonObject absent = envelope(without(without(row(), "net"), "quic"));
        assertAccepted(runFile(writeDoc("maps-absent.json", absent)));

        for (String mapName : List.of("net", "quic")) {
            String tag = mapName;

            JsonObject empty = envelope(set(row(), mapName, new JsonObject()));
            assertAccepted(runFile(writeDoc("map-empty-" + tag + ".json", empty)));

            JsonObject maxLong = envelope(set(row(), mapName,
                    mapOf("drops", "9223372036854775807")));
            assertAccepted(runFile(writeDoc("map-maxlong-" + tag + ".json", maxLong)));

            JsonObject exponent = envelope(set(row(), mapName, mapOf("retransmits", "1e18")));
            assertAccepted(runFile(writeDoc("map-exponent-" + tag + ".json", exponent)));

            JsonObject several = envelope(set(row(), mapName,
                    mapOf("drops", "3", "retransmits", "0")));
            assertAccepted(runFile(writeDoc("map-several-" + tag + ".json", several)));
        }
    }

    @Test
    void optionalLongMapsRejectWrongShapes() throws IOException {
        for (String mapName : List.of("net", "quic")) {
            String tag = mapName;

            JsonObject overflow = envelope(set(row(), mapName,
                    mapOf("drops", "9223372036854775808")));
            assertRejected(runFile(writeDoc("map-overflow-" + tag + ".json", overflow)),
                    "outside the long range");

            JsonObject fractional = envelope(set(row(), mapName, mapOf("drops", "1.5")));
            assertRejected(runFile(writeDoc("map-fraction-" + tag + ".json", fractional)),
                    "not an integer");

            JsonObject stringNumber = new JsonObject();
            stringNumber.add("drops", str("x"));
            // the value is a JSON string; the map needs numbers, not text
            assertRejected(runFile(writeDoc("map-stringvalue-" + tag + ".json",
                    envelope(set(row(), mapName, stringNumber)))), "must be a JSON number");

            JsonObject stringMap = envelope(set(row(), mapName, str("x")));
            assertRejected(runFile(writeDoc("map-shape-" + tag + ".json", stringMap)),
                    "must be a JSON object");

            JsonArray array = new JsonArray();
            array.add(num("1"));
            JsonObject arrayMap = envelope(set(row(), mapName, array));
            assertRejected(runFile(writeDoc("map-array-" + tag + ".json", arrayMap)),
                    "must be a JSON object");
        }
    }

    private static JsonObject mapOf(String... keysAndValues) {
        JsonObject map = new JsonObject();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.add(keysAndValues[i], num(keysAndValues[i + 1]));
        }
        return map;
    }

    // ---------- 23-24. CLI argument and file errors ----------

    @Test
    void usageErrorsReturnTwoWithoutStacktraces() {
        for (String[] argv : new String[][] {
                {}, {"--in"}, {"--in", ""}, {"--in", "   "}, {"--in", "a\u0000b"},}) {
            Outcome outcome = args(argv);
            assertEquals(2, outcome.code(), () -> "argv=" + Arrays.toString(argv));
            assertFalse(outcome.err().isBlank());
            assertFalse(outcome.err().contains("\tat "), "no stack trace expected");
        }
    }

    @Test
    void unreadableInputsReturnOneWithConciseDiagnostics() {
        Outcome missing = args("--in", dir.resolve("not-there.json").toString());
        assertRejected(missing, "cannot read");

        Outcome directory = args("--in", dir.toString());
        assertRejected(directory, "cannot read");
        assertFalse(directory.err().contains("\tat "), "no stack trace expected");
    }

    // ---------- 25. file-size and nesting boundaries ----------

    @Test
    void fileSizeAndDepthBoundariesAreProved() throws IOException {
        String base = json(envelope(row()));
        byte[] baseBytes = base.getBytes(StandardCharsets.UTF_8);
        assertTrue(baseBytes.length < MAX_BYTES, () -> "fixture too large: " + baseBytes.length);

        // Trailing JSON whitespace is legal, so whitespace padding produces an exactly 8 MiB
        // document that is also valid JSON; every byte is counted explicitly above.
        byte[] exact = new byte[MAX_BYTES];
        Arrays.fill(exact, (byte) ' ');
        System.arraycopy(baseBytes, 0, exact, 0, baseBytes.length);
        assertEquals(MAX_BYTES, exact.length, "boundary document must be exactly 8 MiB");
        assertAccepted(runFile(writeRaw("exact-mib.json", exact)));

        byte[] oversized = new byte[MAX_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        System.arraycopy(baseBytes, 0, oversized, 0, baseBytes.length);
        assertRejected(runFile(writeRaw("oversize.json", oversized)), "larger");

        JsonObject sixtyThreeDeep = withDeepExtra(envelope(row()), MAX_DEPTH - 1);
        assertAccepted(runFile(writeDoc("depth-ok.json", sixtyThreeDeep)));

        JsonObject sixtyFourDeep = withDeepExtra(envelope(row()), MAX_DEPTH);
        assertRejected(runFile(writeDoc("depth-limit.json", sixtyFourDeep)),
                "nesting exceeds 64");
    }

    // ---------- decode: malformed UTF-8 inside a string field is refused, not replaced ----------

    @Test
    void malformedUtf8InsideAStringFieldIsRefused() throws IOException {
        String base = json(envelope(row()));
        byte[] bytes = base.getBytes(StandardCharsets.UTF_8);
        int openingQuote = base.indexOf("\"fabric\"");
        assertTrue(openingQuote >= 0, () -> base);

        // bytes: [..] '"' 'f' 'a' 'b' 'r' 'i' 'c' '"' [..]. The clientType value's first
        // character ("f") is replaced by 0xC3 without its required continuation byte (the
        // literal 0x28 is not one), so decoding must REPORT error bytes, never substitute.
        byte[] malformed = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, malformed, 0, openingQuote + 1);
        malformed[openingQuote + 1] = (byte) 0xC3;
        malformed[openingQuote + 2] = 0x28;
        System.arraycopy(bytes, openingQuote + 2, malformed, openingQuote + 3,
                bytes.length - (openingQuote + 2));

        Path file = writeRaw("malformed-utf8.json", malformed);
        byte[] readBack = Files.readAllBytes(file);
        assertTrue(Arrays.equals(malformed, readBack), "fixture must be written intact");

        Outcome outcome = runFile(file);
        assertEquals(1, outcome.code(), () -> "stdout=" + outcome.out());
        assertFalse(outcome.err().isBlank(), "a rejection must print a concise diagnostic");
        assertTrue(outcome.err().contains("not valid UTF-8"),
                () -> "expected a decode refusal, got: " + outcome.err());

        byte[] afterRun = Files.readAllBytes(file);
        assertTrue(Arrays.equals(malformed, afterRun), "the validator must not modify the input");
    }

    // ---------- 26. huge exponents reported quickly, no huge allocations ----------
    @Test
    void hugeExponentsAreRejectedQuicklyWithoutHugeAllocations() throws IOException {
        String base = json(envelope(row()));
        assertTrue(base.contains("\"run\":7,"), () -> base);

        String intOverflow = base.replace("\"run\":7,", "\"run\":1e2147483647,");
        assertRejected(quick(writeDoc("huge-exponent-int.json", intOverflow)),
                "outside the int range");

        String unrepresentable = base.replace("\"run\":7,", "\"run\":1e99999999999999999999,");
        assertRejected(quick(writeDoc("huge-exponent-unrepresentable.json", unrepresentable)),
                "not a decimal number");
    }

    // ---------- 27. the validator writes no files ----------

    @Test
    void validatorWritesNothing() throws IOException {
        Path input = writeDoc("in.json", envelope(row()));
        assertAccepted(runFile(input));

        try (var listing = Files.list(dir)) {
            List<String> names = listing.map(Path::toString).toList();
            assertEquals(1, names.size(), () -> "only the input file may exist: " + names);
            assertTrue(names.get(0).endsWith("in.json"), () -> names.toString());
        }
    }

    // ---------- security follow-up: number literal budget and diagnostic sanitization ----------

    @Test
    void numberLiteralLengthBoundAccepts128Rejects129EvenNested() throws IOException {
        // A unknown extra field is never converted to a RunResult component, so a 128-digit
        // decimal is only judged by the literal length budget, not by any numeric range gate.
        JsonObject flatAccepted = envelope(row());
        flatAccepted.add("unknown", num("9".repeat(128)));
        assertAccepted(runFile(writeDoc("num128-flat.json", flatAccepted)));

        JsonObject flatRejected = envelope(row());
        flatRejected.add("unknown", num("9".repeat(129)));
        Outcome flat = runFile(writeDoc("num129-flat.json", flatRejected));
        assertRejected(flat, "number literal exceeds 128 characters");
        // The whole detail is the fixed constant: no part of the 129-digit token is echoed.
        assertEquals("number literal exceeds 128 characters", assertSanitizedSingleLine(flat));

        JsonObject deep = new JsonObject();
        deep.add("value", num("9".repeat(129)));
        JsonObject nestedHost = new JsonObject();
        nestedHost.add("extra", deep);
        JsonObject nestedRejected = envelope(row());
        nestedRejected.add("unknown", nestedHost);
        Outcome nested = runFile(writeDoc("num129-nested.json", nestedRejected));
        assertRejected(nested, "number literal exceeds 128 characters");
        assertSanitizedSingleLine(nested);
    }

    @Test
    void hugeSignificandIsRejectedQuicklyWithoutEchoingTheToken() throws IOException {
        String base = json(envelope(row()));
        assertTrue(base.startsWith("{"), () -> base);
        // One ~1 MiB decimal significand as an unknown extra value: valid JSON, far over the
        // literal budget, and it must be refused before any BigDecimal allocation.
        String doc = "{" + "\"huge\":" + "9".repeat(1_048_576) + "," + base.substring(1);

        Outcome outcome = quick(writeDoc("huge-significand.json", doc));
        assertRejected(outcome);
        String detail = assertSanitizedSingleLine(outcome);
        // No echo of the massive token: not even a 64-digit run of its all-9s digits.
        assertFalse(outcome.err().contains("9".repeat(64)), () -> detail);
    }

    @Test
    void escapedControlCharactersInMapKeyAreEscapedAndBoundedInDiagnostics() throws IOException {
        String key = "\n\r\t\u0000\u001b\u007f\u009b";   // LF CR TAB NUL ESC DEL C1-0x9b
        JsonObject map = new JsonObject();
        map.add(key, str("x"));   // "x" is not a number, so the diagnostic must name this key

        Outcome outcome = runFile(writeDoc("escaped-key.json",
                envelope(set(row(), "net", map))));
        assertRejected(outcome);
        String detail = assertSanitizedSingleLine(outcome);
        // The backslash must not sit next to the uXXXX in source: Java lexes \\uXXXX before
        // string parsing, so the escape text is pieced together here on purpose.
        String backslash = "\\";
        for (String literal : new String[] {
                "\\n", "\\r", "\\t",
                backslash + "u0000", backslash + "u001b",
                backslash + "u007f", backslash + "u009b",}) {
            assertTrue(detail.contains(literal), () -> "missing " + literal + " in " + detail);
        }
    }

    @Test
    void overlongMapKeyIsTruncatedWithEllipsisAndNoEscapeIsCut() throws IOException {
        // A >2K key with an ESC character every 32 characters, so an escape sits near the 512
        // emitted-character cut however the wording around it changes: truncation must still
        // end on whole tokens, never inside a \\uXXXX escape.
        StringBuilder keyBuilder = new StringBuilder();
        for (int i = 0; i < 2100; i++) {
            keyBuilder.append(i % 32 == 31 ? '\u001b' : 'a');
        }
        JsonObject map = new JsonObject();
        map.add(keyBuilder.toString(), str("x"));

        Outcome outcome = runFile(writeDoc("overlong-key.json",
                envelope(set(row(), "net", map))));
        assertRejected(outcome);
        String detail = assertSanitizedSingleLine(outcome);
        assertTrue(detail.endsWith("..."), () -> detail);
        assertNoCutUnicodeEscapes(detail);
    }

    /** Every \\u occurrence in the detail must be followed by exactly four hex digits. */
    private static void assertNoCutUnicodeEscapes(String detail) {
        int index = 0;
        while ((index = detail.indexOf("\\u", index)) >= 0) {
            final int at = index;
            String tail = detail.substring(at + 2, Math.min(at + 6, detail.length()));
            assertTrue(tail.matches("[0-9a-f]{4}"),
                    () -> "cut escape at " + at + " in: " + detail);
            index++;
        }
    }
}
