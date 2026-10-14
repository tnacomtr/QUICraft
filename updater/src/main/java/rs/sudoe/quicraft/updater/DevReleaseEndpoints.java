// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.util.Objects;

/**
 * The endpoint layout of a release host: the documents, their signatures and the artifacts under the
 * host's origin. Used with the dev origins; the production layout is decided with blocker 5.
 */
public final class DevReleaseEndpoints implements ReleaseEndpoints {

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
        Objects.requireNonNull(entry, "entry");
        return uri(host, "/artifact/" + entry.sha512() + ".jar");
    }

    private static URI uri(ReleaseHost host, String path) {
        Objects.requireNonNull(host, "host");
        return URI.create(host.origin().toString() + path);
    }
}
