// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.util.concurrent.TimeUnit;

/**
 * QUIC transport parameters (docs/protocol.md §9). Every limit is explicit: an unset value would
 * fall back to quiche's default of 0 and nothing could be sent.
 */
public final class TransportConfig {
    public enum CongestionControl { RENO, CUBIC, BBR }

    /** v1 values. Provisional ones come from the Phase 1 benchmark (docs/benchmarks.md). */
    public static final TransportConfig DEFAULT = builder().build();

    final long maxIdleTimeoutMillis;
    final long initialMaxData;
    final long initialMaxStreamData;
    final CongestionControl congestionControl;
    final boolean earlyData;
    final boolean relaxedLossThreshold;

    private TransportConfig(Builder b) {
        this.maxIdleTimeoutMillis = b.maxIdleTimeoutMillis;
        this.initialMaxData = b.initialMaxData;
        this.initialMaxStreamData = b.initialMaxStreamData;
        this.congestionControl = b.congestionControl;
        this.earlyData = b.earlyData;
        this.relaxedLossThreshold = b.relaxedLossThreshold;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CongestionControl congestionControl() {
        return congestionControl;
    }

    /**
     * Whether quiche relaxes its loss-detection thresholds after a spurious loss (packet
     * reordering). Applied only where the loaded native supports it
     * ({@link rs.sudoe.quicraft.core.QuicSupport#hasRelaxedLossThreshold()}); quiche 0.30 implements
     * it for BBR only.
     */
    public boolean relaxedLossThreshold() {
        return relaxedLossThreshold;
    }

    public static final class Builder {
        private long maxIdleTimeoutMillis = TimeUnit.SECONDS.toMillis(60);
        private long initialMaxData = 16L << 20;
        private long initialMaxStreamData = 8L << 20;
        private CongestionControl congestionControl = CongestionControl.BBR;
        private boolean earlyData = true;
        private boolean relaxedLossThreshold = true;

        public Builder maxIdleTimeout(long value, TimeUnit unit) {
            this.maxIdleTimeoutMillis = unit.toMillis(value);
            return this;
        }

        public Builder initialMaxData(long bytes) {
            this.initialMaxData = bytes;
            return this;
        }

        public Builder initialMaxStreamData(long bytes) {
            this.initialMaxStreamData = bytes;
            return this;
        }

        public Builder congestionControl(CongestionControl algorithm) {
            this.congestionControl = algorithm;
            return this;
        }

        public Builder earlyData(boolean enabled) {
            this.earlyData = enabled;
            return this;
        }

        /** Sender-side only; the peer never sees it (docs/protocol.md §9). */
        public Builder relaxedLossThreshold(boolean enabled) {
            this.relaxedLossThreshold = enabled;
            return this;
        }

        public TransportConfig build() {
            return new TransportConfig(this);
        }
    }
}
