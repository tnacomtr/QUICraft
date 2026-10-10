// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.discovery.Advertisement;
import rs.sudoe.quicraft.core.discovery.AdvertisementCache;
import rs.sudoe.quicraft.core.tls.Fingerprint;
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

        /** A QUIC connection to {@code target}, trusted only with {@code fingerprint}. */
        CompletableFuture<QuicByteStream> connectQuic(InetSocketAddress target, Fingerprint fingerprint);

        /** The platform's TCP connection, without the game protocol on it yet. */
        ConnectionRace.Tcp<T> tcp();

        ScheduledExecutorService scheduler();
    }

    /** Exactly one of {@link #quic()} and {@link #tcp()} is non-null. */
    public static final class Outcome<T> {
        private final QuicByteStream quic;
        private final T tcp;
        private final FallbackReason fallback;

        Outcome(QuicByteStream quic, T tcp, FallbackReason fallback) {
            this.quic = quic;
            this.tcp = tcp;
            this.fallback = fallback;
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
    }

    private final AdvertisementCache advertisements;
    private final FailureCache failures;
    private final BooleanSupplier quicAvailable;
    private final long statusQueryTimeoutMillis;

    public ClientConnector(AdvertisementCache advertisements, FailureCache failures) {
        this(advertisements, failures, QuicSupport::isAvailable, STATUS_QUERY_TIMEOUT_MILLIS);
    }

    ClientConnector(AdvertisementCache advertisements, FailureCache failures, BooleanSupplier quicAvailable,
            long statusQueryTimeoutMillis) {
        this.advertisements = advertisements;
        this.failures = failures;
        this.quicAvailable = quicAvailable;
        this.statusQueryTimeoutMillis = statusQueryTimeoutMillis;
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
     * Connects to {@code server} (its resolved TCP address). Fails like TCP would when neither
     * transport connects; in {@code quic-only} mode, with QUIC's error. Cancelling the result
     * abandons the attempt and closes whatever connects afterwards.
     */
    public <T> CompletableFuture<Outcome<T>> connect(InetSocketAddress server, ConnectDecision.Mode mode,
            Platform<T> platform) {
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
                decided(server, mode, found, platform, result);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
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
        ScheduledFuture<?> timeout = platform.scheduler().schedule(
                () -> bounded.completeExceptionally(new TimeoutException("status query")),
                statusQueryTimeoutMillis, TimeUnit.MILLISECONDS);
        final CompletableFuture<Optional<Advertisement>> running = query;
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
            Platform<T> platform, CompletableFuture<Outcome<T>> result) {
        boolean allowed = ad.isPresent() && allows(server, ad.get());
        ConnectDecision.Transport transport = ConnectDecision.decide(mode, quicAvailable.getAsBoolean(), ad, allowed);
        switch (transport) {
            case TCP:
                tcpOnly(platform, result);
                break;
            case QUIC_ONLY:
                quicOnly(server, ad, platform, result);
                break;
            case RACE:
            default:
                race(server, ad.get(), platform, result);
                break;
        }
    }

    private <T> void tcpOnly(Platform<T> platform, CompletableFuture<Outcome<T>> result) {
        ConnectionRace.Tcp<T> tcp = platform.tcp();
        CompletableFuture<T> attempt = tcp.connect();
        attempt.whenComplete((connection, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrap(error));
            } else if (!result.complete(new Outcome<T>(null, connection, null))) {
                tcp.close(connection); // cancelled meanwhile
            }
        });
    }

    private <T> void quicOnly(InetSocketAddress server, Optional<Advertisement> ad, Platform<T> platform,
            CompletableFuture<Outcome<T>> result) {
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
                ad.get().fingerprint());
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

    private <T> void race(InetSocketAddress server, Advertisement ad, Platform<T> platform,
            CompletableFuture<Outcome<T>> result) {
        InetSocketAddress target = quicTarget(server, ad);
        ConnectionRace.Tcp<T> tcp = platform.tcp();
        CompletableFuture<ConnectionRace.Result<T>> race = ConnectionRace.race(
                () -> platform.connectQuic(target, ad.fingerprint()), tcp, HEAD_START_MILLIS, platform.scheduler());
        result.whenComplete((o, e) -> {
            if (result.isCancelled()) {
                race.cancel(false);
            }
        });
        race.whenComplete((r, error) -> {
            if (error != null) {
                result.completeExceptionally(unwrap(error));
                return;
            }
            if (r.quicWon()) {
                failures.recordSuccess(target.getAddress(), target.getPort(), ad.fingerprint());
                if (!result.complete(new Outcome<T>(r.quic(), null, null))) {
                    r.quic().close();
                }
            } else {
                failures.recordFailure(target.getAddress(), target.getPort(), ad.fingerprint());
                if (!result.complete(new Outcome<T>(null, r.tcp(), r.quicFailure()))) {
                    tcp.close(r.tcp());
                }
            }
        });
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
