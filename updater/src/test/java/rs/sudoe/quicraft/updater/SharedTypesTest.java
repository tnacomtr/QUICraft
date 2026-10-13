// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Validation and copy-semantics tests for the Wave 3 shared types. */
class SharedTypesTest {
    private static final String SHA512 = "a".repeat(128);
    private static final String SHA512_UPPER = "A".repeat(128);

    private static ReleaseHost host(String key, String origin) {
        return new ReleaseHost(key, URI.create(origin));
    }

    private static UpdateDocuments.Trust trust() {
        return new UpdateDocuments.Trust(1, Instant.EPOCH, 0, 0, List.of());
    }

    private static UpdateVersion version(String text) throws Exception {
        return UpdateVersion.parse(text);
    }

    // ---- CancelToken ----

    @Test
    void mutableCancelTokenStartsClearAndStaysCancelled() {
        MutableCancelToken token = new MutableCancelToken();
        assertFalse(token.isCancelled());
        token.cancel();
        token.cancel();
        assertTrue(token.isCancelled());
    }

    // ---- ReleaseHost ----

    @Test
    void releaseHostNormalizesSchemeHostAndDefaultPort() {
        ReleaseHost h = host("sudoe", "HTTPS://Example.ORG:443/");
        assertEquals("https://example.org", h.origin().toString());
    }

    @Test
    void releaseHostKeepsNonDefaultPort() {
        assertEquals("https://example.org:8443", host("github", "https://Example.org:8443/").origin().toString());
    }

    @Test
    void releaseHostAcceptsOriginWithoutPathOrSlash() {
        assertEquals("https://example.org", host("modrinth", "https://example.org").origin().toString());
    }

    @Test
    void releaseHostRejectsUnknownKey() {
        assertThrows(IllegalArgumentException.class, () -> host("gitlab", "https://example.org"));
    }

    @Test
    void releaseHostRejectsNullComponents() {
        assertThrows(NullPointerException.class, () -> new ReleaseHost(null, URI.create("https://example.org")));
        assertThrows(NullPointerException.class, () -> new ReleaseHost("github", null));
    }

    @Test
    void releaseHostRejectsHttp() {
        assertThrows(IllegalArgumentException.class, () -> host("github", "http://example.org"));
    }

    @Test
    void releaseHostRejectsUserinfo() {
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://user@example.org"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://@example.org"));
    }

