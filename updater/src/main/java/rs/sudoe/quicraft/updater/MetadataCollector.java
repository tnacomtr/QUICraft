// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Fetches the revocations and release manifests from every configured host, in configured order.
 *
 * <p>For each host the document is fetched first, then its detached {@code .sig}. Both must succeed
 * for the host to yield {@link FetchOutcome#OK} evidence; otherwise the evidence carries the first
 * failing outcome and no pair. A failure on one host never stops the others. Once cancellation is
 * seen, or a fetch reports {@link FetchOutcome#CANCELLED}, every host not yet fetched is marked
 * {@link FetchOutcome#CANCELLED}. Fetches are never retried.
 *
 * <p>Nothing is parsed here and no URI is logged.
 */
final class MetadataCollector {

    /** Cap for the revocations document; equal to the parser's REVOCATIONS_MAX_BYTES. */
    static final long REVOCATIONS_MAX_BYTES = 65_536L;
    /** Cap for the release manifest; equal to the parser's RELEASE_MAX_BYTES. */
    static final long MANIFEST_MAX_BYTES = 65_536L;
    /** Cap for any detached {@code .sig}; equal to the parser's SIGNATURES_MAX_BYTES. */
    static final long SIGNATURE_MAX_BYTES = 4_096L;

    private final SourceFetcher fetcher;
    private final ReleaseEndpoints endpoints;
    private final Map<String, RedirectPolicy> redirectsByHostKey;
    private final FetchTimeouts timeouts;

    /**
     * @param fetcher the fetcher used for every request
     * @param endpoints maps each host to its document and signature URIs
     * @param redirectsByHostKey redirect policy per host key; a missing key means
     *     {@link RedirectPolicy#denyAll()}
     * @param timeouts the connect, idle and overall timeouts for every request
     */
    MetadataCollector(SourceFetcher fetcher, ReleaseEndpoints endpoints,
            Map<String, RedirectPolicy> redirectsByHostKey, FetchTimeouts timeouts) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.redirectsByHostKey = Map.copyOf(Objects.requireNonNull(redirectsByHostKey, "redirectsByHostKey"));
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    }

    /**
     * Collects the signed revocations document and its signature from every host.
     *
     * @param hosts the configured hosts
     * @param cancel the cancellation token
     * @return one evidence entry per configured host, in configured order
     */
    HostCollection collectRevocations(ReleaseHosts hosts, CancelToken cancel) {
        return collect(hosts, cancel, REVOCATIONS_MAX_BYTES,
                endpoints::revocations, endpoints::revocationsSignature);
    }

    /**
     * Collects the signed release manifest and its signature from every host.
     *
     * @param hosts the configured hosts
     * @param cancel the cancellation token
     * @return one evidence entry per configured host, in configured order
     */
    HostCollection collectManifests(ReleaseHosts hosts, CancelToken cancel) {
        return collect(hosts, cancel, MANIFEST_MAX_BYTES,
                endpoints::manifest, endpoints::manifestSignature);
    }

    private HostCollection collect(ReleaseHosts hosts, CancelToken cancel, long documentMaxBytes,
            Function<ReleaseHost, URI> documentUri, Function<ReleaseHost, URI> signatureUri) {
        Objects.requireNonNull(hosts, "hosts");
        Objects.requireNonNull(cancel, "cancel");
        List<ReleaseHost> configured = hosts.hosts();
        List<HostEvidence> evidence = new ArrayList<>(configured.size());
        boolean stopped = false;
        for (ReleaseHost host : configured) {
            if (stopped || cancel.isCancelled()) {
                evidence.add(new HostEvidence(host, FetchOutcome.CANCELLED, null));
                stopped = true;
                continue;
            }
            HostEvidence result = fetchPair(host, cancel, documentMaxBytes,
                    documentUri.apply(host), signatureUri.apply(host));
            evidence.add(result);
            if (result.outcome() == FetchOutcome.CANCELLED) {
                stopped = true;
            }
        }
        return new HostCollection(evidence);
    }

    private HostEvidence fetchPair(ReleaseHost host, CancelToken cancel, long documentMaxBytes,
            URI documentUri, URI signatureUri) {
        FetchedBytes document;
        try {
            document = fetcher.fetch(request(host, documentUri, documentMaxBytes), cancel);
        } catch (FetchException e) {
            return new HostEvidence(host, e.outcome(), null);
        }
        FetchedBytes signature;
        try {
            signature = fetcher.fetch(request(host, signatureUri, SIGNATURE_MAX_BYTES), cancel);
        } catch (FetchException e) {
            return new HostEvidence(host, e.outcome(), null);
        }
        return new HostEvidence(host, FetchOutcome.OK,
                new DocumentPair(document.bytes(), signature.bytes()));
    }

    private FetchRequest request(ReleaseHost host, URI uri, long maxBytes) {
        RedirectPolicy redirects = redirectsByHostKey.getOrDefault(host.key(), RedirectPolicy.denyAll());
        return new FetchRequest(host, uri, maxBytes,
                timeouts.connect(), timeouts.idle(), timeouts.overall(), redirects);
    }
}
