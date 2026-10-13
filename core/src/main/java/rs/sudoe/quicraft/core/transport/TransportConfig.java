// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * QUIC transport parameters (docs/protocol.md §9). Every limit is explicit: an unset value would
 * fall back to quiche's default of 0 and nothing could be sent.
 */
public final class TransportConfig {
    public enum CongestionControl { RENO, CUBIC, BBR }

    /**
     * When the listener sends a QUIC Retry to an admitted attempt (address validation,
     * docs/protocol.md §9). In every mode, an attempt over a limit, an attempt while the
     * open-connection limits are full, and every attempt while a quarter of the open-connection
     * limit is still in its handshake get a Retry and nothing more.
     */
    public enum RetryMode {
        /** Also Retry once the listener's attempts in the current second reach the Retry threshold. */
        ADAPTIVE,
        /** Retry every new connection: for tests and for owners under attack. */
        ALWAYS,
        /** No Retry for admitted attempts; refusals and a handshake backlog still get one. */
        NEVER
    }

    private static final Logger LOG = Logger.getLogger("QUICraft");
    private static final int MAX_LIMIT = 10_000_000;

    // System properties are read once, so a bad value logs one WARNING per JVM.
    private static final int DEFAULT_GLOBAL_ATTEMPTS = limitProperty("globalAttemptsPerSecond", 200);
    private static final int DEFAULT_PER_IP_ATTEMPTS = limitProperty("perIpAttemptsPerSecond", 20);
    private static final int DEFAULT_RETRY_THRESHOLD = limitProperty("retryThresholdPerSecond", 50);
    private static final int DEFAULT_MAX_OPEN = limitProperty("maxOpen", 2048);
    private static final int DEFAULT_MAX_OPEN_PER_IP = limitProperty("maxOpenPerIp", 32);
    private static final int DEFAULT_MAX_TRACKED_IPS = limitProperty("maxTrackedIps", 8192);
    private static final int DEFAULT_INGRESS_INITIALS = limitProperty("ingressInitialsPerSecond", 4000);
    private static final int DEFAULT_INGRESS_INITIALS_PER_IP = limitProperty("ingressInitialsPerIpPerSecond", 200);
    private static final RetryMode DEFAULT_RETRY_MODE = retryModeProperty();

    /**
     * v1 values. Provisional ones come from the Phase 1 benchmark (docs/benchmarks.md). Declared
     * after the property defaults that {@link Builder} reads.
     */
    public static final TransportConfig DEFAULT = builder().build();

    final long maxIdleTimeoutMillis;
    final long initialMaxData;
    final long initialMaxStreamData;
    final CongestionControl congestionControl;
    final int initialCongestionWindowPackets;
    final boolean earlyData;
    final boolean sessionResumption;
    final boolean relaxedLossThreshold;
    final int globalAttemptsPerSecond;
    final int perIpAttemptsPerSecond;
    final int retryThresholdPerSecond;
    final int maxOpen;
    final int maxOpenPerIp;
    final int maxTrackedIps;
    final int ingressInitialsPerSecond;
    final int ingressInitialsPerIpPerSecond;
    final RetryMode retryMode;

    private TransportConfig(Builder b) {
        this.maxIdleTimeoutMillis = b.maxIdleTimeoutMillis;
        this.initialMaxData = b.initialMaxData;
        this.initialMaxStreamData = b.initialMaxStreamData;
        this.congestionControl = b.congestionControl;
        this.initialCongestionWindowPackets = b.initialCongestionWindowPackets;
        this.earlyData = b.earlyData;
        this.sessionResumption = b.sessionResumption;
        this.relaxedLossThreshold = b.relaxedLossThreshold;
        this.globalAttemptsPerSecond = positiveOr(b.globalAttemptsPerSecond, DEFAULT_GLOBAL_ATTEMPTS);
        this.perIpAttemptsPerSecond = positiveOr(b.perIpAttemptsPerSecond, DEFAULT_PER_IP_ATTEMPTS);
        // ConnectionAdmission rejects a Retry threshold above the global budget.
        this.retryThresholdPerSecond = Math.min(positiveOr(b.retryThresholdPerSecond, DEFAULT_RETRY_THRESHOLD),
                globalAttemptsPerSecond);
        this.maxOpen = positiveOr(b.maxOpen, DEFAULT_MAX_OPEN);
        this.maxOpenPerIp = positiveOr(b.maxOpenPerIp, DEFAULT_MAX_OPEN_PER_IP);
        this.maxTrackedIps = positiveOr(b.maxTrackedIps, DEFAULT_MAX_TRACKED_IPS);
        this.ingressInitialsPerSecond = positiveOr(b.ingressInitialsPerSecond, DEFAULT_INGRESS_INITIALS);
        this.ingressInitialsPerIpPerSecond = positiveOr(b.ingressInitialsPerIpPerSecond,
                DEFAULT_INGRESS_INITIALS_PER_IP);
        this.retryMode = b.retryMode != null ? b.retryMode : DEFAULT_RETRY_MODE;
    }

