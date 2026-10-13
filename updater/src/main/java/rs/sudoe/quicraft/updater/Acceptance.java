// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import rs.sudoe.quicraft.updater.SequencePolicy.AcceptResult;
import rs.sudoe.quicraft.updater.SequencePolicy.ManifestDecision;
import rs.sudoe.quicraft.updater.SequencePolicy.SelectionResult;
import rs.sudoe.quicraft.updater.StateRecords.RevokedState;
import rs.sudoe.quicraft.updater.StateRecords.Snapshot;
import rs.sudoe.quicraft.updater.StateRecords.StateDelta;
import rs.sudoe.quicraft.updater.StateRecords.UpdaterState;
import rs.sudoe.quicraft.updater.StateRecords.WithdrawnEntry;
import rs.sudoe.quicraft.updater.UpdateDocuments.ReleaseEntry;
import rs.sudoe.quicraft.updater.UpdateDocuments.ReleaseManifest;
import rs.sudoe.quicraft.updater.UpdateDocuments.Revocations;
import rs.sudoe.quicraft.updater.UpdateDocuments.Trust;
import rs.sudoe.quicraft.updater.UpdateDocuments.Withdrawal;

/**
 * Pure acceptance of fetched evidence (docs/updater.md 12.4, 12.5, 12.6 and 12.12 R3 to R8 and M3
 * to M8). Each method turns evidence into one {@link StateDelta} and an optional candidate.
 *
 * <p>No I/O, no clock reads and no locking. The caller runs these inside {@code StateStore.commit}
 * on a freshly read snapshot. Inputs are never mutated; every change is a new record.
 *
 * <p>Every evidence check is made under the trust snapshot of the current run. Torn pairs (12.6
 * item 7) set {@code tornSince} for their host; a verified pair clears it; a host torn for more
 * than 24 hours gets a diagnostic. Diagnostics name host keys only.
 */
public final class Acceptance {

    /** A host that stays torn longer than this gets a WARN diagnostic (12.6 item 7). */
    static final Duration TORN_WARN_AFTER = Duration.ofHours(24);

    private static final String STATE_FULL_DIAGNOSTIC =
            "persisted state is full; manifests not accepted";

    private Acceptance() {
    }

    /**
     * Evaluates all revocation responses (12.12 R3 to R8) against the running trust.
     *
     * @throws UpdateRejectedException if the persisted revoked keys cannot form a trust snapshot
     */
    public static RevocationAcceptance evaluateRevocations(
            HostCollection responses,
            ReleaseHosts configured,
            Snapshot current,
            Trust running,
            Instant systemNow) throws UpdateRejectedException {
        Objects.requireNonNull(responses, "responses");
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(running, "running");
        Objects.requireNonNull(systemNow, "systemNow");
        TrustSnapshot currentTrust = TrustSnapshot.of(running, current.revoked().revokedPubs());

        RevocationView view = revocationView(responses, configured, currentTrust);
        SelectionResult selection = SequencePolicy.selectCurrent(current, currentTrust, view.docs(), systemNow);
        List<String> diagnostics = new ArrayList<>(view.diagnostics());
        diagnostics.addAll(selection.warnings());

        UpdaterState selected = selection.delta().newState();
        Map<String, Instant> torn = updateTorn(
                selected.tornSince(), view.torn(), Set.of(), systemNow, diagnostics);
        StateDelta delta = new StateDelta(withTornSince(selected, torn), selection.delta().newRevoked());
        return new RevocationAcceptance(delta, diagnostics);
    }

