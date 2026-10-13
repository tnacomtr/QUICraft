// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import rs.sudoe.quicraft.updater.StateRecords.CurrentRevocations;
import rs.sudoe.quicraft.updater.StateRecords.KidSeq;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;
import rs.sudoe.quicraft.updater.UpdateDocuments.ReleaseManifest;
import rs.sudoe.quicraft.updater.UpdateDocuments.Revocations;
import rs.sudoe.quicraft.updater.UpdateDocuments.Trust;
import rs.sudoe.quicraft.updater.UpdateDocuments.Withdrawal;

/**
 * Pure sequence, floor, restriction and coverage rules (docs/updater.md 12.4, 12.6 and 12.12;
 * invariants 2, 6, 8 and 9 of the updater runtime plan).
 *
 * <p>Every method takes its state and clock values as arguments, performs no I/O and reads no
 * clock. State changes are returned as {@link StateDelta} values; nothing is mutated in place.
 *
 * <p>Signer evidence is bound to the trust snapshot of the current run ({@code currentTrust}).
 * A {@link VerifiedDocument} verified under any other {@link TrustSnapshot} is ignored, and role,
 * revocation and key checks use {@code currentTrust}, never the document's own snapshot.
 */
public final class SequencePolicy {

    /** Maximum number of persisted per-kid sequence entries (state codec limit). */
    static final int MAX_SEQ_BY_KID = 32;

    /** Maximum number of persisted withdrawn entries (state codec limit). */
    static final int MAX_WITHDRAWN = 256;

    /** The host origins that count for staging coverage (12.6 item 5). */
    private static final Set<String> COVERAGE_ORIGINS = Set.of("modrinth", "github", "sudoe");

    /** Fixed WARN text for a revocations document verified under another trust snapshot. */
    private static final String OTHER_SNAPSHOT_WARNING =
            "revocations document from another trust snapshot ignored";

    private SequencePolicy() {
    }

    /**
     * The result of {@link #manifestDecision}. {@code ACCEPT} advances the floor, {@code
     * REPLAY_SAME} is the same document fetched again, {@code STALE} is older than the floor or an
     * unbacked floor value, {@code EQUIVOCATION} is the same seq with different bytes, and
     * {@code UNTRUSTED} means no signer is a current, unrevoked release key or the document was
     * verified under another trust snapshot.
     */
    public enum ManifestDecision {
        ACCEPT,
        REPLAY_SAME,
        STALE,
        EQUIVOCATION,
        UNTRUSTED
    }

    /** The result of {@link #acceptManifest}. {@code stateFull} is true when a cap was exceeded. */
    public record AcceptResult(StateDelta delta, boolean stateFull) {
        public AcceptResult {
            Objects.requireNonNull(delta, "delta");
        }
    }

