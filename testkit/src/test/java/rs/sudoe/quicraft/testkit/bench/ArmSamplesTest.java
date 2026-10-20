// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests for {@link ArmSamples#run(Args)} on synthetic fixtures only; a pass certifies structure,
 * never a live measurement.
 */
@ResourceLock("quicraft-v3-samples-system-streams")
class ArmSamplesTest {
    private static final String COMMIT = "3fa1910e29a4c7d5b8e6f0a1b2c3d4e5f6071892";
    private static final String DIGEST =
            "9c8b7a65f4e3d2c10987654321fedcba9876543210fedcba9876543210fedcba";

    @TempDir
    private Path dir;

    private record Outcome(int code, String out, String err) {}

    private static JsonPrimitive num(String v) {
        return new JsonPrimitive(new BigDecimal(v));
    }

    private static JsonArray phases() {
        JsonArray a = new JsonArray();
        for (String v : new String[] {"10", "20", "30", "40"}) {
            a.add(num(v));
        }
        return a;
    }

    private static JsonObject row(String arm, boolean warmup) {
        JsonObject r = new JsonObject();
        r.add("run", num(warmup ? "6" : "7"));
        r.add("warmup", new JsonPrimitive(warmup));
        r.add("ok", new JsonPrimitive(true));
        r.add("phasesMs", phases());
        r.add("joinMs", num("40"));
        r.add("chunkLoadMs", num("805.5"));
        r.add("chunks", num("64"));
        r.add("rttMedianMs", num("12.5"));
        r.add("rttP95Ms", num("20"));
        r.add("rttSamples", num("100"));
        r.add("rttLost", num("3"));
        r.add("disconnected", new JsonPrimitive(false));
        r.add("error", JsonNull.INSTANCE);
        r.add("net", JsonNull.INSTANCE);
        r.add("quic", JsonNull.INSTANCE);
        r.add("zeroRtt", new JsonPrimitive(false));
        r.add("schemaVersion", num(Integer.toString(BenchmarkSchema.VERSION)));
        r.add("buildCommit", new JsonPrimitive(COMMIT));
        switch (arm) {
            case "quic3" -> {
                r.add("transportVersion", new JsonPrimitive(TransportVersion.QUIC_3.name()));
                r.add("streamCount", num("3"));
                r.add("routingTableId", new JsonPrimitive("quicraft-v3-routing-1"));
                r.add("routingTableDigest", new JsonPrimitive(DIGEST));
                r.add("routingTableSchema", num(Integer.toString(BenchmarkSchema.ROUTING_SCHEMA_V3)));
                r.add("negotiatedAlpn", new JsonPrimitive(TransportVersion.QUIC_3.alpn()));
            }
            default -> {
                TransportVersion t = "tcp".equals(arm) ? TransportVersion.TCP : TransportVersion.QUIC_2;
                r.add("transportVersion", new JsonPrimitive(t.name()));
                r.add("streamCount", num("1"));
                r.add("routingTableId", new JsonPrimitive(""));
                r.add("routingTableDigest", new JsonPrimitive(""));
                r.add("routingTableSchema", num("0"));
                r.add("negotiatedAlpn", new JsonPrimitive(t.alpn()));
            }
        }
        r.add("negotiatedTransportVerified", new JsonPrimitive(true));
        return r;
    }

    private static String suffix(String arm) {
        return switch (arm) {
            case "tcp" -> "-tcp";
            case "quic2" -> "-quic-2";
            default -> "-quic-3";
        };
    }

    private static JsonObject doc(String arm, JsonObject... rows) {
        JsonObject root = new JsonObject();
        root.add("clientType", new JsonPrimitive("fabric"));
        root.add("arm", new JsonPrimitive(arm));
        root.add("netemProfile", new JsonPrimitive("clean"));
        root.add("profile", new JsonPrimitive("clean" + suffix(arm)));
        JsonArray runs = new JsonArray();
        for (JsonObject r : rows) {
            runs.add(r);
        }
        root.add("runs", runs);
        return root;
    }

    private static JsonObject good(String arm) {
        return doc(arm, row(arm, true), row(arm, false));
    }

    private static JsonObject firstMeasured(JsonObject doc) {
        return doc.getAsJsonArray("runs").get(1).getAsJsonObject();
    }

