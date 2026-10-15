// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
import rs.sudoe.quicraft.core.pin.PinExpiry;
import rs.sudoe.quicraft.core.pin.PinKey;
import rs.sudoe.quicraft.core.pin.PinStore;
import rs.sudoe.quicraft.core.pin.RebindState;
import rs.sudoe.quicraft.core.routing.RoutingRegistry;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/**
 * The client's connect path (docs/protocol.md §4–§6), independent of the game: decide from the
 * setting, the advertisement cache and the failure cache; query the server's status first when
 * there is no fresh advertisement; race QUIC against TCP; record the outcome. The platform
 * supplies the status query, the QUIC attempt and its own TCP connection type {@code T}.
 *
 * <p>{@link #plan} is synchronous and cheap. When it says {@link Plan#VANILLA_TCP} the platform
 * connects exactly as vanilla and never calls {@link #connect}.
 */
public final class ClientConnector {
    private static final Logger LOG = Logger.getLogger("QUICraft");

    /** Head start H (docs/protocol.md §5). */
    public static final long HEAD_START_MILLIS = 250;
    /** Upper bound for the status query of a direct connect; then the player gets TCP. */
    public static final long STATUS_QUERY_TIMEOUT_MILLIS = 5_000;

    /** What the platform does with a connect request. */
    public enum Plan {
        /** Vanilla TCP, now, without QUICraft in the way. */
        VANILLA_TCP,
        /** Call {@link #connect}: QUIC may be used. */
        CONNECT
    }

    /** The platform's side of a connect. */
    public interface Platform<T> {
        /** A status query over TCP, as vanilla's server list does; the advertisement in it, if any. */
        CompletableFuture<Optional<Advertisement>> queryStatus(InetSocketAddress server);

        /**
         * A QUIC connection to {@code target}, trusted only with {@code fingerprint}. Pass
         * {@code early} on to {@link rs.sudoe.quicraft.core.transport.QuicClient}; it is null when
         * the join has no 0-RTT inputs.
         */
        CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target, Fingerprint fingerprint,
                EarlyFlight early);