    /** The result of {@link #selectCurrent}: the delta and the WARN messages it produced. */
    public record SelectionResult(StateDelta delta, List<String> warnings) {
        public SelectionResult {
            Objects.requireNonNull(delta, "delta");
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * The manifest sequence floor (12.4): the maximum of the trust floor, the current revocation
     * key's {@code minManifestSeq}, and the {@code seq} of every persisted kid that is not revoked.
     * Persisted kids absent from the running trust file still count.
     */
    public static long manifestFloor(Snapshot snap, Trust trust) {
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(trust, "trust");
        long floor = Math.max(trust.manifestSeqFloor(), snap.state().revocations().minManifestSeq());
        Set<String> revokedKids = revokedKids(snap.revoked());
        for (Map.Entry<String, KidSeq> entry : snap.state().seqByKid().entrySet()) {
            if (!revokedKids.contains(entry.getKey())) {
                floor = Math.max(floor, entry.getValue().seq());
            }
        }
        return floor;
    }

    /**
     * Decides what a verified release manifest means for the persisted sequence state. The
     * manifest must have been verified under {@code currentTrust}; otherwise it is {@code
     * UNTRUSTED}. Only signers that are current release keys and not revoked count. A {@code seq}
     * equal to the floor is accepted only when a persisted non-revoked kid holds the identical
     * SHA-256 for that seq (the same document fetched again); any other persisted hash for that seq
     * is equivocation.
     */
    public static ManifestDecision manifestDecision(
            VerifiedDocument<ReleaseManifest> m, Snapshot snap, Trust trust, TrustSnapshot currentTrust) {
        Objects.requireNonNull(m, "m");
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(currentTrust, "currentTrust");
        if (!m.snapshot().equals(currentTrust)) {
            return ManifestDecision.UNTRUSTED;
        }
        Set<String> trustedKids = trustedReleaseKids(m, snap, currentTrust);
        if (trustedKids.isEmpty()) {
            return ManifestDecision.UNTRUSTED;
        }
        long seq = m.document().seq();
        long floor = manifestFloor(snap, trust);
        if (seq > floor) {
            return ManifestDecision.ACCEPT;
        }
        if (seq < floor) {
            return ManifestDecision.STALE;
        }
        String hash = Hex.sha256(m.exactBytes());
        Set<String> revokedKids = revokedKids(snap.revoked());
        boolean sameSeqPersisted = false;
        for (Map.Entry<String, KidSeq> entry : snap.state().seqByKid().entrySet()) {
            KidSeq persisted = entry.getValue();
            if (revokedKids.contains(entry.getKey()) || persisted.seq() != seq) {
                continue;
            }
            if (persisted.sha256().equals(hash)) {
                return ManifestDecision.REPLAY_SAME;
            }
            sameSeqPersisted = true;
        }
        return sameSeqPersisted ? ManifestDecision.EQUIVOCATION : ManifestDecision.STALE;
    }

    /**
     * Merges the restrictions of a revocations document (12.4 item 1, 12.12 R7). The document must
     * be verified under {@code currentTrust}, be signed by the current revocation key (which must
     * not be revoked), and have structurally valid times (R6). Otherwise the delta is unchanged.
     * There is no expiry, {@code seq} or {@code issued} condition beyond R6. Adds the revoked keys
     * and blocked artifacts to the permanent union, maxes {@code minManifestSeq}, and prunes
     * entries of revoked kids.
     */
    public static StateDelta mergeRestrictions(
            Snapshot snap, TrustSnapshot currentTrust, VerifiedDocument<Revocations> doc) {
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(currentTrust, "currentTrust");
        Objects.requireNonNull(doc, "doc");
        if (!admissible(snap, currentTrust, doc)) {
            return unchanged(snap);
        }
        Snapshot merged = applyRestrictions(snap, doc.document());
        return new StateDelta(merged.state(), merged.revoked());
    }

    /**
     * Selects the current revocations document (12.4 item 2) and merges the restrictions of every
     * admissible document (see {@link #mergeRestrictions}), whatever its {@code issued} or {@code
     * seq}. Only admissible documents with {@code issued <= systemNow + 24 h} may become current.
     * A document verified under another trust snapshot is ignored with a fixed WARN. Two different
     * documents with the same winning {@code seq} are equivocation: no change and a WARN.
     */
    public static SelectionResult selectCurrent(
            Snapshot snap,
            TrustSnapshot currentTrust,
            List<VerifiedDocument<Revocations>> docs,
            Instant systemNow) {
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(currentTrust, "currentTrust");
        Objects.requireNonNull(docs, "docs");
        Objects.requireNonNull(systemNow, "systemNow");
        List<String> warnings = new ArrayList<>();

        List<VerifiedDocument<Revocations>> candidates = new ArrayList<>();
        for (VerifiedDocument<Revocations> doc : docs) {
            Objects.requireNonNull(doc, "doc");
            if (!doc.snapshot().equals(currentTrust)) {
                warnings.add(OTHER_SNAPSHOT_WARNING);
            } else if (admissible(snap, currentTrust, doc)) {
                candidates.add(doc);
            }
        }

        Snapshot folded = snap;
        for (VerifiedDocument<Revocations> doc : candidates) {
            folded = applyRestrictions(folded, doc.document());
        }

        CurrentRevocations persisted = snap.state().revocations();
        List<VerifiedDocument<Revocations>> eligible = new ArrayList<>();
        for (VerifiedDocument<Revocations> doc : candidates) {
            if (!FreshnessPolicy.futureIssued(doc.document().issued(), systemNow)) {
                eligible.add(doc);
            }
        }

        List<VerifiedDocument<Revocations>> pool = new ArrayList<>();
        for (VerifiedDocument<Revocations> doc : eligible) {
            long seq = doc.document().seq();
            if (persisted.seed()) {
                if (seq >= persisted.seq()) {
                    pool.add(doc);
                }
            } else if (seq > persisted.seq()) {
                pool.add(doc);
            } else if (seq == persisted.seq()
                    && !Hex.sha256(doc.exactBytes()).equals(persisted.sha256())) {
                warnings.add("revocations equivocation at seq " + seq);
            }
        }

        if (pool.isEmpty()) {
            return new SelectionResult(new StateDelta(folded.state(), folded.revoked()), warnings);
        }
        long highest = Long.MIN_VALUE;
        for (VerifiedDocument<Revocations> doc : pool) {
            highest = Math.max(highest, doc.document().seq());
        }
        VerifiedDocument<Revocations> winner = null;
        Set<String> winnerHashes = new HashSet<>();
        for (VerifiedDocument<Revocations> doc : pool) {
            if (doc.document().seq() == highest) {
                winnerHashes.add(Hex.sha256(doc.exactBytes()));
                if (winner == null) {
                    winner = doc;
                }
            }
        }
        if (winnerHashes.size() > 1) {
            warnings.add("revocations equivocation at seq " + highest);
        } else {
            UpdaterState state = folded.state();
            CurrentRevocations old = state.revocations();
            Revocations doc = winner.document();
            CurrentRevocations next = new CurrentRevocations(
                    doc.seq(),
                    Hex.sha256(winner.exactBytes()),
                    doc.issued(),
                    doc.expires(),
                    old.minManifestSeq());
            folded = new Snapshot(withRevocations(state, next), folded.revoked());
        }
        return new SelectionResult(new StateDelta(folded.state(), folded.revoked()), warnings);
    }

    /**
     * Fresh revocation state (12.6 item 3): the current document is not the seed, is unexpired at
     * {@code effectiveNow}, and meets both the trust and the manifest revocation sequence floors.
     */
    public static boolean freshRevocations(
            Snapshot snap, ReleaseManifest manifest, Trust trust, Instant effectiveNow) {
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(effectiveNow, "effectiveNow");
        CurrentRevocations current = snap.state().revocations();
        if (current.seed()) {
            return false;
        }
        return FreshnessPolicy.unexpired(current.expires(), effectiveNow)
                && current.seq() >= trust.revocationsSeqFloor()
                && current.seq() >= manifest.minRevocationsSeq();
    }

    /**
     * Staging coverage (12.6 item 5, invariant 9): true when at least two distinct coverage origins
     * return an admissible revocations document under {@code currentTrust} whose SHA-256 equals the
     * persisted one. Keys are origin names; a {@link Map} holds each origin once, so callers collapse
     * retries and aliases before calling. Origins other than modrinth, github and sudoe are
     * ignored, and so are documents verified under another trust snapshot. The seed never has
     * coverage.
     */
    public static boolean stagingCoverage(
            Snapshot snap,
            TrustSnapshot currentTrust,
            Map<String, VerifiedDocument<Revocations>> currentByOrigin) {
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(currentTrust, "currentTrust");
        Objects.requireNonNull(currentByOrigin, "currentByOrigin");
        String persisted = snap.state().revocations().sha256();
        if (persisted.isEmpty()) {
            return false;
        }
        int matches = 0;
        for (Map.Entry<String, VerifiedDocument<Revocations>> entry : currentByOrigin.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "origin");
            Objects.requireNonNull(entry.getValue(), "document");
            if (!COVERAGE_ORIGINS.contains(entry.getKey())) {
                continue;
            }
            VerifiedDocument<Revocations> doc = entry.getValue();
            if (admissible(snap, currentTrust, doc)
                    && Hex.sha256(doc.exactBytes()).equals(persisted)) {
                matches++;
            }
        }
        return matches >= 2;
    }

