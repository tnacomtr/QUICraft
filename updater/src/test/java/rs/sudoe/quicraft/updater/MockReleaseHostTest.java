// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import rs.sudoe.quicraft.updater.MockReleaseHostMain.Content;
import rs.sudoe.quicraft.updater.MockReleaseHostMain.Handle;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;

/**
 * Loopback tests for {@link MockReleaseHostMain}, the mock release host of the Wave 5 updater
 * integration run.
 *
 * <p>The host serves three ephemeral HTTPS ports with the throwaway {@code tls/test-localhost.p12}
 * identity, and the trust is the real development file {@code updater/dev-trust/updater-trust.json},
 * so the documents can only be accepted through the production signer, freshness, sequence and
 * coverage rules. The client is an {@link HttpSourceFetcher} trusting only the exported truststore,
 * the same configuration the orchestrator gives the server JVM.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class MockReleaseHostTest {

    private static final String PASSWORD = "quicraft-test-only";
    private static final Path DEV_TRUST = Paths.get("dev-trust", "updater-trust.json");

    /**
     * The check runs on a fixed second-precision clock, like every other {@link CheckPipeline} test:
     * the state timestamps the pipeline persists are second-precision, so the clock a check runs on
     * must be too.
     */
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final List<String> KEYS = List.of("modrinth", "github", "sudoe");
    private static final String PLATFORM = "fabric-26x";
    private static final String TARGET = "26.1.2";
    private static final String VERSION = "1.0.0";
    private static final String RUNNING_VERSION = "0.3.0";
    private static final String RUNNING_SHA512 = "ab".repeat(64);
    private static final String JAR_NAME = "quicraft-fabric-26x-1.0.0.jar";

    @TempDir
    Path tmp;

    @Test
    void theRealCheckPathOffersTheCandidateJar() throws Exception {
        Path jar = candidateJar();
        String expectedSha512 = sha512Of(jar);
        Content content = content(jar);
        Path truststore = tmp.resolve("mock-trust.p12");
        MockReleaseHostMain.exportTruststore(truststore);
        UpdateDocuments.Trust trust = devTrust();
        StateStore store = initialisedStore(trust);

        try (Handle host = MockReleaseHostMain.start(content, List.of(0, 0, 0))) {
            assertEquals(3, Set.copyOf(host.ports()).size(), "three distinct ports");
            ReleaseHosts hosts = hosts(host.ports());

            CheckResult result = pipeline(store, hosts, truststore, trust)
                    .run(CheckMode.STAGE, runningVersion(), new MutableCancelToken());

            AcceptedCandidate candidate = result.candidate()
                    .orElseThrow(() -> new AssertionError("no candidate offered: " + result.diagnostics()));
            assertEquals(expectedSha512, content.sha512(), "the manifest lists the jar's SHA-512");
            assertEquals(UpdateVersion.parse(VERSION), candidate.entry().version());
            assertEquals(expectedSha512, candidate.entry().sha512());
            assertEquals(content.size(), candidate.entry().size());
            assertEquals(Files.size(jar), candidate.entry().size());
            assertEquals(JAR_NAME, candidate.entry().file());
            assertEquals(KEYS, keys(candidate.artifactSources()), "every host served the winner");
            assertTrue(result.freshRevocations(), result.diagnostics().toString());
            assertFalse(result.stateFull(), result.diagnostics().toString());
            assertArrayEquals(Files.readAllBytes(jar), download(candidate, truststore));
        }
    }

    @Test
    void aTamperedManifestByteIsRejected() throws Exception {
        Content content = content(candidateJar());
        Content tampered = content.replacing(MockReleaseHostMain.MANIFEST_PATH,
                tamperOneByte(content.bytesFor(MockReleaseHostMain.MANIFEST_PATH)));
        Path truststore = tmp.resolve("mock-trust.p12");
        MockReleaseHostMain.exportTruststore(truststore);
        UpdateDocuments.Trust trust = devTrust();
        StateStore store = initialisedStore(trust);

        try (Handle host = MockReleaseHostMain.start(tampered, List.of(0, 0, 0))) {
            CheckResult result = pipeline(store, hosts(host.ports()), truststore, trust)
                    .run(CheckMode.REPORT, runningVersion(), new MutableCancelToken());

            assertFalse(result.candidate().isPresent(), result.diagnostics().toString());
            assertEquals(Set.copyOf(KEYS), store.load().orElseThrow().state().tornSince().keySet(),
                    "every tampered pair is recorded as torn");
        }
    }

    @Test
    void exportedTruststoreHoldsExactlyOneTrustedCertificate() throws Exception {
        Path truststore = tmp.resolve("exported-trust.p12");

        MockReleaseHostMain.exportTruststore(truststore);

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(truststore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        assertEquals(1, store.size());
        Enumeration<String> aliases = store.aliases();
        String alias = aliases.nextElement();
        assertFalse(aliases.hasMoreElements(), "exactly one entry");
        assertTrue(store.isCertificateEntry(alias));
        assertFalse(store.isKeyEntry(alias), "no private key is exported");
        assertNotNull(store.getCertificate(alias));
        assertNull(store.getKey(alias, PASSWORD.toCharArray()));
    }

    /**
     * Runs {@link MockReleaseHostMain#main} in a child JVM, the way the orchestrator does, and
     * fetches from the ports it reports. This covers the command line, the {@code MOCK READY} line
     * and the exported truststore end to end.
     */
    @Test
    void mainServesTheDocumentsAtTheDevReleaseEndpoints() throws Exception {
        Path jar = candidateJar();
        Path truststore = tmp.resolve("child-trust.p12");
        UpdateDocuments.Trust trust = devTrust();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                MockReleaseHostMain.class.getName(),
                "--jar", jar.toString(),
                "--version", VERSION,
                "--platform", PLATFORM,
                "--targets", TARGET,
                "--ports", "0,0,0",
                "--export-truststore", truststore.toString())
                .redirectErrorStream(true)
                .start();
        List<String> log = new ArrayList<>();
        ChildOutput out = new ChildOutput(child);
        try {
            Ready ready = null;
            while (ready == null) {
                String line = out.next();
                assertNotNull(line, "the host stopped before MOCK READY: " + log);
                log.add(line);
                if (line.startsWith("MOCK READY ")) {
                    ready = Ready.parse(line);
                }
            }
            assertEquals(3, Set.copyOf(ready.ports()).size(), "three distinct ports");
            assertEquals(sha512Of(jar), ready.sha512());
            assertEquals(Files.size(jar), ready.size());

            ReleaseHost host = new ReleaseHost("modrinth",
                    URI.create("https://localhost:" + ready.ports().get(0)));
            ReleaseEndpoints endpoints = new DevReleaseEndpoints();
            byte[] manifest = fetch(endpoints.manifest(host), host, truststore);
            byte[] signature = fetch(endpoints.manifestSignature(host), host, truststore);

            DocumentVerification<UpdateDocuments.ReleaseManifest> verification =
                    AuthenticatedDocuments.verifyRelease(manifest, signature,
                            TrustSnapshot.of(trust, Set.of()));
            assertEquals(VerificationOutcome.VERIFIED, verification.outcome());
            UpdateDocuments.ReleaseEntry entry = verification.verified().document().releases().get(0);
            assertEquals(UpdateVersion.parse(VERSION), entry.version());
            assertEquals(ready.sha512(), entry.sha512());
            assertArrayEquals(Files.readAllBytes(jar),
                    fetch(endpoints.artifact(host, entry), host, truststore));

            FetchException missing = assertThrows(FetchException.class,
                    () -> fetch(URI.create(host.origin() + "/nope"), host, truststore));
            assertEquals(FetchOutcome.HTTP_STATUS, missing.outcome());
            assertEquals(404, missing.httpStatus());

            // Every request is logged before its response is sent, so exactly these four lines are
            // waiting once the four fetches above have returned.
            List<String> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                requests.add(out.next());
            }
            assertEquals(List.of(
                    "MOCK GET " + MockReleaseHostMain.MANIFEST_PATH + " 200",
                    "MOCK GET " + MockReleaseHostMain.MANIFEST_SIG_PATH + " 200",
                    "MOCK GET " + endpoints.artifact(host, entry).getPath() + " 200",
                    "MOCK GET /nope 404"), requests, log.toString());
        } finally {
            // Kill first: that closes the pipe and ends the drain thread.
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the host did not exit");
        }
    }

    // ---- fixtures ------------------------------------------------------------------------

    /** A candidate jar for {@link #PLATFORM} at {@link #VERSION}, newer than the running identity. */
    private Path candidateJar() throws Exception {
        String buildJson = "{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\"" + PLATFORM
                + "\",\"version\":\"" + VERSION + "\"}";
        return JarFixtures.jar(tmp.resolve("candidate"), JAR_NAME, JarFixtures.OWN_ID, buildJson);
    }

    private static Content content(Path jar) throws Exception {
        return MockReleaseHostMain.content(jar, VERSION, PLATFORM, List.of(TARGET), 1, 1, NOW);
    }

    /**
     * Flips one bit of the manifest body, which is what the signature envelope hashes. The flipped
     * byte is a digit of the {@code issued} year, so the document stays valid JSON of a valid shape;
     * the payload SHA-256 in the envelope is checked before anything else, so the single changed
     * byte is the only reason the pair can no longer verify.
     */
    private static byte[] tamperOneByte(byte[] manifest) {
        String marker = "\"issued\":\"";
        int year = new String(manifest, StandardCharsets.UTF_8).indexOf(marker) + marker.length() + 3;
        byte[] tampered = manifest.clone();
        tampered[year] ^= 0x01;
        return tampered;
    }

    /** The trust a dev server runs with: TEST 1 holds release, TEST 2 holds release and revocation. */
    private static UpdateDocuments.Trust devTrust() throws Exception {
        return DocumentParser.parseTrust(Files.readAllBytes(DEV_TRUST));
    }

    /** A store seeded by the running trust file, the way a first start seeds it. */
    private StateStore initialisedStore(UpdateDocuments.Trust trust) throws Exception {
        StateStore store = new StateStore(PipelineFixtures.privateDir(tmp.resolve("upd")));
        store.initialiseIfAbsent(new StateDelta(
                TrustTransition.apply(TrustTransition.classify(null, trust), trust, null).newState(),
                RevokedState.empty()), true);
        return store;
    }

    private CheckPipeline pipeline(StateStore store, ReleaseHosts hosts, Path truststore,
            UpdateDocuments.Trust trust) throws Exception {
        MetadataCollector collector = new MetadataCollector(
                HttpSourceFetcher.forTests(clientTls(truststore), System::nanoTime),
                new DevReleaseEndpoints(), Map.of(), FetchTimeouts.defaults());
        RuntimeIdentity identity =
                new RuntimeIdentity(PLATFORM, TARGET, runningVersion(), trust, RUNNING_SHA512);
        return new CheckPipeline(store, collector, hosts, identity, CLOCK);
    }

    /** Fetches the accepted artifact over the real endpoint layout, as staging does. */
    private static byte[] download(AcceptedCandidate candidate, Path truststore) throws Exception {
        ReleaseHost source = candidate.artifactSources().get(0);
        URI uri = new DevReleaseEndpoints().artifact(source, candidate.entry());
        FetchTimeouts timeouts = FetchTimeouts.defaults();
        FetchRequest request = new FetchRequest(source, uri, FetchRequest.MAX_FETCH_BYTES,
                timeouts.connect(), timeouts.idle(), timeouts.overall(), RedirectPolicy.denyAll());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        HttpSourceFetcher.forTests(clientTls(truststore), System::nanoTime)
                .download(request, Channels.newChannel(sink), new MutableCancelToken());

        return sink.toByteArray();
    }

    /** One GET over the real fetcher, trusting only the exported truststore. */
    private static byte[] fetch(URI uri, ReleaseHost host, Path truststore) throws Exception {
        FetchTimeouts timeouts = FetchTimeouts.defaults();
        FetchRequest request = new FetchRequest(host, uri, FetchRequest.MAX_FETCH_BYTES,
                timeouts.connect(), timeouts.idle(), timeouts.overall(), RedirectPolicy.denyAll());
        return HttpSourceFetcher.forTests(clientTls(truststore), System::nanoTime)
                .fetch(request, new MutableCancelToken()).bytes();
    }

    /** The client TLS context the server JVM gets from {@code -Djavax.net.ssl.trustStore}. */
    private static SSLContext clientTls(Path truststore) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(truststore)) {
            trust.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, tmf.getTrustManagers(), null);
        return tls;
    }

    private static ReleaseHosts hosts(List<Integer> ports) {
        List<ReleaseHost> hosts = new ArrayList<>(ports.size());
        for (int i = 0; i < ports.size(); i++) {
            hosts.add(new ReleaseHost(KEYS.get(i), URI.create("https://localhost:" + ports.get(i))));
        }
        return ReleaseHosts.of(hosts);
    }

    private static List<String> keys(List<ReleaseHost> hosts) {
        List<String> keys = new ArrayList<>(hosts.size());
        for (ReleaseHost host : hosts) {
            keys.add(host.key());
        }
        return keys;
    }

    private static UpdateVersion runningVersion() throws Exception {
        return UpdateVersion.parse(RUNNING_VERSION);
    }

    /** The SHA-512 recorded in the release manifest, computed independently of the host. */
    private static String sha512Of(Path jar) throws Exception {
        return Hex.encode(MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(jar)));
    }

    /**
     * Drains a child's output on a daemon thread. A {@code readLine()} on the pipe itself ignores
     * interrupts, so a host that stays alive without printing would outlive the test's
     * {@code @Timeout}; polling a queue with a deadline cannot hang.
     */
    private static final class ChildOutput {
        private static final String EOF = new String("EOF");
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        ChildOutput(Process child) {
            Thread drain = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = in.readLine()) != null; ) {
                        lines.add(line);
                    }
                } catch (IOException ignored) {
                    // the pipe closed when the child was killed
                } finally {
                    lines.add(EOF);
                }
            }, "mock-host-output");
            drain.setDaemon(true);
            drain.start();
        }

        /** The next line, or {@code null} once the child closed its output. */
        String next() throws InterruptedException {
            String line = lines.poll(30, TimeUnit.SECONDS);
            assertNotNull(line, "no output from the host within 30 s");
            return line == EOF ? null : line;
        }
    }

    /** The parsed {@code MOCK READY sha512=<hex> size=<n> ports=<p1,p2,p3>} line. */
    private record Ready(String sha512, long size, List<Integer> ports) {

        static Ready parse(String line) {
            String[] fields = line.split(" ");
            if (fields.length != 5 || !"MOCK".equals(fields[0]) || !"READY".equals(fields[1])) {
                throw new IllegalArgumentException("unreadable READY line: " + line);
            }
            List<Integer> ports = new ArrayList<>();
            for (String port : fields[4].substring("ports=".length()).split(",", -1)) {
                ports.add(Integer.parseInt(port));
            }
            return new Ready(fields[2].substring("sha512=".length()),
                    Long.parseLong(fields[3].substring("size=".length())), ports);
        }
    }
}