    /**
     * Evaluates all manifest responses (12.12 M3 to M8) after the revocations were committed.
     *
     * <p>{@code current} must be the snapshot after {@link #evaluateRevocations} was committed. The
     * revocation responses of the same run are needed for staging coverage and for the torn state.
     *
     * @throws UpdateRejectedException if the persisted revoked keys cannot form a trust snapshot
     */
    public static ManifestAcceptance evaluateManifests(
            HostCollection revocationResponses,
            HostCollection manifestResponses,
            ReleaseHosts configured,
            Snapshot current,
            RuntimeIdentity id,
            UpdateVersion onDiskVersion,
            CheckMode mode,
            Instant systemNow) throws UpdateRejectedException {
        Objects.requireNonNull(revocationResponses, "revocationResponses");
        Objects.requireNonNull(manifestResponses, "manifestResponses");
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(onDiskVersion, "onDiskVersion");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(systemNow, "systemNow");

        Trust running = id.runningTrust();
        TrustSnapshot currentTrust = TrustSnapshot.of(running, current.revoked().revokedPubs());
        Instant clockFloor = ClockFloor.of(
                running.built(),
                current.state().revocations(),
                current.state().seqByKid(),
                revokedKids(current.revoked()));
        Instant effectiveNow = FreshnessPolicy.effectiveNow(systemNow, clockFloor);
        List<String> diagnostics = new ArrayList<>();
        if (FreshnessPolicy.clockBehind(systemNow, clockFloor)) {
            diagnostics.add("system clock is behind the clock floor");
        }

        RevocationView revocations = revocationView(revocationResponses, configured, currentTrust);

        Set<String> manifestTorn = new TreeSet<>();
        Set<String> manifestVerified = new TreeSet<>();
        Map<String, VerifiedManifest> verifiedByKey = new TreeMap<>();
        List<VerifiedManifest> verifiedInOrder = new ArrayList<>();
        for (HostEvidence ev : manifestResponses.responses()) {
            String key = ev.host().key();
            if (!configured.contains(ev.host())) {
                diagnostics.add(key + ": host not configured, ignored");
                continue;
            }
            if (ev.outcome() != FetchOutcome.OK) {
                diagnostics.add(key + ": manifest fetch failed (" + ev.outcome() + ")");
                continue;
            }
            DocumentPair pair = ev.pair();
            DocumentVerification<ReleaseManifest> result = AuthenticatedDocuments.verifyRelease(
                    pair.documentBytes(), pair.signatureBytes(), currentTrust);
            VerificationOutcome outcome = result.outcome();
            if (outcome == VerificationOutcome.TORN) {
                manifestTorn.add(key);
            } else if (outcome == VerificationOutcome.VERIFIED) {
                manifestVerified.add(key);
                VerifiedManifest vm = new VerifiedManifest(key, pair, result.verified());
                verifiedByKey.put(key, vm);
                verifiedInOrder.add(vm);
            } else {
                diagnostics.add(key + ": manifest document rejected (" + outcome + ")");
            }
        }

        UpdateVersion floorVersion = max(id.runningVersion(), onDiskVersion);
        Snapshot folded = current;
        boolean stateFull = false;
        VerifiedManifest winner = null;
        for (VerifiedManifest vm : verifiedInOrder) {
            ReleaseManifest doc = vm.verified().document();
            if (!manifestTimesAdmissible(doc, systemNow, effectiveNow)) {
                diagnostics.add(vm.key() + ": manifest outside the freshness window, skipped");
                continue;
            }
            ManifestDecision decision =
                    SequencePolicy.manifestDecision(vm.verified(), folded, running, currentTrust);
            if (decision != ManifestDecision.ACCEPT && decision != ManifestDecision.REPLAY_SAME) {
                diagnostics.add(vm.key() + ": " + rejectionText(decision));
                continue;
            }
            AcceptResult accepted = SequencePolicy.acceptManifest(
                    folded, vm.verified(), running, currentTrust, id.platform(), floorVersion);
            if (accepted.stateFull()) {
                stateFull = true;
                diagnostics.add(STATE_FULL_DIAGNOSTIC);
                break;
            }
            StateDelta next = accepted.delta();
            folded = new Snapshot(next.newState(), next.newRevoked());
            if (winner == null || doc.seq() > winner.verified().document().seq()) {
                winner = vm;
            }
        }

        Optional<AcceptedCandidate> candidate = Optional.empty();
        if (winner != null && !stateFull) {
            candidate = chooseCandidate(
                    winner, verifiedByKey, configured, folded, id, floorVersion,
                    running, currentTrust, revocations, mode, effectiveNow, diagnostics);
        }

        Set<String> tornNow = new TreeSet<>(manifestTorn);
        tornNow.addAll(revocations.torn());
        Set<String> cleared = new TreeSet<>(manifestVerified);
        cleared.retainAll(revocations.byKey().keySet());
        cleared.removeAll(tornNow);
        Map<String, Instant> torn = updateTorn(
                folded.state().tornSince(), tornNow, cleared, systemNow, diagnostics);
        StateDelta delta = new StateDelta(withTornSince(folded.state(), torn), folded.revoked());
        return new ManifestAcceptance(delta, candidate, stateFull, diagnostics);
    }