    /**
     * Persists the effect of an accepted manifest (12.4, 12.12 M7). Only {@code ACCEPT} and {@code
     * REPLAY_SAME} change state. For every current, unrevoked release-role signer, {@code seqByKid}
     * takes the manifest's seq, hash and issued time. Withdrawn entries are added for the compiled
     * platform only, for versions above {@code maxRunningOrOnDisk}, attributed to the first trusted
     * signing kid in sorted order and deduplicated by sha512. Entries of revoked kids are pruned. If
     * a cap would be exceeded, the delta is unchanged and {@code stateFull} is set.
     */
    public static AcceptResult acceptManifest(
            Snapshot snap,
            VerifiedDocument<ReleaseManifest> m,
            Trust trust,
            TrustSnapshot currentTrust,
            String compiledPlatform,
            UpdateVersion maxRunningOrOnDisk) {
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(m, "m");
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(currentTrust, "currentTrust");
        Objects.requireNonNull(compiledPlatform, "compiledPlatform");
        Objects.requireNonNull(maxRunningOrOnDisk, "maxRunningOrOnDisk");
        ManifestDecision decision = manifestDecision(m, snap, trust, currentTrust);
        if (decision != ManifestDecision.ACCEPT && decision != ManifestDecision.REPLAY_SAME) {
            return new AcceptResult(unchanged(snap), false);
        }

        ReleaseManifest manifest = m.document();
        Set<String> signingKids = trustedReleaseKids(m, snap, currentTrust);
        String hash = Hex.sha256(m.exactBytes());

        Map<String, KidSeq> seqByKid = new TreeMap<>(snap.state().seqByKid());
        for (String kid : signingKids) {
            KidSeq existing = seqByKid.get(kid);
            if (existing == null || existing.seq() <= manifest.seq()) {
                seqByKid.put(kid, new KidSeq(manifest.seq(), hash, manifest.issued()));
            }
        }

        List<WithdrawnEntry> withdrawn = new ArrayList<>(snap.state().withdrawn());
        Set<String> seenSha512 = new HashSet<>();
        for (WithdrawnEntry existing : withdrawn) {
            seenSha512.add(existing.sha512());
        }
        String withdrawingKid = signingKids.iterator().next();
        for (Withdrawal w : manifest.withdrawn()) {
            if (!compiledPlatform.equals(w.platform())
                    || w.version().compareTo(maxRunningOrOnDisk) <= 0) {
                continue;
            }
            if (seenSha512.add(w.sha512())) {
                withdrawn.add(new WithdrawnEntry(w.sha512(), w.version(), withdrawingKid));
            }
        }

        UpdaterState base = snap.state();
        UpdaterState next = new UpdaterState(
                base.epoch(),
                base.keySetFingerprint(),
                base.revocationKey(),
                base.revocations(),
                seqByKid,
                withdrawn,
                base.lastAnnounced(),
                base.warnedBad(),
                base.tornSince());
        Snapshot pruned = prune(new Snapshot(next, snap.revoked()));
        if (pruned.state().seqByKid().size() > MAX_SEQ_BY_KID
                || pruned.state().withdrawn().size() > MAX_WITHDRAWN) {
            return new AcceptResult(unchanged(snap), true);
        }
        return new AcceptResult(new StateDelta(pruned.state(), pruned.revoked()), false);
    }

