// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable view of the trust state used for one updater decision.
 *
 * <p>Revoked public keys are never usable, even when the trust file still lists them.
 * All collections are defensive, unmodifiable copies.
 */
public record TrustSnapshot(
        int epoch,
        String keySetFingerprint,
        Set<String> revokedPubs,
        Map<String, String> pubByKid,
        Map<String, Set<KeyRole>> rolesByPub) {

    public TrustSnapshot {
        revokedPubs = Set.copyOf(revokedPubs);
        pubByKid = Collections.unmodifiableMap(new TreeMap<>(pubByKid));
        Map<String, Set<KeyRole>> roles = new TreeMap<>();
        for (Map.Entry<String, Set<KeyRole>> entry : rolesByPub.entrySet()) {
            EnumSet<KeyRole> copy = EnumSet.noneOf(KeyRole.class);
            copy.addAll(entry.getValue());
            roles.put(entry.getKey(), Collections.unmodifiableSet(copy));
        }
        rolesByPub = Collections.unmodifiableMap(roles);
    }

    /**
     * Builds the snapshot from the jar-embedded trust file and the persisted revoked public keys.
     *
     * @throws UpdateRejectedException if a key is malformed or two keys share a kid or public key
     */
    public static TrustSnapshot of(UpdateDocuments.Trust trust, Set<String> persistedRevokedPubs)
            throws UpdateRejectedException {
        String fingerprint = KeySetFingerprint.of(trust.keys());
        Map<String, String> pubByKid = new TreeMap<>();
        Map<String, Set<KeyRole>> rolesByPub = new TreeMap<>();
        for (UpdateDocuments.TrustedKey key : trust.keys()) {
            if (pubByKid.put(key.kid(), key.pub()) != null) {
                throw new UpdateRejectedException("duplicate kid");
            }
            rolesByPub.put(key.pub(), TrustedKeyRoles.of(key));
        }
        return new TrustSnapshot(
                trust.epoch(), fingerprint, persistedRevokedPubs, pubByKid, rolesByPub);
    }

    /** True when {@code pub} is a trusted key that has not been revoked. */
    public boolean usable(String pub) {
        return rolesByPub.containsKey(pub) && !revokedPubs.contains(pub);
    }

    /** Usable keys holding the {@code release} role. */
    public Set<String> releasePubs() {
        return usableWithRole(KeyRole.RELEASE);
    }

    /** Usable keys holding the {@code revocation} role. */
    public Set<String> revocationPubs() {
        return usableWithRole(KeyRole.REVOCATION);
    }

    private Set<String> usableWithRole(KeyRole role) {
        TreeSet<String> result = new TreeSet<>();
        for (Map.Entry<String, Set<KeyRole>> entry : rolesByPub.entrySet()) {
            if (entry.getValue().contains(role) && usable(entry.getKey())) {
                result.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
