// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import rs.sudoe.quicraft.testkit.Args;

/**
 * Read-only validator for one merged real-Fabric external-arm sample document of any arm, as the
 * emitter and runner write it (plan {@code .agents/plans/2026-10-20-external-arms.md}).
 *
 * <p>Envelope contract: {@code clientType} is {@code fabric}, {@code arm} is {@code tcp},
 * {@code quic2} or {@code quic3}, {@code netemProfile} is one of {@code clean loss delay delayloss
 * reorder jitter}, {@code profile} is exactly {@code <netemProfile>-tcp}, {@code -quic-2} or
 * {@code -quic-3} matching the arm, and {@code runs} is a nonempty array. Other envelope fields
 * are allowed.
 *
 * <p>{@code quic3} delegates every rule to {@link V3Samples}; this class only checks that the
 * envelope names that arm. {@code tcp} and {@code quic2} reuse its file parser and limits, its
 * envelope checks, its per-row completeness, phase, metric and status checks, and its
 * duplicate-run, measured-row and shared-identity checks. Their transport gates require, on every
 * row: the arm's {@link TransportVersion}, its empty or {@code quicraft/2} ALPN, a verified
 * transport, {@code streamCount} 1, empty routing identity/digest, routing schema 0, the current
 * {@link BenchmarkSchema#VERSION} and a clean 40-hex build commit shared by all rows.
 *
 * <p>Limits: the same 8 MiB, 64 nesting levels, strict UTF-8 and strict JSON syntax as
 * {@link V3Samples}, including duplicate-key and trailing-token refusal. Missing {@code --in} is a
 * usage error (2); a valid document returns 0; an unreadable, oversized, malformed or inconsistent
 * document returns 1. The command only reads: it never writes a file, opens a socket or starts a
 * game. A pass certifies structure and self-consistency only; every value is self-reported.
 */
public final class ArmSamples {
    private static final Set<String> ARMS = Set.of("tcp", "quic2", "quic3");

    /** A clean full Git SHA-1: exactly 40 lowercase hex characters, no dirty suffix. */
    private static final Pattern FULL_COMMIT = Pattern.compile("[0-9a-f]{40}");

    private static final String SUCCESS =
            "external-arm samples structurally valid and internally consistent;"
                    + " provenance self-reported, live measurement not verified.";

    private ArmSamples() {}

