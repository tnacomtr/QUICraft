// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLContext;

/**
 * {@link SourceFetcher} over {@link HttpClient}, HTTPS only.
 *
 * <p>Redirects are followed by hand: the client never follows them, each hop is checked for HTTPS and
 * against the request's {@link RedirectPolicy}, and at most {@link RedirectPolicy#MAX_HOPS} are
 * followed. Only status 200 carries a body. The overall deadline is measured from the start of the
 * call. The idle timeout bounds each body read: a watchdog closes the body stream when a read has
 * been blocked for longer than the idle timeout or past the overall deadline, and the read then fails
 * as {@link FetchOutcome#TIMEOUT}.
 */
public final class HttpSourceFetcher implements SourceFetcher {

    /** Body read size. */
    static final int CHUNK_BYTES = 16 * 1024;

    private static final long CANCEL_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final int HTTP_OK = 200;

    private final ClientOpener opener;
    private final boolean ownsClients;
    private final LongSupplier nanoTime;

    /**
     * Uses the caller's client for every fetch. The client must not follow redirects, because the
     * fetcher follows them itself; the caller owns the client and it is never closed here.
     *
     * @param client the HTTP client, with redirects set to {@link HttpClient.Redirect#NEVER}
     * @throws IllegalArgumentException when the client follows redirects
     */
    public HttpSourceFetcher(HttpClient client) {
        Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("client must not follow redirects");
        }
        this.opener = connectTimeout -> client;
        this.ownsClients = false;
        this.nanoTime = System::nanoTime;
    }

    private HttpSourceFetcher(SSLContext tls, LongSupplier nanoTime) {
        this.opener = connectTimeout -> newClient(connectTimeout, tls);
        this.ownsClients = true;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * @return a fetcher that builds one client per fetch, with the request's connect timeout and the
     *     default TLS trust
     */
    public static HttpSourceFetcher create() {
        return new HttpSourceFetcher(null, System::nanoTime);
    }

    /**
     * Test seam: a fetcher whose clients trust the given SSL context, with an injectable clock.
     *
     * @param tls the SSL context used by every client, or {@code null} for the default
     * @param nanoTime the clock used for the overall deadline
     * @return the fetcher
     */
    static HttpSourceFetcher forTests(SSLContext tls, LongSupplier nanoTime) {
        return new HttpSourceFetcher(tls, nanoTime);
    }

    @Override
    public FetchedBytes fetch(FetchRequest request, CancelToken cancel) throws FetchException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancel, "cancel");
        if (request.maxBytes() > FetchRequest.MAX_FETCH_BYTES) {
            throw new FetchException(FetchOutcome.OVERSIZE);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        run(request, cancel, (buf, off, len) -> out.write(buf, off, len));
        return new FetchedBytes(out.toByteArray());
    }

    @Override
    public long download(FetchRequest request, WritableByteChannel destination, CancelToken cancel)
            throws FetchException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(cancel, "cancel");
        return run(request, cancel, (buf, off, len) -> {
            ByteBuffer pending = ByteBuffer.wrap(buf, off, len);
            while (pending.hasRemaining()) {
                destination.write(pending);
            }
        });
    }

    private long run(FetchRequest request, CancelToken cancel, Sink sink) throws FetchException {
        checkCancel(cancel);
        Budget budget = new Budget(nanoTime, request.overallDeadline());
        HttpClient client = null;
        try {
            client = opener.open(request.connectTimeout());
            return follow(client, request, cancel, budget, sink);
        } catch (FetchException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
        } finally {
            if (ownsClients && client != null) {
                client.close();
            }
        }
    }

    private long follow(HttpClient client, FetchRequest request, CancelToken cancel, Budget budget,
            Sink sink) throws FetchException {
        URI current = request.uri();
        int redirects = 0;
        while (true) {
            checkCancel(cancel);
            HttpResponse<InputStream> response = send(client, current, budget, cancel);
            int status = response.statusCode();
            if (isRedirect(status)) {
                String location = response.headers().firstValue("location").orElse(null);
                closeBody(response.body());
                if (location == null) {
                    throw new FetchException(FetchOutcome.HTTP_STATUS, status);
                }
                if (redirects == RedirectPolicy.MAX_HOPS) {
                    throw new FetchException(FetchOutcome.REDIRECT_REFUSED);
                }
                URI target = redirectTarget(current, location);
                if (!request.redirects().allows(request.host(), current, target)) {
                    throw new FetchException(FetchOutcome.REDIRECT_REFUSED);
                }
                redirects++;
                current = target;
                continue;
            }
            if (status != HTTP_OK) {
                closeBody(response.body());
                throw new FetchException(FetchOutcome.HTTP_STATUS, status);
            }
            return readBody(response, request, cancel, budget, sink);
        }
    }

    private HttpResponse<InputStream> send(HttpClient client, URI uri, Budget budget, CancelToken cancel)
            throws FetchException {
        long remaining = budget.remaining();
        if (remaining <= 0) {
            throw new FetchException(FetchOutcome.TIMEOUT);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofNanos(remaining))
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        CompletableFuture<HttpResponse<InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            while (true) {
                remaining = budget.remaining();
                if (remaining <= 0) {
                    abandon(future);
                    throw new FetchException(FetchOutcome.TIMEOUT);
                }
                if (cancel.isCancelled()) {
                    abandon(future);
                    throw new FetchException(FetchOutcome.CANCELLED);
                }
                try {
                    return future.get(Math.min(remaining, CANCEL_POLL_NANOS), TimeUnit.NANOSECONDS);
                } catch (TimeoutException polling) {
                    // Poll again: the deadline and the cancel token are checked at the top of the loop.
                }
            }
        } catch (InterruptedException e) {
            abandon(future);
            Thread.currentThread().interrupt();
            throw new FetchException(FetchOutcome.CANCELLED);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                throw new FetchException(FetchOutcome.TIMEOUT);
            }
            throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
        } catch (CancellationException e) {
            throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
        }
    }

    private long readBody(HttpResponse<InputStream> response, FetchRequest request, CancelToken cancel,
            Budget budget, Sink sink) throws FetchException {
        long maxBytes = request.maxBytes();
        long idleNanos = request.idleReadTimeout().toNanos();
        try (InputStream body = response.body();
                IdleWatchdog watchdog = new IdleWatchdog(body)) {
            OptionalLong declared = declaredLength(response);
            if (declared.isPresent() && declared.getAsLong() > maxBytes) {
                throw new FetchException(FetchOutcome.OVERSIZE);
            }
            byte[] buf = new byte[CHUNK_BYTES];
            long total = 0;
            while (true) {
                checkCancel(cancel);
                long remaining = budget.remaining();
                if (remaining <= 0) {
                    throw new FetchException(FetchOutcome.TIMEOUT);
                }
                int want = (int) Math.min(CHUNK_BYTES, maxBytes + 1 - total);
                watchdog.arm(Math.min(idleNanos, remaining));
                int n;
                try {
                    n = body.read(buf, 0, want);
                } catch (IOException e) {
                    throw mapIo(e, watchdog);
                }
                watchdog.disarm();
                if (watchdog.fired()) {
                    throw new FetchException(FetchOutcome.TIMEOUT);
                }
                if (n == -1) {
                    return total;
                }
                if (total + n > maxBytes) {
                    int keep = (int) (maxBytes - total);
                    if (keep > 0) {
                        write(sink, buf, keep, watchdog);
                    }
                    throw new FetchException(FetchOutcome.OVERSIZE);
                }
                write(sink, buf, n, watchdog);
                total += n;
            }
        } catch (IOException e) {
            throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
        }
    }

    private static void write(Sink sink, byte[] buf, int len, IdleWatchdog watchdog)
            throws FetchException {
        try {
            sink.accept(buf, 0, len);
        } catch (IOException e) {
            throw mapIo(e, watchdog);
        }
    }

    private static FetchException mapIo(IOException e, IdleWatchdog watchdog) {
        if (watchdog.fired() || e instanceof HttpTimeoutException) {
            return new FetchException(FetchOutcome.TIMEOUT);
        }
        return new FetchException(FetchOutcome.TRANSPORT_ERROR);
    }

    private static OptionalLong declaredLength(HttpResponse<?> response) throws FetchException {
        try {
            return response.headers().firstValueAsLong("content-length");
        } catch (NumberFormatException e) {
            throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
        }
    }

    private static URI redirectTarget(URI current, String location) throws FetchException {
        URI target;
        try {
            target = current.resolve(URI.create(location));
        } catch (IllegalArgumentException e) {
            throw new FetchException(FetchOutcome.REDIRECT_REFUSED);
        }
        if (!target.isAbsolute() || target.isOpaque() || target.getRawAuthority() == null
                || target.getRawAuthority().indexOf('@') >= 0 || target.getRawFragment() != null
                || !"https".equalsIgnoreCase(target.getScheme())) {
            throw new FetchException(FetchOutcome.REDIRECT_REFUSED);
        }
        return target;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void checkCancel(CancelToken cancel) throws FetchException {
        if (cancel.isCancelled()) {
            throw new FetchException(FetchOutcome.CANCELLED);
        }
    }

    private static void closeBody(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // Nothing is read from a refused or failed response; a close error changes nothing.
        }
    }

    private static void abandon(CompletableFuture<HttpResponse<InputStream>> future) {
        future.cancel(true);
        if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
            closeBody(future.join().body());
        }
    }

    private static HttpClient newClient(Duration connectTimeout, SSLContext tls) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout);
        if (tls != null) {
            builder.sslContext(tls);
        }
        return builder.build();
    }

    /** Opens the client for one fetch. */
    @FunctionalInterface
    private interface ClientOpener {
        HttpClient open(Duration connectTimeout);
    }

    /** Receives body bytes. */
    @FunctionalInterface
    private interface Sink {
        void accept(byte[] buf, int off, int len) throws IOException;
    }

    /** The overall deadline: a fixed budget measured from a start instant, never reset. */
    private static final class Budget {
        private final LongSupplier clock;
        private final long start;
        private final long budgetNanos;

        Budget(LongSupplier clock, Duration overall) {
            this.clock = clock;
            this.start = clock.getAsLong();
            this.budgetNanos = overall.toNanos();
        }

        long remaining() {
            return budgetNanos - (clock.getAsLong() - start);
        }
    }

    /**
     * Closes the body stream when a read is armed longer than its delay. The read then fails and the
     * caller maps the failure to TIMEOUT.
     */
    private static final class IdleWatchdog implements AutoCloseable {
        private final ScheduledExecutorService executor;
        private final InputStream stream;
        private final AtomicBoolean fired = new AtomicBoolean();
        private ScheduledFuture<?> pending;

        IdleWatchdog(InputStream stream) {
            this.stream = stream;
            this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "quicraft-updater-idle-watchdog");
                thread.setDaemon(true);
                return thread;
            });
        }

        void arm(long delayNanos) {
            disarm();
            pending = executor.schedule(this::fire, delayNanos, TimeUnit.NANOSECONDS);
        }

        void disarm() {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
        }

        boolean fired() {
            return fired.get();
        }

        private void fire() {
            fired.set(true);
            closeBody(stream);
        }

        @Override
        public void close() {
            disarm();
            executor.shutdownNow();
        }
    }
}
