// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Instant;
import java.util.List;

/**
 * Immutable schema records for the updater documents (docs/updater.md 12.2 and 12.3).
 *
 * <p>Instances produced by {@link DocumentParser} are structurally valid only. They carry no
 * authenticity, freshness or persistence claim; signature checks and policy are separate layers.
 * Every list is copied defensively by its record constructor.
 */
public final class UpdateDocuments {

    private UpdateDocuments() {
    }

    /** A trusted public key with its roles ({@code release} and/or {@code revocation}). */
    public record TrustedKey(String kid, String pub, List<String> roles) {
        public TrustedKey {
            roles = List.copyOf(roles);
        }
    }

    /** The jar-embedded trust file ({@code updater-trust.json}). */
    public record Trust(
            int epoch,
            Instant built,
            long revocationsSeqFloor,
            long manifestSeqFloor,
            List<TrustedKey> keys) {
        public Trust {
            keys = List.copyOf(keys);
        }
    }

    /** One signature entry of a detached signature file. */
    public record SignatureEntry(String kid, String sig) {
    }

    /** The detached signature envelope ({@code *.sig}). */
    public record SignatureEnvelope(String payloadSha256, List<SignatureEntry> signatures) {
        public SignatureEnvelope {
            signatures = List.copyOf(signatures);
        }
    }

    /** One release entry of a release manifest. */
    public record ReleaseEntry(
            String platform,
            UpdateVersion version,
            List<String> targets,
            String file,
            long size,
            String sha512,
            String tag) {
        public ReleaseEntry {
            targets = List.copyOf(targets);
        }
    }

    /** One withdrawn artifact entry of a release manifest. */
    public record Withdrawal(String platform, UpdateVersion version, String sha512) {
    }

    /** The signed release manifest ({@code release.v1.json}). */
    public record ReleaseManifest(
            long seq,
            Instant issued,
            Instant expires,
            long minRevocationsSeq,
            List<ReleaseEntry> releases,
            List<Withdrawal> withdrawn) {
        public ReleaseManifest {
            releases = List.copyOf(releases);
            withdrawn = List.copyOf(withdrawn);
        }
    }

    /** A revoked public key, identified by both its full key and its kid. */
    public record RevokedKey(String kid, String pub) {
    }

    /** The signed revocations document ({@code revocations.v1.json}). */
    public record Revocations(
            long seq,
            Instant issued,
            Instant expires,
            long minManifestSeq,
            List<RevokedKey> revokedKeys,
            List<String> blockedArtifacts) {
        public Revocations {
            revokedKeys = List.copyOf(revokedKeys);
            blockedArtifacts = List.copyOf(blockedArtifacts);
        }
    }
}
