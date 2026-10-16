// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.profile.ServerProfile;
import rs.sudoe.quicraft.core.resource.ResourcePools;

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

    /**
     * /3 (docs/protocol.md 13.13): the client advertises this much connection credit, i.e. what the
     * server may send (clientbound). The client pins its connection maximum to the same value.
     */
    public static final long MULTISTREAM_CLIENTBOUND_INITIAL_MAX_DATA = 2L << 20;
    /**
     * /3 (13.13): the server's window on client-opened bidirectional streams (CONTROL and REALTIME)
     * and the largest it may grow to. Pinned by Codecs through netty/0005, so quiche never grows it.
     */
    public static final long MULTISTREAM_INITIAL_MAX_STREAM_DATA = 8L << 10;
    /**
     * /3 (13.13): the server's window on server-opened bidirectional streams (reverse CHUNK guards).
     * They carry no valid bytes, so the first byte closes the connection and nothing replenishes them.
     */
    public static final long MULTISTREAM_REVERSE_CHUNK_STREAM_DATA = 1;
    /**
     * Legacy window for the server's server-opened bidi streams while the legacy 3-lane LaneSession is the
     * {@code /3} implementation: 13.13 says 1 byte, but that LaneSession still sends client-to-server bytes on
     * its server-opened stream 1, so the one-byte window would stall it. 13.13 says 1 byte; the U8
     * integration unit switches to the 1-byte value when LaneSession moves to the N+2 design.
     */
    public static final long LEGACY_LANE_REVERSE_STREAM_DATA = 8L << 10;
    /**
     * True while the legacy 3-lane LaneSession is the {@code /3} implementation, so Codecs applies
     * {@link #LEGACY_LANE_REVERSE_STREAM_DATA}. The U8 integration unit sets this to false.
     */
    static final boolean LEGACY_LANE_SESSION = true;
    /**
     * /3: the server advertises this much connection credit, i.e. what the client may send (serverbound).
     * It is fixed before N is known and is the staged pre-login exposure (12.13): the server pins both its
     * connection maximum and its stream maxima so quiche never grows them. Pinned by
     * {@code MultistreamTransportConfigTest}.
     */
    public static final long MULTISTREAM_SERVERBOUND_INITIAL_MAX_DATA = 24L << 10;
    /** /3: the server lets the client open streams 0 and 4. */
    public static final int MULTISTREAM_SERVER_MAX_STREAMS_BIDI = 2;
    /** /3: the client lets the server open up to six chunk streams; application validation caps N (13.2). */
    public static final int MULTISTREAM_CLIENT_MAX_STREAMS_BIDI = 6;
    /**
     * /3 (13.13): the client's initial and maximum window on each bidirectional stream. The client pins
     * its maxima to these values, so its windows never auto-tune beyond the 13.12 reservations.
     */
    public static final long MULTISTREAM_MAX_STREAM_WINDOW = 256L << 10;
    /** /3 (13.13): the client's initial and maximum connection window: 2 MiB. */
    public static final long MULTISTREAM_MAX_CONNECTION_WINDOW = 2L << 20;

    private static final Logger LOG = Logger.getLogger("QUICraft");
    private static final int MAX_LIMIT = 10_000_000;

    // System properties are read once, so a bad value logs one WARNING per JVM.
    // Node-wide cap: lets a 50,000-player node refill in ~25 s if it has the CPU for it. One event loop
    // completes ~900-1000 handshakes/s (docs/benchmarks.md), so that needs several UDP sockets.
    private static final int DEFAULT_GLOBAL_ATTEMPTS = limitProperty("globalAttemptsPerSecond", 2000);
    private static final int DEFAULT_PER_IP_ATTEMPTS = limitProperty("perIpAttemptsPerSecond", 20);
    private static final int DEFAULT_RETRY_THRESHOLD = limitProperty("retryThresholdPerSecond", 50);
    private static final int DEFAULT_MAX_OPEN = limitProperty("maxOpen", 65536);
    private static final int DEFAULT_MAX_OPEN_PER_IP = limitProperty("maxOpenPerIp", 32);
    private static final int DEFAULT_MAX_TRACKED_IPS = limitProperty("maxTrackedIps", 131072);
    // 4x the attempt budget above: above 50 attempts/s each attempt costs two Initials (Retry), plus
    // headroom for retransmitted Initials.
    private static final int DEFAULT_INGRESS_INITIALS = limitProperty("ingressInitialsPerSecond", 8000);
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
    final boolean multistream;
    final int globalAttemptsPerSecond;
    final int perIpAttemptsPerSecond;
    final int retryThresholdPerSecond;
    final int maxOpen;
    final int maxOpenPerIp;
    final int maxTrackedIps;
    final int ingressInitialsPerSecond;
    final int ingressInitialsPerIpPerSecond;
    final RetryMode retryMode;
    final long nodePendingBytes;
    final ReceiveBudget nodeBudget;
    final ResourcePools resourcePools;
    final boolean preLoginOnline;

    private TransportConfig(Builder b) {
        this.maxIdleTimeoutMillis = b.maxIdleTimeoutMillis;
        this.initialMaxData = b.initialMaxData;
        this.initialMaxStreamData = b.initialMaxStreamData;
        this.congestionControl = b.congestionControl;
        this.initialCongestionWindowPackets = b.initialCongestionWindowPackets;
        this.earlyData = b.earlyData;
        this.sessionResumption = b.sessionResumption;
        this.relaxedLossThreshold = b.relaxedLossThreshold;
        this.multistream = b.multistream;
        this.globalAttemptsPerSecond = positiveOr(b.globalAttemptsPerSecond, DEFAULT_GLOBAL_ATTEMPTS);
        this.perIpAttemptsPerSecond = positiveOr(b.perIpAttemptsPerSecond, DEFAULT_PER_IP_ATTEMPTS);
        // ConnectionAdmission rejects a Retry threshold above the global budget.
        this.retryThresholdPerSecond = Math.min(positiveOr(b.retryThresholdPerSecond, DEFAULT_RETRY_THRESHOLD),
                globalAttemptsPerSecond);
        this.maxOpen = positiveOr(b.maxOpen, DEFAULT_MAX_OPEN);
        this.maxOpenPerIp = positiveOr(b.maxOpenPerIp, DEFAULT_MAX_OPEN_PER_IP);
        this.maxTrackedIps = positiveOr(b.maxTrackedIps, DEFAULT_MAX_TRACKED_IPS);
        if (b.serverProfile != null) {
            // 12.13: maxOpen covers the logged-in target plus the pre-login cap; refused at build time.
            b.serverProfile.requireOpen(this.maxOpen);
        }
        this.ingressInitialsPerSecond = positiveOr(b.ingressInitialsPerSecond, DEFAULT_INGRESS_INITIALS);
        this.ingressInitialsPerIpPerSecond = positiveOr(b.ingressInitialsPerIpPerSecond,
                DEFAULT_INGRESS_INITIALS_PER_IP);
        this.retryMode = b.retryMode != null ? b.retryMode : DEFAULT_RETRY_MODE;
        this.nodePendingBytes = Math.max(0L, b.nodePendingBytes);
        this.nodeBudget = b.nodeBudget;
        this.resourcePools = b.resourcePools;
        this.preLoginOnline = b.preLoginOnline;
    }

    private static int positiveOr(int value, int fallback) {
        return value >= 1 && value <= MAX_LIMIT ? value : fallback;
    }

    /** Every value of {@code other}, with {@code multistream} chosen by the caller. */
    private TransportConfig(TransportConfig other, boolean multistream) {
        this.maxIdleTimeoutMillis = other.maxIdleTimeoutMillis;
        this.initialMaxData = other.initialMaxData;
        this.initialMaxStreamData = other.initialMaxStreamData;
        this.congestionControl = other.congestionControl;
        this.initialCongestionWindowPackets = other.initialCongestionWindowPackets;
        this.earlyData = other.earlyData;
        this.sessionResumption = other.sessionResumption;
        this.relaxedLossThreshold = other.relaxedLossThreshold;
        this.multistream = multistream;
        this.globalAttemptsPerSecond = other.globalAttemptsPerSecond;
        this.perIpAttemptsPerSecond = other.perIpAttemptsPerSecond;
        this.retryThresholdPerSecond = other.retryThresholdPerSecond;
        this.maxOpen = other.maxOpen;
        this.maxOpenPerIp = other.maxOpenPerIp;
        this.maxTrackedIps = other.maxTrackedIps;
        this.ingressInitialsPerSecond = other.ingressInitialsPerSecond;
        this.ingressInitialsPerIpPerSecond = other.ingressInitialsPerIpPerSecond;
        this.retryMode = other.retryMode;
        this.nodePendingBytes = other.nodePendingBytes;
        this.nodeBudget = other.nodeBudget;
        this.resourcePools = other.resourcePools;
        this.preLoginOnline = other.preLoginOnline;
    }

    /**
     * A copy of this configuration with the {@code /3} limits of 13.13 in place
     * ({@link #isMultistream()} true). Every other value is the caller's, unchanged: the admission
     * budgets ({@code maxOpen}, {@code maxOpenPerIp}, {@code maxTrackedIps}, the attempt, Retry and
     * ingress budgets, the Retry mode), the idle timeout, congestion control, initial congestion
     * window, {@code earlyData} and {@code sessionResumption}.
     *
     * <p>The {@code /3} entry points (the listener binds and the client dial) use this, never
     * {@link #multistream()}, so enabling {@code /3} cannot silently discard what the caller
     * configured. {@link Codecs#apply} still refuses a multistream config on a native without the
     * window caps, and the {@code /3} entry points check {@link
     * rs.sudoe.quicraft.core.QuicSupport#hasMaxFlowControlWindows()} first (12.3, 13.13).
     */
    TransportConfig withMultistream() {
        return new TransportConfig(this, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The /3 transport parameters of docs/protocol.md 13.13 on top of the /2 defaults. Affects the
     * flow-control limits and stream counts only; the /2 default is unchanged.
     */
    public static TransportConfig multistream() {
        if (!rs.sudoe.quicraft.core.QuicSupport.hasMaxFlowControlWindows()) {
            throw new IllegalStateException("/3 needs the patched native that caps flow-control windows");
        }
        return builder().multistream(true).build();
    }

    /** True for the /3 limits of {@link #multistream()}. */
    public boolean isMultistream() {
        return multistream;
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

    /** The injected node budget, or null. */
    public ReceiveBudget nodeBudget() {
        return nodeBudget;
    }

    /** The {@code /3} node-wide pending-bytes budget, 0 for none; see {@link Builder#nodePendingBytes(long)}. */
    public long nodePendingBytes() {
        return nodePendingBytes;
    }

    /**
     * The receive credit a server grants an admitted {@code /3} connection before anything arrives
     * (13.13: {@code initial_max_data} = three pinned 8 KiB stream windows, 24 KiB), which 13.12 reserves
     * against the node budget.
     */
    public static final long V3_INITIAL_EXPOSURE_BYTES = MULTISTREAM_SERVERBOUND_INITIAL_MAX_DATA;

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
        private boolean multistream;
        private int globalAttemptsPerSecond = DEFAULT_GLOBAL_ATTEMPTS;
        private int perIpAttemptsPerSecond = DEFAULT_PER_IP_ATTEMPTS;
        private int retryThresholdPerSecond = DEFAULT_RETRY_THRESHOLD;
        private int maxOpen = DEFAULT_MAX_OPEN;
        private int maxOpenPerIp = DEFAULT_MAX_OPEN_PER_IP;
        private int maxTrackedIps = DEFAULT_MAX_TRACKED_IPS;
        private int ingressInitialsPerSecond = DEFAULT_INGRESS_INITIALS;
        private int ingressInitialsPerIpPerSecond = DEFAULT_INGRESS_INITIALS_PER_IP;
        private RetryMode retryMode = DEFAULT_RETRY_MODE;
        private long nodePendingBytes;
        private ReceiveBudget nodeBudget;
        private ResourcePools resourcePools;
        private boolean preLoginOnline = true;
        private ServerProfile serverProfile;

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

        /**
         * /3 limits (13.13): initial_max_data 16 MiB at the client and 24 KiB at the server, bidi
         * per-stream limits of 8 KiB, 2 bidi streams at the server and 1 at the client, no uni
         * streams. Replaces initialMaxData and initialMaxStreamData.
         */
        public Builder multistream(boolean enabled) {
            this.multistream = enabled;
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

        /**
         * The node-wide budget, in bytes, for what {@code /3} connections hold in receive and unsend
         * queues (13.12). Each admitted {@code /3} connection reserves its initial receive-credit
         * exposure ({@link TransportConfig#V3_INITIAL_EXPOSURE_BYTES}) against it for as long as it is open, and a
         * connection that does not fit is refused, leaving TCP untouched. Zero (the default) means no
         * node budget, which is every {@code /2} listener's behaviour.
         */
        /**
         * The node's receive budget itself, shared with whatever else the application retains for these
         * connections (the platform's send-side queues): the listener uses this instance for admission
         * and for every lane session's reservations instead of building one from
         * {@link #nodePendingBytes(long)}.
         */
        public Builder nodeBudget(ReceiveBudget budget) {
            this.nodeBudget = budget;
            return this;
        }

        /** Shared staged and logged-in accounting for /3 (§12.13, §13.12). */
        public Builder resourcePools(ResourcePools pools) {
            this.resourcePools = pools;
            return this;
        }

        /**
         * The server profile this listener runs under. Build refuses a {@code maxOpen} that cannot cover
         * the profile's logged-in target plus its pre-login connection cap (12.13).
         */
        public Builder serverProfile(ServerProfile profile) {
            this.serverProfile = profile;
            return this;
        }

        /**
         * /3 (12.13): whether the server runs online mode, where Login Success promotes a staged connection.
         * Offline connections stay staged until activation. Default true.
         */
        public Builder preLoginOnline(boolean online) {
            this.preLoginOnline = online;
            return this;
        }

        public Builder nodePendingBytes(long value) {
            this.nodePendingBytes = value;
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