    private static int positiveOr(int value, int fallback) {
        return value >= 1 && value <= MAX_LIMIT ? value : fallback;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CongestionControl congestionControl() {
        return congestionControl;
    }

    /**
     * Initial congestion window in packets, 64 by default. System property
     * {@code quicraft.initialCwndPackets} overrides it: an explicit {@code 0} selects quiche's
     * native default (10 packets), {@code 1..1000} the value, for experiments. An invalid value
     * (malformed text or out of range) falls back to 64.
     */
    public int initialCongestionWindowPackets() {
        return initialCongestionWindowPackets;
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

    /** Connection attempts per second, all clients together. System property {@code quicraft.admission.globalAttemptsPerSecond}. */
    public int globalAttemptsPerSecond() {
        return globalAttemptsPerSecond;
    }

    /** Connection attempts per second from one IP. System property {@code quicraft.admission.perIpAttemptsPerSecond}. */
    public int perIpAttemptsPerSecond() {
        return perIpAttemptsPerSecond;
    }

    /**
     * Attempts per second after which {@link RetryMode#ADAPTIVE} sends Retry; at most
     * {@link #globalAttemptsPerSecond()}. System property {@code quicraft.admission.retryThresholdPerSecond}.
     */
    public int retryThresholdPerSecond() {
        return retryThresholdPerSecond;
    }

    /** Open QUIC connections, pending and established. System property {@code quicraft.admission.maxOpen}. */
    public int maxOpen() {
        return maxOpen;
    }

    /** Open QUIC connections from one IP. System property {@code quicraft.admission.maxOpenPerIp}. */
    public int maxOpenPerIp() {
        return maxOpenPerIp;
    }

    /** IP addresses tracked per limiter. System property {@code quicraft.admission.maxTrackedIps}. */
    public int maxTrackedIps() {
        return maxTrackedIps;
    }

    /** Initial packets per second accepted at ingress. System property {@code quicraft.admission.ingressInitialsPerSecond}. */
    public int ingressInitialsPerSecond() {
        return ingressInitialsPerSecond;
    }

    /** Initial packets per second accepted from one IP. System property {@code quicraft.admission.ingressInitialsPerIpPerSecond}. */
    public int ingressInitialsPerIpPerSecond() {
        return ingressInitialsPerIpPerSecond;
    }

    /** System property {@code quicraft.admission.retry}: {@code adaptive}, {@code always} or {@code never}. */
    public RetryMode retryMode() {
        return retryMode;
    }

    private static int limitProperty(String name, int fallback) {
        String key = "quicraft.admission." + name;
        String value = System.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 1 && parsed <= MAX_LIMIT) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        LOG.warning("Invalid " + key + "=" + value + " (expected 1.." + MAX_LIMIT + "); using " + fallback);
        return fallback;
    }

    private static RetryMode retryModeProperty() {
        String value = System.getProperty("quicraft.admission.retry");
        if (value == null) {
            return RetryMode.ADAPTIVE;
        }
        try {
            return RetryMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warning("Invalid quicraft.admission.retry=" + value + " (expected adaptive, always or never); using adaptive");
            return RetryMode.ADAPTIVE;
        }
    }

    /** BBR; system property {@code quicraft.congestionControl} (RENO, CUBIC, BBR) for experiments. */
    private static CongestionControl defaultCongestionControl() {
        try {
            return CongestionControl.valueOf(System.getProperty("quicraft.congestionControl", "BBR").trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CongestionControl.BBR;
        }
    }

    /**
     * 64 packets by default. System property {@code quicraft.initialCwndPackets} overrides it:
     * an explicit {@code 0} selects quiche's native default (10 packets), {@code 1..1000} the
     * value, for experiments. A malformed or out-of-range value falls back to 64.
     */
    private static int defaultInitialCwndPackets() {
        String value = System.getProperty("quicraft.initialCwndPackets");
        if (value == null) {
            return 64;
        }
        try {
            int packets = Integer.parseInt(value.trim());
            if (packets == 0) {
                return 0;
            }
            if (packets >= 1 && packets <= 1000) {
                return packets;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the default
        }
        return 64;
    }

    public static final class Builder {
        private long maxIdleTimeoutMillis = TimeUnit.SECONDS.toMillis(60);
        private long initialMaxData = 16L << 20;
        private long initialMaxStreamData = 8L << 20;
        private CongestionControl congestionControl = defaultCongestionControl();
        private int initialCongestionWindowPackets = defaultInitialCwndPackets();
        private boolean earlyData = true;
        private boolean sessionResumption = true;
        private boolean relaxedLossThreshold = true;
        private int globalAttemptsPerSecond = DEFAULT_GLOBAL_ATTEMPTS;
        private int perIpAttemptsPerSecond = DEFAULT_PER_IP_ATTEMPTS;
        private int retryThresholdPerSecond = DEFAULT_RETRY_THRESHOLD;
        private int maxOpen = DEFAULT_MAX_OPEN;
        private int maxOpenPerIp = DEFAULT_MAX_OPEN_PER_IP;
        private int maxTrackedIps = DEFAULT_MAX_TRACKED_IPS;
        private int ingressInitialsPerSecond = DEFAULT_INGRESS_INITIALS;
        private int ingressInitialsPerIpPerSecond = DEFAULT_INGRESS_INITIALS_PER_IP;
        private RetryMode retryMode = DEFAULT_RETRY_MODE;

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

        public Builder initialCongestionWindowPackets(int packets) {
            this.initialCongestionWindowPackets = packets;
            return this;
        }

        public Builder earlyData(boolean enabled) {
            this.earlyData = enabled;
            return this;
        }

        /**
         * Client: resume TLS sessions from tickets the same server issued earlier in this
         * process, skipping the certificate exchange (docs/protocol.md §8). Off gives every
         * connect a full handshake, e.g. to measure one.
         */
        public Builder sessionResumption(boolean enabled) {
            this.sessionResumption = enabled;
            return this;
        }

        /** Sender-side only; the peer never sees it (docs/protocol.md §9). */
        public Builder relaxedLossThreshold(boolean enabled) {
            this.relaxedLossThreshold = enabled;
            return this;
        }

        // Admission limits (docs/protocol.md §9). A value outside 1..10,000,000 means the default.

        public Builder globalAttemptsPerSecond(int value) {
            this.globalAttemptsPerSecond = value;
            return this;
        }

        public Builder perIpAttemptsPerSecond(int value) {
            this.perIpAttemptsPerSecond = value;
            return this;
        }

        /** Clamped to {@link #globalAttemptsPerSecond(int)}. */
        public Builder retryThresholdPerSecond(int value) {
            this.retryThresholdPerSecond = value;
            return this;
        }

        public Builder maxOpen(int value) {
            this.maxOpen = value;
            return this;
        }

        public Builder maxOpenPerIp(int value) {
            this.maxOpenPerIp = value;
            return this;
        }

        public Builder maxTrackedIps(int value) {
            this.maxTrackedIps = value;
            return this;
        }

        public Builder ingressInitialsPerSecond(int value) {
            this.ingressInitialsPerSecond = value;
            return this;
        }

        public Builder ingressInitialsPerIpPerSecond(int value) {
            this.ingressInitialsPerIpPerSecond = value;
            return this;
        }

        /** Null means the default. */
        public Builder retryMode(RetryMode mode) {
            this.retryMode = mode;
            return this;
        }

        public TransportConfig build() {
            return new TransportConfig(this);
        }
    }
}