    /** One manifest that verified under the run's trust, with the pair it came from. */
    private record VerifiedManifest(String key, DocumentPair pair, VerifiedDocument<ReleaseManifest> verified) {
    }

    /**
     * The verified revocations of one run. {@code docs} keeps one entry per verified host in host
     * order; {@code byKey} keys the same verified documents by host key; {@code torn} lists hosts
     * whose pair was torn; {@code diagnostics} covers the other failures.
     */
    private record RevocationView(
            List<VerifiedDocument<Revocations>> docs,
            Map<String, VerifiedDocument<Revocations>> byKey,
            Set<String> torn,
            List<String> diagnostics) {
    }

    private static RevocationView revocationView(
            HostCollection responses, ReleaseHosts configured, TrustSnapshot trust) {
        List<VerifiedDocument<Revocations>> docs = new ArrayList<>();
        Map<String, VerifiedDocument<Revocations>> byKey = new TreeMap<>();
        Set<String> torn = new TreeSet<>();
        List<String> diagnostics = new ArrayList<>();
        for (HostEvidence ev : responses.responses()) {
            String key = ev.host().key();
            if (!configured.contains(ev.host())) {
                diagnostics.add(key + ": host not configured, ignored");
                continue;
            }
            if (ev.outcome() != FetchOutcome.OK) {
                diagnostics.add(key + ": revocations fetch failed (" + ev.outcome() + ")");
                continue;
            }
            DocumentPair pair = ev.pair();
            DocumentVerification<Revocations> result = AuthenticatedDocuments.verifyRevocations(
                    pair.documentBytes(), pair.signatureBytes(), trust);
            VerificationOutcome outcome = result.outcome();
            if (outcome == VerificationOutcome.VERIFIED) {
                docs.add(result.verified());
                byKey.put(key, result.verified());
            } else if (outcome == VerificationOutcome.TORN) {
                torn.add(key);
            } else {
                diagnostics.add(key + ": revocations document rejected (" + outcome + ")");
            }
        }
        return new RevocationView(docs, byKey, torn, diagnostics);
    }

    /**
     * Applies the torn rules of 12.6 item 7, which are host-level: a host is torn when any of its
     * pairs is torn. Hosts in {@code torn} keep the first time they were seen torn and get a
     * diagnostic after 24 hours. Hosts in {@code cleared} lose their entry. Callers clear a host only
     * when all of its pairs verified in this run.
     */
    private static Map<String, Instant> updateTorn(
            Map<String, Instant> existing,
            Set<String> torn,
            Set<String> cleared,
            Instant now,
            List<String> diagnostics) {
        Map<String, Instant> next = new TreeMap<>(existing);
        for (String key : cleared) {
            next.remove(key);
        }
        for (String key : torn) {
            Instant since = next.putIfAbsent(key, now);
            Instant first = since == null ? now : since;
            if (now.isAfter(first.plus(TORN_WARN_AFTER))) {
                diagnostics.add(key + ": torn for more than 24 h");
            }
        }
        return next;
    }

    private static Optional<AcceptedCandidate> chooseCandidate(
            VerifiedManifest winner,
            Map<String, VerifiedManifest> verifiedByKey,
            ReleaseHosts configured,
            Snapshot folded,
            RuntimeIdentity id,
            UpdateVersion floorVersion,
            Trust running,
            TrustSnapshot currentTrust,
            RevocationView revocations,
            CheckMode mode,
            Instant effectiveNow,
            List<String> diagnostics) {
        ReleaseManifest manifest = winner.verified().document();
        Optional<ReleaseEntry> entry = selectEntry(manifest, folded, id, floorVersion, diagnostics);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        if (!SequencePolicy.freshRevocations(folded, manifest, running, effectiveNow)) {
            diagnostics.add("revocation state is not fresh; no update reported");
            return Optional.empty();
        }
        if (mode == CheckMode.STAGE
                && !SequencePolicy.stagingCoverage(folded, currentTrust, revocations.byKey())) {
            diagnostics.add("staging coverage below 2 of 3 hosts; not matching: "
                    + String.join(", ", notMatching(configured, revocations.byKey(), folded)));
            return Optional.empty();
        }

        String winnerSha = Hex.sha256(winner.verified().exactBytes());
        List<ReleaseHost> sources = new ArrayList<>();
        for (ReleaseHost host : configured.hosts()) {
            VerifiedManifest vm = verifiedByKey.get(host.key());
            if (vm != null && Hex.sha256(vm.verified().exactBytes()).equals(winnerSha)) {
                sources.add(host);
            }
        }
        ManifestEvidence evidence = new ManifestEvidence(
                winner.pair().documentBytes(), winner.pair().signatureBytes());
        return Optional.of(new AcceptedCandidate(winner.verified(), evidence, entry.get(), sources));
    }