    /**
     * True when the document was verified under {@code currentTrust}, is signed by the current
     * revocation key (a current, unrevoked revocation-role key), and has structurally valid times
     * (R6). The signer check uses {@code currentTrust} only.
     */
    private static boolean admissible(
            Snapshot snap, TrustSnapshot currentTrust, VerifiedDocument<Revocations> doc) {
        if (!doc.snapshot().equals(currentTrust)) {
            return false;
        }
        String key = snap.state().revocationKey();
        if (snap.revoked().revokedPubs().contains(key) || !currentTrust.revocationPubs().contains(key)) {
            return false;
        }
        if (!doc.signers().containsPub(key)) {
            return false;
        }
        Revocations r = doc.document();
        return FreshnessPolicy.structuralTimesValid(
                r.issued(), r.expires(), FreshnessPolicy.REVOCATIONS_MAX_LIFETIME);
    }

    /** Applies the restriction merge of one document and prunes, returning the new snapshot. */
    private static Snapshot applyRestrictions(Snapshot snap, Revocations doc) {
        RevokedState revoked = snap.revoked();
        List<UpdateDocuments.RevokedKey> keys = new ArrayList<>();
        Set<String> pubs = new HashSet<>();
        for (UpdateDocuments.RevokedKey key : revoked.revokedKeys()) {
            keys.add(key);
            pubs.add(key.pub());
        }
        for (UpdateDocuments.RevokedKey key : doc.revokedKeys()) {
            if (pubs.add(key.pub())) {
                keys.add(key);
            }
        }
        List<String> blocked = new ArrayList<>(revoked.blockedArtifacts());
        Set<String> seenBlocked = new HashSet<>(blocked);
        for (String sha512 : doc.blockedArtifacts()) {
            if (seenBlocked.add(sha512)) {
                blocked.add(sha512);
            }
        }
        RevokedState mergedRevoked = new RevokedState(keys, blocked);

        UpdaterState state = snap.state();
        CurrentRevocations old = state.revocations();
        CurrentRevocations next = new CurrentRevocations(
                old.seq(),
                old.sha256(),
                old.issued(),
                old.expires(),
                Math.max(old.minManifestSeq(), doc.minManifestSeq()));
        return prune(new Snapshot(withRevocations(state, next), mergedRevoked));
    }

