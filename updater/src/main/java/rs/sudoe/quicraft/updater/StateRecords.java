// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable state records shared by the updater runtime (docs/updater.md 12.2 state.json and the
 * revocation state).
 *
 * <p>These records check only structure: hex shape and length, the seed rule for revocations,
 * uniqueness, and defensive copies. Cardinality caps (32 kids, 256 withdrawn entries, 64 revoked
 * keys and so on) are deliberately <em>not</em> checked here. The state codec enforces them when it
 * reads and writes files, and the policy layer enforces them when it merges documents. Null
 * components raise {@link NullPointerException}; every other rejection raises
 * {@link IllegalArgumentException} with a fixed message that never echoes the input.
 */
public final class StateRecords {

    private static final String HEX = "0123456789abcdef";

    private StateRecords() {
    }

    /** The trust anchors that must survive a restart (a subset of {@link UpdaterState}). */
    public record PersistedTrust(int epoch, String keySetFingerprint, String revocationPub) {
        public PersistedTrust {
            if (epoch < 1) {
                throw new IllegalArgumentException("invalid epoch");
            }
            requireHex(keySetFingerprint, 64, "keySetFingerprint");
            requireHex(revocationPub, 64, "revocationPub");
        }
    }

    /** The last accepted sequence number of one release key. */
    public record KidSeq(long seq, String sha256, Instant issued) {
        public KidSeq {
            if (seq < 0) {
                throw new IllegalArgumentException("invalid seq");
            }
            requireHex(sha256, 64, "sha256");
            Objects.requireNonNull(issued, "issued");
        }
    }

    /** A withdrawn artifact entry in persisted state. */
    public record WithdrawnEntry(String sha512, UpdateVersion version, String kid) {
        public WithdrawnEntry {
            requireHex(sha512, 128, "sha512");
            Objects.requireNonNull(version, "version");
            requireHex(kid, 16, "kid");
        }
    }

    /**
     * The revocations document state. The seed case ({@code sha256} empty, {@code issued} and
     * {@code expires} null) is used until the first revocations document is accepted (12.4). Any
     * other combination is rejected.
     */
    public record CurrentRevocations(
            long seq, String sha256, Instant issued, Instant expires, long minManifestSeq) {
        public CurrentRevocations {
            if (seq < 0 || minManifestSeq < 0) {
                throw new IllegalArgumentException("invalid revocations");
            }
            Objects.requireNonNull(sha256, "sha256");
            if (sha256.isEmpty()) {
                if (issued != null || expires != null) {
                    throw new IllegalArgumentException("invalid revocations");
                }
            } else {
                requireHex(sha256, 64, "sha256");
                if (issued == null || expires == null) {
                    throw new IllegalArgumentException("invalid revocations");
                }
            }
        }

        /** Returns true for the seed case, before any revocations document was accepted. */
        public boolean seed() {
            return sha256.isEmpty();
        }
    }

