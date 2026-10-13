// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MetadataCollectorTest {

    private static final ReleaseHost MODRINTH = host("modrinth", "https://modrinth.example");
    private static final ReleaseHost GITHUB = host("github", "https://github.example");
    private static final ReleaseHost SUDOE = host("sudoe", "https://sudoe.example");
    private static final ReleaseHosts HOSTS = ReleaseHosts.of(List.of(MODRINTH, GITHUB, SUDOE));

    private static final byte[] REV_DOC = bytes("revocations-doc");
    private static final byte[] REV_SIG = bytes("revocations-sig");
    private static final byte[] MAN_DOC = bytes("manifest-doc");
    private static final byte[] MAN_SIG = bytes("manifest-sig");

    private static ReleaseHost host(String key, String origin) {
        return new ReleaseHost(key, URI.create(origin));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Endpoints that give every host the same fixed paths under its own origin. */
    private static final ReleaseEndpoints ENDPOINTS = new ReleaseEndpoints() {
        @Override
        public URI revocations(ReleaseHost host) {
            return uri(host, "/revocations.json");
        }

        @Override
        public URI revocationsSignature(ReleaseHost host) {
            return uri(host, "/revocations.json.sig");
        }

        @Override
        public URI manifest(ReleaseHost host) {
            return uri(host, "/manifest.json");
        }

        @Override
        public URI manifestSignature(ReleaseHost host) {
            return uri(host, "/manifest.json.sig");
        }

        @Override
        public URI artifact(ReleaseHost host, UpdateDocuments.ReleaseEntry entry) {
            return uri(host, "/artifact.jar");
        }

        private URI uri(ReleaseHost host, String path) {
            return URI.create(host.origin().toString() + path);
        }
    };

    /** A fetcher that answers from a script and records every request in order. */
    private static final class ScriptedFetcher implements SourceFetcher {
        private final Map<URI, Object> script = new HashMap<>();
        final List<FetchRequest> requests = new ArrayList<>();
        Consumer<URI> onFetch = uri -> { };

        void answer(URI uri, byte[] body) {
            script.put(uri, new FetchedBytes(body));
        }

        void fail(URI uri, FetchOutcome outcome) {
            script.put(uri, new FetchException(outcome));
        }

        @Override
        public FetchedBytes fetch(FetchRequest request, CancelToken cancel) throws FetchException {
            requests.add(request);
            onFetch.accept(request.uri());
            Object scripted = script.get(request.uri());
            if (scripted instanceof FetchException e) {
                throw e;
            }
            if (scripted == null) {
                throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
            }
            return (FetchedBytes) scripted;
        }

        @Override
        public long download(FetchRequest request, WritableByteChannel destination, CancelToken cancel)
                throws FetchException {
            throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
        }

        List<URI> uris() {
            List<URI> out = new ArrayList<>();
            for (FetchRequest request : requests) {
                out.add(request.uri());
            }
            return out;
        }
    }

    private static ScriptedFetcher allHostsOkFetcher() {
        ScriptedFetcher fetcher = new ScriptedFetcher();
        for (ReleaseHost host : HOSTS.hosts()) {
            fetcher.answer(ENDPOINTS.revocations(host), REV_DOC);
            fetcher.answer(ENDPOINTS.revocationsSignature(host), REV_SIG);
            fetcher.answer(ENDPOINTS.manifest(host), MAN_DOC);
            fetcher.answer(ENDPOINTS.manifestSignature(host), MAN_SIG);
        }
        return fetcher;
    }

    private static MetadataCollector collector(SourceFetcher fetcher) {
        return new MetadataCollector(fetcher, ENDPOINTS, Map.of(), FetchTimeouts.defaults());
    }

    private static HostEvidence evidenceFor(HostCollection collection, ReleaseHost host) {
        return collection.responses().stream()
                .filter(e -> e.host().key().equals(host.key()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void allHostsOkYieldsPairsInConfiguredOrder() {
        HostCollection collection = collector(allHostsOkFetcher())
                .collectRevocations(HOSTS, new MutableCancelToken());

        assertEquals(List.of("modrinth", "github", "sudoe"),
                collection.responses().stream().map(e -> e.host().key()).toList());
        for (HostEvidence evidence : collection.responses()) {
            assertEquals(FetchOutcome.OK, evidence.outcome());
            assertArrayEquals(REV_DOC, evidence.pair().documentBytes());
            assertArrayEquals(REV_SIG, evidence.pair().signatureBytes());
        }
    }

    @Test
    void manifestCollectionUsesManifestPairs() {
        HostCollection collection = collector(allHostsOkFetcher())
                .collectManifests(HOSTS, new MutableCancelToken());

        for (HostEvidence evidence : collection.responses()) {
            assertEquals(FetchOutcome.OK, evidence.outcome());
            assertArrayEquals(MAN_DOC, evidence.pair().documentBytes());
            assertArrayEquals(MAN_SIG, evidence.pair().signatureBytes());
        }
    }

    @Test
    void revocationCollectionFetchesOnlyRevocationUris() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        collector(fetcher).collectRevocations(HOSTS, new MutableCancelToken());

        List<URI> expected = new ArrayList<>();
        for (ReleaseHost host : HOSTS.hosts()) {
            expected.add(ENDPOINTS.revocations(host));
            expected.add(ENDPOINTS.revocationsSignature(host));
        }
        assertEquals(expected, fetcher.uris());
    }

    @Test
    void documentFailureOnOneHostDoesNotStopOthersAndSkipsItsSignature() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        fetcher.fail(ENDPOINTS.revocations(GITHUB), FetchOutcome.HTTP_STATUS);

        HostCollection collection = collector(fetcher)
                .collectRevocations(HOSTS, new MutableCancelToken());

        HostEvidence github = evidenceFor(collection, GITHUB);
        assertEquals(FetchOutcome.HTTP_STATUS, github.outcome());
        assertNull(github.pair());
        assertFalse(fetcher.uris().contains(ENDPOINTS.revocationsSignature(GITHUB)));
        assertEquals(FetchOutcome.OK, evidenceFor(collection, MODRINTH).outcome());
        assertEquals(FetchOutcome.OK, evidenceFor(collection, SUDOE).outcome());
    }

    @Test
    void signatureFailureOnOneHostMakesThatHostFail() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        fetcher.fail(ENDPOINTS.revocationsSignature(SUDOE), FetchOutcome.TIMEOUT);

        HostCollection collection = collector(fetcher)
                .collectRevocations(HOSTS, new MutableCancelToken());

        HostEvidence sudoe = evidenceFor(collection, SUDOE);
        assertEquals(FetchOutcome.TIMEOUT, sudoe.outcome());
        assertNull(sudoe.pair());
        assertEquals(FetchOutcome.OK, evidenceFor(collection, MODRINTH).outcome());
        assertEquals(FetchOutcome.OK, evidenceFor(collection, GITHUB).outcome());
    }

    @Test
    void cancelAfterFirstHostMarksRemainingHostsCancelledWithoutFetching() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        MutableCancelToken cancel = new MutableCancelToken();
        URI firstSignature = ENDPOINTS.revocationsSignature(MODRINTH);
        fetcher.onFetch = uri -> {
            if (uri.equals(firstSignature)) {
                cancel.cancel();
            }
        };

        HostCollection collection = collector(fetcher).collectRevocations(HOSTS, cancel);

        assertEquals(FetchOutcome.OK, evidenceFor(collection, MODRINTH).outcome());
        assertEquals(FetchOutcome.CANCELLED, evidenceFor(collection, GITHUB).outcome());
        assertNull(evidenceFor(collection, GITHUB).pair());
        assertEquals(FetchOutcome.CANCELLED, evidenceFor(collection, SUDOE).outcome());
        assertEquals(2, fetcher.requests.size());
    }

    @Test
    void alreadyCancelledTokenMarksEveryHostCancelledAndFetchesNothing() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        MutableCancelToken cancel = new MutableCancelToken();
        cancel.cancel();

        HostCollection collection = collector(fetcher).collectManifests(HOSTS, cancel);

        assertEquals(3, collection.responses().size());
        for (HostEvidence evidence : collection.responses()) {
            assertEquals(FetchOutcome.CANCELLED, evidence.outcome());
            assertNull(evidence.pair());
        }
        assertTrue(fetcher.requests.isEmpty());
    }

    @Test
    void fetcherReportingCancelledStopsLaterHosts() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        fetcher.fail(ENDPOINTS.revocations(MODRINTH), FetchOutcome.CANCELLED);

        HostCollection collection = collector(fetcher)
                .collectRevocations(HOSTS, new MutableCancelToken());

        assertEquals(FetchOutcome.CANCELLED, evidenceFor(collection, MODRINTH).outcome());
        assertEquals(FetchOutcome.CANCELLED, evidenceFor(collection, GITHUB).outcome());
        assertEquals(FetchOutcome.CANCELLED, evidenceFor(collection, SUDOE).outcome());
        assertEquals(1, fetcher.requests.size());
    }

    @Test
    void revocationsUseDocumentCapOf65536AndSignatureCapOf4096() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        collector(fetcher).collectRevocations(HOSTS, new MutableCancelToken());

        assertEquals(65_536L, MetadataCollector.REVOCATIONS_MAX_BYTES);
        for (int i = 0; i < fetcher.requests.size(); i++) {
            FetchRequest request = fetcher.requests.get(i);
            long expected = i % 2 == 0 ? 65_536L : 4_096L;
            assertEquals(expected, request.maxBytes(), "request " + i);
        }
    }

    @Test
    void manifestsUseDocumentCapOf65536AndSignatureCapOf4096() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        collector(fetcher).collectManifests(HOSTS, new MutableCancelToken());

        assertEquals(65_536L, MetadataCollector.MANIFEST_MAX_BYTES);
        assertEquals(4_096L, MetadataCollector.SIGNATURE_MAX_BYTES);
        for (int i = 0; i < fetcher.requests.size(); i++) {
            FetchRequest request = fetcher.requests.get(i);
            long expected = i % 2 == 0 ? 65_536L : 4_096L;
            assertEquals(expected, request.maxBytes(), "request " + i);
        }
    }

    @Test
    void redirectPolicyIsPassedPerHostKeyAndDefaultsToDenyAll() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        RedirectPolicy allowGithub = (host, from, to) -> true;
        MetadataCollector collector = new MetadataCollector(fetcher, ENDPOINTS,
                Map.of("github", allowGithub), FetchTimeouts.defaults());

        collector.collectRevocations(HOSTS, new MutableCancelToken());

        for (FetchRequest request : fetcher.requests) {
            URI to = URI.create("https://elsewhere.example/x");
            if (request.host().key().equals("github")) {
                assertSame(allowGithub, request.redirects());
                assertTrue(request.redirects().allows(GITHUB, request.uri(), to));
            } else {
                assertFalse(request.redirects().allows(request.host(), request.uri(), to));
            }
        }
    }

    @Test
    void missingRedirectKeyDeniesEveryRedirect() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        collector(fetcher).collectManifests(HOSTS, new MutableCancelToken());

        URI to = URI.create("https://elsewhere.example/x");
        for (FetchRequest request : fetcher.requests) {
            assertFalse(request.redirects().allows(request.host(), request.uri(), to));
        }
    }

    @Test
    void defaultTimeoutsAreTenFifteenAndSixtySeconds() {
        FetchTimeouts defaults = FetchTimeouts.defaults();

        assertEquals(Duration.ofSeconds(10), defaults.connect());
        assertEquals(Duration.ofSeconds(15), defaults.idle());
        assertEquals(Duration.ofSeconds(60), defaults.overall());
    }

    @Test
    void timeoutsArePassedIntoEveryRequest() {
        ScriptedFetcher fetcher = allHostsOkFetcher();
        FetchTimeouts custom = new FetchTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(4),
                Duration.ofSeconds(5));
        new MetadataCollector(fetcher, ENDPOINTS, Map.of(), custom)
                .collectRevocations(HOSTS, new MutableCancelToken());

        for (FetchRequest request : fetcher.requests) {
            assertEquals(Duration.ofSeconds(3), request.connectTimeout());
            assertEquals(Duration.ofSeconds(4), request.idleReadTimeout());
            assertEquals(Duration.ofSeconds(5), request.overallDeadline());
        }
    }

    @Test
    void timeoutsRejectZeroNegativeAndOverMaximum() {
        Duration ten = Duration.ofSeconds(10);
        assertThrows(IllegalArgumentException.class,
                () -> new FetchTimeouts(Duration.ZERO, ten, ten));
        assertThrows(IllegalArgumentException.class,
                () -> new FetchTimeouts(ten, Duration.ofSeconds(-1), ten));
        assertThrows(IllegalArgumentException.class,
                () -> new FetchTimeouts(ten, ten, FetchRequest.MAX_DEADLINE.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new FetchTimeouts(FetchRequest.MAX_CONNECT.plusSeconds(1), ten, ten));
        assertThrows(IllegalArgumentException.class,
                () -> new FetchTimeouts(ten, FetchRequest.MAX_IDLE.plusSeconds(1), ten));
    }

    @Test
    void timeoutsAcceptExactMaximum() {
        FetchTimeouts max = new FetchTimeouts(FetchRequest.MAX_CONNECT, FetchRequest.MAX_IDLE,
                FetchRequest.MAX_DEADLINE);
        assertEquals(FetchRequest.MAX_DEADLINE, max.overall());
    }
}
