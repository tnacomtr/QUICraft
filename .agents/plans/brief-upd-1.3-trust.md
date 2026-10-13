# Brief upd-1.3: trust-epoch transitions

GOAL: Wave 1.3 of `.agents/plans/2026-10-13-updater-runtime.md`: pure classification of the six trust transitions and the deterministic StateDelta for each, with tests.
SCOPE: new files only under `updater/src/main/java/rs/sudoe/quicraft/updater/` and its test dir.
BRANCH/WORKTREE: `agent/phase4-upd-trust` in `/home/tna/QUICraft/.worktrees/phase4-upd-trust`. Absolute paths; Gradle only there.
READ FIRST: `docs/updater.md` lines 368-415 (transitions 1-6, staged-jar validation) and 739-760 (initialisation) and 800-815 (growth policy/pruning); `StateRecords.java`; `KeySetFingerprint.java`; `TrustedKeyRoles.java`; `Hex.java`; `UpdateDocuments.java`.

STEPS:
1. `Transition { INITIALISE, NORMAL, INVALID_SAME_EPOCH, OLDER_EPOCH_REPORT_ONLY, ROTATION, REVOCATION_KEY_REPLACEMENT }` with javadoc mapping each to spec case 1-6.
2. `TrustDecision(Transition transition, int newEpoch, String newFingerprint, String newRevocationPub, Set<String> newlyRevokedPubs, boolean keepPermanentRevocations)` record, Set.copyOf.
3. `TrustTransition.classify(StateRecords.PersistedTrust persisted /* null = none */, UpdateDocuments.Trust trust) throws UpdateRejectedException`:
   - fingerprint = KeySetFingerprint.of(trust.keys()); revocationPub = the single key whose roles include REVOCATION (zero or more than one -> throw UpdateRejectedException("trust file must have exactly one revocation key")).
   - persisted null -> INITIALISE. Same epoch & same fingerprint -> NORMAL. Same epoch & different fingerprint -> INVALID_SAME_EPOCH. trust.epoch < persisted.epoch -> OLDER_EPOCH_REPORT_ONLY. Higher epoch & same revocation pub -> ROTATION. Higher epoch & different revocation pub -> REVOCATION_KEY_REPLACEMENT with newlyRevokedPubs = {persisted.revocationPub()}.
   - keepPermanentRevocations is true for every transition (permanent union is never dropped); newlyRevokedPubs empty except case 6.
4. `TrustTransition.apply(TrustDecision d, UpdateDocuments.Trust trust, StateRecords.Snapshot current /* null only for INITIALISE */) -> StateRecords.StateDelta`. Deterministic, never mutates inputs:
   - INITIALISE: state per docs/updater.md 739-750: epoch/fingerprint/revocationKey from decision; revocations = seed (seq=trust.revocationsSeqFloor, sha256 "", issued null, expires null, minManifestSeq=trust.manifestSeqFloor); seqByKid empty; withdrawn empty; lastAnnounced ""; warnedBad empty; tornSince empty. revoked = RevokedState.empty().
   - NORMAL, INVALID_SAME_EPOCH, OLDER_EPOCH_REPORT_ONLY: delta equal to current (no change). (Callers disable staging/updater; that is not this class's job.)
   - ROTATION: copy current state with epoch and keySetFingerprint replaced; everything else kept.
   - REVOCATION_KEY_REPLACEMENT: epoch, fingerprint, revocationKey = new; revocations = seed from trust floors as in INITIALISE; revoked = current revoked plus a RevokedKey(Hex.kid(oldPub), oldPub) for each newly revoked pub not already present (order: existing then new, sorted by pub for new ones); blockedArtifacts kept; then prune: remove seqByKid entries and withdrawn entries whose kid equals Hex.kid(p) for any p in the resulting revoked pubs. Keep lastAnnounced, warnedBad, tornSince.
   - Any other combination (current null for non-INITIALISE) -> IllegalArgumentException.
5. Tests `TrustTransitionTest`: each of the six classifications; exactly-one-revocation-key check; apply for each: INITIALISE matches spec fields; ROTATION keeps floors, seqByKid, withdrawn, revoked union; case 6 adds old pub to revokedKeys, resets revocations to the trust floors, keeps blockedArtifacts and other revoked keys, prunes seqByKid/withdrawn of revoked kids but keeps non-revoked kids' floors; case 6 when the old pub is already revoked does not duplicate; determinism (apply twice -> equal deltas); inputs unchanged after apply. Use real pubs: RFC 8032 public keys d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a, 3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c, fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025 (no private keys needed here).
6. Run `<worktree>/gradlew -p <worktree> :updater:test :updater:check --rerun-tasks --no-build-cache`; green. Commit "updater: trust-epoch transitions" with trailer lines (use your own model name in Co-Authored-By):
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9

CONSTRAINTS: pure functions, no I/O, JDK only. Never use the built-in advisor() tool.
REPORT: RESULT, CHANGED, test counts from XML, CONCERNS, BRANCH + COMMIT.
