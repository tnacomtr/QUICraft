// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Runnable mock release host for the Wave 5 updater integration run (docs/updater.md plan): one
 * {@link HttpsServer} per port, all serving the same precomputed documents, detached signatures and
 * candidate jar at the {@link DevReleaseEndpoints} paths, and 404 for anything else.
 *
 * <p>The documents are signed with the published RFC 8032 section 7.1 TEST 1 and TEST 2 seeds, so a
 * server running with {@code updater/dev-trust/updater-trust.json} accepts them: TEST 1
 * ({@code release} only) signs the release manifest, TEST 2 ({@code release} and {@code revocation})
 * signs the revocations document. No key or certificate is ever generated; the TLS identity is the
 * throwaway fixture {@code /tls/test-localhost.p12}.
 *
 * <p>{@code --version} and {@code --platform} must match the candidate jar's
 * {@code META-INF/quicraft/build.json}: staging compares them through {@link ArtifactVerifier}.
 *
 * <p>Run it as the orchestrator does:
 * <pre>
 * java -cp updater/build/classes/java/test:updater/build/resources/test:\
 *      updater/build/classes/java/main:updater/build/resources/main \
 *      rs.sudoe.quicraft.updater.MockReleaseHostMain --jar candidate.jar --version 1.0.0 \
 *      --platform fabric-26x --targets 26.1.2 --ports 8443,8444,8445 --export-truststore trust.p12
 * </pre>
 *
 * <p>The server JVM trusts the exported store with
 * {@code -Djavax.net.ssl.trustStore=<path> -Djavax.net.ssl.trustStorePassword=quicraft-test-only}.
 */
public final class MockReleaseHostMain {

    /** Served path of the signed release manifest. */
    static final String MANIFEST_PATH = "/manifest.json";
    /** Served path of the manifest signature envelope. */
    static final String MANIFEST_SIG_PATH = "/manifest.json.sig";
    /** Served path of the signed revocations document. */
    static final String REVOCATIONS_PATH = "/revocations.json";
    /** Served path of the revocations signature envelope. */
    static final String REVOCATIONS_SIG_PATH = "/revocations.json.sig";

    /** The throwaway test TLS identity, read from the test resources. */
    static final String TLS_RESOURCE = "/tls/test-localhost.p12";
    /** Store and key password of that identity, and of the exported truststore. */
    static final String TLS_PASSWORD = "quicraft-test-only";
    /** Alias of the identity certificate inside the fixture. */
    static final String TLS_ALIAS = "localhost";

    /** The manifest text is accepted long before the document lifetime ends. */
    static final Duration ISSUED_BACKDATE = Duration.ofMinutes(1);
    /** Document lifetime: 30 days, inside {@link FreshnessPolicy#MANIFEST_MAX_LIFETIME}. */
    static final Duration LIFETIME = Duration.ofDays(30);

