// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.testkit.Args;

/** Comparison rows and the fail-closed {@code /3} gate. Fixtures are synthetic, not measurements. */
class MatrixSummarizeTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path dir;

    @Test
    void manifestIgnoresStatusAndArchivedConfigArtifacts() throws IOException {
        write("tcp.json", "clean-tcp", "clean", RunFixtures.tcp(10, 100, 1));
        write("q2.json", "clean-quic", "clean", RunFixtures.quic2(20, 200, 2));
        write("q3.json", "clean-quic3", "clean", RunFixtures.quic3(30, 300, 3));
        writeManifest(1, "ok", true);
        Files.writeString(dir.resolve("tcp.status.json"), "{}");
        Files.createDirectory(dir.resolve("client-config-before"));
        Files.writeString(dir.resolve("client-config-before/config.json"), "{}");
        Path markdown = dir.resolve("summary.md");
        assertEquals(0, summarize(markdown, "clean"));
        assertTrue(Files.readString(markdown).contains("| clean | quic3 | 1 | 1 | 1 | 0 | 0 | COMPLETE |"));
    }

    @Test
    void missingRoundCannotVerifySurvivingSamples() throws IOException {
        write("tcp.json", "clean-tcp", "clean", RunFixtures.tcp(10, 100, 1));
        write("q2.json", "clean-quic", "clean", RunFixtures.quic2(20, 200, 2));
        write("q3.json", "clean-quic3", "clean", RunFixtures.quic3(30, 300, 3));
        writeManifest(2, "ok", true);
        Path markdown = dir.resolve("summary.md");
        assertEquals(2, summarize(markdown, "clean"));
        String text = Files.readString(markdown);
        assertTrue(text.contains("| clean | quic3 | 2 | 1 | 1 | 0 | 1 | INCOMPLETE |"), text);
        assertTrue(text.contains("Matrix /3 gate: INCOMPLETE"), text);
    }

    @Test
    void failedProcessWithSuccessfulRowStillFailsExpectedCell() throws IOException {
        write("tcp.json", "clean-tcp", "clean", RunFixtures.tcp(10, 100, 1));
        write("q2.json", "clean-quic", "clean", RunFixtures.quic2(20, 200, 2));
        write("q3.json", "clean-quic3", "clean", RunFixtures.quic3(30, 300, 3));
        writeManifest(1, "failed", true);
        Path markdown = dir.resolve("summary.md");
        assertEquals(2, summarize(markdown, "clean"));
        assertTrue(Files.readString(markdown).contains("| clean | quic3 | 1 | 1 | 0 | 1 | 0 | INCOMPLETE |"));
    }

    @Test
    void missingArtifactAndEntirelyUnattemptedCellsArePrinted() throws IOException {
        writeManifest(1, "ok", false);
        Path markdown = dir.resolve("summary.md");
        assertEquals(2, summarize(markdown, "clean"));
        String text = Files.readString(markdown);
        assertTrue(text.contains("| clean | tcp | 1 | 0 | 0 | 0 | 1 | INCOMPLETE |"), text);
        assertTrue(text.contains("| clean | quic3 | 1 | 1 | 0 | 0 | 1 | INCOMPLETE |"), text);
    }

    @Test
    void hopTableReportsCompleteLegsAndRejectsAnIncompleteHopRun() throws IOException {
        writeRaw("clean-tcp.json", "clean", "clean",
                hopRow(RunFixtures.tcp(10, 100, 1), 1, 300, 330, 6, null),
                hopRow(RunFixtures.tcp(10, 100, 1), 2, 320, 350, 6, null));
        writeRaw("clean-quic3.json", "clean", "clean",
                hopRow(RunFixtures.quic3(10, 100, 1), 1, 200, 240, 6, null),
                hopRow(RunFixtures.quic3(10, 100, 1), 2, 250, 260, 3, "leg 3 did not reach 90% within 30 s"));
        Path markdown = dir.resolve("summary.md");
        assertEquals(1, summarize(markdown, "clean"));
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("Teleport-hop table"), text);
        assertTrue(text.contains("| clean | TCP | hop fill median (ms) | 310.0 | 300.0 | 320.0 | 2 |"), text);
        assertTrue(text.contains("| clean | QUIC /3 | hop fill median (ms) | 200.0 | 200.0 | 200.0 | 1 |"), text);
        assertTrue(text.contains("| clean | TCP | hop fill p95 (ms) | 340.0 | 330.0 | 350.0 | 2 |"), text);
        assertTrue(text.contains("successful hop run that is incomplete: 1"), text);
    }

    @Test
    void rowsWithoutHopFieldsGetNoHopTable() throws IOException {
        write("clean-tcp.json", "clean", "clean", RunFixtures.tcp(10, 100, 1));
        write("clean-quic3.json", "clean", "clean", RunFixtures.quic3(20, 200, 2));
        Path markdown = dir.resolve("summary.md");
        summarize(markdown, "clean");
        assertFalse(Files.readString(markdown, StandardCharsets.UTF_8).contains("Teleport-hop table"));
    }

    /** A real fixture row with the teleport-hop fields the external arm writer adds. */
    private static JsonObject hopRow(RunResult fixture, int runIndex, double median, double p95, int legs, String error) {
        JsonObject row = GSON.toJsonTree(fixture).getAsJsonObject();
        row.addProperty("run", runIndex);
        row.addProperty("hop", true);
        row.addProperty("hopRequested", 6);
        row.addProperty("hopCount", legs);
        row.addProperty("hopFillMsMedian", median);
        row.addProperty("hopFillMsP95", p95);
        row.add("hopError", error == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(error));
        return row;
    }

    private void writeManifest(int rounds, String status, boolean all) throws IOException {
        // Fixtures currently use round zero; keep manifest identities tied to the row, not filenames.
        for (String file : List.of("tcp.json", "q2.json", "q3.json")) {
            if (!Files.exists(dir.resolve(file))) continue;
            JsonObject doc = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve(file))).getAsJsonObject();
            for (var run : doc.getAsJsonArray("runs")) run.getAsJsonObject().addProperty("run", 0);
            Files.writeString(dir.resolve(file), GSON.toJson(doc));
        }
        Files.writeString(dir.resolve("runs.tsv"), "#rounds\t" + rounds
                + "\n#profiles\tclean\n#arms\ttcp quic2 quic3\n"
                + (all ? "clean\ttcp\t0\tok\ttcp.json\nclean\tquic2\t0\tok\tq2.json\n" : "")
                + "clean\tquic3\t0\t" + status + "\tq3.json\n");
    }

    @Test
    void quic2RowsNeverSatisfyQuic3EvenWithQuic3LookingFields() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1),
                RunFixtures.tcp(20, 200, 2));
        RunResult disguised = RunFixtures.run(TransportVersion.QUIC_2, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), true, 30, 300, 3);
        write("clean-quic.json", "clean-quic", "clean",
                disguised,
                RunFixtures.quic2(40, 400, 4));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(2, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        String join = line(text, "| clean | join (ms) |");
        assertTrue(join.contains("15.0 (n=2)"), join);
        assertTrue(join.contains("35.0 (n=2)"), join);
        assertTrue(join.contains("| not run ("), join);
        assertTrue(text.contains("Matrix /3 gate: INCOMPLETE"), text);
    }

    @Test
    void completeNegotiatedEvidenceProducesAVerifiedQuic3Row() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1),
                RunFixtures.tcp(20, 200, 2));
        write("clean-quic.json", "clean-quic", "clean",
                RunFixtures.quic2(30, 300, 3),
                RunFixtures.quic2(40, 400, 4));
        write("clean-quic3.json", "clean-quic3", "clean",
                RunFixtures.quic3(50, 500, 5),
                RunFixtures.quic3(60, 600, 6));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(0, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        String join = line(text, "| clean | join (ms) |");
        assertTrue(join.contains("55.0 (n=2)"), join);
        assertTrue(join.contains("R=0123456789ab schema=2 streams=3 alpn=quicraft/3"), join);
        assertTrue(text.contains("Matrix /3 gate: PASS"), text);
    }

    @Test
    void incompleteQuic3EvidenceFailsClosedWithoutAMedian() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1));
        write("clean-quic.json", "clean-quic", "clean",
                RunFixtures.quic2(30, 300, 3));
        RunResult unverified = RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST, BenchmarkSchema.ROUTING_SCHEMA_V3,
                TransportVersion.QUIC_3.alpn(), false, 50, 500, 5);
        write("clean-quic3.json", "clean-quic3", "clean", unverified);

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        String join = line(text, "| clean | join (ms) |");
        assertTrue(join.contains("fail-closed (negotiatedTransportVerified is false)"), join);
        assertFalse(join.contains("50.0"), join);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
    }

    @Test
    void aChangingRoutingTableBetweenQuic3RunsFailsClosed() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1));
        write("clean-quic.json", "clean-quic", "clean",
                RunFixtures.quic2(30, 300, 3));
        String otherDigest = RunFixtures.DIGEST.replace('0', '1');
        write("clean-quic3.json", "clean-quic3", "clean",
                RunFixtures.quic3(50, 500, 5),
                RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION, 3,
                        RunFixtures.COMMIT, "protocol-775", otherDigest, BenchmarkSchema.ROUTING_SCHEMA_V3,
                        TransportVersion.QUIC_3.alpn(), true, 60, 600, 6));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("routing table identity changed between /3 runs"), text);
    }

    @Test
    void aFailedMeasuredRunFailsClosed() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1),
                RunFixtures.run(TransportVersion.TCP, false, BenchmarkSchema.VERSION, 1,
                        RunFixtures.COMMIT, "", "", 0, "", false, -1, -1, -1));
        write("clean-quic.json", "clean-quic", "clean",
                RunFixtures.quic2(30, 300, 3));
        write("clean-quic3.json", "clean-quic3", "clean",
                RunFixtures.quic3(50, 500, 5));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("Failed measured runs: 1 (disconnects: 0)"), text);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
    }

    @Test
    void malformedArtifactFailsClosedEvenWhenAllRowsVerify() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1));
        write("clean-quic.json", "clean-quic", "clean",
                RunFixtures.quic2(30, 300, 3));
        write("clean-quic3.json", "clean-quic3", "clean",
                RunFixtures.quic3(50, 500, 5));
        Files.writeString(dir.resolve("broken.json"), "{not json", StandardCharsets.UTF_8);

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("Unreadable JSON files skipped: 1"), text);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
    }

    @Test
    void aMeasuredRunFromAnotherSchemaFailsClosedEvenWithValidRows() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1),
                RunFixtures.tcp(20, 200, 2));
        write("clean-quic.json", "clean-quic", "clean",
                RunFixtures.quic2(30, 300, 3),
                RunFixtures.quic2(40, 400, 4));
        write("clean-quic3.json", "clean-quic3", "clean",
                RunFixtures.quic3(50, 500, 5),
                RunFixtures.quic3(60, 600, 6));
        write("clean-quic3-wrong-schema.json", "clean-quic3", "clean",
                RunFixtures.run(TransportVersion.QUIC_3, true, BenchmarkSchema.VERSION - 1, 3,
                        RunFixtures.COMMIT, "protocol-775", RunFixtures.DIGEST,
                        BenchmarkSchema.ROUTING_SCHEMA_V3, TransportVersion.QUIC_3.alpn(), true,
                        70, 700, 7));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("Rejected measured runs: 1"), text);
        assertTrue(text.contains("incompatible schema or transport: 1"), text);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
        String join = line(text, "| clean | join (ms) |");
        assertTrue(join.contains("55.0 (n=2)"), join);
        assertFalse(join.contains("70.0"), join);
    }

    @Test
    void aSuccessfulRunThatDisconnectedFailsTheGate() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1));
        write("clean-quic.json", "clean-quic", "clean",
                RunFixtures.quic2(30, 300, 3));
        write("clean-quic3.json", "clean-quic3", "clean",
                RunFixtures.disconnected(RunFixtures.quic3(50, 500, 5)),
                RunFixtures.quic3(60, 600, 6));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("Failed measured runs: 0 (disconnects: 1)"), text);
        assertTrue(text.contains("success reported after a disconnect: 1"), text);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
        String join = line(text, "| clean | join (ms) |");
        assertTrue(join.contains("60.0 (n=1)"), join);
        assertFalse(join.contains("55.0"), join);
    }

    @Test
    void missingRequiredFieldsFailClosedInsteadOfDeserializingToZero() throws IOException {
        JsonObject tcp = GSON.toJsonTree(RunFixtures.tcp(10, 100, 1)).getAsJsonObject();
        tcp.remove("joinMs");
        JsonObject quic2 = GSON.toJsonTree(RunFixtures.quic2(30, 300, 3)).getAsJsonObject();
        quic2.remove("disconnected");
        writeRaw("clean-tcp.json", "clean-tcp", "clean", tcp);
        writeRaw("clean-quic.json", "clean-quic", "clean", quic2);
        write("clean-quic3.json", "clean-quic3", "clean",
                RunFixtures.quic3(50, 500, 5));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("missing required fields: 2"), text);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
        String join = line(text, "| clean | join (ms) |");
        assertTrue(join.contains("not run (no measured runs)"), join);
        assertFalse(join.contains("| 0.0 (n="), join);
    }

    @Test
    void anArtifactWithNoUsableProfileFailsClosedEvenWithCompleteCells() throws IOException {
        completeCleanCells();
        // Valid cells alone would pass; an artifact the summarizer cannot attribute must not
        // disappear, or the directory would pass with part of its evidence missing.
        writeUnprofiled("orphan.json", RunFixtures.tcp(11, 110, 1));
        write("mystery.json", "mystery-tcp", "mystery", RunFixtures.tcp(12, 120, 1));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("Unclassifiable artifacts: 2 (no usable netem profile)"), text);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
        assertFalse(text.contains("Matrix /3 gate: PASS"), text);
    }

    @Test
    void aKnownProfileTheCallerDidNotRequestIsStillIgnored() throws IOException {
        completeCleanCells();
        write("loss.json", "loss-tcp", "loss",
                RunFixtures.tcp(11, 110, 1),
                RunFixtures.tcp(12, 120, 2));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(0, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("Matrix /3 gate: PASS"), text);
        assertFalse(text.contains("Unclassifiable artifacts"), text);
    }

    @Test
    void aSuccessfulRunWithANegativeTimeFailsClosedInsteadOfShrinkingTheSample() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, 1),
                RunFixtures.tcp(-5, 200, 2));
        write("clean-quic.json", "clean-quic", "clean",
                RunFixtures.quic2(30, -1, 3));
        write("clean-quic3.json", "clean-quic3", "clean",
                RunFixtures.quic3(50, 500, 5));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("impossible metric values: 2"), text);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
        assertFalse(text.contains("Matrix /3 gate: PASS"), text);
        String join = line(text, "| clean | join (ms) |");
        assertTrue(join.contains("10.0 (n=1)"), join);
        assertFalse(join.contains("-5.0"), join);
        String chunk = line(text, "| clean | chunk load (ms) |");
        assertTrue(chunk.contains("100.0 (n=1)"), chunk);
        assertFalse(chunk.contains("-1.0"), chunk);
    }

    @Test
    void anRttBelowTheUnavailableSentinelIsInvalidEvidence() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean", RunFixtures.tcp(10, 100, -2));
        write("clean-quic.json", "clean-quic", "clean", RunFixtures.quic2(30, 300, 3));
        write("clean-quic3.json", "clean-quic3", "clean", RunFixtures.quic3(50, 500, 5));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(1, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(text.contains("impossible metric values: 1"), text);
        assertTrue(text.contains("Matrix /3 gate: FAIL-CLOSED"), text);
    }

    @Test
    void anRttOnlyMinusOneCellIsUnavailableAndNeverAVerifiedMedian() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean", RunFixtures.tcp(10, 100, -1));
        write("clean-quic.json", "clean-quic", "clean", RunFixtures.quic2(30, 300, -1));
        write("clean-quic3.json", "clean-quic3", "clean", RunFixtures.quic3(50, 500, -1));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        // Join and chunk load verify; RTT has no sample, so the matrix stays incomplete.
        assertEquals(2, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        String rtt = line(text, "| clean | play RTT (ms) |");
        assertTrue(rtt.contains("not run (RTT unavailable in every successful run (rttMedianMs=-1))"), rtt);
        assertFalse(rtt.contains("(n="), rtt);
        assertTrue(text.contains("Matrix /3 gate: INCOMPLETE"), text);
        assertFalse(text.contains("Matrix /3 gate: PASS"), text);
    }

    @Test
    void anRttMedianWithOneValidSampleReportsThatSampleCount() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean",
                RunFixtures.tcp(10, 100, -1),
                RunFixtures.tcp(20, 200, 40));
        write("clean-quic.json", "clean-quic", "clean", RunFixtures.quic2(30, 300, 30));
        write("clean-quic3.json", "clean-quic3", "clean", RunFixtures.quic3(50, 500, 50));

        Path markdown = dir.resolve("matrix-summary.md");
        int status = summarize(markdown, "clean");

        assertEquals(0, status);
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        String rtt = line(text, "| clean | play RTT (ms) |");
        assertTrue(rtt.contains("40.0 (n=1)"), rtt);
        assertTrue(text.contains("Matrix /3 gate: PASS"), text);
    }

    @Test
    void profileOfPrefersTheBaseNetemProfileAndFallsBackForLegacyFiles() {
        JsonObject current = new JsonObject();
        current.addProperty("profile", "clean-tcp");
        current.addProperty("netemProfile", "clean");
        assertEquals("clean", MatrixSummarize.profileOf(current));

        JsonObject legacy = new JsonObject();
        legacy.addProperty("profile", "clean-quic-0rtt");
        assertEquals("clean", MatrixSummarize.profileOf(legacy));
    }

    @Test
    void transportSuffixesFallBackToTheNetemProfile() {
        assertEquals("clean", MatrixSummarize.baseProfile("clean-quic-0rtt"));
        assertEquals("clean", MatrixSummarize.baseProfile("clean-quic"));
        assertEquals("clean", MatrixSummarize.baseProfile("clean-tcp"));
        assertEquals("delayloss", MatrixSummarize.baseProfile("delayloss"));
    }

    /** One verified run per transport, enough for a complete and passing {@code clean} matrix. */
    private void completeCleanCells() throws IOException {
        write("clean-tcp.json", "clean-tcp", "clean", RunFixtures.tcp(10, 100, 1));
        write("clean-quic.json", "clean-quic", "clean", RunFixtures.quic2(30, 300, 3));
        write("clean-quic3.json", "clean-quic3", "clean", RunFixtures.quic3(50, 500, 5));
    }

    private void write(String file, String profile, String netemProfile, RunResult... runs) throws IOException {
        JsonObject doc = new JsonObject();
        doc.addProperty("profile", profile);
        doc.addProperty("netemProfile", netemProfile);
        doc.add("runs", GSON.toJsonTree(runs));
        Files.writeString(dir.resolve(file), GSON.toJson(doc), StandardCharsets.UTF_8);
    }

    /** Writes a result file with no profile fields, like a truncated or hand-edited artifact. */
    private void writeUnprofiled(String file, RunResult... runs) throws IOException {
        JsonObject doc = new JsonObject();
        doc.add("runs", GSON.toJsonTree(runs));
        Files.writeString(dir.resolve(file), GSON.toJson(doc), StandardCharsets.UTF_8);
    }

    /** Writes hand-built run objects so a test can omit fields a real writer always emits. */
    private void writeRaw(String file, String profile, String netemProfile, JsonObject... runs)
            throws IOException {
        JsonObject doc = new JsonObject();
        doc.addProperty("profile", profile);
        doc.addProperty("netemProfile", netemProfile);
        JsonArray array = new JsonArray();
        for (JsonObject run : runs) {
            array.add(run);
        }
        doc.add("runs", array);
        Files.writeString(dir.resolve(file), GSON.toJson(doc), StandardCharsets.UTF_8);
    }

    @Test
    void movingSummaryWithoutAManifestIsRefused() throws IOException {
        write("tcp.json", "clean-tcp", "clean", RunFixtures.tcp(10, 100, 1));
        write("q3.json", "clean-quic3", "clean", RunFixtures.quic3(30, 300, 3));
        Path markdown = dir.resolve("summary.md");
        int status = MatrixSummarize.run(Args.parse(new String[] {
                "--dir", dir.toString(), "--profiles", "clean", "--markdown", markdown.toString(), "--moving",
        }));
        assertEquals(1, status);
        assertFalse(Files.exists(markdown), "no summary may be written for an unverifiable moving run");
    }

    @Test
    void legacyDirectoryDiscoveryIsLabelledNonGating() throws IOException {
        write("tcp.json", "clean-tcp", "clean", RunFixtures.tcp(10, 100, 1));
        write("q3.json", "clean-quic3", "clean", RunFixtures.quic3(30, 300, 3));
        Path markdown = dir.resolve("summary.md");
        summarize(markdown, "clean");
        assertTrue(Files.readString(markdown).contains("NON-GATING"));
    }

    private int summarize(Path markdown, String profiles) throws IOException {
        return MatrixSummarize.run(Args.parse(new String[] {
                "--dir", dir.toString(),
                "--profiles", profiles,
                "--markdown", markdown.toString(),
        }));
    }

    private static String line(String text, String prefix) {
        return text.lines().filter(line -> line.startsWith(prefix)).findFirst().orElse("");
    }
}
