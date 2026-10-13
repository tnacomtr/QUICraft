# Brief upd-1H: Wave 1 security hardening (three low findings)

GOAL: fix three low findings from the Wave 1 security review, with tests.
SCOPE: `updater/src/main/java/rs/sudoe/quicraft/updater/` and its test dir: TrustTransition.java, VerifiedDocument.java, VerifiedSigner.java, VerifiedSigners.java, DocumentVerification.java, EnvelopeVerification.java, DocumentParser.java (parseTrust only), new Ed25519Points.java, and tests. Nothing else.
BRANCH/WORKTREE: `agent/phase4-upd-harden` in `/home/tna/QUICraft/.worktrees/phase4-upd-harden`. Gradle and git only from there, relative paths (`./gradlew`, `git`).

FIX 1 (TrustTransition.java:122-224): `apply(d, trust, current)` must not trust a caller-built decision. At the top of apply, recompute `TrustDecision expected = classify(current == null ? null : current.state().persistedTrust(), trust)` and throw `IllegalArgumentException("decision does not match trust and state")` unless `expected.equals(d)`. Keep all existing checks. Tests: a hand-built REVOCATION_KEY_REPLACEMENT decision with an empty newlyRevokedPubs is refused; a decision classified against an older state then applied to a changed state (different persisted epoch) is refused; existing tests still pass.

FIX 2 (proof records): only verification code may create proof objects. Convert `VerifiedDocument`, `VerifiedSigner`, `VerifiedSigners`, `DocumentVerification`, `EnvelopeVerification` from public records into `public final class`es with the SAME accessor method names (e.g. `document()`, `exactBytes()`, `signers()`, `snapshot()`, `outcome()`, `verified()`, `status()`, `kid()`, `pub()`, `roles()`, `hasRelease()`...), package-private constructors, and correct `equals`/`hashCode`/`toString` (exactBytes compared with Arrays.equals; toString must not print bytes). Keep defensive copies and the existing constructor invariants. Java records cannot have a less-accessible canonical constructor, which is why they become classes. Fix any compile errors in tests (same package, so package-private constructors still work there). Add a short javadoc line: "Only AuthenticatedDocuments and SignatureVerifier create instances."

FIX 3 (small-order / non-canonical trusted keys): new `final class Ed25519Points` (package-private) with `static boolean isCanonicalPrimeOrderCandidate(byte[] pub32)` returning false when: y >= p; the point does not decode; x == 0 with sign bit set; or [8]A is the identity (small order). Use BigInteger math on edwards25519:
- p = 2^255 - 19; d = -121665 * inverse(121666) mod p; sqrt(-1) I = 2^((p-1)/4) mod p.
- y = little-endian 32 bytes with the top bit (bit 255) cleared; sign = that top bit.
- decode: u = y^2 - 1, v = d*y^2 + 1 (mod p); x = u * v^3 * (u * v^7)^((p-5)/8) mod p; if v*x^2 == u ok; else if v*x^2 == -u (mod p) then x = x*I; else not on curve. If x == 0 and sign == 1 -> reject. If (x mod 2) != sign then x = p - x.
- add (twisted Edwards a=-1, complete since d non-square): x3 = (x1*y2 + y1*x2) / (1 + d*x1*x2*y1*y2), y3 = (y1*y2 + x1*x2) / (1 - d*x1*x2*y1*y2), division = multiply by modInverse mod p.
- [8]A = double three times (add point to itself). Identity is (0, 1).
In `DocumentParser.parseTrust`, after decoding each pub, reject the trust file (`UpdateRejectedException`, existing fixed-message style) if `!Ed25519Points.isCanonicalPrimeOrderCandidate(pub)`. Also call it in `Ed25519.verify` and return false when it fails (defence in depth).
Tests (Ed25519PointsTest): RFC 8032 TEST 1/2/3 pubs (d75a98..., 3d4017..., fc51cd...) accepted; identity `01` followed by 31 zero bytes rejected; all-zero 32 bytes (y=0, order 4) rejected; y = p-1 i.e. `ec` followed by 30 `ff` bytes then `7f` rejected (order 2); y = p (`ed` + 30×`ff` + `7f`) rejected as non-canonical; y = 1 with sign bit (`01` + 30×`00` + `80`) rejected; a y that is not on the curve: find one by trying y = 2, 3, ... in the test and assert at least one is rejected as "does not decode" (assert via a package-private `decodes(byte[])` helper). Also a parseTrust test: a trust file whose key pub is the all-zero key is rejected; a valid one still parses.

Run `./gradlew :updater:test :updater:check --rerun-tasks --no-build-cache`; green. Small commits, trailer (your own model name):
   Co-Authored-By: <your model> <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01CbMd2TPdZhSiL4ecdkFEH9

CONSTRAINTS: JDK only. Never use the built-in advisor() tool. Nothing outside the worktree.
REPORT: RESULT, CHANGED, test counts from XML, deviations, CONCERNS, BRANCH + COMMITS.
