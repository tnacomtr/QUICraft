// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import java.util.Locale;
import java.util.Objects;

/**
 * One comparison row: a netem profile and metric with one cell per transport. A verified
 * {@code /3} cell may only come from a run that passed {@link NegotiatedTransportCheck}; a
 * {@code /2} result is never promoted to it.
 */
public record ComparisonRow(String profile, String metric, Cell tcp, Cell quic2, Cell quic3) {
    public ComparisonRow {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(tcp, "tcp");
        Objects.requireNonNull(quic2, "quic2");
        Objects.requireNonNull(quic3, "quic3");
        if (tcp.transport() != TransportVersion.TCP
                || quic2.transport() != TransportVersion.QUIC_2
                || quic3.transport() != TransportVersion.QUIC_3) {
            throw new IllegalArgumentException("comparison cells must be TCP, QUIC_2 and QUIC_3");
        }
    }

    /** Whether the {@code /3} cell carries complete negotiated evidence. */
    public boolean quic3Verified() {
        return quic3.status() == Status.VERIFIED;
    }

    /** Cell status. Only VERIFIED may be read as a successful comparison. */
    public enum Status {
        VERIFIED,
        NOT_RUN,
        FAIL_CLOSED
    }

    /**
     * One transport's value in a comparison row. VERIFIED requires a finite median and at least one
     * sample; the other states carry no value at all, so a missing or rejected arm can never be
     * printed as a number.
     */
    public record Cell(TransportVersion transport, Status status, double medianMs, int samples, String detail) {
        public Cell {
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (status == Status.VERIFIED) {
                if (!Double.isFinite(medianMs) || medianMs < 0 || samples < 1) {
                    throw new IllegalArgumentException("a verified cell needs a nonnegative median and samples");
                }
            } else if (!Double.isNaN(medianMs) || samples != 0) {
                throw new IllegalArgumentException("a non-verified cell cannot carry a median or samples");
            }
        }

        public static Cell verified(TransportVersion transport, double medianMs, int samples, String detail) {
            return new Cell(transport, Status.VERIFIED, medianMs, samples, detail);
        }

        public static Cell notRun(TransportVersion transport, String detail) {
            return new Cell(transport, Status.NOT_RUN, Double.NaN, 0, detail);
        }

        public static Cell failClosed(TransportVersion transport, String detail) {
            return new Cell(transport, Status.FAIL_CLOSED, Double.NaN, 0, detail);
        }

        /** Markdown cell text, with the median only for a verified arm. */
        public String render() {
            if (status == Status.VERIFIED) {
                return String.format(Locale.ROOT, "%.1f (n=%d)", medianMs, samples);
            }
            return status == Status.NOT_RUN ? "not run (" + detail + ")" : "fail-closed (" + detail + ")";
        }
    }
}