    /**
     * Validates the file named by {@code --in}. Returns 0 for a valid document, 1 for an
     * unreadable, oversized or invalid one, and 2 when {@code --in} is missing or unusable.
     */
    public static int run(Args args) {
        String spec = args == null ? null : args.string("in", null);
        if (spec == null || spec.isBlank() || "true".equals(spec)) {
            System.err.println("arm-samples: missing --in <file>; usage: arm-samples --in FILE");
            return 2;
        }
        Path path;
        try {
            path = Path.of(spec);
        } catch (InvalidPathException e) {
            System.err.println("arm-samples: invalid --in path: " + spec);
            return 2;
        }

        JsonElement root;
        try {
            root = V3Samples.parse(path);
        } catch (V3Samples.InvalidDocument e) {
            return invalid(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return invalid("not valid JSON: " + e.getMessage());
        }

        try {
            Summary summary = validate(root);
            System.out.println(SUCCESS);
            System.out.println("arm: " + summary.arm());
            System.out.println("rows: " + summary.rows() + " (measured " + summary.measured()
                    + ", warmup " + summary.warmup() + ")");
            return 0;
        } catch (V3Samples.InvalidDocument e) {
            return invalid(e.getMessage());
        }
    }

    private static int invalid(String detail) {
        System.err.println("arm-samples: " + V3Samples.sanitize(detail));
        return 1;
    }

    /** Counts of a valid document, for the success line only; never a performance value. */
    private record Summary(String arm, int rows, int measured, int warmup) {}

    static Summary validate(JsonElement rootElement) throws V3Samples.InvalidDocument {
        JsonObject root = V3Samples.asObject(rootElement, "root");
        String arm = V3Samples.stringField(root, "arm", "root");
        if (!ARMS.contains(arm)) {
            throw new V3Samples.InvalidDocument(
                    "root.arm must be one of [tcp, quic2, quic3], not " + V3Samples.quote(arm));
        }
        if ("quic3".equals(arm)) {
            V3Samples.Summary v3 = V3Samples.validate(rootElement);
            return new Summary(arm, v3.rows(), v3.measured(), v3.warmup());
        }

        JsonArray runs = V3Samples.validateEnvelope(rootElement, profileSuffix(arm));
        List<RunResult> results =
                V3Samples.validateRows(runs, (row, what) -> validateRow(row, what, arm));
        V3Samples.requireSharedIdentity(results);
        int warmups = 0;
        int measuredOk = 0;
        for (RunResult result : results) {
            if (result.warmup()) {
                warmups++;
            } else if (result.ok()) {
                measuredOk++;
            }
        }
        if (measuredOk == 0) {
            throw new V3Samples.InvalidDocument("at least one non-warmup ok row is required");
        }
        return new Summary(arm, results.size(), results.size() - warmups, warmups);
    }

    /**
     * Applies the {@code tcp} / {@code quic2} transport gates to a row already checked against
     * the shared {@link V3Samples} shape.
     */
    private static RunResult validateRow(JsonObject row, String what, String arm)
            throws V3Samples.InvalidDocument {
        V3Samples.validateRowShape(row, what);
        TransportVersion expected =
                "tcp".equals(arm) ? TransportVersion.TCP : TransportVersion.QUIC_2;
        String transport = V3Samples.stringField(row, "transportVersion", what);
        if (!expected.name().equals(transport)) {
            throw new V3Samples.InvalidDocument(what + ".transportVersion must be "
                    + expected.name() + " for arm " + arm + ", not " + V3Samples.quote(transport));
        }
        RunResult result = V3Samples.convertRow(row, what);
        if (!expected.alpn().equals(result.negotiatedAlpn())) {
            throw new V3Samples.InvalidDocument(what + ".negotiatedAlpn must be "
                    + V3Samples.quote(expected.alpn()) + " for arm " + arm + ", not "
                    + V3Samples.quote(result.negotiatedAlpn()));
        }
        if (!result.negotiatedTransportVerified()) {
            throw new V3Samples.InvalidDocument(
                    what + ".negotiatedTransportVerified must be true on every ok row");
        }
        if (result.streamCount() != 1) {
            throw new V3Samples.InvalidDocument(what + ".streamCount must be 1 for arm " + arm
                    + ", not " + result.streamCount());
        }
        if (!result.routingTableId().isEmpty()) {
            throw new V3Samples.InvalidDocument(
                    what + ".routingTableId must be empty for arm " + arm);
        }
        if (!result.routingTableDigest().isEmpty()) {
            throw new V3Samples.InvalidDocument(
                    what + ".routingTableDigest must be empty for arm " + arm);
        }
        if (result.routingTableSchema() != 0) {
            throw new V3Samples.InvalidDocument(what + ".routingTableSchema must be 0 for arm "
                    + arm + ", not " + result.routingTableSchema());
        }
        if (result.schemaVersion() != BenchmarkSchema.VERSION) {
            throw new V3Samples.InvalidDocument(what + ".schemaVersion must be "
                    + BenchmarkSchema.VERSION + ", not " + result.schemaVersion());
        }
        if (!FULL_COMMIT.matcher(result.buildCommit()).matches()) {
            throw new V3Samples.InvalidDocument(what + ".buildCommit must be a clean 40-hex"
                    + " commit, not " + V3Samples.quote(result.buildCommit()));
        }
        return result;
    }

    private static String profileSuffix(String arm) {
        return switch (arm) {
            case "tcp" -> "-tcp";
            case "quic2" -> "-quic-2";
            case "quic3" -> "-quic-3";
            default -> throw new IllegalArgumentException("unknown arm " + arm);
        };
    }
}
