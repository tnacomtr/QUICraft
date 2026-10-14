// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fixtures for the update transaction: a synthetic release jar, a manifest whose entry matches it, and
 * a source fetcher that serves the artifact bytes. Uses only the RFC 8032 test keys through
 * {@link PipelineFixtures} and {@link SignedFixtures}.
 */
final class TransactionFixtures {

    static final String PLATFORM = "fabric-26x";
    static final String TARGET = "26.1";
    static final String RUNNING_VERSION = "0.3.0";
    static final String NEW_VERSION = "0.4.0";

    private static final String PAYLOAD_NAME = "payload.bin";

    private TransactionFixtures() {
    }

    /** A well-formed release jar with the trust file of the running trust and build metadata. */
    static byte[] releaseJar(String version) throws IOException {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + version.length());
        }
        return zip(entry(PAYLOAD_NAME, payload),
                entry(ArtifactVerifier.TRUST_ENTRY, bytes(SignedFixtures.trustJson())),
                entry(ArtifactVerifier.BUILD_ENTRY, bytes("{\"type\":\"quicraft.build\",\"v\":1,\"platform\":\""
                        + PLATFORM + "\",\"version\":\"" + version + "\"}")));
    }

    /** A signed release manifest with one entry for {@code jar}, targeting {@link #TARGET}. */
    static byte[] manifestFor(byte[] jar, String version) throws Exception {
        String json = "{\"type\":\"quicraft.release\",\"v\":1,\"seq\":42"
                + ",\"issued\":\"" + SignedFixtures.TS1 + "\",\"expires\":\"" + SignedFixtures.TS2 + "\""
                + ",\"minRevocationsSeq\":3,\"releases\":[{\"platform\":\"" + PLATFORM + "\""
                + ",\"version\":\"" + version + "\",\"targets\":[\"" + TARGET + "\"]"
                + ",\"file\":\"quicraft-" + PLATFORM + "-" + version + ".jar\""
                + ",\"size\":" + jar.length + ",\"sha512\":\"" + sha512Hex(jar) + "\""
                + ",\"tag\":\"v" + version + "\"}],\"withdrawn\":[]}";
        return SignedFixtures.bytes(json);
    }

    /**
     * Answers every host's metadata: the revocations document and signature, and the given manifest
     * with its release signature.
     */
    static void serveMetadata(PipelineFixtures.ScriptedFetcher metadata, byte[] revocations, byte[] manifest)
            throws GeneralSecurityException {
        for (ReleaseHost host : PipelineFixtures.HOSTS.hosts()) {
            metadata.answer(PipelineFixtures.ENDPOINTS.revocations(host), revocations);
            metadata.answer(PipelineFixtures.ENDPOINTS.revocationsSignature(host),
                    PipelineFixtures.revocationsSig(revocations));
            metadata.answer(PipelineFixtures.ENDPOINTS.manifest(host), manifest);
            metadata.answer(PipelineFixtures.ENDPOINTS.manifestSignature(host),
                    PipelineFixtures.manifestSig(manifest));
        }
    }

    static String sha512Hex(byte[] data) throws IOException {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-512").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * Source fetcher for the transaction tests. Metadata is delegated to a scripted fetcher; downloads
     * write {@link #body} unless a failure is scripted. {@link #beforeDownload} runs first, so a test
     * can cancel the transaction in the middle of a download.
     */
    static final class ArtifactFetcher implements SourceFetcher {
        final PipelineFixtures.ScriptedFetcher metadata;
        final List<String> downloads = new ArrayList<>();
        byte[] body;
        boolean failAll;
        int failFirst;
        Runnable beforeDownload = () -> { };

        ArtifactFetcher(PipelineFixtures.ScriptedFetcher metadata, byte[] body) {
            this.metadata = metadata;
            this.body = body;
        }

        @Override
        public FetchedBytes fetch(FetchRequest request, CancelToken cancel) throws FetchException {
            return metadata.fetch(request, cancel);
        }

        @Override
        public long download(FetchRequest request, WritableByteChannel destination, CancelToken cancel)
                throws FetchException {
            downloads.add(request.host().key());
            beforeDownload.run();
            if (failAll) {
                throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
            }
            if (failFirst > 0) {
                failFirst--;
                throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
            }
            ByteBuffer buf = ByteBuffer.wrap(body);
            try {
                while (buf.hasRemaining()) {
                    destination.write(buf);
                }
            } catch (IOException e) {
                throw new FetchException(FetchOutcome.TRANSPORT_ERROR);
            }
            return body.length;
        }
    }

    private record Entry(String name, byte[] data) {
    }

    private static Entry entry(String name, byte[] data) {
        return new Entry(name, data);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Entry e : entries) {
                ZipEntry z = new ZipEntry(e.name());
                zos.putNextEntry(z);
                zos.write(e.data());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** The revoked-key JSON array items for {@code count} synthetic keys starting at {@code from}. */
    static String[] revokedKeys(int from, int count) throws Exception {
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            String pub = Hex.sha256(("over-cap-" + (from + i)).getBytes(StandardCharsets.UTF_8));
            keys[i] = PipelineFixtures.revokedKey(Hex.kid(pub), pub);
        }
        return keys;
    }
}