    private Outcome runDoc(JsonObject doc) throws IOException {
        Path file = dir.resolve("doc.json");
        Files.writeString(file, doc.toString(), StandardCharsets.UTF_8);
        return run("--in", file.toString());
    }

    private static Outcome run(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oo = System.out;
        PrintStream oe = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = ArmSamples.run(Args.parse(argv));
            return new Outcome(code, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oo);
            System.setErr(oe);
        }
    }

    private void assertRejected(JsonObject doc, String reason) throws IOException {
        Outcome o = runDoc(doc);
        assertEquals(1, o.code(), o.err());
        assertTrue(o.err().contains(reason), o.err());
    }

    @Test
    void validTcpDocumentPasses() throws IOException {
        Outcome o = runDoc(good("tcp"));
        assertEquals(0, o.code(), o.err());
        assertTrue(o.out().contains("live measurement not verified"));
    }

    @Test
    void validQuic2DocumentPasses() throws IOException {
        assertEquals(0, runDoc(good("quic2")).code());
    }

    @Test
    void quic3DelegatesToV3Rules() throws IOException {
        assertEquals(0, runDoc(good("quic3")).code());
        JsonObject bad = good("quic3");
        firstMeasured(bad).add("streamCount", num("1"));
        assertEquals(1, runDoc(bad).code());
    }

    @Test
    void quic3WithTooFewStreamsRejected() throws IOException {
        JsonObject d = good("quic3");
        for (com.google.gson.JsonElement r : d.getAsJsonArray("runs")) {
            r.getAsJsonObject().add("streamCount", num("2"));
        }
        assertRejected(d, "streamCount must be at least 3");
    }

    @Test
    void wrongProfileSuffixRejected() throws IOException {
        JsonObject d = good("tcp");
        d.add("profile", new JsonPrimitive("clean-quic-2"));
        assertRejected(d, "root.profile");
    }

    @Test
    void wrongTransportVersionRejected() throws IOException {
        JsonObject d = good("tcp");
        firstMeasured(d).add("transportVersion", new JsonPrimitive(TransportVersion.QUIC_2.name()));
        assertRejected(d, "transportVersion");
    }

    @Test
    void quic2WrongAlpnRejected() throws IOException {
        JsonObject d = good("quic2");
        firstMeasured(d).add("negotiatedAlpn", new JsonPrimitive("quicraft/3"));
        assertRejected(d, "negotiatedAlpn");
    }

    @Test
    void streamCountNotOneRejected() throws IOException {
        JsonObject d = good("quic2");
        firstMeasured(d).add("streamCount", num("2"));
        assertRejected(d, "streamCount");
    }

    @Test
    void nonemptyRoutingTableRejected() throws IOException {
        JsonObject d = good("tcp");
        firstMeasured(d).add("routingTableId", new JsonPrimitive("x"));
        assertRejected(d, "routingTableId");
    }

    @Test
    void dirtyCommitRejected() throws IOException {
        JsonObject d = good("tcp");
        for (var e : d.getAsJsonArray("runs")) {
            e.getAsJsonObject().add("buildCommit", new JsonPrimitive(COMMIT + "-dirty"));
        }
        assertEquals(1, runDoc(d).code());
    }

    @Test
    void mixedCommitsRejected() throws IOException {
        JsonObject d = good("quic2");
        firstMeasured(d).add("buildCommit", new JsonPrimitive("0".repeat(40)));
        assertEquals(1, runDoc(d).code());
    }

    @Test
    void unverifiedTransportRejected() throws IOException {
        JsonObject d = good("quic2");
        firstMeasured(d).add("negotiatedTransportVerified", new JsonPrimitive(false));
        assertEquals(1, runDoc(d).code());
    }

    @Test
    void missingInIsUsageError() {
        assertEquals(2, run().code());
    }

    @Test
    void unknownOrMissingArmRejected() throws IOException {
        JsonObject d = good("tcp");
        d.add("arm", new JsonPrimitive("quic9"));
        assertRejected(d, "root.arm");
        JsonObject m = good("tcp");
        m.remove("arm");
        assertEquals(1, runDoc(m).code());
    }

    @Test
    void onlyWarmupRowsRejected() throws IOException {
        assertEquals(1, runDoc(doc("tcp", row("tcp", true))).code());
    }
}
