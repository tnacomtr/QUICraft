# Brief upd-1.1: key roles, key-set fingerprint, trust snapshot, Ed25519 verify

GOAL: implement Wave 1.1 of `.agents/plans/2026-10-13-updater-runtime.md` (section "Wave 1"), with tests.
SCOPE: only new files under `updater/src/main/java/rs/sudoe/quicraft/updater/` and `updater/src/test/java/rs/sudoe/quicraft/updater/`. Do not edit existing files.
BRANCH/WORKTREE: `agent/phase4-upd-keys` in `/home/tna/QUICraft/.worktrees/phase4-upd-keys`. Use absolute paths; run Gradle only in that worktree.
READ FIRST: `updater/.../UpdateDocuments.java` (TrustedKey, Trust records), `Hex.java` (decode/encode/sha256/kid, PUBLIC_KEY_BYTES), `UpdateRejectedException.java`, `docs/updater.md` lines 360-372 (fingerprint definition).

STEPS:
1. `KeyRole.java`: `public enum KeyRole { RELEASE("release"), REVOCATION("revocation"); }` with `String wireName()` and `int bit()` (RELEASE=1, REVOCATION=2).
2. `TrustedKeyRoles.java`: `public final class` with `static Set<KeyRole> of(UpdateDocuments.TrustedKey key)` returning an unmodifiable EnumSet built from `key.roles()` wire names; unknown name -> IllegalArgumentException("unknown role") (parser already guarantees known names).
3. `KeySetFingerprint.java`: `public static String of(List<UpdateDocuments.TrustedKey> keys) throws UpdateRejectedException`.
   - Decode each pub with `Hex.decode(pub, Hex.PUBLIC_KEY_BYTES)` (throws on bad hex).
   - Duplicate pub -> `throw new UpdateRejectedException("duplicate key")`.
   - Sort keys by pub hex string ascending (lowercase hex string order equals unsigned byte order).
   - Byte string = for each key in order: the 32 raw pub bytes, then one byte = OR of the role bits.
   - Return `Hex.sha256(byteString)`.
4. `TrustSnapshot.java`: `public record TrustSnapshot(int epoch, String keySetFingerprint, Set<String> revokedPubs, Map<String, String> pubByKid, Map<String, Set<KeyRole>> rolesByPub)` with defensive unmodifiable copies (Set.copyOf, and maps copied into unmodifiable TreeMap; role sets copied with EnumSet.copyOf then unmodifiable).
   - `public static TrustSnapshot of(UpdateDocuments.Trust trust, Set<String> persistedRevokedPubs) throws UpdateRejectedException`: fingerprint via KeySetFingerprint.of(trust.keys()); pubByKid from each key's `kid()`->`pub()`; rolesByPub via TrustedKeyRoles.of. revokedPubs = persistedRevokedPubs (copied).
   - Methods: `boolean usable(String pub)` = pub is in rolesByPub and not in revokedPubs; `Set<String> releasePubs()` = usable pubs having RELEASE; `Set<String> revocationPubs()` = usable pubs having REVOCATION. Revoked keys are never usable even if listed in the trust file.
5. `Ed25519.java`: `public final class Ed25519` with `public static boolean verify(byte[] message, String pubHex, String sigHex)`. It must NEVER throw; any problem returns false.
   - pub = Hex.decode(pubHex, 32), sig = Hex.decode(sigHex, 64); on UpdateRejectedException return false. message null -> false.
   - Reject non-canonical S: S is sig bytes 32..63 read LITTLE-endian as an unsigned BigInteger; if S >= L return false, where L = 2^252 + 27742317777372353535851937790883648493.
   - Build key: X.509 DER = hex `302a300506032b6570032100` followed by the 32 pub bytes; `KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der))`.
   - `Signature s = Signature.getInstance("Ed25519"); s.initVerify(key); s.update(message); return s.verify(sig);` Catch `GeneralSecurityException | RuntimeException` -> false.
6. Tests (JUnit 5; style of HexTest.java):
   - `Ed25519Test`: RFC 8032 section 7.1 vectors. Use exactly these (public key / message hex / signature):
     TEST 1: pub d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a, message "" (empty), sig e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b
     TEST 2: pub 3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c, message 72, sig 92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00
     TEST 3: pub fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025, message af82, sig 6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a
     If any vector fails, DO NOT change the vector or the code to make it pass: stop and report it as a concern with the exact output.
     Negative cases: flipped bit in message, flipped bit in sig, wrong pub (TEST 2 pub with TEST 1 sig), uppercase hex sig -> false, 63-byte sig -> false, 31-byte pub -> false, null message -> false, S replaced by S+L (compute with BigInteger, write back little-endian 32 bytes; if it overflows 32 bytes use S = L exactly, i.e. bytes of L) -> false, all-zero pub -> false (no throw).
   - `KeySetFingerprintTest`: order independence (same keys in two list orders -> same fingerprint); role sensitivity (same pub, release vs release+revocation -> different); duplicate pub rejected; bad pub hex rejected; a known-answer test: compute the expected value inside the test independently with MessageDigest over the bytes you assemble by hand for two keys (TEST 1 and TEST 2 pubs), and compare.
   - `TrustSnapshotTest`: revoked pub excluded from releasePubs/revocationPubs and usable() false; unknown pub not usable; pubByKid maps the parser-style kid (use Hex.kid(pub) to build TrustedKey kids); collections unmodifiable.
   - Build Trust records directly with `new UpdateDocuments.Trust(...)` and `new UpdateDocuments.TrustedKey(Hex.kid(pub), pub, List.of("release"))`.
7. Run `/home/tna/QUICraft/.worktrees/phase4-upd-keys/gradlew -p /home/tna/QUICraft/.worktrees/phase4-upd-keys :updater:test :updater:check --rerun-tasks --no-build-cache`; green.
8. Commit on the branch (git -C <worktree>) with message "updater: key roles, key-set fingerprint, trust snapshot, Ed25519 verify" and trailer lines:
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9

CONSTRAINTS: JDK only, no dependencies; no key generation in main code; no network. Never use the built-in advisor() tool.
DONE WHEN: green, committed.
REPORT: RESULT, CHANGED, test counts (from test-results XML), the three RFC vectors' pass/fail, CONCERNS, BRANCH + COMMIT.