    /**
     * Picks the highest-version entry for the platform and exact target, above
     * {@code max(running, on-disk)}, that is neither blocked nor withdrawn. Two eligible entries
     * at the top version mean no entry is picked.
     */
    private static Optional<ReleaseEntry> selectEntry(
            ReleaseManifest manifest,
            Snapshot folded,
            RuntimeIdentity id,
            UpdateVersion floorVersion,
            List<String> diagnostics) {
        Set<String> excluded = new HashSet<>(folded.revoked().blockedArtifacts());
        for (WithdrawnEntry w : folded.state().withdrawn()) {
            excluded.add(w.sha512());
        }
        for (Withdrawal w : manifest.withdrawn()) {
            if (id.platform().equals(w.platform())) {
                excluded.add(w.sha512());
            }
        }
        ReleaseEntry best = null;
        boolean tie = false;
        for (ReleaseEntry e : manifest.releases()) {
            if (!id.platform().equals(e.platform()) || !e.targets().contains(id.target())) {
                continue;
            }
            if (e.version().compareTo(floorVersion) <= 0 || excluded.contains(e.sha512())) {
                continue;
            }
            if (best == null || e.version().compareTo(best.version()) > 0) {
                best = e;
                tie = false;
            } else if (e.version().compareTo(best.version()) == 0) {
                tie = true;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        if (tie) {
            diagnostics.add("ambiguous release entries at the same version; no update reported");
            return Optional.empty();
        }
        return Optional.of(best);
    }

    private static List<String> notMatching(
            ReleaseHosts configured, Map<String, VerifiedDocument<Revocations>> byKey, Snapshot folded) {
        String persisted = folded.state().revocations().sha256();
        List<String> out = new ArrayList<>();
        for (ReleaseHost host : configured.hosts()) {
            VerifiedDocument<Revocations> doc = byKey.get(host.key());
            if (doc == null || !Hex.sha256(doc.exactBytes()).equals(persisted)) {
                out.add(host.key());
            }
        }
        return out;
    }

    /** M6: structural times, future tolerance and expiry at the effective clock. */
    private static boolean manifestTimesAdmissible(
            ReleaseManifest doc, Instant systemNow, Instant effectiveNow) {
        return FreshnessPolicy.structuralTimesValid(
                        doc.issued(), doc.expires(), FreshnessPolicy.MANIFEST_MAX_LIFETIME)
                && !FreshnessPolicy.futureIssued(doc.issued(), systemNow)
                && FreshnessPolicy.unexpired(doc.expires(), effectiveNow);
    }

    private static String rejectionText(ManifestDecision decision) {
        switch (decision) {
            case STALE:
                return "manifest sequence is below the floor";
            case EQUIVOCATION:
                return "manifest equivocation at the floor sequence";
            case UNTRUSTED:
                return "manifest not signed by a trusted release key";
            default:
                return "manifest rejected";
        }
    }

    /** Kids of revoked public keys, which do not contribute floors. */
    private static Set<String> revokedKids(RevokedState revoked) throws UpdateRejectedException {
        Set<String> kids = new HashSet<>();
        for (String pub : revoked.revokedPubs()) {
            kids.add(Hex.kid(pub));
        }
        return kids;
    }

    private static UpdaterState withTornSince(UpdaterState s, Map<String, Instant> torn) {
        return new UpdaterState(
                s.epoch(),
                s.keySetFingerprint(),
                s.revocationKey(),
                s.revocations(),
                s.seqByKid(),
                s.withdrawn(),
                s.lastAnnounced(),
                s.warnedBad(),
                torn);
    }

    private static UpdateVersion max(UpdateVersion a, UpdateVersion b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