    /** Drops {@code seqByKid} and withdrawn entries whose kid belongs to a revoked public key. */
    private static Snapshot prune(Snapshot snap) {
        Set<String> revokedKids = revokedKids(snap.revoked());
        UpdaterState state = snap.state();
        Map<String, KidSeq> seqByKid = new TreeMap<>();
        for (Map.Entry<String, KidSeq> entry : state.seqByKid().entrySet()) {
            if (!revokedKids.contains(entry.getKey())) {
                seqByKid.put(entry.getKey(), entry.getValue());
            }
        }
        List<WithdrawnEntry> withdrawn = new ArrayList<>();
        for (WithdrawnEntry entry : state.withdrawn()) {
            if (!revokedKids.contains(entry.kid())) {
                withdrawn.add(entry);
            }
        }
        UpdaterState pruned = new UpdaterState(
                state.epoch(),
                state.keySetFingerprint(),
                state.revocationKey(),
                state.revocations(),
                seqByKid,
                withdrawn,
                state.lastAnnounced(),
                state.warnedBad(),
                state.tornSince());
        return new Snapshot(pruned, snap.revoked());
    }

    private static UpdaterState withRevocations(UpdaterState s, CurrentRevocations revocations) {
        return new UpdaterState(
                s.epoch(),
                s.keySetFingerprint(),
                s.revocationKey(),
                revocations,
                s.seqByKid(),
                s.withdrawn(),
                s.lastAnnounced(),
                s.warnedBad(),
                s.tornSince());
    }

    /**
     * Kids of the signers that are current release keys (per {@code currentTrust}), whose public key
     * is not revoked, and whose kid maps to that public key in the current trust. Sorted.
     */
    private static Set<String> trustedReleaseKids(
            VerifiedDocument<ReleaseManifest> m, Snapshot snap, TrustSnapshot currentTrust) {
        Set<String> releasePubs = currentTrust.releasePubs();
        Set<String> revokedPubs = snap.revoked().revokedPubs();
        Set<String> kids = new TreeSet<>();
        for (VerifiedSigner signer : m.signers().signers()) {
            String pub = signer.pub();
            if (releasePubs.contains(pub)
                    && !revokedPubs.contains(pub)
                    && pub.equals(currentTrust.pubByKid().get(signer.kid()))) {
                kids.add(signer.kid());
            }
        }
        return kids;
    }

    private static Set<String> revokedKids(RevokedState revoked) {
        Set<String> kids = new HashSet<>();
        for (String pub : revoked.revokedPubs()) {
            kids.add(kidOf(pub));
        }
        return kids;
    }

    private static String kidOf(String pub) {
        try {
            return Hex.kid(pub);
        } catch (UpdateRejectedException e) {
            // Revoked public keys are validated as 64 lowercase hex characters when constructed.
            throw new IllegalStateException("invalid revoked public key", e);
        }
    }

    private static StateDelta unchanged(Snapshot snap) {
        return new StateDelta(snap.state(), snap.revoked());
    }
}
