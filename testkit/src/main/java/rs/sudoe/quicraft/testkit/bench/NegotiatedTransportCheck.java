// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

/**
 * Fail-closed check for a negotiated {@code /3} run. A run verified here is the only source a
 * {@code /3} comparison cell may use. Everything a later U3 driver claims must be present:
 *
 * <ul>
 *   <li>the run itself is a successful {@code QUIC_3} run;
 *   <li>the connection path marked the transport verified and records the {@code quicraft/3} ALPN;
 *   <li>a routing table identity and its schema 2 SHA-256 digest are present;
 *   <li>the build commit is a real commit, the stream count is positive, and the result schema is
 *       current.
 * </ul>
 *
 * <p>A {@code /2} run fails even when every other field looks like {@code /3}. Transport identity is
 * checked first and is never inferred from a label.
 */
public final class NegotiatedTransportCheck {
    /** Outcome of checking one run. */
    public enum Verdict {
        VERIFIED,
        NOT_APPLICABLE,
        FAIL_CLOSED
    }

    /** A check result. Only {@link Verdict#VERIFIED} may be shown as a measured {@code /3} value. */
    public record Result(Verdict verdict, String detail) {
        public boolean verified() {
            return verdict == Verdict.VERIFIED;
        }
    }

    private NegotiatedTransportCheck() {}

    /**
     * Checks {@code run} against the requested transport. Only {@code QUIC_3} has a fail-closed
     * assertion; other expected versions return {@link Verdict#NOT_APPLICABLE}.
     */
    public static Result check(TransportVersion expected, RunResult run) {
        if (expected != TransportVersion.QUIC_3) {
            return new Result(Verdict.NOT_APPLICABLE, "negotiation evidence is required only for QUIC_3");
        }
        if (run == null) {
            return fail("no run");
        }
        if (run.transportVersion() != TransportVersion.QUIC_3) {
            return fail("run transport is " + run.transportVersion() + ", not QUIC_3");
        }
        if (!run.ok()) {
            return fail("run did not complete");
        }
        if (!run.negotiatedTransportVerified()) {
            return fail("negotiatedTransportVerified is false");
        }
        if (!TransportVersion.QUIC_3.alpn().equals(run.negotiatedAlpn())) {
            return fail("negotiated ALPN is " + quote(run.negotiatedAlpn()) + ", not quicraft/3");
        }
        if (run.schemaVersion() != BenchmarkSchema.VERSION) {
            return fail("result schema is " + run.schemaVersion() + ", not " + BenchmarkSchema.VERSION);
        }
        if (!BenchmarkSchema.isBuildCommit(run.buildCommit())) {
            return fail("build commit is missing or not a clean full commit: " + quote(run.buildCommit()));
        }
        if (run.streamCount() < 1) {
            return fail("stream count is " + run.streamCount());
        }
        if (!BenchmarkSchema.isRoutingTableId(run.routingTableId())) {
            return fail("routing table identity is missing or a placeholder");
        }
        if (!BenchmarkSchema.isRoutingDigest(run.routingTableDigest())) {
            return fail("routing table digest is missing or not 64 lowercase hex characters");
        }
        if (run.routingTableSchema() != BenchmarkSchema.ROUTING_SCHEMA_V3) {
            return fail("routing table schema is " + run.routingTableSchema() + ", not "
                    + BenchmarkSchema.ROUTING_SCHEMA_V3);
        }
        return new Result(Verdict.VERIFIED, "R=" + shortDigest(run.routingTableDigest())
                + " schema=" + run.routingTableSchema() + " streams=" + run.streamCount()
                + " alpn=" + run.negotiatedAlpn() + " commit=" + run.buildCommit());
    }

    private static Result fail(String detail) {
        return new Result(Verdict.FAIL_CLOSED, detail);
    }

    private static String shortDigest(String digest) {
        return digest == null ? "none" : digest.substring(0, Math.min(12, digest.length()));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
