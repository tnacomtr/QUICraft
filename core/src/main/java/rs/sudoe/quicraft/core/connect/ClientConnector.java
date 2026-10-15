// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
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

        Outcome(QuicByteStream quic, T tcp, FallbackReason fallback) {
            this(quic, tcp, fallback, null);
        }

        Outcome(QuicByteStream quic, T tcp, FallbackReason fallback, V3Plan v3Plan) {
            this.quic = quic;
            this.tcp = tcp;
            this.fallback = fallback;
            this.v3Plan = v3Plan;
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
         * <p>{@link V3Plan#activated()} and {@link V3Plan#failed(boolean)} report the end of the
         * attempt, so the adapter calls one of them once this outcome's QUIC stream has either
         * finished its binding exchange or failed.</p>
         *
         * <p>Non-null exactly when this outcome carries a {@code /3} QUIC stream, so a caller that
         * got TCP instead has nothing to report and nothing to bind: the {@code /3} attempt either
         * never happened (the selection chose {@code /2} or TCP) or already ended in this same
         * attempt, with the connector recording §6's entry and the re-bind backoff (12.16).</p>
         */
        public V3Plan v3Plan() {
            return v3Plan;
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
         *     caller's event loop
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
            this.worker = worker;
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
                    decided(server, mode, advertisement, joinInputs, platform, result);
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
        Faults.check(Faults.V3_SELECT);
        long now = v3.clock().getAsLong();
        TransportSelection.PinState pinState = pinState(logicalHost, logicalPort, advertisement);
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
            // leaves the pin alone, records §6's entry and the re-bind backoff, and joins over fresh
            // vanilla TCP. It never selects /2 and never sends 0x10, because no connection exists.
            failures.recordFailure(target.getAddress(), target.getPort(),
                    advertisement.get().fingerprint());
            v3.rebinds().endAttempt(attempt, false);
            attempt = null;
        }
        String tcpReason = null;
        if (activationBlocked) {
            tcpReason = "the /3 activation failure for this key is still backing off (12.14)";
        } else if (pinStoreFullForNewName(pinState, logicalHost, logicalPort, advertisement)) {
            // 12.16, spec-literal: "Never automatically evict pins: when the pin store is full, use
            // TCP for new names until the player explicitly deletes a pin." So a new name with a
            // full store is TCP, not /2, even though /2 itself needs no pin.
            tcpReason = "the pin store is full and has no pin for this name (12.16)";
        }
        return new Prepared(selection, pinState, attempt, tcpReason,
                table.isPresent() ? table.get().digest() : null);
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
            int logicalPort, Optional<Advertisement> advertisement) {
        if (pinState != TransportSelection.PinState.NONE || !advertisement.isPresent()
                || !Protocol.ALPN_V3.equals(advertisement.get().alpn())) {
            return false;
        }
        if (v3.pins().pin(logicalHost, logicalPort).isPresent()) {
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
            Optional<Advertisement> advertisement) {
        if (!v3.pins().isUsable()) {
            return TransportSelection.PinState.STORE_UNUSABLE;
        }
        if (!advertisement.isPresent()) {
            return TransportSelection.PinState.NONE;
        }
        Optional<PinStore.Pin> pin = v3.pins().pin(logicalHost, logicalPort);
        if (!pin.isPresent()) {
            return TransportSelection.PinState.NONE;
        }
        return pin.get().fingerprint().equals(advertisement.get().fingerprint().toString())
                ? TransportSelection.PinState.SAME
                : TransportSelection.PinState.CHANGED;
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
            // 12.14 and 12.16: this attempt uses TCP. The reason names the rule, never a secret.
            LOG.log(Level.FINE, "QUICraft /3 uses TCP here: {0}", prepared.tcpReason);
            tcpOnly(platform, result);
            return;
        }
        TransportSelection.Transport transport = prepared.selection.transport();
        if (transport == TransportSelection.Transport.FAIL) {
            result.completeExceptionally(new IOException(prepared.selection.reason()));
            return;
        }
        if (transport == TransportSelection.Transport.TCP || !advertisement.isPresent()) {
            tcpOnly(platform, result);
            return;
        }
        if (transport == TransportSelection.Transport.V2) {
            // The shipped decision, unchanged: racing, the failure cache and 0-RTT (12.1).
            decided(server, mode, advertisement, joinInputs, platform, result);
            return;
        }
        v3Connect(server, logicalHost, logicalPort, advertisement.get(), early, prepared, platform, result);
    }

    /**
     * Connects with the {@code quicraft/3} ALPN. An ordinary pin races TCP under §5, exactly as
     * {@code /2} does (13.1); a changed pin gets 12.16's one automatic re-bind attempt, which does
     * not race, because its failure closes with {@code 0x10} first and only then starts a fresh
     * vanilla TCP join.
     */
    private <T> void v3Connect(final InetSocketAddress server, final String logicalHost, final int logicalPort,
            Advertisement ad, EarlyFlight early, final Prepared prepared, Platform<T> platform,
            CompletableFuture<Outcome<T>> result) {
        final InetSocketAddress target = quicTarget(server, ad);
        if (prepared.pinState == TransportSelection.PinState.CHANGED) {
            final boolean bypassResumption = true;
            CompletableFuture<QuicByteStream> attempt = dial(target, ad, early, bypassResumption, platform);
            result.whenComplete((o, e) -> {
                if (result.isCancelled()) {
                    attempt.cancel(false);
                }
            });
            attempt.whenComplete((stream, error) -> {
                if (error != null) {
                    // No /3 connection was created, so no 0x10 is sent: §6's entry and the re-bind
                    // backoff are recorded, and this same attempt joins over fresh vanilla TCP.
                    // Both writes happen on the worker, never on whatever thread completed the dial.
                    v3.worker().execute(new Runnable() {
                        @Override
                        public void run() {
                            endRebindAttemptFailed(server, ad, prepared);
                        }
                    });
                    tcpOnly(platform, result);
                    return;
                }
                failures.recordSuccess(target.getAddress(), target.getPort(), ad.fingerprint());
                V3Plan plan = plan(logicalHost, logicalPort, ad, prepared, target);
                if (!result.complete(new Outcome<T>(stream, null, null, plan))) {
                    stream.close();
                }
            });
            return;
        }
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
        return new PlanInput(logicalHost, logicalPort, ad, prepared, target);
    }

    private static final class PlanInput {
        final String logicalHost;
        final int logicalPort;
        final Advertisement advertisement;
        final Prepared prepared;
        final InetSocketAddress target;

        PlanInput(String logicalHost, int logicalPort, Advertisement advertisement, Prepared prepared,
                InetSocketAddress target) {
            this.logicalHost = logicalHost;
            this.logicalPort = logicalPort;
            this.advertisement = advertisement;
            this.prepared = prepared;
            this.target = target;
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

    /** Ends a changed-pin attempt that never got a connection, recording §6's entry (12.16). */
    private void endRebindAttemptFailed(InetSocketAddress server, Advertisement ad, Prepared prepared) {
        InetSocketAddress target = quicTarget(server, ad);
        failures.recordFailure(target.getAddress(), target.getPort(), ad.fingerprint());
        if (prepared.attempt != null) {
            v3.rebinds().endAttempt(prepared.attempt, false);
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
        ConnectionRace.Tcp<T> tcp = platform.tcp();
        CompletableFuture<T> attempt = tcp.connect();
        attempt.whenComplete((connection, error) -> {
            if (error != null) {
                result.completeExceptionally(new TcpConnectException(unwrap(error)));
            } else if (!result.complete(new Outcome<T>(null, connection, null))) {
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
     * {@code quicraft/3} dial (13.1) and the winning outcome carries its {@link V3Plan}. Everything
     * else, including the head start, the failure cache and the 0-RTT grace period, is unchanged.
     */
    private <T> void race(InetSocketAddress server, Advertisement ad, EarlyFlight early, Platform<T> platform,
            CompletableFuture<Outcome<T>> result, final PlanInput plans) {
        final InetSocketAddress target = quicTarget(server, ad);
        TimedTcp<T> tcp = new TimedTcp<>(platform.tcp());
        CompletableFuture<ConnectionRace.Result<T>> race = ConnectionRace.race(
                plans == null
                        ? () -> platform.connectQuic(target, ad.fingerprint(), early)
                        : () -> dial(target, ad, early, false, platform),
                tcp, HEAD_START_MILLIS, platform.scheduler());
        result.whenComplete((o, e) -> {
            if (result.isCancelled()) {
                race.cancel(false);
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
                    r.quic().close();
                }
                return;
            }
            failures.recordFailure(target.getAddress(), target.getPort(), ad.fingerprint());
            Outcome<T> outcome = new Outcome<T>(null, r.tcp(), r.quicFailure(), null);
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
