# Brief upd-1.2: signer evidence, authenticated documents, key-set-change authorization

GOAL: Wave 1.2 of `.agents/plans/2026-10-13-updater-runtime.md` (read "Security invariants" 1-3 and 10, and "Wave 1" contracts) with tests.
SCOPE: new files only under `updater/src/main/java/rs/sudoe/quicraft/updater/` and `updater/src/test/java/rs/sudoe/quicraft/updater/` (test resources allowed under `updater/src/test/resources/`). Do not edit existing files.
BRANCH/WORKTREE: `agent/phase4-upd-signers` in `/home/tna/QUICraft/.worktrees/phase4-upd-signers`. Absolute paths; Gradle only there.
READ FIRST: `DocumentParser.java` (parseSignatures, parseRelease, parseRevocations), `UpdateDocuments.java`, `Hex.java`, `Ed25519.java`, `TrustSnapshot.java`, `KeyRole.java`, `StateRecords.java` (PersistedTrust), `docs/updater.md` lines 394-402 (key-set change rule) and the `.sig` envelope section (grep "payloadSha256").

STEPS:
1. `VerifiedSigner(String kid, String pub, Set<KeyRole> roles)` record (roles copied unmodifiable). `VerifiedSigners(List<VerifiedSigner> signers)` record with List.copyOf, `boolean hasRelease()`, `boolean hasRevocation()`, `boolean containsPub(String pub)`.
2. `EnvelopeStatus { VERIFIED, NO_VALID_SIGNER, TORN }`; `EnvelopeVerification(EnvelopeStatus status, VerifiedSigners signers)` (signers empty unless VERIFIED).
3. `SignatureVerifier.check(byte[] documentBytes, UpdateDocuments.SignatureEnvelope envelope, TrustSnapshot snapshot) -> EnvelopeVerification`:
   - If `Hex.sha256(documentBytes)` != `envelope.payloadSha256()` -> TORN.
   - For each entry: pub = snapshot.pubByKid().get(entry.kid()); skip if null or !snapshot.usable(pub); if `Ed25519.verify(documentBytes, pub, entry.sig())` add `new VerifiedSigner(entry.kid(), pub, snapshot.rolesByPub().get(pub))`. A failing entry never removes a valid one. Keep every valid one.
   - None valid -> NO_VALID_SIGNER, else VERIFIED.
4. `VerificationOutcome { VERIFIED, TORN, BAD_SIGNATURE, MALFORMED }`; `VerifiedDocument<T>(T document, byte[] exactBytes, VerifiedSigners signers, TrustSnapshot snapshot)` (clone exactBytes in constructor and in accessor - override `exactBytes()` to return a clone); `DocumentVerification<T>(VerificationOutcome outcome, VerifiedDocument<T> verified)` where verified is non-null iff outcome == VERIFIED (enforce in constructor).
5. `AuthenticatedDocuments`:
   - `static DocumentVerification<UpdateDocuments.ReleaseManifest> verifyRelease(byte[] documentBytes, byte[] sigBytes, TrustSnapshot snapshot)`
   - `static DocumentVerification<UpdateDocuments.Revocations> verifyRevocations(byte[] documentBytes, byte[] sigBytes, TrustSnapshot snapshot)`
   - Order: parse envelope with DocumentParser.parseSignatures (UpdateRejectedException -> MALFORMED); SignatureVerifier.check; TORN -> TORN; NO_VALID_SIGNER -> BAD_SIGNATURE; require role (release manifest: signers.hasRelease(), revocations: signers.hasRevocation()) else BAD_SIGNATURE; then parse the document (UpdateRejectedException -> MALFORMED); return VERIFIED with a VerifiedDocument holding a copy of documentBytes and ALL verified signers. Never return a document otherwise. Never throw on bad input (null bytes -> MALFORMED).
6. `KeySetChangeAuthorization.check(VerifiedDocument<UpdateDocuments.ReleaseManifest> manifest, String stagedKeySetFingerprint, StateRecords.PersistedTrust persisted, TrustSnapshot current) -> boolean`:
   - false if manifest.snapshot() is not equal to `current` (signer evidence bound to one snapshot, invariant 2).
   - true if stagedKeySetFingerprint equals persisted.keySetFingerprint().
   - otherwise true only if some signer in manifest.signers() has pub equal to persisted.revocationPub(), current.usable(that pub) is true, and its roles contain REVOCATION. Javadoc citing docs/updater.md 394-402 and stating no flag waives it.
7. Test support (test sources only): `TestKeys.java` with a big comment: "THROWAWAY TEST KEYS. The private seeds are the published RFC 8032 section 7.1 TEST 1 and TEST 2 seeds. Never use for releases; owner keys are generated offline later." Seeds: TEST 1 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60 (pub d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a); TEST 2 4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb (pub 3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c). Sign with JDK: PKCS#8 DER = hex `302e020100300506032b657004220420` + 32 seed bytes, `KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der))`, `Signature.getInstance("Ed25519")`. Add a self-test that TestKeys' pub for each seed verifies via Ed25519.verify. Helper to build a `.sig` JSON envelope for given bytes and (kid, sig) entries: copy the exact envelope JSON shape from DocumentParserTest / docs (find a valid example there; do not guess field names).
   Build release-manifest and revocations JSON fixtures by copying a VALID example from DocumentParserTest.java (it already parses) - reuse its exact field set.
8. Tests: torn vs bad signature vs no valid signer vs malformed envelope vs malformed document; unknown kid + valid; invalid sig + valid sig both retained only valid; both valid retained; revoked pub ignored; release-only signer on revocations -> BAD_SIGNATURE; revocation-only signer on manifest -> BAD_SIGNATURE; returned exactBytes are a copy; KeySetChangeAuthorization: unchanged fingerprint true with release-only signer; changed fingerprint + release-only false; changed + release + current backup true; one backup key holding both roles satisfies both; backup pub revoked in current snapshot false; manifest verified under a different snapshot false.
9. Run `<worktree>/gradlew -p <worktree> :updater:test :updater:check --rerun-tasks --no-build-cache`; green. Commit with message "updater: signer evidence and authenticated documents" and trailer lines:
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9

CONSTRAINTS: JDK only; no private key material in main sources; only the RFC seeds in test sources. No network. Never use the built-in advisor() tool.
REPORT: RESULT, CHANGED, test counts from XML, CONCERNS, BRANCH + COMMIT.