        /**
         * A QUIC connection for a selected transport (13.1): {@code V2} delegates to
         * {@link #connectQuic(InetSocketAddress, Fingerprint, EarlyFlight)}, the unchanged shipped
         * dial, so a platform that only implements {@code /2} keeps working.
         *
         * <p>A {@code /3} dial must offer {@link Protocol#ALPN_V3} and, when
         * {@code bypassResumption} is true, must not resume a ticket, a token or a recorded flight
         * for that logical server: 12.16's changed-pin re-bind is always a full handshake, no early
         * data, while keeping the old material for later use.</p>
         *
         * <p>The default implementation refuses anything but {@code V2}. A platform that does not
         * implement {@code /3} never reaches that case, because {@code /3} is selected only when the
         * client's multistream flag is on and its exporter probe succeeded (12.1, 12.3), and a
         * throw here is treated as a failed QUIC attempt, which falls back to TCP under §5.</p>
         *
         * @throws UnsupportedOperationException when {@code alpn} is not {@code V2}
         */
        default CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target,
                Fingerprint fingerprint, EarlyFlight early, TransportSelection.Transport alpn,
                boolean bypassResumption) {
            if (alpn != TransportSelection.Transport.V2) {
                throw new UnsupportedOperationException(
                        "this platform does not offer the " + alpn + " transport");
            }
            return connectQuic(target, fingerprint, early);
        }

        /** The platform's TCP connection, without the game protocol on it yet. */
        ConnectionRace.Tcp<T> tcp();

        ScheduledExecutorService scheduler();
    }

    /**
     * The connect failed because TCP did (with QUIC failed or not tried as well): the server is
     * unreachable, as vanilla would have found. {@link #getCause()} is TCP's error. Any other
     * failure of {@link #connect} in {@code auto} mode is QUICraft's, and the platform should
     * connect as vanilla instead.
     */
    public static final class TcpConnectException extends IOException {
        private static final long serialVersionUID = 1L;

        TcpConnectException(Throwable cause) {
            super(String.valueOf(cause.getMessage()), cause);
        }
    }

    /** Exactly one of {@link #quic()} and {@link #tcp()} is non-null. */
    public static final class Outcome<T> {
        private final QuicByteStream quic;
        private final T tcp;
        private final FallbackReason fallback;
        private final V3Plan v3Plan;
        private final String fallbackMessage;

        Outcome(QuicByteStream quic, T tcp, FallbackReason fallback) {
            this(quic, tcp, fallback, null, null);
        }

        Outcome(QuicByteStream quic, T tcp, FallbackReason fallback, V3Plan v3Plan) {
            this(quic, tcp, fallback, v3Plan, null);
        }

        Outcome(QuicByteStream quic, T tcp, FallbackReason fallback, V3Plan v3Plan,
                String fallbackMessage) {
            this.quic = quic;
            this.tcp = tcp;
            this.fallback = fallback;
            this.v3Plan = v3Plan;
            this.fallbackMessage = fallbackMessage;
        }

        public QuicByteStream quic() {
            return quic;
        }

        public T tcp() {
            return tcp;
        }

        /**
         * Non-null when QUIC was tried and TCP won: the reason for the fallback report
         * (docs/protocol.md §6).
         */
        public FallbackReason fallback() {
            return fallback;
        }

        /**
         * The plan of a {@code /3} connection: its pin state, its re-bind attempt, F, R and the
         * bookkeeping of 12.15 and 12.16 (docs/protocol.md 12.3, 13.1). Only the {@code connect}
         * overload that takes the logical server name produces one.
         *
         * <p>{@link V3Plan#activated()} and {@link V3Plan#failed(boolean, String)} report the end of
         * the attempt, so the adapter calls one of them once this outcome's QUIC stream has either
         * finished its binding exchange or failed.</p>
         *
         * <p>Non-null exactly when this outcome carries a {@code /3} QUIC stream, so a caller that
         * got TCP instead has nothing to report and nothing to bind: the {@code /3} attempt either
         * never happened (the selection chose TCP) or already ended in this same attempt, with the
         * connector recording the failure cache entry and the re-bind backoff (12.16) and the
         * player-facing message in {@link #fallbackMessage()}.</p>
         */
        public V3Plan v3Plan() {
            return v3Plan;
        }

        /**
         * What to log and to show the player about a {@code /3} attempt that failed, or null when
         * nothing failed (the user's "fall back to TCP at once, and tell the player" decision).
         *
         * <p>Two sources, one message: a failure the connector itself handled before the adapter
         * could drive the exchange, such as a QUIC handshake that timed out or an ALPN failure,
         * carries it here; a failure the adapter reported through
         * {@link V3Plan#failed(boolean, String)} carries it on the plan.</p>
         */
        public String fallbackMessage() {
            if (fallbackMessage != null) {
                return fallbackMessage;
            }
            return v3Plan == null ? null : v3Plan.fallbackMessage();
        }
    }

    private final AdvertisementCache advertisements;
    private final FailureCache failures;
    private final BooleanSupplier quicAvailable;
    private final long statusQueryTimeoutMillis;
    private final V3Support v3;

    public ClientConnector(AdvertisementCache advertisements, FailureCache failures) {
        this(advertisements, failures, QuicSupport::isAvailable, STATUS_QUERY_TIMEOUT_MILLIS, null);
    }

    /**
     * A connector that can select {@code quicraft/3} (docs/protocol.md 12.3, 13.1, 12.15, 12.16).
     *
     * @param support the client's {@code /3} inputs, or null to never select {@code /3}, which is
     *     the shipped behaviour and leaves every {@code connect} call on the {@code /2} path
     */
    public ClientConnector(AdvertisementCache advertisements, FailureCache failures, V3Support support) {
        this(advertisements, failures, QuicSupport::isAvailable, STATUS_QUERY_TIMEOUT_MILLIS, support);
    }

    ClientConnector(AdvertisementCache advertisements, FailureCache failures, BooleanSupplier quicAvailable,
            long statusQueryTimeoutMillis) {
        this(advertisements, failures, quicAvailable, statusQueryTimeoutMillis, null);
    }

    ClientConnector(AdvertisementCache advertisements, FailureCache failures, BooleanSupplier quicAvailable,
            long statusQueryTimeoutMillis, V3Support support) {
        this.advertisements = advertisements;
        this.failures = failures;
        this.quicAvailable = quicAvailable;
        this.statusQueryTimeoutMillis = statusQueryTimeoutMillis;
        this.v3 = support;
    }

    /**
     * The client's {@code /3} inputs that do not change between connections: the authenticated pins
     * and the changed-pin re-bind state (both loaded by the caller), the routing table lookup's
     * protocol number, the disabled-by-default multistream flag (12.1), the TLS exporter capability
     * probe result (12.3) and the worker executor every pin, re-bind and routing lookup runs on.
     *
     * <p>Passing no {@code V3Support} to the connector means {@code /3} is never selected, which is
     * the shipped behaviour: the {@code /2} path is untouched (12.1).</p>
     *
     * <p>The clock must be the one the pin store and the re-bind state were loaded with: both key
     * their windows and timestamps with {@code System.currentTimeMillis}, which is the clock
     * {@link PinStore#load(java.nio.file.Path)} and {@link RebindState#load(java.nio.file.Path)} use
     * by default.</p>
     */
    public static final class V3Support {
        private final PinStore pins;
        private final RebindState rebinds;
        private final Executor worker;
        private final LongSupplier clock;
        private final int protocolNumber;
        private final boolean multistreamEnabled;
        private final boolean exporterCapable;

        /**
         * @param pins the client's authenticated pins (12.15); required
         * @param rebinds the client's changed-pin re-bind state (12.16); required
         * @param worker the executor every pin, re-bind and routing lookup runs on, never the
         *     caller's event loop. It is wrapped so its tasks run one at a time, in submission
         *     order ({@link SerialExecutor}), because those calls read and write the same state
         *     files
         * @param clock the clock the pin store and the re-bind state were loaded with
         * @param protocolNumber the client's Minecraft protocol number, used to look up the local
         *     routing table and its digest (13.1, 13.7)
         * @param multistreamEnabled whether the disabled-by-default multistream flag is on (12.1)
         * @param exporterCapable whether the TLS exporter capability probe succeeded (12.2, 12.3)
         */
        public V3Support(PinStore pins, RebindState rebinds, Executor worker, LongSupplier clock,
                int protocolNumber, boolean multistreamEnabled, boolean exporterCapable) {
            if (pins == null || rebinds == null || worker == null || clock == null) {
                throw new IllegalArgumentException("pins, rebinds, worker and clock are all required");
            }
            if (protocolNumber < 0) {
                throw new IllegalArgumentException("protocol number must be in unsigned 32-bit range, got "
                        + protocolNumber);
            }
            this.pins = pins;
            this.rebinds = rebinds;
            this.worker = new SerialExecutor(worker);
            this.clock = clock;
            this.protocolNumber = protocolNumber;
            this.multistreamEnabled = multistreamEnabled;
            this.exporterCapable = exporterCapable;
        }

        public PinStore pins() {
            return pins;
        }

        public RebindState rebinds() {
            return rebinds;
        }

        public Executor worker() {
            return worker;
        }

        public LongSupplier clock() {
            return clock;
        }

        public int protocolNumber() {
            return protocolNumber;
        }

        public boolean multistreamEnabled() {
            return multistreamEnabled;
        }

        public boolean exporterCapable() {
            return exporterCapable;
        }
    }

    /**
     * An executor that runs its tasks one at a time, in submission order, whatever the delegate does.
     * The {@code /3} plan's state writes and the pre-connect step read and write the same pin,
     * re-bind and failure files, so their calls must not interleave: the connector serializes them
     * here rather than trusting every caller to hand in a single-threaded executor.
     *
     * <p>A delegate that refuses the task is reported to the caller as before, after the queue is
     * dropped: every caller of this executor already treats a rejection as "the work did not happen"
     * and carries on with TCP.</p>
     */
    private static final class SerialExecutor implements Executor {
        private final Executor delegate;
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        private boolean running;

        SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            boolean start;
            synchronized (this) {
                queue.add(command);
                start = !running;
                if (start) {
                    running = true;
                }
            }
            if (!start) {
                return;
            }
            try {
                delegate.execute(new Runnable() {
                    @Override
                    public void run() {
                        drain();
                    }
                });
            } catch (Throwable t) {
                synchronized (this) {
                    running = false;
                    queue.clear();
                }
                throw t;
            }
        }

        private void drain() {
            while (true) {
                Runnable task;
                synchronized (this) {
                    task = queue.poll();
                    if (task == null) {
                        running = false;
                        return;
                    }
                }
                try {
                    task.run();
                } catch (Throwable ignored) {
                    // Every caller of this executor documents that its task never throws; one that
                    // does must not stop the queue or lose the tasks behind it.
                }
            }
        }
    }

    public AdvertisementCache advertisements() {
        return advertisements;
    }

    /** Lets QUIC be tried again everywhere (the player's "forget QUIC failures"). */
    public void forgetFailures() {
        failures.clear();
    }

    /**
     * Whether QUIC could play any part in connecting to {@code server} (its resolved TCP
     * address). Vanilla TCP when the player chose TCP, QUIC can't run here, or a fresh
     * advertisement exists but the failure cache is backing off from it.
     */
    public Plan plan(InetSocketAddress server, ConnectDecision.Mode mode) {
        if (mode == ConnectDecision.Mode.QUIC_ONLY) {
            return Plan.CONNECT;
        }
        if (mode == ConnectDecision.Mode.TCP_ONLY || !quicAvailable.getAsBoolean()) {
            return Plan.VANILLA_TCP;
        }
        Optional<Advertisement> ad = advertisements.fresh(key(server));
        if (ad.isPresent() && !allows(server, ad.get())) {
            return Plan.VANILLA_TCP;
        }
        return Plan.CONNECT;
    }

    /**
     * Connects to {@code server} (its resolved TCP address). Fails with
     * {@link TcpConnectException} when TCP fails (QUIC too, if tried); in {@code quic-only} mode,
     * with QUIC's error. Cancelling the result
     * abandons the attempt and closes whatever connects afterwards.
     */
    public <T> CompletableFuture<Outcome<T>> connect(InetSocketAddress server, ConnectDecision.Mode mode,
            Platform<T> platform) {
        return connect(server, mode, null, platform);
    }

    /**
     * As {@link #connect(InetSocketAddress, ConnectDecision.Mode, Platform)}, with the join's 0-RTT
     * inputs (docs/protocol.md §8; see {@link EarlyFlight}), or null for none.
     */
    public <T> CompletableFuture<Outcome<T>> connect(InetSocketAddress server, ConnectDecision.Mode mode,
            String joinInputs, Platform<T> platform) {
        CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
        InetSocketAddress key = key(server);
        Optional<Advertisement> cached = advertisements.fresh(key);
        CompletableFuture<Optional<Advertisement>> advertisement;
        if (cached.isPresent() || mode == ConnectDecision.Mode.TCP_ONLY || !quicAvailable.getAsBoolean()) {
            advertisement = CompletableFuture.completedFuture(cached);
        } else {
            advertisement = queryStatus(key, platform);
        }
        result.whenComplete((o, e) -> {
            if (result.isCancelled()) {
                advertisement.cancel(false);
            }
        });
        advertisement.whenComplete((ad, error) -> {
            if (result.isDone()) {
                return;
            }
            Optional<Advertisement> found = error == null ? ad : Optional.<Advertisement>empty();
            try {
                decided(server, mode, found, joinInputs, platform, result);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    /**
     * As {@link #connect(InetSocketAddress, ConnectDecision.Mode, String, Platform)}, but with the
     * logical server the player asked for, so the pre-connect {@code /3} selection of 12.3, 13.1 and
     * 12.16 can run and the outcome can carry a {@link V3Plan}.
     *
     * <p>This overload only exists for a connector built with {@link V3Support}. Without one it
     * delegates to the shipped path unchanged, so the {@code /2} behaviour is untouched (12.1).</p>
     *
     * <p>{@code logicalHost} and {@code logicalPort} are the name the player typed and the logical
     * TCP port, never the resolved address or an SRV target: 12.15 keys pins by exactly those, and
     * 12.16 keys the re-bind backoff the same way. Every pin, re-bind and routing lookup runs on the
     * {@link V3Support} worker, never on the caller's event loop.</p>
     *
     * <p>Failure is never fatal to the join: a missing {@code /3} prerequisite, a refused re-bind
     * window, a corrupt pin store, a full pin store or a fault in the pre-connect step all end in
     * {@code /2} or TCP in this same attempt (12.3, 12.16, 13.1).</p>
     */
    public <T> CompletableFuture<Outcome<T>> connect(InetSocketAddress server, String logicalHost,
            int logicalPort, ConnectDecision.Mode mode, String joinInputs, Platform<T> platform) {
        if (v3 == null || !quicAvailable.getAsBoolean()) {
            return connect(server, mode, joinInputs, platform);
        }
        CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
        InetSocketAddress key = key(server);
        Optional<Advertisement> cached = advertisements.fresh(key);
        CompletableFuture<Optional<Advertisement>> advertisement;
        if (cached.isPresent() || mode == ConnectDecision.Mode.TCP_ONLY) {
            advertisement = CompletableFuture.completedFuture(cached);
        } else {
            advertisement = queryStatus(key, platform);
        }
        result.whenComplete((o, e) -> {
            if (result.isCancelled()) {
                advertisement.cancel(false);
            }
        });
        advertisement.whenComplete((ad, error) -> {
            if (result.isDone()) {
                return;
            }
            Optional<Advertisement> found = error == null ? ad : Optional.<Advertisement>empty();
            prepare(server, logicalHost, logicalPort, mode, found, joinInputs, platform, result);
        });
        return result;
    }

    /**
     * Runs the pre-connect {@code /3} step on the worker and hands the result to {@link #proceed}.
     * A step that throws, whether from {@link Faults} or from a pin, re-bind or routing lookup, is
     * not fatal: the shipped decision runs instead, which is {@code /2} or TCP in this same attempt.
     */
    private <T> void prepare(final InetSocketAddress server, final String logicalHost, final int logicalPort,
            final ConnectDecision.Mode mode, final Optional<Advertisement> advertisement,
            final String joinInputs, final Platform<T> platform, final CompletableFuture<Outcome<T>> result) {
        CompletableFuture<Prepared> prepared;
        try {
            prepared = CompletableFuture.supplyAsync(new Supplier<Prepared>() {
                @Override
                public Prepared get() {
                    return prepare0(server, logicalHost, logicalPort, mode, advertisement);
                }
            }, v3.worker());
        } catch (Throwable t) {
            prepared = new CompletableFuture<Prepared>();
            prepared.completeExceptionally(t);
        }
        final CompletableFuture<Prepared> choice = prepared;
        result.whenComplete((o, e) -> {
            if (result.isCancelled()) {
                choice.cancel(false);
            }
        });
        choice.whenComplete((chosen, error) -> {
            if (result.isDone()) {
                return;
            }
            if (error != null) {
                LOG.log(Level.FINE, "the /3 pre-connect step failed; using the shipped decision",
                        unwrap(error));
                try {
                    fallbackAfterPreconnectFailure(server, mode, advertisement, joinInputs, platform,
                            result);
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
                return;
            }
            try {
                proceed(server, logicalHost, logicalPort, mode, advertisement, chosen, joinInputs,
                        platform, result);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
    }

    /** The worker half of {@link #prepare}: every {@code /3} input is read here, and nowhere else. */
    private Prepared prepare0(InetSocketAddress server, String logicalHost, int logicalPort,
            ConnectDecision.Mode mode, Optional<Advertisement> advertisement) {
        long now = v3.clock().getAsLong();
        // 12.15: expire the pins past their 30 days before anything reads or writes them, so an
        // expired pin is first contact again. This is the worker, and the purge writes both files.
        Set<PinKey> activeKeys = v3.rebinds().activeKeys();
        PinExpiry.purge(v3.pins(), v3.rebinds(), activeKeys);
        TransportSelection.PinState pinState = pinState(logicalHost, logicalPort, advertisement,
                activeKeys);
        InetSocketAddress target = advertisement.isPresent() ? quicTarget(server, advertisement.get()) : null;
        boolean activationBlocked = advertisement.isPresent()
                && Protocol.ALPN_V3.equals(advertisement.get().alpn())
                && failures.isActivationBlocked(target.getAddress(), target.getPort(),
                        advertisement.get().fingerprint(), now);
        RebindState.Attempt attempt = null;
        if (pinState == TransportSelection.PinState.CHANGED && !activationBlocked) {
            // 12.16: one automatic re-bind attempt per persisted window, keyed by the logical server
            // alone, so a rotating fingerprint cannot reset it. Null covers the active window, a
            // state that cannot be trusted and a state that cannot hold another server.
            attempt = v3.rebinds().tryBeginAttempt(logicalHost, logicalPort, now);
        }
        boolean kept = false;
        try {
            // The selection step of 12.3, 13.1 and 12.16. It is checked after the pin and re-bind
            // reads, so a fault here models a failure with an attempt already admitted, which is the
            // case that must release it.
            Faults.check(Faults.V3_SELECT);
            Optional<RoutingTable> table = routingTable(advertisement);
            TransportSelection.Result selection = TransportSelection.select(TransportSelection.Inputs.builder()
                    .setting(mode)
                    .multistreamEnabled(v3.multistreamEnabled())
                    .exporterCapable(v3.exporterCapable())
                    .advertisement(advertisement)
                    .pinState(pinState)
                    .rebindAllowed(attempt != null)
                    .clientProtocolNumber(v3.protocolNumber())
                    .localRoutingDigestHex(table.isPresent() ? Optional.of(table.get().digestHex())
                            : Optional.<String>empty())
                    .build());
            if (attempt != null && advertisement.isPresent()
                    && selection.transport() != TransportSelection.Transport.V3
                    && prerequisiteMissing(selection.missing())) {
                // 12.16's pre-connect precedence: a changed pin whose /3 prerequisites are missing
                // leaves the pin alone, records §6's entry and the re-bind backoff, and joins over
                // fresh vanilla TCP. It never selects /2 and never sends 0x10, because no connection
                // exists.
                failures.recordFailure(target.getAddress(), target.getPort(),
                        advertisement.get().fingerprint());
                v3.rebinds().endAttempt(attempt, false);
                attempt = null;
            }
            String tcpReason = null;
            if (activationBlocked) {
                tcpReason = "the /3 activation failure for this key is still backing off (12.14)";
            } else if (pinStoreFullForNewName(pinState, logicalHost, logicalPort, advertisement,
                    activeKeys)) {
                // 12.16, spec-literal: "Never automatically evict pins: when the pin store is full,
                // use TCP for new names until the player explicitly deletes a pin." So a new name
                // with a full store is TCP, not /2, even though /2 itself needs no pin.
                tcpReason = "the pin store is full and has no pin for this name (12.16)";
            } else if (handshakeBackoff(server, advertisement, mode)) {
                // §6: a UDP-blocked or frontend-proxied /3 server costs at most one head start per
                // backoff window, exactly as the shipped decision treats a /2 server.
                tcpReason = "the section 6 backoff for this address, port and fingerprint is still active";
            }
            kept = true;
            return new Prepared(selection, pinState, attempt, tcpReason,
                    table.isPresent() ? table.get().digest() : null);
        } finally {
            if (attempt != null && !kept) {
                // A pre-connect failure must not leave the attempt holding the server's re-bind
                // state: the caller falls back to TCP, and the state would otherwise stay active for
                // the whole attempt's lifetime.
                try {
                    v3.rebinds().endAttempt(attempt, false);
                } catch (Throwable ignored) {
                    // Best effort: the state disables itself on a failed write.
                }
            }
        }
    }

    /**
     * Whether §6's failure cache is still backing off from this exact address, QUIC port and
     * fingerprint, which makes the {@code /3} attempt use TCP without spending a head start.
     *
     * <p>{@code quic-only} is the debug setting that ignores the cache, exactly as the shipped
     * decision does (§4), so the check does not apply there.</p>
     */
    private boolean handshakeBackoff(InetSocketAddress server, Optional<Advertisement> advertisement,
            ConnectDecision.Mode mode) {
        if (mode == ConnectDecision.Mode.QUIC_ONLY || !advertisement.isPresent()
                || !Protocol.ALPN_V3.equals(advertisement.get().alpn())) {
            return false;
        }
        InetSocketAddress target = quicTarget(server, advertisement.get());
        return !failures.allows(target.getAddress(), target.getPort(),
                advertisement.get().fingerprint());
    }

    /**
     * Whether this connection would need a pin the store has no room for: a name it does not know,
     * in a store already holding {@link PinStore#MAX_PINS} pins, for a listener that offers
     * {@code /3}. A {@code /2} listener never involves a pin, so it is not restricted here.
     *
     * <p>This is the one open question this unit implements spec-literally: 12.16 says a full store
     * means TCP for new names, which overrides {@code /2} selection for such a name.</p>
     */
    private boolean pinStoreFullForNewName(TransportSelection.PinState pinState, String logicalHost,
            int logicalPort, Optional<Advertisement> advertisement, Set<PinKey> activeKeys) {
        if (pinState != TransportSelection.PinState.NONE || !advertisement.isPresent()
                || !Protocol.ALPN_V3.equals(advertisement.get().alpn())) {
            return false;
        }
        if (v3.pins().pin(logicalHost, logicalPort, activeKeys).isPresent()) {
            return false;
        }
        return v3.pins().size() >= PinStore.MAX_PINS;
    }

    /**
     * The pin state of 12.15 for this logical server: the advertised fingerprint against the stored
     * pin. A store that cannot be trusted is {@link TransportSelection.PinState#STORE_UNUSABLE},
     * which disables {@code /3} and is what the caller tells the player about; with no advertisement
     * there is nothing to compare, so the state is {@link TransportSelection.PinState#NONE}.
     */
    private TransportSelection.PinState pinState(String logicalHost, int logicalPort,
            Optional<Advertisement> advertisement, Set<PinKey> activeKeys) {
        if (!v3.pins().isUsable()) {
            return TransportSelection.PinState.STORE_UNUSABLE;
        }
        if (!advertisement.isPresent()) {
            return TransportSelection.PinState.NONE;
        }
        Optional<PinStore.Pin> pin = v3.pins().pin(logicalHost, logicalPort, activeKeys);
        if (!pin.isPresent()) {
            return TransportSelection.PinState.NONE;
        }
        return pin.get().fingerprint().equals(advertisement.get().fingerprint().toString())
                ? TransportSelection.PinState.SAME
                : TransportSelection.PinState.CHANGED;
    }

    /**
     * What to do when the pre-connect {@code /3} step itself threw, which the user's fail-safe
     * decision counts as a {@code /3} failure: fall back to TCP at once and tell the player.
     *
     * <p>A {@code quicraft/2} advertisement, or none at all, keeps the shipped decision, which is
     * {@code /2} or TCP. A {@code quicraft/3} advertisement goes straight to TCP, because a
     * {@code /3} listener speaks {@code /3} only and must never be offered {@code /2} (13.1), with
     * {@code quic-only} still failing as §4 requires.</p>
     */
    private <T> void fallbackAfterPreconnectFailure(InetSocketAddress server, ConnectDecision.Mode mode,
            Optional<Advertisement> advertisement, String joinInputs, Platform<T> platform,
            CompletableFuture<Outcome<T>> result) {
        if (!advertisement.isPresent() || Protocol.ALPN.equals(advertisement.get().alpn())) {
            decided(server, mode, advertisement, joinInputs, platform, result);
            return;
        }
        if (mode == ConnectDecision.Mode.QUIC_ONLY) {
            result.completeExceptionally(new IOException(
                    "QUIC only: the /3 pre-connect step failed and there is no TCP fallback"));
            return;
        }
        tcpOnly(platform, result, V3Plan.fallbackMessage(V3Plan.INTERNAL_ERROR));
    }

    /**
     * A short, human-readable cause for the player, from a failed QUIC attempt's classified reason.
     * It never carries a certificate, an address or any other detail a player cannot use.
     */
    private static String fallbackReason(FallbackReason reason) {
        switch (reason) {
            case TIMEOUT_OR_LOST_RACE:
                return V3Plan.HANDSHAKE_TIMED_OUT;
            case FINGERPRINT_MISMATCH:
                return "server certificate did not match the advertisement";
            case UDP_UNREACHABLE:
                return "UDP is unreachable";
            case HANDSHAKE_FAILURE:
            case OTHER:
            default:
                return "handshake failed";
        }
    }

    /**
     * The client's routing table for its protocol number, when the advertisement is for
     * {@code /3} (13.1, 13.7). Never guessed: no table means no {@code /3}.
     */
    private Optional<RoutingTable> routingTable(Optional<Advertisement> advertisement) {
        if (!advertisement.isPresent() || !Protocol.ALPN_V3.equals(advertisement.get().alpn())) {
            return Optional.empty();
        }
        return RoutingRegistry.forProtocol(v3.protocolNumber());
    }

    /**
     * Whether the selection's blocker is a 12.3 or 13.1 prerequisite that a changed pin treats as
     * 12.16's pre-connect case. The player's own setting is not one of them: choosing {@code tcp-only}
     * is not a missing prerequisite.
     */
    private static boolean prerequisiteMissing(TransportSelection.Missing missing) {
        switch (missing) {
            case ONLY_V2_ADVERTISED:
            case MULTISTREAM_FLAG_OFF:
            case NO_EXPORTER:
            case NO_LOCAL_ROUTING_TABLE:
            case NO_ADVERTISED_DIGEST:
            case DIGEST_MISMATCH:
                return true;
            default:
                return false;
        }
    }

    /** Acts on the prepared {@code /3} decision. */
    private <T> void proceed(InetSocketAddress server, String logicalHost, int logicalPort,
            ConnectDecision.Mode mode, Optional<Advertisement> advertisement, Prepared prepared,
            String joinInputs, Platform<T> platform, CompletableFuture<Outcome<T>> result) {
        EarlyFlight early = joinInputs == null ? null : new EarlyFlight(joinInputs);
        if (prepared.tcpReason != null) {
            endRebindAttemptOnWorker(prepared.attempt);
            if (mode == ConnectDecision.Mode.QUIC_ONLY) {
                // §4: quic-only never downgrades silently, so the blocker is the failure. The
                // shipped path treats a TCP decision the same way.
                result.completeExceptionally(new IOException("QUIC only: " + prepared.tcpReason
                        + ", and quic-only has no TCP fallback (protocol §4)"));
                return;
            }
            // 12.14 and 12.16: this attempt uses TCP. The reason names the rule, never a secret.
            LOG.log(Level.FINE, "QUICraft /3 uses TCP here: {0}", prepared.tcpReason);
            tcpOnly(platform, result);
            return;
        }
        TransportSelection.Transport transport = prepared.selection.transport();
        if (transport == TransportSelection.Transport.FAIL) {
            endRebindAttemptOnWorker(prepared.attempt);
            result.completeExceptionally(new IOException(prepared.selection.reason()));
            return;
        }
        if (transport == TransportSelection.Transport.TCP || !advertisement.isPresent()) {
            endRebindAttemptOnWorker(prepared.attempt);
            tcpOnly(platform, result);
            return;
        }
        if (transport == TransportSelection.Transport.V2) {
            // The shipped decision, unchanged: racing, the failure cache and 0-RTT (12.1). A changed
            // pin never selects /2, so this ends an attempt only if one was somehow admitted.
            endRebindAttemptOnWorker(prepared.attempt);
            decided(server, mode, advertisement, joinInputs, platform, result);
            return;
        }
        if (mode == ConnectDecision.Mode.QUIC_ONLY) {
            // §4: quic-only means QUIC without a TCP fallback, for /3 exactly as for /2.
            v3QuicOnly(server, logicalHost, logicalPort, advertisement.get(), early, prepared, platform,
                    result);
            return;
        }
        v3Connect(server, logicalHost, logicalPort, advertisement.get(), early, prepared, platform, result);
    }

    /**
     * A {@code /3} dial with no TCP fallback, which is what {@code quic-only} asks for (§4): the
     * caller sees the QUIC error, exactly as {@link #quicOnly} reports a {@code /2} one, and TCP is
     * never opened. A changed pin still gets 12.16's full handshake, and the attempt it held is
     * ended whenever this dial does not hand a connection to the adapter.
     */
    private <T> void v3QuicOnly(final InetSocketAddress server, final String logicalHost,
            final int logicalPort, Advertisement ad, EarlyFlight early, final Prepared prepared,
            Platform<T> platform, CompletableFuture<Outcome<T>> result) {
        final InetSocketAddress target = quicTarget(server, ad);
        CompletableFuture<QuicByteStream> attempt = dial(target, ad, early,
                prepared.pinState == TransportSelection.PinState.CHANGED, platform);
        result.whenComplete((o, e) -> {
            if (result.isCancelled()) {
                attempt.cancel(false);
                endRebindAttemptOnWorker(prepared.attempt);
            }
        });
        attempt.whenComplete((stream, error) -> {
            if (error != null) {
                endRebindAttemptOnWorker(prepared.attempt);
                result.completeExceptionally(unwrap(error));
                return;
            }
            failures.recordSuccess(target.getAddress(), target.getPort(), ad.fingerprint());
            V3Plan plan = plan(logicalHost, logicalPort, ad, prepared, target);
            if (!result.complete(new Outcome<T>(stream, null, null, plan))) {
                endRebindAttemptOnWorker(prepared.attempt);
                stream.close();
            }
        });
    }

    /**
     * Connects with the {@code quicraft/3} ALPN. Both pin states race TCP under §5, so the player
     * never waits for a full QUIC timeout: an ordinary pin exactly as {@code /2} does (13.1), and a
     * changed pin's one automatic re-bind (12.16) with a full handshake and no early data. If the
     * {@code /3} handshake wins and the binding later fails, the adapter closes {@code 0x10} and
     * starts a fresh TCP join through {@link V3Plan#failed(V3Plan.Phase, String)}.
     */
    private <T> void v3Connect(final InetSocketAddress server, final String logicalHost, final int logicalPort,
            Advertisement ad, EarlyFlight early, final Prepared prepared, Platform<T> platform,
            CompletableFuture<Outcome<T>> result) {
        InetSocketAddress target = quicTarget(server, ad);
        race(server, ad, early, platform, result, planHolder(logicalHost, logicalPort, ad, prepared, target));
    }

    /**
     * One prepared {@code /3} decision: the selection, the pin state it was made from, the re-bind
     * attempt this connection holds (null when there is none), a non-null {@code tcpReason} when
     * this attempt must use TCP (12.14's activation backoff or 12.16's full pin store), and the
     * local routing digest R the binding exchange needs.
     */
    private static final class Prepared {
        final TransportSelection.Result selection;
        final TransportSelection.PinState pinState;
        final RebindState.Attempt attempt;
        final String tcpReason;
        final byte[] routingDigest;

        Prepared(TransportSelection.Result selection, TransportSelection.PinState pinState,
                RebindState.Attempt attempt, String tcpReason, byte[] routingDigest) {
            this.selection = selection;
            this.pinState = pinState;
            this.attempt = attempt;
            this.tcpReason = tcpReason;
            this.routingDigest = routingDigest;
        }
    }

    /** Everything {@link #plan} needs, so the race can build a plan in either of its branches. */
    private PlanInput planHolder(String logicalHost, int logicalPort, Advertisement ad, Prepared prepared,
            InetSocketAddress target) {
        return new PlanInput(logicalHost, logicalPort, ad, prepared, target,
                prepared.pinState == TransportSelection.PinState.CHANGED);
    }

    private static final class PlanInput {
        final String logicalHost;
        final int logicalPort;
        final Advertisement advertisement;
        final Prepared prepared;
        final InetSocketAddress target;
        /** 12.16: a changed-pin re-bind is a full handshake with no early data, always. */
        final boolean bypassResumption;

        PlanInput(String logicalHost, int logicalPort, Advertisement advertisement, Prepared prepared,
                InetSocketAddress target, boolean bypassResumption) {
            this.logicalHost = logicalHost;
            this.logicalPort = logicalPort;
            this.advertisement = advertisement;
            this.prepared = prepared;
            this.target = target;
            this.bypassResumption = bypassResumption;
        }
    }

    /** The plan of a raced {@code /3} outcome, or null for the shipped {@code /2} path. */
    private V3Plan plan(PlanInput plans) {
        return plans == null ? null
                : plan(plans.logicalHost, plans.logicalPort, plans.advertisement, plans.prepared,
                        plans.target);
    }

    private V3Plan plan(String logicalHost, int logicalPort, Advertisement ad, Prepared prepared,
            InetSocketAddress target) {
        return new V3Plan(prepared.pinState, prepared.attempt, ad.fingerprint(), prepared.routingDigest,
                v3.pins(), failures, v3.rebinds(), v3.worker(), target.getAddress(), target.getPort(),
                logicalHost, logicalPort);
    }

    /**
     * Ends the re-bind attempt this join held, when it held one: 12.16's window advances, so the same
     * server cannot re-bind again inside it. The failure cache entry is the caller's business, and
     * the race already records §6's own.
     *
     * <p>On the worker, never on the caller's thread, because
     * {@link RebindState#endAttempt} writes and fsyncs its state file. A worker that refuses the task
     * is swallowed on purpose: bookkeeping must never stop the join.</p>
     */
    private void endRebindAttemptOnWorker(final RebindState.Attempt attempt) {
        if (attempt == null) {
            return;
        }
        try {
            v3.worker().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        v3.rebinds().endAttempt(attempt, false);
                    } catch (Throwable ignored) {
                        // Best effort: the state disables itself on a failed write.
                    }
                }
            });
        } catch (Throwable ignored) {
            // A worker that is shutting down must not stop the join.
        }
    }

    /** The same, for the paths that carry a plan holder rather than an attempt. */
    private void endRebindAttemptOnWorker(PlanInput plans) {
        if (plans != null) {
            endRebindAttemptOnWorker(plans.prepared.attempt);
        }
    }

    /**
     * A {@code /3} dial: the 13.1 ALPN, and for a changed pin 12.16's full handshake with no early
     * data, bypassing (but keeping) that logical server's tickets, tokens and recorded flights.
     * A {@link Faults#V3_DIAL} fault or a platform that throws becomes a failed attempt, which the
     * race and the fallback already handle.
     */
    private static CompletableFuture<QuicByteStream> dial(InetSocketAddress target, Advertisement ad,
            EarlyFlight early, boolean bypassResumption, Platform<?> platform) {
        try {
            Faults.check(Faults.V3_DIAL);
            return platform.connectQuic(target, ad.fingerprint(), early,
                    TransportSelection.Transport.V3, bypassResumption);
        } catch (Throwable t) {
            CompletableFuture<QuicByteStream> failed = new CompletableFuture<QuicByteStream>();
            failed.completeExceptionally(t);
            return failed;
        }
    }

    private CompletableFuture<Optional<Advertisement>> queryStatus(InetSocketAddress key, Platform<?> platform) {
        CompletableFuture<Optional<Advertisement>> query;
        try {
            query = platform.queryStatus(key);
        } catch (Throwable t) {
            query = new CompletableFuture<>();
            query.completeExceptionally(t);
        }
        CompletableFuture<Optional<Advertisement>> bounded = new CompletableFuture<>();
        final CompletableFuture<Optional<Advertisement>> running = query;
        ScheduledFuture<?> timeout = platform.scheduler().schedule(() -> {
            running.cancel(false); // before the connect goes on without it
            bounded.completeExceptionally(new TimeoutException("status query"));
        }, statusQueryTimeoutMillis, TimeUnit.MILLISECONDS);
        query.whenComplete((ad, error) -> {
            timeout.cancel(false);
            if (error == null) {
                advertisements.record(key, ad);
                bounded.complete(ad);
            } else {
                LOG.log(Level.FINE, "status query failed; using TCP", error);
                bounded.completeExceptionally(error);
            }
        });
        bounded.whenComplete((ad, error) -> {
            if (error != null) {
                running.cancel(false);
            }
        });
        return bounded;
    }

    private <T> void decided(InetSocketAddress server, ConnectDecision.Mode mode, Optional<Advertisement> ad,
            String joinInputs, Platform<T> platform, CompletableFuture<Outcome<T>> result) {
        EarlyFlight early = joinInputs == null ? null : new EarlyFlight(joinInputs);
        boolean allowed = ad.isPresent() && allows(server, ad.get());
        ConnectDecision.Transport transport = ConnectDecision.decide(mode, quicAvailable.getAsBoolean(), ad, allowed);
        switch (transport) {
            case TCP:
                tcpOnly(platform, result);
                break;
            case QUIC_ONLY:
                quicOnly(server, ad, early, platform, result);
                break;
            case RACE:
            default:
                race(server, ad.get(), early, platform, result);
                break;
        }
    }

    private <T> void tcpOnly(Platform<T> platform, CompletableFuture<Outcome<T>> result) {
        tcpOnly(platform, result, null);
    }

    /**
     * As {@link #tcpOnly(Platform, CompletableFuture)}, carrying the player-facing message of a
     * {@code /3} fallback the connector handled itself (the user's fail-safe decision).
     */
    private <T> void tcpOnly(Platform<T> platform, CompletableFuture<Outcome<T>> result,
            String fallbackMessage) {
        ConnectionRace.Tcp<T> tcp = platform.tcp();
        CompletableFuture<T> attempt = tcp.connect();
        attempt.whenComplete((connection, error) -> {
            if (error != null) {
                result.completeExceptionally(new TcpConnectException(unwrap(error)));
            } else if (!result.complete(new Outcome<T>(null, connection, null, null, fallbackMessage))) {
                tcp.close(connection); // cancelled meanwhile
            }
        });
    }

    private <T> void quicOnly(InetSocketAddress server, Optional<Advertisement> ad, EarlyFlight early,
            Platform<T> platform, CompletableFuture<Outcome<T>> result) {
        if (!ad.isPresent()) {
            result.completeExceptionally(new IOException("QUIC only: the server does not advertise QUIC"));
            return;
        }
        if (!quicAvailable.getAsBoolean()) {
            result.completeExceptionally(new IOException("QUIC only: QUIC is unavailable on this platform",
                    QuicSupport.unavailabilityCause()));
            return;
        }
        CompletableFuture<QuicByteStream> attempt = platform.connectQuic(quicTarget(server, ad.get()),
                ad.get().fingerprint(), early);
        result.whenComplete((o, e) -> {
            if (result.isCancelled()) {
                attempt.cancel(false);
            }
        });
        attempt.whenComplete((stream, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrap(error));
            } else if (!result.complete(new Outcome<T>(stream, null, null))) {
                stream.close();
            }
        });
    }

    private <T> void race(InetSocketAddress server, Advertisement ad, EarlyFlight early, Platform<T> platform,
            CompletableFuture<Outcome<T>> result) {
        race(server, ad, early, platform, result, null);
    }

    /**
     * As {@link #race(InetSocketAddress, Advertisement, EarlyFlight, Platform, CompletableFuture)},
     * with an optional {@code /3} plan: when {@code plans} is non-null the QUIC side of the race is a
     * {@code quicraft/3} dial (13.1), the winning outcome carries its {@link V3Plan}, and a lost race
     * or an abandoned join ends the re-bind attempt that dial held (12.16). Everything else,
     * including the head start, the failure cache and the 0-RTT grace period, is unchanged.
     */
    private <T> void race(InetSocketAddress server, Advertisement ad, EarlyFlight early, Platform<T> platform,
            CompletableFuture<Outcome<T>> result, final PlanInput plans) {
        final InetSocketAddress target = quicTarget(server, ad);
        TimedTcp<T> tcp = new TimedTcp<>(platform.tcp());
        CompletableFuture<ConnectionRace.Result<T>> race = ConnectionRace.race(
                plans == null
                        ? () -> platform.connectQuic(target, ad.fingerprint(), early)
                        : () -> dial(target, ad, early, plans.bypassResumption, platform),
                tcp, HEAD_START_MILLIS, platform.scheduler());
        result.whenComplete((o, e) -> {
            if (result.isCancelled()) {
                race.cancel(false);
                // The player gave up: the server's re-bind state must not stay held by this attempt.
                endRebindAttemptOnWorker(plans);
            }
        });
        race.whenComplete((r, error) -> {
            if (error != null) {
                result.completeExceptionally(new TcpConnectException(unwrap(error)));
                return;
            }
            if (r.quicWon()) {
                // A handshake win clears §6's handshake entry only. An activation failure for this
                // key is a separate state that only a successful activation clears (12.14).
                failures.recordSuccess(target.getAddress(), target.getPort(), ad.fingerprint());
                if (!result.complete(new Outcome<T>(r.quic(), null, null, plan(plans)))) {
                    // The caller gave up between the race and here, so this connection is closed
                    // unused: a re-bind attempt it held is over too (12.16).
                    endRebindAttemptOnWorker(plans);
                    r.quic().close();
                }
                return;
            }
            failures.recordFailure(target.getAddress(), target.getPort(), ad.fingerprint());
            endRebindAttemptOnWorker(plans);
            Outcome<T> outcome = new Outcome<T>(null, r.tcp(), r.quicFailure(), null,
                    plans == null ? null : V3Plan.fallbackMessage(fallbackReason(r.quicFailure())));
            if (early != null && early.sent()) {
                // The server may have started a login on the 0-RTT flight. The lost QUIC attempt
                // sends CONNECTION_CLOSE; give it one TCP round trip to arrive before the TCP login
                // (docs/protocol.md §8, "TCP wins after 0-RTT went out").
                long grace = Math.max(MIN_EARLY_GRACE_MILLIS, Math.min(MAX_EARLY_GRACE_MILLIS, tcp.connectMillis()));
                platform.scheduler().schedule(() -> {
                    if (!result.complete(outcome)) {
                        tcp.close(r.tcp());
                    }
                }, grace, TimeUnit.MILLISECONDS);
            } else if (!result.complete(outcome)) {
                tcp.close(r.tcp());
            }
        });
    }

    /** Bounds of the wait after TCP beat a QUIC attempt that sent 0-RTT data. */
    static final long MIN_EARLY_GRACE_MILLIS = 20;
    static final long MAX_EARLY_GRACE_MILLIS = 250;

    /** The platform's TCP, timing its connect: a round-trip estimate. */
    private static final class TimedTcp<T> implements ConnectionRace.Tcp<T> {
        private final ConnectionRace.Tcp<T> tcp;
        private volatile long connectMillis = MAX_EARLY_GRACE_MILLIS;

        TimedTcp(ConnectionRace.Tcp<T> tcp) {
            this.tcp = tcp;
        }

        @Override
        public CompletableFuture<T> connect() {
            long start = System.nanoTime();
            CompletableFuture<T> attempt = tcp.connect();
            attempt.whenComplete((c, e) -> {
                if (e == null) {
                    connectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }
            });
            return attempt;
        }

        @Override
        public void close(T connection) {
            tcp.close(connection);
        }

        long connectMillis() {
            return connectMillis;
        }
    }

    private boolean allows(InetSocketAddress server, Advertisement ad) {
        return failures.allows(server.getAddress(), ad.port(), ad.fingerprint());
    }

    /** QUIC goes to the TCP connection's resolved IP and the advertised port (docs/protocol.md §3). */
    static InetSocketAddress quicTarget(InetSocketAddress server, Advertisement ad) {
        return new InetSocketAddress(server.getAddress(), ad.port());
    }

    /** Cache key: resolved IP and TCP port, without any host name. */
    public static InetSocketAddress key(InetSocketAddress server) {
        return new InetSocketAddress(server.getAddress(), server.getPort());
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
