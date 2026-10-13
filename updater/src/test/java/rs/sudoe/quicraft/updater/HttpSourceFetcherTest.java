// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Loopback tests for {@link HttpSourceFetcher}. The server is an {@link HttpsServer} bound to
 * 127.0.0.1 with the throwaway test-only identity in {@code tls/test-localhost.p12}; requests use
 * {@code https://localhost:<port>}, and the client trusts only that certificate.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class HttpSourceFetcherTest {

    private static final String PASSWORD = "quicraft-test-only";
    private static final Duration LONG = Duration.ofSeconds(30);

    private HttpsServer server;
    private ExecutorService serverThreads;
    private SSLContext clientTls;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger hits = new AtomicInteger();
    private final List<Thread> stalled = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        KeyStore identity = loadIdentity();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(identity, PASSWORD.toCharArray());
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("localhost", identity.getCertificate("localhost"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        serverThreads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fetcher-test-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        release.countDown();
        server.stop(0);
        serverThreads.shutdownNow();
        for (Thread thread : stalled) {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    // ---- 200 bodies and caps -------------------------------------------------------------

    @Test
    void smallBodyIsFetched() throws Exception {
        route("/small", exchange -> respond(exchange, 200, bytes("hello")));

        FetchedBytes body = fetcher().fetch(request("/small", 1024, LONG, LONG, RedirectPolicy.denyAll()),
                new MutableCancelToken());

        assertArrayEquals(bytes("hello"), body.bytes());
    }

    @Test
    void downloadWritesExactlyTheBody() throws Exception {
        byte[] payload = pattern(100_000);
        route("/big", exchange -> respond(exchange, 200, payload));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        long written = fetcher().download(request("/big", 1L << 20, LONG, LONG, RedirectPolicy.denyAll()),
                Channels.newChannel(sink), new MutableCancelToken());

        assertEquals(payload.length, written);
        assertArrayEquals(payload, sink.toByteArray());
    }

    @Test
    void declaredContentLengthOverCapFailsBeforeAnyBodyByte() throws Exception {
        route("/declared", exchange -> respond(exchange, 200, pattern(100)));

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/declared", 50, LONG, LONG, RedirectPolicy.denyAll()), new MutableCancelToken()));

        assertEquals(FetchOutcome.OVERSIZE, failure.outcome());
    }

    @Test
    void chunkedBodyOverCapWritesExactlyTheCapAndNoMore() throws Exception {
        byte[] payload = pattern(5000);
        route("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().download(
                request("/chunked", 4096, LONG, LONG, RedirectPolicy.denyAll()),
                Channels.newChannel(sink), new MutableCancelToken()));

        assertEquals(FetchOutcome.OVERSIZE, failure.outcome());
        assertEquals(4096, sink.size());
        assertArrayEquals(java.util.Arrays.copyOf(payload, 4096), sink.toByteArray());
    }

    @Test
    void fetchAboveTheFetchCapFailsWithoutContactingTheServer() throws Exception {
        route("/never", exchange -> respond(exchange, 200, bytes("x")));

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/never", FetchRequest.MAX_FETCH_BYTES + 1, LONG, LONG, RedirectPolicy.denyAll()),
                new MutableCancelToken()));

        assertEquals(FetchOutcome.OVERSIZE, failure.outcome());
        assertEquals(0, hits.get());
    }

    /**
     * The JDK server keeps the connection open after a short fixed-length body, so the client sees a
     * stall rather than EOF. The idle timeout reports it as TIMEOUT. A closed connection (TRANSPORT_ERROR)
     * is not reproduced here because the JDK server offers no way to reset the socket from a handler.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shortFixedLengthBodyIsNeverAcceptedAsComplete() throws Exception {
        route("/truncated", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            OutputStream out = exchange.getResponseBody();
            out.write(pattern(10));
            out.flush();
            awaitRelease();
        });

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/truncated", 1024, Duration.ofSeconds(1), LONG, RedirectPolicy.denyAll()),
                new MutableCancelToken()));

        assertEquals(FetchOutcome.TIMEOUT, failure.outcome());
    }

    // ---- status and redirects ------------------------------------------------------------

    @Test
    void notFoundIsHttpStatusWithTheCode() throws Exception {
        route("/missing", exchange -> respond(exchange, 404, bytes("nope")));

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/missing", 1024, LONG, LONG, RedirectPolicy.denyAll()), new MutableCancelToken()));

        assertEquals(FetchOutcome.HTTP_STATUS, failure.outcome());
        assertEquals(404, failure.httpStatus());
    }

    @Test
    void redirectWithoutLocationIsHttpStatus() throws Exception {
        route("/noloc", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/noloc", 1024, LONG, LONG, RedirectPolicy.denyAll()), new MutableCancelToken()));

        assertEquals(FetchOutcome.HTTP_STATUS, failure.outcome());
        assertEquals(302, failure.httpStatus());
    }

    @Test
    void redirectDeniedByDefaultPolicyIsRefused() throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        route("/start", exchange -> redirect(exchange, "/target"));
        route("/target", exchange -> {
            targetHits.incrementAndGet();
            respond(exchange, 200, bytes("target"));
        });

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/start", 1024, LONG, LONG, RedirectPolicy.denyAll()), new MutableCancelToken()));

        assertEquals(FetchOutcome.REDIRECT_REFUSED, failure.outcome());
        assertEquals(0, targetHits.get());
    }

    @Test
    void redirectAllowedByPolicyIsFollowed() throws Exception {
        route("/start", exchange -> redirect(exchange, "/target"));
        route("/target", exchange -> respond(exchange, 200, bytes("moved")));
        List<URI> asked = new ArrayList<>();
        RedirectPolicy allowAll = (host, from, to) -> {
            asked.add(to);
            return true;
        };

        FetchedBytes body = fetcher().fetch(request("/start", 1024, LONG, LONG, allowAll), new MutableCancelToken());

        assertArrayEquals(bytes("moved"), body.bytes());
        assertEquals(1, asked.size());
        assertEquals("https", asked.get(0).getScheme());
    }

    @Test
    void threeRedirectsAreFollowedAndTheFourthIsRefused() throws Exception {
        // Three redirects: /three1 -> /three2 -> /three3 -> /three4 (200).
        route("/three1", exchange -> redirect(exchange, "/three2"));
        route("/three2", exchange -> redirect(exchange, "/three3"));
        route("/three3", exchange -> redirect(exchange, "/three4"));
        route("/three4", exchange -> respond(exchange, 200, bytes("end")));
        // Four redirects: /four1 -> /four2 -> /four3 -> /four4 -> /four5; the fourth is refused.
        route("/four1", exchange -> redirect(exchange, "/four2"));
        route("/four2", exchange -> redirect(exchange, "/four3"));
        route("/four3", exchange -> redirect(exchange, "/four4"));
        route("/four4", exchange -> redirect(exchange, "/four5"));
        route("/four5", exchange -> respond(exchange, 200, bytes("unreachable")));
        RedirectPolicy allowAll = (host, from, to) -> true;

        FetchedBytes body = fetcher().fetch(request("/three1", 1024, LONG, LONG, allowAll),
                new MutableCancelToken());
        assertArrayEquals(bytes("end"), body.bytes());

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/four1", 1024, LONG, LONG, allowAll), new MutableCancelToken()));
        assertEquals(FetchOutcome.REDIRECT_REFUSED, failure.outcome());
    }

    @Test
    void redirectToHttpIsRefusedEvenWhenThePolicyAllowsIt() throws Exception {
        route("/start", exchange -> redirect(exchange, "http://localhost:" + port() + "/x"));
        RedirectPolicy allowAll = (host, from, to) -> true;

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/start", 1024, LONG, LONG, allowAll), new MutableCancelToken()));

        assertEquals(FetchOutcome.REDIRECT_REFUSED, failure.outcome());
    }

    // ---- deadlines, idle timeout and cancellation ---------------------------------------

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void stalledBodyTimesOutWithinTheIdleTimeout() throws Exception {
        route("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes("x"));
            out.flush();
            awaitRelease();
        });

        long started = System.nanoTime();
        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/stall", 1024, Duration.ofMillis(300), LONG, RedirectPolicy.denyAll()),
                new MutableCancelToken()));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(FetchOutcome.TIMEOUT, failure.outcome());
        assertTrue(elapsedMillis < 10_000, "idle timeout took " + elapsedMillis + " ms");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void trickleLongerThanTheOverallDeadlineTimesOut() throws Exception {
        route("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                for (int i = 0; i < 200; i++) {
                    out.write('a');
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException closedByClient) {
                // The client gave up; nothing else to send.
            }
        });

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/trickle", 100_000, LONG, Duration.ofMillis(500), RedirectPolicy.denyAll()),
                new MutableCancelToken()));

        assertEquals(FetchOutcome.TIMEOUT, failure.outcome());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cancelDuringTheBodyStopsWithCancelled() throws Exception {
        route("/cancel", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(pattern(1024));
            out.flush();
            awaitRelease();
        });
        MutableCancelToken cancel = new MutableCancelToken();
        WritableByteChannel sink = new WritableByteChannel() {
            @Override
            public int write(ByteBuffer src) {
                int n = src.remaining();
                src.position(src.limit());
                cancel.cancel();
                return n;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        };

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().download(
                request("/cancel", 1L << 20, LONG, LONG, RedirectPolicy.denyAll()), sink, cancel));

        assertEquals(FetchOutcome.CANCELLED, failure.outcome());
    }

    @Test
    void cancelledBeforeConnectSendsNothing() throws Exception {
        route("/never", exchange -> respond(exchange, 200, bytes("x")));
        MutableCancelToken cancel = new MutableCancelToken();
        cancel.cancel();

        FetchException failure = assertThrows(FetchException.class, () -> fetcher().fetch(
                request("/never", 1024, LONG, LONG, RedirectPolicy.denyAll()), cancel));

        assertEquals(FetchOutcome.CANCELLED, failure.outcome());
        assertEquals(0, hits.get());
    }

    @Test
    void closedPortIsATransportError() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = probe.getLocalPort();
        }
        URI uri = URI.create("https://localhost:" + closedPort + "/gone");
        ReleaseHost host = new ReleaseHost("sudoe", URI.create("https://localhost:" + closedPort));
        FetchRequest request = new FetchRequest(host, uri, 1024, Duration.ofSeconds(5), LONG, LONG,
                RedirectPolicy.denyAll());

        FetchException failure = assertThrows(FetchException.class,
                () -> fetcher().fetch(request, new MutableCancelToken()));

        assertEquals(FetchOutcome.TRANSPORT_ERROR, failure.outcome());
    }

    // ---- helpers -------------------------------------------------------------------------

    private HttpSourceFetcher fetcher() {
        return HttpSourceFetcher.forTests(clientTls, System::nanoTime);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private FetchRequest request(String path, long maxBytes, Duration idle, Duration overall,
            RedirectPolicy redirects) {
        ReleaseHost host = new ReleaseHost("sudoe", URI.create("https://localhost:" + port()));
        URI uri = URI.create("https://localhost:" + port() + path);
        return new FetchRequest(host, uri, maxBytes, Duration.ofSeconds(5), idle, overall, redirects);
    }

    private void route(String path, Handler handler) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            try {
                handler.handle(exchange);
            } catch (Exception ignored) {
                // The client may already be gone; the test asserts on the client side.
            } finally {
                exchange.close();
            }
        });
    }

    private void awaitRelease() {
        stalled.add(Thread.currentThread());
        try {
            release.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange, String location)
            throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static KeyStore loadIdentity() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpSourceFetcherTest.class.getResourceAsStream("/tls/test-localhost.p12")) {
            if (in == null) {
                throw new IOException("missing test TLS fixture");
            }
            store.load(in, PASSWORD.toCharArray());
        }
        return store;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static byte[] pattern(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (i * 31 + 7);
        }
        return out;
    }

    /** One server route. */
    @FunctionalInterface
    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }
}
