// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;

/**
 * Maps a release host to the URIs of its metadata and artifacts. Injected so the concrete paths
 * (fixed by the release layout, not by this type) live in one place.
 */
public interface ReleaseEndpoints {

    /** @return the URI of the signed revocations document on the given host */
    URI revocations(ReleaseHost host);

    /** @return the URI of the detached signature for the revocations document */
    URI revocationsSignature(ReleaseHost host);

    /** @return the URI of the signed release manifest on the given host */
    URI manifest(ReleaseHost host);

    /** @return the URI of the detached signature for the release manifest */
    URI manifestSignature(ReleaseHost host);

    /** @return the URI of the artifact described by the release entry on the given host */
    URI artifact(ReleaseHost host, UpdateDocuments.ReleaseEntry entry);
}
