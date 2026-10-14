// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure classification and state transformation for trust-epoch transitions (docs/updater.md
 * 12.8, "Trust-epoch transitions" and "Initialisation").
 *
 * <p>Transitions only ever come from the running jar's trust file, never from network data. This
 * class performs no I/O: {@link #classify} decides which of the six cases applies, and
 * {@link #apply} computes the resulting {@link StateRecords.StateDelta} without mutating its inputs.
 */
public final class TrustTransition {

    private TrustTransition() {
    }

    /**
     * The six trust-epoch cases of docs/updater.md 12.8. Exactly one applies to any trust file
     * compared with the persisted state.
     */
    public enum Transition {
        /** Case 1: no persisted state; initialise from the trust file. */
        INITIALISE,
        /** Case 2: same epoch and same key-set fingerprint; normal operation. */
        NORMAL,
        /** Case 3: same epoch, different key-set fingerprint; trust file invalid, updater disabled. */
        INVALID_SAME_EPOCH,
        /** Case 4: lower epoch; checks report only, staging disabled. */
        OLDER_EPOCH_REPORT_ONLY,
        /** Case 5: higher epoch, same revocation key; store new epoch and fingerprint, keep all floors. */
        ROTATION,
        /**
         * Case 6: higher epoch, different revocation key; reseed the revocation floors, add the old
         * revocation pub to the permanent revoked keys and prune the revoked kids.
         */
        REVOCATION_KEY_REPLACEMENT
    }

    /**
     * The deterministic decision of {@link #classify}, applied by {@link #apply}.
     *
     * @param transition the case that applies
     * @param newEpoch the epoch to store (the trust file's epoch)
     * @param newFingerprint the key-set fingerprint to store
     * @param newRevocationPub the revocation key to store
     * @param newlyRevokedPubs revocation keys that become permanently revoked (case 6 only)
     * @param keepPermanentRevocations always true: the permanent revocation union is never dropped
     */
    public record TrustDecision(
            Transition transition,
            int newEpoch,
            String newFingerprint,
            String newRevocationPub,
            Set<String> newlyRevokedPubs,
            boolean keepPermanentRevocations) {

        public TrustDecision {
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(newFingerprint, "newFingerprint");
            Objects.requireNonNull(newRevocationPub, "newRevocationPub");
            newlyRevokedPubs = Set.copyOf(newlyRevokedPubs);
        }
    }

    /**
     * Classifies the trust file {@code trust} against {@code persisted} (null when no state exists).
     *
     * @throws UpdateRejectedException if the trust file is malformed or does not have exactly one
     *     revocation key
     */
    public static TrustDecision classify(
            StateRecords.PersistedTrust persisted, UpdateDocuments.Trust trust)
            throws UpdateRejectedException {
        Objects.requireNonNull(trust, "trust");
        if (trust.epoch() < 1) {
            throw new UpdateRejectedException("invalid trust epoch");
        }
        String fingerprint = KeySetFingerprint.of(trust.keys());
        String revocationPub = revocationPub(trust);

        if (persisted == null) {
            return decision(Transition.INITIALISE, trust.epoch(), fingerprint, revocationPub, Set.of());
        }
        if (trust.epoch() == persisted.epoch()) {
            Transition t = fingerprint.equals(persisted.keySetFingerprint())
                    ? Transition.NORMAL
                    : Transition.INVALID_SAME_EPOCH;
            return decision(t, trust.epoch(), fingerprint, revocationPub, Set.of());
        }
        if (trust.epoch() < persisted.epoch()) {
            return decision(
                    Transition.OLDER_EPOCH_REPORT_ONLY, trust.epoch(), fingerprint, revocationPub, Set.of());
        }
        if (revocationPub.equals(persisted.revocationPub())) {
            return decision(Transition.ROTATION, trust.epoch(), fingerprint, revocationPub, Set.of());
        }
        return decision(
                Transition.REVOCATION_KEY_REPLACEMENT,
                trust.epoch(),
                fingerprint,
                revocationPub,
                Set.of(persisted.revocationPub()));
    }

    /**
     * Computes the state change for {@code d}. {@code current} must be null for
     * {@link Transition#INITIALISE} and non-null for every other transition.
     *
     * @throws IllegalArgumentException if the combination of {@code d}, {@code trust} and
     *     {@code current} is inconsistent
     * @throws UpdateRejectedException if a revoked public key is malformed
     */
    public static StateRecords.StateDelta apply(
            TrustDecision d, UpdateDocuments.Trust trust, StateRecords.Snapshot current)
            throws UpdateRejectedException {
        Objects.requireNonNull(d, "decision");
        Objects.requireNonNull(trust, "trust");
        // A caller-built decision is never trusted: it must be exactly what classify computes for
        // this trust file against the state it is applied to.
        TrustDecision expected = classify(
                current == null ? null : current.state().persistedTrust(), trust);
        if (!expected.equals(d)) {
            throw new IllegalArgumentException("decision does not match trust and state");
        }
        if ((d.transition() == Transition.INITIALISE) != (current == null)) {
            throw new IllegalArgumentException("current state must be null only for INITIALISE");
        }
        switch (d.transition()) {
            case INITIALISE:
                requireMatchesTrust(d, trust);
                return initialise(d, trust);
            case NORMAL:
            case INVALID_SAME_EPOCH:
            case OLDER_EPOCH_REPORT_ONLY:
                return new StateRecords.StateDelta(current.state(), current.revoked());
            case ROTATION:
                return rotation(d, trust, current);
            case REVOCATION_KEY_REPLACEMENT:
                return replacement(d, trust, current);
            default:
                throw new IllegalArgumentException("unknown transition");
        }
    }

    private static StateRecords.StateDelta initialise(TrustDecision d, UpdateDocuments.Trust trust) {
        StateRecords.UpdaterState state = new StateRecords.UpdaterState(
                d.newEpoch(),
                d.newFingerprint(),
                d.newRevocationPub(),
                seedRevocations(trust),
                Map.of(),
                List.of(),
                "",
                List.of(),
                Map.of());
        return new StateRecords.StateDelta(state, StateRecords.RevokedState.empty());
    }

    private static StateRecords.StateDelta rotation(
            TrustDecision d, UpdateDocuments.Trust trust, StateRecords.Snapshot current) {
        requireMatchesTrust(d, trust);
        StateRecords.UpdaterState s = current.state();
        if (!d.newRevocationPub().equals(s.revocationKey())) {
            throw new IllegalArgumentException("rotation must keep the revocation key");
        }
        StateRecords.UpdaterState state = withEpochAndFingerprint(s, d.newEpoch(), d.newFingerprint());
        return new StateRecords.StateDelta(state, current.revoked());
    }

    private static StateRecords.StateDelta replacement(
            TrustDecision d, UpdateDocuments.Trust trust, StateRecords.Snapshot current)
            throws UpdateRejectedException {
        requireMatchesTrust(d, trust);
        if (!d.keepPermanentRevocations()) {
            throw new IllegalArgumentException("permanent revocations are never dropped");
        }
        if (d.newlyRevokedPubs().contains(d.newRevocationPub())) {
            throw new IllegalArgumentException("the new revocation key cannot be revoked");
        }

        // Permanent union: existing revoked keys first, then new ones sorted by pub.
        List<UpdateDocuments.RevokedKey> revokedKeys = new ArrayList<>(current.revoked().revokedKeys());
        Set<String> present = current.revoked().revokedPubs();
        for (String pub : new TreeSet<>(d.newlyRevokedPubs())) {
            if (!present.contains(pub)) {
                revokedKeys.add(new UpdateDocuments.RevokedKey(Hex.kid(pub), pub));
            }
        }
        StateRecords.RevokedState revoked = new StateRecords.RevokedState(
                revokedKeys, current.revoked().blockedArtifacts());

        // Prune the per-kid floors and withdrawn entries of every revoked kid.
        Set<String> revokedKids = new TreeSet<>();
        for (UpdateDocuments.RevokedKey k : revoked.revokedKeys()) {
            revokedKids.add(Hex.kid(k.pub()));
        }
        StateRecords.UpdaterState old = current.state();
        Map<String, StateRecords.KidSeq> seqByKid = new HashMap<>();
        for (Map.Entry<String, StateRecords.KidSeq> e : old.seqByKid().entrySet()) {
            if (!revokedKids.contains(e.getKey())) {
                seqByKid.put(e.getKey(), e.getValue());
            }
        }
        List<StateRecords.WithdrawnEntry> withdrawn = new ArrayList<>();
        for (StateRecords.WithdrawnEntry w : old.withdrawn()) {
            if (!revokedKids.contains(w.kid())) {
                withdrawn.add(w);
            }
        }

        StateRecords.UpdaterState state = new StateRecords.UpdaterState(
                d.newEpoch(),
                d.newFingerprint(),
                d.newRevocationPub(),
                seedRevocations(trust),
                seqByKid,
                withdrawn,
                old.lastAnnounced(),
                old.warnedBad(),
                old.tornSince());
        return new StateRecords.StateDelta(state, revoked);
    }

    private static StateRecords.UpdaterState withEpochAndFingerprint(
            StateRecords.UpdaterState s, int epoch, String fingerprint) {
        return new StateRecords.UpdaterState(
                epoch,
                fingerprint,
                s.revocationKey(),
                s.revocations(),
                s.seqByKid(),
                s.withdrawn(),
                s.lastAnnounced(),
                s.warnedBad(),
                s.tornSince());
    }

    private static StateRecords.CurrentRevocations seedRevocations(UpdateDocuments.Trust trust) {
        return new StateRecords.CurrentRevocations(
                trust.revocationsSeqFloor(), "", null, null, trust.manifestSeqFloor());
    }

    private static void requireMatchesTrust(TrustDecision d, UpdateDocuments.Trust trust) {
        if (d.newEpoch() != trust.epoch()) {
            throw new IllegalArgumentException("decision epoch does not match trust file");
        }
        try {
            if (!d.newFingerprint().equals(KeySetFingerprint.of(trust.keys()))) {
                throw new IllegalArgumentException("decision fingerprint does not match trust file");
            }
        } catch (UpdateRejectedException e) {
            throw new IllegalArgumentException("trust file keys are malformed", e);
        }
    }

    private static String revocationPub(UpdateDocuments.Trust trust) throws UpdateRejectedException {
        String found = null;
        int count = 0;
        for (UpdateDocuments.TrustedKey key : trust.keys()) {
            if (TrustedKeyRoles.of(key).contains(KeyRole.REVOCATION)) {
                count++;
                found = key.pub();
            }
        }
        if (count != 1) {
            throw new UpdateRejectedException("trust file must have exactly one revocation key");
        }
        return found;
    }

    private static TrustDecision decision(
            Transition transition, int epoch, String fingerprint, String revocationPub, Set<String> revoked) {
        return new TrustDecision(transition, epoch, fingerprint, revocationPub, revoked, true);
    }
}
