// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The endpoint layout of a real release host (docs/updater.md 12.15, blocker 5).
 *
 * <p>Metadata lives under the {@code updater-metadata} tag of the host's own repository:
 * {@code <origin><repo>/releases/download/updater-metadata/<name>} for {@code release.v1.json},
 * {@code release.v1.json.sig}, {@code revocations.v1.json} and {@code revocations.v1.json.sig}.
 * That release is replaced on every publish, so the four files always belong to one publish.
 *
 * <p>An artifact lives under its release tag:
 * {@code <origin><repo>/releases/download/<tag>/<file>}. The tag and the file name come from a
 * signed manifest but are re-checked here before they reach a URI (defence in depth): the tag is
 * {@code v[0-9A-Za-z.-]{1,40}} and the file is {@code [A-Za-z0-9._+-]{1,124}.jar}. A host key with
 * no repository path is refused. Every rejection raises {@link IllegalArgumentException} with a
 * fixed message that never echoes the value.
 */
public final class ProductionReleaseEndpoints implements ReleaseEndpoints {

    /** The release tag of the four metadata files on every host; replaced on every publish. */
    static final String METADATA_TAG = "updater-metadata";

    /** The fixed part of every endpoint, after the host's repository path. */
    static final String DOWNLOAD_PATH = "/releases/download/";

    static final String INVALID_TAG = "invalid release tag";
    static final String INVALID_FILE = "invalid artifact file name";
    static final String UNKNOWN_HOST = "unknown release host key";

    private static final Map<String, String> REPOS = Map.of(
            "github", "/tnacomtr/QUICraft",
            "sudoe", "/tna/QUICraft");
    private static final Pattern TAG = Pattern.compile("v[0-9A-Za-z.-]{1,40}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._+-]{1,124}\\.jar");

    @Override
    public URI revocations(ReleaseHost host) {
        return metadata(host, "revocations.v1.json");
    }

    @Override
    public URI revocationsSignature(ReleaseHost host) {
        return metadata(host, "revocations.v1.json.sig");
    }

    @Override
    public URI manifest(ReleaseHost host) {
        return metadata(host, "release.v1.json");
    }

    @Override
    public URI manifestSignature(ReleaseHost host) {
        return metadata(host, "release.v1.json.sig");
    }

    @Override
    public URI artifact(ReleaseHost host, UpdateDocuments.ReleaseEntry entry) {
        Objects.requireNonNull(entry, "entry");
        String repo = repo(host);
        String tag = entry.tag();
        if (tag == null || !TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException(INVALID_TAG);
        }
        String file = entry.file();
        if (file == null || !FILE.matcher(file).matches()) {
            throw new IllegalArgumentException(INVALID_FILE);
        }
        return uri(host, repo + DOWNLOAD_PATH + tag + "/" + file);
    }

    private static URI metadata(ReleaseHost host, String name) {
        return uri(host, repo(host) + DOWNLOAD_PATH + METADATA_TAG + "/" + name);
    }

    /** The repository path of one host key, with no trailing slash. */
    private static String repo(ReleaseHost host) {
        Objects.requireNonNull(host, "host");
        String repo = REPOS.get(host.key());
        if (repo == null) {
            throw new IllegalArgumentException(UNKNOWN_HOST);
        }
        return repo;
    }

    private static URI uri(ReleaseHost host, String path) {
        return URI.create(host.origin().toString() + path);
    }
}