    /**
     * The persisted updater state (state.json). The maps are copied into sorted, unmodifiable maps
     * and the lists into unmodifiable lists.
     */
    public record UpdaterState(
            int epoch,
            String keySetFingerprint,
            String revocationKey,
            CurrentRevocations revocations,
            Map<String, KidSeq> seqByKid,
            List<WithdrawnEntry> withdrawn,
            String lastAnnounced,
            List<String> warnedBad,
            Map<String, Instant> tornSince) {

        public UpdaterState {
            if (epoch < 1) {
                throw new IllegalArgumentException("invalid epoch");
            }
            requireHex(keySetFingerprint, 64, "keySetFingerprint");
            requireHex(revocationKey, 64, "revocationKey");
            Objects.requireNonNull(revocations, "revocations");

            Objects.requireNonNull(seqByKid, "seqByKid");
            for (Map.Entry<String, KidSeq> e : seqByKid.entrySet()) {
                requireHex(e.getKey(), 16, "kid");
                Objects.requireNonNull(e.getValue(), "seqByKid value");
            }
            seqByKid = Collections.unmodifiableMap(new TreeMap<>(seqByKid));

            Objects.requireNonNull(withdrawn, "withdrawn");
            List<WithdrawnEntry> withdrawnCopy = List.copyOf(withdrawn);
            Set<String> seenSha512 = new HashSet<>();
            for (WithdrawnEntry w : withdrawnCopy) {
                if (!seenSha512.add(w.sha512())) {
                    throw new IllegalArgumentException("duplicate withdrawn sha512");
                }
            }
            withdrawn = withdrawnCopy;

            Objects.requireNonNull(lastAnnounced, "lastAnnounced");
            if (!lastAnnounced.isEmpty()) {
                try {
                    UpdateVersion.parse(lastAnnounced);
                } catch (UpdateRejectedException e) {
                    throw new IllegalArgumentException("invalid lastAnnounced", e);
                }
            }

            Objects.requireNonNull(warnedBad, "warnedBad");
            for (String sha256 : warnedBad) {
                requireHex(sha256, 64, "warnedBad");
            }
            warnedBad = List.copyOf(warnedBad);

            Objects.requireNonNull(tornSince, "tornSince");
            for (Map.Entry<String, Instant> e : tornSince.entrySet()) {
                String host = e.getKey();
                if (!"modrinth".equals(host) && !"github".equals(host) && !"sudoe".equals(host)) {
                    throw new IllegalArgumentException("unknown tornSince host");
                }
                Objects.requireNonNull(e.getValue(), "tornSince value");
            }
            tornSince = Collections.unmodifiableMap(new TreeMap<>(tornSince));
        }

        /** Returns the trust anchors that must survive a restart. */
        public PersistedTrust persistedTrust() {
            return new PersistedTrust(epoch, keySetFingerprint, revocationKey);
        }
    }

    /**
     * The revocation state (revoked.json): revoked keys (the {@link UpdateDocuments.RevokedKey}
     * records) and blocked artifacts. Revoked keys are unique by {@code pub}; blocked artifacts are
     * unique 128-hex sha512 values.
     */
    public record RevokedState(
            List<UpdateDocuments.RevokedKey> revokedKeys, List<String> blockedArtifacts) {

        public RevokedState {
            Objects.requireNonNull(revokedKeys, "revokedKeys");
            Objects.requireNonNull(blockedArtifacts, "blockedArtifacts");
            List<UpdateDocuments.RevokedKey> keysCopy = List.copyOf(revokedKeys);
            Set<String> seenPub = new HashSet<>();
            for (UpdateDocuments.RevokedKey k : keysCopy) {
                requireHex(k.pub(), 64, "revoked pub");
                requireHex(k.kid(), 16, "revoked kid");
                if (!seenPub.add(k.pub())) {
                    throw new IllegalArgumentException("duplicate revoked pub");
                }
            }
            revokedKeys = keysCopy;

            List<String> blockedCopy = List.copyOf(blockedArtifacts);
            Set<String> seenSha512 = new HashSet<>();
            for (String sha512 : blockedCopy) {
                requireHex(sha512, 128, "blocked sha512");
                if (!seenSha512.add(sha512)) {
                    throw new IllegalArgumentException("duplicate blocked sha512");
                }
            }
            blockedArtifacts = blockedCopy;
        }

        /** Returns an empty revocation state. */
        public static RevokedState empty() {
            return new RevokedState(List.of(), List.of());
        }

        /** Returns the set of revoked public keys (hex), unmodifiable. */
        public Set<String> revokedPubs() {
            Set<String> pubs = new HashSet<>();
            for (UpdateDocuments.RevokedKey k : revokedKeys) {
                pubs.add(k.pub());
            }
            return Collections.unmodifiableSet(pubs);
        }
    }

    /** A consistent read of the persisted state and revocation state. */
    public record Snapshot(UpdaterState state, RevokedState revoked) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(revoked, "revoked");
        }
    }

    /**
     * The single change a policy function returns; the store applies it exactly once.
     */
    public record StateDelta(UpdaterState newState, RevokedState newRevoked) {
        public StateDelta {
            Objects.requireNonNull(newState, "newState");
            Objects.requireNonNull(newRevoked, "newRevoked");
        }
    }

    /** Throws unless {@code s} is exactly {@code chars} characters of lowercase hex. */
    private static void requireHex(String s, int chars, String field) {
        if (s == null || s.length() != chars) {
            throw new IllegalArgumentException("invalid " + field);
        }
        for (int i = 0; i < s.length(); i++) {
            if (HEX.indexOf(s.charAt(i)) < 0) {
                throw new IllegalArgumentException("invalid " + field);
            }
        }
    }
}
