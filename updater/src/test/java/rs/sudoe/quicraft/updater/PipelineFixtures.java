// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Shared fixtures for {@link CheckPipeline}, {@link UpdateAdmission} and {@link StateReset} tests.
 * Documents are signed only with the throwaway RFC 8032 TEST 1 (release) and TEST 2 (revocation)
 * seeds from {@link TestKeys}, and they are verified by the production code.
 */
final class PipelineFixtures {

    static final ReleaseHost MOD = new ReleaseHost("modrinth", URI.create("https://modrinth.example"));
    static final ReleaseHost GH = new ReleaseHost("github", URI.create("https://github.example"));
    static final ReleaseHost SU = new ReleaseHost("sudoe", URI.create("https://sudoe.example"));
    static final ReleaseHosts HOSTS = ReleaseHosts.of(List.of(MOD, GH, SU));

    static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String ISSUED = "2026-11-01T12:00:00Z";
    static final String REV_EXPIRES = "2027-11-01T12:00:00Z";
    static final String RUNNING_SHA512 = "ab".repeat(64);

    static final ReleaseEndpoints ENDPOINTS = new ReleaseEndpoints() {
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

    private PipelineFixtures() {
    }

    /** Answers from a script and records every request in order. */
    static final class ScriptedFetcher implements SourceFetcher {
        private final Map<URI, Object> script = new HashMap<>();
        final List<FetchRequest> requests = new ArrayList<>();
        Consumer<URI> onFetch = uri -> { };

        void answer(URI uri, byte[] body) {
            script.put(uri, new FetchedBytes(body));
        }

        void fail(URI uri, FetchOutcome outcome) {
            script.put(uri, new FetchException(outcome));
        }

        /** Answers both documents and both signatures of every host with the given bytes. */
        void answerEveryHost(byte[] revocations, byte[] manifest) throws GeneralSecurityException {
            for (ReleaseHost host : HOSTS.hosts()) {
                answer(ENDPOINTS.revocations(host), revocations);
                answer(ENDPOINTS.revocationsSignature(host), revocationsSig(revocations));
                answer(ENDPOINTS.manifest(host), manifest);
                answer(ENDPOINTS.manifestSignature(host), manifestSig(manifest));
            }
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
    }

    static MetadataCollector collector(SourceFetcher fetcher) {
        return new MetadataCollector(fetcher, ENDPOINTS, Map.of(), FetchTimeouts.defaults());
    }

    static UpdateDocuments.Trust trust() throws Exception {
        return DocumentParser.parseTrust(SignedFixtures.bytes(SignedFixtures.trustJson()));
    }

    static RuntimeIdentity identity() throws Exception {
        return new RuntimeIdentity("fabric-26x", "26.1", UpdateVersion.parse("0.3.0"), trust(),
                RUNNING_SHA512);
    }

    /** The state that a first run writes for the running trust file. */
    static StateRecords.UpdaterState seedState() throws Exception {
        UpdateDocuments.Trust trust = trust();
        return TrustTransition.apply(TrustTransition.classify(null, trust), trust, null).newState();
    }

    /** Revocations document, seq 3, revoking the given (kid, pub) pairs. */
    static byte[] revocations(String... revokedKeysJson) {
        return SignedFixtures.bytes("{\"type\":\"quicraft.revocations\",\"v\":1,\"seq\":3"
                + ",\"issued\":\"" + ISSUED + "\",\"expires\":\"" + REV_EXPIRES + "\""
                + ",\"minManifestSeq\":0,\"revokedKeys\":[" + String.join(",", revokedKeysJson) + "]"
                + ",\"blockedArtifacts\":[]}");
    }

    /** The JSON for one revoked key, given as a kid and pub pair. */
    static String revokedKey(String kid, String pub) {
        return "{\"kid\":\"" + kid + "\",\"pub\":\"" + pub + "\"}";
    }

    /** The standard release manifest (seq 42, release key TEST 1). */
    static byte[] manifest() {
        return SignedFixtures.bytes(SignedFixtures.releaseJson());
    }

    static byte[] revocationsSig(byte[] document) throws GeneralSecurityException {
        return SignedFixtures.envelopeFor(document,
                SignedFixtures.signedEntry(TestKeys.KID_2, TestKeys.SEED_2, document));
    }

    static byte[] manifestSig(byte[] document) throws GeneralSecurityException {
        return SignedFixtures.envelopeFor(document,
                SignedFixtures.signedEntry(TestKeys.KID_1, TestKeys.SEED_1, document));
    }

    /** A private directory for the store, created the way the store creates its own. */
    static Path privateDir(Path p) throws IOException {
        Files.createDirectories(p);
        if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        }
        return p;
    }

    /** Writes a file with owner-only permissions on POSIX. */
    static void writePrivate(Path p, byte[] bytes) throws IOException {
        Files.write(p, bytes);
        if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        }
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