    /** Release file names the manifest parser accepts. */
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._+-]{1,124}\\.jar");

    private static final int MAX_PORT = 65_535;

    private static final String USAGE =
            "usage: MockReleaseHostMain --jar <candidate.jar> --version <v> "
                    + "--platform <fabric-26x|fabric-1.21.11|velocity> --targets <comma list> "
                    + "--ports <p1,p2,p3> [--manifest-seq N] [--revocations-seq N] "
                    + "[--export-truststore <path>]";

    private MockReleaseHostMain() {
    }

    /**
     * Starts the host and serves until the process is killed.
     *
     * @throws IllegalArgumentException on a malformed command line
     * @throws IOException if the jar, the TLS fixture or the truststore path cannot be used
     */
    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("MOCK ERROR " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Content content;
        try {
            content = content(options.jar(), options.version(), options.platform(), options.targets(),
                    options.manifestSeq(), options.revocationsSeq(), Instant.now());
            if (options.truststore() != null) {
                exportTruststore(options.truststore());
                System.out.println("MOCK TRUSTSTORE " + options.truststore().toAbsolutePath());
            }
        } catch (IllegalArgumentException e) {
            System.err.println("MOCK ERROR " + e.getMessage());
            System.exit(2);
            return;
        } catch (IOException | GeneralSecurityException e) {
            System.err.println("MOCK ERROR " + e);
            System.exit(1);
            return;
        }
        try (Handle handle = start(content, options.ports())) {
            System.out.println("MOCK READY sha512=" + content.sha512() + " size=" + content.size()
                    + " ports=" + join(handle.ports()));
            // Keep serving until the process is killed; every server owns its own threads.
            new CountDownLatch(1).await();
        } catch (IOException | GeneralSecurityException e) {
            System.err.println("MOCK ERROR " + e);
            System.exit(1);
        }
    }

    /**
     * Builds the exact bytes the host serves for one candidate jar.
     *
     * <p>The manifest lists exactly this one release, with the jar's file name and tag
     * {@code v<version>}. Both documents are issued one minute ago and expire after
     * {@link #LIFETIME}; {@code minRevocationsSeq} is the revocations sequence and
     * {@code minManifestSeq} is one below the manifest sequence, so both floors are already met on
     * a server whose state holds no revision yet.
     *
     * @param jar the candidate jar; its bytes are read and never modified
     * @param version the release version, which must be newer than the running one
     * @param platform one of {@code velocity}, {@code fabric-26x} or {@code fabric-1.21.11}
     * @param targets the exact game targets the release supports
     * @param manifestSeq the manifest sequence number, at least 1
     * @param revocationsSeq the revocations sequence number, at least 1
     * @param now the instant the documents are built around
     */
    public static Content content(Path jar, String version, String platform, List<String> targets,
            long manifestSeq, long revocationsSeq, Instant now)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(now, "now");
        UpdateVersion release = version(version);
        SharedChecks.requirePlatform(platform);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets must not be empty");
        }
        if (manifestSeq < 1 || revocationsSeq < 1) {
            throw new IllegalArgumentException("sequence numbers start at 1");
        }
        byte[] artifact = Files.readAllBytes(jar);
        String fileName = jar.getFileName().toString();
        if (!FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("jar file name is not a release file name: " + fileName);
        }
        String sha512 = sha512Hex(artifact);
        Instant issued = now.truncatedTo(ChronoUnit.SECONDS).minus(ISSUED_BACKDATE);
        String issuedText = timestamp(issued);
        String expiresText = timestamp(issued.plus(LIFETIME));
        byte[] manifest = bytes(manifestJson(platform, release, targets, fileName, artifact.length,
                sha512, manifestSeq, revocationsSeq, issuedText, expiresText));
        byte[] revocations = bytes(revocationsJson(revocationsSeq, manifestSeq - 1, issuedText, expiresText));
        byte[] manifestSig = SignedFixtures.envelopeFor(manifest,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, manifest));
        byte[] revocationsSig = SignedFixtures.envelopeFor(revocations,
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, revocations));
        Map<String, byte[]> byPath = new LinkedHashMap<>();
        byPath.put(MANIFEST_PATH, manifest);
        byPath.put(MANIFEST_SIG_PATH, manifestSig);
        byPath.put(REVOCATIONS_PATH, revocations);
        byPath.put(REVOCATIONS_SIG_PATH, revocationsSig);
        byPath.put("/artifact/" + sha512 + ".jar", artifact);
        return new Content(byPath, sha512, artifact.length, release.toString());
    }

    /**
     * Starts one {@link HttpsServer} per port, all serving {@code content} on 127.0.0.1.
     *
     * @param content the bytes to serve
     * @param ports the ports to bind; {@code 0} binds an ephemeral port
     * @return the running host; closing it stops every listener
     * @throws IOException if the TLS fixture is missing or a port cannot be bound
     */
    public static Handle start(Content content, List<Integer> ports)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(ports, "ports");
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("at least one port is required");
        }
        for (Integer port : ports) {
            if (port == null || port < 0 || port > MAX_PORT) {
                throw new IllegalArgumentException("invalid port");
            }
        }
        SSLContext tls = serverTls();
        ExecutorService threads = Executors.newCachedThreadPool(daemonThreads());
        List<HttpsServer> servers = new ArrayList<>(ports.size());
        List<Integer> bound = new ArrayList<>(ports.size());
        try {
            for (Integer port : ports) {
                HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                server.setHttpsConfigurator(new HttpsConfigurator(tls));
                server.setExecutor(threads);
                server.createContext("/", exchange -> serve(exchange, content));
                server.start();
                servers.add(server);
                bound.add(server.getAddress().getPort());
            }
        } catch (IOException | RuntimeException e) {
            for (HttpsServer server : servers) {
                server.stop(0);
            }
            threads.shutdownNow();
            throw e;
        }
        return new Handle(servers, List.copyOf(bound), threads);
    }

    /**
     * Writes a PKCS12 truststore holding only the fixture certificate as a trusted-certificate
     * entry, for a server JVM's {@code -Djavax.net.ssl.trustStore}.
     *
     * @param target the file to write, or to overwrite
     */
    public static void exportTruststore(Path target) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(target, "target");
        Certificate certificate = loadIdentity().getCertificate(TLS_ALIAS);
        if (certificate == null) {
            throw new IOException("test TLS fixture holds no certificate");
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry(TLS_ALIAS, certificate);
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            trust.store(out, TLS_PASSWORD.toCharArray());
        }
    }

    private static void serve(HttpExchange exchange, Content content) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        byte[] body = "GET".equals(method) ? content.bytesFor(path) : null;
        int status = !"GET".equals(method) ? 405 : body == null ? 404 : 200;
        // Logged before the response is sent, so a client that has the body has the log line too.
        System.out.println("MOCK " + method + " " + path + " " + status);
        try {
            if (body == null) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } finally {
            exchange.close();
        }
    }

    private static String manifestJson(String platform, UpdateVersion version, List<String> targets,
            String fileName, long size, String sha512, long seq, long minRevocationsSeq,
            String issued, String expires) {
        return "{\"type\":\"quicraft.release\",\"v\":1,\"seq\":" + seq
                + ",\"issued\":\"" + issued + "\",\"expires\":\"" + expires + "\""
                + ",\"minRevocationsSeq\":" + minRevocationsSeq + ",\"releases\":[{\"platform\":\""
                + platform + "\",\"version\":\"" + version + "\",\"targets\":["
                + quoted(targets) + "],\"file\":\"" + fileName + "\",\"size\":" + size
                + ",\"sha512\":\"" + sha512 + "\",\"tag\":\"v" + version + "\"}]"
                + ",\"withdrawn\":[]}";
    }

    private static String revocationsJson(long seq, long minManifestSeq, String issued, String expires) {
        return "{\"type\":\"quicraft.revocations\",\"v\":1,\"seq\":" + seq
                + ",\"issued\":\"" + issued + "\",\"expires\":\"" + expires + "\""
                + ",\"minManifestSeq\":" + minManifestSeq
                + ",\"revokedKeys\":[],\"blockedArtifacts\":[]}";
    }

    private static String quoted(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(items.get(i)).append('"');
        }
        return out.toString();
    }

    private static String join(List<Integer> ports) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ports.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(ports.get(i));
        }
        return out.toString();
    }

    private static String timestamp(Instant instant) {
        return DocumentParser.TIMESTAMP_FORMAT.format(instant.atZone(ZoneOffset.UTC));
    }

    private static UpdateVersion version(String version) {
        Objects.requireNonNull(version, "version");
        try {
            return UpdateVersion.parse(version);
        } catch (UpdateRejectedException e) {
            throw new IllegalArgumentException("invalid version: " + version);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha512Hex(byte[] data) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-512").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    private static SSLContext serverTls() throws IOException, GeneralSecurityException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(loadIdentity(), TLS_PASSWORD.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        return tls;
    }

    private static KeyStore loadIdentity() throws IOException, GeneralSecurityException {
        KeyStore identity = KeyStore.getInstance("PKCS12");
        try (InputStream in = MockReleaseHostMain.class.getResourceAsStream(TLS_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing test TLS fixture");
            }
            identity.load(in, TLS_PASSWORD.toCharArray());
        }
        return identity;
    }

    private static ThreadFactory daemonThreads() {
        return runnable -> {
            Thread thread = new Thread(runnable, "mock-release-host");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** The exact bytes the host serves, keyed by request path, plus the artifact identity. */
    public static final class Content {

        private final Map<String, byte[]> byPath;
        private final String sha512;
        private final long size;
        private final String version;

        Content(Map<String, byte[]> byPath, String sha512, long size, String version) {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : byPath.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().clone());
            }
            this.byPath = Map.copyOf(copy);
            this.sha512 = Objects.requireNonNull(sha512, "sha512");
            this.size = size;
            this.version = Objects.requireNonNull(version, "version");
        }

        /** The bytes served at {@code path}, or {@code null} when the path is not served. */
        public byte[] bytesFor(String path) {
            return byPath.get(path);
        }

        /** The lowercase hex SHA-512 of the candidate jar. */
        public String sha512() {
            return sha512;
        }

        /** The candidate jar size in bytes. */
        public long size() {
            return size;
        }

        /** The canonical release version the manifest lists. */
        public String version() {
            return version;
        }

        /**
         * A copy of this content with one served path replaced. Used to serve a tampered document.
         *
         * @throws IllegalArgumentException if the path is not served here
         */
        public Content replacing(String path, byte[] bytes) {
            if (!byPath.containsKey(path)) {
                throw new IllegalArgumentException("not a served path: " + path);
            }
            Map<String, byte[]> copy = new LinkedHashMap<>(byPath);
            copy.put(path, Objects.requireNonNull(bytes, "bytes").clone());
            return new Content(copy, sha512, size, version);
        }
    }

    /** A running set of servers over one {@link Content}. */
    public static final class Handle implements AutoCloseable {

        private final List<HttpsServer> servers;
        private final List<Integer> ports;
        private final ExecutorService threads;

        Handle(List<HttpsServer> servers, List<Integer> ports, ExecutorService threads) {
            this.servers = List.copyOf(servers);
            this.ports = List.copyOf(ports);
            this.threads = threads;
        }

        /** The bound ports in requested order; an ephemeral request is replaced by the real port. */
        public List<Integer> ports() {
            return ports;
        }

        @Override
        public void close() {
            for (HttpsServer server : servers) {
                server.stop(0);
            }
            threads.shutdownNow();
        }
    }

    /** The parsed command line. */
    private record Options(
            Path jar,
            String version,
            String platform,
            List<String> targets,
            List<Integer> ports,
            long manifestSeq,
            long revocationsSeq,
            Path truststore) {

        static Options parse(String[] args) {
            Path jar = null;
            String version = null;
            String platform = null;
            List<String> targets = null;
            List<Integer> ports = null;
            long manifestSeq = 1;
            long revocationsSeq = 1;
            Path truststore = null;
            if (args.length % 2 != 0) {
                throw new IllegalArgumentException("every option needs a value");
            }
            for (int i = 0; i < args.length; i += 2) {
                String name = args[i];
                String value = args[i + 1];
                if (!name.startsWith("--") || value.startsWith("--")) {
                    throw new IllegalArgumentException("every option needs a value");
                }
                switch (name) {
                    case "--jar" -> jar = Path.of(value);
                    case "--version" -> version = value;
                    case "--platform" -> platform = value;
                    case "--targets" -> targets = List.of(value.split(",", -1));
                    case "--ports" -> ports = ports(value);
                    case "--manifest-seq" -> manifestSeq = seq(value);
                    case "--revocations-seq" -> revocationsSeq = seq(value);
                    case "--export-truststore" -> truststore = Path.of(value);
                    default -> throw new IllegalArgumentException("unknown option " + name);
                }
            }
            if (jar == null || version == null || platform == null || targets == null || ports == null) {
                throw new IllegalArgumentException("--jar, --version, --platform, --targets and --ports are required");
            }
            return new Options(jar, version, platform, targets, ports, manifestSeq, revocationsSeq,
                    truststore);
        }

        private static List<Integer> ports(String value) {
            String[] parts = value.split(",", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("--ports needs three values");
            }
            List<Integer> out = new ArrayList<>(parts.length);
            for (String part : parts) {
                int port;
                try {
                    port = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid port");
                }
                if (port < 0 || port > MAX_PORT) {
                    throw new IllegalArgumentException("invalid port");
                }
                out.add(port);
            }
            return List.copyOf(out);
        }

        private static long seq(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid sequence number");
            }
        }
    }
}