    @Test
    void releaseHostRejectsPath() {
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org/a"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org/a/"));
    }

    @Test
    void releaseHostRejectsQueryAndFragment() {
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org?"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org?x=1"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org#"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org#frag"));
    }

    @Test
    void releaseHostRejectsRelativeAndHostlessAndBadPort() {
        assertThrows(IllegalArgumentException.class, () -> host("github", "/relative"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https:///"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org:0"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org:70000"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.org:"));
    }

    @Test
    void releaseHostErrorMessageNeverEchoesUri() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> host("github", "https://secret-user@example.org/leak"));
        assertFalse(e.getMessage().contains("secret"));
        assertFalse(e.getMessage().contains("leak"));
    }

    // ---- ReleaseHosts ----

    @Test
    void releaseHostsAcceptsOneToThreeDistinctHosts() {
        assertEquals(1, new ReleaseHosts(List.of(host("sudoe", "https://a.example"))).hosts().size());
        assertEquals(3, new ReleaseHosts(List.of(
                host("sudoe", "https://a.example"),
                host("github", "https://b.example"),
                host("modrinth", "https://c.example"))).hosts().size());
    }

    @Test
    void releaseHostsRejectsZeroAndFourHosts() {
        assertThrows(IllegalArgumentException.class, () -> new ReleaseHosts(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseHosts(List.of(
                host("sudoe", "https://a.example"),
                host("github", "https://b.example"),
                host("modrinth", "https://c.example"),
                host("sudoe", "https://d.example"))));
    }

    @Test
    void releaseHostsRejectsDuplicateKey() {
        assertThrows(IllegalArgumentException.class, () -> new ReleaseHosts(List.of(
                host("sudoe", "https://a.example"),
                host("sudoe", "https://b.example"))));
    }

    @Test
    void releaseHostsRejectsDuplicateNormalizedOrigin() {
        assertThrows(IllegalArgumentException.class, () -> new ReleaseHosts(List.of(
                host("sudoe", "https://a.example"),
                host("github", "https://A.example:443/"))));
    }

    @Test
    void releaseHostsCopiesListAndFindsByKey() {
        List<ReleaseHost> source = new ArrayList<>();
        source.add(host("github", "https://a.example"));
        ReleaseHosts hosts = new ReleaseHosts(source);
        source.add(host("sudoe", "https://b.example"));
        assertEquals(1, hosts.hosts().size());
        assertEquals(Optional.of(hosts.hosts().get(0)), hosts.byKey("github"));
        assertEquals(Optional.empty(), hosts.byKey("sudoe"));
    }

    // ---- RedirectPolicy ----

    @Test
    void redirectPolicyDenyAllRefusesEveryHop() throws Exception {
        ReleaseHost h = host("github", "https://a.example");
        assertFalse(RedirectPolicy.denyAll().allows(h, URI.create("https://a.example/x"),
                URI.create("https://a.example/y")));
        assertEquals(3, RedirectPolicy.MAX_HOPS);
    }

    // ---- FetchRequest ----

    private static FetchRequest request(String uri, long maxBytes, Duration connect, Duration idle,
            Duration overall) {
        return new FetchRequest(host("github", "https://a.example"), URI.create(uri), maxBytes,
                connect, idle, overall, RedirectPolicy.denyAll());
    }

    @Test
    void fetchRequestAcceptsValidRequestAndMaxBytesBounds() {
        Duration d = Duration.ofSeconds(1);
        assertEquals(1L, request("https://a.example/x", 1L, d, d, d).maxBytes());
        assertEquals(1L << 31, request("https://a.example/x", 1L << 31, d, d, d).maxBytes());
    }

    @Test
    void fetchRequestRejectsBadMaxBytes() {
        Duration d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> request("https://a.example/x", 0L, d, d, d));
        assertThrows(IllegalArgumentException.class,
                () -> request("https://a.example/x", (1L << 31) + 1, d, d, d));
    }

    @Test
    void fetchRequestRejectsNonHttpsUri() {
        Duration d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> request("http://a.example/x", 10L, d, d, d));
        assertThrows(IllegalArgumentException.class, () -> request("/relative", 10L, d, d, d));
    }

    @Test
    void fetchRequestRejectsNonPositiveDurations() {
        Duration d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class,
                () -> request("https://a.example/x", 10L, Duration.ZERO, d, d));
        assertThrows(IllegalArgumentException.class,
                () -> request("https://a.example/x", 10L, d, Duration.ofSeconds(-1), d));
        assertThrows(IllegalArgumentException.class,
                () -> request("https://a.example/x", 10L, d, d, Duration.ZERO));
    }

    @Test
    void fetchRequestRejectsNullRedirects() {
        Duration d = Duration.ofSeconds(1);
        assertThrows(NullPointerException.class, () -> new FetchRequest(host("github", "https://a.example"),
                URI.create("https://a.example/x"), 10L, d, d, d, null));
    }

    // ---- FetchException / FetchOutcome ----

    @Test
    void fetchExceptionRejectsOkAndKeepsOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new FetchException(FetchOutcome.OK));
        assertThrows(NullPointerException.class, () -> new FetchException(null));
        FetchException e = new FetchException(FetchOutcome.OVERSIZE);
        assertEquals(FetchOutcome.OVERSIZE, e.outcome());
        assertFalse(e.getMessage().contains("OK"));
    }

    @Test
    void fetchOutcomeHasTheSevenDeclaredValues() {
        assertEquals(7, FetchOutcome.values().length);
    }

    // ---- FetchedBytes ----

    @Test
    void fetchedBytesCopiesInAndOut() {
        byte[] source = {1, 2, 3};
        FetchedBytes fetched = new FetchedBytes(source);
        source[0] = 9;
        assertEquals(1, fetched.bytes()[0]);
        byte[] out = fetched.bytes();
        out[1] = 9;
        assertEquals(2, fetched.bytes()[1]);
        assertEquals(3, fetched.size());
    }

    // ---- SourceFetcher (contract only: a channel that receives bytes) ----

    @Test
    void sourceFetcherContractLeavesDestinationWithCallerOwnership() throws Exception {
        SourceFetcher fake = new SourceFetcher() {
            @Override
            public FetchedBytes fetch(FetchRequest request, CancelToken cancel) {
                return new FetchedBytes(new byte[] {7});
            }

            @Override
            public long download(FetchRequest request, java.nio.channels.WritableByteChannel destination,
                    CancelToken cancel) throws FetchException {
                try {
                    return destination.write(ByteBuffer.wrap(new byte[] {7, 8}));
                } catch (java.io.IOException e) {
                    throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
                }
            }
        };
        Duration d = Duration.ofSeconds(1);
        FetchRequest r = request("https://a.example/x", 10L, d, d, d);
        assertEquals(2L, fake.download(r, Channels.newChannel(new java.io.ByteArrayOutputStream()),
                new MutableCancelToken()));
        assertEquals(1, fake.fetch(r, new MutableCancelToken()).size());
    }

    // ---- DocumentPair ----

    @Test
    void documentPairCopiesBothArraysInAndOut() {
        byte[] doc = {1, 2};
        byte[] sig = {3, 4};
        DocumentPair pair = new DocumentPair(doc, sig);
        doc[0] = 9;
        sig[0] = 9;
        assertEquals(1, pair.documentBytes()[0]);
        assertEquals(3, pair.signatureBytes()[0]);
        pair.documentBytes()[1] = 9;
        assertEquals(2, pair.documentBytes()[1]);
    }

    @Test
    void documentPairEqualsByContentAndToStringHidesBytes() {
        DocumentPair a = new DocumentPair("ZQXPAYLOAD".getBytes(), "ZQXSIGNED".getBytes());
        DocumentPair b = new DocumentPair("ZQXPAYLOAD".getBytes(), "ZQXSIGNED".getBytes());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new DocumentPair("ZQXPAYLOAD".getBytes(), "xyz".getBytes()));
        assertFalse(a.toString().contains("ZQXPAYLOAD"));
        assertFalse(a.toString().contains("ZQXSIGNED"));
    }

    // ---- HostEvidence / HostCollection ----

    @Test
    void hostEvidenceNeedsPairExactlyWhenOk() {
        ReleaseHost h = host("github", "https://a.example");
        DocumentPair pair = new DocumentPair(new byte[] {1}, new byte[] {2});
        assertThrows(IllegalArgumentException.class, () -> new HostEvidence(h, FetchOutcome.OK, null));
        assertThrows(IllegalArgumentException.class, () -> new HostEvidence(h, FetchOutcome.TIMEOUT, pair));
        assertEquals(pair, new HostEvidence(h, FetchOutcome.OK, pair).pair());
        assertEquals(null, new HostEvidence(h, FetchOutcome.HTTP_STATUS, null).pair());
    }

    @Test
    void hostCollectionRejectsDuplicateHostAndCopiesList() {
        ReleaseHost a = host("github", "https://a.example");
        ReleaseHost b = host("sudoe", "https://b.example");
        HostEvidence evA = new HostEvidence(a, FetchOutcome.TRANSPORT_ERROR, null);
        HostEvidence evB = new HostEvidence(b, FetchOutcome.CANCELLED, null);
        assertThrows(IllegalArgumentException.class, () -> new HostCollection(List.of(evA, evA)));
        List<HostEvidence> source = new ArrayList<>(List.of(evA));
        HostCollection collection = new HostCollection(source);
        source.add(evB);
        assertEquals(1, collection.responses().size());
        assertEquals(2, new HostCollection(List.of(evA, evB)).responses().size());
    }

    // ---- RuntimeIdentity / ArtifactIdentity ----

    @Test
    void runtimeIdentityValidatesPlatformTargetAndHash() throws Exception {
        UpdateVersion v = version("1.2.3");
        RuntimeIdentity ok = new RuntimeIdentity("velocity", "proxy", v, trust(), SHA512);
        assertEquals("velocity", ok.platform());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeIdentity("forge", "proxy", v, trust(), SHA512));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeIdentity("fabric-26x", "", v, trust(), SHA512));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeIdentity("fabric-26x", "server", v, trust(), SHA512_UPPER));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeIdentity("fabric-26x", "server", v, trust(), "a".repeat(127)));
        assertThrows(NullPointerException.class,
                () -> new RuntimeIdentity("fabric-26x", "server", null, trust(), SHA512));
    }

    @Test
    void artifactIdentityValidatesSizePlatformAndHash() throws Exception {
        UpdateVersion v = version("2.0.0");
        assertEquals(5L, new ArtifactIdentity("fabric-1.21.11", v, 5L, SHA512).size());
        assertThrows(IllegalArgumentException.class, () -> new ArtifactIdentity("velocity", v, 0L, SHA512));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactIdentity("velocity", v, -1L, SHA512));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactIdentity("velocity", v, 1L, "b".repeat(127)));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactIdentity("paper", v, 1L, SHA512));
    }

    // ---- ManifestEvidence ----

    @Test
    void manifestEvidenceCopiesInAndOutAndHidesBytes() {
        byte[] manifest = "ZQXMANIFEST".getBytes();
        byte[] signature = "ZQXSIGNATURE".getBytes();
        ManifestEvidence evidence = new ManifestEvidence(manifest, signature);
        manifest[0] = 0;
        signature[0] = 0;
        assertEquals("ZQXMANIFEST", new String(evidence.exactManifestBytes()));
        assertEquals("ZQXSIGNATURE", new String(evidence.exactSignatureBytes()));
        assertEquals(evidence, new ManifestEvidence("ZQXMANIFEST".getBytes(), "ZQXSIGNATURE".getBytes()));
        assertFalse(evidence.toString().contains("ZQXMANIFEST"));
        assertFalse(evidence.toString().contains("ZQXSIGNATURE"));
    }

    // ---- Enums and interfaces ----

    @Test
    void installCapabilityHasBothValues() {
        assertEquals(2, InstallCapability.values().length);
        assertEquals(InstallCapability.VERIFIED_STAGING_ONLY, InstallCapability.valueOf("VERIFIED_STAGING_ONLY"));
        assertEquals(InstallCapability.ATOMIC_REPLACEMENT, InstallCapability.valueOf("ATOMIC_REPLACEMENT"));
    }

    @Test
    void initBarrierAndHealthSignalAreImplementable() throws Exception {
        InitBarrier barrier = () -> true;
        assertTrue(barrier.readyForStaging());
        HealthSignal signal = timeout -> java.util.concurrent.CompletableFuture.completedFuture(Boolean.TRUE);
        assertTrue(signal.awaitHealthy(Duration.ofSeconds(1)).toCompletableFuture().get());
    }

    // ---- ReleaseHost LDH rules (ADJUST) ----

    @Test
    void releaseHostRejectsTrailingDotAndEmptyLabels() {
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://example.com."));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://a.com.."));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://a..com"));
    }

    @Test
    void releaseHostRejectsIpLiteralsAndZoneIds() {
        String[] bad = {
            "https://[::1]", "https://[0:0:0:0:0:0:0:1]", "https://[::ffff:1.2.3.4]",
            "https://[::1%25eth0]", "https://1.2.3.4", "https://127.0.0.1", "https://127.0.0.1:8443",
        };
        for (String origin : bad) {
            assertThrows(IllegalArgumentException.class, () -> host("github", origin), origin);
        }
    }

    @Test
    void releaseHostRejectsUnderscoreHyphenAndLabelRules() {
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://a_b.com"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://-a.com"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://a-.com"));
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://" + "x".repeat(64) + ".com"));
        String longName = ("x".repeat(63) + ".").repeat(3) + "x".repeat(63);
        assertThrows(IllegalArgumentException.class, () -> host("github", "https://" + longName));
    }

    @Test
    void releaseHostAcceptsLdhNamesWithMixedLastLabel() {
        assertEquals("https://a.1a.com", host("github", "https://a.1a.com").origin().toString());
    }

    @Test
    void releaseHostNormalizesCaseAndPortSpellings() {
        assertEquals("https://example.com", host("sudoe", "https://EXAMPLE.com:443").origin().toString());
        assertEquals("https://example.com", host("sudoe", "https://example.com:0443").origin().toString());
    }

    @Test
    void releaseHostAcceptsLocalhostOnlyAsSingleLabel() {
        assertEquals("https://localhost:8443", host("sudoe", "https://localhost:8443").origin().toString());
        assertEquals("https://localhost", host("sudoe", "https://localhost:443").origin().toString());
        assertEquals("https://localhost", host("sudoe", "https://LOCALHOST").origin().toString());
        assertThrows(IllegalArgumentException.class, () -> host("sudoe", "https://localhostx"));
        assertThrows(IllegalArgumentException.class, () -> host("sudoe", "https://localhost."));
    }

    // ---- ReleaseHosts.contains / of (ADJUST) ----

    @Test
    void releaseHostsContainsRequiresKeyAndOrigin() {
        ReleaseHosts hosts = ReleaseHosts.of(List.of(host("github", "https://a.example")));
        assertTrue(hosts.contains(host("github", "https://a.example")));
        assertFalse(hosts.contains(host("github", "https://b.example")));
        assertFalse(hosts.contains(host("sudoe", "https://a.example")));
    }

    @Test
    void releaseHostsOfFollowsConstructorRules() {
        assertEquals(1, ReleaseHosts.of(List.of(host("github", "https://a.example"))).hosts().size());
        assertThrows(IllegalArgumentException.class, () -> ReleaseHosts.of(List.of()));
    }

    // ---- HostCollection origin uniqueness (ADJUST) ----

    @Test
    void hostCollectionRejectsDuplicateOriginUnderDifferentKeys() {
        HostEvidence a = new HostEvidence(host("github", "https://a.example"), FetchOutcome.TRANSPORT_ERROR, null);
        HostEvidence b = new HostEvidence(host("sudoe", "https://a.example"), FetchOutcome.CANCELLED, null);
        assertThrows(IllegalArgumentException.class, () -> new HostCollection(List.of(a, b)));
    }

    // ---- FetchRequest URI binding and ceilings (ADJUST) ----

    @Test
    void fetchRequestRejectsUriOfAnotherHostOrPort() {
        Duration d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> request("https://b.example/x", 10L, d, d, d));
        assertThrows(IllegalArgumentException.class, () -> request("https://a.example:8443/x", 10L, d, d, d));
        assertThrows(IllegalArgumentException.class, () -> request("https://sub.a.example/x", 10L, d, d, d));
    }

    @Test
    void fetchRequestAcceptsUriEqualAfterNormalization() {
        Duration d = Duration.ofSeconds(1);
        assertEquals("https://A.example:443/x", request("https://A.example:443/x", 10L, d, d, d).uri().toString());
    }

    @Test
    void fetchRequestRejectsUserinfoAndFragment() {
        Duration d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> request("https://u@a.example/x", 10L, d, d, d));
        assertThrows(IllegalArgumentException.class, () -> request("https://a.example/x#frag", 10L, d, d, d));
        assertThrows(IllegalArgumentException.class, () -> request("https://a.example/x#", 10L, d, d, d));
    }

    @Test
    void fetchRequestEnforcesTimeoutCeilings() {
        Duration d = Duration.ofSeconds(1);
        assertEquals(FetchRequest.MAX_CONNECT,
                request("https://a.example/x", 10L, FetchRequest.MAX_CONNECT, d, d).connectTimeout());
        assertThrows(IllegalArgumentException.class, () -> request("https://a.example/x", 10L,
                FetchRequest.MAX_CONNECT.plusMillis(1), d, d));
        assertThrows(IllegalArgumentException.class, () -> request("https://a.example/x", 10L, d,
                FetchRequest.MAX_IDLE.plusMillis(1), d));
        assertEquals(FetchRequest.MAX_DEADLINE,
                request("https://a.example/x", 10L, d, d, FetchRequest.MAX_DEADLINE).overallDeadline());
        assertThrows(IllegalArgumentException.class, () -> request("https://a.example/x", 10L, d, d,
                FetchRequest.MAX_DEADLINE.plusMillis(1)));
    }

    @Test
    void fetchRequestByteCeilingsAndConstants() {
        Duration d = Duration.ofSeconds(1);
        assertEquals(1L << 31, FetchRequest.MAX_BYTES);
        assertEquals(64L << 20, FetchRequest.MAX_FETCH_BYTES);
        assertEquals(FetchRequest.MAX_BYTES, request("https://a.example/x", FetchRequest.MAX_BYTES, d, d, d).maxBytes());
        // A request above MAX_FETCH_BYTES is still a valid request; fetch() refuses it with OVERSIZE.
        assertEquals(FetchRequest.MAX_FETCH_BYTES + 1,
                request("https://a.example/x", FetchRequest.MAX_FETCH_BYTES + 1, d, d, d).maxBytes());
    }

    // ---- FetchException httpStatus (ADJUST) ----

    @Test
    void fetchExceptionCarriesOptionalHttpStatus() {
        assertEquals(-1, new FetchException(FetchOutcome.TRANSPORT_ERROR).httpStatus());
        assertEquals(503, new FetchException(FetchOutcome.HTTP_STATUS, 503).httpStatus());
        assertThrows(IllegalArgumentException.class, () -> new FetchException(FetchOutcome.HTTP_STATUS, 99));
        assertThrows(IllegalArgumentException.class, () -> new FetchException(FetchOutcome.HTTP_STATUS, 600));
        assertThrows(IllegalArgumentException.class, () -> new FetchException(FetchOutcome.OK, 200));
    }
}
